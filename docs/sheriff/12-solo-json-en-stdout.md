# 12. Solo JSON en stdout

**Pedimos:** que stdout lleve solo el JSON de los hallazgos. El banner de uso,
las trazas de los checkers y los mensajes de progreso, a stderr.

## Qué pasa hoy

Comprobado con la imagen del 19 de octubre:

- Con un perfil que no existe, `test` escribe el banner de uso en stdout
  (`Usage: Sheriff [options] [command] [command options]...`), el motivo en
  stderr (`ERROR. Test 'JAVAX' not valid`) y sale con 0. Quien busque el
  array de hallazgos por el primer `[` lee el banner.
- El perfil `TYPESCRIPT` escribe una línea `COMMAND: node node_modules/@kaizten/typescript-ast-analyzer/... <fichero>`
  por cada checker y fichero, antes de los hallazgos: seis para un solo
  fichero de una línea. Un componente TypeScript limpio son solo esas líneas,
  que no son JSON.

## Cómo reproducirlo

```bash
mkdir -p web/src && echo 'export const a = 1;' > web/src/a.ts
docker run --rm --mount type=bind,source="$PWD",target=/data kaizten/sheriff:latest \
  test --test TYPESCRIPT --uri file:/data --component web 2>/dev/null | head -3
```

## Qué hacemos mientras

Los hallazgos se leen de `sheriff_errors.json`, y stdout es solo el plan B:
el analizador descarta las líneas `COMMAND:` y reconoce el banner.

## Si se resuelve

stdout se podría leer directamente, sin pasar por el fichero de estado, y el
análisis no dependería de dejar estado en la carpeta montada.
