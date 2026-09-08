package com.mybrowser.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mozilla.geckoview.WebExtension

private data class FirefoxExtension(
    val name: String,
    val description: String,
    val url: String
)

private val recommendedFirefoxExtensions = listOf(
    FirefoxExtension(
        "uBlock Origin",
        "บล็อกโฆษณา ตัวติดตาม และสคริปต์ที่ไม่ต้องการ",
        "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
    ),
    FirefoxExtension(
        "Dark Reader",
        "ทำเว็บไซต์ให้เป็นโหมดมืด อ่านสบายตา",
        "https://addons.mozilla.org/firefox/downloads/latest/darkreader/latest.xpi"
    ),
    FirefoxExtension(
        "Decentraleyes",
        "ช่วยลดการพึ่งพา CDN ภายนอกเพื่อความเป็นส่วนตัว",
        "https://addons.mozilla.org/firefox/downloads/latest/decentraleyes/latest.xpi"
    )
)

@Composable
fun ExtensionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var installed by remember { mutableStateOf<List<WebExtension>>(emptyList()) }
    var installing by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        GeckoEngine.listExtensions(context) { installed = it }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1014))
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF17191F),
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "ย้อนกลับ", tint = Color.White)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("ส่วนเสริม Firefox", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Firefox Add-ons สำหรับ GeckoView", color = Color(0xFF9DA3AE), fontSize = 12.sp)
                }
                IconButton(onClick = { refresh() }) {
                    Icon(Icons.Default.Refresh, "รีเฟรช", tint = Color.White)
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2028)),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Extension, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.size(10.dp))
                            Text("Firefox Add-ons", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "ติดตั้งส่วนเสริมที่ลงนามโดย Mozilla ได้โดยตรง ไม่ต้องฝังไฟล์ .xpi ไว้ใน APK",
                            color = Color(0xFFB5BBC5), fontSize = 13.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://addons.mozilla.org/android/"))
                                )
                            }
                        ) {
                            Icon(Icons.Default.OpenInBrowser, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("เปิด Mozilla Add-ons")
                        }
                    }
                }
            }

            item {
                Text("แนะนำ", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            items(recommendedFirefoxExtensions) { ext ->
                ExtensionCard(
                    extension = ext,
                    installed = installed.any { it.id.equals(ext.name, ignoreCase = true) || it.metaData?.name?.equals(ext.name, ignoreCase = true) == true },
                    loading = installing == ext.name,
                    onInstall = {
                        installing = ext.name
                        GeckoEngine.installExtension(
                            context,
                            ext.url,
                            onSuccess = {
                                installing = null
                                refresh()
                                Toast.makeText(context, "ติดตั้ง ${ext.name} แล้ว", Toast.LENGTH_SHORT).show()
                            },
                            onError = {
                                installing = null
                                Toast.makeText(context, "ติดตั้งไม่สำเร็จ: ${it.message ?: "ตรวจสอบอินเทอร์เน็ต"}", Toast.LENGTH_LONG).show()
                            }
                        )
                    }
                )
            }

            item {
                Text("ติดตั้งแล้ว", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            }

            if (installed.isEmpty()) {
                item {
                    Text("ยังไม่มีส่วนเสริมที่ติดตั้ง", color = Color(0xFF8F96A3), fontSize = 13.sp)
                }
            } else {
                items(installed) { extension ->
                    InstalledExtensionCard(
                        extension = extension,
                        onUninstall = {
                            GeckoEngine.uninstallExtension(context, extension) {
                                refresh()
                                Toast.makeText(context, "ถอนการติดตั้งแล้ว", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtensionCard(
    extension: FirefoxExtension,
    installed: Boolean,
    loading: Boolean,
    onInstall: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF171A20)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).background(Color(0xFF252B34), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(extension.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(3.dp))
                Text(extension.description, color = Color(0xFF9DA3AE), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.size(8.dp))
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
            } else {
                Button(
                    onClick = onInstall,
                    enabled = !installed,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(if (installed) "ติดตั้งแล้ว" else "ติดตั้ง")
                }
            }
        }
    }
}

@Composable
private fun InstalledExtensionCard(
    extension: WebExtension,
    onUninstall: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF171A20)), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Extension, null, tint = Color(0xFF8E95A3), modifier = Modifier.size(28.dp))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(extension.metaData?.name ?: extension.id, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(extension.id, color = Color(0xFF858C98), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onUninstall) {
                Icon(Icons.Default.DeleteOutline, "ถอนการติดตั้ง", tint = Color(0xFFE57373))
            }
        }
    }
}
