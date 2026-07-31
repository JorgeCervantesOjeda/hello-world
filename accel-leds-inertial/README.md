# Accel LEDs Inercial

Prototipo Android para visualizar aceleración y desaceleración con 20 segmentos.

- La indicación usa el sensor de aceleración lineal del teléfono.
- Se aplica un filtro pasa bajas corto al vector de aceleración.
- El eje longitudinal del vehículo se aprende mediante calibración, sin asumir la orientación del teléfono.
- El GPS se usa únicamente como referencia secundaria de velocidad.

## Calibración

1. Fija el teléfono rígidamente al vehículo.
2. Pulsa **CALIBRAR** estando detenido.
3. Durante la fase indicada, acelera hacia delante en línea recta y después frena suavemente.
4. No cambies la posición del teléfono tras calibrarlo.

Uso experimental en recinto seguro. No manipules el teléfono mientras conduces.
