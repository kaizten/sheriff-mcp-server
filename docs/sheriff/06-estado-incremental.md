# 06. Estado incremental que recuerde perfil y versión de las reglas

**Pedimos:**

1. Que el estado incremental (`sheriff_tracked_files.json`) guarde, junto a
   cada hash, el perfil y la versión de las reglas o de la imagen, y que no
   reutilice un resultado si alguno de los dos cambió.
2. Que la carpeta de ese estado se pueda elegir con una opción, en vez de ir
   siempre a la raíz de lo montado.

## Qué pasa hoy

Sheriff se salta cada fichero cuyo hash ya está en
`sheriff_tracked_files.json` y reutiliza lo que `sheriff_errors.json` decía
de él. Pero ese fichero solo tiene rutas y hashes. Reproducido el 28 de
septiembre: el estado que dejó una ejecución de `JAVA_HEXAGONAL` (0 errores)
hizo que la siguiente de `JAVA` diera 0 errores en código que tenía 3. La
entrada `JAVA` que registró existía y estaba vacía, así que desde fuera no
se nota. Con una imagen nueva con reglas nuevas pasaría igual.

Cada ejecución deja además tres ficheros (`sheriff_errors.json`,
`sheriff_tracked_files.json` y `sheriff_summary.json`, este siempre `{ }`) en
la raíz de lo montado, que en un proyecto de un solo módulo es la carpeta
padre, compartida con otros proyectos.

## Cómo reproducirlo

```bash
docker run --rm --mount type=bind,source="$PWD",target=/data kaizten/sheriff:latest \
  test --test JAVA_HEXAGONAL --uri file:/data --component app
docker run --rm --mount type=bind,source="$PWD",target=/data kaizten/sheriff:latest \
  test --test JAVA --uri file:/data --component app      # sin borrar los sheriff_*.json
```

Con un `app` que tenga errores de estilo fuera de las capas hexagonales, la
segunda da menos errores que la misma ejecución sobre una carpeta limpia. No
lo hemos vuelto a comprobar con la imagen del 19 de octubre.

## Qué hacemos mientras

Borramos el estado antes y después de cada ejecución, así que perdemos la
velocidad incremental. Y para que los ficheros no caigan en el proyecto,
montamos una carpeta de estado propia en `/data` y el componente solo en
`/data/<componente>` (`SheriffContainer`). Así, dos proyectos hermanos
analizados a la vez no se pisan el estado.

## Si se resuelve

Podríamos guardar el estado entre ejecuciones y tener la velocidad
incremental, que en un proyecto grande a través de WSL importa (minutos).
