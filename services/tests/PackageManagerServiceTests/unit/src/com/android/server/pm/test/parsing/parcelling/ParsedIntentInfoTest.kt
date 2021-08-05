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

import android.content.pm.parsing.component.ParsedIntentInfo
import android.os.Parcel
import android.os.Parcelable
import android.os.PatternMatcher
import kotlin.contracts.ExperimentalContracts

@ExperimentalContracts
class ParsedIntentInfoTest : ParcelableComponentTest(ParsedIntentInfo::class) {

    override val defaultImpl = ParsedIntentInfo()

    override val creator = object : Parcelable.Creator<ParsedIntentInfo> {
        override fun createFromParcel(source: Parcel) = ParsedIntentInfo(source)
        override fun newArray(size: Int) = Array<ParsedIntentInfo?>(size) { null }
    }

    override val excludedMethods = listOf(
        // Used to parcel
        "writeIntentInfoToParcel",
        // All remaining IntentFilter methods, which are out of scope
        "hasDataPath",
        "hasDataSchemeSpecificPart",
        "matchAction",
        "matchData",
        "actionsIterator",
        "addAction",
        "addCategory",
        "addDataAuthority",
        "addDataPath",
        "addDataScheme",
        "addDataSchemeSpecificPart",
        "addDataType",
        "addDynamicDataType",
        "addMimeGroup",
        "asPredicate",
        "asPredicateWithTypeResolution",
        "authoritiesIterator",
        "categoriesIterator",
        "clearDynamicDataTypes",
        "countActions",
        "countCategories",
        "countDataAuthorities",
        "countDataPaths",
        "countDataSchemeSpecificParts",
        "countDataSchemes",
        "countDataTypes",
        "countMimeGroups",
        "countStaticDataTypes",
        "dataTypes",
        "debugCheck",
        "dump",
        "dumpDebug",
        "getAction",
        "getAutoVerify",
        "getCategory",
        "getDataAuthority",
        "getDataPath",
        "getDataScheme",
        "getDataSchemeSpecificPart",
        "getDataType",
        "getHosts",
        "getHostsList",
        "getMimeGroup",
        "getOrder",
        "getPriority",
        "getVisibilityToInstantApp",
        "handleAllWebDataURI",
        "handlesWebUris",
        "hasAction",
        "hasCategory",
        "hasDataAuthority",
        "hasDataScheme",
        "hasDataType",
        "hasExactDataType",
        "hasExactDynamicDataType",
        "hasExactStaticDataType",
        "hasMimeGroup",
        "isExplicitlyVisibleToInstantApp",
        "isImplicitlyVisibleToInstantApp",
        "isVerified",
        "isVisibleToInstantApp",
        "match",
        "matchCategories",
        "matchDataAuthority",
        "mimeGroupsIterator",
        "needsVerification",
        "pathsIterator",
        "readFromXml",
        "schemeSpecificPartsIterator",
        "schemesIterator",
        "setAutoVerify",
        "setOrder",
        "setPriority",
        "setVerified",
        "setVisibilityToInstantApp",
        "typesIterator",
        "writeToXml",
    )

    override val baseParams = listOf(
        ParsedIntentInfo::getIcon,
        ParsedIntentInfo::getLabelRes,
        ParsedIntentInfo::isHasDefault,
        ParsedIntentInfo::getNonLocalizedLabel,
    )

    override fun initialObject() = ParsedIntentInfo().apply {
        addAction("test.ACTION")
        addDataAuthority("testAuthority", "404")
        addCategory("test.CATEGORY")
        addMimeGroup("testMime")
        addDataPath("testPath", PatternMatcher.PATTERN_LITERAL)
    }

    override fun extraAssertions(before: Parcelable, after: Parcelable) {
        super.extraAssertions(before, after)
        after as ParsedIntentInfo
        expect.that(after.actionsIterator().asSequence().singleOrNull())
            .isEqualTo("test.ACTION")

        val authority = after.authoritiesIterator().asSequence().singleOrNull()
        expect.that(authority?.host).isEqualTo("testAuthority")
        expect.that(authority?.port).isEqualTo(404)

        expect.that(after.categoriesIterator().asSequence().singleOrNull())
            .isEqualTo("test.CATEGORY")
        expect.that(after.mimeGroupsIterator().asSequence().singleOrNull())
            .isEqualTo("testMime")
        expect.that(after.hasDataPath("testPath")).isTrue()
    }

    override fun writeToParcel(parcel: Parcel, value: Parcelable) =
        ParsedIntentInfo.PARCELER.parcel(value as ParsedIntentInfo, parcel, 0)
}
