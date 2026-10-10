// Datos de ejemplo para trabajar sin backend
const datosKardexEjemplo = {
    "196": {
        id: 196,
        name: "RODAMIENTO 7014",
        sku: "7014 CTA/P4",
        on_hand: 5,
        reserved: 2,
        available: 3,
        ledger: [
            { fecha: "09/10/2026 18:30", tipo: "issue", ubicacion: "U-104", cantidad: -2, referencia: "Orden R061026012", saldo: 5 },
            { fecha: "09/10/2026 10:15", tipo: "receipt", ubicacion: "U-100", cantidad: 5, referencia: "Compra PO-9912", saldo: 7 },
            { fecha: "08/10/2026 14:00", tipo: "transfer_out", ubicacion: "U-100", cantidad: -3, referencia: "Transferencia a U-104", saldo: 2 },
            { fecha: "08/10/2026 14:00", tipo: "transfer_in", ubicacion: "U-104", cantidad: 3, referencia: "Transferencia desde U-100", saldo: 5 }
        ]
    }
};

window.onload = function() {
    // Permite abrir directamente con parámetros por URL, ej: kardex.html?part=196[cite: 10, 11]
    const params = new URLSearchParams(window.location.search);
    const partParam = params.get("part");
    if (partParam && datosKardexEjemplo[partParam]) {
        document.getElementById("inputBusquedaPieza").value = partParam;
        mostrarKardex(partParam);
    }
};

function buscarPiezaKardex() {
    const query = document.getElementById("inputBusquedaPieza").value.trim();
    if (datosKardexEjemplo[query]) {
        mostrarKardex(query);
    } else {
        alert("No se encontró historial para esa pieza en los datos locales. Prueba buscando '196'.");
    }
}

function mostrarKardex(partId) {
    const pieza = datosKardexEjemplo[partId];
    if (!pieza) return;

    document.getElementById("estadoVacioKardex").style.display = "none";
    document.getElementById("panelKardex").style.display = "block";

    document.getElementById("nombrePiezaKardex").textContent = pieza.name;
    document.getElementById("skuPiezaKardex").textContent = `SKU: ${pieza.sku}`;
    document.getElementById("valExistencia").textContent = pieza.on_hand;
    document.getElementById("valReservado").textContent = pieza.reserved;
    document.getElementById("valDisponible").textContent = pieza.available;

    const tbody = document.getElementById("tablaMovimientosBody");
    tbody.innerHTML = "";

    pieza.ledger.forEach(mov => {
        let tipoTexto = "";
        let claseTipo = "";

        // Traducción de términos en inglés de la API según la guía[cite: 10]
        switch(mov.tipo) {
            case "receipt":
                tipoTexto = "Entrada";
                claseTipo = "entrada";
                break;
            case "issue":
                tipoTexto = "Salida";
                claseTipo = "salida";
                break;
            case "transfer_out":
                tipoTexto = "Transferencia (sale)";
                claseTipo = "transferencia";
                break;
            case "transfer_in":
                tipoTexto = "Transferencia (entra)";
                claseTipo = "transferencia";
                break;
            case "adjustment":
                tipoTexto = "Ajuste";
                claseTipo = "ajuste";
                break;
            default:
                tipoTexto = mov.tipo;
                claseTipo = "ajuste";
        }

        const esPositivo = mov.cantidad > 0;
        const colorCantidad = esPositivo ? "#15803d" : "#b91c1c";
        const signoStr = esPositivo ? `+${mov.cantidad}` : `${mov.cantidad}`;

        // Enlace automático a orden.html si la referencia contiene una orden
        let refHTML = mov.referencia;
        if (mov.referencia.includes("Orden ")) {
            const ordenCode = mov.referencia.replace("Orden ", "");
            refHTML = `<a href="orden.html?code=${ordenCode}" class="chip-orden">${mov.referencia}</a>`;
        }

        const tr = document.createElement("tr");
        tr.innerHTML = `
            <td style="font-size: 13px; color: #4b5563;">${mov.fecha}</td>
            <td><span class="badge-tipo ${claseTipo}">${tipoTexto}</span></td>
            <td><span class="sku-mono">${mov.ubicacion}</span></td>
            <td class="derecha" style="font-weight: bold; color: ${colorCantidad};">${signoStr}</td>
            <td>${refHTML}</td>
            <td class="derecha" style="font-weight: bold;">${mov.saldo}</td>
        `;
        tbody.appendChild(tr);
    });
}