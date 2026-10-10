// =====================================================================
// almacen.js — Panel de almacén conectado a la API
// Existencias (/stock), faltantes (/shortages), por comprar (/reorder-suggestions)
// y recepciones por revisar (/unmatched-receipts + resolve).
// Necesita api.js cargado antes.
// =====================================================================

let sugerenciasActuales = [];
let codigosOrden = {};          // work_order_id -> código (R...)
let temporizadorBusqueda = null;

// ---------- Pestañas (igual que antes) ----------
function cambiarPestaña(nombreTab) {
    document.querySelectorAll('.seccion-tab').forEach(sec => sec.style.display = 'none');
    document.querySelectorAll('.btn-pestana').forEach(btn => btn.classList.remove('activa'));

    const posiciones = { existencias: 1, faltantes: 2, comprar: 3, recepciones: 4 };
    document.getElementById(`seccion-${nombreTab}`).style.display = 'block';
    document.querySelector(`.btn-pestana:nth-child(${posiciones[nombreTab]})`).classList.add('activa');
}

// ---------- 1. Existencias ----------
async function cargarExistencias() {
    const tbody = document.getElementById('tablaExistenciasBody');
    const q = document.getElementById('buscadorExistencias').value.trim();
    try {
        const { items } = await apiGet(`/stock?q=${encodeURIComponent(q)}`);
        if (!items.length) {
            filaMensaje(tbody, 5, q ? `No hay piezas que coincidan con "${q}".` : "Todavía no hay existencias.");
            return;
        }
        tbody.innerHTML = items.map(p => `
            <tr>
                <td><a href="kardex.html?part=${p.part_id}" style="color:inherit;"><strong>${esc(p.name)}</strong></a></td>
                <td><span class="sku-mono">${p.sku ? esc(p.sku) : 'P-' + p.part_id}</span></td>
                <td class="derecha">${p.on_hand}</td>
                <td class="derecha">${p.reserved}</td>
                <td class="derecha ${p.available > 0 ? 'texto-verde' : 'texto-rojo'}">${p.available}</td>
            </tr>`).join('');
    } catch (e) {
        filaMensaje(tbody, 5, e.message, '#b91c1c');
    }
}

// Se llama al escribir en el buscador: espera 300 ms para no llamar a la API en cada tecla.
function filtrarExistencias() {
    clearTimeout(temporizadorBusqueda);
    temporizadorBusqueda = setTimeout(cargarExistencias, 300);
}

// ---------- 2. Faltantes ----------
async function cargarFaltantes() {
    const tbody = document.getElementById('tablaFaltantesBody');
    try {
        const { items } = await apiGet('/shortages');
        document.getElementById('valFaltantes').textContent = items.length;
        if (!items.length) {
            filaMensaje(tbody, 4, 'No hay faltantes abiertos. 🎉');
            return;
        }
        tbody.innerHTML = items.map(f => `
            <tr>
                <td><a href="orden.html?code=${encodeURIComponent(f.work_order_code)}" class="chip-orden">${esc(f.work_order_code)}</a></td>
                <td>${esc(f.name)}${f.part_id === null ? ' <span style="color:#687280;">(sin identificar)</span>' : ''}</td>
                <td class="derecha texto-rojo">${f.missing_quantity ?? 'Sin cantidad'}</td>
                <td>${hace(f.opened_at)}</td>
            </tr>`).join('');
    } catch (e) {
        filaMensaje(tbody, 4, e.message, '#b91c1c');
    }
}

// ---------- 3. Por comprar ----------
async function cargarCodigosOrden() {
    try {
        const { items } = await apiGet('/work-orders');
        codigosOrden = {};
        items.forEach(o => codigosOrden[o.work_order_id] = o.code);
    } catch (e) {
        // Si no se pueden traer los códigos, se muestran los números de orden.
    }
}

function chipOrden(id) {
    const codigo = codigosOrden[id];
    return codigo
        ? `<a href="orden.html?code=${encodeURIComponent(codigo)}" class="chip-orden">${esc(codigo)}</a>`
        : `<span class="chip-orden">#${id}</span>`;
}

