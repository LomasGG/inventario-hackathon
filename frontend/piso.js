// =====================================================================
// piso.js — Pantalla de piso (celular) conectada a la API
// Escaneo: /scan?code=  ·  Operaciones: POST /issues, /transfers, /counts
// Necesita api.js y html5-qrcode cargados antes.
// =====================================================================

const titulo = document.getElementById("titulo");
const campoOrden = document.getElementById("campoOrden");
const campoDestino = document.getElementById("campoDestino");
const campoPieza = document.getElementById("campoPieza");
const campoMotivo = document.getElementById("campoMotivo");
const etiquetaCantidad = document.getElementById("etiquetaCantidad");
const mensaje = document.getElementById("mensaje");

// Quién hace la salida (el contrato pide issued_by).
const USUARIO_PISO = "tecnico-piso";

let operacion = "retirar";

function mostrarMensaje(texto, tipo = "info") {
    const colores = { info: "#374151", ok: "#15803d", error: "#b91c1c" };
    mensaje.style.color = colores[tipo];
    mensaje.textContent = texto;
}

function cambiarOperacion(tipo) {
    operacion = tipo;
    mostrarMensaje("");

    campoOrden.hidden = tipo !== "retirar";
    campoDestino.hidden = tipo !== "transferir";
    campoPieza.hidden = false;                 // las 3 operaciones necesitan la pieza
    campoMotivo.hidden = tipo !== "contar";

    document.getElementById("orden").required = tipo === "retirar";
    document.getElementById("destino").required = tipo === "transferir";
    document.getElementById("pieza").required = true;
    document.getElementById("motivo").required = tipo === "contar";

    // En un conteo se puede encontrar 0 piezas, pero el campo empieza vacío para que se escriba lo contado.
    document.getElementById("cantidad").min = tipo === "contar" ? "0" : "1";
    document.getElementById("cantidad").value = tipo === "contar" ? "" : "1";

    const nombres = {
        retirar: ["Retirar pieza", "Cantidad a retirar"],
        transferir: ["Transferir piezas", "Cantidad a transferir"],
        contar: ["Conteo de inventario", "Cantidad física encontrada"]
    };
    titulo.textContent = nombres[tipo][0];
    etiquetaCantidad.textContent = nombres[tipo][1];
}

// ---------------- Cámara ----------------
let html5QrCode = null;

function iniciarEscaner() {
    document.getElementById("lector-camara").style.display = "block";
    document.getElementById("btnCerrarCamara").style.display = "block";

    if (!html5QrCode) {
        // Las etiquetas del taller son Code 128.
        const F = Html5QrcodeSupportedFormats;
        html5QrCode = new Html5Qrcode("lector-camara", {
            formatsToSupport: [F.CODE_128, F.CODE_39, F.EAN_13, F.QR_CODE],
            experimentalFeatures: { useBarCodeDetectorIfSupported: true },
            verbose: false
        });
    }

    html5QrCode.start(
        { facingMode: "environment" },
        { fps: 10, qrbox: { width: 300, height: 120 } },
        (textoLeido) => {
            manejarCodigoLeido(textoLeido);   // primero: toma el campo que pidió la cámara
            detenerEscaner();
        },
        () => { /* fotogramas sin código: se ignoran */ }
    ).catch(err => {
        mostrarMensaje("No se pudo abrir la cámara. Se necesita HTTPS (o localhost) y dar permiso.", "error");
        console.error(err);
    });
}

function detenerEscaner() {
    // Al cerrar, la siguiente lectura vuelve a ser la general.
    campoObjetivo = null;
    const ocultar = () => {
        document.getElementById("lector-camara").style.display = "none";
        document.getElementById("btnCerrarCamara").style.display = "none";
    };
    if (html5QrCode && html5QrCode.isScanning) {
        html5QrCode.stop().then(ocultar).catch(ocultar);
    } else {
        ocultar();
    }
}

// ---------------- Botón de cámara junto a cada campo ----------------
// Cada campo se puede escribir a mano o llenar con su botón 📷.
let campoObjetivo = null;   // campo que pidió la cámara (null = cámara general de arriba)

