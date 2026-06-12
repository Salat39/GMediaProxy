package com.geely.online.service.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.geely.online.service.ui.theme.AppTheme

@Composable
fun RenderSwitcher(
    modifier: Modifier,
    title: String,
    subtitle: String? = null,
    onSubtitle: String = "да",
    offSubtitle: String = "нет",
    value: Boolean? = null,
    enable: Boolean = true,
    groupDivider: Boolean = true,
    onChange: (Boolean) -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(value != null && enable) {
                onChange(value?.let { !it } ?: true)
            }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            Modifier
                .weight(1f)
                .then(if (!enable) Modifier.graphicsLayer { alpha = .25f } else Modifier)
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 23.dp),
                text = title,
                style = AppTheme.typography.screenTitle,
                color = AppTheme.colors.contentPrimary
            )

            Spacer(Modifier.height(5.dp))

            Text(
                text = subtitle ?: if (value == true) {
                    onSubtitle
                } else {
                    offSubtitle
                },
                modifier = Modifier.padding(horizontal = 23.dp),
                color = AppTheme.colors.contentPrimary.copy(.4f),
                style = AppTheme.typography.dialogSubtitle
            )
        }
        if (groupDivider) {
            Spacer(Modifier.width(14.dp))
            Spacer(
                Modifier
                    .width(1.dp)
                    .height(22.dp)
                    .background(AppTheme.colors.sliderPassive)
            )
            Spacer(Modifier.width(14.dp))
        }
        value?.let { value ->
            ProfileSwitch(
                scale = .8f,
                checked = value,
                enabled = enable,
                onCheckedChange = null
            )
        } ?: run { Spacer(Modifier.width(52.dp)) }
        Spacer(Modifier.width(20.dp))
    }
}
