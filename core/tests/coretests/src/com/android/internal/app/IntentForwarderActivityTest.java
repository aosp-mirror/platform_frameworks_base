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

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
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
import android.metrics.LogMaker;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import com.android.internal.R;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class IntentForwarderActivityTest {

    private static final ComponentName FORWARD_TO_MANAGED_PROFILE_COMPONENT_NAME =
            new ComponentName(
                    "android",
                    IntentForwarderActivity.FORWARD_INTENT_TO_MANAGED_PROFILE
            );
    private static final ComponentName FORWARD_TO_PARENT_COMPONENT_NAME =
            new ComponentName(
                    "android",
                    IntentForwarderActivity.FORWARD_INTENT_TO_PARENT
            );
    private static final String TYPE_PLAIN_TEXT = "text/plain";

    private static UserInfo MANAGED_PROFILE_INFO = new UserInfo();
    private static UserInfo PRIVATE_PROFILE_INFO = new UserInfo(12, "Private", null,
            UserInfo.FLAG_PROFILE, UserManager.USER_TYPE_PROFILE_PRIVATE);
    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    static {
        MANAGED_PROFILE_INFO.id = 10;
        MANAGED_PROFILE_INFO.flags = UserInfo.FLAG_MANAGED_PROFILE;
        MANAGED_PROFILE_INFO.userType = UserManager.USER_TYPE_PROFILE_MANAGED;
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
    private int mDeviceProvisionedInitialValue;

    @Before
    public void setup() {

        MockitoAnnotations.initMocks(this);
        mContext = InstrumentationRegistry.getTargetContext();
        sInjector = spy(new TestInjector());
        mDeviceProvisionedInitialValue = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, /* def= */ 0);
    }

    @After
    public void tearDown() {
        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.DEVICE_PROVISIONED,
                mDeviceProvisionedInitialValue);
    }

    @Test
    public void forwardToManagedProfile_canForward_sendIntent() throws Exception {
        sComponentName = FORWARD_TO_MANAGED_PROFILE_COMPONENT_NAME;
        sActivityName = "MyTestActivity";
        sPackageName = "test.package.name";

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

        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        onView(withId(R.id.icon)).check(matches(isDisplayed()));
        onView(withId(R.id.open_cross_profile)).check(matches(isDisplayed()));
        onView(withId(R.id.use_same_profile_browser)).check(matches(isDisplayed()));
        onView(withId(R.id.button_open)).perform(click());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

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
    public void launchInSameProfile_chooserIntent() {
        sComponentName = FORWARD_TO_MANAGED_PROFILE_COMPONENT_NAME;

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

        assertNotNull(activity.mStartActivityIntent);
        assertEquals(Intent.ACTION_CHOOSER, activity.mStartActivityIntent.getAction());
        assertNull(activity.mStartActivityIntent.getPackage());
        assertNull(activity.mStartActivityIntent.getComponent());

        Intent innerIntent = activity.mStartActivityIntent.getParcelableExtra(Intent.EXTRA_INTENT);
        assertNotNull(innerIntent);
        assertEquals(Intent.ACTION_SEND, innerIntent.getAction());
        assertNull(innerIntent.getComponent());
        assertNull(innerIntent.getPackage());
        assertEquals(UserHandle.USER_CURRENT, innerIntent.getContentUserHint());

        assertEquals(CURRENT_USER_INFO.id, activity.mUserIdActivityLaunchedIn);
    }

    @Test
    public void forwardToManagedProfile_canForward_selectorIntent() throws Exception {
        sComponentName = FORWARD_TO_MANAGED_PROFILE_COMPONENT_NAME;
        sActivityName = "MyTestActivity";
        sPackageName = "test.package.name";

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

        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        onView(withId(R.id.icon)).check(matches(isDisplayed()));
        onView(withId(R.id.open_cross_profile)).check(matches(isDisplayed()));
        onView(withId(R.id.use_same_profile_browser)).check(matches(isDisplayed()));
        onView(withId(R.id.button_open)).perform(click());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

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
        verify(sInjector).showToast(anyString(), anyInt());
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
        verify(sInjector, never()).showToast(anyString(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_callIntent_call() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_CALL);

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector, never()).showToast(anyString(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_callIntent_callPrivileged() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_CALL_PRIVILEGED);

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector, never()).showToast(anyString(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_callIntent_callEmergency() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_CALL_EMERGENCY);

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector, never()).showToast(anyString(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_callIntent_dial() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_DIAL);

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector, never()).showToast(anyString(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_callIntent_notCallOrDial() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_ALARM_CHANGED);

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector).showToast(anyString(), anyInt());
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
        verify(sInjector, never()).showToast(anyString(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_textMessageIntent_sms() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_SENDTO)
                .setData(Uri.fromParts("sms", PHONE_NUMBER, null));

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector, never()).showToast(anyString(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_textMessageIntent_smsto() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_SENDTO)
                .setData(Uri.fromParts("smsto", PHONE_NUMBER, null));

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector, never()).showToast(anyString(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_textMessageIntent_mms() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_SENDTO)
                .setData(Uri.fromParts("mms", PHONE_NUMBER, null));

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector, never()).showToast(anyString(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_textMessageIntent_mmsto() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_SENDTO)
                .setData(Uri.fromParts("mmsto", PHONE_NUMBER, null));

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector, never()).showToast(anyString(), anyInt());
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
        verify(sInjector, never()).showToast(anyString(), anyInt());
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
        verify(sInjector, never()).showToast(anyString(), anyInt());
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
        verify(sInjector, never()).showToast(anyString(), anyInt());
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
        verify(sInjector, never()).showToast(anyString(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_textMessageIntent_invalidUri() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_SENDTO)
                .setData(Uri.fromParts("invalid", PHONE_NUMBER, null));

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector).showToast(anyString(), anyInt());
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
        verify(sInjector).showToast(anyString(), anyInt());
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
        verify(sInjector).showToast(anyString(), anyInt());
    }

    @Test
    public void shouldSkipDisclosure_duringDeviceSetup() throws RemoteException {
        setupShouldSkipDisclosureTest();
        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.DEVICE_PROVISIONED,
                /* value= */ 0);
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setData(Uri.fromParts("http", "apache.org", null));

        mActivityRule.launchActivity(intent);

        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        verify(sInjector, never()).showToast(anyString(), anyInt());
    }

    @Test
    public void forwardToManagedProfile_LoggingTest() throws Exception {
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

        ArgumentCaptor<LogMaker> logMakerCaptor = ArgumentCaptor.forClass(LogMaker.class);
        verify(activity.getMetricsLogger()).write(logMakerCaptor.capture());
        assertEquals(MetricsEvent.ACTION_SWITCH_SHARE_PROFILE,
                logMakerCaptor.getValue().getCategory());
        assertEquals(MetricsEvent.MANAGED_PROFILE,
                logMakerCaptor.getValue().getSubtype());
    }

    @Test
    public void forwardToParent_LoggingTest() throws Exception {
        sComponentName = FORWARD_TO_PARENT_COMPONENT_NAME;

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

        ArgumentCaptor<LogMaker> logMakerCaptor = ArgumentCaptor.forClass(LogMaker.class);
        verify(activity.getMetricsLogger()).write(logMakerCaptor.capture());
        assertEquals(MetricsEvent.ACTION_SWITCH_SHARE_PROFILE,
                logMakerCaptor.getValue().getCategory());
        assertEquals(MetricsEvent.PARENT_PROFILE,
                logMakerCaptor.getValue().getSubtype());
    }

    @Test
    public void shouldForwardToParent_telephony_privateProfile() throws Exception {
        mSetFlagsRule.enableFlags(
                android.os.Flags.FLAG_ALLOW_PRIVATE_PROFILE,
                android.multiuser.Flags.FLAG_ENABLE_PRIVATE_SPACE_FEATURES,
                android.multiuser.Flags.FLAG_ENABLE_PRIVATE_SPACE_INTENT_REDIRECTION);

        sComponentName = FORWARD_TO_PARENT_COMPONENT_NAME;
        when(mIPm.canForwardTo(
                any(Intent.class), nullable(String.class), anyInt(), anyInt())).thenReturn(true);

        List<UserInfo> profiles = new ArrayList<>();
        profiles.add(CURRENT_USER_INFO);
        profiles.add(PRIVATE_PROFILE_INFO);
        when(mUserManager.getProfiles(anyInt())).thenReturn(profiles);
        when(mUserManager.getProfileParent(anyInt())).thenReturn(CURRENT_USER_INFO);
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class);
        intent.setAction(Intent.ACTION_DIAL);
        IntentForwarderWrapperActivity activity = mActivityRule.launchActivity(intent);
        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        assertEquals(activity.getStartActivityIntent().getAction(), intent.getAction());
        assertEquals(activity.getUserIdActivityLaunchedIn(), CURRENT_USER_INFO.id);
    }

    @Test
    public void shouldForwardToParent_mms_privateProfile() throws Exception {
        mSetFlagsRule.enableFlags(
                android.os.Flags.FLAG_ALLOW_PRIVATE_PROFILE,
                android.multiuser.Flags.FLAG_ENABLE_PRIVATE_SPACE_INTENT_REDIRECTION);

        sComponentName = FORWARD_TO_PARENT_COMPONENT_NAME;
        when(mIPm.canForwardTo(
                any(Intent.class), nullable(String.class), anyInt(), anyInt())).thenReturn(true);

        List<UserInfo> profiles = new ArrayList<>();
        profiles.add(CURRENT_USER_INFO);
        profiles.add(PRIVATE_PROFILE_INFO);
        when(mUserManager.getProfiles(anyInt())).thenReturn(profiles);
        when(mUserManager.getProfileParent(anyInt())).thenReturn(CURRENT_USER_INFO);
        Intent intent = new Intent(mContext, IntentForwarderWrapperActivity.class);
        intent.setAction(Intent.ACTION_SEND);
        intent.setType(TYPE_PLAIN_TEXT);
        IntentForwarderWrapperActivity activity = mActivityRule.launchActivity(intent);
        verify(mIPm).canForwardTo(any(), any(), anyInt(), anyInt());
        assertEquals(activity.getStartActivityIntent().getAction(), intent.getAction());
        assertEquals(activity.getStartActivityIntent().getType(), intent.getType());
        assertEquals(activity.getUserIdActivityLaunchedIn(), CURRENT_USER_INFO.id);
    }

    private void setupShouldSkipDisclosureTest() throws RemoteException {
        sComponentName = FORWARD_TO_PARENT_COMPONENT_NAME;
        sActivityName = "MyTestActivity";
        sPackageName = "test.package.name";
        Settings.Global.putInt(mContext.getContentResolver(), Settings.Global.DEVICE_PROVISIONED,
                /* value= */ 1);
        when(mApplicationInfo.isSystemApp()).thenReturn(true);
        // Managed profile exists.
        List<UserInfo> profiles = new ArrayList<>();
        profiles.add(CURRENT_USER_INFO);
        profiles.add(MANAGED_PROFILE_INFO);
        when(mUserManager.getProfiles(anyInt())).thenReturn(profiles);
        when(mUserManager.getProfileParent(anyInt())).thenReturn(CURRENT_USER_INFO);
        // Intent can be forwarded.
        when(mIPm.canForwardTo(
                any(Intent.class), nullable(String.class), anyInt(), anyInt())).thenReturn(true);
    }

    public static class IntentForwarderWrapperActivity extends IntentForwarderActivity {

        private Intent mStartActivityIntent;
        private int mUserIdActivityLaunchedIn;
        private MetricsLogger mMetricsLogger = mock(MetricsLogger.class);

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            getIntent().setComponent(sComponentName);
            super.onCreate(savedInstanceState);
            try {
                mExecutorService.awaitTermination(/* timeout= */ 30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        @Override
        protected Injector createInjector() {
            return sInjector;
        }

        @Override
        public void startActivityAsCaller(Intent intent, @Nullable Bundle options,
                boolean ignoreTargetSecurity, int userId) {
            mStartActivityIntent = intent;
            mUserIdActivityLaunchedIn = userId;
        }

        @Override
        public Context createContextAsUser(UserHandle user, int flags) {
            return this;
        }

        @Override
        protected MetricsLogger getMetricsLogger() {
            return mMetricsLogger;
        }

        Intent getStartActivityIntent() {
            return mStartActivityIntent;
        }

        int getUserIdActivityLaunchedIn() {
            return mUserIdActivityLaunchedIn;
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
        public CompletableFuture<ResolveInfo> resolveActivityAsUser(
                Intent intent, int flags, int userId) {
            ActivityInfo activityInfo = new ActivityInfo();
            activityInfo.packageName = sPackageName;
            activityInfo.name = sActivityName;
            activityInfo.applicationInfo = mApplicationInfo;

            ResolveInfo resolveInfo = new ResolveInfo();
            resolveInfo.activityInfo = activityInfo;

            return CompletableFuture.completedFuture(resolveInfo);
        }

        @Override
        public void showToast(String message, int duration) {}
    }
}
