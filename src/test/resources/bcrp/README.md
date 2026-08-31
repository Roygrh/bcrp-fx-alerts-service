# Ejemplos de respuesta de la API del BCRP

`PD04640PD-2026-08-20_2026-08-30.json` reproduce la forma de la respuesta de

    GET https://estadisticas.bcrp.gob.pe/estadisticas/series/api/PD04640PD/json/2026-08-20/2026-08-30/esp

para la serie diaria de tipo de cambio venta SBS: `config.series[i].name`, `periods[].name` con la
fecha en formato `dd.Mmm.aa` (mes abreviado en español) y `periods[].values[i]` como cadenas, con
`"n.d."` en los días sin dato (fines de semana y feriados; el 30 de agosto de 2026 es domingo y,
además, festivo).

Importante: la API está detrás de un proxy de seguridad (Imperva) que responde a los clientes no
reconocidos con una página HTML de desafío en lugar del JSON. Esta muestra se reconstruyó a partir
de la estructura que consumen clientes públicos de la API y de la documentación oficial, no de una
captura directa desde este entorno. Para sustituirla por una captura real basta con abrir la URL
anterior en un navegador, guardar el cuerpo tal cual y comprobar que `BcrpResponseSuite` sigue en
verde (el parser es tolerante con `config` y con las abreviaturas `Set`/`Sep`).
