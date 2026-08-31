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
> fuente de respaldo (ExchangeRate-API) encadenada tras el BCRP, el tipo de cambio vigente en
> `/api/v1/rates/current` con su procedencia explícita, y la API protegida con OAuth 2.0
> (`client_credentials`) y JWT firmados con RS256, con alcances por operación y aislamiento de
> alertas por cliente. La evaluación de alertas se incorpora en un paso posterior.

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

2. Generar el par de claves RSA con el que se firman los tokens y volcarlo en `.env` (una sola
   línea con `\n` literales, entre comillas simples). Desde Git Bash en Windows, o cualquier
   shell POSIX:

   ```bash
   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem
   openssl pkey -in jwt-private.pem -pubout -out jwt-public.pem
   printf "JWT_PRIVATE_KEY='%s'\n" "$(awk 'NF { printf "%s\\n", $0 }' jwt-private.pem)" >> .env
   printf "JWT_PUBLIC_KEY='%s'\n"  "$(awk 'NF { printf "%s\\n", $0 }' jwt-public.pem)"  >> .env
   ```

   (Borra antes las líneas `JWT_PRIVATE_KEY=`/`JWT_PUBLIC_KEY=` de ejemplo. `JWT_PUBLIC_KEY` es
   opcional: si no se indica se deriva de la privada.) Sin `JWT_PRIVATE_KEY` el servicio se niega
   a arrancar; nunca genera una clave efímera por su cuenta. Las claves no se versionan: los
   archivos `.pem` deben quedar fuera del repositorio.

3. Registrar al menos un cliente en `OAUTH_CLIENTS`. La utilidad genera un secreto aleatorio
   (que se entrega al cliente y no vuelve a mostrarse) y su hash (lo único que el servicio guarda):

   ```bash
   sbt "runMain pe.quiroz.fxalerts.infrastructure.security.ClientSecretTool"
   ```

   ```
   OAUTH_CLIENTS=cliente-001|pbkdf2-sha256:600000:<sal>:<hash>|alerts:read,alerts:write,rates:read
   ```

4. Levantar PostgreSQL:

   ```bash
   docker compose up -d --wait
   ```

5. Exportar las variables de `.env` en la terminal y arrancar la aplicación
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
     [Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim().Trim("'"))
   }
   sbt run
   ```

   Al arrancar, la aplicación valida las claves, ejecuta las migraciones de Flyway y queda
   escuchando en `http://localhost:8080`.

