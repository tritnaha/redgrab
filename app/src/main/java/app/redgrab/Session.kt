package app.redgrab

import android.util.Base64
import org.json.JSONObject

/**
 * Sign-in session lifetime. RedGifs' access token is a JWT whose payload carries an `exp`
 * (expiry, epoch seconds); we read it when the token is captured. A token without a readable
 * expiry is treated as "unknown" and simply used until RedGifs answers 401.
 */
object Session {
    enum class State { SignedOut, Valid, ExpiringSoon, Expired, Unknown }

    /** Seconds before expiry at which we start renewing. */
    const val RENEW_MARGIN_S = 15 * 60L

    fun expiryOf(token: String): Long = runCatching {
        val payload = token.split(".").getOrNull(1) ?: return 0
        val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        JSONObject(json).optLong("exp", 0)
    }.getOrDefault(0)

    fun state(prefs: Prefs, nowS: Long = System.currentTimeMillis() / 1000): State = when {
        !prefs.signedIn -> State.SignedOut
        prefs.tokenExp <= 0 -> State.Unknown
        nowS >= prefs.tokenExp -> State.Expired
        nowS >= prefs.tokenExp - RENEW_MARGIN_S -> State.ExpiringSoon
        else -> State.Valid
    }

    /** Human description, e.g. "Valid for 21 h" or "Expired 2 h ago". */
    fun describe(prefs: Prefs, nowS: Long = System.currentTimeMillis() / 1000): String {
        val exp = prefs.tokenExp
        return when (state(prefs, nowS)) {
            State.SignedOut -> "Not signed in"
            State.Unknown -> "Signed in (expiry unknown; renewed automatically when RedGifs asks)"
            State.Expired -> "Session expired ${span(nowS - exp)} ago"
            else -> "Session valid for ${span(exp - nowS)}"
        }
    }

    private fun span(s: Long): String = when {
        s >= 86400 -> "${s / 86400} d ${(s % 86400) / 3600} h"
        s >= 3600 -> "${s / 3600} h ${(s % 3600) / 60} min"
        else -> "${(s / 60).coerceAtLeast(1)} min"
    }
}
