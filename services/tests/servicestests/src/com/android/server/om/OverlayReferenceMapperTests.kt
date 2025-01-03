/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.om

import android.net.Uri
import com.android.server.pm.pkg.AndroidPackage
import com.android.server.testutils.mockThrowOnUnmocked
import com.android.server.testutils.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class OverlayReferenceMapperTests {

    companion object {
        private const val TARGET_PACKAGE_NAME = "com.test.target"
        private const val OVERLAY_PACKAGE_NAME = "com.test.overlay"
        private const val ACTOR_PACKAGE_NAME = "com.test.actor"
        private const val ACTOR_NAME = "overlay://test/actorName"

        @JvmStatic
        @Parameterized.Parameters(name = "deferRebuild {0}")
        fun parameters() = arrayOf(/*true, */false)
    }

    private lateinit var mapper: OverlayReferenceMapper

    @JvmField
    @Parameterized.Parameter(0)
    var deferRebuild = false

    @Before
    fun initMapper() {
        mapper = mapper()
    }

    @Test
    fun targetWithOverlay() {
        val target = mockTarget()
        val overlay = mockOverlay()
        val existing = mapper.addInOrder(overlay) {
            assertThat(it).isEmpty()
        }
        assertEmpty()
        mapper.addInOrder(target, existing = existing) {
            assertThat(it).containsExactly(ACTOR_PACKAGE_NAME)
        }
        assertMapping(ACTOR_PACKAGE_NAME to setOf(target, overlay))
        mapper.remove(target) {
            assertThat(it).containsExactly(ACTOR_PACKAGE_NAME)
        }
        assertEmpty()
    }

    @Test
    fun targetWithMultipleOverlays() {
        val target = mockTarget()
        val overlay0 = mockOverlay(0)
        val overlay1 = mockOverlay(1)
        mapper = mapper(
                overlayToTargetToOverlayables = mapOf(
                        overlay0.packageName to mapOf(
                                target.packageName to target.overlayables.keys
                        ),
                        overlay1.packageName to mapOf(
                                target.packageName to target.overlayables.keys
                        )
                )
        )
        val existing = mapper.addInOrder(overlay0, overlay1) {
            assertThat(it).isEmpty()
        }
        assertEmpty()
        mapper.addInOrder(target, existing = existing) {
            assertThat(it).containsExactly(ACTOR_PACKAGE_NAME)
        }
        assertMapping(ACTOR_PACKAGE_NAME to setOf(target, overlay0, overlay1))
        mapper.remove(overlay0) {
            assertThat(it).containsExactly(ACTOR_PACKAGE_NAME)
        }
        assertMapping(ACTOR_PACKAGE_NAME to setOf(target, overlay1))
        mapper.remove(target) {
            assertThat(it).containsExactly(ACTOR_PACKAGE_NAME)
        }
        assertEmpty()
    }

    @Test
    fun targetWithoutOverlay() {
        val target = mockTarget()
        mapper.addInOrder(target) {
            assertThat(it).containsExactly(ACTOR_PACKAGE_NAME)
        }
        assertMapping(ACTOR_PACKAGE_NAME to setOf(target))
        mapper.remove(target) {
            assertThat(it).containsExactly(ACTOR_PACKAGE_NAME)
        }
        assertEmpty()
    }

    @Test
    fun overlayWithTarget() {
        val target = mockTarget()
        val overlay = mockOverlay()
        val existing = mapper.addInOrder(target) {
            assertThat(it).containsExactly(ACTOR_PACKAGE_NAME)
        }
        assertMapping(ACTOR_PACKAGE_NAME to setOf(target))
        mapper.addInOrder(overlay, existing = existing) {
            assertThat(it).containsExactly(ACTOR_PACKAGE_NAME)
        }
        assertMapping(ACTOR_PACKAGE_NAME to setOf(target, overlay))
        mapper.remove(overlay) {
            assertThat(it).containsExactly(ACTOR_PACKAGE_NAME)
        }
        assertMapping(ACTOR_PACKAGE_NAME to setOf(target))
    }

    @Test
    fun overlayWithMultipleTargets() {
        val target0 = mockTarget(0)
        val target1 = mockTarget(1)
        val overlay = mockOverlay()
        mapper = mapper(
                overlayToTargetToOverlayables = mapOf(
                        overlay.packageName to mapOf(
                                target0.packageName to target0.overlayables.keys,
                                target1.packageName to target1.overlayables.keys
                        )
                )
        )
        mapper.addInOrder(target0, target1, overlay) {
            assertThat(it).containsExactly(ACTOR_PACKAGE_NAME)
        }
        assertMapping(ACTOR_PACKAGE_NAME to setOf(target0, target1, overlay))
        mapper.remove(target0) {
            assertThat(it).containsExactly(ACTOR_PACKAGE_NAME)
        }
        assertMapping(ACTOR_PACKAGE_NAME to setOf(target1, overlay))
        mapper.remove(target1) {
            assertThat(it).containsExactly(ACTOR_PACKAGE_NAME)
        }
        assertEmpty()
    }

    @Test
    fun overlayWithoutTarget() {
        val overlay = mockOverlay()
        mapper.addInOrder(overlay) {
            assertThat(it).isEmpty()
        }
        // An overlay can only have visibility exposed through its target
        assertEmpty()
        mapper.remove(overlay) {
            assertThat(it).isEmpty()
        }
        assertEmpty()
    }

    private fun OverlayReferenceMapper.addInOrder(
        vararg pkgs: AndroidPackage,
        existing: MutableMap<String, AndroidPackage> = mutableMapOf(),
        assertion: (changedPackages: Set<String>) -> Unit
    ): MutableMap<String, AndroidPackage> {
        val changedPackages = mutableSetOf<String>()
        pkgs.forEach {
            changedPackages += addPkg(it, existing)
            existing[it.packageName] = it
        }
        assertion(changedPackages)
        return existing
    }

    private fun OverlayReferenceMapper.remove(
        pkg: AndroidPackage,
        assertion: (changedPackages: Set<String>) -> Unit
    ) = assertion(removePkg(pkg.packageName))

    private fun assertMapping(vararg pairs: Pair<String, Set<AndroidPackage>>) {
        val expected = pairs.associate { it }
                .mapValues { pair -> pair.value.map { it.packageName }.toSet() }

        // This validates the API exposed for querying the relationships
        expected.forEach { (actorPkgName, expectedPkgNames) ->
            expectedPkgNames.forEach { expectedPkgName ->
                if (deferRebuild) {
                    mapper.rebuildIfDeferred()
                    deferRebuild = false
                }

                assertThat(mapper.isValidActor(expectedPkgName, actorPkgName)).isTrue()
            }
        }

        // This asserts no other relationships are defined besides those tested above
        assertThat(mapper.actorPkgToPkgs).containsExactlyEntriesIn(expected)
    }

    private fun assertEmpty() = assertMapping()

    private fun mapper(
        namedActors: Map<String, Map<String, String>> = Uri.parse(ACTOR_NAME).run {
            mapOf(authority!! to mapOf(pathSegments.first() to ACTOR_PACKAGE_NAME))
        },
        overlayToTargetToOverlayables: Map<String, Map<String, Set<String>>> = mapOf(
                mockOverlay().packageName to mapOf(
                        mockTarget().run { packageName to overlayables.keys }
                )
        )
    ) = OverlayReferenceMapper(deferRebuild, object : OverlayReferenceMapper.Provider {
        override fun getActorPkg(actor: String) =
                OverlayActorEnforcer.getPackageNameForActor(actor, namedActors).first

        override fun getTargetToOverlayables(pkg: AndroidPackage) =
                overlayToTargetToOverlayables[pkg.packageName] ?: emptyMap()
    })

    private fun mockTarget(increment: Int = 0) = mockThrowOnUnmocked<AndroidPackage> {
        whenever(packageName) { "$TARGET_PACKAGE_NAME$increment" }
        whenever(overlayables) { mapOf("overlayableName$increment" to ACTOR_NAME) }
        whenever(toString()) { "Package{$packageName}" }
        whenever(isResourceOverlay) { false }
    }

    private fun mockOverlay(increment: Int = 0) = mockThrowOnUnmocked<AndroidPackage> {
        whenever(packageName) { "$OVERLAY_PACKAGE_NAME$increment" }
        whenever(overlayables) { emptyMap<String, String>() }
        whenever(toString()) { "Package{$packageName}" }
        whenever(isResourceOverlay) { true }
    }
}