6. Verificar:

   - `GET http://localhost:8080/health` →
     `{"status":"UP","database":{"status":"UP"},"rates":{"status":"UP",...}}` (sin token)
   - `POST http://localhost:8080/oauth/token` con las credenciales del cliente → `access_token`
   - `GET http://localhost:8080/api/v1/alerts` con `Authorization: Bearer <token>` →
     `{"items":[]}` con la base de datos recién creada
   - `GET http://localhost:8080/api/v1/rates/current` con el token → último tipo de cambio
   - `http://localhost:8080/docs` → Swagger UI generado desde las definiciones Tapir; el botón
     "Authorize" obtiene un token con `client_id` y `client_secret`

   La secuencia `curl` completa está en [Seguridad](#seguridad-oauth-20-client_credentials-y-jwt).

## Seguridad: OAuth 2.0 `client_credentials` y JWT

La API se protege con el flujo `client_credentials` de OAuth 2.0 (RFC 6749 §4.4): cada cliente
comercial es un cliente OAuth con un `client_id`, un secreto y un conjunto de alcances
concedidos. El servicio actúa a la vez de servidor de autorización (emite los tokens en
`POST /oauth/token`) y de servidor de recursos (los verifica en cada petición). `/health`,
`/docs` y el propio `/oauth/token` son los únicos recursos sin autenticación.

### Tokens

- **Formato**: JWT (RFC 7519) firmado con **RS256** (RSA + SHA-256, RFC 7518). El servicio firma
  con la clave privada (`JWT_PRIVATE_KEY`) y verifica con la pública, que se deriva de ella o se
  indica en `JWT_PUBLIC_KEY`; cualquier tercero con la clave pública puede verificar un token sin
  poder emitirlo. Tamaño mínimo 2048 bits; sin clave configurada el servicio no arranca.
- **Claims**: `iss` (`JWT_ISSUER`), `sub` y `client_id` (el `client_id`), `aud` (`JWT_AUDIENCE`),
  `iat`, `exp`, `jti` (UUID único por token) y `scope` (alcances separados por espacio). La
  cabecera lleva `typ: at+jwt` (perfil de tokens de acceso de RFC 9068); solo se aceptan tokens
  con exactamente esa cabecera, por lo que `alg: none` u otros algoritmos se rechazan antes de
  mirar la firma.
- **Vida**: `JWT_TTL` (15 minutos por defecto, entre 1 minuto y 24 horas). No hay tokens de
  refresco ni revocación: al caducar, el cliente pide otro con sus credenciales. La verificación
  tolera un desfase de reloj de 30 segundos.
- **Emisión**: `POST /oauth/token` con cuerpo `application/x-www-form-urlencoded`
  (`grant_type=client_credentials`, `scope` opcional) y las credenciales por HTTP Basic
  (`Authorization: Basic base64(client_id:client_secret)`, el mecanismo recomendado y el que usa
  Swagger UI) o en el cuerpo (`client_id`, `client_secret`), nunca por ambos. Sin `scope` el
  token lleva todos los alcances del cliente; con `scope`, todos los pedidos deben estar
  concedidos. Los errores siguen RFC 6749 §5.2 (`invalid_request`, `invalid_client` —401 con
  `WWW-Authenticate: Basic`—, `unsupported_grant_type`, `invalid_scope`); es el único endpoint
  cuyas respuestas no usan Problem Details, porque su forma la fija el estándar.

### Clientes y secretos

Los clientes se registran por configuración (`OAUTH_CLIENTS`), no en base de datos. El secreto
**nunca se almacena ni se compara en claro**: se guarda su derivación con **PBKDF2-HMAC-SHA256**
(RFC 8018, 600 000 iteraciones, sal aleatoria de 16 bytes, clave de 256 bits) y la comparación de
la clave derivada es en tiempo constante. Un `client_id` desconocido se verifica contra un hash
señuelo con los mismos parámetros, de modo que la duración de la respuesta no delata qué
identificadores existen. La utilidad `ClientSecretTool` genera secretos aleatorios de 256 bits y
sus hashes; ver `.env.example`.

### Alcances

| Alcance | Autoriza |
|---|---|
| `alerts:read` | `GET /api/v1/alerts`, `GET /api/v1/alerts/{id}` |
| `alerts:write` | `POST /api/v1/alerts`, `PUT /api/v1/alerts/{id}`, `DELETE /api/v1/alerts/{id}` |
| `rates:read` | `GET /api/v1/rates/current` |

`alerts:write` no implica `alerts:read`. El esquema de seguridad forma parte de las definiciones
Tapir: el documento OpenAPI publica el flujo `clientCredentials`, su `tokenUrl`, el catálogo de
alcances y el alcance que exige cada endpoint, y Swagger UI permite autenticarse desde "Authorize".

### Respuestas de error

| Situación | Respuesta |
|---|---|
| Sin token, o cabecera `Authorization` con otro esquema | `401` Problem Details `urn:fx-alerts:problem:unauthorized`, `WWW-Authenticate: Bearer realm="bcrp-fx-alerts"` |
| Token mal formado, firma inválida, caducado, emisor o audiencia incorrectos | `401`, **mismo cuerpo** que el caso anterior, `WWW-Authenticate: Bearer realm="bcrp-fx-alerts", error="invalid_token"`; el motivo solo va al log |
| Token válido sin el alcance exigido | `403` Problem Details `urn:fx-alerts:problem:forbidden`, `WWW-Authenticate: Bearer ..., error="insufficient_scope", scope="<alcance>"` |
| Alerta de otro cliente | `404`, indistinguible de una alerta inexistente |

### Aislamiento por cliente

El sujeto del token es el cliente comercial. El propietario de una alerta es siempre el `sub`
del token con que se creó (`POST` no acepta `clientId`; si llega, se ignora), el listado
devuelve únicamente las alertas del cliente autenticado (no existe filtro por otro cliente) y el
acceso por identificador (`GET`, `PUT`, `DELETE`) está acotado al propietario **en la propia
consulta SQL** (`WHERE id = ? AND client_id = ?`), no en una comprobación posterior: no hay
ventana entre comprobar la propiedad y actuar. Una alerta ajena responde `404` y no `403`: un
`403` confirmaría que el identificador existe y permitiría enumerar recursos de otros clientes;
para quien no es su propietario, la alerta no existe.

### Registro (logging)

Nunca se registran tokens, secretos ni la cabecera `Authorization`. El log de peticiones añade
`client=<client_id>` cuando la petición presenta un token válido; el endpoint de token registra
cada emisión (`client_id` y alcances) y cada rechazo (`client_id` si es inocuo y código de error);
los tokens rechazados se registran con su motivo, sin el token.

### Secuencia completa con `curl`

```bash
BASE=http://localhost:8080

# 1. Obtener un token (credenciales por HTTP Basic; -u las codifica)
curl -s -u cliente-001:EL-SECRETO -d grant_type=client_credentials $BASE/oauth/token
# {"access_token":"eyJ...","token_type":"Bearer","expires_in":900,"scope":"alerts:read alerts:write rates:read"}
TOKEN=$(curl -s -u cliente-001:EL-SECRETO -d grant_type=client_credentials $BASE/oauth/token \
        | sed -E 's/.*"access_token":"([^"]+)".*/\1/')

# 2. Usarlo
curl -s -H "Authorization: Bearer $TOKEN" $BASE/api/v1/rates/current
curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"series":"PD04640PD","threshold":3.85,"direction":"ABOVE"}' $BASE/api/v1/alerts
curl -s -H "Authorization: Bearer $TOKEN" $BASE/api/v1/alerts

# 3. 401: sin token, y con un token manipulado (mismo cuerpo, distinto reto)
curl -si $BASE/api/v1/alerts | head -5
curl -si -H "Authorization: Bearer ${TOKEN}x" $BASE/api/v1/alerts | head -5

# 4. 403: token con un alcance insuficiente (solo rates:read) usado para escribir
READ_ONLY=$(curl -s -u cliente-001:EL-SECRETO -d grant_type=client_credentials -d scope=rates:read \
            $BASE/oauth/token | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
curl -si -H "Authorization: Bearer $READ_ONLY" -H 'Content-Type: application/json' \
     -d '{"series":"PD04640PD","threshold":3.85,"direction":"ABOVE"}' $BASE/api/v1/alerts | head -5

# 5. Errores del endpoint de token (RFC 6749 §5.2)
curl -si -u cliente-001:incorrecto -d grant_type=client_credentials $BASE/oauth/token | head -8   # invalid_client
curl -s  -u cliente-001:EL-SECRETO -d grant_type=password $BASE/oauth/token                       # unsupported_grant_type
curl -s  -u cliente-001:EL-SECRETO -d grant_type=client_credentials -d scope=admin $BASE/oauth/token # invalid_scope
```

### Evolución prevista (no implementada)

Este paso cubre la autenticación máquina a máquina de clientes de confianza. La evolución
natural, fuera del alcance actual:

- **`authorization_code` con PKCE** (RFC 7636) para aplicaciones con usuario final, y **tokens de
  refresco** para sesiones largas sin reenviar el secreto.
- **Proveedor de identidad externo** (Keycloak, Entra ID, Auth0...): este servicio pasaría a ser
  solo servidor de recursos y verificaría los tokens con el JWKS del proveedor; el registro de
  clientes, la rotación de secretos y la revocación dejarían de vivir en su configuración.
- **mTLS** (RFC 8705) para autenticar clientes con certificado y ligar los tokens al certificado
  (`cnf`), en lugar del secreto compartido.
- **Perfil FAPI** (Financial-grade API): exige entre otras cosas PAR, `private_key_jwt` o mTLS,
  tokens ligados al emisor de la petición y firma de respuestas; es el marco de referencia si la
  API llega a exponerse a terceros del sector financiero.

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

Las pruebas unitarias no necesitan ninguna infraestructura ni red. Las de seguridad generan
sus propios pares de claves RSA en tiempo de ejecución (no hay claves versionadas) y derivan
los secretos con pocas iteraciones para no ralentizar la suite:

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
│   ├── alert            # AlertService (acotado por cliente propietario), AlertRepository (puerto) y comandos
│   ├── rate             # ExchangeRateService, ExchangeRateSource (puerto), RateSnapshot, Freshness
│   ├── health           # HealthService, DatabaseHealthCheck (puerto), criterio degradado/caído
│   └── security         # Scope, TokenService (client_credentials), puertos ClientRegistry, SecretHasher,
│                        #   TokenIssuer y TokenVerifier; AccessTokenClaims, AuthenticatedClient
└── infrastructure
    ├── remote           # RemoteCall (timeouts, reintentos y log comunes) y RemoteHttpClient (Ember)
    ├── bcrp             # adaptador ExchangeRateSource sobre BCRPData (fuente oficial)
    ├── erapi            # adaptador ExchangeRateSource sobre ExchangeRate-API (respaldo, no oficial)
    ├── rate             # FallbackExchangeRateSource: cadena ordenada de fuentes
    ├── cache            # CachedExchangeRateSource: caché en memoria que decora la cadena
    ├── security         # Pbkdf2SecretHasher, RsaKeyPem (PEM), StaticClientRegistry, ClientSecretTool (CLI)
    │   └── jwt          # Rs256Jwt (firma/verificación pura) y JwtTokens (adaptador TokenIssuer/TokenVerifier)
    ├── http             # endpoints Tapir (health, oauth/token, alerts, rates), Problem Details, middleware, Swagger UI
    │   └── auth         # ApiSecurity (esquema OAuth2 en OpenAPI), BearerAuthentication, POST /oauth/token
    ├── persistence      # transactor doobie/HikariCP, migraciones Flyway, DoobieAlertRepository
    └── config           # carga de configuración desde variables de entorno (ciris)

src/main/resources/db/migration   # migraciones Flyway (V1 control, V2 tabla alerts)
src/test                           # pruebas unitarias (munit), sin infraestructura ni red
src/test/resources/bcrp            # ejemplo de respuesta de la API del BCRP usado por las pruebas
src/test/resources/erapi           # capturas reales de ExchangeRate-API (éxito y error)
integration/src/test               # integración: PostgreSQL (Testcontainers) y API reales (opcional)
```
