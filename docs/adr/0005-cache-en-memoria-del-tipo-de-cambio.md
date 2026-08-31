# ADR 0005 — Caché en memoria con TTL, una sola consulta en vuelo, dato obsoleto y caché negativa

- Estado: Aceptado
- Fecha: 2026-08-30

## Contexto

El tipo de cambio es un dato **diario** que no cambia una vez publicado. Las fuentes son lentas
en el peor caso (varios intentos con tiempos de espera de segundos), no ofrecen SLA y una de
ellas se bloquea de forma intermitente ([ADR 0004](0004-cadena-de-fuentes-con-procedencia.md)).
El mismo dato lo consumen tres caminos: `GET /api/v1/rates/current`, la evaluación de alertas y la
verificación de salud, que un orquestador invoca de forma periódica. Sin caché, cada petición
pagaría la latencia completa y, al arrancar o al vencer el dato, N peticiones concurrentes
producirían N consultas simultáneas a la fuente.

Opciones consideradas:

1. Sin caché.
2. Caché externa (Redis) compartida entre instancias.
3. Caché en memoria por instancia, como decorador del puerto `ExchangeRateSource`.

## Decisión

Opción 3: `CachedExchangeRateSource` decora la cadena de fuentes (la caché no sabe cuántas
fuentes hay ni cuáles) con estas reglas:

- **TTL** (`BCRP_CACHE_TTL`, 15 min por defecto): el dato se sirve marcado `FRESH` sin reconsultar.
- **Una sola consulta en vuelo por serie**: al vencer (o con la caché vacía al arrancar), la
  primera petición reconsulta y las demás esperan ese mismo resultado (`Deferred`). La consulta
  corre en una fibra supervisada independiente del llamador: si quien la provocó se cancela (por
  ejemplo, el tiempo límite de `/health`), la consulta continúa y su resultado alimenta la caché.
- **Degradación a dato obsoleto**: si la fuente falla y existe un valor anterior con antigüedad
  menor que `BCRP_CACHE_MAX_STALE` (24 h), se sirve marcado `STALE` y no se vuelve a reconsultar
  durante `BCRP_CACHE_FAILURE_BACKOFF` (1 min). Servir un dato de ayer es preferible a no servir
  nada porque el dato del día no cambia; el riesgo (que exista un dato más nuevo que no vemos)
  queda expuesto al consumidor en `freshness`, `retrievedAt` y `ageSeconds`.
- **Caché negativa**: si la fuente falla y no hay ningún valor aprovechable, el fallo se retiene
  durante `BCRP_CACHE_FAILURE_BACKOFF`. Las peticiones siguientes fallan de inmediato en lugar de
  repetir la escalera de reintentos contra una fuente que acaba de no responder.
- **La fuente es la autoridad**: si responde que no hay dato publicado, se respeta aunque haya un
  valor anterior en caché.

Redis se descartó por proporción: hay un valor por serie y una sola serie, y el servicio corre en
una instancia. Introducir un sistema externo más para eso añadiría un punto de fallo sin
beneficio hasta que exista despliegue horizontal.

## Consecuencias

Positivas:

- A lo sumo una consulta a las fuentes por TTL, independientemente de la carga; `/health` nunca
  genera tráfico adicional mientras la caché esté vigente.
- Sin avalancha al arrancar ni al vencer el dato.
- La política es verificable con reloj virtual: la suite de la caché prueba vencimientos,
  esperas y reintentos sin dormir de verdad.

Negativas y riesgos asumidos:

- Estado por instancia: con N instancias habrá N consultas por TTL y la frescura puede diferir
  entre ellas. Es aceptable hoy; una caché compartida sería el siguiente paso si se escala
  horizontalmente.
- Puede servirse un dato de hasta 24 h de antigüedad. Es deliberado y siempre visible
  (`freshness = STALE`, `/health` `DEGRADED`, y `basis = UNCONFIRMED` en la evaluación).
- Las variables de configuración conservan el prefijo `BCRP_CACHE_*` aunque la caché es común a
  todas las fuentes; renombrarlas sería un cambio de contrato operativo y se anota como deuda.
