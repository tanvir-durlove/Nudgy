package com.example.ui.navigation

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.NudgyBorder
import com.example.ui.theme.NudgyCanvas
import com.example.ui.theme.NudgyCharcoal
import com.example.ui.theme.NudgyMuted
import com.example.ui.theme.NudgyPrimary

@Composable
fun NudgyBottomBar(
    currentDestination: NudgyNavDestination,
    onNavigate: (NudgyNavDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = NudgyCanvas
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(color = NudgyBorder.copy(alpha = 0.6f), thickness = 1.dp)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NudgyNavDestination.values().forEach { destination ->
                    val isSelected = destination == currentDestination
                    val color = if (isSelected) NudgyPrimary else NudgyMuted

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onNavigate(destination) }
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = destination.icon,
                            contentDescription = destination.title,
                            tint = color,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = destination.title,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = color
                            )
                        )
                    }
                }
            }
        }
    }
}
