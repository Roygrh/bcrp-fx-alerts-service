# bcrp-fx-alerts-service

Servicio backend que expone un CRUD de alertas de tipo de cambio para clientes comerciales y
consume la [API de estadísticas del BCRP](https://estadisticas.bcrp.gob.pe/estadisticas/series/ayuda/api)
(Banco Central de Reserva del Perú) para obtener el tipo de cambio oficial.

Construido en Scala 3 sobre la JVM con http4s (Ember), Tapir, doobie, circe, ciris y Flyway;
PostgreSQL 16 como base de datos. Las decisiones de arquitectura se documentan en
[`docs/adr`](docs/adr).

> Estado actual: esqueleto del servicio (endpoint `/health`, Swagger UI, migraciones y
> configuración por entorno), modelo de dominio de alertas con su persistencia en PostgreSQL,
> CRUD de alertas expuesto por HTTP bajo `/api/v1/alerts` (Problem Details RFC 7807,
> correlación por `X-Request-Id`), cliente de la API del BCRP con reintentos y caché en memoria,
> fuente de respaldo (ExchangeRate-API) encadenada tras el BCRP y el tipo de cambio vigente en
> `/api/v1/rates/current` con su procedencia explícita. La evaluación de alertas y la seguridad se
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

   - `GET http://localhost:8080/health` →
     `{"status":"UP","database":{"status":"UP"},"bcrp":{"status":"UP"}}`
   - `GET http://localhost:8080/api/v1/alerts` → `{"items":[]}` con la base de datos recién creada
   - `GET http://localhost:8080/api/v1/rates/current` → último tipo de cambio publicado
   - `http://localhost:8080/docs` → Swagger UI generado desde las definiciones Tapir

## Tipo de cambio: fuentes, respaldo y procedencia

El servicio obtiene el tipo de cambio de una **cadena ordenada de fuentes** (`RATE_SOURCES`,
por defecto `BCRP,ERAPI`). Solo se pasa a la siguiente fuente cuando la anterior **no pudo
responder**; si una fuente responde que no hay dato publicado, esa respuesta se respeta. Las dos
fuentes no miden lo mismo y el servicio nunca las presenta como equivalentes:

| Código | Fuente | Qué mide | Oficial |
|---|---|---|---|
| `BCRP` | [BCRPData](https://estadisticas.bcrp.gob.pe/estadisticas/series/ayuda/api), serie diaria `PD04640PD` | Tipo de cambio **venta** del sistema bancario, producido por la SBS y distribuido por el BCRP: precio oficial de referencia del sistema financiero peruano | Sí |
| `ERAPI` | [ExchangeRate-API](https://www.exchangerate-api.com), endpoint abierto `open.er-api.com/v6/latest/USD` | Tasa de mercado USD/PEN agregada por un proveedor comercial; referencia indicativa | No |

Cada valor lleva su procedencia en el dominio (`ExchangeRate.provider`) y la respuesta de
`GET /api/v1/rates/current` la expone en el bloque `source` (`id`, `name`, `official`,
`measures`, `attribution`), de modo que un consumidor distingue un precio oficial de una
referencia de mercado sin conocer la implementación. Cuando el servicio recurre al respaldo lo
registra con nivel de aviso y `/health` lo refleja como `DEGRADED`.

> Atribución: los datos de la fuente de respaldo son **"Rates By Exchange Rate API"**
> ([https://www.exchangerate-api.com](https://www.exchangerate-api.com)); la misma atribución viaja
> en `source.attribution` de cada respuesta servida desde esa fuente. El campo `time_eol_unix` de
> esa API anuncia el fin de vida del endpoint abierto; si llega con valor, el servicio lo registra
> con nivel de aviso la primera vez que lo observa.

Consulta al BCRP (fuente oficial):

```
GET {BCRP_BASE_URL}/PD04640PD/json/{hoy - BCRP_LOOKBACK_DAYS}/{hoy}/esp
```

La respuesta trae un valor por día; los días no hábiles vienen como `"n.d."` y se ignoran. El
servicio se queda con el último día con dato. En el respaldo, la fecha del dato es la de
`time_last_update_unix` expresada en el calendario peruano.

- **Reintentos** (política común a ambas fuentes, `BCRP_*` / `ERAPI_*`): solo ante fallos
  transitorios (tiempo de espera agotado, fallo de conexión, 5xx), hasta `*_MAX_RETRIES` veces con
  espera que se duplica en cada intento. Un 4xx, un cuerpo que no es el JSON esperado o un
  `"result": "error"` del proveedor no se reintentan.
- **Caché**: el dato se sirve durante `BCRP_CACHE_TTL` sin volver al BCRP (`"freshness":"FRESH"`).
  Si al vencer el BCRP no responde, se sirve el último valor conocido durante como máximo
  `BCRP_CACHE_MAX_STALE` marcado `"freshness":"STALE"`, y no se vuelve a intentar hasta pasado
  `BCRP_CACHE_FAILURE_BACKOFF` (si no hay ningún valor, durante ese mismo plazo se responde el
  fallo sin reconsultar). Las peticiones concurrentes comparten una única consulta en vuelo.
- **Sin dato**: `404` si la fuente responde pero no hay valor publicado en la ventana; `503`
  (Problem Details `source-unavailable`) si ninguna fuente responde y no hay nada en caché.
- **`/health`**: reporta el componente `rates` con `source` (fuente que sirve actualmente) y
  `official`. El servicio se declara `DEGRADED` (HTTP 200) cuando sirve desde el respaldo no
  oficial, desde caché, o cuando ninguna fuente responde; `DOWN` (HTTP 503) solo si la base de
  datos falla.

Para ver el servicio operando sobre el respaldo basta con apuntar `BCRP_BASE_URL` a un host
inexistente (o dejar la URL real: el proxy del BCRP bloquea a los clientes no interactivos):

```bash
BCRP_BASE_URL=http://bcrp.invalido.local/api sbt run
curl -i http://localhost:8080/api/v1/rates/current   # 200, "source":{"id":"ERAPI","official":false,...}
curl -i http://localhost:8080/health                  # 200 DEGRADED, rates.source=ERAPI
RATE_SOURCES=BCRP sbt run                             # sin respaldo: 503 y rates DOWN
```

> Nota operativa: la API del BCRP está detrás de un proxy de seguridad (Imperva) que responde a
> los clientes no interactivos con una página HTML de desafío en lugar del JSON. El cliente lo
> detecta ("cuerpo no interpretable" en el log), no reintenta y la cadena recurre al respaldo. La
> resolución definitiva es operativa, no técnica: acordar con el BCRP el acceso del servicio
> (lista blanca de IP o de `User-Agent`); hasta entonces el servicio opera `DEGRADED` sobre una
> referencia de mercado no oficial.

## Pruebas

Las pruebas unitarias no necesitan ninguna infraestructura:

```bash
sbt test
```

Las pruebas de integración del repositorio corren contra un PostgreSQL efímero que
[Testcontainers](https://testcontainers.com/) levanta con la misma imagen de `docker-compose.yml`
y al que aplica las migraciones reales de Flyway. Viven en el subproyecto `integration`, que
`sbt test` no ejecuta, de modo que solo requieren Docker cuando se invocan explícitamente:

```bash
sbt integration/test
```

No dependen de `.env` ni de la instancia de `docker compose`: cada ejecución parte de una base
de datos limpia en un puerto libre y el contenedor se elimina al terminar.

Las pruebas contra las API reales (`BcrpLiveSuite`, `ExchangeRateApiLiveSuite`) también viven en
`integration`, pero además se omiten salvo que se activen explícitamente, porque dependen de la red
y de terceros sin SLA:

```bash
BCRP_LIVE_TESTS=true sbt "integration/testOnly *LiveSuite"
```

## Estructura

```
src/main/scala/pe/quiroz/fxalerts
├── Main.scala           # arranque y composición de dependencias
├── domain               # modelos y errores de dominio
│   ├── alert            # Alert, identificadores, umbral, serie BCRP, dirección y estado
│   └── rate             # ExchangeRate (valor, fecha y procedencia), RateProvider (oficial o no), PeruvianCalendar
├── application          # servicios de aplicación y puertos
│   ├── alert            # AlertService, AlertRepository (puerto) y comandos
│   ├── rate             # ExchangeRateService, ExchangeRateSource (puerto), RateSnapshot, Freshness
│   └── health           # HealthService, DatabaseHealthCheck (puerto), criterio degradado/caído
└── infrastructure
    ├── remote           # RemoteCall (timeouts, reintentos y log comunes) y RemoteHttpClient (Ember)
    ├── bcrp             # adaptador ExchangeRateSource sobre BCRPData (fuente oficial)
    ├── erapi            # adaptador ExchangeRateSource sobre ExchangeRate-API (respaldo, no oficial)
    ├── rate             # FallbackExchangeRateSource: cadena ordenada de fuentes
    ├── cache            # CachedExchangeRateSource: caché en memoria que decora la cadena
    ├── http             # endpoints Tapir (health, alerts, rates), Problem Details, middleware, Swagger UI
    ├── persistence      # transactor doobie/HikariCP, migraciones Flyway, DoobieAlertRepository
    └── config           # carga de configuración desde variables de entorno (ciris)

src/main/resources/db/migration   # migraciones Flyway (V1 control, V2 tabla alerts)
src/test                           # pruebas unitarias (munit), sin infraestructura ni red
src/test/resources/bcrp            # ejemplo de respuesta de la API del BCRP usado por las pruebas
src/test/resources/erapi           # capturas reales de ExchangeRate-API (éxito y error)
integration/src/test               # integración: PostgreSQL (Testcontainers) y API reales (opcional)
```
