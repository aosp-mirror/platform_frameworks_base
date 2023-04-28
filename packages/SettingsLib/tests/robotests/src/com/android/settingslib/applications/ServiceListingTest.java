/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.applications;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.provider.Settings;

import androidx.test.core.app.ApplicationProvider;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class ServiceListingTest {

    private static final String TEST_SETTING = "testSetting";
    private static final String TEST_INTENT = "com.example.intent";

    private ServiceListing mServiceListing;
    private Context mContext;
    private PackageManager mPm;

    @Before
    public void setUp() {
        mPm = mock(PackageManager.class);
        mContext = spy(ApplicationProvider.getApplicationContext());
        when(mContext.getPackageManager()).thenReturn(mPm);

        mServiceListing = new ServiceListing.Builder(mContext)
                .setTag("testTag")
                .setSetting(TEST_SETTING)
                .setNoun("testNoun")
                .setIntentAction(TEST_INTENT)
                .setPermission("testPermission")
                .build();
    }

    @Test
    public void testValidator() {
        ServiceInfo s1 = new ServiceInfo();
        s1.permission = "testPermission";
        s1.packageName = "pkg";
        ServiceInfo s2 = new ServiceInfo();
        s2.permission = "testPermission";
        s2.packageName = "pkg2";
        ResolveInfo r1 = new ResolveInfo();
        r1.serviceInfo = s1;
        ResolveInfo r2 = new ResolveInfo();
        r2.serviceInfo = s2;

        when(mPm.queryIntentServicesAsUser(any(), anyInt(), anyInt())).thenReturn(
                ImmutableList.of(r1, r2));

        mServiceListing = new ServiceListing.Builder(mContext)
                .setTag("testTag")
                .setSetting(TEST_SETTING)
                .setNoun("testNoun")
                .setIntentAction(TEST_INTENT)
                .setValidator(info -> {
                    if (info.packageName.equals("pkg")) {
                        return true;
                    }
                    return false;
                })
                .setPermission("testPermission")
                .build();
        ServiceListing.Callback callback = mock(ServiceListing.Callback.class);
        mServiceListing.addCallback(callback);
        mServiceListing.reload();

        verify(mPm).queryIntentServicesAsUser(any(), anyInt(), anyInt());
        ArgumentCaptor<List<ServiceInfo>> captor = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onServicesReloaded(captor.capture());

        assertThat(captor.getValue().size()).isEqualTo(1);
        assertThat(captor.getValue().get(0)).isEqualTo(s1);
    }

    @Test
    public void testNoValidator() {
        ServiceInfo s1 = new ServiceInfo();
        s1.permission = "testPermission";
        s1.packageName = "pkg";
        ServiceInfo s2 = new ServiceInfo();
        s2.permission = "testPermission";
        s2.packageName = "pkg2";
        ResolveInfo r1 = new ResolveInfo();
        r1.serviceInfo = s1;
        ResolveInfo r2 = new ResolveInfo();
        r2.serviceInfo = s2;

        when(mPm.queryIntentServicesAsUser(any(), anyInt(), anyInt())).thenReturn(
                ImmutableList.of(r1, r2));

        mServiceListing = new ServiceListing.Builder(mContext)
                .setTag("testTag")
                .setSetting(TEST_SETTING)
                .setNoun("testNoun")
                .setIntentAction(TEST_INTENT)
                .setPermission("testPermission")
                .build();
        ServiceListing.Callback callback = mock(ServiceListing.Callback.class);
        mServiceListing.addCallback(callback);
        mServiceListing.reload();

        verify(mPm).queryIntentServicesAsUser(any(), anyInt(), anyInt());
        ArgumentCaptor<List<ServiceInfo>> captor = ArgumentCaptor.forClass(List.class);
        verify(callback, times(1)).onServicesReloaded(captor.capture());

        assertThat(captor.getValue().size()).isEqualTo(2);
    }

    @Test
    public void testCallback() {
        ServiceListing.Callback callback = mock(ServiceListing.Callback.class);
        mServiceListing.addCallback(callback);
        mServiceListing.reload();
        verify(callback, times(1)).onServicesReloaded(anyList());
        mServiceListing.removeCallback(callback);
        mServiceListing.reload();
        verify(callback, times(1)).onServicesReloaded(anyList());
    }

    @Test
    public void testSaveLoad() {
        ComponentName testComponent1 = new ComponentName("testPackage1", "testClass1");
        ComponentName testComponent2 = new ComponentName("testPackage2", "testClass2");
        Settings.Secure.putString(RuntimeEnvironment.application.getContentResolver(),
                TEST_SETTING,
                testComponent1.flattenToString() + ":" + testComponent2.flattenToString());

        mServiceListing.reload();

        assertThat(mServiceListing.isEnabled(testComponent1)).isTrue();
        assertThat(mServiceListing.isEnabled(testComponent2)).isTrue();
        assertThat(Settings.Secure.getString(RuntimeEnvironment.application.getContentResolver(),
                TEST_SETTING)).contains(testComponent1.flattenToString());
        assertThat(Settings.Secure.getString(RuntimeEnvironment.application.getContentResolver(),
                TEST_SETTING)).contains(testComponent2.flattenToString());

        mServiceListing.setEnabled(testComponent1, false);

        assertThat(mServiceListing.isEnabled(testComponent1)).isFalse();
        assertThat(mServiceListing.isEnabled(testComponent2)).isTrue();
        assertThat(Settings.Secure.getString(RuntimeEnvironment.application.getContentResolver(),
                TEST_SETTING)).doesNotContain(testComponent1.flattenToString());
        assertThat(Settings.Secure.getString(RuntimeEnvironment.application.getContentResolver(),
                TEST_SETTING)).contains(testComponent2.flattenToString());

        mServiceListing.setEnabled(testComponent1, true);

        assertThat(mServiceListing.isEnabled(testComponent1)).isTrue();
        assertThat(mServiceListing.isEnabled(testComponent2)).isTrue();
        assertThat(Settings.Secure.getString(RuntimeEnvironment.application.getContentResolver(),
                TEST_SETTING)).contains(testComponent1.flattenToString());
        assertThat(Settings.Secure.getString(RuntimeEnvironment.application.getContentResolver(),
                TEST_SETTING)).contains(testComponent2.flattenToString());
    }
}
