package com.chrisz.nikconnect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chrisz.nikconnect.ui.MainViewModel
import com.chrisz.nikconnect.ui.NiKConnectApp
import com.chrisz.nikconnect.ui.NiKConnectTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NiKConnectTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val model: MainViewModel = viewModel()
                    NiKConnectApp(model)
                }
            }
        }
    }
}
