package com.squarecamera.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaActionSound
import android.net.Uri
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Timer10
import androidx.compose.material.icons.filled.Timer3
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.roundToInt

private val Chip = Color.Black.copy(alpha = 0.45f)
private val Accent = Color(0xFFC2E7FF)
private val TopBarHeight = 56.dp

/** The newest photo, for the round thumbnail that opens it in Google Photos. */
private class Shot(val uri: Uri, val thumb: ImageBitmap?)

/**
 * A Pixel-style camera: quick settings along the top, the viewfinder in the shape of the photo
 * (1:1 by default), zoom stops, tap to focus, pinch to zoom, and the thumbnail · shutter · flip
 * row at the bottom.
 */
@Composable
fun CameraScreen(activity: MainActivity) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(context) }
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) askPermission.launch(Manifest.permission.CAMERA) }

    var ratio by remember { mutableStateOf(prefs.ratio) }
    var grid by remember { mutableStateOf(prefs.grid) }
    var flash by remember { mutableStateOf(prefs.flash) }
    var timer by remember { mutableStateOf(prefs.timer) }
    var front by remember { mutableStateOf(false) }

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureFlashMode = flash
        }
    }
    // Frames are turned into photos off the main thread, one at a time.
    val worker = remember { Executors.newSingleThreadExecutor() }
    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    DisposableEffect(Unit) {
        onDispose {
            worker.shutdown()
            sound.release()
        }
    }

    var lastShot by remember { mutableStateOf<Shot?>(null) }
    fun refreshThumbnail() {
        scope.launch {
            lastShot = withContext(Dispatchers.IO) {
                Photos.latest(context)?.let { uri -> Shot(uri, Photos.thumbnail(context, uri)?.asImageBitmap()) }
            }
        }
    }
    // Back from Google Photos (where a photo may have been deleted): refresh the thumbnail.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshThumbnail() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var capturing by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var countdownJob by remember { mutableStateOf<Job?>(null) }
    val dim = remember { Animatable(0f) }

    fun capture() {
        if (capturing || !hasPermission) return
        capturing = true
        val shotRatio = ratio.portrait
        val mirror = front
        sound.play(MediaActionSound.SHUTTER_CLICK)
        scope.launch {
            dim.snapTo(0.7f)
            dim.animateTo(0f, tween(220))
        }
        controller.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                mainExecutor.execute { capturing = false }
                try {
                    val bitmap: Bitmap
                    val rotation: Int
                    try {
                        bitmap = image.toBitmap()
                        rotation = image.imageInfo.rotationDegrees
                    } finally {
                        image.close()
                    }
                    val uri = Photos.save(context, Photos.process(bitmap, rotation, shotRatio, mirror))
                    val thumb = Photos.thumbnail(context, uri)?.asImageBitmap()
                    mainExecutor.execute { lastShot = Shot(uri, thumb) }
                } catch (e: Exception) {
                    mainExecutor.execute {
                        Toast.makeText(context, "Couldn't save the photo: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                mainExecutor.execute {
                    capturing = false
                    Toast.makeText(context, "Couldn't take the photo: ${exception.message}", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    /** The shutter: straight away, or after the self-timer. Pressing it again stops the timer. */
    fun shutter() {
        if (countdown != null) {
            countdownJob?.cancel()
            countdown = null
            return
        }
        if (timer == 0) return capture()
        countdownJob = scope.launch {
            for (s in timer downTo 1) {
                countdown = s
                delay(1000)
            }
            countdown = null
            capture()
        }
    }

    DisposableEffect(activity) {
        activity.onShutterKey = { shutter() }
        onDispose { activity.onShutterKey = null }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        // The viewfinder has the photo's shape. 1:1 sits in the middle of where 4:3 would be;
        // 16:9 is centered on the screen with the controls over it, like the Pixel camera.
        val width = maxWidth
        val viewfinder = when (ratio) {
            Ratio.WIDE -> Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(ratio.portrait)
            Ratio.FULL -> Modifier.align(Alignment.TopCenter).padding(top = TopBarHeight).fillMaxWidth().aspectRatio(ratio.portrait)
            Ratio.SQUARE -> Modifier.align(Alignment.TopCenter).padding(top = TopBarHeight + (width * 4 / 3 - width) / 2)
                .fillMaxWidth().aspectRatio(1f)
        }

        Box(viewfinder) {
            if (hasPermission) {
                Viewfinder(controller)
            } else {
                Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Allow camera access to take photos.", color = Color.White, textAlign = TextAlign.Center)
                    Button(onClick = { askPermission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                }
            }
            if (grid) GridLines(Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim.value)))
            countdown?.let {
                Text(
                    "$it", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Light,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (hasPermission) {
                ZoomStops(controller, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
            }
        }

        // Quick settings: flash · timer · ratio · grid
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(TopBarHeight).padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopIcon(
                when (flash) {
                    ImageCapture.FLASH_MODE_AUTO -> Icons.Filled.FlashAuto
                    ImageCapture.FLASH_MODE_ON -> Icons.Filled.FlashOn
                    else -> Icons.Filled.FlashOff
                },
                "Flash",
            ) {
                flash = when (flash) {
                    ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                    ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                    else -> ImageCapture.FLASH_MODE_OFF
                }
                prefs.flash = flash
                controller.imageCaptureFlashMode = flash
            }
            TopIcon(
                when (timer) {
                    3 -> Icons.Filled.Timer3
                    10 -> Icons.Filled.Timer10
                    else -> Icons.Filled.TimerOff
                },
                "Timer",
            ) {
                timer = when (timer) {
                    0 -> 3
                    3 -> 10
                    else -> 0
                }
                prefs.timer = timer
            }
            Text(
                ratio.label,
                color = if (ratio == Ratio.SQUARE) Color.Black else Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (ratio == Ratio.SQUARE) Accent else Chip)
                    .clickable {
                        ratio = ratio.next()
                        prefs.ratio = ratio
                    }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
            TopIcon(if (grid) Icons.Filled.GridOn else Icons.Filled.GridOff, "Grid") {
                grid = !grid
                prefs.grid = grid
            }
        }

        // Mode · thumbnail, shutter, flip
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                "Photo", color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Accent).padding(horizontal = 16.dp, vertical = 6.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 36.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val shot = lastShot
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(Chip)
                        .border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                        .clickable(enabled = shot != null) { shot?.let { Photos.open(context, it.uri) } },
                ) {
                    shot?.thumb?.let {
                        Image(it, contentDescription = "Latest photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }

                Box(
                    Modifier.size(80.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape)
                        .clickable(enabled = hasPermission) { shutter() },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(64.dp).clip(CircleShape)
                            .background(if (capturing) Color.White.copy(alpha = 0.5f) else Color.White),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (countdown != null) Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFD93025)))
                    }
                }

                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(Chip).clickable {
                        front = !front
                        controller.cameraSelector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Cameraswitch, contentDescription = "Switch camera", tint = Color.White) }
            }
        }
    }
}

