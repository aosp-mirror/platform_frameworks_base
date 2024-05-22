/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.brightness.ui.compose

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.PlatformSlider
import com.android.systemui.brightness.shared.GammaBrightness
import com.android.systemui.brightness.ui.viewmodel.BrightnessSliderViewModel
import com.android.systemui.brightness.ui.viewmodel.Drag
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.utils.PolicyRestriction
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
private fun BrightnessSlider(
    gammaValue: Int,
    valueRange: IntRange,
    label: Text.Resource,
    icon: Icon,
    restriction: PolicyRestriction,
    onRestrictedClick: (PolicyRestriction.Restricted) -> Unit,
    onDrag: (Int) -> Unit,
    onStop: (Int) -> Unit,
    modifier: Modifier = Modifier,
    formatter: (Int) -> String = { "$it" },
) {
    var value by remember(gammaValue) { mutableIntStateOf(gammaValue) }
    val animatedValue by
        animateIntAsState(targetValue = value, label = "BrightnessSliderAnimatedValue")
    val floatValueRange = valueRange.first.toFloat()..valueRange.last.toFloat()
    val isRestricted = restriction is PolicyRestriction.Restricted

    PlatformSlider(
        value = animatedValue.toFloat(),
        valueRange = floatValueRange,
        enabled = !isRestricted,
        onValueChange = {
            if (!isRestricted) {
                value = it.toInt()
                onDrag(value)
            }
        },
        onValueChangeFinished = {
            if (!isRestricted) {
                onStop(value)
            }
        },
        modifier =
            modifier.clickable(
                enabled = isRestricted,
            ) {
                if (restriction is PolicyRestriction.Restricted) {
                    onRestrictedClick(restriction)
                }
            },
        icon = { isDragging ->
            if (isDragging) {
                Text(text = formatter(value))
            } else {
                Icon(modifier = Modifier.size(24.dp), icon = icon)
            }
        },
        label = {
            Text(
                text = stringResource(id = label.res),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
        },
    )
}

@Composable
fun BrightnessSliderContainer(
    viewModel: BrightnessSliderViewModel,
    modifier: Modifier = Modifier,
) {
    val gamma: Int by
        viewModel.currentBrightness.map { it.value }.collectAsStateWithLifecycle(initialValue = 0)
    val coroutineScope = rememberCoroutineScope()
    val restriction by
        viewModel.policyRestriction.collectAsStateWithLifecycle(
            initialValue = PolicyRestriction.NoRestriction
        )

    BrightnessSlider(
        gammaValue = gamma,
        valueRange = viewModel.minBrightness.value..viewModel.maxBrightness.value,
        label = viewModel.label,
        icon = viewModel.icon,
        restriction = restriction,
        onRestrictedClick = viewModel::showPolicyRestrictionDialog,
        onDrag = { coroutineScope.launch { viewModel.onDrag(Drag.Dragging(GammaBrightness(it))) } },
        onStop = { coroutineScope.launch { viewModel.onDrag(Drag.Stopped(GammaBrightness(it))) } },
        modifier = modifier.fillMaxWidth(),
        formatter = viewModel::formatValue,
    )
}
