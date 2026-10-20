package com.tapscene.ui.shell

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType

/** Shared presentation only; each editor still owns its input, validation and save boundary. */
@Composable
internal fun EditorPercentageField(label: String, value: String, onValue: (String) -> Unit, enabled: Boolean, modifier: Modifier,
    onTextEditEnd: () -> Unit = {}) {
    OutlinedTextField(value, onValue, label = { Text(label) }, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.onFocusChanged { if (!it.isFocused) onTextEditEnd() })
}
