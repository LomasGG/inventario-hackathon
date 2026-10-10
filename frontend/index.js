// INTERRUPTOR PRINCIPAL: true lee de archivos locales (mocks), false llama a la API real en puerto 8000
const USE_MOCKS = true;
const API_BASE_URL = "http://localhost:8000";

async function llamarApi(endpoint, opciones = {}) {
    if (USE_MOCKS) {
        // Mapeo de endpoints hacia archivos JSON de ejemplo en la carpeta mocks/
        let mockFile = "";
        if (endpoint.includes("/availability")) mockFile = "availability-196.json";
        else if (endpoint.includes("/materials/")) mockFile = "materials-R060826000.json";
        else if (endpoint.includes("/shortages")) mockFile = "shortages.json";
        else if (endpoint.includes("/reorder-suggestions")) mockFile = "reorder-suggestions.json";
        else if (endpoint.includes("/unmatched-receipts")) mockFile = "unmatched-receipts.json";
        else mockFile = "default.json";

        try {
            const respuesta = await fetch(`mocks/${mockFile}`);
            if (!respuesta.ok) throw new Error("No se pudo cargar el mock.");
            return await respuesta.json();
        } catch (error) {
            console.warn("Aviso de Mock: Usando datos vacíos o error al cargar", error);
            return { items: [] };
        }
    } else {
        // Llamada a la API real
        const respuesta = await fetch(`${API_BASE_URL}${endpoint}`, opciones);
        if (!respuesta.ok) {
            const errorData = await respuesta.json().catch(() => ({}));
            throw { status: respuesta.status, detail: errorData.detail || "Error en el servidor" };
        }
        return await respuesta.json();
    }
}

// Funciones expuestas para que las usen las pantallas (F1 y F2)
const api = {
    async getAvailability(partId) {
        return await llamarApi(`/parts/${partId}/availability`);
    },

    async getMaterials(workOrderCode) {
        return await llamarApi(`/work-orders/${workOrderCode}/materials`);
    },

    async getShortages() {
        return await llamarApi(`/shortages`);
    },

    async getReorderSuggestions() {
        return await llamarApi(`/reorder-suggestions`);
    },

    async postIssue(datosRetiro) {
        return await llamarApi(`/issues`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(datosRetiro)
        });
    },

    async postTransfer(datosTransferencia) {
        return await llamarApi(`/transfers`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(datosTransferencia)
        });
    }
};