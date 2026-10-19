# 11. Códigos de salida con significado

**Pedimos:**

1. Que `test` salga con un código distinto de 0 cuando encuentra errores (por
   ejemplo 1, y 2 para "no se pudo analizar"), como cualquier linter.
2. Que un perfil que no existe salga con un código distinto de 0.
3. Que un `--fixers` con un código desconocido dé un mensaje de una línea,
   no un stack trace de Java.

## Qué pasa hoy

Comprobado con la imagen del 19 de octubre:

| Caso | Código |
|---|---|
| `test` con errores | 0 |
| `test` de un componente que no existe | 1 |
| `test` con un perfil que no existe (`--test JAVAX`) | 0, con el banner de uso en stdout ([12](12-solo-json-en-stdout.md)) |
| `fix -f` que repara | 0 (antes salía siempre con 1) |
| `fix -f` sin estado de un `test` anterior | 0 |
| `fix -f` de un componente que no existe | 1 |
| `fix -f --fixers NoSuchFixer` | 1, con `Exception in thread "main" java.lang.IllegalArgumentException: Invalid fixer reference code` y la lista entera de fixers en una línea |

Lo de `fix` ha mejorado. Lo que queda es `test`: con su código no se sabe
si hubo errores.

## Qué hacemos mientras

No usamos el código de salida de Sheriff para nada. Cada resultado se decide
por la salida o analizando de nuevo, y los errores se cuentan en
`sheriff_errors.json`.

## Si se resuelve

El gate de CI (`--check`) y el plugin de Maven podrían fiarse del código de
`test`. Hoy no cambiaría mucho más, porque igualmente necesitamos la lista de
errores.
