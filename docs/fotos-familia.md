# Fotos de familia (diseño, pendiente)

Estado: **diseño, sin implementar**. Funciones para capturar el momento: nadie con los ojos
cerrados, niños que no salgan movidos y el fotógrafo también dentro de la foto.

## Prioridades

| # | Mejora | Dificultad | Etapa |
|---|---|---|---|
| 1 | Timer de grupo | Baja | 1 |
| 2 | Modo niños | Baja | 1 |
| 3 | Disparo con sonrisa | Media | 1 |
| 4 | Mejor toma de grupo | Media | 2 |
| 5 | Retrato con fondo desenfocado | Media | 2 |
| 6 | Disparo con gesto | Media | 3 |

Las tres primeras reutilizan la detección de caras que ya existe (`handleFaces`,
`STATISTICS_FACES`).

## 1. Timer de grupo
**Problema:** con el timer de 10 s hay que correr para salir en la foto.

**Cómo:**
- Opción "Timer: grupo".
- Al disparar, la app cuenta las caras visibles (N) y espera a que aparezca una más (N + 1).
- Con N + 1 estable durante 1 s → cuenta regresiva de 2 s → disparo, idealmente con Ráfaga ×4
  para elegir la mejor.
- Tiempo máximo de espera (p. ej. 30 s), con aviso si nadie entra.
- Base: la detección de caras de Camera2 alcanza para contar, no requiere ML Kit.

## 2. Modo niños
**Problema:** los niños se mueven y en interiores la foto sale movida.

**Cómo:** un preset que configura:
- Prioridad de obturación con mínimo **1/250 s** (ISO automático hasta el máximo razonable).
- Seguimiento de caras activado.
- Ráfaga ×4 con "solo la mejor".
- "Esperar quietud" desactivado: se mueve el sujeto, no el celu.
- Opcional: subir el umbral a 1/500 s si se detecta movimiento rápido. Esto puede venir del
  análisis de la vista previa (diferencia entre cuadros) o de que las caras se desplacen rápido.

## 3. Disparo con sonrisa
**Cómo:**
- **ML Kit Face Detection** con clasificación activada: da por cara `smilingProbability`,
  `leftEyeOpenProbability` y `rightEyeOpenProbability`.
- Se analiza sobre la vista previa reducida que ya se usa en `analyze` (no hace falta otro stream).
- Condición de disparo: todas las caras con sonrisa > 0.7 y ojos abiertos > 0.6, estable durante
  unos 300 ms.
- Indicador sobre cada cara (😊 / 😐 / ojos cerrados) para que el fotógrafo vea quién falta.
- Dependencia nueva: `com.google.mlkit:face-detection`.

## 4. Mejor toma de grupo
- Extiende el "mejor cuadro de la ráfaga" actual (`rawSharpness` + `reportBest`).
- Puntaje por cuadro = nitidez × (fracción de caras con ojos abiertos) × (sonrisa media).
- Para puntuar las caras hace falta una imagen: generar una miniatura del RAW o usar el JPEG si
  se guardó, y pasarla por ML Kit Face Detection.
- Futuro (complejo): componer la mejor cara de cada persona desde distintos cuadros (*best take*).
  Requiere alinear y fusionar regiones; comparte base con `docs/apilado-alineado.md`.

## 5. Retrato con fondo desenfocado
- **ML Kit Selfie Segmentation** separa persona y fondo, y funciona también con la cámara trasera.
- Sobre el JPEG: desenfoque gaussiano del fondo, con radio proporcional a la distancia de enfoque
  (la distancia ya se calcula a partir de `LENS_FOCUS_DISTANCE`).
- Mezcla con la máscara suavizada para que el pelo no quede con borde duro.
- Se guarda como un JPEG aparte (`IMG_<fecha>_retrato.jpg`); el RAW y el JPEG original no se
  tocan.
- **Limitación:** pelo suelto y objetos que sostiene la persona pueden quedar mal recortados.

## 6. Disparo con gesto
- Levantar la palma frente a la cámara inicia el timer de 2 s.
- **ML Kit Pose Detection** (o un detector de manos) sobre la vista previa reducida.
- Útil cuando el celu está lejos, en un trípode o apoyado.
- Para evitar disparos accidentales, la palma tiene que mantenerse unos 0.5 s.

## Flujo de uso típico
- **Foto de grupo con vos incluido:** celu apoyado → Timer de grupo → te sumás → la app espera
  tu cara, cuenta 2 s y saca la ráfaga con la mejor toma.
- **Niños jugando:** Modo niños → apuntás y disparás; la app se queda con la toma más nítida.
- **Retrato:** Caras activado → Disparo con sonrisa → JPEG retrato con fondo desenfocado.
