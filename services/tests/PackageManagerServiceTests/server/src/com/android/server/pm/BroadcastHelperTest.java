/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.content.pm.Flags.FLAG_REDUCE_BROADCASTS_FOR_COMPONENT_STATE_CHANGES;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.os.UserHandle;
import android.platform.test.annotations.AppModeFull;
import android.platform.test.annotations.AppModeNonSdkSandbox;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.pm.parsing.pkg.AndroidPackageInternal;
import com.android.internal.pm.pkg.component.ParsedActivity;
import com.android.server.pm.pkg.PackageStateInternal;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@AppModeFull
@AppModeNonSdkSandbox
@RunWith(AndroidJUnit4.class)
public class BroadcastHelperTest {
    private static final String TAG = "BroadcastHelperTest";
    private static final String PACKAGE_CHANGED_TEST_PACKAGE_NAME = "testpackagename";
    private static final String PACKAGE_CHANGED_TEST_MAIN_ACTIVITY =
            PACKAGE_CHANGED_TEST_PACKAGE_NAME + ".MainActivity";
    private static final String PERMISSION_PACKAGE_CHANGED_BROADCAST_ON_COMPONENT_STATE_CHANGED =
            "android.permission.INTERNAL_RECEIVE_PACKAGE_CHANGED_BROADCAST_ON_COMPONENT_STATE_CHANGED";

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Mock
    ActivityManagerInternal mMockActivityManagerInternal;
    @Mock
    AndroidPackageInternal mMockAndroidPackageInternal;
    @Mock
    Computer mMockSnapshot;
    @Mock
    Handler mMockHandler;
    @Mock
    PackageManagerServiceInjector mMockPackageManagerServiceInjector;
    @Mock
    PackageMonitorCallbackHelper mMockPackageMonitorCallbackHelper;
    @Mock
    PackageStateInternal mMockPackageStateInternal;
    @Mock
    ParsedActivity mMockParsedActivity;
    @Mock
    UserManagerInternal mMockUserManagerInternal;

    private Context mContext;
    private BroadcastHelper mBroadcastHelper;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        mContext = InstrumentationRegistry.getInstrumentation().getContext();

        when(mMockHandler.sendMessageAtTime(any(Message.class), anyLong())).thenAnswer(
                i -> {
                    ((Message) i.getArguments()[0]).getCallback().run();
                    return true;
                });
        when(mMockPackageManagerServiceInjector.getActivityManagerInternal()).thenReturn(
                mMockActivityManagerInternal);
        when(mMockPackageManagerServiceInjector.getContext()).thenReturn(mContext);
        when(mMockPackageManagerServiceInjector.getHandler()).thenReturn(mMockHandler);
        when(mMockPackageManagerServiceInjector.getPackageMonitorCallbackHelper()).thenReturn(
                mMockPackageMonitorCallbackHelper);
        when(mMockPackageManagerServiceInjector.getUserManagerInternal()).thenReturn(
                mMockUserManagerInternal);

