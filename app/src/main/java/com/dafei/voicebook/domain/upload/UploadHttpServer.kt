package com.dafei.voicebook.domain.upload

import android.content.Context
import com.dafei.voicebook.R
import com.dafei.voicebook.VoicebookApp
import com.dafei.voicebook.data.model.ImportResult
import com.dafei.voicebook.util.NetworkAddressHelper
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

class UploadHttpServer(
    private val appContext: Context,
    private val dedupMd5s: MutableSet<String>,
    port: Int = NetworkAddressHelper.DEFAULT_UPLOAD_PORT,
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.GET && (session.uri == "/" || session.uri == "/index.html") -> {
                    newFixedLengthResponse(
                        Response.Status.OK,
                        "text/html; charset=utf-8",
                        UploadPageHtml.content,
                    )
                }
                session.method == Method.POST && session.uri == "/upload" -> handleUpload(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"status":"failed","message":"${e.message}"}""")
        }
    }

    private fun handleUpload(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val tempPath = files["file"] ?: return jsonResponse(
            status = "failed",
            message = appContext.getString(R.string.import_error_read_file),
        )
        val fileName = session.parameters["filename"]?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: File(tempPath).name
        val app = appContext.applicationContext as VoicebookApp

        val result = runBlocking {
            app.bookRepository.importFromLocalFile(
                file = File(tempPath),
                fileName = fileName,
                sourceUrl = null,
                batchMd5s = dedupMd5s,
                deleteSourceOnFinish = true,
            )
        }

        return when (result) {
            is ImportResult.Started -> jsonResponse(
                status = "started",
                message = appContext.getString(R.string.import_started),
            )
            is ImportResult.Duplicate -> jsonResponse(
                status = "duplicate",
                message = appContext.getString(R.string.import_duplicate_skipped, result.existingTitle),
            )
            is ImportResult.Failed -> jsonResponse(
                status = "failed",
                message = result.message,
            )
        }
    }

    private fun jsonResponse(status: String, message: String): Response {
        val json = JSONObject().apply {
            put("status", status)
            put("message", message)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }
}
