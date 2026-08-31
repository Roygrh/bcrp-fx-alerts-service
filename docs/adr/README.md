# Registros de decisiones de arquitectura (ADR)

Formato de Nygard (Contexto, Decisión, Consecuencias). Están numerados en el orden en que conviene
leerlos por primera vez: primero la base (lenguaje, estructura, persistencia), después el problema
de negocio central (el tipo de cambio) y por último la seguridad y las reglas del dominio.

| ADR | Decisión | En una línea |
|---|---|---|
| [0001](0001-stack-tecnologico.md) | Stack tecnológico | Scala 3 + http4s + Tapir + doobie: contrato OpenAPI derivado del código, errores como valores, misma huella operativa que un servicio Java. |
| [0002](0002-arquitectura-hexagonal.md) | Arquitectura hexagonal con la ceremonia mínima | Tres paquetes con dirección estricta y puertos solo donde hay un sistema externo; por qué no la ceremonia completa de Clean Architecture. |
| [0003](0003-postgresql-docker-compose-flyway.md) | PostgreSQL con Docker Compose y Flyway | El mismo motor en desarrollo, pruebas y producción; H2 embebida descartada por sus diferencias de dialecto. |
| [0004](0004-cadena-de-fuentes-con-procedencia.md) | Cadena de fuentes con procedencia explícita | El WAF del BCRP (bloqueo intermitente) obliga a un respaldo, pero cada dato dice quién lo produjo y si es oficial; la solución definitiva es un acuerdo de acceso. |
| [0005](0005-cache-en-memoria-del-tipo-de-cambio.md) | Caché en memoria del tipo de cambio | TTL, una sola consulta en vuelo, degradación a dato obsoleto visible y caché negativa tras un fallo. |
| [0006](0006-seguridad-oauth2-client-credentials-jwt.md) | Seguridad: OAuth 2.0 `client_credentials` + JWT RS256 + PBKDF2 | Máquina a máquina con tokens verificables por clave pública; por qué no `authorization_code`, mTLS ni FAPI en este alcance. |
| [0007](0007-aislamiento-por-cliente-en-sql-y-404.md) | Aislamiento por cliente en SQL y 404 | El propietario forma parte del `WHERE` de cada sentencia; una alerta ajena "no existe" para no ofrecer un oráculo de enumeración. |
| [0008](0008-regla-de-cruce-estricta-y-base-de-evaluacion.md) | Regla de cruce estricta y base de la evaluación | La igualdad no dispara; el resultado (`outcome`) y la calidad del dato (`basis`, `conclusive`) son dimensiones independientes y la política queda en el consumidor. |
| [0009](0009-errores-problem-details-y-version-en-ruta.md) | Errores Problem Details y versión en la ruta | Un único formato de error legible por máquina (salvo `/oauth/token`, que sigue RFC 6749), 503 para fuentes caídas y `v1` en la ruta. |

Convención: un ADR se escribe cuando se toma la decisión y no se edita después; si se revierte o
sustituye, se añade uno nuevo que lo referencia y el antiguo pasa a estado "Reemplazado".
