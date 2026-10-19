# 05. Distinguir "cumple", "no había nada que analizar" y "no hubo informe"

**Pedimos:** que la salida diga cuántos ficheros del lenguaje del perfil
analizó. Así, 0 errores sobre 0 ficheros no se confunde con 0 errores sobre
40. Y que, si un análisis no llega a generar informe, termine con un código
de salida distinto de 0.

## Qué pasa hoy

Cero errores puede significar tres cosas, y la salida es la misma en las
tres (lista vacía y código 0):

1. el componente cumple;
2. no tiene nada del lenguaje del perfil (por ejemplo `JAVA` sobre una
   carpeta con solo TypeScript);
3. la ejecución no produjo informe.

La tercera la vimos una vez: un análisis respondió 0 errores en un
componente que tenía 221, y no se pudo reproducir.

La salida de `fix` ya trae algo parecido a lo que pedimos para `test`:
`filesByType=(TYPESCRIPT=0 files,PYTHON=0 files,JAVA=0 files,...)`.

## Cómo reproducirlo

```bash
mkdir -p none/src
docker run --rm --mount type=bind,source="$PWD",target=/data kaizten/sheriff:latest \
  test --test JAVA --uri file:/data --component none; echo "exit $?"
```

Con la imagen del 19 de octubre: código 0 y ningún error, igual que en un
componente Java que cumple.

## Qué hacemos mientras

Todas las herramientas miran el componente por su cuenta, buscando fuentes
del lenguaje del perfil. Y el analizador lee `sheriff_errors.json`, que
registra el componente y el perfil analizados, para distinguir una ejecución
limpia de una que no hizo nada. `--check-only` falla con "Nothing was
analyzed" en vez de dar el aprobado.

## Si se resuelve

Desaparece la búsqueda de fuentes por lenguaje que duplica lo que Sheriff ya
sabe, y con ella el riesgo de que las dos discrepen (por ejemplo, el perfil
de Vue también lee `.ts` y `.js`).