async function cargarSugerencias() {
    const tbody = document.getElementById('tablaComprarBody');
    try {
        const { items } = await apiGet('/reorder-suggestions');
        sugerenciasActuales = items;
        document.getElementById('valComprar').textContent = items.length;
        if (!items.length) {
            filaMensaje(tbody, 3, 'No hay sugerencias de compra.');
            return;
        }
        tbody.innerHTML = items.map(s => `
            <tr>
                <td><strong>${esc(s.name)}</strong><br><span class="sku-mono">${s.sku ? esc(s.sku) : 'P-' + s.part_id}</span></td>
                <td class="derecha">${s.suggested_quantity}</td>
                <td>${s.work_order_ids.map(chipOrden).join(' ')}</td>
            </tr>`).join('');
    } catch (e) {
        filaMensaje(tbody, 3, e.message, '#b91c1c');
    }
}

function copiarListaCompras() {
    if (!sugerenciasActuales.length) {
        alert('No hay sugerencias de compra para copiar.');
        return;
    }
    const lineas = sugerenciasActuales.map(s => {
        const ordenes = s.work_order_ids.map(id => codigosOrden[id] || `#${id}`).join(', ');
        return `- ${s.name} (${s.sku || 'P-' + s.part_id}): ${s.suggested_quantity} pza(s) — órdenes: ${ordenes}`;
    });
    const texto = `Solicitud de compra\n\n${lineas.join('\n')}`;
    navigator.clipboard.writeText(texto)
        .then(() => alert('Lista copiada. Pégala en el correo a Compras.'))
        .catch(() => prompt('Copia la lista:', texto));
}

// ---------- 4. Recepciones por revisar ----------
async function cargarRecepciones() {
    const tbody = document.getElementById('tablaRecepcionesBody');
    try {
        const { items } = await apiGet('/unmatched-receipts');
        document.getElementById('valRecepciones').textContent = items.length;
        if (!items.length) {
            filaMensaje(tbody, 5, 'No hay recepciones por revisar.');
            return;
        }
        tbody.innerHTML = items.map(r => `
            <tr>
                <td><span class="sku-mono">${esc(r.part_number)}</span></td>
                <td>${esc(r.description)}</td>
                <td class="derecha">${r.quantity}</td>
                <td>${fechaCorta(r.received_at)}${r.work_order_code ? ' / ' + esc(r.work_order_code) : ''}</td>
                <td><button class="secundario" data-id="${r.id}" data-parte="${esc(r.part_number)}"
                        onclick="relacionar(this.dataset.id, this.dataset.parte)">Relacionar</button></td>
            </tr>`).join('');
    } catch (e) {
        filaMensaje(tbody, 5, e.message, '#b91c1c');
    }
}

/** Relaciona a mano una recepción con una pieza del catálogo y la da de entrada en U-100. */
async function relacionar(idRecepcion, numeroParte) {
    const texto = prompt(`¿Con qué pieza relacionamos "${numeroParte}"?\nEscribe SKU, nombre o ID de pieza:`);
    if (!texto || !texto.trim()) return;

    try {
        let pieza;
        if (/^\d+$/.test(texto.trim())) {
            pieza = { part_id: Number(texto.trim()), name: `Pieza ${texto.trim()}` };
        } else {
            const { items } = await apiGet(`/parts?q=${encodeURIComponent(texto.trim())}`);
            if (!items.length) {
                alert('No encontré piezas con ese texto.');
                return;
            }
            if (items.length === 1) {
                pieza = items[0];
            } else {
                const opciones = items.slice(0, 10)
                    .map((p, i) => `${i + 1}. ${p.name} (${p.sku || 'P-' + p.part_id})`).join('\n');
                const n = Number(prompt(`Hay varias piezas. Escribe el número:\n${opciones}`));
                pieza = items[n - 1];
                if (!pieza) return;
            }
        }

        if (!confirm(`¿Relacionar "${numeroParte}" con ${pieza.name}?\nEntrará al inventario en U-100.`)) return;

        await apiPost(`/unmatched-receipts/${idRecepcion}/resolve`, { part_id: pieza.part_id });
        alert('Listo: la recepción entró al inventario.');
        cargarRecepciones();
        cargarExistencias();
        cargarFaltantes();
    } catch (e) {
        alert(e.message);
    }
}

// ---------- Inicio y refresco automático ----------
async function cargarTodo() {
    await cargarCodigosOrden();
    await Promise.allSettled([
        cargarExistencias(),
        cargarFaltantes(),
        cargarSugerencias(),
        cargarRecepciones()
    ]);
}

window.onload = function () {
    cargarTodo();
    // Para la demo: se actualiza solo cada 10 segundos (reservas, faltantes y compras que llegan).
    setInterval(cargarTodo, 10000);
};
