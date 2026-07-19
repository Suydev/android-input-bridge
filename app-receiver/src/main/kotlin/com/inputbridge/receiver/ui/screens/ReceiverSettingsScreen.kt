package com.inputbridge.receiver.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inputbridge.receiver.ui.theme.*
import com.inputbridge.receiver.viewmodel.ReceiverViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverSettingsScreen(onBack: () -> Unit, viewModel: ReceiverViewModel) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    var portInput by remember { mutableStateOf(config.transport.port.toString()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontFamily = FontFamily.Monospace) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = ReceiverOnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ReceiverSurface, titleContentColor = ReceiverOnSurface)
            )
        },
        containerColor = ReceiverBackground,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text("TRANSPORT", color = ReceiverPrimary, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
            OutlinedTextField(
                value = portInput,
                onValueChange = {
                    portInput = it
                    it.toIntOrNull()?.let { p -> viewModel.setListenPort(p) }
                },
                label = { Text("Listen Port", fontFamily = FontFamily.Monospace) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ReceiverPrimary, unfocusedBorderColor = ReceiverDim,
                    focusedLabelColor = ReceiverPrimary, unfocusedLabelColor = ReceiverDim,
                    focusedTextColor = ReceiverOnSurface, unfocusedTextColor = ReceiverOnSurface,
                ),
            )
            Text("Must match the port set in the bridge app.", color = ReceiverDim,
                fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(32.dp))
        }
    }
}
