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

package com.android.internal.app;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.test.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class IntentForwarderActivityTest {

    private static final ComponentName FORWARD_TO_MANAGED_PROFILE_COMPONENT_NAME =
            new ComponentName(
                    "android",
                    IntentForwarderActivity.FORWARD_INTENT_TO_MANAGED_PROFILE
            );
    private static final String TYPE_PLAIN_TEXT = "text/plain";

    private static UserInfo MANAGED_PROFILE_INFO = new UserInfo();

    static {
        MANAGED_PROFILE_INFO.id = 10;
        MANAGED_PROFILE_INFO.flags = UserInfo.FLAG_MANAGED_PROFILE;
    }

    private static UserInfo CURRENT_USER_INFO = new UserInfo();

    static {
        CURRENT_USER_INFO.id = UserHandle.myUserId();
        CURRENT_USER_INFO.flags = 0;
    }

    private static IntentForwarderActivity.Injector sInjector;
    private static ComponentName sComponentName;
    private static String sActivityName;
    private static String sPackageName;

    @Mock
    private IPackageManager mIPm;
    @Mock
    private PackageManager mPm;
    @Mock
    private UserManager mUserManager;
    @Mock
    private ApplicationInfo mApplicationInfo;

    @Rule
    public ActivityTestRule<IntentForwarderWrapperActivity> mActivityRule =
            new ActivityTestRule<>(IntentForwarderWrapperActivity.class, true, false);

    private Context mContext;
    public static final String PHONE_NUMBER = "123-456-789";

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mContext = InstrumentationRegistry.getTargetContext();
        sInjector = spy(new TestInjector());
    }

    @Test
    public void forwardToManagedProfile_canForward_sendIntent() throws Exception {
        sComponentName = FORWARD_TO_MANAGED_PROFILE_COMPONENT_NAME;

        // Intent can be forwarded.
        when(mIPm.canForwardTo(
                any(Intent.class), nullable(String.class), anyInt(), anyInt())).thenReturn(true);

        // Managed profile exists.
        List<UserInfo> profiles = new ArrayList<>();
        profiles.add(CURRENT_USER_INFO);
        profiles.add(MANAGED_PROFILE_INFO);
        when(mUserManager.getProfiles(anyInt())).thenReturn(profiles);

        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class);
        intent.setAction(Intent.ACTION_SEND);
        intent.setType(TYPE_PLAIN_TEXT);
        IntentForwarderWrapperActivity activity = mActivityRule.launchActivity(intent);

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mIPm).canForwardTo(intentCaptor.capture(), eq(TYPE_PLAIN_TEXT), anyInt(), anyInt());
        assertEquals(Intent.ACTION_SEND, intentCaptor.getValue().getAction());

        assertEquals(Intent.ACTION_SEND, intentCaptor.getValue().getAction());
        assertNotNull(activity.mStartActivityIntent);
        assertEquals(Intent.ACTION_SEND, activity.mStartActivityIntent.getAction());
        assertNull(activity.mStartActivityIntent.getPackage());
        assertNull(activity.mStartActivityIntent.getComponent());
        assertEquals(CURRENT_USER_INFO.id, activity.mStartActivityIntent.getContentUserHint());

        assertEquals(MANAGED_PROFILE_INFO.id, activity.mUserIdActivityLaunchedIn);
    }

    @Test
    public void forwardToManagedProfile_cannotForward_sendIntent() throws Exception {
        sComponentName = FORWARD_TO_MANAGED_PROFILE_COMPONENT_NAME;

        // Intent cannot be forwarded.
        when(mIPm.canForwardTo(
                any(Intent.class), nullable(String.class), anyInt(), anyInt())).thenReturn(false);

        // Managed profile exists.
        List<UserInfo> profiles = new ArrayList<>();
        profiles.add(CURRENT_USER_INFO);
        profiles.add(MANAGED_PROFILE_INFO);
        when(mUserManager.getProfiles(anyInt())).thenReturn(profiles);

        // Create ACTION_SEND intent.
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class);
        intent.setAction(Intent.ACTION_SEND);
        IntentForwarderWrapperActivity activity = mActivityRule.launchActivity(intent);

        assertNull(activity.mStartActivityIntent);
    }

    @Test
    public void forwardToManagedProfile_noManagedProfile_sendIntent() throws Exception {
        sComponentName = FORWARD_TO_MANAGED_PROFILE_COMPONENT_NAME;

        // Intent can be forwarded.
        when(mIPm.canForwardTo(
                any(Intent.class), anyString(), anyInt(), anyInt())).thenReturn(true);

        // Managed profile does not exist.
        List<UserInfo> profiles = new ArrayList<>();
        profiles.add(CURRENT_USER_INFO);
        when(mUserManager.getProfiles(anyInt())).thenReturn(profiles);

        // Create ACTION_SEND intent.
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class);
        intent.setAction(Intent.ACTION_SEND);
        IntentForwarderWrapperActivity activity = mActivityRule.launchActivity(intent);

        assertNull(activity.mStartActivityIntent);
    }

    @Test
    public void forwardToManagedProfile_canForward_chooserIntent() throws Exception {
        sComponentName = FORWARD_TO_MANAGED_PROFILE_COMPONENT_NAME;

        // Intent can be forwarded.
        when(mIPm.canForwardTo(
                any(Intent.class), nullable(String.class), anyInt(), anyInt())).thenReturn(true);

        // Manage profile exists.
        List<UserInfo> profiles = new ArrayList<>();
        profiles.add(CURRENT_USER_INFO);
        profiles.add(MANAGED_PROFILE_INFO);
        when(mUserManager.getProfiles(anyInt())).thenReturn(profiles);

        // Create chooser Intent
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class);
        intent.setAction(Intent.ACTION_CHOOSER);
        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.setComponent(new ComponentName("xx", "yyy"));
        sendIntent.setType(TYPE_PLAIN_TEXT);
        intent.putExtra(Intent.EXTRA_INTENT, sendIntent);
        IntentForwarderWrapperActivity activity = mActivityRule.launchActivity(intent);

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mIPm).canForwardTo(intentCaptor.capture(), eq(TYPE_PLAIN_TEXT), anyInt(), anyInt());
        assertEquals(Intent.ACTION_SEND, intentCaptor.getValue().getAction());

        assertNotNull(activity.mStartActivityIntent);
        assertEquals(Intent.ACTION_CHOOSER, activity.mStartActivityIntent.getAction());
        assertNull(activity.mStartActivityIntent.getPackage());
        assertNull(activity.mStartActivityIntent.getComponent());

        Intent innerIntent = activity.mStartActivityIntent.getParcelableExtra(Intent.EXTRA_INTENT);
        assertNotNull(innerIntent);
        assertEquals(Intent.ACTION_SEND, innerIntent.getAction());
        assertNull(innerIntent.getComponent());
        assertNull(innerIntent.getPackage());
        assertEquals(CURRENT_USER_INFO.id, innerIntent.getContentUserHint());

        assertEquals(MANAGED_PROFILE_INFO.id, activity.mUserIdActivityLaunchedIn);
    }

    @Test
    public void forwardToManagedProfile_canForward_selectorIntent() throws Exception {
        sComponentName = FORWARD_TO_MANAGED_PROFILE_COMPONENT_NAME;

        // Intent can be forwarded.
        when(mIPm.canForwardTo(
                any(Intent.class), nullable(String.class), anyInt(), anyInt())).thenReturn(true);

        // Manage profile exists.
        List<UserInfo> profiles = new ArrayList<>();
        profiles.add(CURRENT_USER_INFO);
        profiles.add(MANAGED_PROFILE_INFO);
        when(mUserManager.getProfiles(anyInt())).thenReturn(profiles);

        // Create selector intent.
        Intent intent = Intent.makeMainSelectorActivity(
                Intent.ACTION_VIEW, Intent.CATEGORY_BROWSABLE);
        IntentForwarderWrapperActivity activity = mActivityRule.launchActivity(intent);

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mIPm).canForwardTo(
                intentCaptor.capture(), nullable(String.class), anyInt(), anyInt());
        assertEquals(Intent.ACTION_VIEW, intentCaptor.getValue().getAction());

        assertNotNull(activity.mStartActivityIntent);
        assertEquals(Intent.ACTION_MAIN, activity.mStartActivityIntent.getAction());
        assertNull(activity.mStartActivityIntent.getPackage());
        assertNull(activity.mStartActivityIntent.getComponent());
        assertEquals(CURRENT_USER_INFO.id, activity.mStartActivityIntent.getContentUserHint());

        Intent innerIntent = activity.mStartActivityIntent.getSelector();
        assertNotNull(innerIntent);
        assertEquals(Intent.ACTION_VIEW, innerIntent.getAction());
        assertNull(innerIntent.getComponent());
        assertNull(innerIntent.getPackage());

        assertEquals(MANAGED_PROFILE_INFO.id, activity.mUserIdActivityLaunchedIn);
    }

    @Test
    public void shouldSkipDisclosure_notWhitelisted() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_SEND)
                .setType(TYPE_PLAIN_TEXT);

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector).showToast(anyInt(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_withResolverActivity() throws RemoteException {
        setupShouldSkipDisclosureTest();
        sActivityName = ResolverActivity.class.getName();
        sPackageName = "android";
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_SEND)
                .setType(TYPE_PLAIN_TEXT);

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector, never()).showToast(anyInt(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_callIntent_call() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_CALL);

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector, never()).showToast(anyInt(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_callIntent_callPrivileged() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_CALL_PRIVILEGED);

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector, never()).showToast(anyInt(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_callIntent_callEmergency() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_CALL_EMERGENCY);

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector, never()).showToast(anyInt(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_callIntent_dial() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_DIAL);

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector, never()).showToast(anyInt(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_callIntent_notCallOrDial() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_ALARM_CHANGED);

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector).showToast(anyInt(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_callIntent_actionViewTel() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setData(Uri.fromParts("tel", PHONE_NUMBER, null));

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector, never()).showToast(anyInt(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_textMessageIntent_sms() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_SENDTO)
                .setData(Uri.fromParts("sms", PHONE_NUMBER, null));

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector, never()).showToast(anyInt(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_textMessageIntent_smsto() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_SENDTO)
                .setData(Uri.fromParts("smsto", PHONE_NUMBER, null));

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector, never()).showToast(anyInt(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_textMessageIntent_mms() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_SENDTO)
                .setData(Uri.fromParts("mms", PHONE_NUMBER, null));

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector, never()).showToast(anyInt(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_textMessageIntent_mmsto() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_SENDTO)
                .setData(Uri.fromParts("mmsto", PHONE_NUMBER, null));

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector, never()).showToast(anyInt(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_textMessageIntent_actionViewSms() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setData(Uri.fromParts("sms", PHONE_NUMBER, null));

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector, never()).showToast(anyInt(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_textMessageIntent_actionViewSmsto() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setData(Uri.fromParts("smsto", PHONE_NUMBER, null));

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector, never()).showToast(anyInt(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_textMessageIntent_actionViewMms() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setData(Uri.fromParts("mms", PHONE_NUMBER, null));

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector, never()).showToast(anyInt(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_textMessageIntent_actionViewMmsto() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setData(Uri.fromParts("mmsto", PHONE_NUMBER, null));

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector, never()).showToast(anyInt(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_textMessageIntent_invalidUri() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_SENDTO)
                .setData(Uri.fromParts("invalid", PHONE_NUMBER, null));

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector).showToast(anyInt(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_viewBrowsableIntent_invalidUri() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setData(Uri.fromParts("invalid", PHONE_NUMBER, null));

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector).showToast(anyInt(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_viewBrowsableIntent_normalUrl() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setData(Uri.fromParts("http", "apache.org", null));

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector).showToast(anyInt(), anyInt());
    }

    private void setupShouldSkipDisclosureTest() throws RemoteException {
        sComponentName = FORWARD_TO_MANAGED_PROFILE_COMPONENT_NAME;
        sActivityName = "MyTestActivity";
        sPackageName = "test.package.name";
        when(mApplicationInfo.isSystemApp()).thenReturn(true);
        // Managed profile exists.
        List<UserInfo> profiles = new ArrayList<>();
        profiles.add(CURRENT_USER_INFO);
        profiles.add(MANAGED_PROFILE_INFO);
        when(mUserManager.getProfiles(anyInt())).thenReturn(profiles);
        // Intent can be forwarded.
        when(mIPm.canForwardTo(
                any(Intent.class), nullable(String.class), anyInt(), anyInt())).thenReturn(true);
    }

    public static class IntentForwarderWrapperActivity extends IntentForwarderActivity {

        private Intent mStartActivityIntent;
        private int mUserIdActivityLaunchedIn;

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            getIntent().setComponent(sComponentName);
            super.onCreate(savedInstanceState);
        }

        @Override
        protected Injector createInjector() {
            return sInjector;
        }

        @Override
        public void startActivityAsCaller(Intent intent, @Nullable Bundle options,
                IBinder permissionToken, boolean ignoreTargetSecurity, int userId) {
            mStartActivityIntent = intent;
            mUserIdActivityLaunchedIn = userId;
        }
    }

    public class TestInjector implements IntentForwarderActivity.Injector {

        @Override
        public IPackageManager getIPackageManager() {
            return mIPm;
        }

        @Override
        public UserManager getUserManager() {
            return mUserManager;
        }

        @Override
        public PackageManager getPackageManager() {
            return mPm;
        }

        @Override
        public ResolveInfo resolveActivityAsUser(Intent intent, int flags, int userId) {
            ActivityInfo activityInfo = new ActivityInfo();
            activityInfo.packageName = sPackageName;
            activityInfo.name = sActivityName;
            activityInfo.applicationInfo = mApplicationInfo;

            ResolveInfo resolveInfo = new ResolveInfo();
            resolveInfo.activityInfo = activityInfo;

            return resolveInfo;
        }

        @Override
        public void showToast(int messageId, int duration) {}
    }
}