function agregarBotonesCamara() {
    ["orden", "ubicacion", "destino", "pieza"].forEach(id => {
        const input = document.getElementById(id);
        if (!input || input.dataset.conCamara) return;
        input.dataset.conCamara = "1";

        const fila = document.createElement("div");
        fila.style.cssText = "display:flex; gap:8px; align-items:center;";
        input.parentNode.insertBefore(fila, input);
        fila.appendChild(input);
        input.style.flex = "1 1 auto";
        input.style.minWidth = "0";

        const btn = document.createElement("button");
        btn.type = "button";
        btn.textContent = "📷";
        btn.title = "Escanear con la cámara";
        btn.setAttribute("aria-label", "Escanear con la cámara");
        btn.style.cssText = "flex:0 0 52px; height:52px; font-size:22px; border:1px solid #d1d5db; " +
                            "border-radius:10px; background:#fff; cursor:pointer; padding:0;";
        btn.onclick = () => {
            campoObjetivo = id;
            iniciarEscaner();
            document.getElementById("lector-camara").scrollIntoView({ behavior: "smooth", block: "center" });
        };
        fila.appendChild(btn);
    });
}

/** Llena justo el campo cuyo 📷 se tocó, validando que el código sea del tipo correcto. */
async function llenarCampo(id, codigo) {
    const campo = document.getElementById(id);
    if (id === "orden") {
        if (!esCodigoOrden(codigo)) {
            mostrarMensaje(`"${codigo}" no es una orden (R + 9 dígitos).`, "error");
            return;
        }
        campo.value = codigo.toUpperCase();
        mostrarMensaje(`Orden: ${campo.value}`, "ok");
        return;
    }
    try {
        const r = await apiGet(`/scan?code=${encodeURIComponent(codigo)}`);
        if (id === "pieza") {
            if (r.kind !== "part") throw new Error("no es una pieza");
            campo.value = codigo;
            mostrarMensaje(`Pieza: ${r.parts.map(p => p.name).join(" / ")}`, "ok");
        } else {
            if (r.kind !== "location") throw new Error("no es una ubicación");
            campo.value = r.location.code;
            mostrarMensaje(`${id === "destino" ? "Destino" : "Ubicación"}: ${r.location.code} · ${r.location.name}`, "ok");
        }
    } catch (e) {
        mostrarMensaje(`No se pudo usar "${codigo}": ${e.message}`, "error");
    }
}

agregarBotonesCamara();

/** Decide en qué campo va lo escaneado preguntándole a la API qué es. */
async function manejarCodigoLeido(codigo) {
    codigo = codigo.trim();   // los SKU traen espacios y diagonales: no se alteran
    if (campoObjetivo) {
        const id = campoObjetivo;
        campoObjetivo = null;
        await llenarCampo(id, codigo);
        return;
    }
    if (esCodigoOrden(codigo)) {
        document.getElementById("orden").value = codigo.toUpperCase();
        mostrarMensaje(`Orden: ${codigo}`, "ok");
        return;
    }
    try {
        const r = await apiGet(`/scan?code=${encodeURIComponent(codigo)}`);
        if (r.kind === "location") {
            const ubicacion = document.getElementById("ubicacion");
            const destino = document.getElementById("destino");
            // En transferencia: el primer escaneo es el origen y el segundo el destino.
            if (operacion === "transferir" && ubicacion.value && !destino.value) {
                destino.value = r.location.code;
                mostrarMensaje(`Destino: ${r.location.code} · ${r.location.name}`, "ok");
            } else {
                ubicacion.value = r.location.code;
                mostrarMensaje(`Ubicación: ${r.location.code} · ${r.location.name}`, "ok");
            }
        } else if (r.kind === "part") {
            document.getElementById("pieza").value = codigo;
            const nombres = r.parts.map(p => p.name).join(" / ");
            mostrarMensaje(r.parts.length > 1
                ? `Ese código es de ${r.parts.length} piezas (${nombres}). Al continuar te pido elegir.`
                : `Pieza: ${nombres}`, "ok");
        } else {
            mostrarMensaje(`Leído: ${codigo}`, "info");
        }
    } catch (e) {
        mostrarMensaje(`No reconozco el código "${codigo}": ${e.message}`, "error");
    }
}

// ---------------- Traducir lo escrito/escaneado a ids ----------------
async function idUbicacion(codigo) {
    const r = await apiGet(`/scan?code=${encodeURIComponent(codigo.trim())}`);
    if (r.kind !== "location") throw new Error(`"${codigo}" no es una ubicación.`);
    return r.location;
}

