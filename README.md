# bcrp-fx-alerts-service

Servicio backend que expone un CRUD de alertas de tipo de cambio para clientes comerciales y
consume la [API de estadísticas del BCRP](https://estadisticas.bcrp.gob.pe/estadisticas/series/ayuda/api)
(Banco Central de Reserva del Perú) para obtener el tipo de cambio oficial.

Construido en Scala 3 sobre la JVM con http4s (Ember), Tapir, doobie, circe, ciris y Flyway;
PostgreSQL 16 como base de datos. Las decisiones de arquitectura se documentan en
[`docs/adr`](docs/adr).

> Estado actual: esqueleto del servicio (endpoint `/health`, Swagger UI, migraciones y
> configuración por entorno). El modelo de alertas, el cliente BCRP y la seguridad se
> incorporan en pasos posteriores.

## Requisitos previos

- JDK 21 (LTS)
- [sbt](https://www.scala-sbt.org/) 1.x (el script `sbt` descarga la versión fijada en `project/build.properties`)
- Docker y Docker Compose v2

## Puesta en marcha local

1. Crear el archivo de entorno a partir de la plantilla y ajustar los valores:

   ```bash
   cp .env.example .env
   ```

   Ninguna credencial está en el código ni en archivos versionados; `.env` está ignorado por git.

2. Levantar PostgreSQL:

   ```bash
   docker compose up -d --wait
   ```

3. Exportar las variables de `.env` en la terminal y arrancar la aplicación
   (sbt no lee `.env` por sí mismo):

   ```bash
   # bash / zsh
   set -a; source .env; set +a
   sbt run
   ```

   ```powershell
   # PowerShell
   Get-Content .env | Where-Object { $_ -match '^\s*[^#]' } | ForEach-Object {
     $name, $value = $_ -split '=', 2
     [Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim())
   }
   sbt run
   ```

   Al arrancar, la aplicación ejecuta las migraciones de Flyway y queda escuchando en
   `http://localhost:8080`.

4. Verificar:

   - `GET http://localhost:8080/health` → `{"status":"UP","database":{"status":"UP"}}`
   - `http://localhost:8080/docs` → Swagger UI generado desde las definiciones Tapir

## Pruebas

```bash
sbt test
```

## Estructura

```
src/main/scala/pe/quiroz/fxalerts
├── Main.scala           # arranque y composición de dependencias
├── domain               # modelos y errores de dominio
├── application          # servicios de aplicación y puertos
└── infrastructure
    ├── http             # endpoints Tapir, rutas http4s, servidor Ember, Swagger UI
    ├── persistence      # transactor doobie/HikariCP, migraciones Flyway
    └── config           # carga de configuración desde variables de entorno (ciris)
```
