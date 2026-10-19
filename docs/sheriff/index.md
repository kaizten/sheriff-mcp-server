# Peticiones al equipo de Sheriff

Lo que este repositorio necesita de la imagen `kaizten/sheriff`, para
hablarlo con el equipo de Sheriff. Cada documento es una petición: qué pasa
hoy, cómo reproducirlo con el CLI de Sheriff y nada más, qué pedimos, qué
hacemos aquí mientras tanto y qué código de aquí desaparece cuando se
resuelva.

Casi todo el código defensivo de este repositorio existe por una de estas.
Cuando Sheriff resuelva una, se quita el rodeo de aquí y se borra su documento
en el mismo cambio.

| Nº | Petición | Prioridad | Comprobado con |
|---|---|---|---|
| [01](01-descripciones-de-una-linea.md) | Descripciones de una línea, sin la salida de Maven dentro | Alta | imagen del 19 oct. |
| [02](02-reglas-del-pom.md) | Reglas de `pom.xml` que juzguen lo que declara el fichero, no lo que trae un BOM | Alta | imagen del 19 oct. |
| [03](03-dependencias-desactualizadas.md) | Que una regla no dependa de la red: la misma respuesta para el mismo código | Alta | imagen del 19 oct. |
| [04](04-exportar-el-catalogo.md) | Exportar el catálogo de reglas: código, mensaje, fixer y perfiles | Alta | imagen del 19 oct. |
| [05](05-cero-errores.md) | Distinguir "cumple", "no había nada que analizar" y "no se generó informe" | Media | sept. y oct. |
| [06](06-estado-incremental.md) | Estado incremental que recuerde perfil y versión de las reglas | Media | 28 sept. |
| [07](07-fixers-jar.md) | Fixers en jar que se puedan ejecutar | Media | imagen del 21 sept. |
| [08](08-fix-sin-fixers.md) | `fix -f` sin `--fixers`: todos los fixers de los errores del estado | Media | 1 oct. |
| [09](09-excluir-carpetas.md) | Poder excluir carpetas del análisis (lo que escribe el build) | Media | 13 oct. |
| [10](10-perfiles-de-arquitectura.md) | Perfiles de arquitectura que incluyan las reglas base del lenguaje | Media | 29 sept. |
| [11](11-codigos-de-salida.md) | Códigos de salida con significado | Baja | imagen del 19 oct. |
| [12](12-solo-json-en-stdout.md) | Solo JSON en stdout | Baja | imagen del 19 oct. |
| [13](13-filename-pascalcase.md) | `FilenamePascalCase` sin `package-info.java` ni `module-info.java` | Baja | sept. |

"Imagen del 19 oct." es `kaizten/sheriff@sha256:4127439f27142691db49b4fbe5690886f5653eed21c31b425ab8a45bc1130487`.

## Cómo se reproduce

Todo se reproduce con la imagen sola, sin nada de este repositorio. Sheriff
analiza una subcarpeta de lo que se monta, así que se monta la carpeta padre
y se nombra el componente:

```bash
docker run --rm --mount type=bind,source="$PWD",target=/data kaizten/sheriff:latest \
  test --test JAVA --uri file:/data --component app
cat sheriff_errors.json        # lo que encontró; Sheriff deja tres sheriff_*.json en la carpeta montada
```

## Cómo se añade una

Un documento por petición, con su número, y una línea en la tabla. Lleva lo
medido, no lo supuesto: la fecha, la imagen y la salida real. Termina con lo
que haríamos aquí si se resuelve, que es lo que la justifica.
