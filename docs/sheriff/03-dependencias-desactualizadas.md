# 03. Que una regla no dependa de la red

**Pedimos:** que el mismo código reciba siempre la misma respuesta de la
misma imagen. `JavaPomOutdatedDependency` (y probablemente
`TypeScriptOutdatedDependency`, que también está en la imagen) pregunta por
la red cuál es la última versión, así que un proyecto que hoy pasa falla
mañana sin que nadie lo toque. Propuestas, de más a menos útil para
nosotros:

1. Que sea un `WARNING`, no un `ERROR`: informa, pero no bloquea.
2. O que vaya en un perfil aparte (`JAVA_DEPENDENCIES`) que cada proyecto
   elija.
3. O que compare con una lista de versiones fijada en la imagen (como el
   "stack" de `DEPENDENCY_VERSION_LOWER_THAN_STACK`), no con la red.

Y una excepción concreta: en un plugin de Maven, la versión de
`maven-plugin-api` (con `scope` `provided`) no se puede subir sin más.

## Qué pasa hoy

Con la imagen del 19 de octubre:

```text
- [JavaPomOutdatedDependency] Maven dependency 'com.fasterxml.jackson:jackson-bom' is outdated: 2.18.2 -> 2.22.4.
- [JavaPomOutdatedDependency] Maven dependency 'org.junit.jupiter:junit-jupiter' is outdated: 5.10.2 -> 6.1.3.
- [JavaPomOutdatedDependency] Maven dependency 'org.apache.maven:maven-core' is outdated: 3.9.6 -> 3.10.0.
```

El de junit propone un cambio de versión mayor (5 → 6).

El de `maven-core` y `maven-plugin-api` no se puede seguir en un plugin: el
`maven-plugin-plugin` escribe la versión de `maven-plugin-api` en el
descriptor como el Maven mínimo que exige el plugin (`requiredMavenVersion`).
Lo medimos: con 3.10.0, el plugin de este repositorio pasaba a rechazar
cualquier build en Maven 3.9, que es el que usa casi todo el mundo hoy.

## Cómo reproducirlo

El proyecto `bom` de la [02](02-reglas-del-pom.md) ya lo da para
`jackson-bom`. Sin red en el contenedor (`docker run --network none ...`) no
sabemos qué hace la regla: no lo hemos probado.

## Por qué importa

El plugin de Maven de este repositorio va en `mvn package`, y el Stop hook no
deja terminar un turno con errores. Con esta regla, cada versión que publique
cualquier dependencia de cualquier proyecto lo pone en rojo en CI y en las
sesiones del asistente, sin que cambie su código. Además se pierde lo que da
valor a un analizador en CI: repetir un build viejo y obtener lo mismo.

## Qué hacemos mientras

En este repositorio se actualizó lo que se podía (jackson 2.22.4, junit
6.1.3, plugin-tools 3.16.0; PR #38), y `maven-core` y `maven-plugin-api` se
quedan en 3.9.6, anotados en CLAUDE.md como falso positivo. Lo que no podemos
evitar es que vuelva a pasar con la siguiente versión de cualquier cosa.

## Si se resuelve

Las excepciones anotadas en CLAUDE.md desaparecen, y un módulo verde lo sigue
estando mientras no cambie.
