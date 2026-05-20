package com.example.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.db.DbEarthquakeRecord
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.data.db.DbHomeSetting
import com.example.data.model.EarlyWarningEvent
import com.example.viewmodel.EarthquakeViewModel
import kotlin.math.absoluteValue

enum class EarthquakeTab {
    HISTORY,
    SETTINGS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EarthquakeApp(viewModel: EarthquakeViewModel) {
    var currentTab by remember { mutableStateOf(EarthquakeTab.HISTORY) }

    val homeSetting by viewModel.homeSetting.collectAsStateWithLifecycle()
    val earthquakes by viewModel.cachedEarthquakes.collectAsStateWithLifecycle()
    val activeWarning by viewModel.activeWarning.collectAsStateWithLifecycle()
    val countdown by viewModel.sWaveCountdown.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    val cencConnected by viewModel.cencConnected.collectAsStateWithLifecycle()
    val ceaConnected by viewModel.ceaConnected.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var isOverlayGranted by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
    var isNotificationGranted by remember {
        mutableStateOf(
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val dpm = remember { context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager }
    val adminComponent = remember { android.content.ComponentName(context, com.example.receiver.MyDeviceAdminReceiver::class.java) }
    var isAdminActive by remember { mutableStateOf(dpm?.isAdminActive(adminComponent) == true) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isOverlayGranted = android.provider.Settings.canDrawOverlays(context)
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    isNotificationGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                isAdminActive = dpm?.isAdminActive(adminComponent) == true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var showPermissionGuide by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(650)
        if (!isOverlayGranted || !isNotificationGranted || !isAdminActive) {
            showPermissionGuide = true
        }
    }

    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFF111215), Color(0xFF1A1C22), Color(0xFF15161A))
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = "App Logo",
                                tint = Color(0xFFFF453A),
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                text = "地震预警卫士",
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                letterSpacing = 0.5.sp
                            )
                        }
                    },
                    actions = {
                        // Connection logs dropdown indicator or status dots
                        ConnectionStatusDot(name = "台网", connected = cencConnected)
                        Spacer(modifier = Modifier.width(4.dp))
                        ConnectionStatusDot(name = "预警", connected = ceaConnected)
                        Spacer(modifier = Modifier.width(16.dp))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF15161A).copy(alpha = 0.9f),
                        titleContentColor = Color.White
                    )
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = Color(0xFF15161A),
                    tonalElevation = 8.dp
                ) {
                    NavigationBarItem(
                        selected = currentTab == EarthquakeTab.HISTORY,
                        onClick = { currentTab = EarthquakeTab.HISTORY },
                        icon = { Icon(Icons.Filled.List, contentDescription = "History Catalog") },
                        label = { Text("地震速报", fontWeight = FontWeight.Medium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFFFF453A),
                            selectedTextColor = Color(0xFFFF9500),
                            indicatorColor = Color(0xFF2C2E35),
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray
                        )
                    )
                    NavigationBarItem(
                        selected = currentTab == EarthquakeTab.SETTINGS,
                        onClick = { currentTab = EarthquakeTab.SETTINGS },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings Panel") },
                        label = { Text("预警设置", fontWeight = FontWeight.Medium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFFFF453A),
                            selectedTextColor = Color(0xFFFF9500),
                            indicatorColor = Color(0xFF2C2E35),
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray
                        )
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (currentTab) {
                    EarthquakeTab.HISTORY -> {
                        HistoryTabScreen(
                            earthquakes = earthquakes,
                            homeSetting = homeSetting,
                            isRefreshing = isRefreshing,
                            onRefresh = { viewModel.refreshHistory() }
                        )
                    }
                    EarthquakeTab.SETTINGS -> {
                        SettingsTabScreen(
                            viewModel = viewModel,
                            homeSetting = homeSetting
                        )
                    }
                }
            }
        }

        // Spectacular System-Alert Simulation Overlay shown when activeWarning is triggered!
        AnimatedVisibility(
            visible = activeWarning != null,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
        ) {
            activeWarning?.let { event ->
                WarningAlarmScreen(
                    event = event,
                    countdown = countdown,
                    userHome = homeSetting,
                    onDismiss = { viewModel.dismissWarning() }
                )
            }
        }

        // Elegant Popup Dialogue guiding user with required system warnings
        if (showPermissionGuide) {
            AlertDialog(
                onDismissRequest = { showPermissionGuide = false },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = "权限引导",
                            tint = Color(0xFF30D158),
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "核心预警权限开启指引",
                            color = Color.White,
                             fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = "为了确保地震横波紧急时刻能够秒级在您背景强弹预警通知，请检查确认开启以下几项核心系统预警权限：",
                            color = Color.LightGray,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )

                        // Task Card 1: System Overlay
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2027)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    1.dp,
                                    if (isOverlayGranted) Color(0xFF30D158).copy(alpha = 0.5f) else Color(0xFFFF9500).copy(alpha = 0.5f),
                                    RoundedCornerShape(12.dp)
                                )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "1. 悬浮窗 / 显示在其他应用上",
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = if (isOverlayGranted) "已开启" else "去开启",
                                        color = if (isOverlayGranted) Color(0xFF30D158) else Color(0xFFFF9500),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "【核心红闪大窗口】即使应用正处于后台、未处于活跃状态或屏幕完全处于息屏锁定状态，也可以立刻在亮屏瞬间弹出“安全横波倒数红闪壁式警报架”，抢占黄金避险周期！",
                                    color = Color.Gray,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp
                                )
                                if (!isOverlayGranted) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = {
                                            try {
                                                val intent = android.content.Intent(
                                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                    android.net.Uri.parse("package:${context.packageName}")
                                                )
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                try {
                                                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                                    context.startActivity(intent)
                                                } catch (ex: Exception) {
                                                    // ignored
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9500)),
                                        modifier = Modifier
                                            .align(Alignment.End)
                                             .height(36.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                                    ) {
                                        Text("立即去授权", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                    }
                                }
                            }
                        }

                        // Task Card 2: Notification Permission
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            val requestNotificationLauncher = rememberLauncherForActivityResult(
                                contract = ActivityResultContracts.RequestPermission()
                            ) { isGranted ->
                                isNotificationGranted = isGranted
                            }

                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2027)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        1.dp,
                                        if (isNotificationGranted) Color(0xFF30D158).copy(alpha = 0.5f) else Color(0xFFFF453A).copy(alpha = 0.5f),
                                        RoundedCornerShape(12.dp)
                                    )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "2. 系统级实时事件推送通知",
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = if (isNotificationGranted) "已开启" else "去允许",
                                            color = if (isNotificationGranted) Color(0xFF30D158) else Color(0xFFFF453A),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "【状态栏消息警告】当中国地震台网发布微弱无破坏性波或速报更新时，本程序将向您推送消息通知横幅，保护睡眠等场景不被打扰，守护您的知情安全权。",
                                        color = Color.Gray,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp
                                    )
                                    if (!isNotificationGranted) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Button(
                                            onClick = {
                                                requestNotificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                            },
                                             colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF453A)),
                                            modifier = Modifier
                                                .align(Alignment.End)
                                                .height(36.dp),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                                        ) {
                                            Text("允许发送通知", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                         }
                                    }
                                }
                             }
                         }

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2027)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    1.dp,
                                    if (isAdminActive) Color(0xFF30D158).copy(alpha = 0.5f) else Color(0xFFFF9500).copy(alpha = 0.5f),
                                    RoundedCornerShape(12.dp)
                                )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "3. 后台保活高级设备管理器",
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = if (isAdminActive) "已激活" else "去激活",
                                        color = if (isAdminActive) Color(0xFF30D158) else Color(0xFFFF9500),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "【设备后台防强杀守护】激活高级设备管理器权限。系统将为此地震预警防灾服务赋予底层持久运行特权，在全机深度睡眠、亮屏一键加速或锁屏状态下免疫强杀清理，提供全天候的稳定保障。",
                                    color = Color.Gray,
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp
                                )
                                if (!isAdminActive) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = {
                                            try {
                                                val intent = android.content.Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                                    putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                                                    putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "启用后本预警系统将具备超级后台保活能力，在锁屏和后台深睡眠中守护您的避险安全。")
                                                }
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9500)),
                                        modifier = Modifier
                                            .align(Alignment.End)
                                            .height(36.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                                    ) {
                                        Text("激活设备管理器", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                    }
                                }
                            }
                        }
                     }
                 },
                 confirmButton = {
                     TextButton(
                         onClick = { showPermissionGuide = false }
                     ) {
                         Text("我已配置完毕 / 不再显示", color = Color(0xFF30D158), fontWeight = FontWeight.Bold)
                     }
                 },
                 containerColor = Color(0xFF15161A),
                 titleContentColor = Color.White,
                 textContentColor = Color.LightGray,
                 shape = RoundedCornerShape(16.dp)
             )
         }
     }
 }

