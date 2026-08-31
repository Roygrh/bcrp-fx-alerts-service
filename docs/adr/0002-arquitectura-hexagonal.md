# ADR 0002 — Arquitectura hexagonal con la ceremonia mínima necesaria

- Estado: Aceptado
- Fecha: 2026-08-28

## Contexto

El servicio tiene tres capacidades (gestionar alertas, entregar el tipo de cambio vigente y
evaluar las alertas contra él) y depende de tres sistemas externos con distinta fiabilidad:
PostgreSQL, la API del BCRP (que resultó estar detrás de un WAF, ver [ADR 0004](0004-cadena-de-fuentes-con-procedencia.md))
y ExchangeRate-API. Hace falta poder probar las reglas de negocio sin red ni base de datos,
sustituir cualquier sistema externo sin tocar el negocio y que un evaluador reconozca la
estructura en minutos.

Se consideraron tres niveles de ceremonia:

1. **Centrado en el framework**: controladores que llaman directamente a repositorios y clientes
   HTTP. Rápido de escribir; el negocio queda repartido entre controladores y adaptadores y solo
   se puede probar levantando infraestructura.
2. **Clean Architecture completa**: un interactor por caso de uso, interfaces de entrada y de
   salida por caso de uso, presentadores, DTO en cada frontera y mapeadores en cada sentido,
   normalmente con un módulo de compilación por capa.
3. **Hexagonal (puertos y adaptadores) con la ceremonia justa**: tres paquetes con dirección de
   dependencia estricta y puertos únicamente donde hay un sistema externo.

## Decisión

Se adopta la opción 3, con estas reglas concretas:

- **Tres paquetes, una dirección**: `domain` no importa nada del proyecto; `application` importa
  solo `domain`; `infrastructure` importa ambos. `Main` es el único lugar que conoce las
  implementaciones concretas y las compone.
- **El dominio es puro**: tipos con constructor inteligente (`Threshold`, `ClientId`), enumerados
  cerrados, errores como valores (`Either[DomainError, _]`) y sin efectos. La regla de negocio
  central, el cruce de umbral, es una función pura (`Alert.evaluate`).
- **Puertos solo donde hay un sistema externo**: `AlertRepository`, `ExchangeRateSource`,
  `DatabaseHealthCheck`, `ClientRegistry`, `SecretHasher`, `TokenIssuer` y `TokenVerifier`. No
  existen "puertos de entrada" por caso de uso: los servicios de aplicación (`AlertService`,
  `AlertEvaluationService`, `ExchangeRateService`, `TokenService`, `HealthService`) son clases
  con métodos y son invocados directamente por los adaptadores HTTP.
- **Modelos de frontera solo en HTTP**: los cuerpos de petición y respuesta son tipos propios de
  `infrastructure/http`, porque el contrato público debe poder evolucionar sin arrastrar al
  dominio. Entre HTTP y aplicación viajan comandos sencillos (`CreateAlert`, `UpdateAlert`), no
  DTO adicionales.
- **Un único módulo de compilación** para el servicio, más un subproyecto `integration` para las
  pruebas que necesitan Docker. La dirección de dependencia se garantiza por convención y revisión,
  no por fronteras de módulo.
- **La abstracción del efecto (`F[_]` con restricciones de cats-effect)** se mantiene porque
  paga su coste: permite probar reintentos, tiempos de espera y vencimientos de caché con reloj
  virtual (`TestControl`) en milisegundos, sin dormir de verdad.

### Por qué no la ceremonia completa de Clean Architecture

El valor de esa ceremonia aparece cuando hay varios mecanismos de entrega para el mismo caso de
uso (HTTP, cola, CLI, interfaz de usuario) o equipos distintos por capa. Aquí hay un único
mecanismo de entrega y un equipo. Con seis operaciones, la variante completa habría añadido del
orden de treinta tipos (interactores, puertos de entrada, puertos de salida, presentadores,
mapeadores) cada uno con exactamente una implementación y un único llamador. Eso no aporta
aislamiento adicional (el dominio ya está aislado) y sí oculta el negocio entre capas de paso.

Lo que sí se conserva de Clean Architecture es lo que protege el negocio: la regla de
dependencia, la pureza del dominio, los puertos para todo sistema externo y los modelos de
frontera en el contrato público.

### Cuándo aumentar la ceremonia

- Si aparece un segundo mecanismo de entrega (por ejemplo, un planificador que evalúe alertas y
  notifique), los servicios de aplicación ya son los casos de uso que invocaría; bastaría añadir el
  adaptador.
- Si el equipo crece o el módulo se vuelve grande, separar `domain`, `application` e
  `infrastructure` en módulos sbt para que el compilador imponga la dirección de dependencia.

## Consecuencias

Positivas:

- Las reglas de negocio se prueban como funciones puras; los servicios de aplicación, con dobles
  en memoria; los endpoints, como una función `Request => Response` sin levantar red.
- Cambiar la fuente del tipo de cambio o el mecanismo de persistencia es escribir un adaptador.
- Un desarrollador Java/Spring reconoce la estructura sin explicación: dominio, servicios,
  repositorios y adaptadores.

Negativas y riesgos asumidos:

- La dirección de dependencia no la impone el compilador; una importación indebida pasaría
  desapercibida hasta la revisión.
- Los servicios de aplicación son de grano grueso (varios métodos por servicio); a este tamaño es
  legible, pero exige disciplina para no convertirlos en cajones de sastre.
- Los tipos de configuración (`RateCacheConfig`, `RemoteCallConfig`) viven en `infrastructure` y
  los consumen los adaptadores; la aplicación no los conoce, lo que es correcto, pero obliga a que
  la composición en `Main` sea explícita.
