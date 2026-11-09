# Apilado con alineación (diseño, pendiente)

Estado: **diseño, sin implementar**. Documenta cómo agregar a RawCam una fusión de ráfagas RAW
alineadas al estilo HDR+, para reducir ruido a pulso y hacer astro sin trazos.

## Por qué

Hoy RawCam tiene dos formas de combinar cuadros, ambas **sin alinear**:

| Modo actual | Qué hace | Limitación |
|---|---|---|
| Ráfaga ×N | Guarda N DNG sueltos (`RAW_<fecha>_bNN.dng`) | La fusión queda para afuera de la app |
| Promedio ×N / Astro ×N | Suma los RAW en la app y guarda `RAW_<fecha>_avgN.dng` | Requiere trípode; a pulso sale movido y en astro las estrellas dejan trazo |

Alinear antes de fusionar permite:
- Ráfagas **a pulso** con ruido reducido ~√N (×8 ≈ 2.8× menos ruido) sin perder nitidez.
- **Rechazo de fantasmas**: lo que se mueve en la escena no se duplica.
- **Astro** con exposición total larga sin trazos de estrellas.

## Pipeline

```
Ráfaga RAW ──► referencia ──► alineación (global + tiles) ──► fusión robusta ──► DNG
```

### 1. Cuadro de referencia
El más nítido de la ráfaga. Ya existe `rawSharpness(image)` en `MainActivity.kt`
(diferencia media entre vecinos Bayer del mismo color, normalizada por brillo).

### 2. Alineación

**Global (barata)**
- Integrar el giroscopio (`TYPE_GYROSCOPE`, ya registrado en `gyroListener`) entre los
  `SENSOR_TIMESTAMP` de cada cuadro y la referencia → rotación del teléfono.
- Desplazamiento en píxeles ≈ ángulo × `focalPx` (ya calculado en `loadCamera`).
- Refinar con *phase correlation* sobre una versión reducida (p. ej. 512 px de ancho).
- Requiere guardar muestras de giroscopio con timestamp (hoy solo se guarda la magnitud).

**Local por tiles (núcleo de HDR+)**
- Bayer RGGB → imagen gris a 1/4 de resolución (promedio de cada bloque 2×2).
- Pirámide gaussiana de 4 niveles.
- Tiles de 16×16 (en el nivel base), búsqueda ±4 px por nivel, de grueso a fino; el vector de
  cada nivel inicializa el siguiente. Métrica: L1 en niveles gruesos, L2 en el fino.
- Resultado: un vector de desplazamiento por tile, que corrige paralaje y rotaciones pequeñas.
- Los desplazamientos se aplican en múltiplos de 2 px sobre el Bayer para no mezclar colores.

### 3. Fusión robusta
Para cada píxel alineado `x` frente a la referencia `r`:

```
d = x − r
w = 1 / (1 + d² / (k · σ²(r)))
salida = Σ(w · x) / Σ(w)        (la referencia entra con w = 1)
```

- `σ²(r) = S · r + O`, con `S` y `O` por canal tomados de `CaptureResult.SENSOR_NOISE_PROFILE`
  (modelo de ruido real del sensor, por cuadro).
- Diferencia ≈ ruido → el cuadro suma. Diferencia grande (algo se movió) → peso ≈ 0, sin fantasmas.
- `k` a calibrar (empezar con 4–8).
- Mejora posterior: fusión en frecuencia (Wiener por tile, como el paper de HDR+), que conserva
  mejor el detalle fino.

### 4. Salida
DNG con `DngCreator.writeByteBuffer(...)` usando los metadatos de la referencia, igual que
`writeStack()` hoy. Nombre sugerido: `RAW_<fecha>_alignN.dng`.

## Recursos y rendimiento (12.5 MP, ráfaga ×8)

**Memoria: fusión incremental.** Se alinea y suma cada cuadro apenas llega, y se libera. Solo
quedan en memoria la referencia, la suma y los pesos: ≈ 125 MB, en lugar de 25 MB × N.

**Implementación:**

| Opción | Tiempo estimado | Comentario |
|---|---|---|
| Kotlin puro | 15–30 s | Simple, pero lento |
| **C++ (NDK) + NEON** | **2–4 s** | **Recomendada.** CMake en Gradle; GitHub Actions ya trae el NDK |
| GPU (Vulkan compute) | < 1 s | Mucha más complejidad |

## Astro: caso especial
- El cielo rota alrededor del polo celeste: no sirve la alineación por tiles.
- Usar una **transformación global de rotación y traslación**, estimada emparejando estrellas
  (triángulos de estrellas brillantes, como Sequator o DeepSkyStacker). `countStars()` ya detecta
  máximos locales en la vista previa y sirve de base.
- **Primer plano:** el paisaje no se mueve, así que al alinear el cielo se borronea. La solución
  completa usa una máscara cielo/suelo: el cielo se alinea y el suelo se promedia sin alinear.

## Plan por etapas

1. **Prototipo en Python en la Mac** (`rawpy`, `numpy`, `opencv-python`) sobre ráfagas reales
   `b01…b08` de RawCam. Ajustar tile, radio de búsqueda y `k`. Comparar contra un DNG suelto y
   contra el promedio simple (`avgN`).
2. **Port a C++/NDK** del algoritmo validado: alineación global con giroscopio, tiles y fusión
   robusta. Nuevo modo de disparo "Ráfaga alineada ×8".
3. **Astro alineado** con emparejamiento de estrellas y máscara cielo/suelo.
4. *(Opcional)* Fusión en frecuencia y super-resolución por micro-desplazamientos
   (*Handheld Multi-Frame Super-Resolution*).

## Datos de prueba a capturar (para la etapa 1)
Con **Ráfaga ×8**, formato **Solo RAW** y "Ráfaga: solo la mejor" **apagado**:
- A pulso con poca luz (interior de noche).
- Escena con algo en movimiento (gente, ramas, agua).
- Nocturna con cielo, celu apoyado (para probar el caso astro).

Copiar las carpetas `DCIM/RawCam` de cada caso a la Mac.

## Referencias
- Hasinoff et al., *Burst photography for high dynamic range and low-light imaging on mobile
  cameras*, SIGGRAPH Asia 2016 (HDR+).
- Wronski et al., *Handheld Multi-Frame Super-Resolution*, SIGGRAPH 2019.
- Implementación abierta de HDR+ en Halide: proyecto `hdr-plus` (timothybrooks).
- Documentación de Camera2: `SENSOR_NOISE_PROFILE`, `SENSOR_TIMESTAMP`, `DngCreator`.
