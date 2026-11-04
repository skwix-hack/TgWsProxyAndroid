package dev.skwix.tgws

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.imageLoader
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import coil.request.repeatCount
import java.security.SecureRandom

class MainActivity : ComponentActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (!prefs.contains("secret")) {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            prefs.edit().putString("secret", bytes.joinToString("") { "%02x".format(it) }).apply()
        }
        setContent { App() }
    }
    fun startProxy() {
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        ContextCompat.startForegroundService(this, Intent(this, ProxyService::class.java))
    }
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFFCBB5FF), onPrimary = Color(0xFF29213D),
    background = Color(0xFF171724), surface = Color(0xFF171724),
    surfaceVariant = Color(0xFF29283D), onSurface = Color(0xFFF0E9FF),
    onSurfaceVariant = Color(0xFFBDB5CF), secondaryContainer = Color(0xFF39304F),
    onSecondaryContainer = Color(0xFFEADDFF), outline = Color(0xFF857C97))
private val LightColors = lightColorScheme(primary=Color(0xFF70539C), background=Color(0xFFFFF8FF), surface=Color(0xFFFFF8FF), surfaceVariant=Color(0xFFECE4F4))

@Composable
fun App() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", 0) }
    var theme by remember { mutableStateOf(prefs.getString("theme", "Тёмная")!!) }
    var animations by remember { mutableStateOf(prefs.getBoolean("animations", true)) }
    var reconnect by remember { mutableStateOf(prefs.getBoolean("reconnect", true)) }
    var verbose by remember { mutableStateOf(prefs.getBoolean("verbose", false)) }
    var port by remember { mutableIntStateOf(prefs.getInt("port", 1443)) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val status by ProxyState.status.collectAsStateWithLifecycle()
    val error by ProxyState.error.collectAsStateWithLifecycle()
    val active = status == "running" || status == "starting" || status == "stopping"
    val dark = theme == "Тёмная" || theme == "Системная" && isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
        Scaffold(bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                listOf("Главная" to Icons.Outlined.Home, "Настройки" to Icons.Outlined.Settings, "Журнал" to Icons.Outlined.Description).forEachIndexed { index, item ->
                    NavigationBarItem(selected = page == index, onClick = { page = index }, icon = { Icon(item.second, item.first) }, label = { Text(item.first) }, colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primary, selectedIconColor = MaterialTheme.colorScheme.onPrimary))
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Text("TG WS Proxy", Modifier.padding(horizontal=24.dp, vertical=18.dp), fontSize=20.sp, fontWeight=FontWeight.Bold)
                AnimatedContent(targetState=page, transitionSpec={ (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 12 }).togetherWith(fadeOut(tween(140))) }, label="navigation") { selected ->
                    when(selected) {
                        0 -> Home(status, error, port, animations, onToggle = {
                            if (status == "running") context.startService(Intent(context, ProxyService::class.java).setAction("STOP"))
                            else (context as MainActivity).startProxy()
                        }, onTelegram = {
                            openLink(context, "tg://proxy?server=127.0.0.1&port=$port&secret=dd${prefs.getString("secret", "")}")
                        })
                        1 -> SettingsPage(port, active, animations, verbose, reconnect, theme,
                            onReconnect = { reconnect=it; prefs.edit().putBoolean("reconnect", it).apply() },
                            onPort = { port=it; prefs.edit().putInt("port", it).apply() },
                            onAnimations = { animations=it; prefs.edit().putBoolean("animations", it).apply() },
                            onVerbose = { verbose=it; prefs.edit().putBoolean("verbose", it).apply() },
                            onTheme = { theme=it; prefs.edit().putString("theme", it).apply() }, onLogs = { page=2 })
                        else -> {
                            val logs by ProxyState.logs.collectAsStateWithLifecycle()
                            DisposableEffect(Unit) {
                                ProxyState.readLogs = true
                                onDispose { ProxyState.readLogs = false }
                            }
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                            Text("Журнал", fontSize=32.sp, fontWeight=FontWeight.Bold)
                            Spacer(Modifier.height(16.dp))
                            Text(if(logs.isBlank()) "Здесь появятся события после запуска прокси." else logs, fontSize=12.sp, color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Duck(name: String, animate: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val loader = context.imageLoader
    val request = remember(name, animate) { ImageRequest.Builder(context).data("file:///android_asset/$name.${if (!animate) "png" else if (Build.VERSION.SDK_INT >= 28) "webp" else "gif"}").repeatCount(-1).build() }
    AsyncImage(model=request, imageLoader=loader, contentDescription="Утка Utya", modifier=modifier)
}

@Composable
fun Home(status: String, error: String, port: Int, animations: Boolean, onToggle: () -> Unit, onTelegram: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val buttonScale by animateFloatAsState(if (pressed) .95f else 1f, spring(dampingRatio=.65f, stiffness=400f), label="power press")
    val running = status == "running"
    val busy = status == "starting" || status == "stopping"
    val title = when(status) { "running" -> "Прокси запущен"; "starting" -> "Запускаем…"; "stopping" -> "Останавливаем…"; "error" -> "Не удалось запустить"; else -> "Готов к запуску" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=24.dp), horizontalAlignment=Alignment.CenterHorizontally) {
        Text(title, Modifier.fillMaxWidth().padding(top=14.dp), fontSize=34.sp, lineHeight=39.sp, fontWeight=FontWeight.Bold)
        Text(if(running) "Добавьте прокси в Telegram и оставайтесь на связи" else "Подключите Telegram через локальный прокси", Modifier.fillMaxWidth().padding(top=12.dp), color=MaterialTheme.colorScheme.onSurfaceVariant, fontSize=16.sp)
        Spacer(Modifier.height(36.dp))
        Box(Modifier.size(270.dp), contentAlignment=Alignment.Center) {
            FilledIconButton(onClick=onToggle, enabled=!busy, modifier=Modifier.size(244.dp).graphicsLayer { scaleX=buttonScale; scaleY=buttonScale }, shape=CircleShape, interactionSource=interaction) {
                if(busy) CircularProgressIndicator(Modifier.size(62.dp), color=MaterialTheme.colorScheme.onPrimary)
                else Icon(if(running) Icons.Outlined.Stop else Icons.Outlined.PowerSettingsNew, if(running) "Остановить прокси" else "Запустить прокси", Modifier.size(100.dp))
            }
            Duck("utya-plane", animations, Modifier.size(104.dp).align(Alignment.BottomEnd))
        }
        Spacer(Modifier.height(20.dp))
        Text(if(running) "Остановить прокси" else if(busy) "Подождите немного" else "Запустить прокси", fontSize=22.sp, fontWeight=FontWeight.Bold)
        Spacer(Modifier.height(28.dp))
        Surface(shape=RoundedCornerShape(24.dp), color=MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.SpaceBetween) {
                Icon(Icons.Outlined.Layers, null)
                Text("MTProto", fontWeight=FontWeight.Medium)
                Text("127.0.0.1:$port", fontSize=14.sp)
            }
        }
        AnimatedVisibility(running) { Button(onClick=onTelegram, modifier=Modifier.fillMaxWidth().padding(top=20.dp).height(54.dp)) { Icon(Icons.Outlined.OpenInNew,null); Spacer(Modifier.width(10.dp)); Text("Открыть Telegram") } }
        if(error.isNotBlank()) Text(error, Modifier.padding(top=18.dp), color=MaterialTheme.colorScheme.error)
        Text(if(running) "Прокси работает, пока активно уведомление" else "После запуска добавьте прокси в Telegram", Modifier.padding(vertical=24.dp), color=MaterialTheme.colorScheme.onSurfaceVariant, fontSize=14.sp)
    }
}

@Composable
fun SettingsPage(port: Int, active: Boolean, animations: Boolean, verbose: Boolean, reconnect: Boolean, theme: String, onReconnect:(Boolean)->Unit, onPort:(Int)->Unit, onAnimations:(Boolean)->Unit, onVerbose:(Boolean)->Unit, onTheme:(String)->Unit, onLogs:()->Unit) {
    val context=LocalContext.current
    var editPort by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf(port.toString()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.SpaceBetween) {
            Text("Настройки", fontSize=32.sp, fontWeight=FontWeight.Bold)
            Duck("utya-map", animations, Modifier.size(72.dp))
        }
        Section("Подключение")
        SettingRow(Icons.Outlined.Dns, "Порт прокси", if(active) "Для изменения остановите прокси" else "Локальный адрес · 127.0.0.1", { if(!active) {input=port.toString();editPort=true} }) { Text(port.toString()); Icon(Icons.Outlined.ChevronRight,null) }
        RoutingSetting(active)
        Section("Оформление")
        Text("Тема приложения", Modifier.padding(vertical=12.dp), fontSize=17.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("Системная", "Светлая", "Тёмная").forEach { value ->
                FilterChip(selected=theme==value, onClick={onTheme(value)}, label={Text(value, fontSize=12.sp)}, modifier=Modifier.weight(1f))
            }
        }
        SettingRow(Icons.Outlined.AutoAwesome, "Анимации утки", "На главной и в настройках", {onAnimations(!animations)}) { Switch(checked=animations, onCheckedChange=onAnimations) }
        Section("Диагностика")
        SettingRow(Icons.Outlined.Description, "Подробный журнал", "Применится при следующем запуске", {onVerbose(!verbose)}) { Switch(checked=verbose, onCheckedChange=onVerbose) }
        SettingRow(Icons.Outlined.ReceiptLong, "Открыть журнал", "События подключения", onLogs) { Icon(Icons.Outlined.ChevronRight,null) }
        Section("О приложении")
        SettingRow(Icons.Outlined.Code, "Оригинальный TG WS Proxy", "Flowseal · GitHub", {openLink(context,"https://github.com/Flowseal/tg-ws-proxy")}) { Icon(Icons.Outlined.OpenInNew,null) }
        SettingRow(Icons.Outlined.EmojiEmotions, "Стикеры Utya Duck", "Открыть набор в Telegram", {openLink(context,"https://t.me/addstickers/UtyaDuck")}) { Icon(Icons.Outlined.OpenInNew,null) }
        Text("by skwix", Modifier.align(Alignment.CenterHorizontally).padding(top=24.dp), color=MaterialTheme.colorScheme.primary, fontWeight=FontWeight.SemiBold)
        Text("TG WS Proxy для Android · 0.2.0", Modifier.align(Alignment.CenterHorizontally).padding(top=8.dp,bottom=28.dp), color=MaterialTheme.colorScheme.onSurfaceVariant, fontSize=12.sp)
    }
    if(editPort) AlertDialog(onDismissRequest={editPort=false}, title={Text("Порт прокси")}, text={OutlinedTextField(value=input,onValueChange={input=it.filter(Char::isDigit).take(5)}, label={Text("1024–65535")}, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number), isError=input.toIntOrNull() !in 1024..65535)}, confirmButton={TextButton(enabled=input.toIntOrNull() in 1024..65535,onClick={onPort(input.toInt());editPort=false}){Text("Сохранить")}}, dismissButton={TextButton(onClick={editPort=false}){Text("Отмена")}})
}

@Composable
fun Section(title: String) {
    Text(title, Modifier.padding(top=24.dp,bottom=10.dp), color=MaterialTheme.colorScheme.primary, fontSize=16.sp,fontWeight=FontWeight.SemiBold)
    HorizontalDivider(color=MaterialTheme.colorScheme.outline.copy(alpha=.25f))
}

@Composable
fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick:()->Unit, trailing:@Composable RowScope.()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=16.dp), verticalAlignment=Alignment.CenterVertically) {
        Icon(icon,null,Modifier.size(24.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(start=16.dp,end=8.dp)) {
            Text(title,fontSize=16.sp)
            Text(subtitle,fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

fun openLink(context: android.content.Context, url: String) {
    try { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }
    catch(e: android.content.ActivityNotFoundException) { Toast.makeText(context,"Приложение для открытия ссылки не найдено",Toast.LENGTH_LONG).show() }
}


@Composable
fun RoutingSetting(active: Boolean) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", 0) }
    val options = listOf(
        "dc4" to "Медиа · DC4",
        "direct" to "Без перенаправлений",
        "original" to "Исходный · DC2 и DC4"
    )
    var selected by remember { mutableStateOf(prefs.getString("routing", "dc4")!!) }
    var open by remember { mutableStateOf(false) }
    SettingRow(Icons.Outlined.Route, "Маршрут загрузки файлов",
        if (active) "Сначала остановите прокси" else options.first { it.first == selected }.second,
        { if (!active) open = true }) { Icon(Icons.Outlined.ChevronRight, null) }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Маршрут медиа") },
        text = {
            Column {
                Text("Если DC4 не помогает, попробуйте режим без перенаправлений.", fontSize = 14.sp)
                options.forEach { (key, title) ->
                    Row(Modifier.fillMaxWidth().clickable {
                        selected = key
                        prefs.edit().putString("routing", key).apply()
                        open = false
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == key, onClick = null)
                        Text(title, Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Закрыть") } }
    )
}
