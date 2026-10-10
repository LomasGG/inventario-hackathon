-- Datos de prueba para pruebas-dev.http (no usar en la entrega)
-- SKUs inventados para que no choquen con las piezas reales del simulador
INSERT INTO parts (part_id, sku, sku_norm, name, active) VALUES
  (9001, 'PRUEBA 9001/X', 'PRUEBA 9001/X', 'RODAMIENTO DE PRUEBA', true),
  (9002, 'DUP-1', 'DUP-1', 'GEMELA A', true),
  (9003, 'DUP-1', 'DUP-1', 'GEMELA B', true)
ON CONFLICT DO NOTHING;

INSERT INTO locations (location_id, code, name) VALUES (101, 'U-101', 'Estante de prueba')
ON CONFLICT DO NOTHING;

INSERT INTO work_orders (work_order_id, code) VALUES (5001, 'R000000001')
ON CONFLICT DO NOTHING;