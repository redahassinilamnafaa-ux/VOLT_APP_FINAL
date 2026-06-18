package com.volt.terminal

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class VoltApiClient {

    enum class ValidationStatus {
        APPROVED, COOLDOWN, BLOCKED, QR_EXPIRED_OR_INVALID, NOT_SUBSCRIBED, SERVER_ERROR, UNKNOWN
    }

    data class ValidationResult(
        val status: ValidationStatus,
        val userName: String?  = null,
        val remainingSecs: Long? = null,
        val reason: String?    = null
    )

    companion object {
        private const val TAG           = "VoltApiClient"
        private const val BASE_URL      = "https://volt-backend-final.vercel.app"
        private const val MACHINE_SECRET = "volt-admin-secret-2025"
        private const val MACHINE_ID    = "volt-machine-01"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun validateToken(qrToken: String, machineId: String = MACHINE_ID, callback: (ValidationResult) -> Unit) {
        val body = JSONObject().apply {
            put("qr_token",   qrToken)
            put("machine_id", machineId)
        }.toString()

        val request = Request.Builder()
            .url("$BASE_URL/api/validate")
            .addHeader("Content-Type", "application/json")
            .addHeader("x-machine-secret", MACHINE_SECRET)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Erreur réseau: ${e.message}")
                callback(ValidationResult(ValidationStatus.SERVER_ERROR, reason = e.message))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val result = json.optString("result", "")
                    val reason = json.optString("reason", null.toString())

                    val validationResult = when (result) {
                        "APPROVED" -> ValidationResult(
                            status   = ValidationStatus.APPROVED,
                            userName = json.optString("user_name", null.toString())
                        )
                        "COOLDOWN" -> ValidationResult(
                            status        = ValidationStatus.COOLDOWN,
                            remainingSecs = json.optLong("remaining_secs", 900)
                        )
                        "DENIED" -> {
                            val status = when (reason) {
                                "BLOCKED_BY_GYM" -> ValidationStatus.BLOCKED
                                "NOT_SUBSCRIBED"  -> ValidationStatus.NOT_SUBSCRIBED
                                "QR_EXPIRED_OR_INVALID", "NO_QR_TOKEN" -> ValidationStatus.QR_EXPIRED_OR_INVALID
                                else -> ValidationStatus.UNKNOWN
                            }
                            ValidationResult(status = status, reason = reason)
                        }
                        else -> ValidationResult(ValidationStatus.UNKNOWN, reason = reason)
                    }

                    Log.d(TAG, "validate → $validationResult")
                    callback(validationResult)
                } catch (e: Exception) {
                    Log.e(TAG, "Erreur parsing: ${e.message}")
                    callback(ValidationResult(ValidationStatus.SERVER_ERROR, reason = e.message))
                }
            }
        })
    }
}
