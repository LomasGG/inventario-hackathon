# Decisiones de diseño

## 1. ¿Cómo relacionan las compras con el catálogo?
- **Decisión:** se compara el `part_number` contra el SKU con trim y mayúsculas. Si no hay coincidencia exacta, se intenta sin espacios ni guiones, pero solo se acepta si queda exactamente una pieza.
- **Revisión manual:** con 0 o más de 1 coincidencia, la compra va a `/unmatched-receipts`; al resolverla se da la entrada en `U-100`.
- **Por qué:** nunca adivinamos entre dos piezas; un error de inventario cuesta más que una revisión de 10 segundos.
- **Fuera:** coincidencias aproximadas y tablas de números de proveedor.

## 2. ¿Cómo manejan los kits?
- **Decisión:** cada línea de la lista de partes se inventaría por su `part_id`. El `group_name` solo agrupa en pantalla.
- **Por qué:** la necesidad vive por `bom_line_id`, así que renombrar un grupo no rompe nada y no hay que "armar" ni "desarmar" kits.
- **Fuera:** inventariar el kit como pieza única.

## 3. ¿Cómo evitan reservas dobles?
- **Decisión:** la necesidad vive por (orden, `bom_line_id`) en `material_lines`. Una aprobación posterior **reemplaza** `required` sin tocar `reserved` ni `issued`, y `reconcile` reserva o libera solo la diferencia. Un solo hilo consume, y un candado por pieza (`SELECT ... FOR UPDATE`) protege contra la API.
- **Por qué:** es lo que pide el contrato y evita que la inspección completa duplique la rápida. `available` nunca baja de cero.
- **Fuera:** reserva por ubicación (reservamos por pieza: el técnico la toma de cualquier estante).

## 4. ¿Cómo garantizan la idempotencia?
- **Decisión:** el `event_id` se guarda en `processed_events` dentro de la misma transacción que los efectos; si llega repetido, no hace nada. Los eventos de salida se publican después del commit. Al arrancar se lee primero el catálogo, y con la base vacía se releen los tópicos desde el inicio.
- **Por qué:** si algo falla, todo se deshace junto; releer desde cero da la misma existencia.
- **Fuera:** tabla *outbox* (si el servicio cae justo después del commit, un evento de salida podría perderse).

## Otras decisiones
- **Catálogo al día antes de cada compra:** antes de procesar una compra se lee `shop.catalog` hasta el final, para que la compra vea todas las piezas publicadas antes que ella (entre tópicos no hay orden).
- **Código de ubicación no único:** la identidad de una ubicación es su `location_id`; las pruebas repiten códigos (como `U-013`) con otro id.
- **Orden por orden:** si un evento de una orden está pendiente, los siguientes de esa misma orden esperan detrás. Así una anulación nunca se aplica antes que su aprobación.
- **Pendientes y errores:** un evento que menciona una pieza o línea aún desconocida espera en `pending_events` y se reintenta al llegar catálogo u órdenes. Lo que falla tras 2 reintentos va a `inventory.dlq` sin detener el consumo.
- **Liberar no reasigna:** al anular o borrar una orden, lo liberado queda disponible y se reparte con la siguiente compra.
- **Servicios (familia SERVICIO):** se tratan como cualquier pieza (reservan y generan sugerencia). Mejora pendiente: no inventariarlos.
- **Jackson:** los eventos de entrada se leen con Jackson 2 (`jackson-databind` 2.21.5, que hoy llega como dependencia transitiva de `flyway-core` 11.14.1; si Flyway lo quita, hay que declararlo en el `pom.xml`). La API y los eventos de salida usan el Jackson 3 de Spring Boot 4.
- **Un comando:** el servicio corre en Docker con el perfil `app` y se conecta a Kafka por la red del kit (`KIT_NETWORK`).