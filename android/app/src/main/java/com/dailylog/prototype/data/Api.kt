package com.dailylog.prototype.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class ApiException(val code: String, override val message: String) : Exception(message)

/** The prototype backend. Every call returns parsed data or throws [ApiException] with a message to show. */
class Api(private val baseUrl: String, private val session: Session) {
    private val http =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS) // speech-to-text + the model can take a while on the free tier
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

    private val zone: String get() = ZoneId.systemDefault().id

    suspend fun login(phone: String): LoginResult {
        val json = call(post("auth/login", JSONObject().put("phone", phone)), authed = false)
        return LoginResult(json.getString("token"), json.getString("phone"), json.optBoolean("is_new"))
    }

    suspend fun logout() {
        runCatching { call(post("auth/logout", JSONObject())) }
    }

    suspend fun day(date: LocalDate): DayView = dayOf(call(Request.Builder().url("${baseUrl}days/$date?time_zone=$zone").get()))

    suspend fun submitText(entryId: String, date: LocalDate, text: String): Entry =
        entryOf(
            call(
                post(
                    "entries/text",
                    JSONObject().put("entry_id", entryId).put("log_date", date.toString()).put("time_zone", zone).put("text", text),
                ),
            ),
        )

    suspend fun submitVoice(entryId: String, date: LocalDate, audio: File): Entry {
        val body =
            MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("entry_id", entryId)
                .addFormDataPart("log_date", date.toString())
                .addFormDataPart("time_zone", zone)
                .addFormDataPart("audio", audio.name, audio.asRequestBody("audio/mp4".toMediaType()))
                .build()
        return entryOf(call(Request.Builder().url("${baseUrl}entries/voice").post(body)))
    }

    suspend fun submitPhoto(entryId: String, date: LocalDate, slot: String, jpeg: ByteArray): Entry {
        val body =
            MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("entry_id", entryId)
                .addFormDataPart("log_date", date.toString())
                .addFormDataPart("time_zone", zone)
                .addFormDataPart("slot", slot)
                .addFormDataPart("image", "meal.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
                .build()
        return entryOf(call(Request.Builder().url("${baseUrl}entries/photo").post(body)))
    }

    suspend fun confirmLog(entryId: String, fields: List<Field>): Entry =
        entryOf(call(post("entries/$entryId/confirm", JSONObject().put("fields", JSONArray(fields.map { it.toJson() })))).getJSONObject("entry"))

    suspend fun confirmMeal(entryId: String, slot: String, items: List<MealItem>): Entry =
        entryOf(
            call(post("entries/$entryId/confirm", JSONObject().put("slot", slot).put("items", JSONArray(items.map { it.toJson() }))))
                .getJSONObject("entry"),
        )

    suspend fun discard(entryId: String) {
        runCatching { call(Request.Builder().url("${baseUrl}entries/$entryId").delete()) }
    }

    private fun post(path: String, json: JSONObject): Request.Builder =
        Request.Builder().url("$baseUrl$path").post(json.toString().toRequestBody(JSON))

    private suspend fun call(builder: Request.Builder, authed: Boolean = true): JSONObject =
        withContext(Dispatchers.IO) {
            if (authed) session.token?.let { builder.header("Authorization", "Bearer $it") }
            try {
                http.newCall(builder.build()).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val error = runCatching { JSONObject(text) }.getOrNull()
                        val code = error?.optString("code").orEmpty().ifEmpty { "http_${response.code}" }
                        if (response.code == 401) session.clear()
                        throw ApiException(code, error?.optString("message").orEmpty().ifEmpty { "Something went wrong. Try again." })
                    }
                    JSONObject(text.ifEmpty { "{}" })
                }
            } catch (error: IOException) {
                throw ApiException("offline", "Can't reach the server. Is the backend running and on the same network?")
            } catch (error: JSONException) {
                throw ApiException("bad_response", "The server sent something unexpected.")
            }
        }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
