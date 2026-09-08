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
import org.mozilla.geckoview.WebExtension
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

// Reusable UI composables (home page, toolbar, dialogs, tab switcher)
@Composable
fun CustomHomePage(bookmarks: List<BookmarkItem>, onSearch: (String) -> Unit, onSelectBookmark: (String) -> Unit) {
    var searchText by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(modifier = Modifier.height(40.dp))
        Text("FOX LITE", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Text("GeckoView Browser", color = Color(0xFF9DA3AE), fontSize = 13.sp)
        Spacer(modifier = Modifier.height(30.dp))
        OutlinedTextField(
            value = searchText, onValueChange = { searchText = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            placeholder = { Text("ค้นหาหรือพิมพ์ URL...", color = Color.Gray) }, singleLine = true, shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color(0xFF1E1E1E), unfocusedContainerColor = Color(0xFF1E1E1E), focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedBorderColor = Color(0xFF333333)),
            trailingIcon = { IconButton(onClick = { if (searchText.isNotBlank()) onSearch(searchText) }) { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { if (searchText.isNotBlank()) onSearch(searchText) })
        )
        Spacer(modifier = Modifier.height(40.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Bookmark, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("บุ๊กมาร์กของคุณ", color = Color.LightGray, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (bookmarks.isEmpty()) Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { Text("ยังไม่มีบุ๊กมาร์ก\n(กดเมนูขวาล่างเพื่อเพิ่มหน้าเว็บลงบุ๊กมาร์ก)", color = Color.DarkGray, textAlign = TextAlign.Center, fontSize = 13.sp) }
        else LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(bookmarks) { item -> BookmarkGridItem(item = item, onClick = { onSelectBookmark(item.url) }) }
        }
    }
}
@Composable
fun BookmarkGridItem(item: BookmarkItem, onClick: () -> Unit) {
    val initial = item.title.trim().take(1).uppercase().ifEmpty { "★" }
    val faviconBitmap = remember(item.favicon) { base64ToBitmap(item.favicon) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(4.dp)) {
        Surface(modifier = Modifier.size(52.dp), shape = RoundedCornerShape(16.dp), color = Color(0xFF252525)) {
            Box(contentAlignment = Alignment.Center) {
                if (faviconBitmap != null) Image(bitmap = faviconBitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)))
                else Text(initial, color = MaterialTheme.colorScheme.primary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(item.title, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
@Composable
fun BrowserBottomToolbar(
    urlBarText: String, onUrlBarChange: (String) -> Unit, onGo: () -> Unit, onBack: () -> Unit, onForward: () -> Unit, onRefresh: () -> Unit, onMenu: () -> Unit, onTabs: () -> Unit, canGoBack: Boolean, canGoForward: Boolean, progress: Int,
    extensionActions: Map<String, WebExtension.Action> = emptyMap(),
    extensionIcons: Map<String, Bitmap> = emptyMap(),
    onExtensionClick: (String) -> Unit = {}
) {
    Column(modifier = Modifier.background(Color(0xFF1E1E1E))) {
        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = canGoBack, modifier = Modifier.size(38.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = if (canGoBack) Color.White else Color.DarkGray) }
            IconButton(onClick = onForward, enabled = canGoForward, modifier = Modifier.size(38.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward", tint = if (canGoForward) Color.White else Color.DarkGray) }
            OutlinedTextField(
                value = urlBarText, onValueChange = onUrlBarChange, modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                placeholder = { Text("ค้นหา...", fontSize = 12.sp, color = Color.Gray) }, singleLine = true, textStyle = TextStyle(fontSize = 13.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { onGo() }),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                        IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.Gray, modifier = Modifier.size(16.dp)) }
                        IconButton(onClick = onGo, modifier = Modifier.size(28.dp)) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Go", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)) }
                    }
                }
            )
            if (extensionActions.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    extensionActions.forEach { (id, action) ->
                        val bitmap = extensionIcons[id]
                        IconButton(onClick = { onExtensionClick(id) }, modifier = Modifier.size(34.dp)) {
                            if (bitmap != null) {
                                Image(bitmap = bitmap.asImageBitmap(), contentDescription = action.title ?: "ส่วนเสริม", modifier = Modifier.size(20.dp))
                            } else {
                                Icon(Icons.Default.Extension, contentDescription = action.title ?: "ส่วนเสริม", tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            IconButton(onClick = onTabs, modifier = Modifier.size(38.dp)) { Icon(Icons.Default.Layers, contentDescription = "Tabs", tint = Color.White) }
            IconButton(onClick = onMenu, modifier = Modifier.size(38.dp)) { Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Color.White) }
        }
    }
}

/** Small floating panel that renders a WebExtension's popup (e.g. tapping the toolbar icon). */
@Composable
fun ExtensionPopup(session: GeckoSession, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.width(320.dp).height(420.dp),
        color = Color(0xFF1B2028),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 12.dp,
        shadowElevation = 12.dp
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "ปิด", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    GeckoView(ctx).apply { setSession(session) }
                }
            )
        }
    }
}

@Composable
fun MenuDrawer(isDesktop: Boolean, onHome: () -> Unit, onAddTab: () -> Unit, onAddIncognitoTab: () -> Unit, onAddBookmark: () -> Unit, onOpenHistoryBookmarks: () -> Unit, onToggleDesktop: () -> Unit, onSettings: () -> Unit, onExtensions: () -> Unit, onClearData: () -> Unit, onExit: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.width(220.dp), color = Color(0xFF252525), shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text("🏠 หน้าแรก", Modifier.clickable(onClick = onHome).fillMaxWidth().padding(10.dp), color = Color.White)
            Text("➕ แท็บใหม่", Modifier.clickable(onClick = onAddTab).fillMaxWidth().padding(10.dp), color = Color.White)
            Text("🕶️ แท็บไม่ระบุตัวตน", Modifier.clickable(onClick = onAddIncognitoTab).fillMaxWidth().padding(10.dp), color = Color.White)
            Text("⭐ เพิ่มลงบุ๊กมาร์ก", Modifier.clickable(onClick = onAddBookmark).fillMaxWidth().padding(10.dp), color = Color.White)
            Text("📜 ประวัติ & บุ๊กมาร์ก", Modifier.clickable(onClick = onOpenHistoryBookmarks).fillMaxWidth().padding(10.dp), color = Color.White)
            Text(if (isDesktop) "📱 มุมมองมือถือ" else "💻 มุมมองคอมพิวเตอร์", Modifier.clickable(onClick = onToggleDesktop).fillMaxWidth().padding(10.dp), color = Color.White)
            Text("🧩 ส่วนเสริม Firefox", Modifier.clickable(onClick = onExtensions).fillMaxWidth().padding(10.dp), color = Color.White)
            Text("⚙️ ตั้งค่า", Modifier.clickable(onClick = onSettings).fillMaxWidth().padding(10.dp), color = Color.White)
            Text("🧹 ล้างแคช", Modifier.clickable(onClick = onClearData).fillMaxWidth().padding(10.dp), color = Color.White)
            Text("🚪 ออกจากแอป", Modifier.clickable(onClick = onExit).fillMaxWidth().padding(10.dp), color = Color.White)
        }
    }
}
@Composable
fun SettingsDialog(currentEngine: SearchEngine, onSelectEngine: (SearchEngine) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("⚙️ ตั้งค่า") }, text = {
        Column {
            Text("เครื่องมือค้นหาเริ่มต้น", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            SearchEngine.values().forEach { engine ->
                Row(modifier = Modifier.fillMaxWidth().clickable { onSelectEngine(engine) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = engine == currentEngine, onClick = { onSelectEngine(engine) })
                    Spacer(Modifier.width(8.dp))
                    Text(engine.label)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("ปิด") } })
}
@Composable
fun DataManagementDialog(dbHelper: NativeBrowserDb, onSelectUrl: (String) -> Unit, onDismiss: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var historyList by remember { mutableStateOf(dbHelper.getAllHistory()) }
    var bookmarkList by remember { mutableStateOf(dbHelper.getAllBookmarks()) }
    var showClearConfirm by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) { Text("📜 ประวัติ", modifier = Modifier.padding(12.dp)) }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) { Text("⭐ บุ๊กมาร์ก", modifier = Modifier.padding(12.dp)) }
            }
        },
        text = {
            Box(modifier = Modifier.height(350.dp).fillMaxWidth()) {
                if (selectedTab == 0) {
                    if (historyList.isEmpty()) Text("ไม่มีประวัติการท่องเว็บ", modifier = Modifier.align(Alignment.Center), color = Color.Gray)
                    else LazyColumn { items(historyList.size) { i -> val item = historyList[i]; val faviconBitmap = remember(item.favicon) { base64ToBitmap(item.favicon) }
                        Row(modifier = Modifier.fillMaxWidth().clickable { onSelectUrl(item.url); onDismiss() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (faviconBitmap != null) { Image(bitmap = faviconBitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp))); Spacer(Modifier.width(8.dp)) }
                            Column(modifier = Modifier.weight(1f)) { Text(item.title, maxLines = 1, color = Color.White, fontSize = 14.sp); Text(item.url, maxLines = 1, color = Color.Gray, fontSize = 11.sp) }
                            IconButton(onClick = { dbHelper.deleteHistoryById(item.id); historyList = dbHelper.getAllHistory() }) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray, modifier = Modifier.size(18.dp)) }
                        }
                        HorizontalDivider(color = Color(0xFF333333)) } }
                } else {
                    if (bookmarkList.isEmpty()) Text("ยังไม่มีบุ๊กมาร์ก", modifier = Modifier.align(Alignment.Center), color = Color.Gray)
                    else LazyColumn { items(bookmarkList.size) { i -> val item = bookmarkList[i]; val faviconBitmap = remember(item.favicon) { base64ToBitmap(item.favicon) }
                        Row(modifier = Modifier.fillMaxWidth().clickable { onSelectUrl(item.url); onDismiss() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (faviconBitmap != null) { Image(bitmap = faviconBitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(20.dp).clip(RoundedCornerShape(4.dp))); Spacer(Modifier.width(8.dp)) }
                            Column(modifier = Modifier.weight(1f)) { Text(item.title, maxLines = 1, color = Color.White, fontSize = 14.sp); Text(item.url, maxLines = 1, color = Color.Gray, fontSize = 11.sp) }
                            IconButton(onClick = { dbHelper.deleteBookmarkByUrl(item.url); bookmarkList = dbHelper.getAllBookmarks() }) { Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color.Gray, modifier = Modifier.size(18.dp)) }
                        }
                        HorizontalDivider(color = Color(0xFF333333)) } }
                }

                if (showClearConfirm) {
                    AlertDialog(onDismissRequest = { showClearConfirm = false }, title = { Text("ล้างประวัติทั้งหมด?") }, text = { Text("การกระทำนี้ไม่สามารถย้อนกลับได้") },
                        confirmButton = { TextButton(onClick = { dbHelper.clearAllHistory(); historyList = emptyList(); showClearConfirm = false }) { Text("ยืนยันลบ", color = Color.Red) } },
                        dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("ยกเลิก") } }
                    )
                }
            }
        },
        confirmButton = { if (selectedTab == 0 && historyList.isNotEmpty()) { TextButton(onClick = { showClearConfirm = true }) { Text("🧹 ล้างประวัติทั้งหมด", color = Color.Red) } } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ปิด") } }
    )
}
@Composable
fun ContextMenuSheet(data: ContextMenuData, onOpenInNewTab: (String) -> Unit, onCopyLink: (String) -> Unit, onDownload: (String) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val isLink = data.type == ContextMenuType.LINK || data.type == ContextMenuType.IMAGE_LINK
    val isImage = data.type == ContextMenuType.IMAGE || data.type == ContextMenuType.IMAGE_LINK
    Surface(modifier = modifier.fillMaxWidth().padding(8.dp), color = Color(0xFF252525), shape = MaterialTheme.shapes.large, tonalElevation = 8.dp) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = data.url, color = Color.Gray, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(bottom = 12.dp))
            if (isLink) { Text("🔗 เปิดในแท็บใหม่", Modifier.clickable { onOpenInNewTab(data.url); onDismiss() }.fillMaxWidth().padding(12.dp), color = Color.White); Text("📋 คัดลอกลิงก์", Modifier.clickable { onCopyLink(data.url); onDismiss() }.fillMaxWidth().padding(12.dp), color = Color.White) }
            if (isImage) { Text("🖼️ เปิดรูปในแท็บใหม่", Modifier.clickable { onOpenInNewTab(data.url); onDismiss() }.fillMaxWidth().padding(12.dp), color = Color.White); Text("📥 ดาวน์โหลดรูปภาพ", Modifier.clickable { onDownload(data.url); onDismiss() }.fillMaxWidth().padding(12.dp), color = Color.White); if (!isLink) Text("📋 คัดลอกลิงก์รูปภาพ", Modifier.clickable { onCopyLink(data.url); onDismiss() }.fillMaxWidth().padding(12.dp), color = Color.White) }
            Spacer(Modifier.height(8.dp)); Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) { Text("ยกเลิก") }
        }
    }
}

