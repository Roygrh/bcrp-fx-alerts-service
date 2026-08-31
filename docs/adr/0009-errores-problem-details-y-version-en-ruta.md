# ADR 0009 — Errores como Problem Details (RFC 7807) y versión de la API en la ruta

- Estado: Aceptado
- Fecha: 2026-08-30

## Contexto

Los consumidores integran contra el contrato, no contra una persona. Necesitan errores con forma
estable y legible por máquina, que distingan una validación de negocio de una petición mal
formada, y que nunca filtren detalles de infraestructura. También hace falta un criterio de
versionado desde el primer día.

## Decisión

- Todas las respuestas de error usan **Problem Details** (`application/problem+json`) con `type`
  estable en forma de URN (`urn:fx-alerts:problem:validation`, `not-found`, `unauthorized`,
  `forbidden`, `source-unavailable`, `malformed-request`, `internal`...), `title` fijo por tipo,
  `status`, `detail` redactado para el consumidor y, en validaciones, `errors[]` por campo.
- Se distingue **petición mal formada** (JSON inválido, UUID inválido: `malformed-request`) de
  **regla de negocio incumplida** (umbral no positivo: `validation`); la primera la produce el
  intérprete de Tapir y la segunda el dominio.
- Los detalles técnicos (excepciones, mensajes del driver) van al log con el identificador de
  correlación `X-Request-Id`, nunca al cuerpo.
- **Excepción única**: `POST /oauth/token` responde con la forma que fija RFC 6749 §5.2
  (`error`, `error_description`), porque los clientes OAuth genéricos la esperan tal cual.
- La indisponibilidad de una fuente externa responde **503** (no 502/504): el cliente habla con
  este servicio, que no es una pasarela transparente; 503 expresa "temporalmente no disponible,
  reintente" sin revelar la topología aguas arriba.
- La versión viaja en la ruta (`/api/v1/...`): visible en logs, proxies y Swagger UI, y permite
  convivir dos versiones en prefijos distintos si hiciera falta una ruptura del contrato.

## Consecuencias

Positivas:

- `ApiError` es una jerarquía sellada con una variante `oneOf` por código: la documentación
  OpenAPI y el serializador no pueden divergir, y un error de dominio nuevo obliga a decidir su
  traducción HTTP en un único lugar (`ApiError.fromDomain`).
- Los clientes discriminan por `type`, no por texto libre.

Negativas y riesgos asumidos:

- Los URN no son URL de documentación; si se publica una, basta cambiar el prefijo, pero los
  clientes que discriminen por el valor completo tendrían que actualizarse.
- La salida de error común declara el 503 en todos los endpoints protegidos, incluidos los del
  CRUD de alertas que nunca lo producen; es ruido documental asumido a cambio de una única salida
  de error.
