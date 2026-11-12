package dev.turkce.olcum

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import kotlin.concurrent.thread
import kotlin.math.roundToInt

private const val STATUS_EVERY_MS = 2_000L
/** About 5 % of a TV's 960 x 540 dp on each side, so overscan never cuts anything off. */
private const val PAD_X_DP = 48
private const val PAD_Y_DP = 27
/** The QR code's size: easy for a phone to read from the couch, small enough to leave room for every button. */
private const val QR_DP = 140

/**
 * The TV screen: a QR code that opens the phone page, where every setting and the PS5 pairing live, plus the
 * translation state, the game pack in use and one start/stop button for when the phone is not at hand. While it is
 * in front (and the app may draw over the game) it brings up the app's service, which holds the phone panel from
 * then on, so the phone turns the translation on and off even after this screen is closed. Works with the remote's
 * arrows: the focused button turns white. Back closes the screen; the service and a running translation go on.
 */
class AyarActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var state: TextView
    private lateinit var packLine: TextView
    private lateinit var toggle: Button
    private lateinit var allow: Button
    private lateinit var phone: LinearLayout
    /** The page address the QR code shows; the code is drawn again when the service starts over with a new key. */
    private var shownAddress: String? = null
    private var phoneDrawn = false
    @Volatile private var installing = false
    @Volatile private var installError: String? = null
    /** Why Android would not start the app's service from this screen; null when it did. */
    private var serviceProblem: String? = null
    private var message: String? = null
    private var messageUntil = 0L
    /** The permission screen was opened once, so coming back without the permission means the TV needs the guide. */
    private var askedOverlay = false
    private var overlayHelp = false
    private val tick = object : Runnable {
        override fun run() {
            keepServiceUp()
            refresh()
            handler.postDelayed(this, STATUS_EVERY_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = label("", 22f)
        packLine = label("", 18f).apply { setTextColor(SCREEN_DIM_TEXT) }
        toggle = tvButton("Çeviriyi başlat") { startOrStop() }
        allow = tvButton("İzni ver") { askOverlay() }
        installModel()
        ActivePack.ensure(this)
        phone = LinearLayout(this).apply { gravity = Gravity.CENTER }
        showPhone(runCatching { AyarSunucusu.start(this) }.getOrNull())
        // All four buttons on one row, so none is pushed off a 960 x 540 dp screen whatever the status says; each
        // keeps its text width while the row fits and they shrink alike if a large font makes it too wide.
        val buttons = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            isBaselineAligned = false
            for (button in listOf(
                allow,
                toggle,
                tvButton("Deneme modu") { startDemo() },
                tvButton("Hakkında ve lisanslar") { startActivity(Intent(this@AyarActivity, HakkindaActivity::class.java)) },
            )) {
                button.setPadding(dp(20), dp(10), dp(20), dp(10))
                addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    .apply { marginStart = dp(6); marginEnd = dp(6) })
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(PAD_X_DP), dp(PAD_Y_DP), dp(PAD_X_DP), dp(PAD_Y_DP))
            setBackgroundColor(SCREEN_BACKGROUND)
            addView(label("Tv Türkçe Altyazı", 34f))
            addView(label("Oyun, film ve dizi altyazısı Türkçe", 18f).apply { setTextColor(SCREEN_DIM_TEXT) })
            addView(phone, centered())
            addView(state, centered())
            addView(packLine, centered().apply { topMargin = 0 })
            addView(buttons, centered().apply { topMargin = dp(12) })
        }
        // Android 15 and later draw apps under the system bars; keep the content clear of them.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(dp(PAD_X_DP) + bars.left, dp(PAD_Y_DP) + bars.top, dp(PAD_X_DP) + bars.right, dp(PAD_Y_DP) + bars.bottom)
                insets
            }
        }
        // Everything fits a 960 x 540 dp TV; the scroll is only a safety net (a large font setting, a long message),
        // and it follows the remote's focus so every button stays reachable.
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(SCREEN_BACKGROUND)
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { finish() }
        }
        refresh()
        focusDefault(force = true)
    }

    /**
     * The QR code for [address], or why there is none. Whole pixels per module keep the code sharp; the size is set in
     * dp so it is the same share of every TV.
     */
    private fun showPhone(address: String?) {
        if (phoneDrawn && address == shownAddress) return
        phoneDrawn = true
        shownAddress = address
        phone.removeAllViews()
        if (address == null) {
            phone.addView(label("TV ev ağına bağlı değil.\nTelefonla ayar için TV'yi Wi-Fi'a ya da kabloyla modeme bağla.", 20f))
            return
        }
        val modules = qrBitmap(address, 1).width
        phone.addView(ImageView(this).apply { setImageBitmap(qrBitmap(address, maxOf(2, dp(QR_DP) / modules))) })
        phone.addView(label("Telefonun kamerasıyla okut.\n\nÇeviri, altyazı yeri ve görünümü,\nPlayStation bağlantısı ve güvenlik\nhepsi telefondan.",
            20f).apply {
            gravity = Gravity.START
            setPadding(dp(24), 0, 0, 0)
        })
    }

    /**
     * The phone panel lives in the app's service, which Android lets the app start only from a screen in front, so this
     * screen starts it (once the app may draw over the game, which the translation needs anyway) and checks on it each
     * tick. The panel's server is opened here too, so the QR code works even before the permission is given.
     */
    private fun keepServiceUp() {
        if (FirstRun.canOverlay(this) && !OlcumService.alive) serviceProblem = OlcumService.ready(this)
        if (OlcumService.up) serviceProblem = null
        showPhone(runCatching { AyarSunucusu.start(this) }.getOrNull())
    }

    /** Back closes this screen and the TV goes home; the translation service is not tied to it. */
    @Deprecated("Android 13 and later use the callback registered in onCreate.")
    override fun onBackPressed() {
        finish()
    }

    /** First start on a new TV: the model comes out of the APK once, in the background. */
    private fun installModel() {
        if (FirstRun.modelReady(this) || Translation.running.get()) return
        installing = true
        thread(isDaemon = true) {
            installError = runCatching { FirstRun.installModel(this) }.exceptionOrNull()?.let { it.message ?: "Çeviri modeli kurulamadı." }
            installing = false
        }
    }

    /**
     * Opens the TV's own screen for "display over other apps". TVs differ, so it tries the nearest screens that
     * the TV has; when none opens, or the permission is still off on return, the screen shows the way by hand.
     */
    private fun askOverlay() {
        val app = Uri.parse("package:$packageName")
        val screens = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, app),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app),
        )
        val opened = screens.any { it.resolveActivity(packageManager) != null && runCatching { startActivity(it) }.isSuccess }
        askedOverlay = true
        if (!opened) {
            overlayHelp = true
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        if (askedOverlay && !FirstRun.canOverlay(this)) overlayHelp = true
        ActivePack.ensure(this)
        handler.post(tick)
        focusDefault()
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun paired() = runCatching { Ps5Setup.status(this).getBoolean("eslesti") }.getOrDefault(false)

    private fun overlayGuide() =
        "Bu TV izin ekranını açmadı. Kumandayla şuraya git:\n" +
            "Ayarlar > Uygulamalar > Özel uygulama erişimi > Diğer uygulamaların üzerinde göster\n" +
            "ve Tv Türkçe Altyazı uygulamasını aç.\n" +
            "Bu menü yoksa bilgisayardan: adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow"

    private fun refresh() {
        val running = Translation.running.get()
        val allowed = FirstRun.canOverlay(this)
        val paired = paired()
        if (allowed) overlayHelp = false
        allow.visibility = if (allowed) View.GONE else View.VISIBLE
        val serviceTrouble = if (allowed && !OlcumService.up) serviceProblem ?: OlcumService.problem else null
        state.text = when {
            System.currentTimeMillis() < messageUntil -> message ?: ""
            installing -> "Çeviri modeli kuruluyor, birkaç saniye…"
            installError != null -> installError ?: ""
            !allowed && overlayHelp -> overlayGuide()
            !allowed -> "İzin gerekli: altyazının oyunun üstünde görünmesi için\n«İzni ver»e bas, açılan ekranda bu uygulamayı aç, geri gel."
            serviceTrouble != null -> serviceTrouble
            running && Translation.demo -> "● Deneme modu · ${Translation.note}"
            running -> "● ${Translation.note}"
            // Why the last run ended, when it was not stopped on purpose (e.g. the PS5 or the decoder failed).
            Translation.phase == Phase.FAILED -> "○ ${Translation.note}"
            !paired -> "PlayStation eşleşmemiş: telefondaki adımları izle."
            else -> "○ Çeviri kapalı · telefondaki panelden açıp kapatabilirsin"
        }
        packLine.text = ActivePack.summary()
        toggle.text = if (running) "Çeviriyi durdur" else "Çeviriyi başlat"
        // Stays focusable so the remote never gets stuck; pressing it says what is missing.
        toggle.alpha = if (running || paired && allowed && !installing) 1f else 0.5f
        focusDefault()
    }

    /** Puts the remote's focus on the button that matters now, unless a visible one already has it. */
    private fun focusDefault(force: Boolean = false) {
        val focused = currentFocus
        if (!force && focused != null && focused.isShown) return
        (if (allow.visibility == View.VISIBLE) allow else toggle).requestFocus()
    }

    /** A message that stays on screen a few seconds before the status takes over again. */
    private fun tell(text: String) {
        message = text
        messageUntil = System.currentTimeMillis() + 8_000
        state.text = text
    }

    private fun startOrStop() {
        if (Translation.running.get()) {
            Translation.stop()
            refresh()
            return
        }
        val missing = when {
            !FirstRun.canOverlay(this) -> "Önce izin ver: «İzni ver» düğmesine bas."
            installing -> "Çeviri modeli kuruluyor, birkaç saniye sonra tekrar dene."
            !paired() -> "Önce PlayStation'ı telefondan eşleştir."
            else -> null
        }
        if (missing != null) {
            tell(missing)
            return
        }
        OlcumService.ready(this)?.let {
            tell(it)
            return
        }
        val error = runCatching { Translation.start(this) }.exceptionOrNull()
        refresh()
        if (error != null) tell(error.message ?: "Başlatılamadı")
    }

    /** Deneme modu: a sample clip with the Turkish subtitles on top. Needs the permission and the model, not the PS5. */
    private fun startDemo() {
        val running = Translation.running.get()
        val missing = when {
            !FirstRun.canOverlay(this) -> "Önce izin ver: «İzni ver» düğmesine bas."
            installing -> "Çeviri modeli kuruluyor, birkaç saniye sonra tekrar dene."
            installError != null -> installError
            running && !Translation.demo || Translation.waiting -> "Önce çeviriyi durdur."
            // A demo still letting go; one running (started from the phone in a test build) is taken over by the screen.
            running && Translation.stopAsked -> "Deneme modu kapanıyor, birkaç saniye sonra tekrar dene."
            else -> null
        }
        if (missing != null) {
            tell(missing)
            return
        }
        startActivity(Intent(this, DenemeActivity::class.java))
    }

    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun centered() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(8)
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
