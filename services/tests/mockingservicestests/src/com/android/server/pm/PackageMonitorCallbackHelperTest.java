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

package com.android.server.pm;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;

/**
 * A unit test for PackageMonitorCallbackHelper implementation.
 */
@RunWith(JUnit4.class)
public class PackageMonitorCallbackHelperTest {

    private static final String FAKE_PACKAGE_NAME = "com.android.server.pm.fakeapp";
    private static final int FAKE_PACKAGE_UID = 123;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    PackageMonitorCallbackHelper mPackageMonitorCallbackHelper;

    @Rule
    public final MockSystemRule mMockSystem = new MockSystemRule();

    @Before
    public void setup() {
        when(mMockSystem.mocks().getInjector().getHandler()).thenReturn(mHandler);
        mPackageMonitorCallbackHelper = new PackageMonitorCallbackHelper(
                mMockSystem.mocks().getInjector());
    }


    @After
    public void teardown() {
        mPackageMonitorCallbackHelper = null;
    }

    @Test
    public void testWithoutRegisterPackageMonitorCallback_callbackNotCalled() throws Exception {
        IRemoteCallback callback = createMockPackageMonitorCallback();

        mPackageMonitorCallbackHelper.notifyPackageMonitor(Intent.ACTION_PACKAGE_ADDED,
                FAKE_PACKAGE_NAME, createFakeBundle(), new int[]{0} /* userIds */);
        Thread.sleep(300);

        verify(callback, never()).sendResult(any());
    }

    @Test
    public void testUnregisterPackageMonitorCallback_callbackShouldNotCalled() throws Exception {
        IRemoteCallback callback = createMockPackageMonitorCallback();

        mPackageMonitorCallbackHelper.registerPackageMonitorCallback(callback, 0 /* userId */);
        mPackageMonitorCallbackHelper.notifyPackageMonitor(Intent.ACTION_PACKAGE_ADDED,
                FAKE_PACKAGE_NAME, createFakeBundle(), new int[]{0});
        Thread.sleep(300);

        verify(callback, times(1)).sendResult(any());

        reset(callback);
        mPackageMonitorCallbackHelper.unregisterPackageMonitorCallback(callback);
        mPackageMonitorCallbackHelper.notifyPackageMonitor(Intent.ACTION_PACKAGE_ADDED,
                FAKE_PACKAGE_NAME, createFakeBundle(), new int[]{0} /* userIds */);
        Thread.sleep(300);

        verify(callback, never()).sendResult(any());
    }

