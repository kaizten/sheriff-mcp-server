# 07. Fixers en jar que se puedan ejecutar

**Pedimos:** que los fixers empaquetados como jar se lancen con `java -jar`,
estén registrados con el nombre que produce su build, y hagan lo que dicen.
Y que un fixer que no cambia nada no diga que cambió algo.

## Qué pasa hoy

Medido con la imagen del 21 de septiembre; no lo hemos vuelto a comprobar
con la del 19 de octubre:

- Sheriff lanza los fixers en jar sin `-jar`, así que no puede ejecutarse
  ninguno.
- `BracesForStatements` está registrado con un nombre que su `pom.xml` no
  produce, y declara una clase principal que no existe. Su lógica funciona
  si se lanza a mano.
- `FolderNameUnderscore` es un esqueleto: renombra un `com.oldpkg` fijo a
  `com.newpkg`, nunca lee la carpeta que recibe, y dice que ha renombrado algo
  y sale con 0 en cualquier caso.

Un fixer que falla lo dice en stdout (`Could not apply fixer for error
'CODE'` y luego `Error: <motivo>`). Pero uno que sale con 0 sin hacer nada no
dice nada, y desde fuera no se distingue de uno que funcionó.

## Qué hacemos mientras

Leemos esas líneas de fallo (`FixerFailures`) y las nombramos en cada
informe. Y después de cada reparación analizamos de nuevo, porque es la única
forma de saber qué arregló de verdad: nunca damos por reparado un error
porque se haya ejecutado su fixer.

## Si se resuelve

Lo que hoy arregla el modelo a mano en esas reglas lo arreglaría Sheriff, sin
gastar tokens y siempre igual.
