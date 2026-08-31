# ADR 0008 — Regla de cruce estricta y base de la evaluación separada del resultado

- Estado: Aceptado
- Fecha: 2026-08-31

## Contexto

Una alerta declara un umbral y un sentido (`ABOVE` o `BELOW`). Al evaluarla contra el tipo de
cambio hay dos preguntas que el diseño debe responder sin ambigüedad:

1. ¿Qué ocurre cuando el valor es **exactamente igual** al umbral?
2. ¿Vale lo mismo una alerta disparada sobre el precio oficial confirmado que una disparada sobre
   la referencia de mercado del respaldo, o sobre un dato obsoleto que ninguna fuente confirma?

## Decisión

### Comparación estricta en ambos sentidos

`ABOVE` exige `valor > umbral` y `BELOW` exige `valor < umbral`. Un valor igual al umbral no
dispara en ningún sentido. Razones:

- **Semántica**: cruzar un umbral es rebasarlo. Con umbral 3.80, un tipo de cambio de 3.80 "está
  en" el umbral, no por encima ni por debajo.
- **Simetría**: dos alertas opuestas sobre el mismo umbral nunca se disparan a la vez sobre el
  mismo dato. Si `ABOVE` fuera `>=` y `BELOW` `<` (o al revés), la igualdad dispararía solo una
  por una elección arbitraria que el cliente no controla ni ve.
- **Expresividad**: la semántica inclusiva se expresa con el propio umbral. El dato oficial se
  publica con tres decimales y el umbral admite cuatro, de modo que `ABOVE 3.7999` equivale a
  "mayor o igual que 3.800" sobre cualquier dato oficial. La opción inversa no existe: con una
  comparación no estricta no habría forma de pedir la exclusiva.
- La comparación es numérica exacta (`BigDecimal`): `3.80` y `3.8` son el mismo valor y no hay
  errores de representación binaria.

Se descartó hacer la inclusividad configurable por alerta: añade un campo y combinatoria a cambio
de una capacidad que el cuarto decimal ya cubre; puede incorporarse después sin romper el
contrato.

### Resultado y base como dimensiones independientes

- `AlertOutcome` dice si el valor cruzó el umbral: `TRIGGERED`, `NOT_TRIGGERED`, o por qué no se
  aplicó la regla (`INACTIVE`, `SERIES_MISMATCH`). Se devuelven **todas** las alertas del cliente
  con su resultado, no solo las disparadas: "evaluada y no disparada" e "inactiva" son
  información distinta y una lista vacía significa inequívocamente "sin alertas".
- `EvaluationBasis` dice cuánto vale el dato como fundamento para actuar: `OFFICIAL_CONFIRMED`
  (precio oficial confirmado dentro del periodo de validez; el único **concluyente**),
  `MARKET_REFERENCE` (respaldo no oficial) y `UNCONFIRMED` (último valor conocido, ninguna fuente
  responde). La falta de confirmación prevalece sobre la oficialidad: un dato oficial que ya nadie
  confirma no es mejor fundamento que una referencia de mercado vigente.
- Todas las alertas de una respuesta se evalúan contra el mismo dato, así que la base se expresa
  **una vez** por evaluación, junto con el dato usado (`rate`, con la misma forma que
  `GET /api/v1/rates/current`) y un booleano `conclusive` para que un sistema automático decida con
  un solo campo.
- El servicio **no aplica ninguna política** sobre los casos no concluyentes: no oculta ni degrada
  la alerta. Se consideró suprimir las alertas disparadas sobre datos no oficiales y se descartó:
  el servicio no conoce la tolerancia al riesgo de cada cliente y ocultar información es peor que
  exponerla con precisión. Una política del lado servidor, opcional por cliente, queda como
  posible evolución.
- La evaluación es una **consulta**: se recalcula en cada llamada, no se persiste y no notifica.
  Sin tipo de cambio no hay evaluación (404 o 503 iguales que en `/rates/current`), tampoco vacía:
  "ninguna alerta disparada" no debe confundirse con "no hay dato contra el que evaluar".

## Consecuencias

Positivas:

- Regla determinista, simétrica y documentada en OpenAPI; la regla vive en el dominio como
  función pura y se prueba en aislamiento.
- El consumidor recibe toda la información para decidir (resultado, base, dato y frescura) sin
  que el servicio tome decisiones de negocio en su nombre.

Negativas y riesgos asumidos:

- Un cliente que espere semántica inclusiva debe conocer el recurso del cuarto decimal; está
  documentado en el endpoint.
- "Cruce" se evalúa como **estado** en el instante de la consulta, no como **transición** (estaba
  por debajo y ahora está por encima). Detectar transiciones y notificar exige persistir
  evaluaciones anteriores; queda fuera de este alcance.
- `SERIES_MISMATCH` es hoy inalcanzable porque el catálogo tiene una sola serie; existe para que
  incorporar una segunda no cambie el contrato.
