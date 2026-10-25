package xyz.vmflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.vmflow.R
import xyz.vmflow.ui.theme.OfflineGray
import xyz.vmflow.ui.theme.OnlineGreen

@Composable
fun StatusChip(
    isOnline: Boolean,
    modifier: Modifier = Modifier
) {
    val dotColor = if (isOnline) OnlineGreen else OfflineGray
    val label = stringResource(if (isOnline) R.string.status_online else R.string.status_offline)
    val bgColor = if (isOnline) {
        OnlineGreen.copy(alpha = 0.12f)
    } else {
        OfflineGray.copy(alpha = 0.12f)
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = dotColor,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}
