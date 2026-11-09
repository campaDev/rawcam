# Hoja de ruta: RawCam vs. Google Camera

Objetivo: que RawCam le gane a la Google Camera **escena por escena**, midiendo cada paso, en
lugar de intentar superarla en todo de una vez.

## Dónde estamos
- **RawCam ya gana:** fotos de producto (tarjeta gris, sesión bloqueada, guías para redes),
  trípode y exposiciones largas (Promedio, Astro), look natural (JPEG natural o DNG revelado a
  gusto), control manual y ayudas (zebras, peaking, ETTR, intervalómetro).
- **Google Camera gana:** fotos rápidas a pulso, poca luz sin trípode, contraluces fuertes en JPEG,
  zoom y retrato.
- **La razón principal:** cada foto de Google Camera es una fusión de varios cuadros alineados
  (HDR+). Su DNG también es una fusión, con menos ruido que un RAW de un solo cuadro como el de
  RawCam. A eso suma un revelado ajustado durante años para ese sensor y procesamiento en el chip
  Tensor.

## Principios
1. **Medir antes de mejorar.** Ningún cambio de calidad se da por bueno sin pasar el banco de
   pruebas.
2. **Elegir las batallas.** Primero donde ya ganamos o podemos ganar rápido; después lo difícil.
3. **La calidad no puede costar comodidad.** Si una mejora vuelve la app lenta, no está terminada.

## Fases

| Fase | Objetivo | Resultado medible | Estado |
|---|---|---|---|
| 0 · Estabilizar | Todo compila y funciona en el celu; recordar ajustes; calibrar el detector de lente | Sin errores al usarla | ⏳ |
| 1 · Medir | Banco de pruebas + script de métricas en la Mac | Línea base contra Google Camera | ⏳ |
| 2 · Fusión | Descarte de cuadros movidos ✅, alineación global, modo Noche | Menos ruido de noche a pulso | 🟡 En curso |
| 3 · Revelado propio | JPEG generado desde el RAW fusionado, con look natural | Ganar la comparación a ciegas | ⏳ |
| 4 · Velocidad | Disparo sin demora (ZSL), procesamiento en segundo plano, C++/GPU | Misma comodidad que la app nativa | ⏳ |
| 5 · Avanzado | Apilado por tiles, super-resolución, retrato | Pelear zoom y retrato | ⏳ |

### Fase 0 · Estabilizar
Gran parte del código se escribió sin compilarse ni probarse en el teléfono.
- Que el build de Actions pase en verde y el APK se instale desde el link fijo de la release.
- Recorrer todas las funciones del `README.md` en el Pixel y anotar fallas.
- **Recordar ajustes** al cerrar la app (`SharedPreferences`): modo, formato, guía, toggles.
- **Calibrar el detector de lente sucio** con los números de depuración (lente limpio vs. sucio).
- Revisar rendimiento con todos los análisis activos (temperatura, fluidez de la vista previa).

### Fase 1 · Medir
**Banco de pruebas fijo:** las mismas escenas, siempre en el mismo lugar, con las dos apps:

| # | Escena | Qué estresa |
|---|---|---|
| 1 | Interior con poca luz, a pulso | Ruido, nitidez, movimiento |
| 2 | Contraluz (ventana o sol detrás) | Rango dinámico, luces quemadas |
| 3 | Producto con tarjeta gris | Color, consistencia |
| 4 | Persona (retrato y medio cuerpo) | Tonos de piel, enfoque en la cara |
| 5 | Noche, celu apoyado | Ruido en exposición larga, luces puntuales |
| 6 | Noche, a pulso | Fusión a pulso (la pelea principal) |

**Script de métricas en la Mac** (Python: `rawpy`, `numpy`, `opencv`):
- **Ruido:** desviación estándar en una zona plana marcada (pared, cielo, tarjeta).
- **Luces quemadas:** % de píxeles recortados en altas luces.
- **Nitidez:** MTF50 sobre un borde inclinado (*slanted edge*) o varianza del laplaciano.
- **Color:** ΔE de la tarjeta gris respecto de neutro.
- **Comparación a ciegas:** el script muestra pares sin decir de qué app es cada foto y registra
  la elección.
- Cada corrida se guarda con fecha y versión de RawCam, para ver la evolución.

### Fase 2 · Fusión
Es el 80% de la ventaja de Google. Detalle en `poca-luz-y-zoom.md` y `apilado-alineado.md`.
1. ✅ **Descarte de cuadros movidos:** el giroscopio mide cuánto se movió el celu durante cada
   exposición (Promedio/Astro los excluyen, Ráfaga los penaliza, Simple avisa).
2. **Alineación global:** giroscopio + *phase correlation*, en Kotlin.
3. **Modo Noche automático:** exposición por cuadro según el pulso, cantidad de cuadros según la
   luz, subexposición de −1/−1.5 EV, fusión robusta con `SENSOR_NOISE_PROFILE`.
4. **Apilado por tiles** (cuando lo anterior esté medido).

Cada paso se valida por separado con las escenas 1, 5 y 6.

### Fase 3 · Revelado propio
Donde RawCam puede ganar en gusto, no solo en números.
- Generar el JPEG final **desde el RAW fusionado**, no desde el procesador de la cámara.
- *Tone mapping* local (fusión de exposiciones sintéticas, como HDR+), pero con un perfil más
  natural: menos efecto HDR, menos nitidez artificial y colores más fieles.
- Perfiles de revelado: "Natural" (por defecto), "Producto" (neutro, contraste medio) y
  "Vívido" (más saturación para redes).
- Se valida con la comparación a ciegas de la fase 1.

### Fase 4 · Velocidad
- **Disparo sin demora (*zero shutter lag*):** buffer circular con los últimos cuadros. Al tocar
  el disparador, la ráfaga ya está capturada. Clave para niños y mascotas.
- **Procesamiento en segundo plano:** cola de fusión; se puede seguir disparando mientras procesa.
  Notificación al terminar.
- **Mover la fusión a C++ (NDK + NEON) o GPU (Vulkan)** cuando el algoritmo esté validado en
  Kotlin.
- Limitaciones a medir: memoria (cada RAW ≈ 25 MB) y temperatura.

### Fase 5 · Avanzado
- Apilado por tiles con precisión subpíxel (`apilado-alineado.md`).
- **Super-resolución** con los micro-movimientos de la mano (*Super Res Zoom*).
- **Retrato** con fondo desenfocado (Selfie Segmentation, `fotos-familia.md`).
- Pelear zoom y retrato recién acá, donde Google tiene más ventaja.

## Orden de batallas
1. **Producto y trípode:** ya ganamos; pulirlo (fase 0).
2. **Noche a pulso con look natural:** primera pelea de igual a igual (fases 2–3).
3. **Contraluz:** fusión + subexposición (fases 2–3).
4. **Velocidad y momentos** (niños, mascotas): ZSL (fase 4).
5. **Zoom y retrato** (fase 5).

## Documentos relacionados
- `apilado-alineado.md`: fusión estilo HDR+ (tiles, fusión robusta, astro).
- `poca-luz-y-zoom.md`: modo Noche, descarte de cuadros, visor nocturno, zoom.
- `fotos-productos.md`, `fotos-familia.md`, `ml-kit.md`: funciones por uso.
