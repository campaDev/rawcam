# Mejoras con ML Kit

Ideas para aprovechar ML Kit (Google), que procesa en el teléfono sin enviar imágenes a la nube.
Ya integrados: **Face Detection** (mejor toma de grupo) y **Pose Detection** (disparo con gesto).

Estado:
- ✅ Implementado: **1** SKU por código de barras (+ carpetas por SKU), **4** producto centrado,
  **5** escáner de documentos, **6** disparo con sonrisa, **8** encuadre de personas,
  **10** sugerencias por contenido, **11** carpetas y etiquetas automáticas.
- ⏳ Pendiente: **2** leer etiquetas, **3** fondo blanco, **7** retrato, **9** mascotas.

## Resumen

| # | Idea | API | Uso | Dificultad | Estado |
|---|---|---|---|---|---|
| 1 | SKU desde el código de barras | Code Scanner (Barcode) | TechNTere | Baja | ✅ Hecho |
| 2 | Leer modelo y specs de la etiqueta | Text Recognition v2 | TechNTere | Media | ⏳ Pendiente |
| 3 | Fondo blanco automático | Subject Segmentation | TechNTere | Media | ⏳ Pendiente |
| 4 | Producto siempre centrado | Object Detection & Tracking | TechNTere | Media | ✅ Hecho |
| 5 | Escáner de documentos | Document Scanner | TechNTere | Baja | ✅ Hecho |
| 6 | Disparo con sonrisa | Face Detection | Familia | Baja | ✅ Hecho |
| 7 | Retrato con fondo desenfocado | Selfie Segmentation | Familia | Media | ⏳ Pendiente |
| 8 | Asistente de encuadre de personas | Pose Detection | Familia | Media | ✅ Hecho |
| 9 | Seguimiento de mascotas | Object Detection + Image Labeling | Familia | Media | ⏳ Pendiente |
| 10 | Sugerencias de escena por contenido | Image Labeling | Toda la app | Media | ✅ Hecho |
| 11 | Etiquetas y carpetas automáticas | Image Labeling | Toda la app | Baja | ✅ Hecho |

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

## 4. Producto siempre centrado ✅
- Casilla "Centrar producto". **Object Detection** en modo stream (~4 veces por segundo) sobre
  la vista previa: recuadro del objeto principal, verde si está bien y naranja si no.
- Avisos, en orden: se sale por un lado de la guía (con el margen del 5%), ocupa menos del 25%
  del encuadre ("Acercate"), o está corrido más de un 8% del centro (flecha hacia dónde moverlo).
- El AF/AE sigue al producto (las caras tienen prioridad; un toque o la sesión bloqueada lo anulan).
- Dependencia: `com.google.mlkit:object-detection`.

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

## 8. Asistente de encuadre de personas ✅
- Casilla "Encuadre personas". Con **Pose Detection** (una persona) y las caras de Camera2 (grupos):
  - cabeza cortada (se estima la coronilla desde nariz y hombros);
  - alguien del grupo cortado en el borde;
  - hombros o caderas tocando el borde ("dejale aire");
  - corte en las rodillas (rodillas abajo y tobillos fuera de cuadro);
  - pies cortados.

## 9. Seguimiento de mascotas
- **Object Detection** (con clasificación) o **Image Labeling** para detectar perros y gatos, y
  mantenerles AF/AE como a las caras.

## 10. Sugerencias de escena por contenido ✅
- **Image Labeling** (≈400 etiquetas, confianza ≥ 0.65, una vez por segundo) se suma a las reglas
  de brillo e histograma. Nuevas sugerencias (se aplican con un toque):
  - 3+ caras → timer de grupo + ráfaga ×4 con la mejor;
  - mascota → modo niños;
  - comida → guía 4:5 + JPEG natural;
  - producto electrónico → guía 1:1 + centrar producto;
  - nieve/playa → +0.7 EV.
- Las reglas de poca luz siguen teniendo prioridad.
- Dependencia: `com.google.mlkit:image-labeling`.

## 11. Etiquetas y carpetas automáticas ✅
- Casilla "Carpetas auto": cada captura va a `DCIM/RawCam/<Categoría>/`, con categoría Personas
  (hay caras), Mascotas, Comida, Productos, Paisajes u Otros. El SKU tiene prioridad.
- Las primeras 5 etiquetas se escriben en el JPEG (`ImageDescription` y `UserComment`). El DNG no
  se toca.
- Pendiente: palabras clave XMP en el DNG para Lightroom.

## Fuera de alcance en el Pixel 7
- APIs de IA generativa en el dispositivo (Gemini Nano), por ejemplo para redactar el texto de
  una publicación a partir de la foto: no están disponibles en el Pixel 7. Haría falta un
  servicio en la nube.