    @Test
    public void testRegisterPackageMonitorCallback_callbackCalled() throws Exception {
        IRemoteCallback callback = createMockPackageMonitorCallback();

        mPackageMonitorCallbackHelper.registerPackageMonitorCallback(callback, 0 /* userId */);
        mPackageMonitorCallbackHelper.notifyPackageMonitor(Intent.ACTION_PACKAGE_ADDED,
                FAKE_PACKAGE_NAME, createFakeBundle(), new int[]{0} /* userIds */);
        Thread.sleep(300);

        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(callback, times(1)).sendResult(bundleCaptor.capture());
        Bundle bundle = bundleCaptor.getValue();
        Intent intent = bundle.getParcelable(
                PackageManager.EXTRA_PACKAGE_MONITOR_CALLBACK_RESULT, Intent.class);
        assertThat(intent).isNotNull();
        assertThat(intent.getAction()).isEqualTo(Intent.ACTION_PACKAGE_ADDED);
        assertThat(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1)).isEqualTo(0);
    }

    @Test
    public void testRegisterPackageMonitorCallbackUserNotMatch_callbackShouldNotCalled()
            throws Exception {
        IRemoteCallback callback = createMockPackageMonitorCallback();

        // Register for user 0
        mPackageMonitorCallbackHelper.registerPackageMonitorCallback(callback, 0 /* userId */);
        // Notify for user 10
        mPackageMonitorCallbackHelper.notifyPackageMonitor(Intent.ACTION_PACKAGE_ADDED,
                FAKE_PACKAGE_NAME, createFakeBundle(), new int[]{10} /* userIds */);
        Thread.sleep(300);

        verify(callback, never()).sendResult(any());
    }

    @Test
    public void testNotifyPackageChanged_callbackCalled() throws Exception {
        IRemoteCallback callback = createMockPackageMonitorCallback();

        ArrayList<String> components = new ArrayList<>();
        String component1 = FAKE_PACKAGE_NAME + "/.Component1";
        components.add(component1);
        mPackageMonitorCallbackHelper.registerPackageMonitorCallback(callback, 0 /* userId */);
        mPackageMonitorCallbackHelper.notifyPackageChanged(FAKE_PACKAGE_NAME,
                false /* dontKillApp */, components, FAKE_PACKAGE_UID, null /* reason */,
                new int[]{0} /* userIds */);
        Thread.sleep(300);

        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(callback, times(1)).sendResult(bundleCaptor.capture());
        Bundle bundle = bundleCaptor.getValue();
        Intent intent = bundle.getParcelable(
                PackageManager.EXTRA_PACKAGE_MONITOR_CALLBACK_RESULT, Intent.class);
        assertThat(intent).isNotNull();
        assertThat(intent.getAction()).isEqualTo(Intent.ACTION_PACKAGE_CHANGED);
        assertThat(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1)).isEqualTo(0);
        assertThat(intent.getStringExtra(Intent.EXTRA_REASON)).isNull();
        assertThat(intent.getBooleanExtra(Intent.EXTRA_DONT_KILL_APP, true)).isFalse();
        assertThat(intent.getIntExtra(Intent.EXTRA_UID, -1)).isEqualTo(FAKE_PACKAGE_UID);
        String[] result = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST);
        assertThat(result).isNotNull();
        assertThat(result.length).isEqualTo(1);
        assertThat(result[0]).isEqualTo(component1);
    }

    @Test
    public void testNotifyPackageAddedForNewUsers_callbackCalled() throws Exception {
        IRemoteCallback callback = createMockPackageMonitorCallback();

        mPackageMonitorCallbackHelper.registerPackageMonitorCallback(callback, 0 /* userId */);
        mPackageMonitorCallbackHelper.notifyPackageAddedForNewUsers(FAKE_PACKAGE_NAME,
                FAKE_PACKAGE_UID, new int[]{0} /* userIds */, new int[0],
                PackageInstaller.DATA_LOADER_TYPE_STREAMING);
        Thread.sleep(300);

        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(callback, times(1)).sendResult(bundleCaptor.capture());
        Bundle bundle = bundleCaptor.getValue();
        Intent intent = bundle.getParcelable(
                PackageManager.EXTRA_PACKAGE_MONITOR_CALLBACK_RESULT, Intent.class);
        assertThat(intent).isNotNull();
        assertThat(intent.getAction()).isEqualTo(Intent.ACTION_PACKAGE_ADDED);
        assertThat(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1)).isEqualTo(0);
        assertThat(intent.getIntExtra(PackageInstaller.EXTRA_DATA_LOADER_TYPE, -1)).isEqualTo(
                PackageInstaller.DATA_LOADER_TYPE_STREAMING);
        assertThat(intent.getIntExtra(Intent.EXTRA_UID, -1)).isEqualTo(FAKE_PACKAGE_UID);
    }

    @Test
    public void testNotifyResourcesChanged_callbackCalled() throws Exception {
        IRemoteCallback callback = createMockPackageMonitorCallback();

        mPackageMonitorCallbackHelper.registerPackageMonitorCallback(callback, 0 /* userId */);
        mPackageMonitorCallbackHelper.notifyResourcesChanged(true /* mediaStatus */,
                true /* replacing */, new String[]{FAKE_PACKAGE_NAME},
                new int[]{FAKE_PACKAGE_UID} /* uids */);
        Thread.sleep(300);

        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(callback, times(1)).sendResult(bundleCaptor.capture());
        Bundle bundle = bundleCaptor.getValue();
        Intent intent = bundle.getParcelable(
                PackageManager.EXTRA_PACKAGE_MONITOR_CALLBACK_RESULT, Intent.class);
        assertThat(intent).isNotNull();
        assertThat(intent.getAction()).isEqualTo(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        assertThat(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1)).isEqualTo(0);
        assertThat(intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)).isTrue();

        int[] uids = intent.getIntArrayExtra(Intent.EXTRA_CHANGED_UID_LIST);
        assertThat(uids).isNotNull();
        assertThat(uids.length).isEqualTo(1);
        assertThat(uids[0]).isEqualTo(FAKE_PACKAGE_UID);

        String[] pkgNames = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
        assertThat(pkgNames).isNotNull();
        assertThat(pkgNames.length).isEqualTo(1);
        assertThat(pkgNames[0]).isEqualTo(FAKE_PACKAGE_NAME);
    }

    private IRemoteCallback createMockPackageMonitorCallback() {
        return spy(new IRemoteCallback.Stub() {
            @Override
            public void sendResult(Bundle data) {
                // no op
            }
        });
    }

    private Bundle createFakeBundle() {
        Bundle bundle = new Bundle();
        bundle.putInt(Intent.EXTRA_UID, FAKE_PACKAGE_UID);
        return bundle;
    }
}
