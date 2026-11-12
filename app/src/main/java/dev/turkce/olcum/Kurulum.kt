package dev.turkce.olcum

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The PS5 turned the pairing code down; the server then looks at the network once to say why. */
internal class PairRefused : IllegalStateException("PlayStation eşleşmeyi kabul etmedi. PS5'ten yeni kod al ve tekrar dene.")

/**
 * Where the phone page's five setup steps stand: 1 new account on the PS5, 2 Remote Play on for it, 3 that account
 * introduced to the app, 4 PS5 found, 5 paired. [locks] says, for each step not done, why it cannot be done yet
 * (empty when it can). The state lives on the TV, so a reloaded page shows the same ticks.
 */
internal data class SetupSteps(val done: List<Boolean>, val locks: List<String> = List(done.size) { "" }) {
    val count: Int get() = done.count { it }

    /** The first step still to do, counted from 1; one past the last when all are done (the page's "Hazır" part). */
    val current: Int get() = done.indexOf(false).let { if (it < 0) done.size + 1 else it + 1 }

    fun toJson(): JSONObject = JSONObject().put("adimlar", JSONArray(done)).put("kilitler", JSONArray(locks))
        .put("tamam", count).put("sira", current)
}

/** The phone page's PS5 setup: step state, PS5 address typing, and short Turkish errors instead of raw ones. */
internal object SetupGuide {
    private const val FILE = "kurulum"
    private const val NEW_ACCOUNT = "yeni_hesap"
    private const val REMOTE_PLAY = "uzaktan_oynama"
    /** Remote Play's mark from the four-step setup; moved to [REMOTE_PLAY] the first time the file is opened. */
    private const val OLD_REMOTE_PLAY = "uzaktan_oynatma"
    private const val CHOSEN = "secilen_adres"
    const val STEPS = 5
    /** What phones type or paste between the numbers instead of a dot: a Turkish keypad has only the comma. */
    private const val SEPARATORS = ".,٫、．。"
    /** Four numbers with dots, not glued to more digits on either side (so a typo is shown, not silently cut). */
    private val ADDRESS = Regex("(?<![\\d.])\\d{1,3}(?:\\.\\d{1,3}){3}(?!\\.?\\d)")
    /** Every message the app writes for people is Turkish; text without these letters comes from the system. */
    private val TURKISH = Regex("[çğıöşüÇĞİÖŞÜ]")

    /**
     * Steps 1 and 2 happen on the PS5, where the TV cannot see them, so the page's buttons record them. The page asks
     * for both before it lets an account be introduced, so a linked account proves step 1, and a pairing (which works
     * only with Remote Play on and the account linked) proves steps 1 to 3, even after a reinstall lost the marks.
     */
    fun steps(newAccount: Boolean, remotePlay: Boolean, account: Boolean, chosen: String, paired: Boolean): SetupSteps {
        val done = listOf(
            newAccount || account || paired,
            remotePlay || paired,
            account || paired,
            paired || Ps5Setup.isHomeAddress(chosen),
            paired,
        )
        val locks = MutableList(done.size) { "" }
        if (!done[2]) {
            locks[2] = when {
                !done[0] && !done[1] -> "Önce 1. ve 2. adımı bitir: yeni hesabı aç, o hesapta Uzaktan Oynama'yı etkinleştir."
                !done[0] -> "Önce 1. adımı bitir: yeni hesabı aç ve PS5'te o hesaba geç."
                !done[1] -> "Önce 2. adımı bitir: yeni hesapta Uzaktan Oynama'yı etkinleştir."
                else -> ""
            }
        }
        if (!done[4] && !done[2]) locks[4] = "Önce 3. adımda yeni hesabı uygulamaya tanıt; eşleştirme o hesapla yapılır."
        return SetupSteps(done, locks)
    }

    /** The first three numbers of the TV's home address ("192.168.1"), which the PS5 on the same network shares. */
    fun networkPrefix(address: String?): String? =
        address?.trim()?.takeIf { Ps5Setup.isHomeAddress(it) }?.substringBeforeLast('.')

    /**
     * What a person typed or pasted, as an address: commas become dots, any script's digits become 0-9, words around
     * the address are dropped and leading zeros go ("192,168,001,20" -> "192.168.1.20"). Not checked here.
     */
    fun cleanAddress(text: String): String {
        val mapped = text.trim().map { c ->
            when {
                c in SEPARATORS -> '.'
                c.isDigit() -> '0' + c.digitToInt()
                else -> ' '
            }
        }.joinToString("")
        val address = ADDRESS.find(mapped)?.value ?: mapped.replace(" ", "")
        val parts = address.split('.')
        return if (parts.size == 4 && parts.all { it.length in 1..3 }) parts.joinToString(".") { it.toInt().toString() } else address
    }

