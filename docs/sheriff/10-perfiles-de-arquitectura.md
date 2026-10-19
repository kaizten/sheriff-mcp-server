# 10. Perfiles de arquitectura que incluyan las reglas base

**Pedimos:** que `JAVA_HEXAGONAL`, `JAVA_DDD` y el resto de perfiles de
arquitectura incluyan las reglas base de su lenguaje (`JAVA`), o que `test`
acepte varios perfiles en una ejecución (`--test JAVA,JAVA_HEXAGONAL`).

## Qué pasa hoy

Medido el 29 de septiembre: un fichero con cinco fallos de estilo recibe
cinco errores bajo `JAVA` esté donde esté. Bajo `JAVA_HEXAGONAL` no recibe
ninguno en `infrastructure/` ni fuera de las capas, seis en `domain/model/`
y dos en `application/service/`. `JAVA_DDD` se comporta igual. Así que un
proyecto que declara su arquitectura pierde las reglas base fuera de ciertas
carpetas. Y `test` acepta un solo perfil por ejecución.

## Qué hacemos mientras

`Rules.withBaseProfiles` pone el perfil base delante de cada perfil de
arquitectura, venga de donde venga. Las herramientas hacen un análisis por
perfil y juntan los resultados (`AnalysisResult.merged`), y reparan perfil a
perfil, porque `fix` lee el estado del `test` justo anterior. Cuesta un
análisis más por cada perfil de arquitectura.

## Si se resuelve

Un análisis por componente en vez de dos, y desaparecen la fusión de
resultados y la reparación perfil a perfil.
