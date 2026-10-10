// =====================================================================
// api.js — conexión de todas las pantallas con el backend de inventario
// Se carga ANTES del .js de cada pantalla:
//   <script src="api.js"></script>
//   <script src="almacen.js"></script>
// =====================================================================

// ⚙️ Dónde está el backend cuando abren la página con Live Server o como archivo.
//    Si el backend corre en OTRA computadora, pongan aquí su IP, ej. "http://192.168.1.45:8000".
const API_REMOTA = "http://10.177.190.200:8000";

// Si la página la sirve el propio backend (puerto 8000) o un túnel HTTPS, se usan rutas relativas.
const API_BASE = (location.port === "8000" || location.protocol === "https:") ? "" : API_REMOTA;

/** Llama a la API. Devuelve el JSON o lanza un Error con el mensaje "detail" del backend. */
async function api(ruta, opciones = {}) {
    let resp;
    try {
        resp = await fetch(API_BASE + ruta, opciones);
    } catch (e) {
        throw new Error("No hay conexión con el servidor de inventario.");
    }
    const texto = await resp.text();
    let datos = null;
    try { datos = texto ? JSON.parse(texto) : null; } catch (e) { datos = null; }
    if (!resp.ok) {
        const error = new Error((datos && datos.detail) || `Error ${resp.status} del servidor`);
        error.status = resp.status;
        throw error;
    }
    return datos;
}

function apiGet(ruta) {
    return api(ruta);
}

function apiPost(ruta, cuerpo) {
    return api(ruta, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(cuerpo)
    });
}

/** Escapa texto para meterlo en HTML sin romper la tabla (los SKU traen /, comas, etc.). */
function esc(valor) {
    return String(valor ?? "").replace(/[&<>"']/g, c => (
        { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]
    ));
}

/** "Hace 5 min", "Hace 2 horas", "Hace 3 días". */
function hace(fechaIso) {
    if (!fechaIso) return "--";
    const min = Math.round((Date.now() - new Date(fechaIso).getTime()) / 60000);
    if (min < 1) return "Justo ahora";
    if (min < 60) return `Hace ${min} min`;
    const horas = Math.round(min / 60);
    if (horas < 24) return `Hace ${horas} ${horas === 1 ? "hora" : "horas"}`;
    const dias = Math.round(horas / 24);
    return `Hace ${dias} ${dias === 1 ? "día" : "días"}`;
}

/** 09/10/26 18:30 */
function fechaCorta(fechaIso) {
    if (!fechaIso) return "--";
    return new Date(fechaIso).toLocaleString("es-MX", { dateStyle: "short", timeStyle: "short" });
}

/** Pone una sola fila con un mensaje (cargando, vacío o error) en una tabla. */
function filaMensaje(tbody, columnas, texto, color = "#687280") {
    tbody.innerHTML = `<tr><td colspan="${columnas}" style="text-align:center; color:${color}; padding:18px;">${esc(texto)}</td></tr>`;
}

/** Los códigos de orden del taller: R + 9 dígitos. */
function esCodigoOrden(texto) {
    return /^R\d{9}$/.test(String(texto || "").trim().toUpperCase());
}
