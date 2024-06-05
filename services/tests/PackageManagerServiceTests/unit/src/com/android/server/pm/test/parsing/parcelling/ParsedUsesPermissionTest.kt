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

import com.android.internal.pm.pkg.component.ParsedUsesPermission
import com.android.internal.pm.pkg.component.ParsedUsesPermissionImpl
import kotlin.contracts.ExperimentalContracts

@ExperimentalContracts
class ParsedUsesPermissionTest : ParcelableComponentTest(
    ParsedUsesPermission::class,
    ParsedUsesPermissionImpl::class
) {

    override val defaultImpl =
        ParsedUsesPermissionImpl("", 0)
    override val creator = ParsedUsesPermissionImpl.CREATOR

    override val baseParams = listOf(
        ParsedUsesPermission::getName,
        ParsedUsesPermission::getUsesPermissionFlags
    )

    override fun initialObject() =
        ParsedUsesPermissionImpl("", 0)
}
