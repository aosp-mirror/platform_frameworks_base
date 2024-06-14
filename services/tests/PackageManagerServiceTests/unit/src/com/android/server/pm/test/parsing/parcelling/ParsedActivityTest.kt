/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm.test.parsing.parcelling

import android.content.pm.ActivityInfo
import com.android.internal.pm.pkg.component.ParsedActivity
import com.android.internal.pm.pkg.component.ParsedActivityImpl
import kotlin.contracts.ExperimentalContracts

@ExperimentalContracts
class ParsedActivityTest : ParsedMainComponentTest(
    ParsedActivity::class,
    ParsedActivityImpl::class
) {

    override val defaultImpl =
        ParsedActivityImpl()
    override val creator = ParsedActivityImpl.CREATOR

    override val mainComponentSubclassBaseParams = listOf(
        ParsedActivity::getPermission,
        ParsedActivity::getColorMode,
        ParsedActivity::getConfigChanges,
        ParsedActivity::getDocumentLaunchMode,
        ParsedActivity::getLaunchMode,
        ParsedActivity::getLockTaskLaunchMode,
        ParsedActivity::getMaxAspectRatio,
        ParsedActivity::getMaxRecents,
        ParsedActivity::getMinAspectRatio,
        ParsedActivity::getParentActivityName,
        ParsedActivity::getPersistableMode,
        ParsedActivity::getPrivateFlags,
        ParsedActivity::getRequestedVrComponent,
        ParsedActivity::getResizeMode,
        ParsedActivity::getRotationAnimation,
        ParsedActivity::getScreenOrientation,
        ParsedActivity::getSoftInputMode,
        ParsedActivity::getTargetActivity,
        ParsedActivity::getTaskAffinity,
        ParsedActivity::getTheme,
        ParsedActivity::getUiOptions,
        ParsedActivity::isSupportsSizeChanges,
        ParsedActivity::getRequiredDisplayCategory,
        ParsedActivity::getRequireContentUriPermissionFromCaller
    )

    override fun mainComponentSubclassExtraParams() = listOf(
        getSetByValue(
            ParsedActivity::getWindowLayout,
            ParsedActivityImpl::setWindowLayout,
            ActivityInfo.WindowLayout(1, 1f, 2, 1f, 3, 4, 5),
            compare = { first, second ->
                equalBy(
                    first, second,
                    ActivityInfo.WindowLayout::width,
                    ActivityInfo.WindowLayout::widthFraction,
                    ActivityInfo.WindowLayout::height,
                    ActivityInfo.WindowLayout::heightFraction,
                    ActivityInfo.WindowLayout::gravity,
                    ActivityInfo.WindowLayout::minWidth,
                    ActivityInfo.WindowLayout::minHeight
                )
            }
        ),
        getter(ParsedActivity::getKnownActivityEmbeddingCerts, setOf("TESTEMBEDDINGCERT"))
    )
}
