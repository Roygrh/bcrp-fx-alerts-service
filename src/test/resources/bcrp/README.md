# Ejemplos de respuesta de la API del BCRP

## `PD04640PD-2026-08-24_2026-08-28.json` — captura real

Cuerpo real devuelto por la API para la serie diaria de tipo de cambio venta SBS,

    GET https://estadisticas.bcrp.gob.pe/estadisticas/series/api/PD04640PD/json/{inicio}/{fin}/esp

obtenido el 31 de agosto de 2026. Trae los periodos del 24 al 28 de agosto de 2026, todos con
dato publicado: `config.series[i].name`, `periods[].name` con la fecha en formato `dd.Mmm.aa`
(mes abreviado en español) y `periods[].values[i]` como cadenas.

## `PD04640PD-sintetico-dias-sin-dato.json` — muestra SINTÉTICA

No es una captura: es la reconstrucción anterior a partir de la estructura que consumen clientes
públicos de la API y de la documentación oficial. Se conserva únicamente porque la captura real
no incluye ningún día sin dato, y este caso necesita cobertura: la API representa los fines de
semana y feriados con la cadena `"n.d."` (aquí, 22, 23, 29 y 30 de agosto de 2026, con periodos
`"n.d."` también al final de la ventana). Si una captura real futura trae días `"n.d."`, puede
sustituir a ambas muestras.

Nota operativa: la API está detrás de un proxy de seguridad (Imperva) que, de forma intermitente,
responde a los clientes no reconocidos con una página HTML de desafío en lugar del JSON. Para
renovar la captura basta abrir la URL en un navegador, guardar el cuerpo tal cual y ajustar los
valores esperados de `BcrpResponseSuite` y `BcrpExchangeRateClientSuite`.
