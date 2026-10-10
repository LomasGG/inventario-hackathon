// =====================================================================
// kardex.js — Kardex de una pieza conectado a la API
// Saldo: /parts/{id}/availability · Movimientos: /parts/{id}/ledger
// Búsqueda por nombre o SKU: /parts?q= · Códigos de ubicación: /locations
// Necesita api.js cargado antes.
// =====================================================================

let ubicaciones = {};   // location_id -> código (U-101)

window.onload = async function () {
    try {
        const { items } = await apiGet("/locations");
        items.forEach(u => ubicaciones[u.location_id] = u.code);
    } catch (e) {
        // Sin códigos se muestra el número de ubicación.
    }

    document.getElementById("inputBusquedaPieza").addEventListener("keydown", e => {
        if (e.key === "Enter") buscarPiezaKardex();
    });

    // kardex.html?part=196 abre directo esa pieza
    const partParam = new URLSearchParams(window.location.search).get("part");
    if (partParam) {
        document.getElementById("inputBusquedaPieza").value = partParam;
        mostrarKardex(Number(partParam));
    }
};

async function buscarPiezaKardex() {
    const texto = document.getElementById("inputBusquedaPieza").value.trim();
    if (!texto) return;

    // ID directo (196) o etiqueta de pieza sin SKU (P-24)
    const id = /^\d+$/.test(texto) ? Number(texto)
             : /^P-\d+$/i.test(texto) ? Number(texto.slice(2)) : null;
    if (id !== null) {
        mostrarKardex(id);
        return;
    }

    try {
        const { items } = await apiGet(`/parts?q=${encodeURIComponent(texto)}`);
        if (!items.length) {
            alert(`No encontré piezas con "${texto}".`);
        } else if (items.length === 1) {
            mostrarKardex(items[0].part_id, items[0]);
        } else {
            mostrarOpciones(items);
        }
    } catch (e) {
        alert(e.message);
    }
}

/** Si la búsqueda trae varias piezas, se muestran para elegir una. */
function mostrarOpciones(items) {
    document.getElementById("panelKardex").style.display = "none";
    const vacio = document.getElementById("estadoVacioKardex");
    vacio.style.display = "block";
    vacio.innerHTML = `
        <p style="color:#687280; font-size:15px; margin-top:0;">Encontré ${items.length} piezas. Elige una:</p>
        <div style="display:flex; flex-wrap:wrap; gap:8px; justify-content:center;">
            ${items.slice(0, 20).map(p => `
                <button type="button" class="secundario" onclick="mostrarKardex(${p.part_id})">
                    ${esc(p.name)} <span class="sku-mono">(${p.sku ? esc(p.sku) : 'P-' + p.part_id})</span>
                </button>`).join("")}
        </div>`;
}

/** Busca nombre y SKU de una pieza por su id (la API de saldo no los trae). */
async function datosPieza(partId) {
    try {
        // /parts?q= busca por nombre o SKU; si no la encuentra por id, se busca en la lista de existencias.
        let { items } = await apiGet(`/parts?q=${partId}`);
        let pieza = items.find(p => p.part_id === partId);
        if (!pieza) {
            ({ items } = await apiGet(`/stock?q=`));
            pieza = items.find(p => p.part_id === partId);
        }
        return pieza || null;
    } catch (e) {
        return null;
    }
}

const TIPOS = {
    receipt:      ["Entrada", "entrada"],
    issue:        ["Salida", "salida"],
    transfer_out: ["Transferencia (sale)", "transferencia"],
    transfer_in:  ["Transferencia (entra)", "transferencia"],
    adjustment:   ["Ajuste", "ajuste"]
};

async function mostrarKardex(partId, pieza = null) {
    let saldo, kardex;
    try {
        [saldo, kardex] = await Promise.all([
            apiGet(`/parts/${partId}/availability`),
            apiGet(`/parts/${partId}/ledger`)
        ]);
    } catch (e) {
        alert(e.status === 404 ? `No existe la pieza ${partId}.` : e.message);
        return;
    }
    pieza = pieza || await datosPieza(partId);

    document.getElementById("estadoVacioKardex").style.display = "none";
    document.getElementById("panelKardex").style.display = "block";

    document.getElementById("nombrePiezaKardex").textContent = pieza ? pieza.name : `Pieza ${partId}`;
    document.getElementById("skuPiezaKardex").textContent =
        `SKU: ${pieza && pieza.sku ? pieza.sku : 'P-' + partId} · ID ${partId}`;
    document.getElementById("valExistencia").textContent = saldo.on_hand;
    document.getElementById("valReservado").textContent = saldo.reserved;
    document.getElementById("valDisponible").textContent = saldo.available;

    const tbody = document.getElementById("tablaMovimientosBody");
    if (!kardex.entries.length) {
        filaMensaje(tbody, 6, "Esta pieza todavía no tiene movimientos.");
        return;
    }

    // La API ya los manda del más reciente al más antiguo, con el saldo corrido.
    tbody.innerHTML = kardex.entries.map(mov => {
        const [tipoTexto, claseTipo] = TIPOS[mov.type] || [mov.type, "ajuste"];
        const positivo = mov.quantity > 0;
        const referencia = mov.reference || "--";
        const refHTML = esCodigoOrden(referencia)
            ? `<a href="orden.html?code=${encodeURIComponent(referencia)}" class="chip-orden">${esc(referencia)}</a>`
            : esc(referencia);
        return `
            <tr>
                <td style="font-size:13px; color:#4b5563;">${fechaCorta(mov.occurred_at)}</td>
                <td><span class="badge-tipo ${claseTipo}">${tipoTexto}</span></td>
                <td><span class="sku-mono">${esc(ubicaciones[mov.location_id] || mov.location_id)}</span></td>
                <td class="derecha" style="font-weight:bold; color:${positivo ? '#15803d' : '#b91c1c'};">${positivo ? '+' : ''}${mov.quantity}</td>
                <td>${refHTML}</td>
                <td class="derecha" style="font-weight:bold;">${mov.balance}</td>
            </tr>`;
    }).join("");
}
