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

import com.android.server.pm.pkg.component.ParsedIntentInfo
import com.android.server.pm.pkg.component.ParsedIntentInfoImpl
import android.os.Parcelable
import android.os.PatternMatcher
import kotlin.contracts.ExperimentalContracts

@ExperimentalContracts
class ParsedIntentInfoTest : ParcelableComponentTest(
    ParsedIntentInfo::class,
    ParsedIntentInfoImpl::class,
) {

    override val defaultImpl =
        ParsedIntentInfoImpl()
    override val creator = ParsedIntentInfoImpl.CREATOR

    override val excludedMethods = listOf(
        // Tested through extraAssertions, since this isn't a settable field
        "getIntentFilter"
    )

    override val baseParams = listOf(
        ParsedIntentInfo::getIcon,
        ParsedIntentInfo::getLabelRes,
        ParsedIntentInfo::isHasDefault,
        ParsedIntentInfo::getNonLocalizedLabel,
    )

    override fun initialObject() = ParsedIntentInfoImpl()
        .apply {
        intentFilter.apply {
            addAction("test.ACTION")
            addDataAuthority("testAuthority", "404")
            addCategory("test.CATEGORY")
            addMimeGroup("testMime")
            addDataPath("testPath", PatternMatcher.PATTERN_LITERAL)
        }
    }

    override fun extraAssertions(before: Parcelable, after: Parcelable) {
        super.extraAssertions(before, after)
        val intentFilter = (after as ParsedIntentInfo).intentFilter
        expect.that(intentFilter.actionsIterator().asSequence().singleOrNull())
            .isEqualTo("test.ACTION")

        val authority = intentFilter.authoritiesIterator().asSequence().singleOrNull()
        expect.that(authority?.host).isEqualTo("testAuthority")
        expect.that(authority?.port).isEqualTo(404)

        expect.that(intentFilter.categoriesIterator().asSequence().singleOrNull())
            .isEqualTo("test.CATEGORY")
        expect.that(intentFilter.mimeGroupsIterator().asSequence().singleOrNull())
            .isEqualTo("testMime")
        expect.that(intentFilter.hasDataPath("testPath")).isTrue()
    }
}
