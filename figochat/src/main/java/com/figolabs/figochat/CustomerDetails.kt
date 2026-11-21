package com.figolabs.figochat

import android.annotation.SuppressLint
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import org.json.JSONObject

/** The customer's name, email and phone, as sent with every message. Empty strings when unknown. */
data class FigoCustomerDetails(
    val fullName: String = "",
    val emailAddress: String = "",
    /** International format, e.g. +2348012345678. */
    val mobilePhone: String = "",
) {
    internal val isEmpty: Boolean get() = fullName.isEmpty() && emailAddress.isEmpty() && mobilePhone.isEmpty()
}

/** Where details typed into the details form are remembered, per assistant. A seam for JVM unit tests. */
internal interface FigoDetailsStore {
    fun read(assistantId: String): FigoCustomerDetails?
    fun save(assistantId: String, details: FigoCustomerDetails)
    fun forget(assistantId: String)
}

/**
 * Details kept in the app's SharedPreferences so returning visitors aren't asked again. Without an application
 * context (see [FigoChatInitProvider]) nothing is kept and the visitor is simply asked again.
 */
internal object SharedPreferencesDetailsStore : FigoDetailsStore {
    private const val PREFS = "com.figolabs.figochat.details"

    private fun prefs() = FigoChatInitProvider.appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(assistantId: String) = "details.$assistantId"

    override fun read(assistantId: String): FigoCustomerDetails? = try {
        prefs()?.getString(key(assistantId), null)?.let { raw ->
            val json = JSONObject(raw)
            FigoCustomerDetails(
                fullName = json.optString("fullName", ""),
                emailAddress = json.optString("emailAddress", ""),
                mobilePhone = json.optString("mobilePhone", ""),
            ).takeUnless { it.isEmpty }
        }
    } catch (_: Exception) {
        null // corrupt or unavailable
    }

    override fun save(assistantId: String, details: FigoCustomerDetails) {
        try {
            val json = JSONObject()
                .put("fullName", details.fullName)
                .put("emailAddress", details.emailAddress)
                .put("mobilePhone", details.mobilePhone)
            prefs()?.edit()?.putString(key(assistantId), json.toString())?.apply()
        } catch (_: Exception) {
            // storage unavailable
        }
    }

    override fun forget(assistantId: String) {
        try {
            prefs()?.edit()?.remove(key(assistantId))?.apply()
        } catch (_: Exception) {
            // storage unavailable
        }
    }
}

/**
 * Captures the application context when the app starts, so [FigoChat.initialize] (which takes no context) can
 * remember the details form. Declared in the library manifest; does nothing else.
 */
class FigoChatInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.applicationContext?.let { appContext = it }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    internal companion object {
        // the application context lives as long as the process: no leak
        @SuppressLint("StaticFieldLeak")
        @Volatile
        var appContext: Context? = null
    }
}

internal object FigoDetailsMessages {
    const val NAME = "Please enter your full name."
    const val EMAIL = "Please enter a valid email address."
    const val PHONE = "Please enter a valid phone number."
}

internal enum class FigoDetailsField { FULL_NAME, EMAIL_ADDRESS, MOBILE_PHONE }

/** Result of checking the details form: the details to use, or the first problem found. */
internal data class FigoDetailsValidation(
    val details: FigoCustomerDetails? = null,
    val error: String? = null,
    val field: FigoDetailsField? = null,
)

private val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]{2,}$""")

/** Checks the details form like the Web SDK: name, then email, then a 6–14 digit national number. */
internal fun validateCustomerDetails(fullName: String, emailAddress: String, country: FigoCountry, localPhone: String): FigoDetailsValidation {
    val name = fullName.trim()
    val email = emailAddress.trim()
    val phone = FigoCountries.internationalNumber(country, localPhone.trim())
    val national = phone.count { it in '0'..'9' } - country.dial.length
    return when {
        name.length < 2 -> FigoDetailsValidation(error = FigoDetailsMessages.NAME, field = FigoDetailsField.FULL_NAME)
        !EMAIL.matches(email) -> FigoDetailsValidation(error = FigoDetailsMessages.EMAIL, field = FigoDetailsField.EMAIL_ADDRESS)
        national < 6 || national > 14 -> FigoDetailsValidation(error = FigoDetailsMessages.PHONE, field = FigoDetailsField.MOBILE_PHONE)
        else -> FigoDetailsValidation(details = FigoCustomerDetails(name, email, phone))
    }
}
