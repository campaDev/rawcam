# Fotos de familia

Funciones para capturar el momento: nadie con los ojos cerrados, niños que no salgan movidos y
el fotógrafo también dentro de la foto.

Estado:
- ✅ Implementado: **1** timer de grupo, **2** modo niños, **3** disparo con sonrisa,
  **4** mejor toma de grupo, **6** disparo con gesto (mano por encima de la cabeza).
- ⏳ Pendiente: **5** retrato con fondo desenfocado.
- Dependencias ya agregadas: `com.google.mlkit:face-detection`, `com.google.mlkit:pose-detection`.

## Prioridades

| # | Mejora | Dificultad | Etapa | Estado |
|---|---|---|---|---|
| 1 | Timer de grupo | Baja | 1 | ✅ Hecho |
| 2 | Modo niños | Baja | 1 | ✅ Hecho |
| 3 | Disparo con sonrisa | Media | 1 | ✅ Hecho |
| 4 | Mejor toma de grupo | Media | 2 | ✅ Hecho |
| 5 | Retrato con fondo desenfocado | Media | 2 | ⏳ Pendiente |
| 6 | Disparo con gesto | Media | 3 | ✅ Hecho |

Las tres primeras reutilizan la detección de caras que ya existe (`handleFaces`,
`STATISTICS_FACES`).

## 1. Timer de grupo ✅
**Problema:** con el timer de 10 s hay que correr para salir en la foto.

**Cómo:**
- Opción "Timer: grupo".
- Al disparar, la app cuenta las caras visibles (N) y espera a que aparezca una más (N + 1).
- Con N + 1 estable durante 1 s → cuenta regresiva de 2 s → disparo, idealmente con Ráfaga ×4
  para elegir la mejor.
- Tiempo máximo de espera (p. ej. 30 s), con aviso si nadie entra.
- Base: la detección de caras de Camera2 alcanza para contar, no requiere ML Kit.
- Si la cámara no detecta caras, cae a un timer de 10 s. Tocar el disparador mientras espera
  cancela.

## 2. Modo niños ✅
**Problema:** los niños se mueven y en interiores la foto sale movida.

**Cómo:** un preset que configura:
- Prioridad de obturación con mínimo **1/250 s** (ISO automático hasta el máximo razonable).
- Seguimiento de caras activado.
- Ráfaga ×4 con "solo la mejor".
- "Esperar quietud" desactivado: se mueve el sujeto, no el celu.
- Al desactivarlo vuelve a Auto, disparo simple y "Esperar quietud".
- Pendiente: subir el umbral a 1/500 s si se detecta movimiento rápido. Esto puede venir del
  análisis de la vista previa (diferencia entre cuadros) o de que las caras se desplacen rápido.

## 3. Disparo con sonrisa ✅
**Cómo:**
- **ML Kit Face Detection** con clasificación activada: da por cara `smilingProbability`,
  `leftEyeOpenProbability` y `rightEyeOpenProbability`.
- Se analiza sobre la vista previa reducida que ya se usa en `analyze` (no hace falta otro stream).
- Condición de disparo: todas las caras con sonrisa > 0.7 y ojos abiertos > 0.6, estable durante
  unos 300 ms.
- El botón muestra cuántas caras están listas (`😊 2/3`); a los 20 s sin lograrlo, cancela.
- Pendiente: indicador sobre cada cara (😊 / 😐 / ojos cerrados) en el visor.

## 4. Mejor toma de grupo ✅
- Extiende el "mejor cuadro de la ráfaga" (`rawSharpness` + `reportBest`).
- Si al disparar hay caras en cuadro, cada cuadro de la ráfaga captura también un JPEG. Se
  decodifica a 1/4 de resolución y se pasa por ML Kit Face Detection (`groupScore`).
- Factor de grupo = (ojos abiertos medios) × (0.8 + 0.2 × sonrisa media).
- Puntaje = nitidez × (0.3 + 0.7 × factor de grupo): la nitidez sigue pesando, pero un parpadeo
  baja mucho la toma.
- Con "Ráfaga: solo la mejor" se borran el RAW y el JPEG de las demás. En Solo RAW el JPEG se usa
  para medir y no se guarda.
- Futuro (complejo): componer la mejor cara de cada persona desde distintos cuadros (*best take*).
  Requiere alinear y fusionar regiones; comparte base con `docs/apilado-alineado.md`.

## 5. Retrato con fondo desenfocado ⏳
- **ML Kit Selfie Segmentation** separa persona y fondo, y funciona también con la cámara trasera.
- Sobre el JPEG: desenfoque gaussiano del fondo, con radio proporcional a la distancia de enfoque
  (la distancia ya se calcula a partir de `LENS_FOCUS_DISTANCE`).
- Mezcla con la máscara suavizada para que el pelo no quede con borde duro.
- Se guarda como un JPEG aparte (`IMG_<fecha>_retrato.jpg`); el RAW y el JPEG original no se
  tocan.
- **Limitación:** pelo suelto y objetos que sostiene la persona pueden quedar mal recortados.

## 6. Disparo con gesto ✅
- Levantar una mano por encima de la cabeza (muñeca por encima de la nariz) inicia el timer de
  2 s, con vibración y aviso.
- **ML Kit Pose Detection** en modo stream sobre la vista previa reducida, unas 4 veces por segundo.
- Para evitar disparos accidentales, la mano tiene que mantenerse 0.5 s, y después del disparo hay
  4 s sin aceptar otro gesto.
- Detecta a una sola persona (la más visible); funciona mejor de la cintura para arriba, a 1–4 m.
- Útil cuando el celu está lejos, en un trípode o apoyado.

## Flujo de uso típico
- **Foto de grupo con vos incluido:** celu apoyado → Timer de grupo → te sumás → la app espera
  tu cara, cuenta 2 s y saca la ráfaga con la mejor toma.
- **Niños jugando:** Modo niños → apuntás y disparás; la app se queda con la toma más nítida.
- **Retrato:** Caras activado → Disparo con sonrisa → JPEG retrato con fondo desenfocado.
