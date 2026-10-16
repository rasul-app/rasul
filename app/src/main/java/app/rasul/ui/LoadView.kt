package app.rasul.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.rasul.R
import app.rasul.data.Load

/** Shared loading / no connection / no key / error states; [content] renders the Ready value. */
@Composable
fun <T> LoadView(state: Load<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        Load.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        Load.NoConnection -> Message(stringResource(R.string.no_connection), null)
        is Load.NoKey -> Message(stringResource(R.string.no_key, state.connection), onRetry)
        is Load.Failed -> Message(state.error.text(), onRetry, isError = true)
        is Load.Ready -> content(state.value)
    }
}

@Composable
private fun Message(text: String, onRetry: (() -> Unit)?, isError: Boolean = false) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}
