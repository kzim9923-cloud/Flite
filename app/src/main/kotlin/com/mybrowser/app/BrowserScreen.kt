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

// Main browser screen
@Composable
fun BrowserScreen(prefs: SharedPreferences) {
    val context = LocalContext.current
    val dbHelper = remember { NativeBrowserDb(context) }
    val lifecycleOwner = LocalLifecycleOwner.current

    var showDataDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }\n    var showExtensionsScreen by remember { mutableStateOf(false) }

    var searchEngine by remember { mutableStateOf(SearchEngine.valueOf(dbHelper.getSetting("search_engine", SearchEngine.GOOGLE.name))) }

    val restoredTabs = remember { dbHelper.loadTabs() }
    val tabs = remember {
        mutableStateListOf<TabState>().apply {
            if (restoredTabs.isNotEmpty()) restoredTabs.forEach { add(TabState(id = it.id, url = it.url, title = it.title, isDesktop = it.isDesktop)) }
            else add(TabState(url = INTERNAL_HOME_URL))
        }
    }

    var currentTab by rememberSaveable { mutableIntStateOf(0) }
    if (currentTab !in tabs.indices) currentTab = 0

    fun persistTabs() {
        val toSave = tabs.mapIndexedNotNull { idx, t -> if (t.isIncognito) null else SavedTab(t.id, t.url, t.title, t.isDesktop, idx) }
        thread { dbHelper.saveTabs(toSave) }
    }

    val sessionMap = remember { mutableStateMapOf<String, GeckoSession>() }

    var urlBarText by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var showUrlSuggestions by remember { mutableStateOf(false) }
    var urlSuggestions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    var contextMenuData by remember { mutableStateOf<ContextMenuData?>(null) }

    var pendingRedirectUrl by remember { mutableStateOf<String?>(null) }
    var pendingRedirectTabId by remember { mutableStateOf<String?>(null) }

    var bookmarksList by remember { mutableStateOf(dbHelper.getAllBookmarks()) }
    fun refreshBookmarks() { bookmarksList = dbHelper.getAllBookmarks() }
    fun currentSession(): GeckoSession? = sessionMap[tabs.getOrNull(currentTab)?.id]

    fun navigate(target: String) {
        val tab = tabs.getOrNull(currentTab) ?: return
        tab.url = target
        currentSession()?.loadUri(target)
        showUrlSuggestions = false
    }

    fun switchToTab(newIndex: Int) {
        if (newIndex !in tabs.indices) return
        currentTab = newIndex
        showTabs = false

        val target = tabs[newIndex]
        urlBarText = if (target.url != INTERNAL_HOME_URL) target.url else ""

        val session = sessionMap[target.id]
        // In GeckoView, state is restored when attached or navigated
        progress = if (session != null && target.url != INTERNAL_HOME_URL) 100 else 0
        refreshBookmarks()
    }

    fun addTab(url: String = INTERNAL_HOME_URL, incognito: Boolean = false) {
        if (tabs.size >= MAX_TABS) { Toast.makeText(context, "⚠️ เปิดได้สูงสุด $MAX_TABS แท็บ", Toast.LENGTH_SHORT).show(); return }
        tabs.add(TabState(url = url, isIncognito = incognito))
        switchToTab(tabs.size - 1)
        persistTabs()
    }

    fun closeTab(idx: Int) {
        if (tabs.size <= 1 || idx !in tabs.indices) return
        val closedId = tabs[idx].id
        val wasCurrentId = tabs.getOrNull(currentTab)?.id

        sessionMap.remove(closedId)?.close()
        tabs.removeAt(idx)

        val newCurrentIndex = if (closedId == wasCurrentId) idx.coerceAtMost(tabs.size - 1)
        else tabs.indexOfFirst { it.id == wasCurrentId }.takeIf { it >= 0 } ?: (tabs.size - 1)
        switchToTab(newCurrentIndex)
        persistTabs()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> persistTabs()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sessionMap.values.forEach { it.close() }
        }
    }

    BackHandler(enabled = true) {
        when {
            pendingRedirectUrl != null -> { pendingRedirectUrl = null; pendingRedirectTabId = null }
            showUrlSuggestions -> showUrlSuggestions = false
            contextMenuData != null -> contextMenuData = null
            showMenu -> showMenu = false
            showTabs -> showTabs = false
            showDataDialog -> showDataDialog = false
            showSettingsDialog -> showSettingsDialog = false
            canGoBack -> currentSession()?.goBack()
            else -> (context as? ComponentActivity)?.finish()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {

        if (tabs.getOrNull(currentTab)?.isIncognito == true) {
            Surface(color = Color(0xFF3A2A55), modifier = Modifier.fillMaxWidth()) {
                Text("🕶️ โหมดไม่ระบุตัวตน — จะไม่บันทึกประวัติการเข้าชม", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            val activeTab = tabs.getOrNull(currentTab)
            if (activeTab != null) {
                val isTabHome = activeTab.url == INTERNAL_HOME_URL

                if (isTabHome) {
                    CustomHomePage(
                        bookmarks = bookmarksList,
                        onSearch = { query -> val target = BrowserSecurity.sanitizeUrl(query, searchEngine); activeTab.url = target; urlBarText = target; showUrlSuggestions = false },
                        onSelectBookmark = { url -> activeTab.url = url; urlBarText = url; showUrlSuggestions = false }
                    )
                } else {
                    key(activeTab.id, activeTab.reloadKey) {
                        CleanGeckoView(
                            initialUrl = activeTab.url,
                            savedState = activeTab.savedState,
                            isDesktop = activeTab.isDesktop,
                            isIncognito = activeTab.isIncognito,
                            dbHelper = dbHelper,
                            onSessionCreated = { session -> sessionMap[activeTab.id] = session },
                            onUrlChanged = { url ->
                                if (url != INTERNAL_HOME_URL) { activeTab.url = url; urlBarText = url; persistTabs() }
                            },
                            onTitleChanged = { title ->
                                activeTab.title = if (activeTab.url == INTERNAL_HOME_URL) "หน้าแรก" else title?.take(20) ?: "Web Page"
                                persistTabs()
                            },
                            onProgressChanged = { p -> progress = p },
                            onNavStateChanged = { back, fwd -> canGoBack = back; canGoForward = fwd },
                            onLongPress = { contextMenuData = it },
                            onConfirmRedirect = { redirectUrl -> pendingRedirectUrl = redirectUrl; pendingRedirectTabId = activeTab.id },
                            onCrashed = {
                                sessionMap.remove(activeTab.id)?.close()
                                activeTab.savedState = null
                                activeTab.reloadKey++
                            },
                            onSuspend = { state, snapshot ->
                                activeTab.savedState = state
                                activeTab.snapshot = snapshot
                                activeTab.isSuspended = true
                                sessionMap.remove(activeTab.id)
                            }
                        )
                    }
                }
            }

            if (pendingRedirectUrl != null) {
                AlertDialog(
                    onDismissRequest = { pendingRedirectUrl = null; pendingRedirectTabId = null },
                    title = { Text("⚠️ แจ้งเตือนการเปลี่ยนหน้า") },
                    text = { Text("เว็บนี้กำลังพาคุณไปยังลิงก์อื่น คุณต้องการไปยังลิงก์นี้หรือไม่?\n\n$pendingRedirectUrl") },
                    confirmButton = {
                        TextButton(onClick = {
                            val url = pendingRedirectUrl
                            val tabId = pendingRedirectTabId
                            pendingRedirectUrl = null; pendingRedirectTabId = null
                            if (url != null) {
                                val scheme = try { Uri.parse(url).scheme?.lowercase() ?: "" } catch (e: Exception) { "" }
                                if (scheme == "http" || scheme == "https") tabId?.let { sessionMap[it]?.loadUri(url) }
                                else {
                                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                                    catch (e: Exception) { Toast.makeText(context, "⚠️ ไม่พบแอปที่รองรับลิงก์นี้", Toast.LENGTH_SHORT).show() }
                                }
                            }
                        }) { Text("ตกลงไป") }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingRedirectUrl = null; pendingRedirectTabId = null }) { Text("บล็อกลิงก์ (ยกเลิก)") }
                    }
                )
            }

            if (showMenu) {
                val menuTab = tabs.getOrNull(currentTab)
                MenuDrawer(
                    isDesktop = menuTab?.isDesktop ?: false,
                    onHome = { menuTab?.url = INTERNAL_HOME_URL; urlBarText = ""; refreshBookmarks(); showMenu = false },
                    onAddTab = { addTab(); showMenu = false },
                    onAddIncognitoTab = { addTab(incognito = true); showMenu = false },
                    onAddBookmark = {
                        val currentUrl = menuTab?.url ?: ""
                        if (currentUrl.isNotBlank() && currentUrl != INTERNAL_HOME_URL) {
                            if (dbHelper.isBookmarked(currentUrl)) Toast.makeText(context, "ℹ️ บุ๊กมาร์กนี้มีอยู่แล้ว", Toast.LENGTH_SHORT).show()
                            else {
                                dbHelper.insertBookmark(currentUrl, menuTab?.title ?: currentUrl, null) // Favicon support needs extraction from GeckoView
                                refreshBookmarks()
                                Toast.makeText(context, "📌 เพิ่มบุ๊กมาร์กเรียบร้อย", Toast.LENGTH_SHORT).show()
                            }
                        }
                        showMenu = false
                    },
                    onOpenHistoryBookmarks = { showDataDialog = true; showMenu = false },
                    onToggleDesktop = {
                        tabs.getOrNull(currentTab)?.let { t ->
                            t.isDesktop = !t.isDesktop
                            sessionMap[t.id]?.settings?.userAgentMode = if (t.isDesktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
                            sessionMap[t.id]?.reload()
                        }
                        persistTabs(); showMenu = false
                    },
                    onSettings = { showSettingsDialog = true; showMenu = false },
                    onExtensions = { showExtensionsScreen = true; showMenu = false },
                    onClearData = { Toast.makeText(context, "🧹 (Clear Cache ถูกจัดการโดย Runtime)", Toast.LENGTH_SHORT).show(); showMenu = false },
                    onExit = {
                        persistTabs()
                        sessionMap.values.forEach { it.close() }
                        (context as? ComponentActivity)?.finishAffinity()
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 8.dp, end = 8.dp)
                )
            }

            if (showTabs) {
                FullScreenTabSwitcher(
                    tabs = tabs, currentTab = currentTab,
                    onSelect = { switchToTab(it) }, onClose = { closeTab(it) }, onAddTab = { addTab() },
                    onAddIncognitoTab = { addTab(incognito = true) }, onDismiss = { showTabs = false }, modifier = Modifier.fillMaxSize().padding(10.dp)
                )
            }

            contextMenuData?.let { data ->
                ContextMenuSheet(
                    data = data,
                    onOpenInNewTab = { url -> addTab(url) },
                    onCopyLink = { url -> copyToClipboard(context, url) },
                    onDownload = { url -> downloadFile(context, url) },
                    onDismiss = { contextMenuData = null },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            if (showExtensionsScreen) { Box(Modifier.fillMaxSize()) { ExtensionsScreen(onBack = { showExtensionsScreen = false }) } }
            if (showDataDialog) DataManagementDialog(dbHelper, onSelectUrl = { navigate(it) }, onDismiss = { showDataDialog = false; refreshBookmarks() })
            if (showSettingsDialog) SettingsDialog(searchEngine, onSelectEngine = { searchEngine = it; dbHelper.setSetting("search_engine", it.name) }, onDismiss = { showSettingsDialog = false })
        }

        if (!showExtensionsScreen) Box {
            BrowserBottomToolbar(
                urlBarText = urlBarText,
                onUrlBarChange = { text ->
                    urlBarText = text
                    if (text.isNotBlank()) { urlSuggestions = dbHelper.searchHistoryAndBookmarks(text); showUrlSuggestions = urlSuggestions.isNotEmpty() } 
                    else showUrlSuggestions = false
                },
                onGo = { navigate(BrowserSecurity.sanitizeUrl(urlBarText, searchEngine)) },
                onBack = { currentSession()?.goBack() },
                onForward = { currentSession()?.goForward() },
                onRefresh = { currentSession()?.reload() },
                onMenu = { showMenu = !showMenu; showTabs = false },
                onTabs = { showTabs = !showTabs; showMenu = false },
                canGoBack = canGoBack, canGoForward = canGoForward, progress = if (tabs.getOrNull(currentTab)?.url == INTERNAL_HOME_URL) 0 else progress
            )

            if (showUrlSuggestions && urlSuggestions.isNotEmpty()) {
                Surface(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).offset(y = (-64).dp), color = Color(0xFF252525), tonalElevation = 8.dp) {
                    Column {
                        urlSuggestions.forEach { (title, url) ->
                            Row(modifier = Modifier.fillMaxWidth().clickable { navigate(BrowserSecurity.sanitizeUrl(url, searchEngine)) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.History, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(title.ifBlank { url }, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(url, color = Color.Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            HorizontalDivider(color = Color(0xFF333333))
                        }
                    }
                }
            }
        }
    }
}
