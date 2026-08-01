# Ventana Adaptativa GPS

Prototipo Android que simula una función de software para ajustar automáticamente la apertura de una ventana según la velocidad del vehículo obtenida por GPS.

## Comportamiento

- La función está apagada por defecto.
- Al activarla, toma inmediatamente la apertura actual como referencia si existe una velocidad válida.
- Subir o bajar manualmente la ventana suspende temporalmente el control.
- Al soltar el control manual, la apertura final se convierte en la nueva referencia si es válida.
- Los cierres automáticos nunca dejan menos de 25 mm de apertura.
- Abrir totalmente la ventana a 0 km/h no crea una referencia nueva.
- Incluye un modo de prueba para simular velocidad sin conducir.

## Velocidad mínima de referencia

La velocidad mínima de 5 km/h solo se usa al capturar una referencia.

Al soltar el control manual:

```text
velocidad_referencia = máximo(velocidad_real_al_soltar, 5 km/h)
apertura_referencia = apertura_al_soltar
```

Ejemplo: si se suelta la ventana con una apertura de 50 mm y una velocidad real de 2 km/h, la referencia queda en 50 mm a 5 km/h.

Durante la regulación no se sustituye la velocidad real por la mínima. El objetivo se calcula continuamente con la velocidad real:

```text
apertura_objetivo = apertura_referencia × velocidad_referencia / velocidad_real
```

En el ejemplo anterior, mientras la velocidad real continúe en 2 km/h:

```text
apertura_objetivo = 50 × 5 / 2 = 125 mm
```

Cualquier velocidad válida puede utilizarse durante la operación, aunque sea menor de 5 km/h.

## Conservación de la velocidad GPS

La velocidad utilizada por el control se inicializa en 0 km/h. Cada actualización GPS solo sustituye ese valor cuando incluye una velocidad válida. Si la ubicación no contiene velocidad, o el campo no es utilizable, se conserva la última velocidad conocida y la regulación continúa. GPS desactivado o permiso denegado siguen siendo fallos reales.

## Velocidad cero

Una lectura válida de 0 km/h es un estado operativo válido. Como no puede dividirse entre cero, su objetivo es la apertura máxima de 450 mm.

Una lectura GPS ausente, inválida o desactivada no se interpreta como 0 km/h. En ese caso la regulación se suspende sin generar un objetivo nuevo.

## Velocidad de movimiento de la ventana

La versión 2.4.1 acelera la simulación de apertura y cierre automático de 8 a 15 mm por ciclo de 300 ms, aproximadamente 1.9 veces la velocidad anterior.

El recorrido visual completo de 450 mm tarda inicialmente unos 9 segundos, en lugar de 17 segundos. Después se sustituye por los tiempos aprendidos de apertura y cierre.

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

## Límites

El objetivo automático se limita al intervalo de 25 a 450 mm.

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
