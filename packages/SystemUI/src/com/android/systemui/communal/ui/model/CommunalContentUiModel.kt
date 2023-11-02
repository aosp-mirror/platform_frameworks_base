package com.android.systemui.communal.ui.model

import android.view.View
import com.android.systemui.communal.shared.model.CommunalContentSize

/**
 * Encapsulates data for a communal content that holds a view.
 *
 * This model stays in the UI layer.
 */
data class CommunalContentUiModel(
    val id: String,
    val view: View,
    val size: CommunalContentSize = CommunalContentSize.HALF,
)