@Composable
fun ConnectionStatusDot(name: String, connected: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(32.dp))
            .background(Color(0xFF22242B))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (connected) Color(0xFF30D158) else Color(0xFFFF453A))
        )
        Text(
            text = name,
            color = Color.LightGray,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun HistoryTabScreen(
    earthquakes: List<DbEarthquakeRecord>,
    homeSetting: DbHomeSetting,
    isRefreshing: Boolean,
    onRefresh: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Welcome banner showing the core information cards
        HomeLocationHeader(homeSetting = homeSetting)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            if (earthquakes.isEmpty() && isRefreshing) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFFFF9500))
                }
            } else if (earthquakes.isEmpty()) {
                EmptyStateView(onRefresh = onRefresh)
            } else {
                // List of earthquakes
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(earthquakes, key = { it.eventId }) { item ->
                        EarthquakeEventCard(item = item, homeSetting = homeSetting)
                    }
                }

                // Small loading floating pill if refreshing but data exists
                if (isRefreshing) {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2E35).copy(alpha = 0.9f)),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFFFF453A),
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Text(text = "更新目录中...", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HomeLocationHeader(homeSetting: DbHomeSetting) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111216)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(22.dp))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Home,
                    contentDescription = "Home",
                    tint = Color(0xFFFF9500),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "防震守护位置",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = homeSetting.name,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "坐标: ${String.format("%.4f", homeSetting.latitude)}°N, ${String.format("%.4f", homeSetting.longitude)}°E",
                    color = Color.DarkGray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFF3B30).copy(alpha = 0.12f)),
                    modifier = Modifier.border(0.5.dp, Color(0xFFFF3B30).copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                ) {
                    Text(
                        text = "起报烈度 > ${homeSetting.alertThreshold} 度",
                        color = Color(0xFFFF453A),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun EarthquakeEventCard(item: DbEarthquakeRecord, homeSetting: DbHomeSetting) {
    // Math distance & intensity calculation
    val earthRadius = 6371.0
    val dLat = Math.toRadians(item.latitude - homeSetting.latitude)
    val dLon = Math.toRadians(item.longitude - homeSetting.longitude)
    val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(homeSetting.latitude)) * kotlin.math.cos(Math.toRadians(item.latitude)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
    val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    val distanceKm = earthRadius * c

    val localIntensity = if (distanceKm < 3.0) {
        kotlin.math.max(0.0, 1.5 * item.magnitude - 0.5)
    } else {
        kotlin.math.max(0.0, 1.5 * item.magnitude - 1.5 * kotlin.math.log10(distanceKm) - 0.5)
    }

    val magColor = when {
        item.magnitude >= 6.0 -> Color(0xFFFF453A) // Red
        item.magnitude >= 4.5 -> Color(0xFFFF9500) // Amber
        else -> Color(0xFF30D158) // Green
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111216)),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (item.isRealTime) 1.2.dp else 0.5.dp,
                color = if (item.isRealTime) Color(0xFFFF453A) else Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(20.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Magnitude column
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(magColor.copy(alpha = 0.15f))
                    .size(62.dp)
                    .border(1.dp, magColor, CircleShape)
            ) {
                Text(
                    text = String.format("%.1f", item.magnitude),
                    color = magColor,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.SansSerif
                )
                Text(
                    text = "M级",
                    color = magColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Text info details
            Column(
                modifier = Modifier
                    .weight(1f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.placeName,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    
                    Text(
                        text = item.infoTypeName,
                        color = if (item.infoTypeName.contains("自动")) Color(0xFFFF9500) else Color(0xFF30D158),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "深度: ${item.depth.toInt()} km  |  震中距: ${distanceKm.toInt()} km",
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                
                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "发震时间: ${item.time}",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Estimating color and degree
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "预估本地烈度",
                    color = Color.Gray,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (localIntensity > 0.0) "${String.format("%.1f", localIntensity)}度" else "烈度 0",
                    color = if (localIntensity >= 4.0) Color(0xFFFF3B30) else if (localIntensity >= 2.0) Color(0xFFFF9500) else Color.LightGray,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun EmptyStateView(onRefresh: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = "Search",
            tint = Color.Gray,
            modifier = Modifier.size(72.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "暂本地震速报目录",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "中国地震台网数据连接中，点击下方重新拉取最新的历史速报数据。",
            color = Color.Gray,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onRefresh,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF453A))
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("同步台网目录")
        }
    }
}

fun getCurrentGpsLocation(context: android.content.Context, onResult: (android.location.Location?) -> Unit) {
    val locationManager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager
    if (locationManager == null) {
        onResult(null)
        return
    }
    val fineGranted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val coarseGranted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    if (!fineGranted && !coarseGranted) {
        onResult(null)
        return
    }
    try {
        var location: android.location.Location? = null
        if (locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
            location = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
        }
        if (location == null && locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
            location = locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
        }

        if (location != null) {
            onResult(location)
            return
        }

        val provider = if (locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
            android.location.LocationManager.GPS_PROVIDER
        } else if (locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
            android.location.LocationManager.NETWORK_PROVIDER
        } else {
            null
        }

        if (provider != null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                locationManager.getCurrentLocation(
                    provider,
                    null,
                    context.mainExecutor
                ) { loc ->
                    onResult(loc)
                }
            } else {
                locationManager.requestSingleUpdate(provider, object : android.location.LocationListener {
                    override fun onLocationChanged(loc: android.location.Location) {
                        onResult(loc)
                    }
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }, context.mainLooper)
            }
        } else {
            onResult(null)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        onResult(null)
    }
}

@Composable
fun SettingsTabScreen(
    viewModel: EarthquakeViewModel,
    homeSetting: DbHomeSetting
) {
    val context = LocalContext.current
    var inputLat by remember { mutableStateOf(homeSetting.latitude.toString()) }
    var inputLon by remember { mutableStateOf(homeSetting.longitude.toString()) }
    var inputName by remember { mutableStateOf(homeSetting.name) }
    var isExpandedDropdown by remember { mutableStateOf(false) }

    val logs by viewModel.logMessages.collectAsStateWithLifecycle()

    val locationPresets = listOf(
        PresetLocation("四川省成都市中心", 30.67, 104.06),
        PresetLocation("四川甘孜州雅江县", 29.43, 101.09),
        PresetLocation("云南省昆明市中心", 25.04, 102.71),
        PresetLocation("北京市东城区中心", 39.90, 116.40),
        PresetLocation("新疆乌鲁木齐沙区", 43.82, 87.62),
        PresetLocation("河北省唐山市中心", 39.63, 118.18),
        PresetLocation("甘肃省临夏州积石山", 35.70, 102.79),
        PresetLocation("福建省福州市中心", 26.08, 119.30)
    )

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            getCurrentGpsLocation(context) { location ->
                if (location != null) {
                    inputLat = String.format("%.4f", location.latitude)
                    inputLon = String.format("%.4f", location.longitude)
                    inputName = "当前 GPS 定位点"
                    android.widget.Toast.makeText(context, "GPS 卫星定位成功！已自动填入经纬度。", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(context, "定位获取失败，请确保系统 GPS 定位服务已开启后重试", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        } else {
            android.widget.Toast.makeText(context, "未获得位置系统权限，无法使用 GPS 实时定位", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section 1: Coordinates and Preset
        Text(
            text = "家の地理位置与参数 (核心设置)",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2027))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Preset Dropdown trigger and GPS Locator in adaptive side-by-side Row layout
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Left element: Preset Location dropdown
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { isExpandedDropdown = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF9500)),
                            border = BorderStroke(1.dp, Color(0xFFFF9500).copy(alpha = 0.5f))
                        ) {
                            Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("多发地预设点", fontSize = 12.sp, maxLines = 1)
                        }
                        DropdownMenu(
                            expanded = isExpandedDropdown,
                            onDismissRequest = { isExpandedDropdown = false },
                            modifier = Modifier.background(Color(0xFF2C2E35))
                        ) {
                            locationPresets.forEach { preset ->
                                DropdownMenuItem(
                                    text = { Text(preset.name, color = Color.White) },
                                    onClick = {
                                        inputLat = preset.lat.toString()
                                        inputLon = preset.lon.toString()
                                        inputName = preset.name
                                        isExpandedDropdown = false
                                    },
                                    leadingIcon = { Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Color(0xFFFF453A)) }
                                )
                            }
                        }
                    }

                    // Right element: GPS locator button invoking system permissions flow
                    Button(
                        onClick = {
                            requestPermissionLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2F80ED))
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("GPS 卫星定位", fontSize = 12.sp, maxLines = 1)
                    }
                }

                Divider(color = Color(0xFF2D303D))

                // Name field
                OutlinedTextField(
                    value = inputName,
                    onValueChange = { inputName = it },
                    label = { Text("防震守护地名称 (如: 四川康定)", color = Color.Gray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFFF453A),
                        unfocusedBorderColor = Color(0xFF2D303D)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = inputLat,
                        onValueChange = { inputLat = it },
                        label = { Text("经度 (Latitude)", color = Color.Gray) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFFF453A),
                            unfocusedBorderColor = Color(0xFF2D303D)
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = inputLon,
                        onValueChange = { inputLon = it },
                        label = { Text("纬度 (Longitude)", color = Color.Gray) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFFF453A),
                            unfocusedBorderColor = Color(0xFF2D303D)
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                Button(
                    onClick = {
                        val latVal = inputLat.toDoubleOrNull() ?: 30.67
                        val lonVal = inputLon.toDoubleOrNull() ?: 104.06
                        viewModel.saveHomeSetting(latVal, lonVal, inputName)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF453A)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("应用并保存此防守坐标")
                }
            }
        }

        // Section 2: Alert Behavior Switches
        Text(
            text = "地震早期预警偏好 (波级限制与广播)",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2027))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Warning threshold slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "起报烈度槛阈值", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(
                            text = if (homeSetting.alertThreshold > 0.0) "${String.format("%.1f", homeSetting.alertThreshold)}度" else "烈度 0 (全部早期预警都播报)",
                            color = Color(0xFFFF9500),
                            fontWeight = FontWeight.Black
                        )
                    }
                    Slider(
                        value = homeSetting.alertThreshold.toFloat(),
                        onValueChange = { viewModel.updateAlertThreshold(it.toDouble()) },
                        valueRange = 0.0f..6.0f,
                        steps = 5,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFF453A),
                            activeTrackColor = Color(0xFFFF453A),
                            inactiveTrackColor = Color(0xFF2D303D)
                        )
                    )
                    Text(
                        text = "本起报烈度为“预估本地烈度”。当且仅当发生国家级的早期地震预警，且本地计算出来的烈度大于等于该阈值时，程序才会“强制弹出大尺寸横波警告浮窗并高声广播”。",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }

                Divider(color = Color(0xFF2D303D), modifier = Modifier.padding(vertical = 4.dp))

                // Toggle 1: Custom Overlay
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "强制全屏/防震窗口大横幅警告", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(text = "一旦符合报警烈度，立刻接断当前屏幕弹出强烈的防空预警面板", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(
                        checked = homeSetting.isSystemAlertEnabled,
                        onCheckedChange = { viewModel.updateAlertSwitches(it, homeSetting.soundEnabled, homeSetting.playTtsEnabled) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFF453A), checkedTrackColor = Color(0xFFFF453A).copy(alpha = 0.4f))
                    )
                }

                // Toggle 2: Siren Sound
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "蜂鸣器与双音频电抗警报声", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(text = "通过设备自带发声振荡单元，循环播放防震空袭警报嗡鸣", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(
                        checked = homeSetting.soundEnabled,
                        onCheckedChange = { viewModel.updateAlertSwitches(homeSetting.isSystemAlertEnabled, it, homeSetting.playTtsEnabled) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFF453A), checkedTrackColor = Color(0xFFFF453A).copy(alpha = 0.4f))
                    )
                }

                // Toggle 3: TTS Text to Speech Voice
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "真人级国家速报TTS中文语音提示", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(text = "自动将触发时长的地名、震级、破坏性横波到达秒数合成普通话朗读", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(
                        checked = homeSetting.playTtsEnabled,
                        onCheckedChange = { viewModel.updateAlertSwitches(homeSetting.isSystemAlertEnabled, homeSetting.soundEnabled, it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFF453A), checkedTrackColor = Color(0xFFFF453A).copy(alpha = 0.4f))
                    )
                }
            }
        }

        // Section 3: Professional Simulation System (A gorgeous addition!)
        Text(
            text = "防震减灾演练模拟系统 (可快速测试本报警)",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2027)),
            modifier = Modifier.border(1.dp, Color(0xFFFF453A).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "说明：由于国内罕见即发地震，我们贴心地配备了“预警演练发生模拟器”。您可以点击下列不同震级和距离的演练按钮，程序会自动核算出家的横波倒计时，并呼啸长鸣，测试您的撤离逃生流程。",
                    color = Color.LightGray,
                    fontSize = 11.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.simulateWarning(6.5, 120.0, "四川甘孜州雅江县") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF453A)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("模拟中偏远强震 (本地5度)", fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                    Button(
                        onClick = { viewModel.simulateWarning(4.5, 25.0, "本地近区浅震") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9500)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("模拟浅层微震 (本地4.5度)", fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.simulateWarning(7.9, 320.0, "大断裂带超强特大地震") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFBF5AF2)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("超强特大地震 (演练)", fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                    Button(
                        onClick = { viewModel.simulateWarning(5.0, 75.0, "演示性浅表地震") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30D158)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("M5.0 避险体验 (75km)", fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }

        // Section 4: Event log monitor
        Text(
            text = "守护状态与后台服务日志实时监控",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0C0D10)),
            modifier = Modifier.border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp)
            ) {
                if (logs.isEmpty()) {
                    Text(text = "无任何服务异常日志，运行极度平稳...", color = Color.Gray, fontSize = 12.sp)
                } else {
                    logs.forEach { log ->
                        Text(
                            text = log,
                            color = if (log.contains("[WARN]") || log.contains("[ERR]") || log.contains("M")) Color(0xFFFF453A) else if (log.contains("[INFO]")) Color(0xFF30D158) else Color.LightGray,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

data class PresetLocation(val name: String, val lat: Double, val lon: Double)

/**
 * Highly Polished, Premium Civil Defense Earthquake Early Warning (EEW) Screen
 * Designed precisely to match high-fidelity mobile systems.
 */
@Composable
fun WarningAlarmScreen(
    event: EarlyWarningEvent,
    countdown: Int,
    userHome: DbHomeSetting,
    onDismiss: () -> Unit
) {
    // Intercept swipe back gesture / physical back button to safely mute playing sirens & dismiss overlay
    BackHandler {
        onDismiss()
    }

    val context = LocalContext.current
    val distance = event.calculateDistanceKm(userHome.latitude, userHome.longitude)
    val localIntensity = event.estimateLocalIntensity(userHome.latitude, userHome.longitude)

    val systemBaseColor = when {
        localIntensity < 3.0 -> Color(0xFF2F80ED) // Blue (蓝色地震预警)
        localIntensity < 5.0 -> Color(0xFFFFCC00) // Yellow (黄色地震预警)
        localIntensity < 7.0 -> Color(0xFFFF9500) // Orange (橙色地震预警)
        else -> Color(0xFFFF3B30) // Red (红色地震预警)
    }

    val warningLevelName = when {
        localIntensity < 3.0 -> "蓝色地震预警"
        localIntensity < 5.0 -> "黄色地震预警"
        localIntensity < 7.0 -> "橙色地震预警"
        else -> "红色地震预警"
    }

    val systemBaseRed = systemBaseColor // For full compatibility

    // Vertical design dynamic graded background
    val backgroundBrush = Brush.verticalGradient(
        colors = when {
            localIntensity < 3.0 -> listOf(Color(0xFF031E3D), Color(0xFF010812), Color(0xFF000000))
            localIntensity < 5.0 -> listOf(Color(0xFF423B00), Color(0xFF0F0E00), Color(0xFF000000))
            localIntensity < 7.0 -> listOf(Color(0xFF6B3100), Color(0xFF140801), Color(0xFF000000))
            else -> listOf(Color(0xFF8B1E00), Color(0xFF140301), Color(0xFF000000))
        }
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Top navigation and title row with premium custom svg cross
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color.White.copy(alpha = 0.12f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss Alert",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "地震预警",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = warningLevelName,
                        color = systemBaseColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. High-contrast main announcer header
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (countdown > 0) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = countdown.toString(),
                            color = Color.White,
                            fontSize = 80.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.SansSerif,
                            lineHeight = 80.sp,
                            letterSpacing = (-2).sp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "秒",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 14.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "地震波正在靠近",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.2.sp
                    )
                } else {
                    Text(
                        text = "地震波已到达",
                        color = Color.White,
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.SansSerif,
                        lineHeight = 44.sp,
                        letterSpacing = 1.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "发震时间：${event.shockTime}",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "信息来源：${if (event.isMock) "防震减灾演练中心 (模拟验证模式)" else "中国地震预警网 成都美新减灾研究所"}",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.2.sp
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // 3. Grid representation (2 columns x 2 rows) matching screenshot
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    WarningGridCard(
                        icon = Icons.Default.LocationOn,
                        title = "震中",
                        value = event.placeName,
                        valueColor = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    
                    val intensityDesc = when {
                        localIntensity >= 7.0 -> "极度强烈 破坏极其严重"
                        localIntensity >= 5.0 -> "震感强烈 安全避险"
                        localIntensity >= 3.0 -> "震感明显 保持警惕"
                        else -> "轻微震感 保持冷静"
                    }
                    WarningGridCard(
                        icon = Icons.Default.Warning,
                        title = "预估烈度 ${String.format("%.1f", localIntensity)}",
                        value = intensityDesc,
                        valueColor = systemBaseRed,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    WarningGridCard(
                        icon = Icons.Default.Info,
                        title = "预警震级",
                        value = "${event.magnitude} 级",
                        valueColor = systemBaseRed,
                        modifier = Modifier.weight(1f)
                    )
                    WarningGridCard(
                        icon = Icons.Default.Place,
                        title = "震中距离",
                        value = "${distance.toInt()} km",
                        valueColor = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 4. Full-Width Safety Advisory Section with clear line spacing guidelines
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF16171E).copy(alpha = 0.85f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Safety Advice",
                            tint = Color(0xFFFF9500),
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "安全提示",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "• 沉着冷静，后退避险：避开悬挂物，不乘电梯",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "• 室内：躲避风机、承重墙角等三角安全空间",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "• 室外：远离玻璃幕墙、高压线并就地开阔处避险",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 5. SOS Utilities section according to screenshot reference standard
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "SOS 工具",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 2.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Left pill - SOS Hotline Call
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xFFE53935))
                            .clickable {
                                try {
                                    val dialIntent = android.content.Intent(
                                        android.content.Intent.ACTION_DIAL,
                                        android.net.Uri.parse("tel:110")
                                    )
                                    context.startActivity(dialIntent)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = "SOS Phone",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "SOS 紧急联络",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    // Right pill - Map search for safe shelter location
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xFF2E7D32))
                            .clickable {
                                try {
                                    val mapIntent = android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse("geo:0,0?q=地震避难所")
                                    )
                                    context.startActivity(mapIntent)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Place,
                            contentDescription = "Shelter Maps",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "附近避难所",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 6. Huge safe dismiss button at bottom
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = systemBaseRed),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .border(0.5.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(28.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Dismiss Alert",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "我已就地避护安全，关闭此警报",
                    fontSize = 15.sp,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
fun WarningGridCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2027).copy(alpha = 0.85f)),
        modifier = modifier.border(0.5.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = title,
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = value,
                color = valueColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
