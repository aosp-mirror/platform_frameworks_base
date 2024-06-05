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

import android.util.ArrayMap
import com.android.internal.pm.pkg.component.ParsedProcess
import com.android.internal.pm.pkg.component.ParsedProcessImpl
import kotlin.contracts.ExperimentalContracts

@ExperimentalContracts
class ParsedProcessTest : ParcelableComponentTest(ParsedProcess::class, ParsedProcessImpl::class) {

    override val defaultImpl =
        ParsedProcessImpl()
    override val creator = ParsedProcessImpl.CREATOR

    override val excludedMethods = listOf(
        // Copying method
        "addStateFrom",
        // Utility method
        "putAppClassNameForPackage",
    )

    override val baseParams = listOf(
        ParsedProcess::getName,
        ParsedProcess::getGwpAsanMode,
        ParsedProcess::getMemtagMode,
        ParsedProcess::getNativeHeapZeroInitialized,
        ParsedProcess::isUseEmbeddedDex,
    )

    override fun extraParams() = listOf(
        getter(ParsedProcess::getDeniedPermissions, setOf("testDeniedPermission")),
        getter(ParsedProcess::getAppClassNamesByPackage, ArrayMap<String, String>().apply {
            put("package1", "classname1")
        }),
    )
}
