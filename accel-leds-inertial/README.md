# Accel LEDs Inercial

Prototipo Android para visualizar aceleración y desaceleración con 20 segmentos.

- La indicación usa el sensor de aceleración lineal del teléfono.
- Se aplica un filtro pasa bajas corto al vector de aceleración.
- El eje longitudinal del vehículo se aprende mediante calibración, sin asumir la orientación del teléfono.
- El GPS se usa únicamente como referencia secundaria de velocidad.

## Calibración

1. Fija el teléfono rígidamente al vehículo.
2. Pulsa **CALIBRAR** estando detenido.
3. Espera la cuenta inicial y acelera suavemente hacia delante, en línea recta, durante la fase indicada.
4. No cambies la posición del teléfono tras calibrarlo.
5. Usa **AJUSTAR CERO** estando completamente detenido si aparece una pequeña lectura residual.

Esta rama existe para compilar y validar el APK generado por GitHub Actions.

Uso experimental en recinto seguro. No manipules el teléfono mientras conduces.
