# ADR 0007 — Aislamiento por cliente en la propia consulta SQL y 404 para recursos ajenos

- Estado: Aceptado
- Fecha: 2026-08-31

## Contexto

Cada alerta pertenece a un cliente comercial y ningún cliente debe poder leer, modificar ni
eliminar las de otro. Los identificadores son UUID aleatorios, no adivinables, pero pueden
filtrarse por otras vías (registros, tickets de soporte, capturas) y el acceso indebido a objetos
por identificador es la primera categoría del OWASP API Security Top 10 (BOLA). Hay tres riesgos
concretos: olvidar la comprobación en una operación nueva, una ventana entre comprobar la
propiedad y actuar (TOCTOU), y que la respuesta permita enumerar recursos ajenos.

Opciones consideradas:

1. Comprobar la propiedad en el servicio tras recuperar la alerta
   (`if alert.clientId != owner then Forbidden`).
2. Acotar por propietario en la propia consulta del repositorio.
3. Seguridad a nivel de fila (RLS) en PostgreSQL.

Y para la respuesta ante un recurso ajeno: `403 Forbidden` o `404 Not Found`.

## Decisión

Opción 2 y **404**.

- El propietario es un parámetro de **todas** las operaciones del puerto `AlertRepository`
  (`findById(owner, id)`, `findAll(owner)`, `delete(owner, id)`, `update(alert)` con su
  `clientId`) y forma parte del `WHERE` de cada sentencia: `WHERE id = ? AND client_id = ?`. La
  base de datos nunca devuelve ni modifica filas ajenas; no hay comprobación posterior en memoria
  que pueda omitirse ni ventana entre comprobar y actuar.
- El propietario es siempre el sujeto del token. `POST /api/v1/alerts` no acepta `clientId` en el
  cuerpo (si llega, se ignora); no existe filtro por otro cliente en el listado.
- Una alerta ajena responde **404 con el mismo cuerpo que una inexistente**. RFC 7231 admite
  expresamente el 404 para no revelar la existencia de un recurso. Un 403 confirmaría que el
  identificador existe y convertiría el endpoint en un oráculo de enumeración entre clientes.
  Para un cliente, las alertas de otros no existen en su universo.

La opción 1 se descartó porque separa la comprobación de la acción: obliga a leer antes de
modificar o borrar (TOCTOU) y duplica la comprobación en cada operación, con el riesgo de que una
consulta nueva la olvide. La opción 3 se descartó **por ahora**: RLS requiere fijar el cliente en
una variable de sesión por transacción (`SET LOCAL`) con un pool de conexiones, lo que añade
piezas móviles; es el endurecimiento natural si otros sistemas acceden directamente a la base de
datos.

## Consecuencias

Positivas:

- Un único punto de aplicación del aislamiento, verificado en las pruebas de integración contra
  SQL real (una alerta de otro cliente no se lee, no se actualiza y no se borra).
- Sin TOCTOU: comprobar y actuar son la misma sentencia.
- Sin oráculo de enumeración: el cuerpo del 404 es idéntico para "no existe" y "no es tuya".

Negativas y riesgos asumidos:

- Ambigüedad para soporte: un cliente que use por error el identificador de otro recibe "no
  existe". El log de peticiones lleva `client=` y `requestId`, suficiente para diagnosticarlo.
- El índice por `client_id` es necesario para que el listado no recorra la tabla; existe desde la
  migración `V2`.
