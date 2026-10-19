# 13. `FilenamePascalCase` sin `package-info.java` ni `module-info.java`

**Pedimos:** que `FilenamePascalCase` no se aplique a `package-info.java` ni
a `module-info.java`.

## Qué pasa hoy

`FilenamePascalCase` marca `package-info.java` y `module-info.java` porque no
están en PascalCase. Son nombres que exige Java: no se pueden cambiar.

## Qué hacemos mientras

`KnownFalsePositives`, dentro del analizador, quita solo ese caso. Es el
único hallazgo de Sheriff que este repositorio descarta. La e2e (escenario
16) comprueba que nunca se informa, pero por eso mismo no dice si Sheriff lo
sigue dando: no lo hemos vuelto a comprobar con la imagen del 19 de octubre.

## Si se resuelve

Desaparece `KnownFalsePositives`, y con él la única excepción a "no se quita
ninguna regla".
