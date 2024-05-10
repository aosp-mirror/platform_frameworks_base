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

import android.content.pm.PathPermission
import android.os.PatternMatcher
import com.android.internal.pm.pkg.component.ParsedProvider
import com.android.internal.pm.pkg.component.ParsedProviderImpl
import kotlin.contracts.ExperimentalContracts

@ExperimentalContracts
class ParsedProviderTest : ParsedMainComponentTest(ParsedProvider::class, ParsedProviderImpl::class)
{
    override val defaultImpl =
        ParsedProviderImpl()
    override val creator = ParsedProviderImpl.CREATOR

    override val mainComponentSubclassBaseParams = listOf(
        ParsedProvider::getAuthority,
        ParsedProvider::isSyncable,
        ParsedProvider::getReadPermission,
        ParsedProvider::getWritePermission,
        ParsedProvider::isGrantUriPermissions,
        ParsedProvider::isForceUriPermissions,
        ParsedProvider::isMultiProcess,
        ParsedProvider::getInitOrder,
    )

    override fun mainComponentSubclassExtraParams() = listOf(
        getSetByValue(
            ParsedProvider::getUriPermissionPatterns,
            ParsedProviderImpl::addUriPermissionPattern,
            PatternMatcher("testPattern", PatternMatcher.PATTERN_LITERAL),
            transformGet = { it.singleOrNull() },
            compare = { first, second ->
                equalBy(
                    first, second,
                    PatternMatcher::getPath,
                    PatternMatcher::getType
                )
            }
        ),
        getSetByValue(
            ParsedProvider::getPathPermissions,
            ParsedProviderImpl::addPathPermission,
            PathPermission(
                "testPermissionPattern",
                PatternMatcher.PATTERN_LITERAL,
                "test.READ_PERMISSION",
                "test.WRITE_PERMISSION"
            ),
            transformGet = { it.singleOrNull() },
            compare = { first, second ->
                equalBy(
                    first, second,
                    PatternMatcher::getPath,
                    PatternMatcher::getType,
                    PathPermission::getReadPermission,
                    PathPermission::getWritePermission,
                )
            }
        )
    )
}
