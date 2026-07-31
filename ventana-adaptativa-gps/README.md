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

## Fórmula simulada

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

No manipules la aplicación mientras conduces.
