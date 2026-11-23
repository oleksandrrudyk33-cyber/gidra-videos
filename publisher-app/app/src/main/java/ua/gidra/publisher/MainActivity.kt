package ua.gidra.publisher

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
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
