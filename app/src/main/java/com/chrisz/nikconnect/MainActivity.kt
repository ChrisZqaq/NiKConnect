package com.chrisz.nikconnect

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.chrisz.nikconnect.cloud.CloudDebug
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chrisz.nikconnect.ui.MainViewModel
import com.chrisz.nikconnect.ui.NiKConnectApp
import com.chrisz.nikconnect.ui.NiKConnectTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 云创接口原始报文落盘（仅 debug），便于本地拉取排查；不影响 release 行为。
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            CloudDebug.dir = File(cacheDir, "cloud_debug")
            CloudDebug.enabled = true
        }
        enableEdgeToEdge()
        setContent {
            NiKConnectTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Android 13+ 通知运行时授权：连接成功前需已授权，否则前台服务通知不显示
                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) {}
                    LaunchedEffect(Unit) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                this@MainActivity, Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    val model: MainViewModel = viewModel()
                    NiKConnectApp(model)
                }
            }
        }
    }
}
