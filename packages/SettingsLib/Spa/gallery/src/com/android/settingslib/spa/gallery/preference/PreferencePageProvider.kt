/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.settingslib.spa.gallery.preference

import android.os.Bundle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.DisabledByDefault
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.settingslib.spa.framework.common.EntrySearchData
import com.android.settingslib.spa.framework.common.EntryStatusData
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SettingsEntryBuilder
import com.android.settingslib.spa.framework.common.SettingsPageProvider
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import com.android.settingslib.spa.framework.common.createSettingsPage
import com.android.settingslib.spa.framework.theme.SettingsTheme
import com.android.settingslib.spa.gallery.R
import com.android.settingslib.spa.gallery.SettingsPageProviderEnum
import com.android.settingslib.spa.gallery.preference.PreferencePageModel.Companion.ASYNC_PREFERENCE_TITLE
import com.android.settingslib.spa.gallery.preference.PreferencePageModel.Companion.AUTO_UPDATE_PREFERENCE_TITLE
import com.android.settingslib.spa.gallery.preference.PreferencePageModel.Companion.DISABLE_PREFERENCE_SUMMARY
import com.android.settingslib.spa.gallery.preference.PreferencePageModel.Companion.DISABLE_PREFERENCE_TITLE
import com.android.settingslib.spa.gallery.preference.PreferencePageModel.Companion.MANUAL_UPDATE_PREFERENCE_TITLE
import com.android.settingslib.spa.gallery.preference.PreferencePageModel.Companion.PAGE_TITLE
import com.android.settingslib.spa.gallery.preference.PreferencePageModel.Companion.SIMPLE_PREFERENCE_KEYWORDS
import com.android.settingslib.spa.gallery.preference.PreferencePageModel.Companion.SIMPLE_PREFERENCE_SUMMARY
import com.android.settingslib.spa.gallery.preference.PreferencePageModel.Companion.SIMPLE_PREFERENCE_TITLE
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import com.android.settingslib.spa.widget.preference.SimplePreferenceMacro
import com.android.settingslib.spa.widget.ui.SettingsIcon

private const val TAG = "PreferencePage"

object PreferencePageProvider : SettingsPageProvider {
    // Defines all entry name in this page.
    // Note that entry name would be used in log. DO NOT change it once it is set.
    // One can still change the display name for better readability if necessary.
    enum class EntryEnum(val displayName: String) {
        SIMPLE_PREFERENCE("preference"),
        SUMMARY_PREFERENCE("preference_with_summary"),
        SINGLE_LINE_SUMMARY_PREFERENCE("preference_with_single_line_summary"),
        DISABLED_PREFERENCE("preference_disable"),
        ASYNC_SUMMARY_PREFERENCE("preference_with_async_summary"),
        MANUAL_UPDATE_PREFERENCE("preference_actionable"),
        AUTO_UPDATE_PREFERENCE("preference_auto_update"),
    }

    override val name = SettingsPageProviderEnum.PREFERENCE.name
    override val displayName = SettingsPageProviderEnum.PREFERENCE.displayName
    private val spaLogger = SpaEnvironmentFactory.instance.logger
    private val owner = createSettingsPage()

    private fun createEntry(entry: EntryEnum): SettingsEntryBuilder {
        return SettingsEntryBuilder.create(owner, entry.name, entry.displayName)
    }

