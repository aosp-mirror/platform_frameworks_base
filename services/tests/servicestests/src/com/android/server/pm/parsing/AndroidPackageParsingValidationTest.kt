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

package com.android.server.pm.parsing

import android.content.res.Validator
import android.os.Environment
import android.platform.test.annotations.Postsubmit
import com.android.internal.R
import com.android.server.pm.PackageManagerService
import com.android.server.pm.pkg.parsing.ParsingPackageUtils
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File

@Postsubmit
class AndroidPackageParsingValidationTest {
    companion object {
        private val parser2 = PackageParser2.forParsingFileWithDefaults()
        private val apks = ((PackageManagerService.SYSTEM_PARTITIONS)
                .flatMap {
                    listOfNotNull(it.privAppFolder, it.appFolder, it.overlayFolder)
                } + File(Environment.getRootDirectory(), "framework"))
                .flatMap {
                    it.walkTopDown()
                            .filter { file -> file.name.endsWith(".apk") }
                            .toList()
                }
                .distinct()
        private val pullParser: XmlPullParser = run {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            factory.newPullParser()
        }
        private val ns = "xmlns:android=\"http://schemas.android.com/apk/res/android\""
    }

    @Test
    fun parseExistingApks_NoParseFailures() {
        val failedParsePackages = mutableListOf<File>()
        for (apk in apks) {
            try {
                parser2.parsePackage(apk, ParsingPackageUtils.PARSE_IS_SYSTEM_DIR, false)
            } catch (e: Exception) {
                if (e.message!!.startsWith("Failed to parse")) {
                    failedParsePackages.add(apk)
                } else if (e.message!!.startsWith("Skipping target and overlay pair")) {
                    // ignore
                } else {
                    throw e
                }
            }
        }
        assertThat(failedParsePackages).isEmpty()
    }

    @Test
    fun parseBadManifests() {
        val tag = "manifest"
        val prefix = "<manifest $ns>"
        val suffix = "</manifest>"
        parseTagBadAttr(tag, "package", 256, )
        parseTagBadAttr(tag, "android:sharedUserId", 256)
        parseTagBadAttr(tag, "android:versionName", 4000)
        parseBadApplicationTags(100, prefix, suffix, tag)
        parseBadOverlayTags(100, prefix, suffix, tag)
        parseBadInstrumentationTags(100, prefix, suffix, tag)
        parseBadPermissionGroupTags(100, prefix, suffix, tag)
        parseBadPermissionTreeTags(100, prefix, suffix, tag)
        parseBadSupportsGlTextureTags(100, prefix, suffix, tag)
        parseBadSupportsScreensTags(100, prefix, suffix, tag)
        parseBadUsesConfigurationTags(100, prefix, suffix, tag)
        parseBadUsesPermissionSdk23Tags(100, prefix, suffix, tag)
        parseBadUsesSdkTags(100, prefix, suffix, tag)
        parseBadCompatibleScreensTags(200, prefix, suffix, tag)
        parseBadQueriesTags(200, prefix, suffix, tag)
        parseBadAttributionTags(400, prefix, suffix, tag)
        parseBadUsesFeatureTags(400, prefix, suffix, tag)
        parseBadPermissionTags(2000, prefix, suffix, tag)
        parseBadUsesPermissionTags(20000, prefix, suffix, tag)
    }

    private fun parseBadApplicationTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "application"
        val newPrefix = "$prefix<$tag>"
        val newSuffix = "</$tag>$suffix"

        parseTagBadAttr(tag, "android:backupAgent", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:manageSpaceActivity", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:name", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:permission", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:process", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:requiredAccountType", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:restrictedAccountType", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:taskAffinity", 1024, prefix, suffix)
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)

