package com.mybrowser.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.webkit.URLUtil
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

// Security helpers: URL sanitization & PIN hashing
object BrowserSecurity {
    private val BLOCKED_SCHEMES = arrayOf("file:", "content:", "javascript:", "vbscript:", "data:")
    private val DOMAIN_REGEX = Regex("^(https?://)?([\\w-]+\\.)+[a-zA-Z]{2,}(:\\d{1,5})?(/.*)?$")

    fun isDangerous(url: String?): Boolean {
        if (url == null) return true
        val u = url.lowercase().trim()
        return BLOCKED_SCHEMES.any { u.startsWith(it) }
    }

    fun sanitizeUrl(input: String?, engine: SearchEngine = SearchEngine.GOOGLE): String {
        if (input.isNullOrBlank()) return INTERNAL_HOME_URL
        var s = input.trim()
        if (isDangerous(s)) return INTERNAL_HOME_URL
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            val looksLikeDomain = !s.contains(" ") && DOMAIN_REGEX.matches(s)
            s = if (looksLikeDomain) "https://$s" else engine.urlPrefix + Uri.encode(s)
        }
        return s
    }
}

object PinSecurity {
    fun generateSalt(): String { val bytes = ByteArray(16); SecureRandom().nextBytes(bytes); return Base64.encodeToString(bytes, Base64.NO_WRAP) }
    fun hash(pin: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256"); var data = (salt + pin).toByteArray(Charsets.UTF_8)
        repeat(1000) { data = digest.digest(data); digest.reset() }
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }
    fun verify(pin: String, salt: String, storedHash: String): Boolean = hash(pin, salt) == storedHash
}
