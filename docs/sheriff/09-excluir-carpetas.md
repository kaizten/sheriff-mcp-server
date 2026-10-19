# 09. Poder excluir carpetas del análisis

**Pedimos:** una opción de `test` y de `fix` para excluir carpetas (por
ejemplo `--exclude target,build,.gradle`), o que Sheriff no entre por defecto
en lo que escribe el build: `target/` junto a un `pom.xml`, y `build/` y
`.gradle/` junto a un `build.gradle`.

## Qué pasa hoy

Sheriff analiza todos los ficheros del componente, incluido lo que generó el
build, y `test` no acepta más que componente, perfil, URI y repositorio. El
13 de octubre, en PetClinic después de un build de Gradle con AOT, 10.082 de
sus 10.311 errores estaban en `build/generated`. El análisis tardó 7 min 27 s
desde `C:\` a través del Docker de WSL, frente a 6 s en el disco propio de
WSL. Y el modelo se puso a arreglar código generado.

## Qué hacemos mientras

`SheriffContainer` tapa dentro del contenedor, con un `tmpfs` vacío,
`target/` junto a un `pom.xml`, y `build/` y `.gradle/` junto a un
`build.gradle`. Así Sheriff no los lee ni los repara, y en el disco no cambia
nada. Va por el fichero de build, no solo por el nombre, porque `build/` puede
ser código propio en otro tipo de proyecto. La e2e de este repositorio
(escenario 20) lo comprueba.

## Si se resuelve

Desaparecen los `tmpfs` y la detección de carpetas de build.
