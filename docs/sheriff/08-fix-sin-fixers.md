# 08. `fix -f` sin `--fixers`: todos los fixers de los errores del estado

**Pedimos:** que `fix -f` sin `--fixers` ejecute el fixer de cada error que
dejó el `test` anterior, y solo esos. Si el conjunto por defecto tiene que
ser otro, que se documente cuál es.

## Qué pasa hoy

`fix -f` sin `--fixers` ejecuta un conjunto por defecto que no son todos los
fixers, y tampoco solo los de los errores del estado. El 1 de octubre ordenó
los imports de PetClinic después de haber borrado de `sheriff_errors.json`
todas las entradas de `SortedImport`, así que ejecuta al menos uno sin que el
estado lo pida. Y deja fuera fixers cuyos errores sí están en el estado. Con
`--fixers A,B` ejecuta solo esos, e ignora los códigos que no tienen fixer en
la lista.

## Qué hacemos mientras

`sheriff_fix` ejecuta primero el conjunto por defecto y luego `--fixers` con
todos los códigos encontrados, en rondas mientras baje la cuenta, hasta tres
(`SheriffRepair`). Así cada llamada llega al mismo resultado. Cuesta un
análisis por ronda, que a través de WSL son minutos en un proyecto grande.

## Si se resuelve

Una sola llamada a `fix -f` en vez de rondas, y el catálogo deja de hacer
falta para reparar.
