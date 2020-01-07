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

package com.android.internal.telephony.tests;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.UserHandle;
import android.provider.Telephony;
import android.telephony.TelephonyManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.telephony.SmsApplication;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.List;

/**
 * Unit tests for the {@link SmsApplication} utility class
 */
@RunWith(AndroidJUnit4.class)
public class SmsApplicationTest {
    private static final ComponentName TEST_COMPONENT_NAME =
            ComponentName.unflattenFromString("com.android.test/.TestSmsApp");
    private static final String MMS_RECEIVER_NAME = "TestMmsReceiver";
    private static final String RESPOND_VIA_SMS_NAME = "TestRespondViaSmsHandler";
    private static final String SEND_TO_NAME = "TestSendTo";
    private static final int SMS_APP_UID = 10001;

    private static final int FAKE_PHONE_UID = 10002;
    private static final int FAKE_MMS_UID = 10003;
    private static final int FAKE_BT_UID = 10004;
    private static final int FAKE_TELEPHONY_PROVIDER_UID = 10005;

    private static final String[] APP_OPS_TO_CHECK = {
            AppOpsManager.OPSTR_READ_SMS,
            AppOpsManager.OPSTR_WRITE_SMS,
            AppOpsManager.OPSTR_RECEIVE_SMS,
            AppOpsManager.OPSTR_RECEIVE_WAP_PUSH,
            AppOpsManager.OPSTR_SEND_SMS,
            AppOpsManager.OPSTR_READ_CELL_BROADCASTS
    };

