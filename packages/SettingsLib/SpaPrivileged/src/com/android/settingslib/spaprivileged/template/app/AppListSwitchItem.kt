package com.android.settingslib.spaprivileged.template.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel
import com.android.settingslib.spa.widget.preference.TwoTargetSwitchPreference
import com.android.settingslib.spaprivileged.model.app.AppRecord

@Composable
fun <T : AppRecord> AppListItemModel<T>.AppListSwitchItem(
    onClick: () -> Unit,
    checked: State<Boolean?>,
    changeable: State<Boolean>,
    onCheckedChange: ((newChecked: Boolean) -> Unit)?,
) {
    TwoTargetSwitchPreference(
        model = remember {
            object : SwitchPreferenceModel {
                override val title = label
                override val summary = this@AppListSwitchItem.summary
                override val checked = checked
                override val changeable = changeable
                override val onCheckedChange = onCheckedChange
            }
        },
        icon = { AppIcon(record.app, SettingsDimension.appIconItemSize) },
        onClick = onClick,
    )
}
