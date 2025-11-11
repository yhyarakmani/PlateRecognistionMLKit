package com.cashin.platerecognistionmlkit.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cashin.plate_scanner.model.PipelineResult

@Composable
fun InputScreen(
    firstText: String,
    onFirstTextChanged: (String) -> Unit,
    secondText: String,
    onSecondTextChanged: (String) -> Unit,
    onStartScanning: () -> Unit,
    success: Boolean,
    successResult: PipelineResult?,
    hasPermission: Boolean
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(50.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        // --- Text fields ---
        OutlinedTextField(
            value = firstText,
            onValueChange = onFirstTextChanged,
            label = { Text("Plate Number") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = secondText,
            onValueChange = onSecondTextChanged,
            label = { Text("Plate Characters") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = {
            focusManager.clearFocus()
            if (firstText.isNotBlank() && secondText.isNotBlank() && hasPermission) {
                onStartScanning()
            }
        }) {
            Text("Start Scanning")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Show success result ---
        if (success && successResult != null) {
            Text(
                text = "✅ Success!",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Image(
                bitmap = successResult.bitmap.asImageBitmap(),
                contentDescription = "Detected Plate Frame",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.6f)
            )
        }
    }
}
