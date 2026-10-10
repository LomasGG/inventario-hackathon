   -- Los tests y el simulador pueden repetir el código (U-013) con distinto location_id.
   -- La identidad de una ubicación es su location_id.
   ALTER TABLE locations DROP CONSTRAINT IF EXISTS locations_code_key;