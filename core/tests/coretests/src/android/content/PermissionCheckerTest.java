/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.content;

import static com.google.common.truth.Truth.assertThat;

import android.app.AppOpsManager;
import android.app.UiAutomation;
import android.os.Binder;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

// TODO(b/147877945): Add missing tests.
@RunWith(AndroidJUnit4.class)
public class PermissionCheckerTest {
    private static final String INTERACT_ACROSS_PROFILES_PERMISSION =
            "android.permission.INTERACT_ACROSS_PROFILES";
    private static final String MANAGE_APP_OPS_MODE = "android.permission.MANAGE_APP_OPS_MODES";

    private  final Context mContext = InstrumentationRegistry.getContext();;
    private final AppOpsManager mAppOpsManager = mContext.getSystemService(AppOpsManager.class);
    private UiAutomation mUiAutomation =
            InstrumentationRegistry.getInstrumentation().getUiAutomation();

    @After
    public void tearDown() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .dropShellPermissionIdentity();
    }

    @Test
    public void testCheckPermissionForPreflight_appOpPermission_modeDefaultAndPermissionGranted_returnsGranted() {
        mUiAutomation.adoptShellPermissionIdentity(
                INTERACT_ACROSS_PROFILES_PERMISSION, MANAGE_APP_OPS_MODE);
        mAppOpsManager.setMode(AppOpsManager.permissionToOp(INTERACT_ACROSS_PROFILES_PERMISSION),
                Binder.getCallingUid(), mContext.getPackageName(), AppOpsManager.MODE_DEFAULT);

        assertThat(PermissionChecker.checkPermissionForPreflight(
                    mContext, INTERACT_ACROSS_PROFILES_PERMISSION, Binder.getCallingPid(),
                    Binder.getCallingUid(), mContext.getPackageName()))
                .isEqualTo(PermissionChecker.PERMISSION_GRANTED);
    }

    @Test
    public void testCheckPermissionForPreflight_appOpPermission_modeDefaultAndPermissionNotGranted_returnsHardDenied() {
        mUiAutomation.adoptShellPermissionIdentity(MANAGE_APP_OPS_MODE);
        mAppOpsManager.setMode(AppOpsManager.permissionToOp(INTERACT_ACROSS_PROFILES_PERMISSION),
                Binder.getCallingUid(), mContext.getPackageName(), AppOpsManager.MODE_DEFAULT);

        assertThat(PermissionChecker.checkPermissionForPreflight(
                    mContext, INTERACT_ACROSS_PROFILES_PERMISSION, Binder.getCallingPid(),
                    Binder.getCallingUid(), mContext.getPackageName()))
                .isEqualTo(PermissionChecker.PERMISSION_HARD_DENIED);
    }

    @Test
    public void testCheckPermissionForPreflight_appOpPermission_modeAllowed_returnsGranted() {
        mUiAutomation.adoptShellPermissionIdentity(MANAGE_APP_OPS_MODE);
        mAppOpsManager.setMode(AppOpsManager.permissionToOp(INTERACT_ACROSS_PROFILES_PERMISSION),
                Binder.getCallingUid(), mContext.getPackageName(), AppOpsManager.MODE_ALLOWED);

        assertThat(PermissionChecker.checkPermissionForPreflight(
                    mContext, INTERACT_ACROSS_PROFILES_PERMISSION, Binder.getCallingPid(),
                    Binder.getCallingUid(), mContext.getPackageName()))
                .isEqualTo(PermissionChecker.PERMISSION_GRANTED);
    }

    @Test
    public void testCheckPermissionForPreflight_appOpPermission_packageNameIsNull_returnsGranted() {
        mUiAutomation.adoptShellPermissionIdentity(MANAGE_APP_OPS_MODE);
        mAppOpsManager.setMode(AppOpsManager.permissionToOp(INTERACT_ACROSS_PROFILES_PERMISSION),
                Binder.getCallingUid(), mContext.getPackageName(), AppOpsManager.MODE_ALLOWED);

        assertThat(PermissionChecker.checkPermissionForPreflight(
                    mContext, INTERACT_ACROSS_PROFILES_PERMISSION, Binder.getCallingPid(),
                    Binder.getCallingUid(), /* packageName= */ null))
                .isEqualTo(PermissionChecker.PERMISSION_GRANTED);
    }

    @Test
    public void testCheckPermissionForPreflight_appOpPermission_modeIgnored_returnsHardDenied() {
        mUiAutomation.adoptShellPermissionIdentity(MANAGE_APP_OPS_MODE);
        mAppOpsManager.setMode(AppOpsManager.permissionToOp(INTERACT_ACROSS_PROFILES_PERMISSION),
                Binder.getCallingUid(), mContext.getPackageName(), AppOpsManager.MODE_IGNORED);

        assertThat(PermissionChecker.checkPermissionForPreflight(
                    mContext, INTERACT_ACROSS_PROFILES_PERMISSION, Binder.getCallingPid(),
                    Binder.getCallingUid(), mContext.getPackageName()))
                .isEqualTo(PermissionChecker.PERMISSION_HARD_DENIED);
    }

    @Test
    public void testCheckPermissionForPreflight_appOpPermission_modeErrored_returnsHardDenied() {
        mUiAutomation.adoptShellPermissionIdentity(MANAGE_APP_OPS_MODE);
        mAppOpsManager.setMode(AppOpsManager.permissionToOp(INTERACT_ACROSS_PROFILES_PERMISSION),
                Binder.getCallingUid(), mContext.getPackageName(), AppOpsManager.MODE_ERRORED);

        assertThat(PermissionChecker.checkPermissionForPreflight(
                mContext, INTERACT_ACROSS_PROFILES_PERMISSION, Binder.getCallingPid(),
                Binder.getCallingUid(), mContext.getPackageName()))
                .isEqualTo(PermissionChecker.PERMISSION_HARD_DENIED);
    }

    @Test
    public void testCheckPermissionForDataDelivery_appOpPermission_modeDefaultAndPermissionGranted_returnsGranted() {
        mUiAutomation.adoptShellPermissionIdentity(
                INTERACT_ACROSS_PROFILES_PERMISSION, MANAGE_APP_OPS_MODE);
        mAppOpsManager.setMode(AppOpsManager.permissionToOp(INTERACT_ACROSS_PROFILES_PERMISSION),
                Binder.getCallingUid(), mContext.getPackageName(), AppOpsManager.MODE_DEFAULT);

        assertThat(PermissionChecker.checkPermissionForDataDelivery(
                mContext, INTERACT_ACROSS_PROFILES_PERMISSION, Binder.getCallingPid(),
                Binder.getCallingUid(), mContext.getPackageName(), /* featureId= */null,
                /* message= */null)).isEqualTo(PermissionChecker.PERMISSION_GRANTED);
    }

    @Test
    public void testCheckPermissionForDataDelivery_appOpPermission_modeDefaultAndPermissionNotGranted_returnsHardDenied() {
        mUiAutomation.adoptShellPermissionIdentity(MANAGE_APP_OPS_MODE);
        mAppOpsManager.setMode(AppOpsManager.permissionToOp(INTERACT_ACROSS_PROFILES_PERMISSION),
                Binder.getCallingUid(), mContext.getPackageName(), AppOpsManager.MODE_DEFAULT);

        assertThat(PermissionChecker.checkPermissionForDataDelivery(
                mContext, INTERACT_ACROSS_PROFILES_PERMISSION, Binder.getCallingPid(),
                Binder.getCallingUid(), mContext.getPackageName(), /* featureId= */null,
                /* message= */null)).isEqualTo(PermissionChecker.PERMISSION_HARD_DENIED);
    }

    @Test
    public void testCheckPermissionForDataDelivery_appOpPermission_modeAllowed_returnsGranted() {
        mUiAutomation.adoptShellPermissionIdentity(MANAGE_APP_OPS_MODE);
        mAppOpsManager.setMode(AppOpsManager.permissionToOp(INTERACT_ACROSS_PROFILES_PERMISSION),
                Binder.getCallingUid(), mContext.getPackageName(), AppOpsManager.MODE_ALLOWED);

        assertThat(PermissionChecker.checkPermissionForDataDelivery(
                mContext, INTERACT_ACROSS_PROFILES_PERMISSION, Binder.getCallingPid(),
                Binder.getCallingUid(), mContext.getPackageName(), /* featureId= */null,
                /* message= */null)).isEqualTo(PermissionChecker.PERMISSION_GRANTED);
    }

    @Test
    public void testCheckPermissionForDataDelivery_appOpPermission_packageNameIsNull_returnsGranted() {
        mUiAutomation.adoptShellPermissionIdentity(MANAGE_APP_OPS_MODE);
        mAppOpsManager.setMode(AppOpsManager.permissionToOp(INTERACT_ACROSS_PROFILES_PERMISSION),
                Binder.getCallingUid(), mContext.getPackageName(), AppOpsManager.MODE_ALLOWED);

        assertThat(PermissionChecker.checkPermissionForDataDelivery(
                mContext, INTERACT_ACROSS_PROFILES_PERMISSION, Binder.getCallingPid(),
                Binder.getCallingUid(), /* packageName= */ null, /* featureId= */null,
                /* message= */null)).isEqualTo(PermissionChecker.PERMISSION_GRANTED);
    }

    @Test
    public void testCheckPermissionForDataDelivery_appOpPermission_modeIgnored_returnsHardDenied() {
        mUiAutomation.adoptShellPermissionIdentity(MANAGE_APP_OPS_MODE);
        mAppOpsManager.setMode(AppOpsManager.permissionToOp(INTERACT_ACROSS_PROFILES_PERMISSION),
                Binder.getCallingUid(), mContext.getPackageName(), AppOpsManager.MODE_IGNORED);

        assertThat(PermissionChecker.checkPermissionForDataDelivery(
                mContext, INTERACT_ACROSS_PROFILES_PERMISSION, Binder.getCallingPid(),
                Binder.getCallingUid(), mContext.getPackageName(), /* featureId= */null,
                /* message= */null)).isEqualTo(PermissionChecker.PERMISSION_HARD_DENIED);
    }

    @Test
    public void testCheckPermissionForDataDelivery_appOpPermission_modeErrored_returnsHardDenied() {
        mUiAutomation.adoptShellPermissionIdentity(MANAGE_APP_OPS_MODE);
        mAppOpsManager.setMode(AppOpsManager.permissionToOp(INTERACT_ACROSS_PROFILES_PERMISSION),
                Binder.getCallingUid(), mContext.getPackageName(), AppOpsManager.MODE_ERRORED);

        assertThat(PermissionChecker.checkPermissionForDataDelivery(
                mContext, INTERACT_ACROSS_PROFILES_PERMISSION, Binder.getCallingPid(),
                Binder.getCallingUid(), mContext.getPackageName(), /* featureId= */null,
                /* message= */null)).isEqualTo(PermissionChecker.PERMISSION_HARD_DENIED);
    }
}
