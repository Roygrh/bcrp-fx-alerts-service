# ADR 0001 — Stack tecnológico: Scala 3 + http4s + Tapir + doobie

- Estado: Aceptado
- Fecha: 2026-08-28

## Contexto

El servicio expone un CRUD de alertas de tipo de cambio para clientes comerciales y consume la
API pública del BCRP. Es una pieza de evaluación técnica de arquitectura en banca, por lo que se
valoran: corrección verificable por el compilador, manejo explícito de errores y efectos,
documentación de API siempre sincronizada con la implementación, ausencia de secretos en el
código y facilidad para que un equipo Java/Spring entienda y mantenga el diseño.

Se consideraron tres familias de opciones:

1. Java + Spring Boot (Spring MVC/WebFlux, springdoc-openapi, JPA).
2. Scala con un framework "batteries included" (Play).
3. Scala 3 con librerías de la familia Typelevel (http4s, doobie, circe, cats-effect) y Tapir
   para la definición de endpoints.

## Decisión

Adoptar **Scala 3 (3.3 LTS) sobre la JVM 21** con:

- **http4s (backend Ember)** como servidor HTTP: rutas y middlewares son funciones puras
  `Request => F[Response]` gestionadas con `cats-effect`, lo que hace trivial componer,
  probar sin red y controlar el ciclo de vida de recursos (pool de conexiones, servidor) con
  `Resource`.
- **Tapir** para definir cada endpoint como un valor tipado (`Endpoint[I, E, O, R]`) que
  declara entradas, salidas, códigos de estado y errores. De esa única definición se derivan:
  - las rutas http4s (`Http4sServerInterpreter`), y
  - la especificación OpenAPI 3 y la Swagger UI servida en `/docs`.
- **doobie** para el acceso a datos: SQL explícito y tipado (sin ORM), transacciones como
  valores (`ConnectionIO`) y pool HikariCP; las migraciones se gestionan con **Flyway**.
- **circe** para JSON, con derivación de códecs en tiempo de compilación.
- **ciris** para configuración exclusivamente desde variables de entorno, con `Secret` para
  credenciales.

### Por qué Tapir y no rutas http4s "a mano" con OpenAPI separado

Definir rutas con el DSL de http4s y mantener un `openapi.yaml` aparte tiene un problema
estructural: el contrato y la implementación son dos artefactos independientes que solo un
proceso humano mantiene alineados. En un contexto bancario, donde el contrato de API se comparte
con otros equipos y con auditoría, esa deriva es un riesgo real (campos documentados que no se
devuelven, códigos de error omitidos, validaciones no reflejadas).

Con Tapir el endpoint es la única fuente de verdad:

- Si se añade un código de error, un header o un campo al endpoint, el compilador obliga a que
  la lógica de servidor lo produzca y la documentación lo refleja automáticamente.
- Los tipos de entrada/salida se validan y decodifican antes de llegar a la lógica de negocio;
  un body malformado nunca alcanza el servicio de aplicación.
- La misma definición puede interpretarse como cliente HTTP tipado (útil para pruebas de
  contrato) o para generar documentación en CI.
- El coste es marginal: Tapir se interpreta a http4s, así que no se renuncia a nada del
  servidor subyacente.

### Trasladabilidad a Java/Spring

Todo el stack corre sobre la JVM estándar (JDK 21) y usa JDBC, HikariCP, Flyway, SLF4J/Logback y
PostgreSQL, exactamente las mismas piezas de infraestructura que un servicio Spring Boot. La
arquitectura por capas (`domain` / `application` / `infrastructure`) con puertos y adaptadores
es independiente del lenguaje: cada elemento tiene un equivalente directo en Spring
(controladores + springdoc para Tapir, `JdbcTemplate`/JPA para doobie, `@ConfigurationProperties`
para ciris). El diseño, las migraciones y la base de datos se podrían reutilizar sin cambios si
el equipo decidiera reimplementar en Java.

## Consecuencias

Positivas:

- El contrato OpenAPI no puede divergir de la implementación; se genera en cada compilación.
- Los errores de dominio y de infraestructura se modelan como valores y el compilador exige
  tratarlos; no hay excepciones no controladas atravesando capas.
- Gestión de recursos determinista (apagado ordenado del servidor y del pool).
- Pruebas de endpoints sin levantar red ni base de datos: la `HttpApp` es una función.
- Huella operativa idéntica a un servicio Java: mismo JDK, mismos drivers, misma observabilidad.

Negativas / riesgos asumidos:

- Curva de aprendizaje de cats-effect y del estilo tagless-final para equipos sin experiencia en
  Scala funcional; se mitiga manteniendo las abstracciones mínimas y documentadas.
- doobie publica su línea 1.0 como release candidates (RC) desde hace tiempo; es la única línea
  compatible con cats-effect 3 y se usa ampliamente en producción, pero se fija la versión
  explícitamente y se revisa en cada actualización.
- Menor oferta de perfiles Scala frente a Java en el mercado local; la trasladabilidad descrita
  arriba acota ese riesgo.
