# ADR 0003 — PostgreSQL con Docker Compose y Flyway; H2 embebida descartada

- Estado: Aceptado
- Fecha: 2026-08-28

## Contexto

Las alertas deben persistir. El destino real de un servicio de este tipo en banca es un gestor
relacional gestionado; a la vez, quien evalúe el repositorio debe poder levantarlo en minutos. El
esquema usa `NUMERIC(10,4)` para los umbrales (comparación monetaria exacta), `TIMESTAMPTZ` para
los instantes y restricciones `CHECK` con expresiones regulares para replicar las invariantes del
dominio en la base de datos.

Opciones consideradas:

1. **H2 embebida** (en memoria o en archivo) en "modo PostgreSQL", sin Docker.
2. **SQLite**, sin Docker.
3. **PostgreSQL 16 en Docker Compose** para desarrollo local, y la misma imagen en las pruebas de
   integración.

## Decisión

Opción 3: PostgreSQL 16 (`postgres:16-alpine`) definido en `docker-compose.yml`, esquema propio
(`fx_alerts`, el servicio no escribe en `public`) y **Flyway** para las migraciones, versionadas
en `src/main/resources/db/migration` y aplicadas por la propia aplicación al arrancar.

H2 se descartó por estas razones:

- Su modo de compatibilidad con PostgreSQL es una aproximación. Difieren, entre otros, la
  semántica de `TIMESTAMPTZ`, el tratamiento de la escala en `NUMERIC`, el operador de expresión
  regular `~` que usan las restricciones `CHECK` de `V2__alerts.sql`, funciones como `btrim`, los
  códigos de error y el comportamiento de bloqueo. Un esquema validado en H2 da una confianza que
  no se sostiene en el motor real.
- Mantener dos dialectos obliga a escribir las migraciones para el mínimo común denominador o a
  duplicarlas; ambas cosas erosionan la garantía de que la migración probada es la que se
  ejecutará.
- La ganancia de H2 (no necesitar Docker) es pequeña: Docker ya es el mecanismo habitual para
  reproducir infraestructura, y las pruebas unitarias no lo necesitan en ningún caso.

SQLite se descartó por los mismos motivos, agravados por su tipado dinámico.

Flyway se eligió frente a Liquibase por su simplicidad (SQL plano, versionado por nombre de
archivo, suma de control por script) y porque el equipo destinatario lo reconoce. Las migraciones
son inmutables una vez aplicadas: un cambio de esquema es siempre un script nuevo.

## Consecuencias

Positivas:

- El mismo motor en desarrollo, pruebas de integración y producción: las restricciones y los
  tipos se prueban donde van a vivir.
- Las pruebas de integración (`sbt integration/test`) levantan un PostgreSQL efímero con
  Testcontainers a partir de la misma imagen y aplican las migraciones reales, con lo que también
  verifican que las migraciones son válidas.
- El esquema es código revisable y su historial queda en la tabla de Flyway.

Negativas y riesgos asumidos:

- Docker es requisito para ejecutar el servicio en local y las pruebas de integración (no para
  `sbt test`).
- Las migraciones se aplican al arrancar con las credenciales de la aplicación. Es lo adecuado
  para desarrollo y demostración; en un entorno regulado suelen ejecutarse en un paso separado del
  despliegue con una cuenta revisada por DBA. `FlywayMigrator` está aislado precisamente para que
  ese cambio sea de cableado, no de código.
- El puerto 5432 puede estar ocupado en la máquina de desarrollo; `DB_PORT` lo hace configurable
  y `docker-compose.yml` lo respeta.