@Composable
fun FullScreenTabSwitcher(tabs: List<TabState>, currentTab: Int, onSelect: (Int) -> Unit, onClose: (Int) -> Unit, onAddTab: () -> Unit, onAddIncognitoTab: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (tabs.isEmpty()) return
    val safeInitial = currentTab.coerceIn(0, tabs.size - 1)
    val switcherPagerState = rememberPagerState(initialPage = safeInitial, pageCount = { tabs.size })

    Surface(modifier = modifier, color = Color(0xFF0A0A0A), shape = RoundedCornerShape(20.dp), tonalElevation = 12.dp) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("แท็บทั้งหมด (${tabs.size})", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "ปิด", tint = Color.White) }
            }
            Text("ปัดซ้าย/ขวาเพื่อดูแท็บ · แตะเพื่อเปิด", color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            HorizontalPager(state = switcherPagerState, modifier = Modifier.weight(1f).fillMaxWidth(), pageSpacing = 12.dp, contentPadding = PaddingValues(horizontal = 28.dp)) { page ->
                val tab = tabs.getOrNull(page)
                if (tab != null) TabPreviewCard(tab = tab, isActive = page == currentTab, modifier = Modifier.fillMaxSize(), onClick = { onSelect(page) }, onClose = { onClose(page) })
            }
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center) {
                tabs.indices.forEach { i ->
                    val isCurrentPage = i == switcherPagerState.currentPage
                    Box(modifier = Modifier.padding(horizontal = 3.dp).size(if (isCurrentPage) 8.dp else 6.dp).background(color = if (isCurrentPage) Color.White else Color(0xFF444444), shape = androidx.compose.foundation.shape.CircleShape))
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onAddTab, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("แท็บใหม่", fontSize = 13.sp) }
                OutlinedButton(onClick = onAddIncognitoTab, modifier = Modifier.weight(1f)) { Icon(Icons.Default.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("ไม่ระบุตัวตน", fontSize = 13.sp) }
            }
        }
    }
}

@Composable
fun TabPreviewCard(tab: TabState, isActive: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit, onClose: () -> Unit) {
    Surface(modifier = modifier.clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick), color = Color(0xFF1B1B1B), border = if (isActive) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null, shape = RoundedCornerShape(24.dp)) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF262626)), contentAlignment = Alignment.Center) {
                val favicon = tab.favicon
                if (favicon != null) Image(bitmap = favicon.asImageBitmap(), contentDescription = null, modifier = Modifier.size(64.dp))
                else Icon(if (tab.url == INTERNAL_HOME_URL) Icons.Default.Home else Icons.Default.Language, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(64.dp))
            }
            Box(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Brush.verticalGradient(colors = listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    if (tab.isIncognito) { Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
                    Text(text = tab.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, contentDescription = "ปิดแท็บ", tint = Color.White, modifier = Modifier.size(16.dp)) }
                }
            }
            Box(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Brush.verticalGradient(colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)))).padding(12.dp)) {
                Text(text = if (tab.url == INTERNAL_HOME_URL) "หน้าแรก" else tab.url, color = Color.LightGray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
