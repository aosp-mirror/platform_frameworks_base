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
import android.os.SystemProperties.PROP_VALUE_MAX
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
    fun parseManifestTag() {
        val tag = "manifest"
        validateTagAttr(tag, "package", null, 256)
        validateTagAttr(tag, "sharedUserId", null, 256)
        validateTagAttr(tag, "versionName", null, 1024)
        validateTagCount("application", 100, tag)
        validateTagCount("overlay", 100, tag)
        validateTagCount("instrumentation", 100, tag)
        validateTagCount("permission-group", 100, tag)
        validateTagCount("permission-tree", 100, tag)
        validateTagCount("supports-gl-texture", 100, tag)
        validateTagCount("supports-screens", 100, tag)
        validateTagCount("uses-configuration", 100, tag)
        validateTagCount("uses-sdk", 100, tag)
        validateTagCount("compatible-screens", 200, tag)
        validateTagCount("queries", 200, tag)
        validateTagCount("attribution", 400, tag)
        validateTagCount("uses-feature", 400, tag)
        validateTagCount("permission", 2000, tag)
        validateTagCount("uses-permission", 20000, tag)
    }

    @Test
    fun parseApplicationTag() {
        val tag = "application"
        validateTagAttr(
            tag,
            "backupAgent",
            R.styleable.AndroidManifestApplication_backupAgent,
            1024
        )
        validateTagAttrComponentName(
            tag,
            "backupAgent",
            R.styleable.AndroidManifestApplication_backupAgent
        )
        validateTagAttr(
            tag,
            "manageSpaceActivity",
            R.styleable.AndroidManifestApplication_manageSpaceActivity,
            1024
        )
        validateTagAttr(tag, "name", R.styleable.AndroidManifestApplication_name, 1024)
        validateTagAttrComponentName(tag, "name", R.styleable.AndroidManifestApplication_name)
        validateTagAttr(tag, "permission", R.styleable.AndroidManifestApplication_permission, 1024)
        validateTagAttr(tag, "process", R.styleable.AndroidManifestApplication_process, 1024)
        validateTagAttr(
            tag,
            "requiredAccountType",
            R.styleable.AndroidManifestApplication_requiredAccountType,
            1024
        )
        validateTagAttr(
            tag,
            "restrictedAccountType",
            R.styleable.AndroidManifestApplication_restrictedAccountType,
            1024
        )
        validateTagAttr(
            tag,
            "taskAffinity",
            R.styleable.AndroidManifestApplication_taskAffinity,
            1024
        )
        validateTagAttr(
            tag,
            "zygotePreloadName",
            R.styleable.AndroidManifestApplication_zygotePreloadName,
            1024
        )
        validateTagAttrComponentName(
            tag,
            "zygotePreloadName",
            R.styleable.AndroidManifestApplication_zygotePreloadName
        )
        validateTagCount("profileable", 100, tag)
        validateTagCount("uses-native-library", 100, tag)
        validateTagCount("receiver", 1000, tag)
        validateTagCount("service", 1000, tag)
        validateTagCount("meta-data", 1000, tag)
        validateTagCount("uses-library", 1000, tag)
        validateTagCount("activity-alias", 4000, tag)
        validateTagCount("provider", 8000, tag)
        validateTagCount("activity", 40000, tag)
    }

    @Test
    fun parseUsesNativeLibraryTag() {
        val tag = "uses-native-library"
        validateTagAttr(tag, "name", R.styleable.AndroidManifestUsesNativeLibrary_name, 1024)
    }

    @Test
    fun parseReceiverTag() {
        val tag = "receiver"
        validateTagAttr(tag, "name", R.styleable.AndroidManifestReceiver_name, 1024)
        validateTagAttrComponentName(tag, "name", R.styleable.AndroidManifestReceiver_name)
        validateTagAttr(tag, "permission", R.styleable.AndroidManifestReceiver_permission, 1024)
        validateTagAttr(tag, "process", R.styleable.AndroidManifestReceiver_process, 1024)
        validateTagCount("meta-data", 1000, tag)
        validateTagCount("intent-filter", 20000, tag)
    }

    @Test
    fun parseServiceTag() {
        val tag = "service"
        validateTagAttr(tag, "name", R.styleable.AndroidManifestService_name, 1024)
        validateTagAttrComponentName(tag, "name", R.styleable.AndroidManifestService_name)
        validateTagAttr(tag, "permission", R.styleable.AndroidManifestService_permission, 1024)
        validateTagAttr(tag, "process", R.styleable.AndroidManifestService_process, 1024)
        validateTagCount("meta-data", 1000, tag)
        validateTagCount("intent-filter", 20000, tag)
    }

    @Test
    fun parseActivityAliasTag() {
        val tag = "activity-alias"
        validateTagAttr(tag, "name", R.styleable.AndroidManifestActivityAlias_name, 1024)
        validateTagAttr(
            tag,
            "permission",
            R.styleable.AndroidManifestActivityAlias_permission,
            1024
        )
        validateTagAttr(
            tag,
            "targetActivity",
            R.styleable.AndroidManifestActivityAlias_targetActivity,
            1024
        )
        validateTagAttrComponentName(
            tag,
            "targetActivity",
            R.styleable.AndroidManifestActivityAlias_targetActivity
        )
        validateTagCount("meta-data", 1000, tag)
        validateTagCount("intent-filter", 20000, tag)
    }

    @Test
    fun parseUsesLibraryTag() {
        val tag = "uses-library"
        validateTagAttr(tag, "name", R.styleable.AndroidManifestUsesLibrary_name, 1024)
    }

    @Test
    fun parseActivityTag() {
        val tag = "activity"
        validateTagAttr(tag, "name", R.styleable.AndroidManifestActivity_name, 1024)
        validateTagAttrComponentName(tag, "name", R.styleable.AndroidManifestActivity_name)
        validateTagAttr(
            tag,
            "parentActivityName",
            R.styleable.AndroidManifestActivity_parentActivityName,
            1024
        )
        validateTagAttrComponentName(
            tag,
            "parentActivityName",
            R.styleable.AndroidManifestActivity_parentActivityName
        )
        validateTagAttr(tag, "permission", R.styleable.AndroidManifestActivity_permission, 1024)
        validateTagAttr(tag, "process", R.styleable.AndroidManifestActivity_process, 1024)
        validateTagAttr(tag, "taskAffinity", R.styleable.AndroidManifestActivity_taskAffinity, 1024)
        validateTagCount("layout", 1000, tag)
        validateTagCount("meta-data", 1000, tag)
        validateTagCount("intent-filter", 20000, tag)
    }

    @Test
    fun parseOverlayTag() {
        val tag = "overlay"
        validateTagAttr(tag, "category", R.styleable.AndroidManifestResourceOverlay_category, 1024)
        validateTagAttr(
            tag,
            "requiredSystemPropertyName",
            R.styleable.AndroidManifestResourceOverlay_requiredSystemPropertyName,
            1024
        )
        validateTagAttr(
            tag,
            "requiredSystemPropertyValue",
            R.styleable.AndroidManifestResourceOverlay_requiredSystemPropertyValue,
            PROP_VALUE_MAX
        )
        validateTagAttr(
            tag,
            "targetPackage",
            R.styleable.AndroidManifestResourceOverlay_targetPackage,
            256
        )
        validateTagAttr(
            tag,
            "targetName",
            R.styleable.AndroidManifestResourceOverlay_targetName,
            1024
        )
    }

    @Test
    fun parseInstrumentationTag() {
        val tag = "instrumentation"
        validateTagAttr(tag, "name", R.styleable.AndroidManifestInstrumentation_name, 1024)
        validateTagAttrComponentName(tag, "name", R.styleable.AndroidManifestInstrumentation_name)
        validateTagAttr(
            tag,
            "targetPackage",
            R.styleable.AndroidManifestInstrumentation_targetPackage,
            256
        )
        validateTagAttr(
            tag,
            "targetProcesses",
            R.styleable.AndroidManifestInstrumentation_targetProcesses,
            1024
        )
    }

    @Test
    fun parsePermissionGroupTag() {
        val tag = "permission-group"
        validateTagAttr(tag, "name", R.styleable.AndroidManifestPermissionGroup_name, 1024)
    }

    @Test
    fun parsePermissionTreeTag() {
        val tag = "permission-tree"
        validateTagAttr(tag, "name", R.styleable.AndroidManifestPermissionTree_name, 1024)
    }

    @Test
    fun parseSupportsGlTextureTag() {
        val tag = "supports-gl-texture"
        validateTagAttr(tag, "name", null, 1024)
    }

    @Test
    fun parseUsesPermissionSdk23Tag() {
        val tag = "uses-permission-sdk-23"
        validateTagAttr(tag, "name", R.styleable.AndroidManifestUsesPermission_name, 1024)
    }

    @Test
    fun parseCompatibleScreensTag() {
        val tag = "compatible-screens"
        validateTagCount("screen", 4000, tag)
    }

    @Test
    fun parseQueriesTag() {
        val tag = "queries"
        validateTagCount("package", 1000, tag)
        validateTagCount("intent", 2000, tag)
        validateTagCount("provider", 8000, tag)
    }

    @Test
    fun parsePackageTag() {
        val tag = "package"
        validateTagAttr(tag, "name", null, 1024)
    }

    @Test
    fun parseIntentTag() {
        val tag = "intent"
        validateTagCount("action", 20000, tag)
        validateTagCount("category", 40000, tag)
        validateTagCount("data", 40000, tag)
    }

    @Test
    fun parseProviderTag() {
        val tag = "provider"
        validateTagAttr(tag, "name", R.styleable.AndroidManifestProvider_name, 1024)
        validateTagAttrComponentName(tag, "name", R.styleable.AndroidManifestProvider_name)
        validateTagAttr(tag, "permission", R.styleable.AndroidManifestProvider_permission, 1024)
        validateTagAttr(tag, "process", R.styleable.AndroidManifestProvider_process, 1024)
        validateTagAttr(
            tag,
            "readPermission",
            R.styleable.AndroidManifestProvider_readPermission,
            1024
        )
        validateTagAttr(
            tag,
            "writePermission",
            R.styleable.AndroidManifestProvider_writePermission,
            1024
        )
        validateTagCount("grant-uri-permission", 100, tag)
        validateTagCount("path-permission", 100, tag)
        validateTagCount("meta-data", 1000, tag)
        validateTagCount("intent-filter", 20000, tag)
    }

    @Test
    fun parseGrantUriPermissionTag() {
        val tag = "grant-uri-permission"
        validateTagAttr(tag, "path", R.styleable.AndroidManifestGrantUriPermission_path, 4000)
        validateTagAttr(
            tag,
            "pathPrefix",
            R.styleable.AndroidManifestGrantUriPermission_pathPrefix,
            4000
        )
        validateTagAttr(
            tag,
            "pathPattern",
            R.styleable.AndroidManifestGrantUriPermission_pathPattern,
            4000
        )
    }

    @Test
    fun parsePathPermissionTag() {
        val tag = "path-permission"
        validateTagAttr(tag, "path", R.styleable.AndroidManifestPathPermission_path, 4000)
        validateTagAttr(
            tag,
            "pathPrefix",
            R.styleable.AndroidManifestPathPermission_pathPrefix,
            4000
        )
        validateTagAttr(
            tag,
            "pathPattern",
            R.styleable.AndroidManifestPathPermission_pathPattern,
            4000
        )
        validateTagAttr(
            tag,
            "permission",
            R.styleable.AndroidManifestPathPermission_permission,
            1024
        )
        validateTagAttr(
            tag,
            "readPermission",
            R.styleable.AndroidManifestPathPermission_readPermission,
            1024
        )
        validateTagAttr(
            tag,
            "writePermission",
            R.styleable.AndroidManifestPathPermission_writePermission,
            1024
        )
    }

    @Test
    fun parseMetaDataTag() {
        val tag = "meta-data"
        validateTagAttr(tag, "name", R.styleable.AndroidManifestMetaData_name, 1024)
        validateTagAttr(tag, "value", R.styleable.AndroidManifestMetaData_value, 32_768)
    }

    @Test
    fun parseIntentFilterTag() {
        val tag = "intent-filter"
        validateTagCount("action", 20000, tag)
        validateTagCount("category", 40000, tag)
        validateTagCount("data", 40000, tag)
    }

    @Test
    fun parseActionTag() {
        val tag = "action"
        validateTagAttr(tag, "name", R.styleable.AndroidManifestAction_name, 1024)
    }

    @Test
    fun parseCategoryTag() {
        val tag = "category"
        validateTagAttr(tag, "name", R.styleable.AndroidManifestCategory_name, 1024)
    }

    @Test
    fun parseDataTag() {
        val tag = "data"
        validateTagAttr(tag, "scheme", R.styleable.AndroidManifestData_scheme, 256)
        validateTagAttr(tag, "host", R.styleable.AndroidManifestData_host, 256)
        validateTagAttr(tag, "path", R.styleable.AndroidManifestData_path, 4000)
        validateTagAttr(tag, "pathPattern", R.styleable.AndroidManifestData_pathPattern, 4000)
        validateTagAttr(tag, "pathPrefix", R.styleable.AndroidManifestData_pathPrefix, 4000)
        validateTagAttr(tag, "pathSuffix", R.styleable.AndroidManifestData_pathSuffix, 4000)
        validateTagAttr(
            tag,
            "pathAdvancedPattern",
            R.styleable.AndroidManifestData_pathAdvancedPattern,
            4000
        )
        validateTagAttr(tag, "mimeType", R.styleable.AndroidManifestData_mimeType, 512)
    }

    @Test
    fun parseUsesFeatureTag() {
        val tag = "uses-feature"
        validateTagAttr(tag, "name", R.styleable.AndroidManifestUsesFeature_name, 1024)
    }

    @Test
    fun parsePermissionTag() {
        val tag = "permission"
        validateTagAttr(tag, "name", R.styleable.AndroidManifestPermission_name, 1024)
        validateTagAttr(
            tag,
            "permissionGroup",
            R.styleable.AndroidManifestPermission_permissionGroup,
            256
        )
    }

    @Test
    fun parseUsesPermissionTag() {
        val tag = "uses-permission"
        validateTagAttr(tag, "name", R.styleable.AndroidManifestUsesPermission_name, 1024)
    }

    private fun validateTagAttrComponentName(tag: String, attr: String, index: Int) {
        val passNames = arrayOf("com.android.TestClass", "TestClass", "_", "$", ".TestClass", "上")
        for (name in passNames) {
            val xml = "<$tag $attr=\"$name\" />"
            pullParser.setInput(ByteArrayInputStream(xml.toByteArray()), null)
            val validator = Validator()
            pullParser.nextTag()
            validator.validate(pullParser)
            try {
                validator.validateStrAttr(pullParser, attr, name)
            } catch (e: SecurityException) {
                fail(
                    "Failed to parse attribute $attr in <$tag> as valid Java class name:" +
                        " ${e.message}"
                )
            }
            try {
                validator.validateResStrAttr(pullParser, index, name)
            } catch (e: SecurityException) {
                fail(
                    "Failed to parse attribute $attr in <$tag> as valid Java class name:" +
                        " ${e.message}"
                )
            }
        }

        val badNames = arrayOf(
            ";",
            ",",
            "[",
            "]",
            "(",
            ")",
            "{",
            "}",
            ":",
            "?",
            "%",
            "^",
            "*",
            "|",
            "/",
            "\\"
        )
        for (name in badNames) {
            val xml = "<$tag $attr=\"$name\" />"
            pullParser.setInput(ByteArrayInputStream(xml.toByteArray()), null)
            val validator = Validator()
            pullParser.nextTag()
            validator.validate(pullParser)
            val e1 = assertThrows(
                "$name is not valid Java class name",
                SecurityException::class.java
            ) {
                validator.validateStrAttr(pullParser, attr, name)
            }
            assertEquals(expectedAttrComponentNameErrorMsg(name), e1.message)
            val e2 = assertThrows(
                "$name is not valid Java class name",
                SecurityException::class.java
            ) {
                validator.validateResStrAttr(pullParser, index, name)
            }
            assertEquals(expectedAttrComponentNameErrorMsg(name), e2.message)
        }
    }

    private fun validateTagAttr(tag: String, name: String, index: Int?, maxLen: Int) {
        validateTagAttr_shouldPass(tag, name, index, maxLen)
        validateTagAttr_shouldFail(tag, name, index, maxLen)
    }

    private fun validateTagAttr_shouldPass(
        tag: String,
        name: String,
        index: Int?,
        maxLen: Int
    ) {
        val value = "x".repeat(maxLen)
        val xml = "<$tag $name=\"$value\" />"
        pullParser.setInput(ByteArrayInputStream(xml.toByteArray()), null)
        val validator = Validator()
        pullParser.nextTag()
        validator.validate(pullParser)
        try {
            validator.validateStrAttr(pullParser, name, value)
        } catch (e: SecurityException) {
            fail(
                "Failed to parse valid <$tag> attribute $name with max length of $maxLen:" +
                    " ${e.message}"
            )
        }
        if (index != null) {
            try {
                validator.validateResStrAttr(pullParser, index, value)
            } catch (e: SecurityException) {
                fail(
                    "Failed to parse valid <$tag> resource string attribute $name with max" +
                        " length of $maxLen: ${e.message}"
                )
            }
        }
    }

    private fun validateTagAttr_shouldFail(
        tag: String,
        name: String,
        index: Int?,
        maxLen: Int
    ) {
        val value = "x".repeat(maxLen + 1)
        val xml = "<$tag $name=\"$value\" />"
        pullParser.setInput(ByteArrayInputStream(xml.toByteArray()), null)
        val validator = Validator()
        pullParser.nextTag()
        validator.validate(pullParser)
        val e1 = assertThrows(SecurityException::class.java) {
            validator.validateStrAttr(pullParser, name, value)
        }
        assertEquals(expectedAttrLengthErrorMsg(name, tag), e1.message)
        if (index != null) {
            val e2 = assertThrows(SecurityException::class.java) {
                validator.validateResStrAttr(pullParser, index, value)
            }
            assertEquals(expectedResAttrLengthErrorMsg(tag), e2.message)
        }
    }

    private fun validateTagCount(tag: String, maxNum: Int, parentTag: String) {
        validateTagCount_shouldPass(tag, maxNum, parentTag)
        validateTagCount_shouldFail(tag, maxNum, parentTag)
    }

    private fun validateTagCount_shouldPass(tag: String, maxNum: Int, parentTag: String) {
        val tags = "<$tag />".repeat(maxNum)
        val xml = "<$parentTag>$tags</$parentTag>"
        try {
            parseXmlStr(xml)
        } catch (e: SecurityException) {
            fail(
                "Failed to parse <$tag> with max count limit of $maxNum under" +
                        " <$parentTag>: ${e.message}"
            )
        }
    }

    private fun validateTagCount_shouldFail(tag: String, maxNum: Int, parentTag: String) {
        val tags = "<$tag />".repeat(maxNum + 1)
        val xml = "<$parentTag>$tags</$parentTag>"
        val e = assertThrows(SecurityException::class.java) {
            parseXmlStr(xml)
        }
        assertEquals(expectedCountErrorMsg(tag, parentTag), e.message)
    }

    @Test
    fun parseUnexpectedTag_shouldSkip() {
        val host = "x".repeat(256)
        val dataTags = "<data host=\"$host\" />".repeat(2049)
        val xml = "<manifest package=\"test\">$dataTags</manifest>"
        parseXmlStr(xml)
    }

    fun parseXmlStr(manifestStr: String) {
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

    fun expectedAttrComponentNameErrorMsg(name: String) = "$name is not a valid Java class name"
}
