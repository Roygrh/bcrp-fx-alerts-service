# ADR 0006 — Seguridad: OAuth 2.0 `client_credentials`, JWT RS256 y PBKDF2

- Estado: Aceptado
- Fecha: 2026-08-31

## Contexto

Los consumidores son sistemas de clientes comerciales (tesorerías, ERP, paneles de operación):
comunicación máquina a máquina, sin usuario final presente. Cada cliente debe ver únicamente sus
alertas. No existe en el alcance un proveedor de identidad corporativo al que delegar, y se decidió
no añadir dependencias: toda la criptografía debe salir del JDK.

Opciones consideradas para autenticar clientes:

1. Claves de API estáticas en cabecera.
2. Tokens opacos con consulta a base de datos en cada petición.
3. JWT firmados con HMAC (HS256).
4. OAuth 2.0 `client_credentials` con JWT firmados con RSA (RS256), emitidos por el propio
   servicio.
5. Proveedor de identidad externo (Keycloak, Entra ID, Auth0) con el servicio como servidor de
   recursos.

## Decisión

Opción 4, dejando la 5 como evolución natural.

- El servicio actúa a la vez de **servidor de autorización** (`POST /oauth/token`, RFC 6749 §4.4)
  y de **servidor de recursos**. Los errores del endpoint de token siguen RFC 6749 §5.2.
- **JWT RS256** con cabecera fija `typ: at+jwt` (perfil de tokens de acceso, RFC 9068). Solo se
  aceptan tokens con exactamente esa cabecera: `alg: none` u otro algoritmo se rechazan antes de
  mirar la firma, lo que elimina la confusión de algoritmo. Claims: `iss`, `sub` y `client_id`,
  `aud`, `iat`, `exp`, `jti`, `scope`. Vida de 15 minutos por defecto, sin tokens de refresco ni
  revocación; tolerancia de reloj de 30 s.
- **RS256 y no HS256**: la verificación solo necesita la clave pública. Separar el emisor en el
  futuro, o publicar un JWKS, no exige compartir el secreto de firma con cada verificador.
- **Alcances** por operación (`alerts:read`, `alerts:write`, `rates:read`); el de escritura no
  implica el de lectura. Forman parte de la definición Tapir, por lo que OpenAPI publica el flujo,
  su `tokenUrl` y el alcance que exige cada endpoint.
- **Secretos con PBKDF2-HMAC-SHA256** (RFC 8018, 600 000 iteraciones, sal de 16 bytes, clave de
  256 bits), comparación en tiempo constante, y verificación contra un hash señuelo cuando el
  `client_id` no existe, para que la duración de la respuesta no revele qué identificadores hay.
  PBKDF2 y no Argon2id/bcrypt: está en el JDK, y los secretos son valores aleatorios de 256 bits
  generados por `ClientSecretTool`, no contraseñas humanas, con lo que la resistencia adicional de
  Argon2id aporta poco. El formato almacenado lleva el nombre del algoritmo para permitir migrar.
- **Registro de clientes en configuración** (`OAUTH_CLIENTS`), sin secretos en claro. El alta y
  la baja exigen reiniciar; se asume en este alcance.
- Nunca se registran tokens, secretos ni la cabecera `Authorization`; los rechazos se registran
  con su motivo, y hacia el cliente todos los motivos de token inválido responden igual.

### Por qué no `authorization_code`, mTLS ni FAPI en este alcance

- **`authorization_code` (+ PKCE)** sirve para aplicaciones con usuario final que delega acceso.
  Aquí no hay usuario, navegador ni consentimiento; implementarlo añadiría interfaz de inicio de
  sesión, sesión, consentimiento y tokens de refresco sin ningún consumidor que los use.
- **mTLS (RFC 8705)** autentica al cliente con certificado y liga el token al certificado (`cnf`).
  Exige una PKI con ciclo de vida de certificados por cliente y una terminación TLS que entregue
  la identidad del certificado al servicio. En este alcance no hay terminación TLS (el servicio
  escucha HTTP y delega TLS al despliegue), así que no hay dónde apoyarlo. Es el paso adecuado
  para clientes de alto valor y el modelo de tokens actual lo admite.
- **FAPI (Financial-grade API)** es un perfil para exponer APIs a terceros bajo regulación de
  banca abierta: exige PAR, `private_key_jwt` o mTLS, tokens ligados al emisor de la petición
  (DPoP o mTLS), firma de respuestas y, en la práctica, un servidor de autorización certificado.
  No existe aquí un mandato regulatorio que lo requiera y montarlo a medias, sin servidor de
  autorización certificado, daría una falsa sensación de conformidad. Es el marco de referencia si
  la API llega a exponerse a terceros del sector.

La ruta de evolución es la opción 5: un proveedor de identidad externo emite los tokens y este
servicio pasa a verificar únicamente contra su JWKS. Como la verificación ya es un puerto
(`TokenVerifier`) y la emisión otro (`TokenIssuer`), el cambio no toca la aplicación.

## Consecuencias

Positivas:

- Autorización estándar, comprensible por cualquier cliente OAuth genérico (Swagger UI incluido).
- Los secretos nunca están en claro ni en el código ni en la configuración; la verificación es
  resistente a comparación por tiempo.
- El aislamiento por cliente se apoya en una identidad firmada, no en un dato de la petición.

Negativas y riesgos asumidos:

- Sin revocación: un token es válido hasta que caduca; la vida corta acota la ventana.
- **Sin rotación de claves**: hay una única clave sin `kid` y sin JWKS. Cambiar la clave exige
  reiniciar e invalida todos los tokens en vuelo. Es la primera mejora pendiente en esta área.
- El registro de clientes en configuración no escala en número de clientes ni permite rotar
  secretos sin reinicio.
- El servicio escucha HTTP: en cualquier entorno real debe ir detrás de una terminación TLS; sin
  ella, tokens y secretos viajarían en claro.
