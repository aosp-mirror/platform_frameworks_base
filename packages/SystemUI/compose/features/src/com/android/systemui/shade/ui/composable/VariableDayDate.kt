package com.android.systemui.shade.ui.composable

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import com.android.compose.theme.colorAttr
import com.android.systemui.shade.ui.viewmodel.ShadeHeaderViewModel.HeaderChipHighlight

@Composable
fun VariableDayDate(
    longerDateText: String,
    shorterDateText: String,
    chipHighlight: HeaderChipHighlight,
    modifier: Modifier = Modifier,
) {
    val textColor =
        if (chipHighlight is HeaderChipHighlight.Strong)
            colorAttr(android.R.attr.textColorPrimaryInverse)
        else colorAttr(android.R.attr.textColorPrimary)

    Layout(
        contents =
            listOf(
                {
                    Text(
                        text = longerDateText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                        maxLines = 1,
                    )
                },
                {
                    Text(
                        text = shorterDateText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                        maxLines = 1,
                    )
                },
            ),
        modifier = modifier,
    ) { measureables, constraints ->
        check(measureables.size == 2)
        check(measureables[0].size == 1)
        check(measureables[1].size == 1)

        val longerMeasurable = measureables[0][0]
        val shorterMeasurable = measureables[1][0]

        val longerPlaceable = longerMeasurable.measure(constraints)
        val shorterPlaceable = shorterMeasurable.measure(constraints)

        // If width < maxWidth (and not <=), we can assume that the text fits.
        val placeable =
            when {
                longerPlaceable.width < constraints.maxWidth &&
                    longerPlaceable.height <= constraints.maxHeight -> longerPlaceable
                shorterPlaceable.width < constraints.maxWidth &&
                    shorterPlaceable.height <= constraints.maxHeight -> shorterPlaceable
                else -> null
            }

        layout(placeable?.width ?: 0, placeable?.height ?: 0) { placeable?.placeRelative(0, 0) }
    }
}
