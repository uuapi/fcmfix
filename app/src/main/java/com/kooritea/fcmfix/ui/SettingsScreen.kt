package com.kooritea.fcmfix.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kooritea.fcmfix.R
import com.kooritea.fcmfix.util.SelfCheck.Level

private const val PROJECT_URL = "https://github.com/kooritea/fcmfix"
private const val FORK_URL = "https://github.com/uuapi/fcmfix"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: AppUiState,
    actions: AppActions,
    nav: Navigation,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
) {
    val config = state.config
    val editable = config != null
    var confirmAllowAll by rememberSaveable { mutableStateOf(false) }
    val fcmApps = state.apps?.filter { it.includeFcm }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { SectionHeader("推送") }
            item {
                GroupCard {
                    SwitchRow(
                        R.drawable.ic_notifications, "阻止应用停止时自动清除通知",
                        "放行的应用被系统停止时，保留它已经发出的通知",
                        config?.keepNotifications == true, editable, actions::setKeepNotifications,
                    )
                    RowDivider()
                    SwitchRow(
                        R.drawable.ic_notifications, "自动放行时开启通知",
                        "自动放行的应用若通知已关闭，自动代为开启；手动关闭过的应用不会被反复打开",
                        config?.autoEnableNotify == true, editable, actions::setAutoEnableNotify,
                    )
                }
            }
            item { SectionHeader("应用") }
            item {
                GroupCard {
                    val auto = config?.autoAllowFcm == true
                    ActionRow(
                        R.drawable.ic_select_all, "放行所有包含 FCM 的应用",
                        when {
                            auto -> "已开启自动放行，不需要再全部放行"
                            fcmApps == null -> "正在读取应用列表…"
                            else -> "共 ${fcmApps.size} 个；之后安装的应用不会自动放行"
                        },
                        enabled = editable && !auto && fcmApps != null,
                    ) { confirmAllowAll = true }
                }
            }
            item { SectionHeader("关于") }
            item {
                GroupCard {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = { LevelIcon(Level.INFO) },
                        headlineContent = { Text("fcmfix ${state.versionName}", fontWeight = FontWeight.Medium) },
                        supportingContent = { Text("基于上游 fcmfix，含 ColorOS 启动闸门旁路。LSPosed 作用域：系统框架") },
                    )
                    RowDivider()
                    ActionRow(R.drawable.ic_code, "上游项目主页", "github.com/kooritea/fcmfix", trailing = R.drawable.ic_open_in_new) {
                        nav.openUrl(PROJECT_URL)
                    }
                    RowDivider()
                    ActionRow(R.drawable.ic_guide, "本分支主页", "github.com/uuapi/fcmfix", trailing = R.drawable.ic_open_in_new) {
                        nav.openUrl(FORK_URL)
                    }
                }
            }
        }
    }
    if (confirmAllowAll && fcmApps != null) {
        AlertDialog(
            onDismissRequest = { confirmAllowAll = false },
            title = { Text("放行所有包含 FCM 的应用？") },
            text = { Text("将放行 ${fcmApps.size} 个应用。之后安装的应用需要再手动放行，或开启“自动放行包含 FCM 的应用”。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmAllowAll = false
                    actions.allowAllFcm()
                }) { Text("放行") }
            },
            dismissButton = { TextButton(onClick = { confirmAllowAll = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SettingIcon(icon: Int) {
    Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchRow(icon: Int, title: String, summary: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { SettingIcon(icon) },
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(summary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
    )
}

@Composable
private fun ActionRow(
    icon: Int,
    title: String,
    summary: String,
    enabled: Boolean = true,
    trailing: Int? = null,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.38f
    ListItem(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        ),
        leadingContent = { SettingIcon(icon) },
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(summary) },
        trailingContent = trailing?.let { { SettingIcon(it) } },
    )
}
