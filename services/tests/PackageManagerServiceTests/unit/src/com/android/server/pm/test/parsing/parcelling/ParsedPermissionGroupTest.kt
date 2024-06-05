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

import com.android.internal.pm.pkg.component.ParsedPermissionGroup
import com.android.internal.pm.pkg.component.ParsedPermissionGroupImpl
import kotlin.contracts.ExperimentalContracts

@ExperimentalContracts
class ParsedPermissionGroupTest : ParsedComponentTest(
    ParsedPermissionGroup::class,
    ParsedPermissionGroupImpl::class,
) {

    override val defaultImpl =
        ParsedPermissionGroupImpl()
    override val creator = ParsedPermissionGroupImpl.CREATOR

    override val subclassBaseParams = listOf(
        ParsedPermissionGroup::getRequestDetailRes,
        ParsedPermissionGroup::getBackgroundRequestDetailRes,
        ParsedPermissionGroup::getBackgroundRequestRes,
        ParsedPermissionGroup::getRequestRes,
        ParsedPermissionGroup::getPriority,
    )
}
