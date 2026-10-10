// Control de pestañas
function cambiarPestaña(nombreTab) {
    // Ocultar todas las secciones
    document.querySelectorAll('.seccion-tab').forEach(sec => {
        sec.style.display = 'none';
    });

    // Desactivar botones de pestañas
    document.querySelectorAll('.btn-pestana').forEach(btn => {
        btn.classList.remove('activa');
    });

    // Mostrar la seleccionada
    if (nombreTab === 'existencias') {
        document.getElementById('seccion-existencias').style.display = 'block';
        document.querySelector('.btn-pestana:nth-child(1)').classList.add('activa');
    } else if (nombreTab === 'faltantes') {
        document.getElementById('seccion-faltantes').style.display = 'block';
        document.querySelector('.btn-pestana:nth-child(2)').classList.add('activa');
    } else if (nombreTab === 'comprar') {
        document.getElementById('seccion-comprar').style.display = 'block';
        document.querySelector('.btn-pestana:nth-child(3)').classList.add('activa');
    } else if (nombreTab === 'recepciones') {
        document.getElementById('seccion-recepciones').style.display = 'block';
        document.querySelector('.btn-pestana:nth-child(4)').classList.add('activa');
    }
}

// Datos de ejemplo para poblar las tablas rápidamente en la demo
function cargarDatosEjemplo() {
    // 1. KPIs superiores
    document.getElementById('valFaltantes').textContent = "3";
    document.getElementById('valComprar').textContent = "5";
    document.getElementById('valRecepciones').textContent = "2";

    // 2. Existencias
    const tbodyExistencias = document.getElementById('tablaExistenciasBody');
    tbodyExistencias.innerHTML = `
        <tr>
            <td><strong>RODAMIENTO 7014</strong></td>
            <td><span class="sku-mono">7014 CTA/P4</span></td>
            <td class="derecha">5</td>
            <td class="derecha">2</td>
            <td class="derecha texto-verde">3</td>
        </tr>
        <tr>
            <td><strong>SELLO MECÁNICO</strong></td>
            <td><span class="sku-mono">SM-2040</span></td>
            <td class="derecha">0</td>
            <td class="derecha">0</td>
            <td class="derecha texto-rojo">0</td>
        </tr>
    `;

    // 3. Faltantes
    const tbodyFaltantes = document.getElementById('tablaFaltantesBody');
    tbodyFaltantes.innerHTML = `
        <tr>
            <td><a href="orden.html?code=R061026012" class="chip-orden">R061026012</a></td>
            <td>RODAMIENTO 7014</td>
            <td class="derecha texto-rojo">2</td>
            <td>Hace 2 horas</td>
        </tr>
        <tr>
            <td><a href="orden.html?code=R061026015" class="chip-orden">R061026015</a></td>
            <td><span style="color: #687280;">Sin identificar</span></td>
            <td class="derecha text-rojo">Sin cantidad</td>
            <td>Hace 30 mins</td>
        </tr>
    `;

    // 4. Por comprar
    const tbodyComprar = document.getElementById('tablaComprarBody');
    tbodyComprar.innerHTML = `
        <tr>
            <td><strong>RODAMIENTO 7014</strong><br><span class="sku-mono">7014 CTA/P4</span></td>
            <td class="derecha">4</td>
            <td>
                <a href="#" class="chip-orden">R061026012</a>
                <a href="#" class="chip-orden">R061026018</a>
            </td>
        </tr>
    `;

    // 5. Recepciones por revisar
    const tbodyRecepciones = document.getElementById('tablaRecepcionesBody');
    tbodyRecepciones.innerHTML = `
        <tr>
            <td><span class="sku-mono">EXT-9921</span></td>
            <td>Caja con baleros chicos</td>
            <td class="derecha">2</td>
            <td>Hoy / R061026012</td>
            <td><button class="secundario" onclick="alert('Abriendo buscador para relacionar pieza...')">Relacionar</button></td>
        </tr>
    `;
}

function copiarListaCompras() {
    alert("Lista copiada al portapapeles (Simulado para correo a Compras).");
}

function filtrarExistencias() {
    // Lógica para filtrar elementos en la tabla de existencias
}

// Inicializar al cargar la página
window.onload = function() {
    cargarDatosEjemplo();
};