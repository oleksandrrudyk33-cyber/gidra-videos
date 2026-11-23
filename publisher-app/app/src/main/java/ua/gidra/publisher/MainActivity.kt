package ua.gidra.publisher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Native")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    inner class Bridge {

        // Завантаження відео в галерею телефону (Movies/GidraPublisher). Повертає content:// URI.
        @JavascriptInterface
        fun download(id: String, url: String, name: String) {
            Thread {
                val out = JSONObject()
                try {
                    val res = contentResolver
                    val coll = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    var uri: Uri? = null
                    res.query(coll, arrayOf(MediaStore.Video.Media._ID),
                        MediaStore.Video.Media.DISPLAY_NAME + "=? AND " + MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?",
                        arrayOf(name, "Movies/GidraPublisher%"), null)?.use { c ->
                        if (c.moveToFirst()) uri = Uri.withAppendedPath(coll, c.getLong(0).toString())
                    }
                    if (uri == null) {
                        val v = ContentValues()
                        v.put(MediaStore.Video.Media.DISPLAY_NAME, name)
                        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GidraPublisher")
                        v.put(MediaStore.Video.Media.IS_PENDING, 1)
                        val u = res.insert(coll, v) ?: throw Exception("не вдалось створити файл")
                        val c = URL(url).openConnection() as HttpURLConnection
                        c.connectTimeout = 30000; c.readTimeout = 120000
                        if (c.responseCode != 200) throw Exception("HTTP " + c.responseCode)
                        c.inputStream.use { i -> res.openOutputStream(u)!!.use { o -> i.copyTo(o) } }
                        val done = ContentValues(); done.put(MediaStore.Video.Media.IS_PENDING, 0)
                        res.update(u, done, null, null)
                        uri = u
                    }
                    out.put("status", 200).put("body", uri.toString())
                } catch (e: Exception) {
                    out.put("status", 0).put("body", "Помилка: " + (e.message ?: e.toString()))
                }
                runOnUiThread {
                    web.evaluateJavascript("window.__cb(" + JSONObject.quote(id) + "," + JSONObject.quote(out.toString()) + ")", null)
                }
            }.start()
        }

        // Копіює опис у буфер і відкриває екран публікації потрібного застосунку з відео.
        @JavascriptInterface
        fun share(pkg: String, uri: String, text: String, subject: String): String {
            return try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("caption", text))
                val i = Intent(Intent.ACTION_SEND)
                i.type = "video/mp4"
                i.putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                i.putExtra(Intent.EXTRA_TEXT, text)
                i.putExtra(Intent.EXTRA_SUBJECT, subject)
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                i.setPackage(pkg)
                try {
                    startActivity(i)
                    "ok"
                } catch (e: android.content.ActivityNotFoundException) {
                    i.setPackage(null)
                    startActivity(Intent.createChooser(i, "Поділитися"))
                    "chooser"
                }
            } catch (e: Exception) {
                "err:" + (e.message ?: e.toString())
            }
        }
        // HTTP виконується нативно (без CORS). Результат повертається в JS через window.__cb(id, json).
        @JavascriptInterface
        fun http(id: String, method: String, url: String, headersJson: String, body: String) {
            Thread {
                val out = JSONObject()
                try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.requestMethod = method
                    c.connectTimeout = 30000
                    c.readTimeout = 120000
                    val h = JSONObject(headersJson)
                    for (k in h.keys()) c.setRequestProperty(k, h.getString(k))
                    if (body.isNotEmpty()) {
                        c.doOutput = true
                        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    }
                    val code = c.responseCode
                    val stream = if (code in 200..399) c.inputStream else c.errorStream
                    val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    out.put("status", code).put("body", text)
                } catch (e: Exception) {
                    out.put("status", 0).put("body", "Помилка мережі: " + (e.message ?: e.toString()))
                }
                runOnUiThread {
                    web.evaluateJavascript("window.__cb(" + JSONObject.quote(id) + "," + JSONObject.quote(out.toString()) + ")", null)
                }
            }.start()
        }
    }
}
