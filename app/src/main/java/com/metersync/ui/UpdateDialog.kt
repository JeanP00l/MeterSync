package com.metersync.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Диалог для уведомления об обновлении приложения
 */
@Composable
fun UpdateDialog(
    currentVersion: String,
    latestVersion: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Доступно обновление")
        },
        text = {
            Text(
                "Текущая версия: $currentVersion\n" +
                "Доступна версия: $latestVersion\n\n" +
                "Рекомендуется обновить приложение для получения новых функций и исправлений."
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    // Открываем страницу релизов в браузере
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/JeanP00l/MeterSync/releases"))
                    context.startActivity(intent)
                    onDismiss()
                }
            ) {
                Text("Обновить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Позже")
            }
        }
    )
}

