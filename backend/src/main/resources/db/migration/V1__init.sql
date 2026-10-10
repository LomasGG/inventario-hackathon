-- B2 · infraestructura del consumidor
CREATE TABLE processed_events (
    event_id     uuid PRIMARY KEY,
    event_type   text NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE pending_events (
    id          bigserial PRIMARY KEY,
    topic       text NOT NULL,
    payload     jsonb NOT NULL,          -- el sobre completo
    waiting_for text NOT NULL,           -- ej. 'part:305' o 'bom_line:144'
    attempts    int NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- B2 · catálogo
CREATE TABLE parts (
    part_id   int PRIMARY KEY,
    sku       text,                      -- como viene, puede ser null
    sku_norm  text,                      -- upper(trim(sku)), para relacionar compras
    name      text NOT NULL,
    family    text,
    unit_name text,
    image_url text,
    active    boolean NOT NULL
);
CREATE INDEX ON parts (sku_norm);
CREATE TABLE locations (
    location_id  int PRIMARY KEY,
    code         text NOT NULL UNIQUE,
    name         text NOT NULL,
    is_workbench boolean NOT NULL DEFAULT false,
    active       boolean NOT NULL DEFAULT true
);
INSERT INTO locations VALUES (100, 'U-100', 'Recepción de material', false, true);
CREATE TABLE bom_lines (
    bom_line_id  int PRIMARY KEY,
    model_id     int NOT NULL,
    part_id      int,
    name         text NOT NULL,
    group_name   text,
    qty_per_unit int,
    sort_order   int
);

-- B2 · órdenes y necesidad
CREATE TABLE work_orders (
    work_order_id int PRIMARY KEY,
    code          text NOT NULL UNIQUE,
    model_id      int,                   -- null si se creó desde una inspección adelantada
    deleted       boolean NOT NULL DEFAULT false,
    opened_at     timestamptz
);
CREATE TABLE material_lines (
    work_order_id      int NOT NULL,
    bom_line_id        int NOT NULL,
    inspection_id      int NOT NULL,     -- la aprobación que fijó la necesidad vigente
    inspection_item_id int NOT NULL,
    part_id            int,              -- null = sin identificar
    name               text NOT NULL,
    required           int,              -- null = sin cantidad
    reserved           int NOT NULL DEFAULT 0,
    issued             int NOT NULL DEFAULT 0,
    active             boolean NOT NULL DEFAULT true,
    shortage_opened_at timestamptz,      -- cuándo empezó a faltar (orden de surtido)
    PRIMARY KEY (work_order_id, bom_line_id)
);
CREATE TABLE reorder_suggestions (
    part_id            int PRIMARY KEY,
    suggested_quantity int NOT NULL,
    work_order_ids     int[] NOT NULL
);

-- B1 · libro mayor
CREATE TABLE movements (
    movement_id      bigserial PRIMARY KEY,
    part_id          int NOT NULL,
    location_id      int NOT NULL,
    type             text NOT NULL,      -- receipt | issue | transfer_out | transfer_in | adjustment
    quantity         int NOT NULL,       -- con signo
    occurred_at      timestamptz NOT NULL,
    reference        text,               -- orden, PR-2044/520, o motivo
    purchase_line_id int,
    work_order_id    int,
    unit_cost        numeric(14,2),
    currency         text
);
CREATE INDEX ON movements (part_id, occurred_at, movement_id);
CREATE TABLE stock (
    part_id     int NOT NULL,
    location_id int NOT NULL,
    on_hand     int NOT NULL,
    PRIMARY KEY (part_id, location_id)
);
CREATE TABLE unmatched_receipts (
    id               bigserial PRIMARY KEY,
    purchase_line_id int NOT NULL UNIQUE,
    part_number      text NOT NULL,
    description      text NOT NULL,
    quantity         int NOT NULL,
    unit_price       numeric(14,2),
    currency         text,
    received_at      timestamptz NOT NULL,
    work_order_code  text,
    resolved         boolean NOT NULL DEFAULT false
);
