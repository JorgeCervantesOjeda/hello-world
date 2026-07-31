# Ventana Adaptativa GPS

Prototipo Android que simula una función de regulación automática de la apertura de una ventana según la velocidad obtenida por GPS.

## Reglas implementadas

- La función inicia apagada.
- Puede activarse o desactivarse libremente.
- La apertura manual de al menos 30 mm, circulando a más de 5 km/h, captura una referencia.
- La apertura objetivo se calcula como `aperturaReferencia × velocidadReferencia / velocidadActual`.
- El cierre automático nunca baja de una ranura de 25 mm.
- Cualquier subida manual cancela inmediatamente la regulación y permite cerrar completamente.
- Abrir totalmente a velocidad cero no crea referencia.
- Al detenerse, la regulación queda suspendida y conserva la posición.
- Incluye modo de prueba para variar la velocidad sin conducir.

La app es únicamente una simulación visual y no controla sistemas reales del automóvil.
