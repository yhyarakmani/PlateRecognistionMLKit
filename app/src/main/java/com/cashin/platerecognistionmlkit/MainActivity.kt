package com.cashin.platerecognistionmlkit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cashin.platerecognistionmlkit.ui.components.LicensePlateScannerApp
import com.cashin.platerecognistionmlkit.ui.theme.PlateRecognistionMLKitTheme

class MainActivity : ComponentActivity() {


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PlateRecognistionMLKitTheme {
                LicensePlateScannerApp()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}



