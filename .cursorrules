# Reglas del Proyecto / Project Rules

## Regla 1: Ámbito de Modificación de Código
- **Sólo** se permite crear y modificar código dentro de las carpetas de este proyecto (es decir, dentro de `g:\Software\StrategyQuantX\SQX_144_2938_win_20260520\user\extend\Snippets` y sus subcarpetas).
- No se deben modificar ni crear archivos de código fuera de esta ruta.

## Regla 2: Referencias de Código y Consulta de la API
- Para consultar cómo estructurar, heredar o ejecutar los códigos necesarios, **se puede** usar como referencia, si fuese necesario, cualquier archivo encontrado dentro de la carpeta principal de StrategyQuantX y sus subcarpetas: `G:\Software\StrategyQuantX\SQX_144_2938_win_20260520\`.
- En esa ubicación se encuentran referencias de código, guías de extensión de SQX, referencias de la API, etc. Esta consulta se puede realizar sin ningún tipo de restricción de lectura.

## Regla 3: Sincronización de Reglas entre IAs
- Siempre que se añada, modifique o elimine una regla en este proyecto, dicho cambio deberá aplicarse por igual en **todos** los archivos de reglas de todas las IAs presentes en el proyecto (actualmente `CLAUDE.md`, `.clinerules`, `.cursorrules` y `.geminirules`, así como cualquier otro que se añada en el futuro).
- No se considerará completa una modificación de reglas hasta que todos estos archivos estén sincronizados entre sí.

## Regla 4: Documentación de Custom Analysis
- Siempre que se cree o modifique un Custom Analysis (archivo `.java` dentro de `SQ/CustomAnalysis/`), deberá actualizarse en la misma tarea la documentación que lo acompaña (mismo nombre base, misma carpeta, ver Regla 5 para el formato exacto de los archivos), o crearse si todavía no existe.
- Dicha documentación debe describir qué es el Custom Analysis y su funcionamiento, así como instrucciones para el usuario: cómo configurarlo en SQX, sus Input Arguments (nombre, valor por defecto, descripción, ejemplo) y los outputs/columnas de Databank que genera.
- No se considerará completa una modificación de un Custom Analysis hasta que su documentación esté actualizada y sea coherente con el código.

## Regla 5: Documentación de Custom Analysis en Dos Idiomas
- Toda la documentación de un Custom Analysis (ver Regla 4) debe generarse siempre en **dos versiones**, tanto al crearla como al actualizarla: una en inglés con el sufijo `_ENG` y otra en español con el sufijo `_SPA`, ambas junto al `.java` correspondiente (mismo nombre base, misma carpeta). Por ejemplo, para `MonkeyTest.java`: `MonkeyTest_ENG.md` y `MonkeyTest_SPA.md`.
- Ambas versiones deben ser **traducción directa** una de la otra: misma estructura, mismas secciones, mismo orden y la misma información en ambas — únicamente cambia el idioma. No se permite que una versión contenga contenido, matices o nivel de detalle que la otra no tenga.
- Cada vez que se actualice una de las dos versiones (por un cambio en el Custom Analysis, según la Regla 4), debe actualizarse también la otra en la misma tarea, manteniéndolas sincronizadas entre sí.
- No se considerará completa una modificación de un Custom Analysis hasta que **ambas** versiones (`_ENG` y `_SPA`) estén actualizadas, sean coherentes con el código y sean fieles traducciones entre sí.