        mBroadcastHelper = new BroadcastHelper(mMockPackageManagerServiceInjector);
    }

    @RequiresFlagsEnabled(FLAG_REDUCE_BROADCASTS_FOR_COMPONENT_STATE_CHANGES)
    @Test
    public void changeNonExportedComponent_sendPackageChangedBroadcastToSystem_withPermission()
            throws Exception {
        changeComponentAndSendPackageChangedBroadcast(false /* changeExportedComponent */,
                new String[0] /* sharedPackages */);

        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mMockActivityManagerInternal).broadcastIntentWithCallback(
                captor.capture(), eq(null),
                eq(new String[]{PERMISSION_PACKAGE_CHANGED_BROADCAST_ON_COMPONENT_STATE_CHANGED}),
                anyInt(), eq(null), eq(null), eq(null));
        Intent intent = captor.getValue();
        assertNotNull(intent);
        assertThat(intent.getPackage()).isEqualTo("android");
    }

    @RequiresFlagsEnabled(FLAG_REDUCE_BROADCASTS_FOR_COMPONENT_STATE_CHANGES)
    @Test
    public void changeNonExportedComponent_sendPackageChangedBroadcastToApplicationItself()
            throws Exception {
        changeComponentAndSendPackageChangedBroadcast(false /* changeExportedComponent */,
                new String[0] /* sharedPackages */);

        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mMockActivityManagerInternal).broadcastIntentWithCallback(captor.capture(), eq(null),
                eq(null), anyInt(), eq(null), eq(null), eq(null));
        Intent intent = captor.getValue();
        assertNotNull(intent);
        assertThat(intent.getPackage()).isEqualTo(PACKAGE_CHANGED_TEST_PACKAGE_NAME);
    }

    @RequiresFlagsEnabled(FLAG_REDUCE_BROADCASTS_FOR_COMPONENT_STATE_CHANGES)
    @Test
    public void changeNonExportedComponent_sendPackageChangedBroadcastToSharedUserIdApplications()
            throws Exception {
        changeComponentAndSendPackageChangedBroadcast(false /* changeExportedComponent */,
                new String[]{"shared.package"} /* sharedPackages */);

        ArgumentCaptor<Intent> captorIntent = ArgumentCaptor.forClass(Intent.class);
        ArgumentCaptor<String[]> captorRequiredPermissions = ArgumentCaptor.forClass(
                String[].class);
        verify(mMockActivityManagerInternal, times(3)).broadcastIntentWithCallback(
                captorIntent.capture(), eq(null), captorRequiredPermissions.capture(), anyInt(),
                eq(null), eq(null), eq(null));
        List<Intent> intents = captorIntent.getAllValues();
        List<String[]> requiredPermissions = captorRequiredPermissions.getAllValues();
        assertNotNull(intents);
        assertThat(intents.size()).isEqualTo(3);

        final Intent intent1 = intents.get(0);
        final String[] requiredPermission1 = requiredPermissions.get(0);
        assertThat(intent1.getPackage()).isEqualTo("android");
        assertThat(requiredPermission1).isEqualTo(
                new String[]{PERMISSION_PACKAGE_CHANGED_BROADCAST_ON_COMPONENT_STATE_CHANGED});

        final Intent intent2 = intents.get(1);
        final String[] requiredPermission2 = requiredPermissions.get(1);
        assertThat(intent2.getPackage()).isEqualTo(PACKAGE_CHANGED_TEST_PACKAGE_NAME);
        assertThat(requiredPermission2).isNull();

        final Intent intent3 = intents.get(2);
        final String[] requiredPermission3 = requiredPermissions.get(2);
        assertThat(intent3.getPackage()).isEqualTo("shared.package");
        assertThat(requiredPermission3).isNull();
    }

    @Test
    public void changeExportedComponent_sendPackageChangedBroadcastToAll() throws Exception {
        changeComponentAndSendPackageChangedBroadcast(true /* changeExportedComponent */,
                new String[0] /* sharedPackages */);

        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mMockActivityManagerInternal).broadcastIntentWithCallback(captor.capture(), eq(null),
                eq(null), anyInt(), eq(null), eq(null), eq(null));
        Intent intent = captor.getValue();
        assertNotNull(intent);
        assertNull(intent.getPackage());
    }

    private void changeComponentAndSendPackageChangedBroadcast(boolean changeExportedComponent,
            String[] sharedPackages) {
        when(mMockSnapshot.getPackageStateInternal(eq(PACKAGE_CHANGED_TEST_PACKAGE_NAME),
                anyInt())).thenReturn(mMockPackageStateInternal);
        when(mMockSnapshot.isInstantAppInternal(any(), anyInt(), anyInt())).thenReturn(false);
        when(mMockSnapshot.getVisibilityAllowLists(any(), any())).thenReturn(null);
        when(mMockSnapshot.getSharedUserPackagesForPackage(eq(PACKAGE_CHANGED_TEST_PACKAGE_NAME),
                anyInt())).thenReturn(sharedPackages);
        when(mMockPackageStateInternal.getPkg()).thenReturn(mMockAndroidPackageInternal);

        when(mMockParsedActivity.getClassName()).thenReturn(
                PACKAGE_CHANGED_TEST_MAIN_ACTIVITY);
        when(mMockParsedActivity.isExported()).thenReturn(changeExportedComponent);
        ArrayList<ParsedActivity> parsedActivities = new ArrayList<>();
        parsedActivities.add(mMockParsedActivity);

        when(mMockAndroidPackageInternal.getReceivers()).thenReturn(new ArrayList<>());
        when(mMockAndroidPackageInternal.getProviders()).thenReturn(new ArrayList<>());
        when(mMockAndroidPackageInternal.getServices()).thenReturn(new ArrayList<>());
        when(mMockAndroidPackageInternal.getActivities()).thenReturn(parsedActivities);

        doNothing().when(mMockPackageMonitorCallbackHelper).notifyPackageChanged(any(),
                anyBoolean(), any(), anyInt(), any(), any(), any(), any(), any());
        when(mMockActivityManagerInternal.broadcastIntentWithCallback(any(), any(), any(), anyInt(),
                any(), any(), any())).thenReturn(ActivityManager.BROADCAST_SUCCESS);

        ArrayList<String> componentNames = new ArrayList<>();
        componentNames.add(PACKAGE_CHANGED_TEST_MAIN_ACTIVITY);

        mBroadcastHelper.sendPackageChangedBroadcast(mMockSnapshot,
                PACKAGE_CHANGED_TEST_PACKAGE_NAME, true /* dontKillApp */, componentNames,
                UserHandle.USER_SYSTEM, "test" /* reason */, "test" /* reasonForTrace */);
    }
}