/** The live preview, with tap to focus (and a focus ring where you tap) and pinch to zoom. */
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun Viewfinder(camera: LifecycleCameraController) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var touch by remember { mutableStateOf(Offset.Zero) }
    var ring by remember { mutableStateOf<Offset?>(null) }
    val focusState by camera.tapToFocusState.observeAsState()

    LaunchedEffect(focusState) {
        when (focusState) {
            CameraController.TAP_TO_FOCUS_STARTED -> ring = touch
            CameraController.TAP_TO_FOCUS_FOCUSED,
            CameraController.TAP_TO_FOCUS_NOT_FOCUSED,
            CameraController.TAP_TO_FOCUS_FAILED -> {
                delay(700)
                ring = null
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    // The default (SurfaceView) preview spills past its box when cropped, so 1:1
                    // looked taller than the photo; this one stays clipped to the box.
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    setController(camera)
                    // Only notes where the finger lifted; the camera itself handles the tap and pinch.
                    setOnTouchListener { _, e ->
                        if (e.action == MotionEvent.ACTION_UP) touch = Offset(e.x, e.y)
                        false
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        ring?.let { at ->
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Color.White, radius = 36.dp.toPx(), center = at, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
    // The camera runs only while the app is on screen.
    DisposableEffect(camera, lifecycleOwner) {
        camera.bindToLifecycle(lifecycleOwner)
        onDispose { camera.unbind() }
    }
}

/** Rule-of-thirds lines. */
@Composable
private fun GridLines(modifier: Modifier) {
    Canvas(modifier) {
        val c = Color.White.copy(alpha = 0.5f)
        val stroke = 1.dp.toPx()
        for (i in 1..2) {
            val x = size.width * i / 3
            val y = size.height * i / 3
            drawLine(c, Offset(x, 0f), Offset(x, size.height), stroke)
            drawLine(c, Offset(0f, y), Offset(size.width, y), stroke)
        }
    }
}

/** The .5 · 1 · 2 · 5 zoom stops; the current one shows the exact zoom, e.g. "1.4×". */
@Composable
private fun ZoomStops(controller: LifecycleCameraController, modifier: Modifier) {
    val zoomState by controller.zoomState.observeAsState()
    val state = zoomState ?: return
    val zoom = state.zoomRatio
    val stops = buildList {
        if (state.minZoomRatio < 0.95f) add(state.minZoomRatio)
        add(1f)
        if (state.maxZoomRatio >= 2f) add(2f)
        if (state.maxZoomRatio >= 5f) add(5f)
    }
    if (stops.size < 2) return
    val active = stops.lastOrNull { it <= zoom + 0.05f } ?: stops.first()

    Row(
        modifier.clip(RoundedCornerShape(50)).background(Chip).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stops.forEach { stop ->
            val selected = stop == active
            Box(
                Modifier.size(if (selected) 44.dp else 36.dp).clip(CircleShape)
                    .background(if (selected) Color.White.copy(alpha = 0.25f) else Color.Transparent)
                    .clickable { controller.setZoomRatio(stop) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (selected) zoomLabel(zoom) + "×" else zoomLabel(stop),
                    color = if (selected) Accent else Color.White,
                    fontSize = if (selected) 13.sp else 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** 0.5 → ".5", 1 → "1", 1.4 → "1.4". */
private fun zoomLabel(z: Float): String {
    val tenths = (z * 10).roundToInt()
    return when {
        tenths < 10 -> ".$tenths"
        tenths % 10 == 0 -> "${tenths / 10}"
        else -> "${tenths / 10}.${tenths % 10}"
    }
}

@Composable
private fun TopIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, tint = Color.White) }
}
