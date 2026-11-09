# Poca luz y zoom (diseño, pendiente)

Estado: **2** implementado ✅; el resto, diseño.

## Punto de partida
Hoy RawCam tiene para poca luz:

| Modo | Limitación |
|---|---|
| Promedio ×N | Sin alinear: requiere trípode |
| Ráfaga ×N + "solo la mejor" | Elige un cuadro, no fusiona: no reduce ruido |
| Astro ×N | Sin alinear: trazos si el total supera la regla NPF |

Lo que falta es lo que hace Night Sight: **fusionar varios cuadros tomados a pulso**.

## Orden propuesto

| # | Mejora | Dificultad | Estado |
|---|---|---|---|
| 1 | Zoom con pellizco + chips rápidos | Baja | ⏳ Pendiente |
| 2 | Descarte de cuadros movidos (giroscopio) | Baja | ✅ Hecho |
| 3 | Modo Noche automático (alineación global + subexposición) | Media | ⏳ Pendiente |
| 4 | Visor nocturno + asistencia de AF | Baja | ⏳ Pendiente |
| 5 | Píxeles calientes / dark frame | Baja | ⏳ Pendiente |
| 6 | Apilado por tiles + super-resolución | Alta | ⏳ Ver `apilado-alineado.md` |

---

# Poca luz

## 2. Descarte de cuadros movidos ✅
- Hoy el giroscopio guarda solo la magnitud reciente. Hay que guardar **muestras con timestamp**
  (ring buffer de ~2 s).
- Para cada cuadro: `SENSOR_TIMESTAMP` (inicio) + `SENSOR_EXPOSURE_TIME` → ventana de exposición.
  Integrar la velocidad angular en esa ventana → ángulo recorrido.
- Desenfoque estimado (px) = ángulo × `focalPx` × `zoomRatio`.
- Si supera ~2 px, el cuadro se descarta del promedio o del apilado (o pierde puntaje en
  "mejor cuadro").
- Ojo: el timestamp del sensor y el del giroscopio usan la misma base de tiempo solo si
  `SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME`; si no, hay que estimar el offset.

**Implementado:**
- Giroscopio a ~200 Hz con historial de ~5 s (timestamp + velocidad angular).
- `motionBlurPx(result)`: velocidad angular media en la ventana del cuadro (inicio + exposición
  + `SENSOR_ROLLING_SHUTTER_SKEW`, ±5 ms) × exposición × `focalPx` × `zoomRatio`.
- **Promedio / Astro:** los cuadros con más de 2.5 px de movimiento no se suman. El aviso final
  dice cuántos se descartaron; si se descartan todos, sugiere apoyar el celu o acortar la
  exposición.
- **Ráfaga (mejor toma):** puntaje × 1 / (1 + (px / 2)²); con 2 px el puntaje cae a la mitad. El
  aviso muestra el movimiento de la mejor y cuántas salieron movidas.
- **Foto simple:** si supera 3 px, avisa "La foto salió movida".
- Si la cámara no usa base de tiempo REALTIME, la medición se desactiva (no descarta nada).
- Pendiente: calibrar los umbrales con fotos reales; usarlo en el futuro modo Noche.

## 3. Modo Noche automático
Un único modo de disparo, "Noche", que decide todo:

**Exposición por cuadro**
- `t_frame = min(obturación segura según pulso, límite por movimiento de la escena)`.
- Si hay caras o movimiento (diferencia entre cuadros de la vista previa), acortar a ≤ 1/30 s.
- Celu apoyado (`gyroRms < STABLE_RMS`) → cuadros largos (hasta 1 s).

**Cantidad de cuadros**
- Presupuesto total según la luz medida: p. ej. 1 s a pulso (15 × 1/15 s) o 4 s apoyado
  (4 × 1 s). Máximo ~15 cuadros a pulso.

**Subexposición**
- Disparar a −1 / −1.5 EV respecto de la medición, para no quemar luces de calle y carteles.
- La fusión baja el ruido y deja recuperar sombras en el revelado (es el truco central de HDR+).

