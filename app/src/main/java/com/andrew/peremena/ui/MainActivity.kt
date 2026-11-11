package com.andrew.peremena.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andrew.peremena.alarm.AlarmScheduler
import com.andrew.peremena.data.Repo
import com.andrew.peremena.model.Task

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        Repo.housekeeping()
        setContent { PeremenaTheme { MainScreen() } }
    }

    override fun onResume() {
        super.onResume()
        // Пользователь мог выдать разрешение или сменить время — пересчитать.
        AlarmScheduler.reschedule(this)
    }
}

private val Blue = Color(0xFF1F5F8B)

@Composable
fun PeremenaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Blue,
            secondary = Color(0xFF3C7A5A),
            tertiary = Color(0xFFB4562B),
            background = Color(0xFFF7F9FB),
            surface = Color(0xFFFFFFFF),
        ),
        content = content,
    )
}

/** Какое окно задачи открыто: null — никакое; id == null — новая задача. */
private data class TaskEdit(val task: Task?)

@Composable
fun MainScreen() {
    val data by Repo.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var edit by remember { mutableStateOf<TaskEdit?>(null) }

    // Разрешение на уведомления (Android 13+) — спрашиваем при первом запуске.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Home, null) }, label = { Text("Сегодня") },
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.DateRange, null) }, label = { Text("Расписание") },
                )
            }
        },
        floatingActionButton = {
            if (tab == 0) {
                FloatingActionButton(onClick = { edit = TaskEdit(null) }) {
                    Icon(Icons.Default.Add, contentDescription = "Задача")
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (tab) {
            0 -> TodayScreen(data, m, onEdit = { edit = TaskEdit(it) }, onOpenSchedule = { tab = 1 })
            else -> ScheduleScreen(data, m)
        }
    }

    edit?.let { e ->
        TaskDialog(data = data, task = e.task, onDismiss = { edit = null })
    }
}
