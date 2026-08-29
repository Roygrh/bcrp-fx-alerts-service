-- Migración inicial: tabla de control del servicio.
-- El esquema de negocio (alertas, clientes, etc.) se incorpora en migraciones posteriores.
-- Flyway ejecuta este script con el esquema configurado (DB_SCHEMA) como esquema por defecto.

CREATE TABLE service_metadata (
    key        TEXT        PRIMARY KEY,
    value      TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO service_metadata (key, value)
VALUES ('schema.baseline', 'v1');
