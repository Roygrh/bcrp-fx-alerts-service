-- Alertas de tipo de cambio registradas por clientes comerciales.
-- Las restricciones replican las invariantes del dominio: la aplicación es la primera línea de
-- defensa, la base de datos la última (accesos directos, migraciones de datos, defectos).

CREATE TABLE alerts (
    -- El identificador lo genera la aplicación (UUID aleatorio), no la base de datos: así la
    -- entidad tiene identidad antes de persistirse y no hay ida y vuelta para conocer la clave.
    id          UUID          PRIMARY KEY,

    -- Identificador externo del cliente. TEXT y no VARCHAR(n): en PostgreSQL son equivalentes
    -- en coste y la longitud máxima se expresa como restricción explícita, más fácil de leer
    -- y de evolucionar que el tipo de la columna.
    client_id   TEXT          NOT NULL,

    -- Código de la serie del BCRP (p. ej. PD04640PD). Se guarda el código y no un enum de
    -- PostgreSQL para que incorporar series nuevas no requiera migración de esquema.
    series_code TEXT          NOT NULL,

    -- NUMERIC exacto, nunca FLOAT/DOUBLE: un umbral de tipo de cambio es un importe monetario y
    -- la comparación contra el dato oficial debe ser exacta. Escala 4: el BCRP publica con 3
    -- decimales y el interbancario cotiza con 4. Precisión 10 deja 6 dígitos enteros, holgados
    -- para cualquier serie de tipo de cambio. El dominio valida los mismos límites para que
    -- ningún valor llegue a redondearse silenciosamente al insertarse.
    threshold   NUMERIC(10,4) NOT NULL,

    -- Conjuntos cerrados y estables. TEXT + CHECK en lugar de un tipo ENUM de PostgreSQL:
    -- mismo control, sin ALTER TYPE si algún día se añade un valor.
    direction   TEXT          NOT NULL,
    status      TEXT          NOT NULL,

    -- TIMESTAMPTZ: instante absoluto en UTC, independiente de la zona horaria de la sesión.
    -- Sin DEFAULT now(): las marcas las asigna la aplicación con su reloj, de modo que el valor
    -- persistido es exactamente el que la entidad conoce.
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL,

    CONSTRAINT alerts_client_id_not_blank CHECK (btrim(client_id) <> ''),
    CONSTRAINT alerts_client_id_length    CHECK (char_length(client_id) <= 64),
    -- Formato de los códigos de serie del BCRP: dos letras, cinco dígitos, dos letras.
    CONSTRAINT alerts_series_code_format  CHECK (series_code ~ '^[A-Z]{2}[0-9]{5}[A-Z]{2}$'),
    CONSTRAINT alerts_threshold_positive  CHECK (threshold > 0),
    CONSTRAINT alerts_direction_valid     CHECK (direction IN ('ABOVE', 'BELOW')),
    CONSTRAINT alerts_status_valid        CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

-- Las alertas se consultan por cliente (listado del panel del cliente y, más adelante,
-- notificaciones); el índice evita un recorrido completo de la tabla en esa consulta.
CREATE INDEX alerts_client_id_idx ON alerts (client_id);
