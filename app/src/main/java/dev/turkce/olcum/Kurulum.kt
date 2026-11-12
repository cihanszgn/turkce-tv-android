package dev.turkce.olcum

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The PS5 turned the pairing code down; the server then looks at the network once to say why. */
internal class PairRefused : IllegalStateException("PlayStation eşleşmeyi kabul etmedi. PS5'ten yeni kod al ve tekrar dene.")

/**
 * Where the phone page's four setup steps stand: 1 Remote Play on, 2 account linked, 3 PS5 chosen, 4 paired.
 * The state lives on the TV, so a reloaded page shows the same ticks.
 */
internal data class SetupSteps(val done: List<Boolean>) {
    val count: Int get() = done.count { it }

    /** The first step still to do, counted from 1; one past the last when all are done (the page's "Hazır" part). */
    val current: Int get() = done.indexOf(false).let { if (it < 0) done.size + 1 else it + 1 }

    fun toJson(): JSONObject = JSONObject().put("adimlar", JSONArray(done)).put("tamam", count).put("sira", current)
}

/** The phone page's PS5 setup: step state, PS5 address typing, and short Turkish errors instead of raw ones. */
internal object SetupGuide {
    private const val FILE = "kurulum"
    private const val REMOTE_PLAY = "uzaktan_oynatma"
    private const val CHOSEN = "secilen_adres"
    /** What phones type or paste between the numbers instead of a dot: a Turkish keypad has only the comma. */
    private const val SEPARATORS = ".,٫、．。"
    /** Four numbers with dots, not glued to more digits on either side (so a typo is shown, not silently cut). */
    private val ADDRESS = Regex("(?<![\\d.])\\d{1,3}(?:\\.\\d{1,3}){3}(?!\\.?\\d)")
    /** Every message the app writes for people is Turkish; text without these letters comes from the system. */
    private val TURKISH = Regex("[çğıöşüÇĞİÖŞÜ]")

    fun steps(remotePlay: Boolean, account: Boolean, chosen: String, paired: Boolean) = SetupSteps(listOf(
        // Pairing works only with Remote Play on and an account linked, so a pairing proves those steps too.
        remotePlay || paired,
        account || paired,
        paired || Ps5Setup.isHomeAddress(chosen),
        paired,
    ))

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
                "$host adresinde PS5 cevap vermedi. PS5 açık mı, TV ile aynı modeme bağlı mı? 3. adımda PS5'i yeniden ara."
            ps5 == null ->
                "$host adresinde PS5 yok; ağdaki PS5 ${found.first().address} adresinde. 3. adımda listeden onu seç, sonra tekrar dene."
            !ps5.awake ->
                "PS5 dinlenme modunda. Kumandadaki PS tuşuna basıp PS5'i aç, Cihaz Bağla'dan yeni kod al, tekrar dene."
            else ->
                "PS5 kodu kabul etmedi. Şunlara bak: kodu doğru yazdın mı; kod hâlâ PS5 ekranında mı (ekran kapandıysa yeni kod al); " +
                    "PS5'te açık olan kullanıcı 2. adımda bağladığın yeni hesap mı."
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
            path == "/api/ps5/eslestir" -> "Eşleştirme yapılamadı. PS5'te Cihaz Bağla ekranını kapatıp yeniden aç, yeni kodla tekrar dene."
            path == "/api/ps5/uyandir" -> "PS5 uyandırılamadı. Kumandadaki PS tuşuna basarak PS5'i elle aç."
            path.startsWith("/api/paket") && error is java.io.IOException ->
                "TV internete çıkamadı ya da dosya alınamadı. TV'nin internet bağlantısına bak, biraz sonra tekrar dene."
            else -> "İşlem yapılamadı. Sayfayı yenileyip tekrar dene."
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Step 1 happens on the PS5, where the TV cannot see it; the page's "Yaptım" button records it. */
    fun markRemotePlay(context: Context, on: Boolean) = prefs(context).edit().putBoolean(REMOTE_PLAY, on).apply()

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
        val remotePlay = prefs.getBoolean(REMOTE_PLAY, false)
        val chosen = prefs.getString(CHOSEN, null) ?: ""
        return steps(remotePlay, ps5.optBoolean("hesap"), chosen, ps5.optBoolean("eslesti")).toJson()
            .put("uzaktanOynatma", remotePlay)
            .put("secilenAdres", chosen)
            .put("onek", networkPrefix(ownAddress) ?: "")
    }
}
