package app.rasul.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.unit.Dp
import app.rasul.R
import app.rasul.data.Load
import app.rasul.ui.components.PrimaryButton

/** Shared loading / no connection / no key / error states; [content] renders the Ready value. */
@Composable
fun <T> LoadView(state: Load<T>, onRetry: () -> Unit, onPairPc: (() -> Unit)? = null, content: @Composable (T) -> Unit) {
    when (state) {
        Load.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        Load.NoConnection -> Message(
            stringResource(R.string.no_connection), null,
            action = onPairPc?.let { pair -> { PrimaryButton(stringResource(R.string.pair_your_pc), pair, modifier = Modifier.fillMaxWidth()) } },
        )
        is Load.BridgeUnreachable -> Message(stringResource(R.string.bridge_pc_unreachable, state.pcName), onRetry, isError = true, contentOffset = 11.dp, compactRetry = true)
        is Load.BridgeNoReadyBots -> Message(stringResource(R.string.bridge_no_ready_bots, state.pcName), null, contentOffset = (-8).dp)
        is Load.NoKey -> Message(stringResource(R.string.no_key, state.connection), onRetry)
        is Load.Failed -> Message(state.error.text(), onRetry, isError = true)
        is Load.Ready -> content(state.value)
    }
}

@Composable
private fun Message(text: String, onRetry: (() -> Unit)?, isError: Boolean = false, action: (@Composable () -> Unit)? = null, contentOffset: Dp = 0.dp, compactRetry: Boolean = false) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(Modifier.offset(y = contentOffset).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(text, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (onRetry != null) {
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = if (compactRetry) androidx.compose.foundation.layout.PaddingValues(0.dp) else androidx.compose.material3.ButtonDefaults.TextButtonContentPadding,
                ) {
                    Text(stringResource(R.string.retry))
                }
            }
            action?.invoke()
        }
    }
}
