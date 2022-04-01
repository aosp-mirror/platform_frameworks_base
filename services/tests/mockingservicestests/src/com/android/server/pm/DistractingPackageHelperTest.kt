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

package com.android.server.pm

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import com.android.server.testutils.any
import com.android.server.testutils.eq
import com.android.server.testutils.nullable
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@RunWith(JUnit4::class)
class DistractingPackageHelperTest : PackageHelperTestBase() {

    lateinit var distractingPackageHelper: DistractingPackageHelper

    override fun setup() {
        super.setup()
        distractingPackageHelper = DistractingPackageHelper(
                pms, rule.mocks().injector, broadcastHelper, suspendPackageHelper)
    }

    @Test
    fun setDistractingPackageRestrictionsAsUser() {
        val unactionedPackages = distractingPackageHelper.setDistractingPackageRestrictionsAsUser(
                pms.snapshotComputer(), packagesToChange,
                PackageManager.RESTRICTION_HIDE_NOTIFICATIONS, TEST_USER_ID, deviceOwnerUid)
        testHandler.flush()

        verify(pms).scheduleWritePackageRestrictions(eq(TEST_USER_ID))
        verify(broadcastHelper).sendPackageBroadcast(eq(Intent.ACTION_DISTRACTING_PACKAGES_CHANGED),
                nullable(), bundleCaptor.capture(), anyInt(), nullable(), nullable(), any(),
                nullable(), nullable(), nullable())

        val modifiedPackages = bundleCaptor.value.getStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST)
        val distractionFlags = bundleCaptor.value.getInt(Intent.EXTRA_DISTRACTION_RESTRICTIONS)
        assertThat(modifiedPackages).asList().containsExactly(TEST_PACKAGE_1, TEST_PACKAGE_2)
        assertThat(distractionFlags).isEqualTo(PackageManager.RESTRICTION_HIDE_NOTIFICATIONS)
        assertThat(unactionedPackages).isEmpty()
    }

    @Test
    fun setDistractingPackageRestrictionsAsUser_setSameDistractionRestrictionTwice() {
        distractingPackageHelper.setDistractingPackageRestrictionsAsUser(
                pms.snapshotComputer(), packagesToChange,
                PackageManager.RESTRICTION_HIDE_NOTIFICATIONS, TEST_USER_ID, deviceOwnerUid)
        testHandler.flush()
        clearInvocations(pms)
        clearInvocations(broadcastHelper)

        val unactionedPackages = distractingPackageHelper.setDistractingPackageRestrictionsAsUser(
                pms.snapshotComputer(), packagesToChange,
                PackageManager.RESTRICTION_HIDE_NOTIFICATIONS, TEST_USER_ID, deviceOwnerUid)
        testHandler.flush()
        verify(pms, never()).scheduleWritePackageRestrictions(eq(TEST_USER_ID))
        verify(broadcastHelper, never()).sendPackageBroadcast(
                eq(Intent.ACTION_DISTRACTING_PACKAGES_CHANGED), nullable(), bundleCaptor.capture(),
                anyInt(), nullable(), nullable(), any(), nullable(), nullable(), nullable())
        assertThat(unactionedPackages).isEmpty()
    }

    @Test
    fun setDistractingPackageRestrictionsAsUser_emptyPackageName() {
        var unactionedPackages = distractingPackageHelper.setDistractingPackageRestrictionsAsUser(
                pms.snapshotComputer(), null /* packageNames */,
                PackageManager.RESTRICTION_HIDE_NOTIFICATIONS, TEST_USER_ID, deviceOwnerUid)
        assertThat(unactionedPackages).isNull()

        unactionedPackages = distractingPackageHelper.setDistractingPackageRestrictionsAsUser(
                pms.snapshotComputer(), arrayOfNulls(0) /* packageNames */,
                PackageManager.RESTRICTION_HIDE_NOTIFICATIONS, TEST_USER_ID, deviceOwnerUid)
        assertThat(unactionedPackages).isEmpty()
    }

    @Test
    fun setDistractingPackageRestrictionsAsUser_callerIsNotAllowed() {
        val unactionedPackages = distractingPackageHelper.setDistractingPackageRestrictionsAsUser(
                pms.snapshotComputer(), arrayOf(TEST_PACKAGE_1),
                PackageManager.RESTRICTION_HIDE_NOTIFICATIONS, TEST_USER_ID, Binder.getCallingUid())

        assertThat(unactionedPackages).asList().hasSize(1)
        assertThat(unactionedPackages).asList().contains(TEST_PACKAGE_1)
    }

    @Test
    fun setDistractingPackageRestrictionsAsUser_setCallerItself() {
        val unactionedPackages = distractingPackageHelper.setDistractingPackageRestrictionsAsUser(
                pms.snapshotComputer(), arrayOf(DEVICE_OWNER_PACKAGE),
                PackageManager.RESTRICTION_HIDE_NOTIFICATIONS, TEST_USER_ID, Binder.getCallingUid())

        assertThat(unactionedPackages).asList().hasSize(1)
        assertThat(unactionedPackages).asList().contains(DEVICE_OWNER_PACKAGE)
    }

    @Test
    fun setDistractingPackageRestrictionsAsUser_nonexistentPackage() {
        val unactionedPackages = distractingPackageHelper.setDistractingPackageRestrictionsAsUser(
                pms.snapshotComputer(), arrayOf(NONEXISTENT_PACKAGE),
                PackageManager.RESTRICTION_HIDE_NOTIFICATIONS, TEST_USER_ID, deviceOwnerUid)

        assertThat(unactionedPackages).asList().hasSize(1)
        assertThat(unactionedPackages).asList().contains(NONEXISTENT_PACKAGE)
    }

    @Test
    fun setDistractingPackageRestrictionsAsUser_setKnownPackages() {
        val knownPackages = arrayOf(DEVICE_ADMIN_PACKAGE, DEFAULT_HOME_PACKAGE, DIALER_PACKAGE,
                INSTALLER_PACKAGE, UNINSTALLER_PACKAGE, VERIFIER_PACKAGE,
                PERMISSION_CONTROLLER_PACKAGE)
        val unactionedPackages = distractingPackageHelper.setDistractingPackageRestrictionsAsUser(
                pms.snapshotComputer(), knownPackages,
                PackageManager.RESTRICTION_HIDE_NOTIFICATIONS, TEST_USER_ID, deviceOwnerUid)

        assertThat(unactionedPackages.size).isEqualTo(knownPackages.size)
        for (pkg in knownPackages) {
            assertThat(unactionedPackages).asList().contains(pkg)
        }
    }

    @Test
    fun removeDistractingPackageRestrictions() {
        distractingPackageHelper.setDistractingPackageRestrictionsAsUser(
                pms.snapshotComputer(), packagesToChange,
                PackageManager.RESTRICTION_HIDE_NOTIFICATIONS, TEST_USER_ID, deviceOwnerUid)
        testHandler.flush()
        clearInvocations(pms)
        clearInvocations(broadcastHelper)

        distractingPackageHelper.removeDistractingPackageRestrictions(pms.snapshotComputer(),
                packagesToChange, TEST_USER_ID)
        testHandler.flush()

        verify(pms).scheduleWritePackageRestrictions(eq(TEST_USER_ID))
        verify(broadcastHelper).sendPackageBroadcast(eq(Intent.ACTION_DISTRACTING_PACKAGES_CHANGED),
                nullable(), bundleCaptor.capture(), anyInt(), nullable(), nullable(), any(),
                nullable(), nullable(), nullable())
        val modifiedPackages = bundleCaptor.value.getStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST)
        val distractionFlags = bundleCaptor.value.getInt(Intent.EXTRA_DISTRACTION_RESTRICTIONS)
        assertThat(modifiedPackages).asList().containsExactly(TEST_PACKAGE_1, TEST_PACKAGE_2)
        assertThat(distractionFlags).isEqualTo(PackageManager.RESTRICTION_NONE)
    }

    @Test
    fun removeDistractingPackageRestrictions_notDistractingPackage() {
        distractingPackageHelper.removeDistractingPackageRestrictions(pms.snapshotComputer(),
                arrayOf(TEST_PACKAGE_1), TEST_USER_ID)
        testHandler.flush()

        verify(pms, never()).scheduleWritePackageRestrictions(eq(TEST_USER_ID))
        verify(broadcastHelper, never()).sendPackageBroadcast(eq(
                Intent.ACTION_DISTRACTING_PACKAGES_CHANGED), nullable(), nullable(), anyInt(),
                nullable(), nullable(), any(), nullable(), nullable(), nullable())
    }

    @Test
    fun removeDistractingPackageRestrictions_emptyPackageName() {
        distractingPackageHelper.removeDistractingPackageRestrictions(pms.snapshotComputer(),
                null /* packagesToChange */, TEST_USER_ID)
        testHandler.flush()
        verify(pms, never()).scheduleWritePackageRestrictions(eq(TEST_USER_ID))
        verify(broadcastHelper, never()).sendPackageBroadcast(eq(
                Intent.ACTION_DISTRACTING_PACKAGES_CHANGED), nullable(), nullable(), anyInt(),
                nullable(), nullable(), any(), nullable(), nullable(), nullable())

        distractingPackageHelper.removeDistractingPackageRestrictions(pms.snapshotComputer(),
                arrayOfNulls(0), TEST_USER_ID)
        testHandler.flush()
        verify(pms, never()).scheduleWritePackageRestrictions(eq(TEST_USER_ID))
        verify(broadcastHelper, never()).sendPackageBroadcast(eq(
                Intent.ACTION_DISTRACTING_PACKAGES_CHANGED), nullable(), nullable(), anyInt(),
                nullable(), nullable(), any(), nullable(), nullable(), nullable())
    }

    @Test
    fun sendDistractingPackagesChanged_withSameVisibilityAllowList() {
        mockAllowList(packageSetting1, allowList(10001, 10002, 10003))
        mockAllowList(packageSetting2, allowList(10001, 10002, 10003))

        distractingPackageHelper.sendDistractingPackagesChanged(pms.snapshotComputer(),
                packagesToChange, uidsToChange, TEST_USER_ID,
                PackageManager.RESTRICTION_HIDE_NOTIFICATIONS)
        testHandler.flush()
        verify(broadcastHelper).sendPackageBroadcast(eq(Intent.ACTION_DISTRACTING_PACKAGES_CHANGED),
                nullable(), bundleCaptor.capture(), anyInt(), nullable(), nullable(), any(),
                nullable(), nullable(), nullable())

        var changedPackages = bundleCaptor.value.getStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST)
        var changedUids = bundleCaptor.value.getIntArray(Intent.EXTRA_CHANGED_UID_LIST)
        assertThat(changedPackages).asList().containsExactly(TEST_PACKAGE_1, TEST_PACKAGE_2)
        assertThat(changedUids).asList().containsExactly(
                packageSetting1.appId, packageSetting2.appId)
    }

    @Test
    fun sendDistractingPackagesChanged_withDifferentVisibilityAllowList() {
        mockAllowList(packageSetting1, allowList(10001, 10002, 10003))
        mockAllowList(packageSetting2, allowList(10001, 10002, 10004))

        distractingPackageHelper.sendDistractingPackagesChanged(pms.snapshotComputer(),
                packagesToChange, uidsToChange, TEST_USER_ID,
                PackageManager.RESTRICTION_HIDE_NOTIFICATIONS)
        testHandler.flush()
        verify(broadcastHelper, times(2)).sendPackageBroadcast(
                eq(Intent.ACTION_DISTRACTING_PACKAGES_CHANGED), nullable(), bundleCaptor.capture(),
                anyInt(), nullable(), nullable(), any(), nullable(), nullable(), nullable())

        bundleCaptor.allValues.forEach {
            var changedPackages = it.getStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST)
            var changedUids = it.getIntArray(Intent.EXTRA_CHANGED_UID_LIST)
            assertThat(changedPackages?.size).isEqualTo(1)
            assertThat(changedUids?.size).isEqualTo(1)
            assertThat(changedPackages?.get(0)).isAnyOf(TEST_PACKAGE_1, TEST_PACKAGE_2)
            assertThat(changedUids?.get(0)).isAnyOf(packageSetting1.appId, packageSetting2.appId)
        }
    }

    @Test
    fun sendDistractingPackagesChanged_withNullVisibilityAllowList() {
        mockAllowList(packageSetting1, allowList(10001, 10002, 10003))
        mockAllowList(packageSetting2, null /* list */)

        distractingPackageHelper.sendDistractingPackagesChanged(pms.snapshotComputer(),
                packagesToChange, uidsToChange, TEST_USER_ID,
                PackageManager.RESTRICTION_HIDE_NOTIFICATIONS)
        testHandler.flush()
        verify(broadcastHelper, times(2)).sendPackageBroadcast(
                eq(Intent.ACTION_DISTRACTING_PACKAGES_CHANGED), nullable(), bundleCaptor.capture(),
                anyInt(), nullable(), nullable(), any(), nullable(), nullable(), nullable())

        bundleCaptor.allValues.forEach {
            var changedPackages = it.getStringArray(Intent.EXTRA_CHANGED_PACKAGE_LIST)
            var changedUids = it.getIntArray(Intent.EXTRA_CHANGED_UID_LIST)
            assertThat(changedPackages?.size).isEqualTo(1)
            assertThat(changedUids?.size).isEqualTo(1)
            assertThat(changedPackages?.get(0)).isAnyOf(TEST_PACKAGE_1, TEST_PACKAGE_2)
            assertThat(changedUids?.get(0)).isAnyOf(packageSetting1.appId, packageSetting2.appId)
        }
    }
}