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

import android.content.om.OverlayInfo
import android.content.om.OverlayableInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Process
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import java.lang.UnsupportedOperationException

class OverlayActorEnforcerTests {
    companion object {
        private const val NAMESPACE = "testnamespace"
        private const val ACTOR_NAME = "testactor"
        private const val ACTOR_PKG_NAME = "com.test.actor.one"
        private const val OVERLAYABLE_NAME = "TestOverlayable"
        private const val UID = 3536
        private const val USER_ID = 55
    }

    @get:Rule
    val expectedException = ExpectedException.none()!!

    @Test
    fun isRoot() {
        verify(callingUid = Process.ROOT_UID)
    }

    @Test(expected = SecurityException::class)
    fun isShell() {
        verify(callingUid = Process.SHELL_UID)
    }

    @Test
    fun isSystem() {
        verify(callingUid = Process.SYSTEM_UID)
    }

    @Test(expected = SecurityException::class)
    fun noOverlayable_noTarget() {
        verify(targetOverlayableName = null)
    }

    @Test
    fun noOverlayable_noTarget_withPermission() {
        verify(targetOverlayableName = null, hasPermission = true)
    }

    @Test(expected = SecurityException::class)
    fun noOverlayable_withTarget() {
        verify(targetOverlayableName = OVERLAYABLE_NAME)
    }

    @Test(expected = SecurityException::class)
    fun withOverlayable_noTarget() {
        verify(
                targetOverlayableName = null,
                overlayableInfo = OverlayableInfo(OVERLAYABLE_NAME, null)
        )
    }

    @Test(expected = SecurityException::class)
    fun withOverlayable_noActor() {
        verify(
                overlayableInfo = OverlayableInfo(OVERLAYABLE_NAME, null)
        )
    }

    @Test
    fun withOverlayable_noActor_withPermission() {
        verify(
                hasPermission = true,
                overlayableInfo = OverlayableInfo(OVERLAYABLE_NAME, null)
        )
    }

    @Test(expected = SecurityException::class)
    fun withOverlayable_withActor_notActor() {
        verify(
                isActor = false,
                overlayableInfo = OverlayableInfo(OVERLAYABLE_NAME,
                        "overlay://$NAMESPACE/$ACTOR_NAME")
        )
    }

    @Test(expected = SecurityException::class)
    fun withOverlayable_withActor_isActor_notPreInstalled() {
        verify(
                isActor = true,
                isPreInstalled = false,
                overlayableInfo = OverlayableInfo(OVERLAYABLE_NAME,
                        "overlay://$NAMESPACE/$ACTOR_NAME")
        )
    }

    @Test
    fun withOverlayable_withActor_isActor_isPreInstalled() {
        verify(
                isActor = true,
                isPreInstalled = true,
                overlayableInfo = OverlayableInfo(OVERLAYABLE_NAME,
                        "overlay://$NAMESPACE/$ACTOR_NAME")
        )
    }

    @Test(expected = SecurityException::class)
    fun withOverlayable_invalidActor() {
        verify(
                isActor = true,
                isPreInstalled = true,
                overlayableInfo = OverlayableInfo(OVERLAYABLE_NAME, "notValidActor")
        )
    }

    private fun verify(
        isActor: Boolean = false,
        isPreInstalled: Boolean = false,
        hasPermission: Boolean = false,
        overlayableInfo: OverlayableInfo? = null,
        callingUid: Int = UID,
        targetOverlayableName: String? = OVERLAYABLE_NAME
    ) {
        val callback = MockCallback(
                isActor = isActor,
                isPreInstalled = isPreInstalled,
                hasPermission = hasPermission,
                overlayableInfo = overlayableInfo
        )

        val overlayInfo = overlayInfo(targetOverlayableName)
        OverlayActorEnforcer(callback)
                .enforceActor(overlayInfo, "test", callingUid, USER_ID)
    }

    private fun overlayInfo(targetOverlayableName: String?) = OverlayInfo("com.test.overlay",
            "com.test.target", targetOverlayableName, null, "/path", OverlayInfo.STATE_UNKNOWN, 0,
            0, false)

    private class MockCallback(
        private val isActor: Boolean = false,
        private val isPreInstalled: Boolean = false,
        private val hasPermission: Boolean = false,
        private val overlayableInfo: OverlayableInfo? = null,
        private vararg val packageNames: String = arrayOf("com.test.actor.one")
    ) : OverlayableInfoCallback {

        override fun getNamedActors() = if (isActor) {
            mapOf(NAMESPACE to mapOf(ACTOR_NAME to ACTOR_PKG_NAME))
        } else {
            emptyMap()
        }

        override fun getOverlayableForTarget(
            packageName: String,
            targetOverlayableName: String,
            userId: Int
        ) = overlayableInfo

        override fun getPackagesForUid(uid: Int) = when (uid) {
            UID -> packageNames
            else -> null
        }

        override fun getPackageInfo(packageName: String, userId: Int) = PackageInfo().apply {
            applicationInfo = ApplicationInfo().apply {
                flags = if (isPreInstalled) ApplicationInfo.FLAG_SYSTEM else 0
            }
        }

        override fun doesTargetDefineOverlayable(targetPackageName: String?, userId: Int): Boolean {
            return overlayableInfo != null
        }

        override fun enforcePermission(permission: String?, message: String?) {
            if (!hasPermission) {
                throw SecurityException()
            }
        }

        override fun signaturesMatching(pkgName1: String, pkgName2: String, userId: Int): Boolean {
            throw UnsupportedOperationException()
        }
    }
}
