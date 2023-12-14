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

import com.android.internal.pm.pkg.component.ParsedPermission
import com.android.internal.pm.pkg.component.ParsedPermissionGroup
import com.android.internal.pm.pkg.component.ParsedPermissionGroupImpl
import com.android.internal.pm.pkg.component.ParsedPermissionImpl
import kotlin.contracts.ExperimentalContracts

@ExperimentalContracts
class ParsedPermissionTest : ParsedComponentTest(
    ParsedPermission::class,
    ParsedPermissionImpl::class
) {

    override val defaultImpl =
        ParsedPermissionImpl()
    override val creator = ParsedPermissionImpl.CREATOR

    override val subclassExcludedMethods = listOf(
        // Utility methods
        "isAppOp",
        "isRuntime",
        "getProtection",
        "getProtectionFlags",
        "calculateFootprint",
        "setKnownCert", // Tested through setKnownCerts
    )
    override val subclassBaseParams = listOf(
        ParsedPermission::getBackgroundPermission,
        ParsedPermission::getGroup,
        ParsedPermission::getRequestRes,
        ParsedPermission::getProtectionLevel,
        ParsedPermission::isTree,
    )

    override fun subclassExtraParams() = listOf(
        getter(ParsedPermission::getKnownCerts, setOf("testCert")),
        getSetByValue(
            ParsedPermission::getParsedPermissionGroup,
            ParsedPermissionImpl::setParsedPermissionGroup,
            ParsedPermissionGroupImpl()
                .apply { name = "test.permission.group" },
            compare = { first, second -> equalBy(first, second, ParsedPermissionGroup::getName) }
        ),
    )
}
