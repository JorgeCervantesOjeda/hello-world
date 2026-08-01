# Ventana Adaptativa GPS

Prototipo Android que simula un control de apertura de ventana para mantener aproximadamente constante el flujo asociado a la velocidad del vehículo.

## Modelo de control

En el simulador se supone una relación lineal entre recorrido del cristal y área libre de paso. Todas las aperturas y objetivos se almacenan como `double`.

Al finalizar un movimiento manual positivo:

```text
apertura_referencia = apertura_manual
velocidad_referencia = máximo(velocidad_real, 5 km/h)
```

Durante la operación:

```text
apertura_objetivo = apertura_referencia × velocidad_referencia / velocidad_real
```

- A 0 km/h, el objetivo es la apertura máxima de 450 mm.
- Con cualquier velocidad positiva, el objetivo automático se mantiene estrictamente mayor que cero.
- El límite de 25 mm fue eliminado.
- El requisito manual de 30 mm fue eliminado: cualquier apertura positiva puede definir una referencia.
- Solo un cierre manual completo puede establecer 0 mm; ese cierre elimina la referencia e impide que el sistema vuelva a abrir la ventana por sí solo.

## Control continuo sin enteros

La apertura manual ya no utiliza un `SeekBar` entero. Se obtiene directamente de la posición táctil sobre la representación de la ventana y se conserva como `double`.

El recorrido, la referencia, el objetivo y los pasos automáticos no se convierten a `int`. La interfaz formatea los valores únicamente para mostrarlos, sin modificar el estado interno.

Para proteger el principio de área positiva, un objetivo automático que matemáticamente pudiera subdesbordarse a cero se limita a `Double.MIN_VALUE`, la menor magnitud positiva representable por `double`. No es un mínimo físico de apertura.

## Velocidad GPS almacenada

- La velocidad se inicializa en 0 km/h al arrancar la aplicación.
- Una lectura GPS válida reemplaza el valor almacenado.
- Una ubicación sin velocidad utilizable conserva el valor anterior.
- Desactivar o reactivar el proveedor GPS conserva la última velocidad.
- Al salir del modo simulado se recupera la última velocidad GPS, no se fuerza cero.

## Movimiento y aprendizaje

La simulación mueve la ventana hasta 15 mm cada 300 ms. El tiempo inicial equivalente del recorrido completo es de aproximadamente 9 segundos.

La aplicación aprende por separado los tiempos de apertura y cierre a partir de movimientos automáticos completados. El tiempo límite de cada recorrido parcial se calcula con la distancia, el tiempo aprendido, un margen del 40 % y 0.3 segundos adicionales.

## Uso

1. Activa el control adaptativo.
2. Arrastra verticalmente el borde del cristal para elegir una apertura positiva.
3. Al soltar, esa apertura y la velocidad almacenada definen la referencia.
4. La ventana sigue el objetivo continuo conforme cambia la velocidad.
5. Para cancelar la referencia, arrastra manualmente el cristal hasta 0 mm.

## Requisitos

- Android 8.0 o posterior.
- Permiso de ubicación para recibir velocidad GPS.
- Java 17 y Android SDK 35 para compilar.

## Compilación

```bash
gradle -p ventana-adaptativa-gps assembleDebug
```

El APK se genera en:

```text
ventana-adaptativa-gps/app/build/outputs/apk/debug/app-debug.apk
```

## Alcance

La aplicación es un simulador visual y no controla una ventana física. Para un vehículo real, la conversión entre posición y área debe obtenerse mediante una calibración geométrica o una tabla del mecanismo.

No manipules la aplicación mientras conduces.
