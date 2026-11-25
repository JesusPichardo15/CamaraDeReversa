package com.example.camaradereversa

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PermissionWrapper()
        }
    }
}

@Composable
fun PermissionWrapper() {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted }
    )

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        MainCameraScreen()
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "Se necesita permiso de cámara para iniciar.")
        }
    }
}

@Composable
fun MainCameraScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val urlStreamEsp32 = "http://192.168.4.1/stream"
    val urlDistanceEsp32 = "http://192.168.4.1:81/distance"
    val urlBuzzerBase = "http://192.168.4.1:81/buzzer"

    var usarCamaraLocal by remember { mutableStateOf(false) }
    var buzzerMuted by remember { mutableStateOf(false) }
    var currentFrame by remember { mutableStateOf<Bitmap?>(null) }
    var detectionResults by remember { mutableStateOf<ObjectDetectorResult?>(null) }
    var imageSize by remember { mutableStateOf(Pair(1, 1)) }
    var distanceCm by remember { mutableIntStateOf(-1) }
    var videoFps by remember { mutableIntStateOf(0) }

    val detectorHelper = remember {
        ObjectDetectorHelper(
            context = context,
            onResults = { result, width, height ->
                imageSize = Pair(width, height)
                detectionResults = result
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose { detectorHelper.close() }
    }

    // 1. Lector continuo del stream MJPEG de la ESP32-S3
    LaunchedEffect(usarCamaraLocal) {
        if (!usarCamaraLocal) {
            val client = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
            val request = Request.Builder().url(urlStreamEsp32).build()

            while (isActive) {
                withContext(Dispatchers.IO) {
                    try {
                        client.newCall(request).execute().use { response ->
                            val body = response.body ?: return@use
                            val inputStream = BufferedInputStream(body.byteStream(), 16384)
                            val jpegBuffer = ByteArrayOutputStream()
                            var prevByte = -1
                            var insideJpeg = false

                            var frameCount = 0
                            var lastFpsTime = SystemClock.elapsedRealtime()

                            while (isActive) {
                                val currByte = inputStream.read()
                                if (currByte == -1) break

                                if (!insideJpeg) {
                                    if (prevByte == 0xFF && currByte == 0xD8) {
                                        insideJpeg = true
                                        jpegBuffer.reset()
                                        jpegBuffer.write(0xFF)
                                        jpegBuffer.write(0xD8)
                                    }
                                } else {
                                    jpegBuffer.write(currByte)
                                    if (prevByte == 0xFF && currByte == 0xD9) {
                                        insideJpeg = false
                                        val bytes = jpegBuffer.toByteArray()
                                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                                        if (bitmap != null) {
                                            frameCount++
                                            val now = SystemClock.elapsedRealtime()
                                            if (now - lastFpsTime >= 1000L) {
                                                val fpsCalc = frameCount
                                                frameCount = 0
                                                lastFpsTime = now
                                                withContext(Dispatchers.Main) { videoFps = fpsCalc }
                                            }

                                            withContext(Dispatchers.Main) {
                                                currentFrame = bitmap
                                            }
                                            detectorHelper.detectLiveStream(bitmap)
                                        }
                                    }
                                }
                                prevByte = currByte
                            }
                        }
                    } catch (_: Exception) {
                        withContext(Dispatchers.Main) { videoFps = 0 }
                    }
                }
                delay(300)
            }
        }
    }

    // 2. Lectura paralela de distancia del sensor ultrasónico (puerto 81)
    LaunchedEffect(usarCamaraLocal) {
        if (!usarCamaraLocal) {
            val distClient = OkHttpClient.Builder()
                .connectTimeout(300, TimeUnit.MILLISECONDS)
                .readTimeout(300, TimeUnit.MILLISECONDS)
                .build()
            val distRequest = Request.Builder().url(urlDistanceEsp32).build()

            while (isActive) {
                withContext(Dispatchers.IO) {
                    try {
                        distClient.newCall(distRequest).execute().use { response ->
                            val text = response.body?.string()?.trim()
                            val parsed = text?.toIntOrNull() ?: -1
                            withContext(Dispatchers.Main) {
                                distanceCm = parsed
                            }
                        }
                    } catch (_: Exception) {
                        withContext(Dispatchers.Main) {
                            distanceCm = -1
                        }
                    }
                }
                delay(100)
            }
        } else {
            distanceCm = -1
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (usarCamaraLocal) {
            CameraXView(
                onFrameCaptured = { bitmap ->
                    detectorHelper.detectLiveStream(bitmap)
                }
            )
        } else {
            currentFrame?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Stream ESP32",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        BoundingBoxOverlay(
            results = detectionResults,
            imageWidth = imageSize.first,
            imageHeight = imageSize.second,
            distanceCm = distanceCm
        )

        // Barra superior: FPS, control del buzzer y selector de cámara
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (usarCamaraLocal) "Cámara Celular" else "ESP32-S3 • $videoFps FPS",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(Color(0xAA000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!usarCamaraLocal) {
                    Button(
                        onClick = {
                            val nextMute = !buzzerMuted
                            buzzerMuted = nextMute
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val client = OkHttpClient.Builder()
                                        .connectTimeout(400, TimeUnit.MILLISECONDS)
                                        .build()
                                    val req = Request.Builder()
                                        .url("$urlBuzzerBase?mute=${if (nextMute) 1 else 0}")
                                        .build()
                                    client.newCall(req).execute().close()
                                } catch (_: Exception) {
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (buzzerMuted) Color(0xCC757575) else Color(0xCC43A047)
                        )
                    ) {
                        Text(
                            text = if (buzzerMuted) "Buzzer: OFF" else "Buzzer: ON",
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }
                }

                Button(
                    onClick = {
                        detectionResults = null
                        currentFrame = null
                        usarCamaraLocal = !usarCamaraLocal
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC1E88E5))
                ) {
                    Text(
                        text = if (usarCamaraLocal) "Usar ESP32" else "Usar Celular",
                        color = Color.White,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Alerta inferior sincronizada con los umbrales del buzzer (<= 65 cm pita, <= 20 cm fijo)
        if (distanceCm in 2..400) {
            val metros = String.format(Locale.US, "%.2f", distanceCm / 100f)
            val alertColor = when {
                distanceCm <= 20 -> Color(0xE6D32F2F)   // Rojo intenso: pitido continuo
                distanceCm <= 65 -> Color(0xDDF57C00)  // Naranja: pitido intermitente acelerado
                else -> Color(0xCC2E7D32)               // Verde: más de 65 cm (sin pitido)
            }
            val mensaje = when {
                distanceCm <= 20 -> "¡ALTO! Obstáculo a $metros m ($distanceCm cm)"
                distanceCm <= 65 -> "¡PRECAUCIÓN! A $metros m ($distanceCm cm)"
                else -> "Distancia segura: $metros m"
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .background(alertColor, RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = mensaje,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun CameraXView(onFrameCaptured: (Bitmap) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = context as LifecycleOwner
    val executor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(executor) { imageProxy ->
                            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                            val rawBitmap = imageProxy.toBitmap()

                            val bitmap = if (rotationDegrees != 0) {
                                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                                Bitmap.createBitmap(
                                    rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true
                                )
                            } else {
                                rawBitmap
                            }

                            onFrameCaptured(bitmap)
                            imageProxy.close()
                        }
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalyzer
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun BoundingBoxOverlay(
    results: ObjectDetectorResult?,
    imageWidth: Int,
    imageHeight: Int,
    distanceCm: Int
) {
    val textPaint = remember {
        Paint().apply {
            color = android.graphics.Color.YELLOW
            textSize = 44f
            style = Paint.Style.FILL
            isFakeBoldText = true
            setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK)
        }
    }

    val traducciones = remember {
        mapOf(
            "person" to "Persona",
            "car" to "Auto",
            "truck" to "Camioneta / Camión",
            "bus" to "Autobús",
            "motorcycle" to "Moto",
            "bicycle" to "Bicicleta",
            "dog" to "Perro",
            "cat" to "Gato",
            "chair" to "Silla",
            "bench" to "Banca",
            "fire hydrant" to "Hidrante",
            "stop sign" to "Señal de alto",
            "backpack" to "Mochila",
            "suitcase" to "Maleta",
            "potted plant" to "Maceta",
            "bottle" to "Botella",
            "cup" to "Vaso / Taza",
            "cell phone" to "Celular",
            "laptop" to "Laptop"
        )
    }

    val detections = results?.detections().orEmpty()

    val closestDetection = if (distanceCm in 2..400) {
        detections.maxByOrNull { it.boundingBox().bottom }
    } else {
        null
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val scaleX = size.width / imageWidth.toFloat()
        val scaleY = size.height / imageHeight.toFloat()

        detections.forEach { detection ->
            val box = detection.boundingBox()
            val left = box.left * scaleX
            val top = box.top * scaleY
            val right = box.right * scaleX
            val bottom = box.bottom * scaleY

            val isClosest = (detection == closestDetection)
            val boxColor = when {
                isClosest && distanceCm <= 20 -> Color.Red
                isClosest && distanceCm <= 65 -> Color(0xFFFF9800)
                isClosest -> Color.Green
                else -> Color.Red
            }

            drawRect(
                color = boxColor,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = 8f)
            )

            detection.categories().firstOrNull()?.let { category ->
                val rawName = category.categoryName().lowercase(Locale.ROOT)
                val label = traducciones[rawName] ?: category.categoryName()
                val porcentaje = (category.score() * 100).toInt()

                val textoDistancia = if (isClosest) {
                    val metros = String.format(Locale.US, "%.2f", distanceCm / 100f)
                    " • $metros m"
                } else {
                    ""
                }

                val texto = "$label $porcentaje%$textoDistancia"
                drawContext.canvas.nativeCanvas.drawText(
                    texto,
                    left,
                    (top - 12f).coerceAtLeast(48f),
                    textPaint
                )
            }
        }
    }
}

class ObjectDetectorHelper(
    context: Context,
    private val onResults: (ObjectDetectorResult, Int, Int) -> Unit
) {
    private var objectDetector: ObjectDetector? = null
    private var lastTimestamp = 0L

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("efficientdet_lite2.tflite")
            .build()

        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setMaxResults(8)
            .setScoreThreshold(0.38f)
            .setResultListener { result, inputImage ->
                onResults(result, inputImage.width, inputImage.height)
            }
            .setErrorListener { e ->
                e.printStackTrace()
            }
            .build()

        objectDetector = ObjectDetector.createFromOptions(context, options)
    }

    fun detectLiveStream(bitmap: Bitmap) {
        val now = SystemClock.uptimeMillis()
        val timestamp = if (now <= lastTimestamp) lastTimestamp + 1 else now
        lastTimestamp = timestamp

        val mpImage = BitmapImageBuilder(bitmap).build()
        objectDetector?.detectAsync(mpImage, timestamp)
    }

    fun close() {
        objectDetector?.close()
    }
}