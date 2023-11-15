/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.pm.test.pkg

import android.content.Intent
import android.content.pm.overlay.OverlayPaths
import android.content.pm.PackageManager
import android.content.pm.PathPermission
import android.content.pm.SharedLibraryInfo
import android.content.pm.VersionedPackage
import android.os.PatternMatcher
import android.util.ArraySet
import com.android.internal.pm.pkg.component.ParsedActivity
import com.android.internal.pm.pkg.component.ParsedInstrumentation
import com.android.internal.pm.pkg.component.ParsedPermission
import com.android.internal.pm.pkg.component.ParsedPermissionGroup
import com.android.internal.pm.pkg.component.ParsedProcess
import com.android.internal.pm.pkg.component.ParsedProvider
import com.android.internal.pm.pkg.component.ParsedService
import com.android.server.pm.PackageSetting
import com.android.server.pm.PackageSettingBuilder
import com.android.server.pm.parsing.pkg.PackageImpl
import com.android.server.pm.pkg.AndroidPackage
import com.android.server.pm.pkg.PackageState
import com.android.server.pm.pkg.PackageUserState
import com.android.server.pm.pkg.component.ParsedActivityImpl
import com.android.server.pm.pkg.component.ParsedComponentImpl
import com.android.server.pm.pkg.component.ParsedIntentInfoImpl
import com.android.server.pm.pkg.component.ParsedPermissionImpl
import com.android.server.pm.pkg.component.ParsedProcessImpl
import com.android.server.pm.pkg.component.ParsedProviderImpl
import com.android.server.pm.test.parsing.parcelling.AndroidPackageTest
import com.google.common.truth.Expect
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.contracts.ExperimentalContracts
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KType
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.starProjectedType

class PackageStateTest {

