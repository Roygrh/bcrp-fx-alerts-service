# ADR 0004 — Cadena de fuentes de tipo de cambio con procedencia explícita

- Estado: Aceptado
- Fecha: 2026-08-30

## Contexto

La fuente oficial del tipo de cambio es BCRPData, serie diaria `PD04640PD` (tipo de cambio venta
del sistema bancario, elaborado por la SBS y distribuido por el BCRP). Al integrarla se encontró
un hecho que condiciona el diseño:

- La API está detrás de un WAF (Imperva). Desde clientes no interactivos (curl, JVM, PowerShell)
  las peticiones recibían HTTP 200 con `text/html` y un desafío JavaScript en lugar del JSON, es
  decir, un "éxito" HTTP con un cuerpo inutilizable.
- Observaciones posteriores mostraron que el bloqueo es **intermitente**: unas peticiones pasan y
  otras reciben el desafío, sin un patrón controlable desde el cliente.

Un servicio que dependa de una sola fuente que puede dejar de responder de forma impredecible
no es aceptable. Pero la única alternativa de acceso libre (ExchangeRate-API) entrega una tasa
de mercado agregada por un proveedor comercial: **no mide lo mismo** ni es oficial, y presentarla
como si lo fuera sería un error de negocio grave en un contexto financiero.

Opciones consideradas:

1. Solo BCRP; fallar cuando esté bloqueado.
2. Sortear el WAF (navegador sin cabeza, imitar cabeceras de un navegador). Descartado: frágil, y
   eludir un control de seguridad de una institución pública no es una práctica admisible para un
   banco.
3. Respaldo silencioso: si el BCRP falla, servir ExchangeRate-API sin distinguirlo.
4. Cadena ordenada de fuentes con procedencia explícita en cada dato.

## Decisión

Opción 4.

- La **procedencia es un concepto de dominio**: `RateProvider` (código, nombre, `official`,
  `measures`) viaja en cada `ExchangeRate`. Una regla de negocio puede saber si el valor es
  oficial sin conocer la infraestructura.
- `FallbackExchangeRateSource` consulta las fuentes en el orden de `RATE_SOURCES`
  (`BCRP,ERAPI` por defecto). Solo se pasa a la siguiente cuando la anterior **no pudo responder**
  (`ExchangeRateUnavailable`). Si una fuente responde que no hay dato publicado
  (`ExchangeRateNotPublished`), esa respuesta es autoritativa y no se consulta el respaldo.
- Un 2xx con cuerpo que no es el JSON esperado (el desafío del WAF) se clasifica como fallo **no
  transitorio**: no se reintenta (repetir la petición no resuelve un desafío JavaScript y añade
  carga) y se pasa a la siguiente fuente de inmediato.
- La procedencia se expone en el contrato: `source { id, name, official, measures, attribution }`
  en `GET /api/v1/rates/current` y en la evaluación de alertas; `/health` informa `rates.source`
  y `rates.official` y se declara `DEGRADED` cuando se sirve desde el respaldo; cada salto al
  respaldo se registra con nivel de aviso.
- Los adaptadores comparten una política de llamada (`RemoteCall`): presupuesto de tiempo por
  intento, reintentos solo ante fallos transitorios con espera creciente, y registro sin cuerpos.

La **resolución definitiva es operativa, no técnica**: acordar con el BCRP el acceso del servicio
(lista blanca de IP, `User-Agent` registrado o el canal que la institución disponga). Ninguna
medida técnica del lado cliente convierte una fuente bloqueada intermitentemente en una fuente
fiable. Hasta que exista ese acuerdo, el servicio opera de forma honesta: sigue funcionando y dice
en cada respuesta sobre qué dato lo hace.

## Consecuencias

Positivas:

- El servicio sigue disponible ante el bloqueo del BCRP, con la degradación visible para el
  consumidor, el operador (`/health`, log) y el negocio (`official = false`).
- El consumidor decide su política sobre datos no oficiales con información completa; el servicio
  no la decide por él (ver [ADR 0008](0008-regla-de-cruce-estricta-y-base-de-evaluacion.md)).
- Incorporar una fuente es añadir un adaptador y un caso de `RateProvider`, decidiendo
  explícitamente si es oficial; el compilador obliga a decidir qué serie aproxima
  (`ExchangeRateApiClient.currencyFor`).

Negativas y riesgos asumidos:

- Las dos fuentes miden magnitudes distintas: una alerta puede dispararse sobre la referencia de
  mercado y no sobre el dato oficial, o al revés.
- El endpoint abierto de ExchangeRate-API exige atribución (se incluye en `source.attribution`) y
  anuncia su fin de vida en `time_eol_unix`; el servicio lo registra la primera vez que lo ve,
  pero sustituirlo será trabajo futuro.
- La muestra de respuesta del BCRP usada por las pruebas está reconstruida a partir de la
  estructura documentada, no capturada desde este entorno (ver `src/test/resources/bcrp/README.md`).
