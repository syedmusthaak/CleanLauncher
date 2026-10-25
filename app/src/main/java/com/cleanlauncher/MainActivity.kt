package com.cleanlauncher

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

data class AppItem(val label: String, val pkg: String, val intent: Intent, val icon: ImageBitmap)
data class InputItem(val id: String, val label: String)

class MainActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences
    private var refresh by mutableIntStateOf(0)
    private var pendingBootInput by mutableStateOf<String?>(null)
    private var hidden by mutableStateOf(emptySet<String>())
    private var bootInput by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher", Context.MODE_PRIVATE)
        hidden = prefs.getStringSet("hidden", emptySet())!!.toSet()
        bootInput = prefs.getString("bootInput", null)
        // Fresh boot detection: BOOT_COUNT changes on every reboot.
        val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (boot != prefs.getInt("lastBoot", -2)) {
            prefs.edit().putInt("lastBoot", boot).apply()
            pendingBootInput = bootInput
        }
        setContent { Home() }
    }

    override fun onResume() { super.onResume(); refresh++ }

    @Deprecated("Home key should do nothing special")
    override fun onBackPressed() { /* launcher: ignore Back */ }

    private fun loadApps(): List<AppItem> {
        val pm = packageManager
        val result = LinkedHashMap<String, AppItem>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(q, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || result.containsKey(pkg)) continue
                val launch = pm.getLaunchIntentForPackage(pkg) ?: continue
                val d = ri.activityInfo.loadBanner(pm) ?: ri.loadIcon(pm)
                result[pkg] = AppItem(ri.loadLabel(pm).toString(), pkg, launch, d.toBitmap(320, 180).asImageBitmap())
            }
        }
        return result.values.sortedBy { it.label.lowercase() }
    }

    private fun loadInputs(): List<InputItem> {
        val m = getSystemService(Context.TV_INPUT_SERVICE) as TvInputManager
        return m.tvInputList.filter { it.isPassthroughInput }.map {
            InputItem(it.id, (it.loadCustomLabel(this) ?: it.loadLabel(this)).toString())
        }
    }

    private fun launchInput(id: String) {
        pendingBootInput = null
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id)))
        }
    }

    private fun toggleBootInput(id: String) {
        bootInput = if (bootInput == id) null else id
        prefs.edit().putString("bootInput", bootInput).apply()
    }

    private fun toggleHidden(pkg: String) {
        hidden = hidden + pkg
        prefs.edit().putStringSet("hidden", hidden).apply()
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Home() {
        val apps = remember(refresh) { loadApps() }
        val inputs = remember(refresh) { loadInputs() }
        var time by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            val f = SimpleDateFormat("h:mm a", Locale.getDefault())
            while (true) { time = f.format(Date()); delay(15_000) }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0xFF1B1F2A), Color(0xFF05060A)))
            )
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Home", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text(time, color = Color(0xFFB0B4C0), fontSize = 24.sp)
                }
                Spacer(Modifier.height(28.dp))
                Section("Apps  ·  hold OK to hide")
                LazyRow(contentPadding = PaddingValues(vertical = 16.dp)) {
                    items(apps.filter { it.pkg !in hidden }, key = { it.pkg }) { app ->
                        Tile(app.label, app.icon, null,
                            onClick = { startActivity(app.intent) },
                            onLong = { toggleHidden(app.pkg) })
                    }
                    if (hidden.isNotEmpty()) item {
                        Tile("Show ${hidden.size} hidden", null, null,
                            onClick = { hidden = emptySet(); prefs.edit().remove("hidden").apply() },
                            onLong = {})
                    }
                }
                Spacer(Modifier.height(20.dp))
                Section("Inputs  ·  hold OK to auto-start on boot")
                LazyRow(contentPadding = PaddingValues(vertical = 16.dp)) {
                    if (inputs.isEmpty()) item {
                        Text("No HDMI/AV inputs reported by this TV", color = Color(0xFF8A8F9C), fontSize = 18.sp)
                    }
                    items(inputs, key = { it.id }) { inp ->
                        Tile(inp.label, null, if (bootInput == inp.id) "⏻ BOOT" else null,
                            onClick = { launchInput(inp.id) },
                            onLong = { toggleBootInput(inp.id) })
                    }
                }
            }
            pendingBootInput?.let { id ->
                val name = inputs.firstOrNull { it.id == id }?.label ?: "input"
                BootOverlay(name, onGo = { launchInput(id) }, onCancel = { pendingBootInput = null })
            }
        }
    }

    @Composable
    private fun Section(title: String) =
        Text(title, color = Color(0xFFB0B4C0), fontSize = 20.sp, fontWeight = FontWeight.Medium)

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Tile(label: String, image: ImageBitmap?, badge: String?, onClick: () -> Unit, onLong: () -> Unit) {
        var focused by remember { mutableStateOf(false) }
        val scale by animateFloatAsState(if (focused) 1.12f else 1f, label = "scale")
        Column(Modifier.padding(end = 20.dp).width(220.dp)) {
            Box(
                Modifier.scale(scale)
                    .height(124.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF2A2F3D))
                    .border(if (focused) 3.dp else 0.dp, Color.White, RoundedCornerShape(14.dp))
                    .onFocusChanged { focused = it.isFocused }
                    .combinedClickable(onClick = onClick, onLongClick = onLong),
                contentAlignment = Alignment.Center
            ) {
                if (image != null) Image(image, label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text(label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                if (badge != null) Text(
                    badge, color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                        .background(Color(0xFFFFD60A), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(label, color = if (focused) Color.White else Color(0xFF8A8F9C), fontSize = 16.sp, maxLines = 1)
        }
    }

    @Composable
    private fun BootOverlay(name: String, onGo: () -> Unit, onCancel: () -> Unit) {
        var left by remember { mutableIntStateOf(5) }
        val fr = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            fr.requestFocus()
            while (left > 0) { delay(1000); left-- }
            onGo()
        }
        Box(
            Modifier.fillMaxSize().background(Color(0xF2000000))
                .focusRequester(fr).focusable()
                .onKeyEvent { if (it.type == KeyEventType.KeyDown) { onCancel(); true } else false },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Starting $name in $left", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text("Press any button to cancel", color = Color(0xFFB0B4C0), fontSize = 20.sp)
            }
        }
    }
}
