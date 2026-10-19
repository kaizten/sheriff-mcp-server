# 04. Exportar el catálogo de reglas

**Pedimos:** un comando que exporte, para cada regla de la imagen, en JSON:

- el `referenceCode` (el que llevan los hallazgos y el que acepta `--fixers`);
- la plantilla del mensaje (`description`) y del `howToSolve`;
- si tiene fixer, y cuál;
- los perfiles que la ejecutan, todos, incluidos los de arquitectura;
- si se puede, un ejemplo de código que la cumple.

## Qué pasa hoy

Sheriff no trae documentación de sus reglas. `tree --export-profile JSON` da,
con la imagen del 19 de octubre, cinco campos por nodo: `displayName`,
`description`, `active`, `settings` y `children`. No trae el código de
referencia, ni si hay fixer, ni la plantilla del mensaje. Además, familias
enteras de reglas comparten una hoja del árbol, así que con el árbol solo no
se sabe en qué perfil está cada código.

## Qué hacemos mientras

Un extractor (`RuleCatalogExtractor`, `CatalogBuilder`) que lee el catálogo
de dentro de la imagen: el árbol, más el bytecode de las clases de mensajes
y de los checkers, más los scripts de los fixers. Con la imagen del 19 de
octubre saca 534 reglas, 89 con fixer, y le quedan 87 comprobaciones sin un
código que les corresponda. Tarda unos 16 s a través del Docker de WSL.
Alrededor hay una comprobación de que el catálogo es de la imagen instalada
(`CatalogFreshness`) y una caché que lo vuelve a extraer cuando cambia la
imagen (`CatalogProvisioning`).

Es código frágil por naturaleza: depende de cómo están compiladas las clases
de la imagen, y cualquier cambio interno de Sheriff puede romperlo.

El catálogo no está en este repositorio: se lee de la imagen, que es de
Kaizten.

## Por qué importa

`sheriff_guidelines` enseña al modelo la regla y el código que la cumple
antes de escribir. El deterministic fixer elige con él qué `--fixers` pasar.
Sin catálogo, el modelo trabaja a ciegas y la reparación usa solo el conjunto
por defecto.

## Si se resuelve

Desaparecen el extractor, la lectura de bytecode, la comprobación de que el
catálogo es de la imagen actual y la provisión en segundo plano: el catálogo
se pediría a Sheriff cada vez.