async function piezaDesde(codigo) {
    codigo = codigo.trim();
    if (/^\d+$/.test(codigo)) return { part_id: Number(codigo), name: `Pieza ${codigo}` };
    const r = await apiGet(`/scan?code=${encodeURIComponent(codigo)}`);
    if (r.kind !== "part" || !r.parts.length) throw new Error(`"${codigo}" no es una pieza.`);
    if (r.parts.length === 1) return r.parts[0];

    // Mismo SKU en dos piezas: el contrato pide que el usuario elija.
    const opciones = r.parts.map((p, i) => `${i + 1}. ${p.name}`).join("\n");
    const n = Number(prompt(`El código "${codigo}" es de varias piezas. Escribe el número:\n${opciones}`));
    const elegida = r.parts[n - 1];
    if (!elegida) throw new Error("No se eligió pieza.");
    return elegida;
}

// ---------------- Enviar la operación ----------------
document.getElementById("formOperacion").addEventListener("submit", async function (event) {
    event.preventDefault();

    const ubicacionTxt = document.getElementById("ubicacion").value.trim();
    const cantidad = Number(document.getElementById("cantidad").value);
    const piezaTxt = document.getElementById("pieza").value.trim();
    const orden = document.getElementById("orden").value.trim().toUpperCase();
    const destinoTxt = document.getElementById("destino").value.trim();
    const motivo = document.getElementById("motivo").value;

    const minimo = operacion === "contar" ? 0 : 1;
    const cantidadVacia = document.getElementById("cantidad").value.trim() === "";
    if (!ubicacionTxt || cantidadVacia || !Number.isInteger(cantidad) || cantidad < minimo) {
        mostrarMensaje("Revisa la ubicación y la cantidad.", "error");
        return;
    }
    if (!piezaTxt) {
        mostrarMensaje("Escanea o escribe la pieza.", "error");
        return;
    }
    if (operacion === "retirar" && !orden) {
        mostrarMensaje("Introduce la orden de trabajo.", "error");
        return;
    }
    if (operacion === "transferir" && (!destinoTxt || destinoTxt === ubicacionTxt)) {
        mostrarMensaje("Introduce una ubicación de destino diferente.", "error");
        return;
    }
    if (operacion === "contar" && !motivo) {
        mostrarMensaje("Selecciona el motivo del conteo.", "error");
        return;
    }

    const boton = this.querySelector("button[type=submit]");
    boton.disabled = true;
    mostrarMensaje("Enviando…");

    try {
        const ubicacion = await idUbicacion(ubicacionTxt);
        const pieza = await piezaDesde(piezaTxt);

        if (operacion === "retirar") {
            await apiPost("/issues", {
                work_order_code: orden,
                part_id: pieza.part_id,
                location_id: ubicacion.location_id,
                quantity: cantidad,
                issued_by: USUARIO_PISO
            });
            mostrarMensaje(`✔ Salida registrada: ${cantidad} × ${pieza.name} desde ${ubicacion.code} para ${orden}.`, "ok");
        } else if (operacion === "transferir") {
            const destino = await idUbicacion(destinoTxt);
            await apiPost("/transfers", {
                part_id: pieza.part_id,
                from_location_id: ubicacion.location_id,
                to_location_id: destino.location_id,
                quantity: cantidad
            });
            mostrarMensaje(`✔ Transferencia: ${cantidad} × ${pieza.name} de ${ubicacion.code} a ${destino.code}.`, "ok");
        } else {
            const r = await apiPost("/counts", {
                location_id: ubicacion.location_id,
                reason: motivo,
                lines: [{ part_id: pieza.part_id, counted_quantity: cantidad }]
            });
            const ajuste = r.adjustments.find(a => a.part_id === pieza.part_id);
            mostrarMensaje(ajuste
                ? `✔ Conteo registrado. Ajuste de ${ajuste.delta > 0 ? '+' : ''}${ajuste.delta} en ${ubicacion.code}.`
                : `✔ Conteo registrado. Sin diferencias en ${ubicacion.code}.`, "ok");
        }

        // Limpiar para la siguiente operación (se queda la ubicación y la orden).
        document.getElementById("pieza").value = "";
        document.getElementById("destino").value = "";
        // En conteo se deja vacío a propósito: hay que escribir lo que se contó físicamente.
        document.getElementById("cantidad").value = operacion === "contar" ? "" : "1";
    } catch (e) {
        mostrarMensaje(`✘ ${e.message}`, "error");
    } finally {
        boton.disabled = false;
    }
});
