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

// GeckoView engine wrapper composable
@Composable
fun CleanGeckoView(
    initialUrl: String,
    savedState: GeckoSession.SessionState?,
    isDesktop: Boolean,
    isIncognito: Boolean,
    dbHelper: NativeBrowserDb,
    onSessionCreated: (GeckoSession) -> Unit,
    onUrlChanged: (String) -> Unit,
    onTitleChanged: (String?) -> Unit,
    onProgressChanged: (Int) -> Unit,
    onNavStateChanged: (Boolean, Boolean) -> Unit,
    onLongPress: (ContextMenuData) -> Unit,
    onConfirmRedirect: (String) -> Unit,
    onCrashed: () -> Unit = {},
    onSuspend: (GeckoSession.SessionState?, Bitmap?) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    var customVideoView by remember { mutableStateOf<View?>(null) }

    // Tracks the latest session state (GeckoView 130 removed session.saveState();
    // the current state must instead be cached from ProgressDelegate.onSessionStateChange).
    val latestSessionStateHolder = remember { arrayOfNulls<GeckoSession.SessionState?>(1) }
    // Tracks back/forward availability (GeckoSession no longer exposes canGoBack/canGoForward
    // directly; they must be tracked from NavigationDelegate.onCanGoBack/onCanGoForward).
    val navStateHolder = remember { booleanArrayOf(false, false) } // [canGoBack, canGoForward]

    // File Chooser state
    var pendingFilePrompt by remember { mutableStateOf<GeckoSession.PromptDelegate.FilePrompt?>(null) }
    var pendingFileResult by remember { mutableStateOf<GeckoResult<GeckoSession.PromptDelegate.PromptResponse>?>(null) }
    val filePickerLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (uri != null && pendingFilePrompt != null) {
            pendingFileResult?.complete(pendingFilePrompt?.confirm(context, uri))
        } else {
            pendingFileResult?.complete(pendingFilePrompt?.dismiss())
        }
        pendingFilePrompt = null
        pendingFileResult = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize().background(Color.White),
            factory = { ctx ->
                val geckoView = GeckoView(ctx)
                
                // Gecko Session Setup
                val settings = GeckoSessionSettings.Builder()
                    .usePrivateMode(isIncognito)
                    .userAgentMode(if (isDesktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                    .suspendMediaWhenInactive(true)
                    .allowJavascript(true)
                    .build()

                val session = GeckoSession(settings)
                
                // --- 1. Progress Delegate ---
                session.progressDelegate = object : GeckoSession.ProgressDelegate {
                    override fun onPageStart(session: GeckoSession, url: String) {
                        onProgressChanged(10)
                    }

                    override fun onPageStop(session: GeckoSession, success: Boolean) {
                        onProgressChanged(100)
                    }

                    override fun onProgressChange(session: GeckoSession, progress: Int) {
                        onProgressChanged(progress)
                    }

                    override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
                        latestSessionStateHolder[0] = sessionState
                    }
                }

                // --- 2. Content Delegate (Title, Context Menu, FullScreen, Crash) ---
                session.contentDelegate = object : GeckoSession.ContentDelegate {
                    override fun onTitleChange(session: GeckoSession, title: String?) {
                        onTitleChanged(title)
                    }

                    override fun onCrash(session: GeckoSession) {
                        Toast.makeText(ctx, "⚠️ หน้าเว็บมีปัญหา", Toast.LENGTH_SHORT).show()
                        onCrashed()
                    }

                    override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: GeckoSession.ContentDelegate.ContextElement) {
                        val type = when {
                            element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE && element.linkUri != null -> ContextMenuType.IMAGE_LINK
                            element.type == GeckoSession.ContentDelegate.ContextElement.TYPE_IMAGE -> ContextMenuType.IMAGE
                            element.linkUri != null -> ContextMenuType.LINK
                            else -> ContextMenuType.UNKNOWN
                        }
                        val url = element.linkUri ?: element.srcUri ?: ""
                        if (url.isNotEmpty() && type != ContextMenuType.UNKNOWN) {
                            onLongPress(ContextMenuData(type, url))
                        }
                    }

                    override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                        if (fullScreen) {
                            // Needs a dedicated view for video. Using GeckoView's internal system if possible, 
                            // or leave blank to fallback to built-in behavior.
                        } else {
                            customVideoView = null
                        }
                    }
                }

                // --- 3. Navigation Delegate (URL intercept; ad/tracker blocking handled by the uBlock Origin WebExtension) ---
                session.navigationDelegate = object : GeckoSession.NavigationDelegate {
                    override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
                        val urlStr = request.uri

                        if (BrowserSecurity.isDangerous(urlStr)) {
                            Toast.makeText(ctx, "🚫 บล็อกลิงก์ที่ไม่ปลอดภัย", Toast.LENGTH_SHORT).show()
                            return GeckoResult.fromValue(AllowOrDeny.DENY)
                        }

                        val scheme = try { Uri.parse(urlStr).scheme?.lowercase() ?: "" } catch (e: Exception) { "" }
                        val isHttp = scheme == "http" || scheme == "https"

                        if (!isHttp) {
                            onConfirmRedirect(urlStr)
                            return GeckoResult.fromValue(AllowOrDeny.DENY)
                        }

                        // App-level domain redirect checks can be placed here if needed
                        return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                    }

                    override fun onLocationChange(session: GeckoSession, url: String?, perms: List<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                        if (url != null) {
                            onUrlChanged(url)
                            if (!isIncognito && url != INTERNAL_HOME_URL) {
                                thread { dbHelper.insertHistory(url, "Web Page") }
                            }
                        }
                    }

                    override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                        navStateHolder[0] = canGoBack
                        onNavStateChanged(navStateHolder[0], navStateHolder[1])
                    }

                    override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                        navStateHolder[1] = canGoForward
                        onNavStateChanged(navStateHolder[0], canGoForward)
                    }
                }

                // --- 4. Prompt Delegate (File Picker) ---
                session.promptDelegate = object : GeckoSession.PromptDelegate {
                    override fun onFilePrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.FilePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                        pendingFilePrompt = prompt
                        pendingFileResult = result

                        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
                        try {
                            filePickerLauncher.launch(intent)
                        } catch (e: Exception) {
                            result.complete(prompt.dismiss())
                        }
                        return result
                    }
                }

                session.open(GeckoEngine.getRuntime(ctx))
                geckoView.setSession(session)

                if (savedState != null) {
                    session.restoreState(savedState)
                } else if (initialUrl != INTERNAL_HOME_URL) {
                    session.loadUri(initialUrl)
                }

                onSessionCreated(session)
                geckoView
            },
            update = { view ->
                // Switch Desktop Mode Dynamically
                view.session?.settings?.userAgentMode = if (isDesktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
            },
            onRelease = { view ->
                onSuspend(latestSessionStateHolder[0], null) // GeckoView snapshot is asynchronous, omitting here for simplicity
                view.session?.close()
                view.releaseSession()
            }
        )

        customVideoView?.let { videoView ->
            AndroidView(factory = { videoView }, modifier = Modifier.fillMaxSize().background(Color.Black))
        }
    }
}