**Alineación global ("barata")**
- Desplazamiento por cuadro desde el giroscopio (ángulo integrado entre cuadros × `focalPx`).
- Refinado con *phase correlation* sobre una versión reducida del canal verde (≈512 px).
- Desplazar en múltiplos de 2 px sobre el Bayer para no mezclar colores.
- Factible en Kotlin, sin NDK.

**Fusión**
- Promedio robusto contra la referencia (el cuadro más nítido):
  `w = 1 / (1 + d² / (k · σ²))`, con σ² de `SENSOR_NOISE_PROFILE`.
- Lo que se mueve en la escena pierde peso → sin fantasmas.
- Salida: un DNG (`RAW_<fecha>_nightN.dng`), como el promedio actual.

**UI**
- Antes de disparar: "Noche · 12 × 1/15 s · mantené quieto ~1 s".
- Durante: barra de progreso con la cuenta de cuadros.

## 4. Visor nocturno y asistencia de AF
- **Visor nocturno:** con la escena muy oscura, la vista previa sube la ganancia y permite hasta
  1/10 s, solo para encuadrar (la foto usa sus propios valores).
- **Asistencia de AF:** linterna (`FLASH_MODE_TORCH`) ~0.5 s mientras enfoca, apagarla antes de
  capturar. Si el AF no confirma, sugerir foco en ∞ para escenas lejanas.

## 5. Píxeles calientes / dark frame
- **Mediana:** en apilados de ≥ 5 cuadros, los píxeles que se desvían mucho de la mediana local
  se reemplazan.
- **Dark frame (astro):** toma con el lente tapado, con la misma exposición e ISO, que se resta
  del apilado. Se puede guardar por combinación de ISO y exposición para reusarla.

---

# Zoom

## 1. Controles

| Control | Implementación |
|---|---|
| **Pellizcar** | `ScaleGestureDetector` sobre la vista previa → `CONTROL_ZOOM_RATIO` dentro de `CONTROL_ZOOM_RATIO_RANGE` (~0.7×–8× en el Pixel 7). Convive con tocar (enfocar) y mantener apretado (bloquear AE/AF). |
| **Chips rápidos** | **0.7× · 1× · 2× · 5×** sobre la vista previa, abajo al centro. Reemplazan el botón "Lente" actual (el ultra gran angular pasa a ser el chip 0.7×). |
| **Slider de precisión** | Aparece mientras se pellizca o al tocar un chip, para ajustar fino; se oculta a los 2 s. |
| **Doble toque** | Alterna 1× ↔ 2×. |

Notas:
- El zoom ya entra en el cálculo de obturación segura y NPF (`zoomRatio`).
- Las regiones de AF/AE con zoom ≠ 1 son relativas al campo visible ampliado; se mantiene el
  mapeo actual de `regionFor`.
- Con zoom, el histograma, las zebras y el peaking siguen midiendo lo que se ve.

## RAW y zoom digital
- `CONTROL_ZOOM_RATIO` recorta el JPEG, pero el DNG suele salir con el **cuadro completo del
  sensor**. Solo cambia de verdad al pasar a la cámara física del ultra gran angular (< 1×).
- **Opción simple:** avisarlo en pantalla ("el RAW guarda el cuadro completo") y recortar en
  Lightroom.
- **Opción completa:** recortar el RAW en la app (copiar la región del Bayer, alineada a 2 px) y
  escribirlo con `DngCreator.writeByteBuffer`. Hay que validar que los metadatos (tamaño activo,
  mapa de sombreado del lente) sigan siendo coherentes con el recorte.

## Super-resolución (futuro)
- Combinando el apilado alineado con los micro-movimientos naturales de la mano entre cuadros se
  obtiene detalle real por encima del recorte digital (*Super Res Zoom*, Wronski et al. 2019).
- Depende de la etapa 2 de `apilado-alineado.md` (alineación por tiles con precisión subpíxel).
