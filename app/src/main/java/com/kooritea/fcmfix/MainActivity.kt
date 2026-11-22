package com.kooritea.fcmfix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kooritea.fcmfix.ui.FcmFixApp
import com.kooritea.fcmfix.ui.theme.FcmFixTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            FcmFixTheme {
                FcmFixApp(viewModel())
            }
        }
    }
}
