// Datos de ejemplo simulados para la demo
const ordenesEjemplo = {
    "R061026012": {
        code: "R061026012",
        modelo: "GROB MSP-040 DOOSAN DNM400",
        lines: [
            { name: "RODAMIENTO 7014", sku: "7014 CTA/P4", required: 2, reserved: 2, issued: 0, missing: 0 },
            { name: "SELLO MECÁNICO", sku: "SM-2040", required: 1, reserved: 0, issued: 0, missing: 1 },
            { name: "KIT DE ORINGS", sku: null, required: null, reserved: 0, issued: 0, missing: null }
        ]
    },
    "R061026015": {
        code: "R061026015",
        modelo: "TORNO CNC HAAS ST-20",
        lines: [
            { name: "CORREA DE TRANSMISIÓN", sku: "CR-850", required: 1, reserved: 1, issued: 1, missing: 0 }
        ]
    }
};

window.onload = function() {
    cargarSelectorRapido();

    // Revisar si viene un código por parámetro en la URL (ej. orden.html?code=R061026012)
    const params = new URLSearchParams(window.location.search);
    const codeParam = params.get("code");
    if (codeParam && ordenesEjemplo[codeParam]) {
        document.getElementById("inputCodigoOrden").value = codeParam;
        mostrarOrden(codeParam);
    }
};

function cargarSelectorRapido() {
    const contenedor = document.getElementById("selectorRapidoOrdenes");
    contenedor.innerHTML = "<span style='font-size:12px; color:#687280; align-self:center;'>Órdenes rápidas:</span>";
    
    Object.keys(ordenesEjemplo).forEach(code => {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "chip-selector-orden";
        btn.textContent = code;
        btn.onclick = () => {
            document.getElementById("inputCodigoOrden").value = code;
            mostrarOrden(code);
        };
        contenedor.appendChild(btn);
    });
}

function buscarOrden() {
    const codigo = document.getElementById("inputCodigoOrden").value.trim().toUpperCase();
    if (ordenesEjemplo[codigo]) {
        mostrarOrden(codigo);
    } else {
        alert("No se encontró la orden de trabajo en los datos locales.");
    }
}

function mostrarOrden(codigo) {
    const orden = ordenesEjemplo[codigo];
    if (!orden) return;

    document.getElementById("estadoVacioOrden").style.display = "none";
    document.getElementById("panelOrden").style.display = "block";

    document.getElementById("tituloCodigoOrden").textContent = orden.code;
    document.getElementById("modeloEquipoOrden").textContent = `Modelo de equipo: ${orden.modelo}`;

    const tbody = document.getElementById("tablaMaterialesBody");
    tbody.innerHTML = "";

    let lineasCompletas = 0;
    const totalLineas = orden.lines.length;

    orden.lines.forEach(line => {
        // Calcular estado de la línea según las reglas de la guía
        let estadoHTML = "";
        let claseEstado = "";

        if (line.sku === null) {
            estadoHTML = "Sin identificar";
            claseEstado = "sin-identificar";
        } else if (line.issued >= line.required && line.required !== null) {
            estadoHTML = "Entregado";
            claseEstado = "entregado";
            lineasCompletas++;
        } else if (line.reserved >= line.required && line.required !== null) {
            estadoHTML = "Listo para retirar";
            claseEstado = "listo";
        } else if (line.reserved > 0) {
            estadoHTML = "Parcial";
            claseEstado = "parcial";
        } else {
            estadoHTML = "Faltante";
            claseEstado = "faltante";
        }

        const tr = document.createElement("tr");
        tr.innerHTML = `
            <td><strong>${line.name}</strong><br><span class="sku-mono" style="font-size: 12px; color: #687280;">${line.sku || 'Sin SKU'}</span></td>
            <td class="derecha">${line.required ?? 'Sin cantidad'}</td>
            <td class="derecha">${line.reserved}</td>
            <td class="derecha">${line.issued}</td>
            <td class="derecha" style="font-weight: bold; color: ${line.missing > 0 ? '#b91c1c' : 'inherit'};">${line.missing ?? '--'}</td>
            <td><span class="pastilla ${claseEstado}">${estadoHTML}</span></td>
        `;
        tbody.appendChild(tr);
    });

    // Actualizar barra de avance superior
    document.getElementById("textoAvance").textContent = `${lineasCompletas} de ${totalLineas} líneas completas`;
    const porcentaje = totalLineas > 0 ? (lineasCompletas / totalLineas) * 100 : 0;
    document.getElementById("barraProgresoInterna").style.width = `${porcentaje}%`;
}