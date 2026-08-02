# Reglas del Proyecto / Project Rules

## Regla 1: Ámbito de Modificación de Código
- **Sólo** se permite crear y modificar código dentro de las carpetas de este proyecto (es decir, dentro de `g:\Software\StrategyQuantX\SQX_144_2938_win_20260520\user\extend\Snippets` y sus subcarpetas).
- No se deben modificar ni crear archivos de código fuera de esta ruta.

## Regla 2: Referencias de Código y Consulta de la API
- Para consultar cómo estructurar, heredar o ejecutar los códigos necesarios, se puede y **debe** usar como referencia cualquier archivo encontrado dentro de la carpeta principal de StrategyQuantX y sus subcarpetas: `G:\Software\StrategyQuantX\SQX_144_2938_win_20260520\`.
- En esa ubicación se encuentran referencias de código, guías de extensión de SQX, referencias de la API, etc. Esta consulta se puede realizar sin ningún tipo de restricción de lectura.

## Regla 3: Sincronización de Reglas entre IAs
- Siempre que se añada, modifique o elimine una regla en este proyecto, dicho cambio deberá aplicarse por igual en **todos** los archivos de reglas de todas las IAs presentes en el proyecto (actualmente `CLAUDE.md`, `.clinerules`, `.cursorrules` y `.geminirules`, así como cualquier otro que se añada en el futuro).
- No se considerará completa una modificación de reglas hasta que todos estos archivos estén sincronizados entre sí.

## Regla 4: Documentación de Custom Analysis
- Siempre que se cree o modifique un Custom Analysis (archivo `.java` dentro de `SQ/CustomAnalysis/`), deberá actualizarse en la misma tarea el archivo `.md` que lo acompaña (mismo nombre base, misma carpeta), o crearse si todavía no existe.
- Dicho `.md` debe describir qué es el Custom Analysis y su funcionamiento, así como instrucciones para el usuario: cómo configurarlo en SQX, sus Input Arguments (nombre, valor por defecto, descripción, ejemplo) y los outputs/columnas de Databank que genera.
- No se considerará completa una modificación de un Custom Analysis hasta que su `.md` esté actualizado y sea coherente con el código.
