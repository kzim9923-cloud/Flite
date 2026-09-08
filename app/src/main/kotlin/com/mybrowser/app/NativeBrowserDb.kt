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

// Native SQLite Database Helper
class NativeBrowserDb(context: Context) : SQLiteOpenHelper(context, "browser_native.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS history (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT, title TEXT, timestamp INTEGER, favicon TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT, title TEXT, timestamp INTEGER, favicon TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS tabs (id TEXT PRIMARY KEY, url TEXT, title TEXT, is_desktop INTEGER, position INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_url ON history(url)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_timestamp ON history(timestamp)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmarks_url ON bookmarks(url)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { onCreate(db) }

    fun insertHistory(url: String, title: String, favicon: String? = null) {
        if (url == INTERNAL_HOME_URL || url.isBlank()) return
        try {
            val values = ContentValues().apply { put("url", url); put("title", title); put("timestamp", System.currentTimeMillis()); put("favicon", favicon) }
            writableDatabase.insert("history", null, values)
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun updateHistoryFavicon(url: String, favicon: String) {
        try {
            val values = ContentValues().apply { put("favicon", favicon) }
            writableDatabase.update("history", values, "url=?", arrayOf(url))
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun getAllHistory(): List<HistoryItem> {
        val list = mutableListOf<HistoryItem>()
        try {
            readableDatabase.rawQuery("SELECT id, url, title, timestamp, favicon FROM history ORDER BY timestamp DESC LIMIT 200", null).use { cursor ->
                while (cursor.moveToNext()) list.add(HistoryItem(cursor.getLong(0), cursor.getString(1) ?: "", cursor.getString(2) ?: "", cursor.getLong(3), cursor.getString(4)))
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    private fun escapeLike(input: String): String = input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    fun searchHistoryAndBookmarks(query: String, limit: Int = 6): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        if (query.isBlank()) return results
        try {
            val like = "%${escapeLike(query.trim())}%"
            readableDatabase.rawQuery(
                "SELECT title, url FROM (SELECT title, url, timestamp FROM bookmarks WHERE url LIKE ? ESCAPE '\\' OR title LIKE ? ESCAPE '\\' UNION ALL SELECT title, url, timestamp FROM history WHERE url LIKE ? ESCAPE '\\' OR title LIKE ? ESCAPE '\\') ORDER BY timestamp DESC LIMIT ?",
                arrayOf(like, like, like, like, limit.toString())
            ).use { cursor -> while (cursor.moveToNext()) results.add((cursor.getString(0) ?: "") to (cursor.getString(1) ?: "")) }
        } catch (e: Exception) { e.printStackTrace() }
        return results.distinctBy { it.second }
    }

    fun deleteHistoryById(id: Long) { try { writableDatabase.delete("history", "id=?", arrayOf(id.toString())) } catch (e: Exception) {} }
    fun clearAllHistory() { try { writableDatabase.delete("history", null, null) } catch (e: Exception) {} }

    fun insertBookmark(url: String, title: String, favicon: String? = null) {
        if (url == INTERNAL_HOME_URL || url.isBlank()) return
        try {
            if (isBookmarked(url)) return
            val values = ContentValues().apply { put("url", url); put("title", title); put("timestamp", System.currentTimeMillis()); put("favicon", favicon) }
            writableDatabase.insert("bookmarks", null, values)
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun isBookmarked(url: String): Boolean {
        return try { readableDatabase.rawQuery("SELECT 1 FROM bookmarks WHERE url=? LIMIT 1", arrayOf(url)).use { it.moveToFirst() } } catch (e: Exception) { false }
    }

    fun getAllBookmarks(): List<BookmarkItem> {
        val list = mutableListOf<BookmarkItem>()
        try {
            readableDatabase.rawQuery("SELECT id, url, title, timestamp, favicon FROM bookmarks ORDER BY timestamp DESC", null).use { cursor ->
                while (cursor.moveToNext()) list.add(BookmarkItem(cursor.getLong(0), cursor.getString(1) ?: "", cursor.getString(2) ?: "", cursor.getLong(3), cursor.getString(4)))
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    fun deleteBookmarkByUrl(url: String) { try { writableDatabase.delete("bookmarks", "url=?", arrayOf(url)) } catch (e: Exception) {} }

    fun saveTabs(tabs: List<SavedTab>) {
        try {
            writableDatabase.beginTransaction()
            writableDatabase.delete("tabs", null, null)
            tabs.forEach { t ->
                val values = ContentValues().apply { put("id", t.id); put("url", t.url); put("title", t.title); put("is_desktop", if (t.isDesktop) 1 else 0); put("position", t.position) }
                writableDatabase.insert("tabs", null, values)
            }
            writableDatabase.setTransactionSuccessful()
        } catch (e: Exception) { e.printStackTrace() } finally { try { writableDatabase.endTransaction() } catch (e: Exception) {} }
    }

    fun loadTabs(): List<SavedTab> {
        val list = mutableListOf<SavedTab>()
        try {
            readableDatabase.rawQuery("SELECT id, url, title, is_desktop, position FROM tabs ORDER BY position ASC", null).use { cursor ->
                while (cursor.moveToNext()) list.add(SavedTab(cursor.getString(0) ?: "", cursor.getString(1) ?: INTERNAL_HOME_URL, cursor.getString(2) ?: "หน้าแรก", cursor.getInt(3) == 1, cursor.getInt(4)))
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    fun getSetting(key: String, default: String): String { try { readableDatabase.rawQuery("SELECT value FROM settings WHERE key=?", arrayOf(key)).use { cursor -> if (cursor.moveToFirst()) return cursor.getString(0) ?: default } } catch (e: Exception) { e.printStackTrace() }; return default }
    fun setSetting(key: String, value: String) { try { val values = ContentValues().apply { put("key", key); put("value", value) }; writableDatabase.insertWithOnConflict("settings", null, values, SQLiteDatabase.CONFLICT_REPLACE) } catch (e: Exception) { e.printStackTrace() } }
}
