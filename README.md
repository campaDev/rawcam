# RawCam

App de cámara Android (Camera2, Kotlin) para Pixel 7: captura RAW (DNG) + JPEG con control manual
completo, modos de disparo avanzados y ayudas "inteligentes" que corren en el teléfono.

El APK se compila en GitHub Actions (`build-apk`) en cada push. Se descarga desde
**Actions → última ejecución → Artifacts → `rawcam-debug-apk`**.

## Funciones implementadas

### Captura y exposición
- ✅ RAW (DNG) + JPEG a máxima resolución, guardados en `DCIM/RawCam`.
- ✅ Modos **Auto**, **Prioridad ISO**, **Prioridad obturación** y **Manual**; obturación hasta
  30 s (o el máximo del sensor).
- ✅ Compensación de exposición (EV).
- ✅ **ETTR automático**: expone lo más a la derecha posible sin quemar.
- ✅ Vista previa fluida en Manual con exposiciones largas (compensa con ISO).
- ✅ DNG válido en la cámara lógica del Pixel (calibración de color del sensor físico).

### Enfoque
- ✅ Tocar para enfocar y medir; mantener apretado para bloquear AE/AF; Reset AF.
- ✅ Foco manual con slider (∞ ↔ mínima distancia).
- ✅ **Seguimiento de caras** (AF/AE sobre la cara más grande).
- ✅ **Distancia al sujeto** y profundidad de campo en pantalla.

### Color
- ✅ Balance de blancos: auto, presets (luz día, nublado, sombra, incandescente, fluorescente) y
  Kelvin (2000–10000 K).
- ✅ **Balance con tarjeta gris** (corrige también el tinte verde/magenta de LED).

### Modos de disparo
- ✅ Simple, **Ráfaga** ×4/×8/×16, **Bracketing** ±1 ×3, ±2 ×3, ±2 ×5.
- ✅ **Promedio** ×8/×16/×32 dentro de la app (un solo DNG, sin alinear; requiere trípode).
- ✅ **Astro** ×16/×32 con foco en ∞, más asistente (regla NPF, deriva del cielo, conteo de
  estrellas, ajustes con un toque).
- ✅ **Mejor cuadro de la ráfaga** por nitidez, con opción "solo la mejor"; en fotos de grupo
  también puntúa ojos abiertos y sonrisa (ML Kit).
- ✅ Timer 2 s / 10 s / **grupo**; disparo con botones de volumen; **disparo con gesto** (mano
  arriba); **esperar quietud** (giroscopio).
- ✅ **Intervalómetro** (2–60 s, 10–300 tomas) para timelapse.
- ✅ **Disparo con sonrisa**: espera a que todos sonrían con los ojos abiertos (ML Kit).

### Ayudas en el visor
- ✅ Histograma en vivo, zebras, focus peaking, grilla de tercios y nivel.
- ✅ **Obturación segura sugerida** según el pulso (giroscopio).
- ✅ **Detector de lente sucio** (heurístico; umbrales por calibrar).
- ✅ **Sugerencias por escena**: por luz (poca luz, contraluz, muy oscuro, primer plano) y por
  contenido con ML Kit Image Labeling (grupo, mascota, comida, producto, nieve/playa).
- ✅ **Guías de encuadre** 1:1, 4:5 y 9:16 con zonas seguras de TikTok/Reels/Estados.
- ✅ **Centrar producto** (ML Kit Object Detection): recuadro, avisos de encuadre y AF/AE sobre el
  producto.
- ✅ **Encuadre de personas** (Pose + caras): cabeza o pies cortados, cortes en rodillas, bordes.

### Presets
- ✅ **Sesión de producto**: bloquea exposición, balance y foco para todo un lote.
- ✅ **Modo niños**: 1/250 s, caras y ráfaga ×4 con la mejor.

### Guardado
- ✅ **SKU** (a mano o leyendo el código de barras): carpeta `DCIM/RawCam/<SKU>/` y numeración por
  producto.
- ✅ **Carpetas automáticas** por contenido (Personas, Mascotas, Comida, Productos, Paisajes,
  Otros) y etiquetas en el JPEG.
- ✅ **Escáner de documentos** a PDF en `Documents/RawCam/` (ML Kit Document Scanner).
- ✅ Formato RAW + JPEG / Solo RAW / Solo JPEG; calidad JPEG 85–100%; JPEG "natural" (sin
  nitidez, ruido mínimo).
- ✅ Ubicación GPS opcional en JPEG y DNG.
- ✅ Selector de lente (1× / gran angular por zoom).

## Pendiente (documentado)
- [Apilado con alineación](docs/apilado-alineado.md): ráfagas a pulso y astro sin trazos, al
  estilo HDR+.
- [Fotos de productos](docs/fotos-productos.md): exportación para redes, fondo blanco automático,
  360°, apilado de foco.
- [Fotos de familia](docs/fotos-familia.md): retrato con fondo desenfocado.
- [Mejoras con ML Kit](docs/ml-kit.md): leer texto de etiquetas, seguimiento de mascotas.
- Calibrar los umbrales del detector de lente sucio con datos reales.
- Recordar los ajustes al cerrar la app.

## Estructura
- `app/src/main/java/dev/campa/rawcam/MainActivity.kt`: cámara, captura, análisis y UI.
- `app/src/main/java/dev/campa/rawcam/OverlayView.kt`: dibujo sobre el visor (histograma,
  máscaras, grilla, nivel, caras, guías).
- `app/debug.keystore`: firma fija para que cada build se instale como actualización.
- `docs/`: diseños de funciones pendientes.
