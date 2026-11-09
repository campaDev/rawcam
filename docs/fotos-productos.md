# Fotos de productos

Funciones para fotografiar el catálogo de TechNTere: color fiel, consistencia entre tomas y
archivos listos para publicar. Canales de publicación: **WhatsApp, Instagram, Facebook y TikTok**
(MercadoLibre no se usa).

Estado:
- ✅ Implementado: **1** tarjeta gris, **2** sesión de producto, **3** guías de encuadre.
- ⏳ Pendiente: 4–8.

## Prioridades

| # | Mejora | Dificultad | Etapa | Estado |
|---|---|---|---|---|
| 1 | Balance de blancos con tarjeta gris | Baja | 1 | ✅ Hecho |
| 2 | Modo sesión de producto (bloqueos) | Baja | 1 | ✅ Hecho |
| 3 | Guías de encuadre 1:1, 4:5 y 9:16 | Baja | 1 | ✅ Hecho |
| 4 | Nombres y carpetas por SKU | Baja | 1 | ⏳ Pendiente |
| 5 | Exportación lista para la web | Media | 2 | ⏳ Pendiente |
| 6 | Fondo blanco automático | Media | 2 | ⏳ Pendiente |
| 7 | Producto 360° | Baja | 2 | ⏳ Pendiente |
| 8 | Apilado de foco | Alta | 3 | ⏳ Pendiente |

La etapa 1 es una sola tanda y es la que más tiempo ahorra por semana.

## 1. Balance de blancos con tarjeta gris ✅
**Problema:** el balance automático tiñe los productos. El negro de un celular sale azulado y el
plateado de un cargador, cálido.

**Cómo:**
- Botón "WB con tarjeta". El usuario toca una tarjeta gris (o una hoja blanca) en la vista previa.
- Sobre la región tocada se toman los valores R, G, B promedio desde la vista previa analizada
  (`pixels` en `analyze`).
- Ganancias: `gainR = G/R`, `gainB = G/B`, con G = 1.
- Se aplica con `CONTROL_AWB_MODE_OFF` + `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX`, reutilizando
  `lastTransform` y la lógica de `applyWhiteBalance()` del modo Kelvin.
- Es mejor que el Kelvin porque corrige también el eje verde–magenta de las luces LED y
  fluorescentes.

**Notas:**
- La vista previa ya viene tono-mapeada; para más precisión, tomar las ganancias del próximo
  `CaptureResult` (`COLOR_CORRECTION_GAINS`) iterando 2–3 veces hasta que la región quede neutra.
- El DNG guarda la imagen cruda, así que el balance es reversible en Lightroom.

## 2. Modo sesión de producto ✅
**Problema:** en 30 fotos del catálogo, la exposición y el color varían entre tomas.

**Cómo:**
- Interruptor "Sesión". Al activarlo:
  - se fijan la exposición actual (pasa a Manual con los valores medidos), el WB (tarjeta gris o
    Kelvin actual) y el foco;
  - se apagan ETTR, sugerencias y caras para que nada cambie solo.
- Un indicador visible ("SESIÓN BLOQUEADA") deja claro que la app no se reajusta sola.
- Recomendación de uso: celu en trípode, luz continua y timer de 2 s o "Esperar quietud".

## 3. Guías de encuadre ✅
Marcos sobre la vista previa en `OverlayView`, con el exterior oscurecido y un margen interior
punteado del 5% para que el producto no toque el borde:

| Guía | Uso |
|---|---|
| Libre | Sin guía (3:4 nativo del sensor; WhatsApp lo muestra completo) |
| **1:1** | Feed de Instagram y Facebook, catálogo y foto de perfil de WhatsApp Business |
| **4:5** | Publicación vertical de Instagram y Facebook (lo que más pantalla ocupa en el feed) |
| **9:16** | TikTok, Reels, Historias de IG/FB y Estados de WhatsApp |

En 9:16 se marcan además las **zonas seguras**, donde esas apps tapan la imagen con su
interfaz: ~14% arriba, ~22% abajo (descripción, botones) y ~15% a la derecha (likes,
comentarios). El producto y el texto importante tienen que quedar dentro.

## 4. Nombres y carpetas por SKU ⏳
- Campo de texto "SKU" antes de la sesión (p. ej. `TNT-0123`).
- Carpeta: `DCIM/RawCam/<SKU>/` (cambiar `RELATIVE_PATH` en `insertImage`).
- Nombres: `<SKU>_01.dng`, `<SKU>_01.jpg`, `<SKU>_02…`, con contador por SKU.
- Recordar el último SKU y sugerir el siguiente número.

## 5. Exportación lista para la web ⏳
Junto al RAW, generar un JPEG adicional:
- Recorte según la guía elegida (1:1, 4:5 o 9:16).
- Tamaños por red: 1080×1080 (1:1), 1080×1350 (4:5), 1080×1920 (9:16); sRGB, calidad 85–90.
  Instagram, Facebook, TikTok y WhatsApp recomprimen todo lo que supere ~1080 px de ancho, así
  que subir más grande no mejora la calidad.
- Opcional: marca de agua con el logo de TechNTere (PNG en assets), en una esquina y con
  opacidad configurable.
- Nombre: `<SKU>_01_web.jpg`.

## 6. Fondo blanco automático ⏳
- **ML Kit Subject Segmentation** (Google Play services, en el dispositivo): da una máscara del
  objeto principal.
- Salidas: JPEG sobre blanco puro (`#FFFFFF`) y PNG con fondo transparente.
- Suavizar el borde de la máscara (feather de 1–2 px) y conservar una sombra suave opcional, para
  que el producto no parezca "flotando".
- **Limitación:** con productos transparentes o muy reflectivos (vidrio, cromados) la máscara
  puede fallar. Mostrar la vista previa del recorte antes de guardar.
- Dependencia nueva: `com.google.android.gms:play-services-mlkit-subject-segmentation`.

## 7. Producto 360° ⏳
- Preset del intervalómetro: 24 o 36 tomas, intervalo de 2–3 s, con la sesión bloqueada.
- Se gira el producto a mano o con una base giratoria entre tomas.
- Salida numerada (`<SKU>_360_01…36.jpg`) para armar el visor 360 en la tienda web.

## 8. Apilado de foco ⏳
- **Para qué:** productos chicos de cerca (placas, conectores, auriculares), donde la profundidad
  de campo no alcanza para todo el objeto.
- **Captura:** barrido de `LENS_FOCUS_DISTANCE` en N pasos (p. ej. 8–15) entre el punto más
  cercano y el más lejano del producto, con trípode.
- **Fusión:** por píxel se elige el cuadro con mayor nitidez local (laplaciano en una ventana
  de 5×5) y se suaviza el mapa de selección para evitar costuras.
- **Comparte base con el apilado alineado** (`docs/apilado-alineado.md`): al cambiar el foco
  cambia levemente la ampliación (*focus breathing*), así que hay que alinear y escalar los
  cuadros antes de fusionar.
- Se puede prototipar primero en Python en la Mac con los DNG.

## Flujo de trabajo propuesto
1. Armar el puesto: fondo blanco, dos luces continuas y celu en trípode.
2. Escribir el SKU.
3. WB con tarjeta gris y activar la sesión.
4. Guía 1:1, timer de 2 s y disparar las tomas necesarias.
5. Revisar los JPEG `_web` y subir directo.
