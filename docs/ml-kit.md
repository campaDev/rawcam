# Mejoras con ML Kit

Ideas para aprovechar ML Kit (Google), que procesa en el teléfono sin enviar imágenes a la nube.
Ya integrados: **Face Detection** (mejor toma de grupo) y **Pose Detection** (disparo con gesto).

Estado:
- ✅ Implementado: **1** SKU por código de barras (+ carpetas por SKU), **6** disparo con sonrisa,
  **5** escáner de documentos.
- ⏳ Pendiente: el resto.

## Resumen

| # | Idea | API | Uso | Dificultad | Estado |
|---|---|---|---|---|---|
| 1 | SKU desde el código de barras | Code Scanner (Barcode) | TechNTere | Baja | ✅ Hecho |
| 2 | Leer modelo y specs de la etiqueta | Text Recognition v2 | TechNTere | Media | ⏳ Pendiente |
| 3 | Fondo blanco automático | Subject Segmentation | TechNTere | Media | ⏳ Pendiente |
| 4 | Producto siempre centrado | Object Detection & Tracking | TechNTere | Media | ⏳ Pendiente |
| 5 | Escáner de documentos | Document Scanner | TechNTere | Baja | ✅ Hecho |
| 6 | Disparo con sonrisa | Face Detection | Familia | Baja | ✅ Hecho |
| 7 | Retrato con fondo desenfocado | Selfie Segmentation | Familia | Media | ⏳ Pendiente |
| 8 | Asistente de encuadre de personas | Pose Detection | Familia | Media | ⏳ Pendiente |
| 9 | Seguimiento de mascotas | Object Detection + Image Labeling | Familia | Media | ⏳ Pendiente |
| 10 | Sugerencias de escena por contenido | Image Labeling | Toda la app | Media | ⏳ Pendiente |
| 11 | Etiquetas y carpetas automáticas | Image Labeling | Toda la app | Baja | ⏳ Pendiente |

## 1. SKU desde el código de barras ✅
- Botón "Código" → abre el **Google Code Scanner** (interfaz de Play services, sin permisos
  extra) → el valor del código (EAN/UPC/QR) se carga en el campo SKU.
- También se puede escribir el SKU a mano. Se normaliza: mayúsculas, solo `A–Z 0–9 - _`,
  hasta 40 caracteres.
- Con SKU cargado, las fotos van a `DCIM/RawCam/<SKU>/` y se numeran `RAW_<SKU>_01.dng`,
  `IMG_<SKU>_01.jpg`, … El contador por SKU se recuerda entre sesiones.
- Dependencia: `com.google.android.gms:play-services-code-scanner`.
- Cubre la función "Nombres y carpetas por SKU" de `fotos-productos.md`.

## 2. Leer modelo y specs de la etiqueta
- **Text Recognition v2** sobre la vista previa o sobre la foto.
- Propone el modelo como nombre (p. ej. "Redmi Note 13 · 8GB/256GB") y guarda el texto
  reconocido como borrador para la publicación.
- Ayuda filtrar líneas con patrones de modelo, capacidad (GB/TB), mAh, W.

## 3. Fondo blanco automático
Ver `fotos-productos.md` §6: **Subject Segmentation** → JPEG sobre blanco o PNG transparente.

## 4. Producto siempre centrado
- **Object Detection & Tracking** en modo stream: caja del objeto principal.
- Aviso si se sale de la guía (1:1 / 4:5 / 9:16) o queda corrido; opcionalmente, AF/AE que lo
  siguen, como con las caras.

## 5. Escáner de documentos ✅
- Botón "Documento" → **ML Kit Document Scanner** (interfaz de Google: detección de bordes,
  corrección de perspectiva, filtros, varias páginas, importar de la galería).
- Se guarda como PDF en `Documents/RawCam/` (con el SKU en el nombre si hay uno cargado).
- Uso: facturas, comprobantes de importación, garantías.
- Dependencia: `com.google.android.gms:play-services-mlkit-document-scanner`.
- La primera vez Play services descarga el módulo (necesita internet una vez).

## 6. Disparo con sonrisa ✅
- Casilla "Disparo con sonrisa". Al tocar el disparador, la app queda esperando: analiza la
  vista previa unas 4 veces por segundo con **Face Detection** (modo rápido, con clasificación).
- Dispara cuando **todas** las caras tienen sonrisa > 0.7 y ojos abiertos > 0.6 durante 300 ms.
- El botón muestra cuántas caras están listas (`😊 2/3`). Tocar de nuevo cancela; a los 20 s sin
  lograrlo, cancela solo.
- Combinable con Ráfaga ×4 + "solo la mejor" para cubrir parpadeos.

## 7. Retrato con fondo desenfocado
Ver `fotos-familia.md` §5: **Selfie Segmentation** + desenfoque del fondo en un JPEG aparte.

## 8. Asistente de encuadre de personas
- Con los puntos de **Pose Detection**: avisa si se cortan cabezas o pies, o si alguien queda en
  el borde; en grupo, sugiere alejarse.

## 9. Seguimiento de mascotas
- **Object Detection** (con clasificación) o **Image Labeling** para detectar perros y gatos, y
  mantenerles AF/AE como a las caras.

## 10. Sugerencias de escena por contenido
- **Image Labeling** (≈400 etiquetas) complementa las reglas de brillo e histograma actuales:
  comida → WB natural y 4:5; cielo nocturno → Astro; texto → nitidez alta; mascota → seguimiento.

## 11. Etiquetas y carpetas automáticas
- Guardar etiquetas como palabras clave en los metadatos, o separar en `RawCam/Productos` y
  `RawCam/Familia`, para buscarlas después en Lightroom o Google Fotos.

## Fuera de alcance en el Pixel 7
- APIs de IA generativa en el dispositivo (Gemini Nano), por ejemplo para redactar el texto de
  una publicación a partir de la foto: no están disponibles en el Pixel 7. Haría falta un
  servicio en la nube.
