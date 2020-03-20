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

package com.android.server.compat;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.internal.verification.VerificationModeFactory.times;
import static org.testng.Assert.assertThrows;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.Build;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.compat.AndroidBuildClassifier;
import com.android.internal.compat.CompatibilityChangeInfo;
import com.android.server.LocalServices;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class PlatformCompatTest {
    private static final String PACKAGE_NAME = "my.package";

    @Mock
    private Context mContext;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private PackageManagerInternal mPackageManagerInternal;
    @Mock
    CompatChange.ChangeListener mListener1, mListener2;
    PlatformCompat mPlatformCompat;
    CompatConfig mCompatConfig;
    @Mock
    private AndroidBuildClassifier mBuildClassifier;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        android.app.compat.ChangeIdStateCache.disable();
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.getPackageUid(eq(PACKAGE_NAME), eq(0))).thenThrow(
                new PackageManager.NameNotFoundException());
        when(mPackageManagerInternal.getPackageUid(eq(PACKAGE_NAME), eq(0), anyInt()))
            .thenReturn(-1);
        mCompatConfig = new CompatConfig(mBuildClassifier, mContext);
        mPlatformCompat = new PlatformCompat(mContext, mCompatConfig);
        // Assume userdebug/eng non-final build
        when(mBuildClassifier.isDebuggableBuild()).thenReturn(true);
        when(mBuildClassifier.isFinalBuild()).thenReturn(false);
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInternal);
    }

    @Test
    public void testListAllChanges() {
        mCompatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addEnabledChangeWithId(1L)
                .addDisabledChangeWithIdAndName(2L, "change2")
                .addTargetSdkChangeWithIdAndDescription(Build.VERSION_CODES.O, 3L, "description")
                .addTargetSdkChangeWithId(Build.VERSION_CODES.P, 4L)
                .addTargetSdkChangeWithId(Build.VERSION_CODES.Q, 5L)
                .addTargetSdkChangeWithId(Build.VERSION_CODES.R, 6L)
                .addLoggingOnlyChangeWithId(7L)
                .build();
        mPlatformCompat = new PlatformCompat(mContext, mCompatConfig);
        assertThat(mPlatformCompat.listAllChanges()).asList().containsExactly(
                new CompatibilityChangeInfo(1L, "", -1, false, false, ""),
                new CompatibilityChangeInfo(2L, "change2", -1, true, false, ""),
                new CompatibilityChangeInfo(3L, "", Build.VERSION_CODES.O, false, false,
                        "description"),
                new CompatibilityChangeInfo(4L, "", Build.VERSION_CODES.P, false, false, ""),
                new CompatibilityChangeInfo(5L, "", Build.VERSION_CODES.Q, false, false, ""),
                new CompatibilityChangeInfo(6L, "", Build.VERSION_CODES.R, false, false, ""),
                new CompatibilityChangeInfo(7L, "", -1, false, true, ""));
    }

    @Test
    public void testListUIChanges() {
        mCompatConfig = CompatConfigBuilder.create(mBuildClassifier, mContext)
                .addEnabledChangeWithId(1L)
                .addDisabledChangeWithIdAndName(2L, "change2")
                .addTargetSdkChangeWithIdAndDescription(Build.VERSION_CODES.O, 3L, "description")
                .addTargetSdkChangeWithId(Build.VERSION_CODES.P, 4L)
                .addTargetSdkChangeWithId(Build.VERSION_CODES.Q, 5L)
                .addTargetSdkChangeWithId(Build.VERSION_CODES.R, 6L)
                .addLoggingOnlyChangeWithId(7L)
                .build();
        mPlatformCompat = new PlatformCompat(mContext, mCompatConfig);
        assertThat(mPlatformCompat.listUIChanges()).asList().containsExactly(
                new CompatibilityChangeInfo(1L, "", -1, false, false, ""),
                new CompatibilityChangeInfo(2L, "change2", -1, true, false, ""),
                new CompatibilityChangeInfo(4L, "", Build.VERSION_CODES.P, false, false, ""),
                new CompatibilityChangeInfo(5L, "", Build.VERSION_CODES.Q, false, false, ""));
    }

    @Test
    public void testRegisterListenerToSameIdThrows() throws Exception {
        // Registering a listener to change 1 is successful.
        mPlatformCompat.registerListener(1, mListener1);
        // Registering a listener to change 2 is successful.
        mPlatformCompat.registerListener(2, mListener1);
        // Trying to register another listener to change id 1 fails.
        assertThrows(IllegalStateException.class,
                () -> mPlatformCompat.registerListener(1, mListener1));
    }

    @Test
    public void testRegisterListenerReturn() throws Exception {
        mPlatformCompat.setOverrides(
                CompatibilityChangeConfigBuilder.create().enable(1L).build(),
                PACKAGE_NAME);

        // Change id 1 is known (added in setOverrides).
        assertThat(mPlatformCompat.registerListener(1, mListener1)).isTrue();
        // Change 2 is unknown.
        assertThat(mPlatformCompat.registerListener(2, mListener1)).isFalse();
    }

    @Test
    public void testListenerCalledOnSetOverrides() throws Exception {
        mPlatformCompat.registerListener(1, mListener1);
        mPlatformCompat.registerListener(2, mListener1);

        mPlatformCompat.setOverrides(
                CompatibilityChangeConfigBuilder.create().enable(1L).disable(2L).build(),
                PACKAGE_NAME);

        verify(mListener1, times(2)).onCompatChange(PACKAGE_NAME);
    }

    @Test
    public void testListenerNotCalledOnWrongPackage() throws Exception {
        mPlatformCompat.registerListener(1, mListener1);
        mPlatformCompat.registerListener(2, mListener1);

        mPlatformCompat.setOverrides(
                CompatibilityChangeConfigBuilder.create().enable(1L).disable(2L).build(),
                PACKAGE_NAME);

        verify(mListener1, never()).onCompatChange("other.package");
    }

    @Test
    public void testListenerCalledOnSetOverridesTwoListeners() throws Exception {
        mPlatformCompat.registerListener(1, mListener1);

        mPlatformCompat.setOverrides(
                CompatibilityChangeConfigBuilder.create().enable(1L).disable(2L).build(),
                PACKAGE_NAME);

        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, never()).onCompatChange(PACKAGE_NAME);

        reset(mListener1);
        reset(mListener2);

        mPlatformCompat.registerListener(2, mListener2);

        mPlatformCompat.setOverrides(
                CompatibilityChangeConfigBuilder.create().enable(1L).disable(2L).build(),
                PACKAGE_NAME);

        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, times(1)).onCompatChange(PACKAGE_NAME);
    }

    @Test
    public void testListenerCalledOnSetOverridesForTest() throws Exception {
        mPlatformCompat.registerListener(1, mListener1);
        mPlatformCompat.registerListener(2, mListener1);

        mPlatformCompat.setOverrides(
                CompatibilityChangeConfigBuilder.create().enable(1L).disable(2L).build(),
                PACKAGE_NAME);

        verify(mListener1, times(2)).onCompatChange(PACKAGE_NAME);
    }

    @Test
    public void testListenerCalledOnSetOverridesTwoListenersForTest() throws Exception {
        mPlatformCompat.registerListener(1, mListener1);

        mPlatformCompat.setOverrides(
                CompatibilityChangeConfigBuilder.create().enable(1L).disable(2L).build(),
                PACKAGE_NAME);

        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, never()).onCompatChange(PACKAGE_NAME);

        reset(mListener1);
        reset(mListener2);

        mPlatformCompat.registerListener(2, mListener2);

        mPlatformCompat.setOverrides(
                CompatibilityChangeConfigBuilder.create().enable(1L).disable(2L).build(),
                PACKAGE_NAME);

        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, times(1)).onCompatChange(PACKAGE_NAME);
    }

    @Test
    public void testListenerCalledOnClearOverrides() throws Exception {
        mPlatformCompat.registerListener(1, mListener1);
        mPlatformCompat.registerListener(2, mListener2);

        mPlatformCompat.setOverrides(
                CompatibilityChangeConfigBuilder.create().enable(1L).build(),
                PACKAGE_NAME);
        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, never()).onCompatChange(PACKAGE_NAME);

        reset(mListener1);
        reset(mListener2);

        mPlatformCompat.clearOverrides(PACKAGE_NAME);
        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, never()).onCompatChange(PACKAGE_NAME);
    }

    @Test
    public void testListenerCalledOnClearOverridesMultipleOverrides() throws Exception {
        mPlatformCompat.registerListener(1, mListener1);
        mPlatformCompat.registerListener(2, mListener2);

        mPlatformCompat.setOverrides(
                CompatibilityChangeConfigBuilder.create().enable(1L).disable(2L).build(),
                PACKAGE_NAME);
        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, times(1)).onCompatChange(PACKAGE_NAME);

        reset(mListener1);
        reset(mListener2);

        mPlatformCompat.clearOverrides(PACKAGE_NAME);
        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, times(1)).onCompatChange(PACKAGE_NAME);
    }

    @Test
    public void testListenerCalledOnClearOverrideExists() throws Exception {
        mPlatformCompat.registerListener(1, mListener1);
        mPlatformCompat.registerListener(2, mListener2);

        mPlatformCompat.setOverrides(
                CompatibilityChangeConfigBuilder.create().enable(1L).build(),
                PACKAGE_NAME);
        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, never()).onCompatChange(PACKAGE_NAME);

        reset(mListener1);
        reset(mListener2);

        mPlatformCompat.clearOverride(1, PACKAGE_NAME);
        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, never()).onCompatChange(PACKAGE_NAME);
    }

    @Test
    public void testListenerCalledOnClearOverrideDoesntExist() throws Exception {
        mPlatformCompat.registerListener(1, mListener1);

        mPlatformCompat.clearOverride(1, PACKAGE_NAME);
        // Listener not called when a non existing override is removed.
        verify(mListener1, never()).onCompatChange(PACKAGE_NAME);
    }
}