    companion object {
        private val IGNORED_TYPES = listOf(
                "java.io.File",
                "java.lang.Boolean",
                "java.lang.Byte",
                "java.lang.CharSequence",
                "java.lang.Character",
                "java.lang.Double",
                "java.lang.Float",
                "java.lang.Integer",
                "java.lang.Long",
                "java.lang.Short",
                "java.lang.String",
                "java.lang.Void",
        )
        // STOPSHIP: Remove these and fix the implementations
        private val IGNORED_FUNCTIONS = listOf(
            ParsedActivity::getIntents,
            ParsedActivity::getKnownActivityEmbeddingCerts,
            ParsedActivity::getProperties,
            ParsedInstrumentation::getIntents,
            ParsedInstrumentation::getIntents,
            ParsedInstrumentation::getProperties,
            ParsedInstrumentation::getProperties,
            ParsedPermission::getIntents,
            ParsedPermission::getProperties,
            ParsedPermissionGroup::getIntents,
            ParsedPermissionGroup::getProperties,
            ParsedProcess::getAppClassNamesByPackage,
            ParsedProvider::getIntents,
            ParsedProvider::getPathPermissions,
            ParsedProvider::getProperties,
            ParsedProvider::getUriPermissionPatterns,
            ParsedService::getIntents,
            ParsedService::getProperties,
            Intent::getCategories,
            PackageUserState::getDisabledComponents,
            PackageUserState::getEnabledComponents,
            PackageUserState::getSharedLibraryOverlayPaths,
            OverlayPaths::getOverlayPaths,
            OverlayPaths::getResourceDirs,
        )
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    @get:Rule
    val expect = Expect.create()

    private val collectionType = MutableCollection::class.starProjectedType
    private val mapType = Map::class.starProjectedType

    @OptIn(ExperimentalContracts::class)
    @Test
    fun collectionImmutability() {
        val seenTypes = mutableSetOf<KType>()
        val (_, pkg) = AndroidPackageTest().buildBefore()
        val packageState = PackageSettingBuilder()
            .setPackage(pkg as AndroidPackage)
            .setCodePath(tempFolder.newFile().path)
            .build()

        fillMissingData(packageState, pkg as PackageImpl)

        visitType(seenTypes, emptyList(), PackageSetting(packageState, true),
            PackageState::class.starProjectedType)
        visitType(seenTypes, emptyList(), pkg, AndroidPackage::class.starProjectedType)
        visitType(seenTypes, emptyList(), packageState.getUserStateOrDefault(0),
                PackageUserState::class.starProjectedType)

        // Don't check empties for defaults since their collections will always be empty
        visitType(seenTypes, emptyList(), PackageUserState.DEFAULT,
                PackageUserState::class.starProjectedType, enforceNonEmpty = false)

        // Check that some minimum number of functions were validated,
        // in case the type checking breaks somehow
        expect.that(seenTypes.size).isGreaterThan(10)
    }

    /**
     * Fill fields in [PackageState] and its children that are not filled by [AndroidPackageTest].
     * Real objects and real invocations of the live APIs are necessary to ensure that the test
     * mirrors real device behavior.
     */
    private fun fillMissingData(pkgSetting: PackageSetting, pkg: PackageImpl) {
        pkgSetting.addUsesLibraryFile("usesLibraryFile")

        val sharedLibraryDependency = listOf(SharedLibraryInfo(
            "pathDependency",
            "packageNameDependency",
            listOf(tempFolder.newFile().path),
            "nameDependency",
            1,
            0,
            VersionedPackage("versionedPackage0Dependency", 1),
            listOf(VersionedPackage("versionedPackage1Dependency", 2)),
            emptyList(),
            false
        ))

        pkgSetting.addUsesLibraryInfo(SharedLibraryInfo(
            "path",
            "packageName",
            listOf(tempFolder.newFile().path),
            "name",
            1,
            0,
            VersionedPackage("versionedPackage0", 1),
            listOf(VersionedPackage("versionedPackage1", 2)),
            sharedLibraryDependency,
            false
        ))
        pkgSetting.addMimeTypes("mimeGroup", setOf("mimeType"))
        pkgSetting.getOrCreateUserState(0).apply {
            setEnabledComponents(ArraySet<String>().apply { add("com.test.EnabledComponent") })
            setDisabledComponents(ArraySet<String>().apply { add("com.test.DisabledComponent") })
            setSharedLibraryOverlayPaths("sharedLibrary",
                OverlayPaths.Builder().addApkPath("/test/overlay.apk").build())
        }

        val property = PackageManager.Property("propertyName", 1, "com.test", null)
        listOf(
            pkg.activities,
            pkg.receivers,
            pkg.providers,
            pkg.services,
            pkg.instrumentations,
            pkg.permissions,
            pkg.permissionGroups
        ).map { it.first() as ParsedComponentImpl }
            .forEach {
                it.addIntent(ParsedIntentInfoImpl())
                it.addProperty(property)
            }

        (pkg.activities.first() as ParsedActivityImpl).knownActivityEmbeddingCerts =
            setOf("TESTEMBEDDINGCERT")

        (pkg.permissions.first() as ParsedPermissionImpl).knownCerts = setOf("TESTEMBEDDINGCERT")

        (pkg.providers.first() as ParsedProviderImpl).apply {
            addPathPermission(PathPermission("pattern", PatternMatcher.PATTERN_LITERAL,
                "readPermission", "writerPermission"))
            addUriPermissionPattern(PatternMatcher("*", PatternMatcher.PATTERN_LITERAL))
        }

        (pkg.processes.values.first() as ParsedProcessImpl).apply {
            deniedPermissions = setOf("deniedPermission")
            putAppClassNameForPackage("package", "className")
        }
    }

    private fun visitType(
        seenTypes: MutableSet<KType>,
        parentChain: List<String>,
        impl: Any,
        type: KType,
        enforceNonEmpty: Boolean = true
    ) {
        if (!seenTypes.add(type)) return
        val kClass = type.classifier as KClass<*>
        val qualifiedName = kClass.qualifiedName!!
        if (IGNORED_TYPES.contains(qualifiedName)) return

        val newChain = parentChain + kClass.simpleName!!
        val newChainText = newChain.joinToString()

        val filteredFunctions = kClass.memberFunctions
            .filter {
                // Size 1 because the impl receiver counts as a parameter
                it.parameters.size == 1
            }
            .filterNot(IGNORED_FUNCTIONS::contains)

        filteredFunctions.filter { it.returnType.isSubtypeOf(collectionType) }
                .forEach {
                    val collection = it.call(impl)
                    if (collection as? MutableCollection<*> == null) {
                        expect.withMessage("Method $newChainText ${it.name} cannot return null")
                            .fail()
                        return@forEach
                    }

                    val value = try {
                        if (AndroidPackage::getSplits == it) {
                            // The base split is defined to never have any dependencies,
                            // so force the visitor to use the split at index 1 instead of 0.
                            collection.last()
                        } else {
                            collection.first()
                        }
                    } catch (e: Exception) {
                        if (enforceNonEmpty) {
                            expect.withMessage("Method $newChainText ${it.name} returns empty")
                                .that(e)
                                .isNull()
                            return@forEach
                        } else null
                    }

                    if (value != null) {
                        it.returnType.arguments.forEach {
                            visitType(seenTypes, newChain, value, it.type!!)
                        }
                    }

                    // Must test clear last in case it works and actually clears the collection
                    expectUnsupported(newChain, it) { collection.clear() }
                }
        filteredFunctions.filter { it.returnType.isSubtypeOf(mapType) }
                .forEach {
                    val map = it.call(impl)
                    if (map as? MutableMap<*, *> == null) {
                        expect.withMessage("Method $newChainText ${it.name} cannot return null")
                            .fail()
                        return@forEach
                    }

                    val entry = try {
                        map.entries.stream().findFirst().get()!!
                    } catch (e: Exception) {
                        expect.withMessage("Method $newChainText ${it.name} returns empty")
                                .that(e)
                                .isNull()
                        return@forEach
                    }

                    visitType(seenTypes, newChain, entry.key!!, it.returnType.arguments[0].type!!)
                    visitType(seenTypes, newChain, entry.value!!, it.returnType.arguments[1].type!!)

                    // Must test clear last in case it works and actually clears the map
                    expectUnsupported(newChain, it) { map.clear() }
                }
    }

    private fun expectUnsupported(
            parentChain: List<String>,
            function: KFunction<*>,
            block: () -> Unit
    ) {
        val exception = try {
            block()
            null
        } catch (e: UnsupportedOperationException) {
            e
        }

        expect.withMessage("Method ${parentChain.joinToString()} $function doesn't throw")
                .that(exception)
                .isNotNull()
    }
}