    @Mock private Context mContext;
    @Mock private TelephonyManager mTelephonyManager;
    @Mock private RoleManager mRoleManager;
    @Mock private PackageManager mPackageManager;
    @Mock private AppOpsManager mAppOpsManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(mContext.getSystemService(Context.TELEPHONY_SERVICE)).thenReturn(mTelephonyManager);
        when(mContext.getSystemService(Context.ROLE_SERVICE)).thenReturn(mRoleManager);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mContext.getSystemService(RoleManager.class)).thenReturn(mRoleManager);
        when(mContext.getSystemService(AppOpsManager.class)).thenReturn(mAppOpsManager);

        doAnswer(invocation -> getResolveInfosForIntent(invocation.getArgument(0)))
                .when(mPackageManager)
                .queryBroadcastReceiversAsUser(nullable(Intent.class), anyInt(), anyInt());
        doAnswer(invocation -> getResolveInfosForIntent(invocation.getArgument(0)))
                .when(mPackageManager)
                .queryIntentActivitiesAsUser(nullable(Intent.class), anyInt(), anyInt());
        doAnswer(invocation -> getResolveInfosForIntent(invocation.getArgument(0)))
                .when(mPackageManager)
                .queryIntentServicesAsUser(nullable(Intent.class), anyInt(),
                        nullable(UserHandle.class));

        when(mTelephonyManager.isSmsCapable()).thenReturn(true);
        when(mRoleManager.isRoleAvailable(RoleManager.ROLE_SMS)).thenReturn(true);
        when(mRoleManager.getDefaultSmsPackage(anyInt()))
                .thenReturn(TEST_COMPONENT_NAME.getPackageName());

        for (String opStr : APP_OPS_TO_CHECK) {
            when(mAppOpsManager.unsafeCheckOp(
                    opStr, SMS_APP_UID, TEST_COMPONENT_NAME.getPackageName()))
                    .thenReturn(AppOpsManager.MODE_ALLOWED);
        }
    }

    @Test
    public void testGetDefaultSmsApplication() {
        assertEquals(TEST_COMPONENT_NAME,
                SmsApplication.getDefaultSmsApplicationAsUser(mContext, false, 0));
    }

    @Test
    public void testGetDefaultSmsApplicationWithAppOpsFix() throws Exception {
        when(mAppOpsManager.unsafeCheckOp(AppOpsManager.OPSTR_READ_SMS, SMS_APP_UID,
                TEST_COMPONENT_NAME.getPackageName()))
                .thenReturn(AppOpsManager.MODE_IGNORED);
        setupPackageInfosForCoreApps();

        assertEquals(TEST_COMPONENT_NAME,
                SmsApplication.getDefaultSmsApplicationAsUser(mContext, true, 0));
        verify(mAppOpsManager, atLeastOnce()).setUidMode(AppOpsManager.OPSTR_READ_SMS, SMS_APP_UID,
                AppOpsManager.MODE_ALLOWED);
    }

    private void setupPackageInfosForCoreApps() throws Exception {
        PackageInfo phonePackageInfo = new PackageInfo();
        ApplicationInfo phoneApplicationInfo = new ApplicationInfo();
        phoneApplicationInfo.uid = FAKE_PHONE_UID;
        phonePackageInfo.applicationInfo = phoneApplicationInfo;
        when(mPackageManager.getPackageInfo(eq(SmsApplication.PHONE_PACKAGE_NAME), anyInt()))
                .thenReturn(phonePackageInfo);

        PackageInfo mmsPackageInfo = new PackageInfo();
        ApplicationInfo mmsApplicationInfo = new ApplicationInfo();
        mmsApplicationInfo.uid = FAKE_MMS_UID;
        mmsPackageInfo.applicationInfo = mmsApplicationInfo;
        when(mPackageManager.getPackageInfo(eq(SmsApplication.MMS_SERVICE_PACKAGE_NAME), anyInt()))
                .thenReturn(mmsPackageInfo);

        PackageInfo bluetoothPackageInfo = new PackageInfo();
        ApplicationInfo bluetoothApplicationInfo = new ApplicationInfo();
        bluetoothApplicationInfo.uid = FAKE_BT_UID;
        bluetoothPackageInfo.applicationInfo = bluetoothApplicationInfo;
        when(mPackageManager.getPackageInfo(eq(SmsApplication.BLUETOOTH_PACKAGE_NAME), anyInt()))
                .thenReturn(bluetoothPackageInfo);

        PackageInfo telephonyProviderPackageInfo = new PackageInfo();
        ApplicationInfo telephonyProviderApplicationInfo = new ApplicationInfo();
        telephonyProviderApplicationInfo.uid = FAKE_TELEPHONY_PROVIDER_UID;
        telephonyProviderPackageInfo.applicationInfo = telephonyProviderApplicationInfo;
        when(mPackageManager.getPackageInfo(
                eq(SmsApplication.TELEPHONY_PROVIDER_PACKAGE_NAME), anyInt()))
                .thenReturn(telephonyProviderPackageInfo);
    }

    private List<ResolveInfo> getResolveInfosForIntent(Intent intent) {
        switch (intent.getAction()) {
            case Telephony.Sms.Intents.SMS_DELIVER_ACTION:
                return Collections.singletonList(makeSmsDeliverResolveInfo());
            case Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION:
                return Collections.singletonList(makeWapPushResolveInfo());
            case TelephonyManager.ACTION_RESPOND_VIA_MESSAGE:
                return Collections.singletonList(makeRespondViaMessageResolveInfo());
            case Intent.ACTION_SENDTO:
                return Collections.singletonList(makeSendToResolveInfo());
        }
        return Collections.emptyList();
    }

    private ApplicationInfo makeSmsApplicationInfo() {
        ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = SMS_APP_UID;
        return applicationInfo;
    }

    private ResolveInfo makeSmsDeliverResolveInfo() {
        ResolveInfo info = new ResolveInfo();
        ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.applicationInfo = makeSmsApplicationInfo();

        activityInfo.permission = Manifest.permission.BROADCAST_SMS;
        activityInfo.packageName = TEST_COMPONENT_NAME.getPackageName();
        activityInfo.name = TEST_COMPONENT_NAME.getClassName();

        info.activityInfo = activityInfo;
        return info;
    }

    private ResolveInfo makeWapPushResolveInfo() {
        ResolveInfo info = new ResolveInfo();
        ActivityInfo activityInfo = new ActivityInfo();

        activityInfo.permission = Manifest.permission.BROADCAST_WAP_PUSH;
        activityInfo.packageName = TEST_COMPONENT_NAME.getPackageName();
        activityInfo.name = MMS_RECEIVER_NAME;

        info.activityInfo = activityInfo;
        return info;
    }

    private ResolveInfo makeRespondViaMessageResolveInfo() {
        ResolveInfo info = new ResolveInfo();
        ServiceInfo serviceInfo = new ServiceInfo();

        serviceInfo.permission = Manifest.permission.SEND_RESPOND_VIA_MESSAGE;
        serviceInfo.packageName = TEST_COMPONENT_NAME.getPackageName();
        serviceInfo.name = RESPOND_VIA_SMS_NAME;

        info.serviceInfo = serviceInfo;
        return info;
    }

    private ResolveInfo makeSendToResolveInfo() {
        ResolveInfo info = new ResolveInfo();
        ActivityInfo activityInfo = new ActivityInfo();

        activityInfo.packageName = TEST_COMPONENT_NAME.getPackageName();
        activityInfo.name = SEND_TO_NAME;

        info.activityInfo = activityInfo;
        return info;
    }
}