    override fun buildEntry(arguments: Bundle?): List<SettingsEntry> {
        val entryList = mutableListOf<SettingsEntry>()
        entryList.add(
            createEntry(EntryEnum.SIMPLE_PREFERENCE)
                .setMacro {
                    spaLogger.message(TAG, "create macro for ${EntryEnum.SIMPLE_PREFERENCE}")
                    SimplePreferenceMacro(title = SIMPLE_PREFERENCE_TITLE)
                }
                .setStatusDataFn { EntryStatusData(isDisabled = false) }
                .build()
        )
        entryList.add(
            createEntry(EntryEnum.SUMMARY_PREFERENCE)
                .setMacro {
                    spaLogger.message(TAG, "create macro for ${EntryEnum.SUMMARY_PREFERENCE}")
                    SimplePreferenceMacro(
                        title = SIMPLE_PREFERENCE_TITLE,
                        summary = SIMPLE_PREFERENCE_SUMMARY,
                        searchKeywords = SIMPLE_PREFERENCE_KEYWORDS,
                    )
                }
                .setStatusDataFn { EntryStatusData(isDisabled = true) }
                .build()
        )
        entryList.add(singleLineSummaryEntry())
        entryList.add(
            createEntry(EntryEnum.DISABLED_PREFERENCE)
                .setHasMutableStatus(true)
                .setMacro {
                    spaLogger.message(TAG, "create macro for ${EntryEnum.DISABLED_PREFERENCE}")
                    SimplePreferenceMacro(
                        title = DISABLE_PREFERENCE_TITLE,
                        summary = DISABLE_PREFERENCE_SUMMARY,
                        disabled = true,
                        icon = Icons.Outlined.DisabledByDefault,
                    )
                }
                .setStatusDataFn { EntryStatusData(isDisabled = true) }
                .build()
        )
        entryList.add(
            createEntry(EntryEnum.ASYNC_SUMMARY_PREFERENCE)
                .setHasMutableStatus(true)
                .setSearchDataFn {
                    EntrySearchData(title = ASYNC_PREFERENCE_TITLE)
                }
                .setStatusDataFn { EntryStatusData(isDisabled = false) }
                .setUiLayoutFn {
                    val model = PreferencePageModel.create()
                    Preference(
                        object : PreferenceModel {
                            override val title = ASYNC_PREFERENCE_TITLE
                            override val summary = { model.asyncSummary.value }
                            override val enabled = { model.asyncEnable.value }
                        }
                    )
                }.build()
        )
        entryList.add(
            createEntry(EntryEnum.MANUAL_UPDATE_PREFERENCE)
                .setUiLayoutFn {
                    val model = PreferencePageModel.create()
                    val manualUpdaterSummary = remember { model.getManualUpdaterSummary() }
                    Preference(
                        object : PreferenceModel {
                            override val title = MANUAL_UPDATE_PREFERENCE_TITLE
                            override val summary = { manualUpdaterSummary.value }
                            override val onClick = { model.manualUpdaterOnClick() }
                            override val icon = @Composable {
                                SettingsIcon(imageVector = Icons.Outlined.TouchApp)
                            }
                        }
                    )
                }.build()
        )
        entryList.add(
            createEntry(EntryEnum.AUTO_UPDATE_PREFERENCE)
                .setUiLayoutFn {
                    val model = PreferencePageModel.create()
                    val autoUpdaterSummary = remember {
                        model.getAutoUpdaterSummary()
                    }.observeAsState(" ")
                    Preference(
                        object : PreferenceModel {
                            override val title = AUTO_UPDATE_PREFERENCE_TITLE
                            override val summary = { autoUpdaterSummary.value }
                            override val icon = @Composable {
                                SettingsIcon(imageVector = Icons.Outlined.Autorenew)
                            }
                        }
                    )
                }.build()
        )

        return entryList
    }

    private fun singleLineSummaryEntry() = createEntry(EntryEnum.SINGLE_LINE_SUMMARY_PREFERENCE)
        .setUiLayoutFn {
            val summary = stringResource(R.string.single_line_summary_preference_summary)
            Preference(
                model = object : PreferenceModel {
                    override val title: String =
                        stringResource(R.string.single_line_summary_preference_title)
                    override val summary = { summary }
                },
                singleLineSummary = true,
            )
        }
        .build()

    fun buildInjectEntry(): SettingsEntryBuilder {
        return SettingsEntryBuilder.createInject(owner = owner)
            .setMacro {
                spaLogger.message(TAG, "create macro for INJECT entry")
                SimplePreferenceMacro(
                    title = PAGE_TITLE,
                    clickRoute = SettingsPageProviderEnum.PREFERENCE.name
                )
            }
    }

    override fun getTitle(arguments: Bundle?): String {
        return PAGE_TITLE
    }
}

@Preview(showBackground = true)
@Composable
private fun PreferencePagePreview() {
    SpaEnvironmentFactory.resetForPreview()
    SettingsTheme {
        PreferencePageProvider.Page(null)
    }
}
