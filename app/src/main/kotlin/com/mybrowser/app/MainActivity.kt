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

// Activity entry point, root app & PIN gate
class MainActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("Browser", Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                BrowserApp(prefs = prefs, onExitApp = { finishAffinity() })
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 5. Root App & PIN Gate
// ═══════════════════════════════════════════════════════════
@Composable
fun BrowserApp(prefs: SharedPreferences, onExitApp: () -> Unit) {
    var unlocked by rememberSaveable { mutableStateOf(false) }
    if (!unlocked) PasswordGateDialog(prefs = prefs, onUnlocked = { unlocked = true }, onExit = onExitApp)
    else BrowserScreen(prefs = prefs)
}

@Composable
fun PasswordGateDialog(prefs: SharedPreferences, onUnlocked: () -> Unit, onExit: () -> Unit) {
    val savedHash = remember { prefs.getString("app_pin_hash", "") ?: "" }
    val savedSalt = remember { prefs.getString("app_pin_salt", "") ?: "" }
    val isSetupMode = savedHash.isEmpty()

    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var failedAttempts by remember { mutableIntStateOf(0) }
    val isLockedOut = failedAttempts >= 5

    AlertDialog(
        onDismissRequest = { },
        title = { Text(if (isSetupMode) "🆕 ตั้งรหัสผ่านของคุณ" else "🔐 ยืนยันรหัสผ่าน") },
        text = {
            Column {
                Text(if (isSetupMode) "กรุณากำหนด PIN เพื่อเข้าใช้งาน" else "กรุณากรอกรหัสผ่านเพื่อเข้าสู่ระบบ")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pin, onValueChange = { pin = it; error = null },
                    singleLine = true, enabled = !isLockedOut,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                if (isLockedOut) { Spacer(Modifier.height(4.dp)); Text("⛔ ลองผิดหลายครั้งเกินไป กรุณาปิดแล้วเปิดแอปใหม่", color = MaterialTheme.colorScheme.error) }
                error?.let { Spacer(Modifier.height(4.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isLockedOut,
                onClick = {
                    val entered = pin.trim()
                    if (entered.isEmpty()) { error = "❌ ห้ามปล่อยช่องว่าง"; return@TextButton }
                    if (entered.length < 4) { error = "❌ PIN ต้องมีอย่างน้อย 4 หลัก"; return@TextButton }
                    if (isSetupMode) {
                        val newSalt = PinSecurity.generateSalt()
                        val newHash = PinSecurity.hash(entered, newSalt)
                        prefs.edit().putString("app_pin_hash", newHash).putString("app_pin_salt", newSalt).apply()
                        onUnlocked()
                    } else if (PinSecurity.verify(entered, savedSalt, savedHash)) { onUnlocked() } 
                    else { failedAttempts++; error = "❌ รหัสผ่านไม่ถูกต้อง"; pin = "" }
                }
            ) { Text("ตกลง") }
        },
        dismissButton = { TextButton(onClick = onExit) { Text("ออก") } }
    )
}
