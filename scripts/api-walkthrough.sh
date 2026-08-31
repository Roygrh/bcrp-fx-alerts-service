#!/usr/bin/env sh
# Recorrido completo de la API con curl: token, CRUD de alertas, tipo de cambio, evaluación y los
# casos de error 400 / 401 / 403 / 404 / 503. Cada paso imprime la petición, el código esperado, el
# código recibido y el cuerpo. Al final, un resumen y código de salida distinto de cero si algún
# paso no respondió lo esperado.
#
# Requisitos: curl y un shell POSIX (bash, zsh, Git Bash en Windows). Nada más.
#
# Uso:
#   CLIENT_ID=cliente-001 CLIENT_SECRET='<secreto entregado al cliente>' scripts/api-walkthrough.sh
#
# Variables opcionales:
#   BASE_URL          raíz del servicio (por defecto http://localhost:8080)
#   EXPECT_RATES_503  'true' si el servicio corre sin ninguna fuente accesible
#                     (RATE_SOURCES=BCRP y BCRP_BASE_URL a un host inexistente): entonces el
#                     tipo de cambio y la evaluación deben responder 503 en lugar de 200
#
# El secreto se lee de una variable de entorno y NUNCA se imprime. Este archivo no contiene
# credenciales: los valores <ASI> son marcadores que debes sustituir.

set -u

BASE_URL="${BASE_URL:-http://localhost:8080}"
CLIENT_ID="${CLIENT_ID:-cliente-001}"
: "${CLIENT_SECRET:?Define CLIENT_SECRET con el secreto del cliente (se genera con ClientSecretTool)}"
EXPECT_RATES_503="${EXPECT_RATES_503:-false}"

if [ "$EXPECT_RATES_503" = "true" ]; then
  RATES_OK=503
else
  RATES_OK=200
fi

BODY_FILE="$(mktemp)"
trap 'rm -f "$BODY_FILE"' EXIT

FAILURES=0
STEP=0

# call <esperado> <descripción> <argumentos de curl...>
# Ejecuta curl, guarda el cuerpo en BODY_FILE y compara el código de estado con el esperado.
call() {
  expected="$1"; shift
  description="$1"; shift
  STEP=$((STEP + 1))
  printf '\n[%02d] %s\n' "$STEP" "$description"
  print_masked "$@"
  status="$(curl -s -o "$BODY_FILE" -w '%{http_code}' "$@")"
  if [ "$status" = "$expected" ]; then
    verdict="OK"
  else
    verdict="FALLO"
    FAILURES=$((FAILURES + 1))
  fi
  printf '     esperado %s, recibido %s -> %s\n' "$expected" "$status" "$verdict"
  if [ -s "$BODY_FILE" ]; then
    printf '     '; head -c 600 "$BODY_FILE"; printf '\n'
  fi
}

# Imprime la línea de curl sustituyendo cualquier argumento que contenga el secreto por un marcador.
print_masked() {
  printf '     curl'
  for arg in "$@"; do
    case "$arg" in
      *"$CLIENT_SECRET"*) printf ' %s' "<argumento con CLIENT_SECRET>" ;;
      *) printf ' %s' "$arg" ;;
    esac
  done
  printf '\n'
}

# Extrae un campo de texto de primer nivel del último cuerpo JSON (sin depender de jq).
json_field() {
  sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" "$BODY_FILE" | head -n 1
}

JSON='Content-Type: application/json'
ALERT='{"series":"PD04640PD","threshold":3.85,"direction":"ABOVE"}'
ALERT_UPDATED='{"series":"PD04640PD","threshold":3.95,"direction":"BELOW","status":"INACTIVE"}'

printf 'Servicio: %s\nCliente:  %s\n' "$BASE_URL" "$CLIENT_ID"

# --- Salud y documentación (sin token) -------------------------------------------------------

call 200 "Estado del servicio (sin token; DEGRADED también es 200)" \
  "$BASE_URL/health"

