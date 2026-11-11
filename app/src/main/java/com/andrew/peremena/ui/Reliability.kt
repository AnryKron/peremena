package com.andrew.peremena.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.andrew.peremena.alarm.AlarmScheduler

/** Проверки, без которых напоминалка «иногда не звенит». */
object Perms {
    fun notificationsOk(ctx: Context) = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    fun exactOk(ctx: Context) = AlarmScheduler.canExact(ctx)
    fun batteryOk(ctx: Context) =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    /** Производители, которые известны агрессивным «убийством» фоновых приложений. */
    val aggressiveVendor: Boolean
        get() = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco", "huawei", "honor", "oppo", "realme", "vivo", "oneplus", "meizu", "samsung", "tecno", "infinix")

    private fun pkg(ctx: Context) = Uri.parse("package:" + ctx.packageName)

    private fun open(ctx: Context, vararg intents: Intent) {
        for (i in intents) {
            if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    fun openNotifications(ctx: Context) = open(
        ctx,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg(ctx)),
    )

    fun openExact(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            open(ctx, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg(ctx)), Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg(ctx)))
        }
    }

    @SuppressLint("BatteryLife")
    fun openBattery(ctx: Context) = open(
        ctx,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg(ctx)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    )

    fun openAppDetails(ctx: Context) = open(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg(ctx)))
}

/**
 * Карточка надёжности. compact = true — только то, что не настроено
 * (для экрана «Сегодня»); иначе — все пункты и подсказка производителя.
 */
@Composable
fun ReliabilityCard(compact: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    // Пользователь вернулся из настроек — перепроверить.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    val notif = remember(tick) { Perms.notificationsOk(ctx) }
    val exact = remember(tick) { Perms.exactOk(ctx) }
    val battery = remember(tick) { Perms.batteryOk(ctx) }
    val allOk = notif && exact && battery
    if (compact && allOk) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (allOk) MaterialTheme.colorScheme.surface else Color(0xFFFFF3E0),
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!allOk) Icon(Icons.Default.Warning, null, tint = Color(0xFFB4562B))
                Text(
                    if (allOk) "Надёжность сигналов: всё настроено" else "  Сигналы могут не прийти",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (!compact || !notif) Item("Уведомления", notif, "Разрешить") { Perms.openNotifications(ctx) }
            if (!compact || !exact) Item("Точные будильники", exact, "Разрешить") { Perms.openExact(ctx) }
            if (!compact || !battery) Item("Без ограничений экономии батареи", battery, "Снять") { Perms.openBattery(ctx) }
            if (!compact && Perms.aggressiveVendor) {
                Text(
                    "На телефонах ${Build.MANUFACTURER} дополнительно включите для «Перемены» " +
                        "«Автозапуск» и «Без ограничений» в разделе батареи — иначе система может " +
                        "закрывать приложение в фоне.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { Perms.openAppDetails(ctx) }) { Text("Открыть настройки приложения") }
            }
        }
    }
}

@Composable
private fun Item(title: String, ok: Boolean, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text((if (ok) "✓ " else "✗ ") + title, Modifier.weight(1f), color = if (ok) Color(0xFF3C7A5A) else Color(0xFFB4562B))
        if (!ok) OutlinedButton(onClick = onClick) { Text(action) }
    }
}
