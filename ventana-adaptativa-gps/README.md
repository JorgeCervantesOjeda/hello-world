# Ventana Adaptativa GPS

Prototipo Android que simula una función de software para ajustar automáticamente la apertura de una ventana según la velocidad del vehículo obtenida por GPS.

## Comportamiento

- La función está apagada por defecto.
- Al activarla, toma inmediatamente la apertura actual como referencia.
- Si el vehículo circula a más de 5 km/h, también toma la velocidad actual y comienza a regular.
- Si está detenido, conserva la apertura y fija la velocidad de referencia al superar 5 km/h.
- Subir o bajar manualmente la ventana suspende temporalmente el control.
- Al terminar el movimiento manual, la nueva apertura se convierte en referencia si es válida.
- Los cierres automáticos nunca dejan menos de 25 mm de apertura.
- Abrir totalmente la ventana estando detenido no crea una referencia de flujo.
- Incluye un modo de prueba para simular velocidad sin conducir.

## Caída de velocidad durante un movimiento

Si la velocidad baja a 5 km/h o menos mientras la ventana todavía se dirige hacia un objetivo automático:

1. Se congela el último objetivo calculado con una velocidad válida.
2. La ventana continúa hasta alcanzar ese objetivo.
3. Una vez alcanzado, el control se suspende mientras la velocidad siga por debajo del umbral.
4. No se calculan objetivos nuevos utilizando una velocidad cercana a cero.
5. Una intervención manual sigue cancelando inmediatamente el movimiento automático.

## Aprendizaje del tiempo de recorrido

La aplicación aprende por separado el tiempo equivalente del recorrido completo de apertura y de cierre.

Cada movimiento automático completado mide:

- posición inicial;
- posición final;
- distancia recorrida;
- tiempo transcurrido;
- sentido de movimiento.

A partir de esos datos estima el tiempo equivalente para recorrer los 450 mm completos. Las mediciones se actualizan gradualmente y se guardan para las siguientes ejecuciones.

El límite de un movimiento parcial se calcula como:

```text
tiempo_esperado = tiempo_recorrido_completo × distancia / 450

tiempo_límite = tiempo_esperado × 1.4 + 0.3 segundos
```

Si la ventana no alcanza el objetivo dentro del tiempo calculado, el movimiento se detiene y la regulación queda anulada hasta una nueva intervención del usuario.

En el simulador el tiempo completo inicial es de 17 segundos, coherente con la velocidad visual del cristal. Se sustituye por los valores aprendidos cuando existen movimientos suficientes para medirlo.

## Fórmula de apertura

```text
apertura_objetivo = apertura_referencia × velocidad_referencia / velocidad_actual
```

El resultado se limita al intervalo de 25 a 450 mm.

## Requisitos

- Android 8.0 o posterior.
- Permiso de ubicación para utilizar el GPS.
- Java 17 y Android SDK 35 para compilar.

## Compilación

Desde la raíz del repositorio:

```bash
gradle -p ventana-adaptativa-gps assembleDebug
```

El APK se genera en:

```text
ventana-adaptativa-gps/app/build/outputs/apk/debug/app-debug.apk
```

También se incluye un flujo de GitHub Actions que compila el APK y lo publica como artefacto.

## Alcance

Esta aplicación es un simulador visual. No se conecta al módulo de puertas ni controla ventanas reales.

En una implementación real, el controlador del elevalunas debería medir los recorridos físicos usando su posición estimada, sus finales de carrera y el tiempo real del motor.

No manipules la aplicación mientras conduces.