# --- Emisión de tokens -----------------------------------------------------------------------

call 200 "Token con todos los alcances del cliente (credenciales por HTTP Basic)" \
  -u "$CLIENT_ID:$CLIENT_SECRET" -d grant_type=client_credentials "$BASE_URL/oauth/token"
TOKEN="$(json_field access_token)"
if [ -z "$TOKEN" ]; then
  printf '\nNo se obtuvo un token; el resto del recorrido no puede continuar.\n'
  exit 1
fi

call 200 "Token restringido a rates:read (servirá para provocar el 403)" \
  -u "$CLIENT_ID:$CLIENT_SECRET" -d grant_type=client_credentials -d scope=rates:read \
  "$BASE_URL/oauth/token"
READ_ONLY_TOKEN="$(json_field access_token)"

call 401 "invalid_client: secreto incorrecto (RFC 6749 §5.2; WWW-Authenticate: Basic)" \
  -u "$CLIENT_ID:secreto-incorrecto" -d grant_type=client_credentials "$BASE_URL/oauth/token"

call 400 "unsupported_grant_type: solo se admite client_credentials" \
  -u "$CLIENT_ID:$CLIENT_SECRET" -d grant_type=password "$BASE_URL/oauth/token"

call 400 "invalid_scope: alcance no concedido a este cliente" \
  -u "$CLIENT_ID:$CLIENT_SECRET" -d grant_type=client_credentials -d scope=admin \
  "$BASE_URL/oauth/token"

call 400 "invalid_request: credenciales por Basic y por el cuerpo a la vez" \
  -u "$CLIENT_ID:$CLIENT_SECRET" -d grant_type=client_credentials \
  -d "client_id=$CLIENT_ID" -d "client_secret=$CLIENT_SECRET" "$BASE_URL/oauth/token"

AUTH="Authorization: Bearer $TOKEN"
AUTH_READ_ONLY="Authorization: Bearer $READ_ONLY_TOKEN"

# --- 401: sin token y con token manipulado ---------------------------------------------------

call 401 "401 sin token (WWW-Authenticate: Bearer realm=...)" \
  "$BASE_URL/api/v1/alerts"

call 401 "401 con token manipulado (mismo cuerpo; error=\"invalid_token\" en el reto)" \
  -H "Authorization: Bearer ${TOKEN}x" "$BASE_URL/api/v1/alerts"

call 401 "401 con otro esquema de autenticación en Authorization" \
  -H "Authorization: Basic Zm9vOmJhcg==" "$BASE_URL/api/v1/alerts"

# --- Tipo de cambio ---------------------------------------------------------------------------

call "$RATES_OK" "Tipo de cambio vigente con procedencia (source) y frescura (freshness)" \
  -H "$AUTH" "$BASE_URL/api/v1/rates/current"

# --- CRUD de alertas --------------------------------------------------------------------------

call 200 "Listado inicial de alertas del cliente" \
  -H "$AUTH" "$BASE_URL/api/v1/alerts"

call 201 "Alta de una alerta (el propietario es el sujeto del token; Location en la respuesta)" \
  -H "$AUTH" -H "$JSON" -d "$ALERT" "$BASE_URL/api/v1/alerts"
ALERT_ID="$(json_field id)"

call 200 "Consulta de la alerta creada" \
  -H "$AUTH" "$BASE_URL/api/v1/alerts/$ALERT_ID"

call 200 "Reemplazo completo (PUT) de la alerta: nuevo umbral, sentido y estado INACTIVE" \
  -H "$AUTH" -H "$JSON" -X PUT -d "$ALERT_UPDATED" "$BASE_URL/api/v1/alerts/$ALERT_ID"

call 200 "Listado con la alerta modificada" \
  -H "$AUTH" "$BASE_URL/api/v1/alerts"

# --- Evaluación --------------------------------------------------------------------------------