    /** Why a cleaned address cannot be the PS5's, in words for the page; null when it can. */
    fun addressProblem(address: String): String? {
        if (address.isEmpty()) return "Adresi yaz: noktayla ayrılmış dört sayı, ör. 192.168.1.20."
        val parts = address.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) {
            return "Adres noktayla ayrılmış dört sayıdır, ör. 192.168.1.20. Bir sayı eksik ya da fazla."
        }
        parts.firstOrNull { it.length > 3 || it.toInt() > 255 }?.let { return "Adresteki her sayı 0 ile 255 arasındadır; $it olamaz." }
        if (parts[3].toInt() !in 1..254) return "Son sayı 1 ile 254 arasında olur."
        if (!Ps5Setup.isHomeAddress(address)) {
            return "Bu adres ev ağına ait değil. PS5'in adresi çoğunlukla 192.168. ile başlar; PS5'teki adrese yeniden bak."
        }
        return null
    }

    /** Why the PS5 at [host] said no, judged from who answered a search right after. */
    fun pairRefused(host: String, found: List<FoundPs5>): String {
        val ps5 = found.firstOrNull { it.address == host }
        return when {
            ps5 == null && found.isEmpty() ->
                "$host adresinde PS5 cevap vermedi. PS5 açık mı, TV ile aynı modeme bağlı mı? 4. adımda PS5'i yeniden ara."
            ps5 == null ->
                "$host adresinde PS5 yok; ağdaki PS5 ${found.first().address} adresinde. 4. adımda listeden onu seç, sonra tekrar dene."
            !ps5.awake ->
                "PS5 dinlenme modunda. Kumandadaki PS tuşuna basıp PS5'i aç, Cihazı Eşleştir'den yeni kod al, tekrar dene."
            else ->
                "PS5 kodu kabul etmedi. Şunlara bak: kodu doğru yazdın mı; kod hâlâ PS5 ekranında mı (ekran kapandıysa yeni kod al); " +
                    "PS5'te açık olan kullanıcı 3. adımda tanıttığın yeni hesap mı."
        }
    }

    /** A short Turkish message for the page; raw system text (English, or echoing what was sent) never reaches it. */
    fun friendlyError(path: String, error: Throwable): String {
        if (error !is NumberFormatException && error !is JSONException) {
            error.message?.takeIf { TURKISH.containsMatchIn(it) }?.let { return it }
        }
        return when {
            path == "/api/ps5/psn" && error is java.io.IOException ->
                "TV, Sony'nin sunucusuna ulaşamadı. TV'nin internete bağlı olduğuna bak, sonra «Sony'de giriş yap»a yeniden bas."
            path == "/api/ps5/psn" -> "Sony'den beklenmeyen bir cevap geldi. «Sony'de giriş yap»a yeniden bas, yeni adresi yapıştır."
            path == "/api/ps5/bul" ->
                "Ev ağında arama yapılamadı. TV'nin Wi-Fi'a ya da kabloyla modeme bağlı olduğuna bak; olmazsa PS5'in adresini elle yaz."
            path == "/api/ps5/eslestir" -> "Eşleştirme yapılamadı. PS5'te Cihazı Eşleştir ekranını kapatıp yeniden aç, yeni kodla tekrar dene."
            path == "/api/ps5/uyandir" -> "PS5 uyandırılamadı. Kumandadaki PS tuşuna basarak PS5'i elle aç."
            path.startsWith("/api/paket") && error is java.io.IOException ->
                "TV internete çıkamadı ya da dosya alınamadı. TV'nin internet bağlantısına bak, biraz sonra tekrar dene."
            else -> "İşlem yapılamadı. Sayfayı yenileyip tekrar dene."
        }
    }

    /** The setup file, with the four-step setup's Remote Play mark moved over once; an odd old value counts as not done. */
    @Synchronized
    private fun prefs(context: Context): SharedPreferences {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (prefs.contains(OLD_REMOTE_PLAY)) {
            val all = prefs.all
            prefs.edit().putBoolean(REMOTE_PLAY, mark(all[OLD_REMOTE_PLAY]) || mark(all[REMOTE_PLAY])).remove(OLD_REMOTE_PLAY).apply()
        }
        return prefs
    }

    /** A stored mark as yes or no; anything odd (missing, or another type from an older build) is "not done", never a crash. */
    fun mark(value: Any?): Boolean = value as? Boolean ?: false

    private fun flag(prefs: SharedPreferences, key: String) = mark(prefs.all[key])

    /** Step 1: the page's "Yeni hesabı açtım" button. */
    fun markNewAccount(context: Context, on: Boolean) = prefs(context).edit().putBoolean(NEW_ACCOUNT, on).apply()

    /** Step 2: the page's "Açtım" button. */
    fun markRemotePlay(context: Context, on: Boolean) = prefs(context).edit().putBoolean(REMOTE_PLAY, on).apply()

    /** "Baştan başla": the PS5-side marks and the chosen address go; the account and pairing are forgotten separately. */
    fun reset(context: Context) = prefs(context).edit().remove(NEW_ACCOUNT).remove(REMOTE_PLAY).remove(CHOSEN).apply()

    /** Keeps the PS5 address found or typed on the phone; an empty text forgets it. */
    fun choose(context: Context, text: String): String {
        val address = cleanAddress(text)
        if (address.isEmpty()) {
            prefs(context).edit().remove(CHOSEN).apply()
            return ""
        }
        addressProblem(address)?.let { throw IllegalArgumentException(it) }
        prefs(context).edit().putString(CHOSEN, address).apply()
        return address
    }

    /** The steps for the page, with the TV's network prefix so the page asks only for the PS5's last number. */
    fun toJson(context: Context, ps5: JSONObject, ownAddress: String?): JSONObject {
        val prefs = prefs(context)
        val newAccount = flag(prefs, NEW_ACCOUNT)
        val remotePlay = flag(prefs, REMOTE_PLAY)
        val chosen = prefs.all[CHOSEN] as? String ?: ""
        return steps(newAccount, remotePlay, ps5.optBoolean("hesap"), chosen, ps5.optBoolean("eslesti")).toJson()
            .put("yeniHesap", newAccount)
            .put("uzaktanOynama", remotePlay)
            .put("secilenAdres", chosen)
            .put("onek", networkPrefix(ownAddress) ?: "")
    }
}
