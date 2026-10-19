# 02. Reglas de `pom.xml` que juzguen lo que declara el fichero

**Pedimos:**

1. Que las reglas de nombre de un `pom.xml` (`artifactId` sin guiones bajos)
   se apliquen a lo que el fichero declara, no a lo que trae un BOM importado
   con `<scope>import</scope>`.
2. Un `referenceCode` por comprobación. Hoy `JavaPomValidation` agrupa cosas
   muy distintas, y algunas salen sin código.
3. Que una regla propia de Kaizten (la URL del proyecto) no esté en el perfil
   general `JAVA`, o que se pueda configurar.

## Qué pasa hoy

Un pom que solo importa el BOM de Jackson recibe cuatro errores que **ningún
proyecto puede arreglar**: son nombres de artefactos de Jackson, no del
proyecto.

```text
- [JavaPomValidation] Maven artifactId 'jackson-module-scala_2.11' must not contain underscores.
- [JavaPomValidation] Maven artifactId 'jackson-module-scala_2.12' must not contain underscores.
- [JavaPomValidation] Maven artifactId 'jackson-module-scala_2.13' must not contain underscores.
- [JavaPomValidation] Maven artifactId 'jackson-module-scala_3' must not contain underscores.
```

Spring Boot importa ese BOM, así que es probable que afecte a casi todos esos
proyectos. No lo hemos medido en uno.

Bajo el mismo código `JavaPomValidation` salen, con la imagen del 19 de
octubre, el guion bajo de un `artifactId`, una versión que no usa una
propiedad, la versión de Java sin configurar, el `groupId` que falta y el
fallo de Maven del [01](01-descripciones-de-una-linea.md). Con un solo código
no se puede consultar ni reparar una de ellas: `--fixers` y el catálogo
trabajan por código.

Y dos salen sin `referenceCode`, como `WARNING`:

```text
- [] Description of project is not defined
- [] Project URL must be https://www.kaizten.com
```

La segunda pide la URL de Kaizten a cualquier proyecto Java.

## Cómo reproducirlo

```bash
mkdir -p bom/src/main/java/demo
cat > bom/pom.xml <<'POM'
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>demo</groupId>
    <artifactId>bom</artifactId>
    <version>1.0</version>
    <properties>
        <jackson.version>2.18.2</jackson.version>
    </properties>
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>com.fasterxml.jackson</groupId>
                <artifactId>jackson-bom</artifactId>
                <version>${jackson.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
POM
printf 'package demo;\n\n/**\n * A.\n */\npublic class A {\n}\n' > bom/src/main/java/demo/A.java
docker run --rm --mount type=bind,source="$PWD",target=/data kaizten/sheriff:latest \
  test --test JAVA --uri file:/data --component bom
```

## Por qué importa

Las herramientas de este repositorio no dejan terminar un turno con errores
(el Stop hook), y el plugin de Maven falla el build con ellos. Un error que no
se puede arreglar bloquea las sesiones del asistente y los builds de
cualquier proyecto que importe ese BOM. Aquí mismo, `sheriff-mcp-java` se
queda con esos cuatro.

## Qué hacemos mientras

Nada en el código: este repositorio no quita reglas, salvo falsos positivos
demostrados (`KnownFalsePositives`). Los cuatro de `sheriff-mcp-java` están
anotados en CLAUDE.md. Se podría filtrar un `artifactId` que no aparece en el
propio `pom.xml`, pero preferimos que lo resuelva Sheriff.

## Si se resuelve

Los dos módulos de aquí vuelven a 0 errores bajo `JAVA` sin excepciones
anotadas.