call 201 "Segunda alerta, activa, con umbral bajo para que se dispare con casi cualquier valor" \
  -H "$AUTH" -H "$JSON" -d '{"series":"PD04640PD","threshold":0.5,"direction":"ABOVE"}' \
  "$BASE_URL/api/v1/alerts"
SECOND_ALERT_ID="$(json_field id)"

call "$RATES_OK" "Evaluación de todas las alertas del cliente (outcome por alerta; basis y conclusive por evaluación)" \
  -H "$AUTH" "$BASE_URL/api/v1/alerts/evaluation"

# --- 400: validación de negocio y peticiones mal formadas ------------------------------------

call 400 "400 validation: umbral no positivo (regla de dominio; errors[] por campo)" \
  -H "$AUTH" -H "$JSON" -d '{"series":"PD04640PD","threshold":0,"direction":"ABOVE"}' \
  "$BASE_URL/api/v1/alerts"

call 400 "400 validation: umbral con más de cuatro decimales" \
  -H "$AUTH" -H "$JSON" -d '{"series":"PD04640PD","threshold":3.85001,"direction":"ABOVE"}' \
  "$BASE_URL/api/v1/alerts"

call 400 "400 malformed-request: JSON con un sentido de cruce desconocido" \
  -H "$AUTH" -H "$JSON" -d '{"series":"PD04640PD","threshold":3.85,"direction":"SIDEWAYS"}' \
  "$BASE_URL/api/v1/alerts"

call 400 "400 malformed-request: identificador que no es un UUID" \
  -H "$AUTH" "$BASE_URL/api/v1/alerts/no-es-un-uuid"

# --- 403: token válido sin el alcance exigido ------------------------------------------------

call 403 "403 insufficient_scope: token de solo rates:read intentando crear una alerta" \
  -H "$AUTH_READ_ONLY" -H "$JSON" -d "$ALERT" "$BASE_URL/api/v1/alerts"

call 403 "403 insufficient_scope: el mismo token intentando listar (alerts:read)" \
  -H "$AUTH_READ_ONLY" "$BASE_URL/api/v1/alerts"

call "$RATES_OK" "El token de solo rates:read sí puede leer el tipo de cambio" \
  -H "$AUTH_READ_ONLY" "$BASE_URL/api/v1/rates/current"

# --- 404 ---------------------------------------------------------------------------------------

call 404 "404 not-found: alerta inexistente (una alerta de OTRO cliente responde exactamente igual)" \
  -H "$AUTH" "$BASE_URL/api/v1/alerts/00000000-0000-4000-8000-000000000000"

call 404 "404 not-found: ruta inexistente" \
  -H "$AUTH" "$BASE_URL/api/v1/no-existe"

# --- Limpieza ----------------------------------------------------------------------------------

call 204 "Baja de la primera alerta" \
  -H "$AUTH" -X DELETE "$BASE_URL/api/v1/alerts/$ALERT_ID"

call 204 "Baja de la segunda alerta" \
  -H "$AUTH" -X DELETE "$BASE_URL/api/v1/alerts/$SECOND_ALERT_ID"

call 404 "404 al borrar de nuevo la misma alerta" \
  -H "$AUTH" -X DELETE "$BASE_URL/api/v1/alerts/$ALERT_ID"

# --- 503: solo reproducible por configuración --------------------------------------------------
#
# El 503 (urn:fx-alerts:problem:source-unavailable) aparece cuando NINGUNA fuente responde y no hay
# dato en caché. No se puede provocar desde el cliente; se reproduce arrancando el servicio así:
#
#   RATE_SOURCES=BCRP BCRP_BASE_URL=http://bcrp.invalido.local/api sbt run
#
# y ejecutando este script con EXPECT_RATES_503=true: entonces los pasos de tipo de cambio y de
# evaluación esperan 503 en lugar de 200, y /health informa rates DOWN (el servicio sigue en
# DEGRADED, 200, porque la base de datos responde).

printf '\n%d paso(s) ejecutado(s), %d fallo(s).\n' "$STEP" "$FAILURES"
[ "$FAILURES" -eq 0 ]
