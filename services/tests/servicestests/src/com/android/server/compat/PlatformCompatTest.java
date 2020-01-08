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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.internal.verification.VerificationModeFactory.times;
import static org.testng.Assert.assertThrows;

import android.compat.Compatibility;
import android.content.Context;
import android.content.pm.PackageManager;

import com.android.internal.compat.AndroidBuildClassifier;
import com.android.internal.compat.CompatibilityChangeConfig;

import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class PlatformCompatTest {
    private static final String PACKAGE_NAME = "my.package";

    @Mock
    private Context mContext;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    CompatChange.ChangeListener mListener1, mListener2;
    CompatConfig mCompatConfig;
    @Mock
    AndroidBuildClassifier mBuildClassifier;



    @Before
    public void setUp() throws Exception {
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.getPackageUid(eq(PACKAGE_NAME), eq(0))).thenThrow(
                new PackageManager.NameNotFoundException());
        mCompatConfig = new CompatConfig(mBuildClassifier, mContext);
        // Assume userdebug/eng non-final build
        when(mBuildClassifier.isDebuggableBuild()).thenReturn(true);
        when(mBuildClassifier.isFinalBuild()).thenReturn(false);
    }

    @Test
    public void testRegisterListenerToSameIdThrows() throws Exception {
        PlatformCompat pc = new PlatformCompat(mContext, mCompatConfig);

        // Registering a listener to change 1 is successful.
        pc.registerListener(1, mListener1);
        // Registering a listener to change 2 is successful.
        pc.registerListener(2, mListener1);
        // Trying to register another listener to change id 1 fails.
        assertThrows(IllegalStateException.class, () -> pc.registerListener(1, mListener1));
    }

    @Test
    public void testRegisterListenerReturn() throws Exception {
        PlatformCompat pc = new PlatformCompat(mContext, mCompatConfig);

        pc.setOverrides(
                new CompatibilityChangeConfig(
                        new Compatibility.ChangeConfig(ImmutableSet.of(1L), ImmutableSet.of())),
                PACKAGE_NAME);

        // Change id 1 is known (added in setOverrides).
        assertThat(pc.registerListener(1, mListener1)).isTrue();
        // Change 2 is unknown.
        assertThat(pc.registerListener(2, mListener1)).isFalse();
    }

    @Test
    public void testListenerCalledOnSetOverrides() throws Exception {
        PlatformCompat pc = new PlatformCompat(mContext, mCompatConfig);

        pc.registerListener(1, mListener1);
        pc.registerListener(2, mListener1);

        pc.setOverrides(
                new CompatibilityChangeConfig(
                        new Compatibility.ChangeConfig(ImmutableSet.of(1L), ImmutableSet.of(2L))),
                PACKAGE_NAME);

        verify(mListener1, times(2)).onCompatChange(PACKAGE_NAME);
    }

    @Test
    public void testListenerNotCalledOnWrongPackage() throws Exception {
        PlatformCompat pc = new PlatformCompat(mContext, mCompatConfig);

        pc.registerListener(1, mListener1);
        pc.registerListener(2, mListener1);

        pc.setOverridesForTest(
                new CompatibilityChangeConfig(
                        new Compatibility.ChangeConfig(ImmutableSet.of(1L), ImmutableSet.of(2L))),
                PACKAGE_NAME);

        verify(mListener1, never()).onCompatChange("other.package");
    }

    @Test
    public void testListenerCalledOnSetOverridesTwoListeners() throws Exception {
        PlatformCompat pc = new PlatformCompat(mContext, mCompatConfig);
        pc.registerListener(1, mListener1);

        final ImmutableSet<Long> enabled = ImmutableSet.of(1L);
        final ImmutableSet<Long> disabled = ImmutableSet.of(2L);

        pc.setOverrides(
                new CompatibilityChangeConfig(
                        new Compatibility.ChangeConfig(enabled, disabled)),
                PACKAGE_NAME);

        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, never()).onCompatChange(PACKAGE_NAME);

        reset(mListener1);
        reset(mListener2);

        pc.registerListener(2, mListener2);

        pc.setOverrides(
                new CompatibilityChangeConfig(
                        new Compatibility.ChangeConfig(enabled, disabled)),
                PACKAGE_NAME);

        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, times(1)).onCompatChange(PACKAGE_NAME);
    }

    @Test
    public void testListenerCalledOnSetOverridesForTest() throws Exception {
        PlatformCompat pc = new PlatformCompat(mContext, mCompatConfig);

        pc.registerListener(1, mListener1);
        pc.registerListener(2, mListener1);

        pc.setOverridesForTest(
                new CompatibilityChangeConfig(
                        new Compatibility.ChangeConfig(ImmutableSet.of(1L), ImmutableSet.of(2L))),
                PACKAGE_NAME);

        verify(mListener1, times(2)).onCompatChange(PACKAGE_NAME);
    }

    @Test
    public void testListenerCalledOnSetOverridesTwoListenersForTest() throws Exception {
        PlatformCompat pc = new PlatformCompat(mContext, mCompatConfig);
        pc.registerListener(1, mListener1);

        final ImmutableSet<Long> enabled = ImmutableSet.of(1L);
        final ImmutableSet<Long> disabled = ImmutableSet.of(2L);

        pc.setOverridesForTest(
                new CompatibilityChangeConfig(
                        new Compatibility.ChangeConfig(enabled, disabled)),
                PACKAGE_NAME);

        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, never()).onCompatChange(PACKAGE_NAME);

        reset(mListener1);
        reset(mListener2);

        pc.registerListener(2, mListener2);
        pc.setOverridesForTest(
                new CompatibilityChangeConfig(
                        new Compatibility.ChangeConfig(enabled, disabled)),
                PACKAGE_NAME);

        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, times(1)).onCompatChange(PACKAGE_NAME);
    }

    @Test
    public void testListenerCalledOnClearOverrides() throws Exception {
        PlatformCompat pc = new PlatformCompat(mContext, mCompatConfig);

        pc.registerListener(1, mListener1);
        pc.registerListener(2, mListener2);

        pc.setOverrides(
                new CompatibilityChangeConfig(
                        new Compatibility.ChangeConfig(ImmutableSet.of(1L), ImmutableSet.of())),
                PACKAGE_NAME);
        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, never()).onCompatChange(PACKAGE_NAME);

        reset(mListener1);
        reset(mListener2);

        pc.clearOverrides(PACKAGE_NAME);
        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, never()).onCompatChange(PACKAGE_NAME);
    }

    @Test
    public void testListenerCalledOnClearOverridesMultipleOverrides() throws Exception {
        PlatformCompat pc = new PlatformCompat(mContext, mCompatConfig);

        pc.registerListener(1, mListener1);
        pc.registerListener(2, mListener2);

        pc.setOverrides(
                new CompatibilityChangeConfig(
                        new Compatibility.ChangeConfig(ImmutableSet.of(1L), ImmutableSet.of(2L))),
                PACKAGE_NAME);
        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, times(1)).onCompatChange(PACKAGE_NAME);

        reset(mListener1);
        reset(mListener2);

        pc.clearOverrides(PACKAGE_NAME);
        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, times(1)).onCompatChange(PACKAGE_NAME);
    }

    @Test
    public void testListenerCalledOnClearOverrideExists() throws Exception {
        PlatformCompat pc = new PlatformCompat(mContext, mCompatConfig);

        pc.registerListener(1, mListener1);
        pc.registerListener(2, mListener2);

        pc.setOverrides(
                new CompatibilityChangeConfig(
                        new Compatibility.ChangeConfig(ImmutableSet.of(1L), ImmutableSet.of())),
                PACKAGE_NAME);
        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, never()).onCompatChange(PACKAGE_NAME);

        reset(mListener1);
        reset(mListener2);

        pc.clearOverride(1, PACKAGE_NAME);
        verify(mListener1, times(1)).onCompatChange(PACKAGE_NAME);
        verify(mListener2, never()).onCompatChange(PACKAGE_NAME);
    }

    @Test
    public void testListenerCalledOnClearOverrideDoesntExist() throws Exception {
        PlatformCompat pc = new PlatformCompat(mContext, mCompatConfig);

        pc.registerListener(1, mListener1);

        pc.clearOverride(1, PACKAGE_NAME);
        // Listener not called when a non existing override is removed.
        verify(mListener1, never()).onCompatChange(PACKAGE_NAME);
    }


}
