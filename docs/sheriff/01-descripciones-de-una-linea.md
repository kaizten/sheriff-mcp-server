# 01. Descripciones de una línea, sin la salida de Maven dentro

**Pedimos:** que la `description` de un hallazgo sea una frase de una línea.
Si una herramienta falla, que la descripción lo diga en una frase y que su
salida vaya a otro campo (por ejemplo `details`), o a ninguno.

## Qué pasa hoy

Con la imagen del 19 de octubre, `JAVA` comprueba el `pom.xml`. Cuando Maven
no puede leer el pom, Sheriff pega **la salida entera de Maven, unas 20
líneas**, en la descripción de un solo hallazgo:

```text
- [JavaPomValidation] Cannot complete Maven dependency/version checks for '/data/empty/pom.xml': Maven POM checks failed: [INFO] Scanning for projects...
[ERROR] [ERROR] Some problems were encountered while processing the POMs:
[ERROR] 'modelVersion' is missing. @ line 1, column 11
[FATAL] 'groupId' is missing. @ line 1, column 11
...
[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/ProjectBuildingException
```

Además, los tres errores anteriores del mismo pom ya dicen lo mismo en una
línea cada uno (`groupId is missing...`, `artifactId is missing...`,
`version is missing...`): el cuarto los repite dentro del log.

Pasa algo parecido, en pequeño, con el código citado: `Braces are not used in
if statement 'if (a > 100)\n    return a;' (lines 6-6).` lleva el salto de
línea y la sangría del fuente. Ahí molesta menos, pero tampoco cabe en una
línea de un log.

## Cómo reproducirlo

```bash
mkdir -p empty/src/main/java/demo && echo '<project/>' > empty/pom.xml
printf 'package demo;\n\n/**\n * A.\n */\npublic class A {\n}\n' > empty/src/main/java/demo/A.java
docker run --rm --mount type=bind,source="$PWD",target=/data kaizten/sheriff:latest \
  test --test JAVA --uri file:/data --component empty
python3 -c "import json; d=json.load(open('sheriff_errors.json')); [print(len(e['description'].splitlines()), e['description'].splitlines()[0][:80]) for c in d.values() for p in c.values() for es in p.values() for e in es]"
```

El último hallazgo tiene 20 líneas.

## Por qué importa

Las herramientas de este repositorio enseñan los errores a un modelo y a una
persona, una línea por error. El mensaje del Stop hook (los cinco primeros
errores) pasó de 14 líneas a 34 por ese único hallazgo, y la e2e de este
repositorio falló en `main` en cuanto CI descargó la imagen nueva. En un
`[ERROR]` de Maven o de un IDE, cada línea extra pierde el prefijo y queda
suelta.

## Qué hacemos mientras

`SheriffFinding.brief` (PR #38): pone la descripción en una línea y la corta
a 300 caracteres. Lo usan los hooks y las respuestas del MCP. `--check` y el
log del plugin de Maven la ponen en una línea, pero entera.

## Si se resuelve

`brief` deja de cortar nada, y el tope se puede quitar.
