package dev.turkce.olcum

import android.app.Activity
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs

private const val SYNC_EVERY_MS = 500L
/** How far the picture may run off the clip the service reads before it is put back in step. */
private const val MAX_DRIFT_MS = 300
/** A seek lands a little late; give it time before the next check. */
private const val SEEK_SETTLE_MS = 2_000L
private const val EDGE_PX = 40

/**
 * Deneme modu: the sample clip inside the app plays full screen, while the translation service reads the same clip
 * through the product flow and puts the Turkish on top, as it would over a game. No PS5 and no network. The picture
 * follows the service's clock so the Turkish matches the English on screen. Back, or leaving the screen any other
 * way, stops the demo.
 */
class DenemeActivity : Activity(), SurfaceHolder.Callback {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var surface: SurfaceView
    private lateinit var status: TextView
    private var player: MediaPlayer? = null
    @Volatile private var clip: File? = null
    private var prepared = false
    private var surfaceReady = false
    private var resumed = false
    /** This screen started the demo run, or took over the one running when it was rebuilt, so it is the one to stop it. */
    private var started = false
    private var leaving = false
    private var problem: String? = null
    private var lastSeekAt = 0L
    private val tick = object : Runnable {
        override fun run() {
            maybeStart()
            update()
            handler.postDelayed(this, SYNC_EVERY_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surface = SurfaceView(this).apply { holder.addCallback(this@DenemeActivity) }
        val corner = TextView(this).apply {
            text = "Deneme modu: örnek görüntü"
            textSize = 16f
            setTextColor(Color.WHITE)
            background = rounded(Color.argb(170, 0, 0, 0))
            setPadding(20, 10, 20, 10)
        }
        status = TextView(this).apply {
            text = "Hazırlanıyor…"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(Color.argb(190, 0, 0, 0))
            setPadding(32, 20, 32, 20)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(corner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START).apply { setMargins(EDGE_PX, EDGE_PX, EDGE_PX, EDGE_PX) })
            // Up top, so it never covers the subtitles the demo is about.
            addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = EDGE_PX * 3 })
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                corner.translationX = bars.left.toFloat()
                corner.translationY = bars.top.toFloat()
                view.onApplyWindowInsets(insets)
            }
        }
        setContentView(root)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { leave() }
        }
        thread(isDaemon = true, name = "deneme-klip") {
            val file = runCatching { DemoClip.file(applicationContext) }
                .onFailure { Log.w("TurkceTv", "deneme klibi kopyalanamadi: $it") }
                .getOrNull()
            handler.post {
                if (file == null) {
                    fail(DEMO_FAILED)
                } else {
                    clip = file
                    preparePlayer()
                }
            }
        }
    }

    @Deprecated("Android 13 and later use the callback registered in onCreate.")
    override fun onBackPressed() {
        leave()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        Translation.screenOpen = true
        handler.post(tick)
        maybeStart()
    }

    override fun onPause() {
        resumed = false
        Translation.screenOpen = false
        handler.removeCallbacks(tick)
        super.onPause()
    }

    /** Home, another app or the screen going off: the demo is only for while it is watched. */
    override fun onStop() {
        if (!isChangingConfigurations) leave()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        super.onDestroy()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        val p = player
        if (p == null) preparePlayer() else p.setDisplay(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        player?.setDisplay(null)
    }

    /** The clip loops on mute; it waits on its first frame until the service has started reading. */
    private fun preparePlayer() {
        val file = clip ?: return
        if (!surfaceReady || player != null || leaving) return
        player = MediaPlayer().apply {
            setOnPreparedListener {
                prepared = true
                it.seekTo(0)
                maybeStart()
            }
            setOnErrorListener { _, what, extra ->
                Log.w("TurkceTv", "deneme oynatici hatasi $what/$extra")
                fail(DEMO_FAILED)
                true
            }
            try {
                setDataSource(file.path)
                setDisplay(surface.holder)
                isLooping = true
                setVolume(0f, 0f)
                prepareAsync()
            } catch (error: Exception) {
                Log.w("TurkceTv", "deneme oynaticisi acilamadi: $error")
                fail(DEMO_FAILED)
            }
        }
    }

    /**
     * Starts the translation on the clip once the picture is ready and the screen is in front. The run, not this
     * screen, is the record of what runs: a demo already running (this screen rebuilt after a configuration change)
     * is taken over, and one still stopping is waited for, tick by tick.
     */
    private fun maybeStart() {
        if (!resumed || !prepared || started || leaving || problem != null) return
        if (Translation.running.get() && Translation.demo) {
            if (Translation.stopAsked) return
            started = true
            update()
            return
        }
        val error = runCatching { Translation.start(this, demo = true) }
        when {
            error.isFailure -> fail(error.exceptionOrNull()?.message ?: "Başlatılamadı")
            error.getOrDefault(false) -> started = true
            // Something else runs: one translation at a time.
            else -> fail("Önce çeviriyi durdur.")
        }
        update()
    }

    private fun update() {
        if (problem != null) {
            show("$problem\nGeri tuşuyla çık.")
            return
        }
        if (!started) return
        if (!Translation.running.get()) {
            // Stopped on purpose (the phone or the TV screen): nothing left to show here.
            if (Translation.note == "Kapalı") leave() else fail(Translation.note)
            return
        }
        val note = Translation.note
        show(if (note == "Çeviri açık") null else note)
        follow()
    }

    /** Keeps the picture on the time the service's demo clip is at, so the Turkish fits the English shown. */
    private fun follow() {
        val p = player ?: return
        val startNs = Translation.demoStartNs
        if (!prepared || startNs == 0L) return
        val length = p.duration
        if (length <= 0) return
        val target = ((System.nanoTime() - startNs) / 1_000_000 % length).toInt()
        val now = System.currentTimeMillis()
        if (!p.isPlaying) {
            seek(p, target, now)
            p.start()
            return
        }
        if (now - lastSeekAt < SEEK_SETTLE_MS) return
        var drift = p.currentPosition - target
        if (drift > length / 2) drift -= length
        if (drift < -length / 2) drift += length
        if (abs(drift) > MAX_DRIFT_MS) seek(p, target, now)
    }

    private fun seek(p: MediaPlayer, target: Int, now: Long) {
        p.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST)
        lastSeekAt = now
    }

    private fun show(text: String?) {
        status.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null) status.text = text
    }

    private fun fail(message: String) {
        if (problem == null) problem = message
        runCatching { player?.pause() }
        show("$problem\nGeri tuşuyla çık.")
    }

    /** Stops the demo run this screen started, never a translation someone else started, and goes back. */
    private fun leave() {
        if (leaving) return
        leaving = true
        if (started && Translation.running.get() && Translation.demo) Translation.stop()
        finish()
    }

    private fun releasePlayer() {
        val p = player ?: return
        player = null
        prepared = false
        runCatching { p.release() }
    }
}
