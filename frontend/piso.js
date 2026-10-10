
const titulo = document.getElementById("titulo");
const campoOrden = document.getElementById("campoOrden");
const campoDestino = document.getElementById("campoDestino");
const campoPieza = document.getElementById("campoPieza");
const campoMotivo = document.getElementById("campoMotivo");
const etiquetaCantidad = document.getElementById("etiquetaCantidad");
const mensaje = document.getElementById("mensaje");

let operacion = "retirar";

function cambiarOperacion(tipo) {
    operacion = tipo;
    mensaje.textContent = "";

    campoOrden.hidden = tipo !== "retirar";
    campoDestino.hidden = tipo !== "transferir";
    campoPieza.hidden = tipo === "contar";
    campoMotivo.hidden = tipo !== "contar";

    document.getElementById("orden").required =
        tipo === "retirar";

    document.getElementById("destino").required =
        tipo === "transferir";

    document.getElementById("pieza").required =
        tipo !== "contar";

    document.getElementById("motivo").required =
        tipo === "contar";

    const nombres = {
        retirar: ["Retirar pieza", "Cantidad a retirar"],
        transferir: ["Transferir piezas", "Cantidad a transferir"],
        contar: ["Conteo de inventario", "Cantidad física encontrada"]
    };

    titulo.textContent = nombres[tipo][0];
    etiquetaCantidad.textContent = nombres[tipo][1];
}

let html5QrCode = null;

function iniciarEscaner() {
    const contenedorLector = document.getElementById("lector-camara");
    const btnCerrar = document.getElementById("btnCerrarCamara");
    
    contenedorLector.style.display = "block";
    btnCerrar.style.display = "block";

    if (!html5QrCode) {
        html5QrCode = new Html5Qrcode("lector-camara");
    }

    // Configuración para usar la cámara trasera por defecto
    const config = { fps: 10, qrbox: { width: 250, height: 150 } };

    html5QrCode.start(
        { facingMode: "environment" }, 
        config,
        (decodedText, decodedResult) => {
            // Éxito al leer el código de barras
            manejarCodigoLeido(decodedText);
            detenerEscaner();
        },
        (errorMessage) => {
            // Errores de escaneo por fotograma (se pueden ignorar para que no sature la consola)
        }
    ).catch(err => {
        mensaje.textContent = "Error al iniciar la cámara. Asegúrate de usar HTTPS o localhost.";
        console.error(err);
    });
}

function detenerEscaner() {
    if (html5QrCode && html5QrCode.isScanning) {
        html5QrCode.stop().then(() => {
            document.getElementById("lector-camara").style.display = "none";
            document.getElementById("btnCerrarCamara").style.display = "none";
        }).catch(err => {
            console.error("Error al detener la cámara", err);
        });
    } else {
        document.getElementById("lector-camara").style.display = "none";
        document.getElementById("btnCerrarCamara").style.display = "none";
    }
}

function manejarCodigoLeido(codigo) {
    codigo = codigo.trim();
    mensaje.textContent = `Código leído con éxito: ${codigo}`;

    // Lógica inteligente según el formato o la operación actual
    if (codigo.startsWith("U-")) {
        // Si empieza con U-, asumimos que es una ubicación
        document.getElementById("ubicacion").value = codigo;
    } else {
        // Si no es ubicación, lo mandamos al campo de pieza (si está visible) o de orden
        if (!campoPieza.hidden) {
            document.getElementById("pieza").value = codigo;
        } else if (!campoOrden.hidden) {
            document.getElementById("orden").value = codigo;
        }
    }
}

document.getElementById("formOperacion")
    .addEventListener("submit", function(event) {
        event.preventDefault();

        const ubicacion =
            document.getElementById("ubicacion").value.trim();

        const cantidad =
            Number(document.getElementById("cantidad").value);

        const pieza =
            document.getElementById("pieza").value.trim();

        const orden =
            document.getElementById("orden").value.trim();

        const destino =
            document.getElementById("destino").value.trim();

        const motivo =
            document.getElementById("motivo").value;

        if (!ubicacion || cantidad < 1 ||
            !Number.isInteger(cantidad)) {
            mensaje.textContent =
                "Revisa la ubicación y la cantidad.";
            return;
        }

        if (operacion === "retirar" && (!pieza || !orden)) {
            mensaje.textContent =
                "Introduce la pieza y la orden de trabajo.";
            return;
        }

        if (operacion === "transferir" &&
            (!pieza || !destino || destino === ubicacion)) {
            mensaje.textContent =
                "Introduce la pieza y una ubicación de destino diferente.";
            return;
        }

        if (operacion === "contar" && !motivo) {
            mensaje.textContent =
                "Selecciona el motivo del conteo.";
            return;
        }

        let resumen = "";

        if (operacion === "retirar") {
            resumen = `Retiro de ${cantidad} unidad(es) de ${pieza}, ` +
                      `para la orden ${orden}, desde ${ubicacion}.`;
        } else if (operacion === "transferir") {
            resumen = `Transferencia de ${cantidad} unidad(es) de ${pieza}, ` +
                      `desde ${ubicacion} hasta ${destino}.`;
        } else {
            resumen = `Conteo de ${cantidad} unidad(es) en ${ubicacion}. ` +
                      `Motivo: ${motivo}.`;
        }

        mensaje.textContent =
            resumen + " Operación validada en la demostración.";
    });
