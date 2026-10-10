// =====================================================================
// orden.js — Orden de trabajo conectada a la API
// Materiales por línea: /work-orders/{code}/materials
// Órdenes rápidas: /work-orders?open=true
// Necesita api.js cargado antes.
// =====================================================================

let ordenActual = null;

window.onload = function () {
    cargarSelectorRapido();

    // orden.html?code=R061026012 abre directo esa orden
    const codeParam = new URLSearchParams(window.location.search).get("code");
    if (codeParam) {
        document.getElementById("inputCodigoOrden").value = codeParam;
        mostrarOrden(codeParam.toUpperCase());
    }

    // Enter en el buscador = Consultar
    document.getElementById("inputCodigoOrden").addEventListener("keydown", e => {
        if (e.key === "Enter") buscarOrden();
    });

    // Para la demo: la orden abierta se actualiza sola cada 5 segundos.
    setInterval(() => { if (ordenActual) mostrarOrden(ordenActual, true); }, 5000);
};

async function cargarSelectorRapido() {
    const contenedor = document.getElementById("selectorRapidoOrdenes");
    contenedor.innerHTML = "<span style='font-size:12px; color:#687280; align-self:center;'>Órdenes abiertas:</span>";
    try {
        const { items } = await apiGet("/work-orders?open=true");
        // Las órdenes reales del taller primero (las de las pruebas automáticas traen ids enormes).
        const reales = items.filter(o => o.work_order_id < 100000000);
        const lista = (reales.length ? reales : items).slice(0, 8);
        lista.forEach(o => {
            const btn = document.createElement("button");
            btn.type = "button";
            btn.className = "chip-selector-orden";
            btn.textContent = o.code;
            btn.onclick = () => {
                document.getElementById("inputCodigoOrden").value = o.code;
                mostrarOrden(o.code);
            };
            contenedor.appendChild(btn);
        });
    } catch (e) {
        contenedor.innerHTML += `<span style='font-size:12px; color:#b91c1c;'>${esc(e.message)}</span>`;
    }
}

function buscarOrden() {
    const codigo = document.getElementById("inputCodigoOrden").value.trim().toUpperCase();
    if (!codigo) return;
    mostrarOrden(codigo);
}

/** Calcula el estado de una línea de material. */
function estadoLinea(line) {
    if (line.part_id === null) return ["Sin identificar", "sin-identificar", false];
    if (line.required === null) return ["Sin cantidad", "sin-identificar", false];
    if (line.issued >= line.required) return ["Entregado", "entregado", true];
    if (line.reserved + line.issued >= line.required) return ["Listo para retirar", "listo", false];
    if (line.reserved > 0 || line.issued > 0) return ["Parcial", "parcial", false];
    return ["Faltante", "faltante", false];
}

async function mostrarOrden(codigo, silencioso = false) {
    let orden;
    try {
        orden = await apiGet(`/work-orders/${encodeURIComponent(codigo)}/materials`);
    } catch (e) {
        if (!silencioso) alert(e.status === 404 ? `No existe la orden ${codigo}.` : e.message);
        return;
    }
    ordenActual = orden.code;

    document.getElementById("estadoVacioOrden").style.display = "none";
    document.getElementById("panelOrden").style.display = "block";
    document.getElementById("tituloCodigoOrden").textContent = orden.code;
    document.getElementById("modeloEquipoOrden").textContent =
        `${orden.lines.length} ${orden.lines.length === 1 ? "línea" : "líneas"} de material · actualizado ${new Date().toLocaleTimeString("es-MX")}`;

    const tbody = document.getElementById("tablaMaterialesBody");
    if (!orden.lines.length) {
        filaMensaje(tbody, 6, "Esta orden todavía no tiene materiales aprobados.");
    } else {
        tbody.innerHTML = orden.lines.map(line => {
            const [texto, clase] = estadoLinea(line);
            const pieza = line.part_id === null
                ? '<span style="font-size:12px; color:#687280;">Sin identificar</span>'
                : `<a href="kardex.html?part=${line.part_id}" class="sku-mono" style="font-size:12px; color:#687280;">Pieza ${line.part_id}</a>`;
            return `
                <tr>
                    <td><strong>${esc(line.name)}</strong><br>${pieza}</td>
                    <td class="derecha">${line.required ?? 'Sin cantidad'}</td>
                    <td class="derecha">${line.reserved}</td>
                    <td class="derecha">${line.issued}</td>
                    <td class="derecha" style="font-weight:bold; color:${line.missing > 0 ? '#b91c1c' : 'inherit'};">${line.missing ?? '--'}</td>
                    <td><span class="pastilla ${clase}">${texto}</span></td>
                </tr>`;
        }).join("");
    }

    const completas = orden.lines.filter(l => estadoLinea(l)[2]).length;
    const total = orden.lines.length;
    document.getElementById("textoAvance").textContent = `${completas} de ${total} líneas completas`;
    document.getElementById("barraProgresoInterna").style.width = `${total ? (completas / total) * 100 : 0}%`;
}
