# Diseño: sonido al bajar grados de temperatura

Fecha: 2026-08-05
Versión prevista: 4.9
Rama: `agent/build-inertial-apk`

## Objetivo

Añadir un sonido grave y claramente distinto cada vez que la temperatura de la batería cruce hacia abajo un grado Celsius entero. El comportamiento de sonido existente al aumentar la temperatura no debe modificarse ni recrearse.

## Contexto verificado

La versión 4.8 obtiene la temperatura desde `BatteryManager.EXTRA_TEMPERATURE` en décimas de grado y actualiza `batteryTemperatureC`. En el código actualmente publicado en la rama no aparece una llamada explícita de audio asociada al aumento. Por ello, esta modificación será estrictamente descendente: no añadirá, sustituirá ni ajustará lógica de sonido ascendente. Si una versión posterior del archivo incorpora esa lógica antes de implementar este diseño, deberá conservarse sin cambios.

## Comportamiento

- La primera lectura válida solo inicializa el nivel térmico y no produce sonido.
- Cada límite entero cruzado hacia abajo genera un tono grave.
- Un salto de `42.3 °C` a `40.8 °C` genera dos tonos graves consecutivos.
- Un cambio dentro del mismo grado no genera sonido.
- Los cambios ascendentes no generan sonidos nuevos desde esta funcionalidad y no alteran el sonido ascendente existente.
- Los tonos pendientes se reproducen en secuencia, sin superposición.
- Duración objetivo por tono: aproximadamente 150–200 ms.
- Separación objetivo entre tonos: aproximadamente 100–150 ms.

## Detección con histéresis

Se mantendrá un nivel entero estable inicializado con `floor(temperaturaInicial)` y una histéresis de `0.2 °C`.

Para descender del nivel `N` al nivel `N-1`, la lectura debe ser menor o igual a `N - 0.2 °C`. Por cada nivel descendido se encola un tono. Para rearmar niveles al subir, la lectura debe superar el límite superior con la misma histéresis, pero esa actualización no reproducirá ningún sonido de esta funcionalidad.

Ejemplo:

- Nivel estable inicial: `42` con lectura `42.3 °C`.
- Lectura `41.8 °C`: cruza de `42` a `41`, un tono grave.
- Lectura `40.8 °C`: cruza de `41` a `40`, otro tono grave.
- Oscilaciones entre `41.9 °C` y `42.1 °C` no repiten el aviso.

## Audio

- Se usará un generador de tono integrado de Android, sin archivos de audio externos.
- La salida se dirigirá al canal de alarma con volumen del generador al máximo.
- El tono descendente será de carácter grave y diferente del sonido ascendente existente.
- La app no modificará ajustes de volumen del dispositivo.
- El sistema operativo todavía puede bloquear el audio mediante No molestar total, volumen de alarmas en cero, una ruta de audio externa u otras políticas del dispositivo.

## Ciclo de vida y cola

- El generador de tono se creará de forma controlada y se liberará al destruir la actividad.
- Un contador o cola registrará todos los grados descendidos pendientes.
- Un único `Runnable` reproducirá los tonos uno por uno.
- Al destruirse la actividad se cancelarán tareas pendientes para evitar fugas o sonidos posteriores.
- No se añadirá un servicio en segundo plano; el comportamiento aplica mientras la actividad esté activa y reciba lecturas de batería.

## Manejo de errores

- Lecturas ausentes o inválidas no cambian el nivel estable ni generan sonido.
- Si Android no puede crear el generador de tono, la app seguirá funcionando sin bloquearse.
- No se producirán excepciones visibles al usuario por fallos de audio.

## Alcance de código

Cambios previstos:

- `MainActivity.java`: estado térmico estable, histéresis, cola de tonos graves, reproducción y liberación de recursos.
- `app/build.gradle`: identificador y nombre de versión 4.9.
- Flujo de GitHub Actions: nombre del artefacto 4.9.

No se cambiarán:

- cálculo GPS;
- escala logarítmica;
- rangos o dirección de las barras;
- filtros de aceleración;
- dibujo de la interfaz;
- comportamiento ascendente existente.

## Pruebas y criterios de aceptación

1. Primera lectura `42.3 °C`: cero tonos.
2. `42.3 → 41.8`: un tono grave.
3. `42.3 → 40.8`: dos tonos graves secuenciales.
4. `42.3 → 42.0`: cero tonos por histéresis.
5. Oscilación `41.9 ↔ 42.1`: sin repetición.
6. Tras rearmar por encima de `42.2`, una nueva bajada a `41.8` genera exactamente un tono.
7. Lectura inválida entre lecturas válidas: no genera tonos ni corrompe el nivel.
8. El proyecto compila con Java 17 y Gradle 8.7.
9. La APK contiene la versión 4.9 y conserva la funcionalidad visual 4.8.
10. No se añade ni modifica ninguna reproducción asociada al aumento de temperatura.