        parseBadProfileableTags(100, newPrefix, newSuffix, tag)
        parseBadUsesNativeLibraryTags(100, newPrefix, newSuffix, tag)
        parseBadReceiverTags(1000, newPrefix, newSuffix, tag)
        parseBadServiceTags(1000, newPrefix, newSuffix, tag)
        parseBadActivityAliasTags(4000, newPrefix, newSuffix, tag)
        parseBadUsesLibraryTags(4000, newPrefix, newSuffix, tag)
        parseBadProviderTags(8000, newPrefix, newSuffix, tag)
        parseBadMetaDataTags(8000, newPrefix, newSuffix, tag)
        parseBadActivityTags(40000, newPrefix, newSuffix, tag)
    }

    private fun parseBadProfileableTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "profileable"
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
    }

    private fun parseBadUsesNativeLibraryTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "uses-native-library"
        parseTagBadAttr(tag, "android:name", 1024, prefix, suffix)
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
    }

    private fun parseBadReceiverTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "receiver"
        val newPrefix = "$prefix<$tag>"
        val newSuffix = "</$tag>$suffix"
        parseTagBadAttr(tag, "android:name", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:permission", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:process", 1024, prefix, suffix)

        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
        parseBadMetaDataTags(8000, newPrefix, newSuffix, tag)
        parseBadIntentFilterTags(20000, newPrefix, newSuffix, tag)
    }

    private fun parseBadServiceTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "service"
        val newPrefix = "$prefix<$tag>"
        val newSuffix = "</$tag>$suffix"
        parseTagBadAttr(tag, "android:name", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:permission", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:process", 1024, prefix, suffix)

        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
        parseBadMetaDataTags(8000, newPrefix, newSuffix, tag)
        parseBadIntentFilterTags(20000, newPrefix, newSuffix, tag)
    }

    private fun parseBadActivityAliasTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "activity-alias"
        val newPrefix = "$prefix<$tag>"
        val newSuffix = "</$tag>$suffix"
        parseTagBadAttr(tag, "android:name", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:permission", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:targetActivity", 1024, prefix, suffix)
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
        parseBadMetaDataTags(8000, newPrefix, newSuffix, tag)
        parseBadIntentFilterTags(20000, newPrefix, newSuffix, tag)
    }

    private fun parseBadUsesLibraryTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "uses-library"
        parseTagBadAttr(tag, "android:name", 1024, prefix, suffix)
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
    }

    private fun parseBadActivityTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "activity"
        val newPrefix = "$prefix<$tag>"
        val newSuffix = "</$tag>$suffix"
        parseTagBadAttr(tag, "android:name", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:parentActivityName", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:permission", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:process", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:taskAffinity", 1024, prefix, suffix)
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
        parseBadLayoutTags(1000, newPrefix, newSuffix, tag)
        parseBadMetaDataTags(8000, newPrefix, newSuffix, tag)
        parseBadIntentFilterTags(20000, newPrefix, newSuffix, tag)
    }

    private fun parseBadLayoutTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "layout"
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
    }

    private fun parseBadOverlayTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "overlay"
        parseTagBadAttr(tag, "android:category", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:requiredSystemPropertyName", 32768, prefix, suffix)
        parseTagBadAttr(tag, "android:requiredSystemPropertyValue", 256, prefix, suffix)
        parseTagBadAttr(tag, "android:targetPackage", 256, prefix, suffix)
        parseTagBadAttr(tag, "android:targetName", 1024, prefix, suffix)
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
    }

    private fun parseBadInstrumentationTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "instrumentation"
        parseTagBadAttr(tag, "android:name", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:targetPackage", 256, prefix, suffix)
        parseTagBadAttr(tag, "android:targetProcesses", 1024, prefix, suffix)
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
    }

    private fun parseBadPermissionGroupTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "permission-group"
        parseTagBadAttr(tag, "android:name", 1024, prefix, suffix)
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
    }

    private fun parseBadPermissionTreeTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "permission-tree"
        parseTagBadAttr(tag, "android:name", 1024, prefix, suffix)
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
    }

    private fun parseBadSupportsGlTextureTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "supports-gl-texture"
        parseTagBadAttr(tag, "android:name", 1024, prefix, suffix)
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
    }

    private fun parseBadSupportsScreensTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "supports-screens"
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
    }

    private fun parseBadUsesConfigurationTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "uses-configuration"
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
    }

    private fun parseBadUsesPermissionSdk23Tags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "uses-permission-sdk-23"
        parseTagBadAttr(tag, "android:name", 1024, prefix, suffix)
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
    }

    private fun parseBadUsesSdkTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "uses-sdk"
        parseTagBadAttr(tag, "android:name", 1024, prefix, suffix)
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
    }

    private fun parseBadCompatibleScreensTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "compatible-screens"
        val newPrefix = "$prefix<$tag>"
        val newSuffix = "</$tag>$suffix"
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
        parseBadScreenTags(4000, newPrefix, newSuffix, tag)
    }

    private fun parseBadScreenTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "screen"
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
    }

    private fun parseBadQueriesTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "queries"
        val newPrefix = "$prefix<$tag>"
        val newSuffix = "</$tag>$suffix"
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
        parseBadPackageTags(1000, newPrefix, newSuffix, tag)
        parseBadIntentTags(2000, newPrefix, newSuffix, tag)
        parseBadProviderTags(8000, newPrefix, newSuffix, tag)
    }

    private fun parseBadPackageTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "package"
        parseTagBadAttr(tag, "android:name", 1024, prefix, suffix)
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
    }

    private fun parseBadIntentTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "intent"
        val newPrefix = "$prefix<$tag>"
        val newSuffix = "</$tag>$suffix"
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
        parseBadActionTags(20000, newPrefix, newSuffix, tag)
        parseBadCategoryTags(40000, newPrefix, newSuffix, tag)
        parseBadDataTags(40000, newPrefix, newSuffix, tag)
    }

    private fun parseBadProviderTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "provider"
        val newPrefix = "$prefix<$tag>"
        val newSuffix = "</$tag>$suffix"
        parseTagBadAttr(tag, "android:name", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:permission", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:process", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:readPermission", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:writePermission", 1024, prefix, suffix)
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
        parseBadGrantUriPermissionTags(100, newPrefix, newSuffix, tag)
        parseBadPathPermissionTags(100, newPrefix, newSuffix, tag)
        parseBadMetaDataTags(8000, newPrefix, newSuffix, tag)
        parseBadIntentFilterTags(20000, newPrefix, newSuffix, tag)
    }

    private fun parseBadGrantUriPermissionTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "grant-uri-permission"
        parseTagBadAttr(tag, "android:path", 4000, prefix, suffix)
        parseTagBadAttr(tag, "android:pathPrefix", 4000, prefix, suffix)
        parseTagBadAttr(tag, "android:pathPattern", 4000, prefix, suffix)
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
    }

    private fun parseBadPathPermissionTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "path-permission"
        parseTagBadAttr(tag, "android:path", 4000, prefix, suffix)
        parseTagBadAttr(tag, "android:pathPrefix", 4000, prefix, suffix)
        parseTagBadAttr(tag, "android:pathPattern", 4000, prefix, suffix)
        parseTagBadAttr(tag, "android:permission", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:readPermission", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:writePermission", 1024, prefix, suffix)
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
    }

    private fun parseBadMetaDataTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "meta-data"
        parseTagBadAttr(tag, "android:name", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:value", 32768, prefix, suffix)
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
    }

    private fun parseBadIntentFilterTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "intent-filter"
        val newPrefix = "$prefix<$tag>"
        val newSuffix = "</$tag>$suffix"
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
        parseBadActionTags(20000, newPrefix, newSuffix, tag)
        parseBadCategoryTags(40000, newPrefix, newSuffix, tag)
        parseBadDataTags(40000, newPrefix, newSuffix, tag)
    }

    private fun parseBadActionTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "action"
        parseTagBadAttr(tag, "android:name", 1024, prefix, suffix)
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
    }

    private fun parseBadCategoryTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "category"
        parseTagBadAttr(tag, "android:name", 1024, prefix, suffix)
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
    }

    private fun parseBadDataTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "data"
        parseTagBadAttr(tag, "android:scheme", 256, prefix, suffix)
        parseTagBadAttr(tag, "android:host", 256, prefix, suffix)
        parseTagBadAttr(tag, "android:path", 4000, prefix, suffix)
        parseTagBadAttr(tag, "android:pathPattern", 4000, prefix, suffix)
        parseTagBadAttr(tag, "android:pathPrefix", 4000, prefix, suffix)
        parseTagBadAttr(tag, "android:pathSuffix", 4000, prefix, suffix)
        parseTagBadAttr(tag, "android:pathAdvancedPattern", 4000, prefix, suffix)
        parseTagBadAttr(tag, "android:mimeType", 512, prefix, suffix)
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
    }

    private fun parseBadAttributionTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "attribution"
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
    }

    private fun parseBadUsesFeatureTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "uses-feature"
        parseTagBadAttr(tag, "android:name", 1024, prefix, suffix)
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
    }

    private fun parseBadPermissionTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "permission"
        parseTagBadAttr(tag, "android:name", 1024, prefix, suffix)
        parseTagBadAttr(tag, "android:permissionGroup", 256, prefix, suffix)
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
    }

    private fun parseBadUsesPermissionTags(
            maxNum: Int,
            prefix: String,
            suffix: String,
            parentTag: String
    ) {
        val tag = "uses-permission"
        parseTagBadAttr(tag, "android:name", 1024, prefix, suffix)
        parseBadTagCount(tag, maxNum, parentTag, prefix, suffix)
    }

    private fun parseTagBadAttr(
            tag: String,
            attrName: String,
            maxLength: Int,
            prefix: String = "",
            suffix: String = ""
    ) {
        var attrValue = "x".repeat(maxLength)
        var tagValue = if (tag.equals("manifest")) "$tag $ns" else tag
        var manifestStr = "$prefix<$tagValue $attrName=\"$attrValue\" />$suffix"
        try {
            parseManifestStr(manifestStr)
        } catch (e: XmlPullParserException) {
            fail("Failed to parse valid <$tag> attribute $attrName with max length of $maxLength:" +
                    " ${e.message}")
        }
        attrValue = "x".repeat(maxLength + 1)
        manifestStr = "$prefix<$tagValue $attrName=\"$attrValue\" />$suffix"
        val e = assertThrows(XmlPullParserException::class.java) {
            parseManifestStr(manifestStr)
        }
        assertEquals(expectedAttrLengthErrorMsg(attrName.split(":").last(), tag), e.message)
    }

    private fun parseBadTagCount(
            tag: String,
            maxNum: Int,
            parentTag: String,
            prefix: String,
            suffix: String
    ) {
        var tags = "<$tag />".repeat(maxNum)
        var manifestStr = "$prefix$tags$suffix"
        try {
            parseManifestStr(manifestStr)
        } catch (e: XmlPullParserException) {
            fail("Failed to parse <$tag> with max count limit of $maxNum under" +
                    " <$parentTag>: ${e.message}")
        }
        tags = "<$tag />".repeat(maxNum + 1)
        manifestStr = "$prefix$tags$suffix"
        val e = assertThrows(XmlPullParserException::class.java) {
            parseManifestStr(manifestStr)
        }
        assertEquals(expectedCountErrorMsg(tag, parentTag), e.message)
    }

    @Test
    fun parseUnexpectedTag_shouldSkip() {
        val host = "x".repeat(256)
        val dataTags = "<data android:host=\"$host\" />".repeat(2049)
        val ns = "http://schemas.android.com/apk/res/android"
        val manifestStr = "<manifest xmlns:android=\"$ns\" package=\"test\">$dataTags</manifest>"
        parseManifestStr(manifestStr)
    }

    fun parseManifestStr(manifestStr: String) {
        pullParser.setInput(ByteArrayInputStream(manifestStr.toByteArray()), null)
        val validator = Validator()
        do {
            val type = pullParser.next()
            validator.validate(pullParser)
        } while (type != XmlPullParser.END_DOCUMENT)
    }

    fun expectedCountErrorMsg(tag: String, parentTag: String) =
            "The number of child $tag elements exceeded the max allowed in $parentTag"

    fun expectedAttrLengthErrorMsg(attr: String, tag: String) =
            "String length limit exceeded for attribute $attr in $tag"

    fun expectedResAttrLengthErrorMsg(tag: String) =
            "String length limit exceeded for attribute in $tag"

    @Test
    fun validateResAttrs() {
        pullParser.setInput(ByteArrayInputStream("<manifest />".toByteArray()), null)
        pullParser.next()
        val validator = Validator()
        validator.validate(pullParser)
        validateResAttr(pullParser, validator, R.styleable.AndroidManifestData_host,
                "R.styleable.AndroidManifestData_host", 255)
        validateResAttr(pullParser, validator, R.styleable.AndroidManifestData_port,
                "R.styleable.AndroidManifestData_port", 255)
        validateResAttr(pullParser, validator, R.styleable.AndroidManifestData_scheme,
                "R.styleable.AndroidManifestData_scheme", 255)
        validateResAttr(pullParser, validator, R.styleable.AndroidManifestData_mimeType,
                "R.styleable.AndroidManifestData_mimeType", 512)
    }

    fun validateResAttr(
            parser: XmlPullParser,
            validator: Validator,
            resId: Int,
            resIdStr: String,
            maxLength: Int
    ) {
        try {
            validator.validateAttr(parser, resId, "x".repeat(maxLength))
        } catch (e: XmlPullParserException) {
            fail("Failed to parse valid string resource attribute $resIdStr with max length of" +
                    " $maxLength: ${e.message}")
        }
        val e = assertThrows(XmlPullParserException::class.java) {
            validator.validateAttr(parser, resId, "x".repeat(maxLength + 1))
        }
        assertEquals(expectedResAttrLengthErrorMsg("manifest"), e.message)
    }
}