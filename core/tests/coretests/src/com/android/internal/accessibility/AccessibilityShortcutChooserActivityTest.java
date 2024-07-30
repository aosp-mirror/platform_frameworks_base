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

package com.android.internal.accessibility;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.action.ViewActions.swipeUp;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withClassName;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.endsWith;
import static org.junit.Assume.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.Until;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IAccessibilityManager;
import android.widget.Button;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.R;
import com.android.internal.accessibility.dialog.AccessibilityShortcutChooserActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tests for {@link AccessibilityShortcutChooserActivity}.
 */
@RunWith(AndroidJUnit4.class)
public class AccessibilityShortcutChooserActivityTest {
    private static final String ONE_HANDED_MODE = "One-Handed mode";
    private static final String ALLOW_LABEL = "Allow";
    private static final String DENY_LABEL = "Deny";
    private static final String UNINSTALL_LABEL = "Uninstall";
    private static final String EDIT_LABEL = "Edit shortcuts";
    private static final String LIST_TITLE_LABEL = "Choose features to use";
    private static final String TEST_LABEL = "TEST_LABEL";
    private static final String TEST_PACKAGE = "TEST_LABEL";
    private static final ComponentName TEST_COMPONENT_NAME = new ComponentName(TEST_PACKAGE,
            "class");
    private static final long UI_TIMEOUT_MS = 1000;
    private UiDevice mDevice;
    private ActivityScenario<TestAccessibilityShortcutChooserActivity> mScenario;
    private TestAccessibilityShortcutChooserActivity mActivity;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule
    public final MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Mock
    private AccessibilityServiceInfo mAccessibilityServiceInfo;
    @Mock
    private ResolveInfo mResolveInfo;
    @Mock
    private ServiceInfo mServiceInfo;
    @Mock
    private ApplicationInfo mApplicationInfo;
    @Mock
    private IAccessibilityManager mAccessibilityManagerService;
    @Mock
    private KeyguardManager mKeyguardManager;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private PackageInstaller mPackageInstaller;

    @Before
    public void setUp() throws Exception {
        final PackageManager pm =
                InstrumentationRegistry.getInstrumentation().getContext().getPackageManager();
        assumeFalse("AccessibilityShortcutChooserActivity not supported on watch",
                pm.hasSystemFeature(PackageManager.FEATURE_WATCH));

        mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        mDevice.wakeUp();
        when(mAccessibilityServiceInfo.getResolveInfo()).thenReturn(mResolveInfo);
        mResolveInfo.serviceInfo = mServiceInfo;
        mServiceInfo.applicationInfo = mApplicationInfo;
        when(mResolveInfo.loadLabel(any(PackageManager.class))).thenReturn(TEST_LABEL);
        when(mAccessibilityServiceInfo.getComponentName()).thenReturn(TEST_COMPONENT_NAME);
        when(mAccessibilityManagerService.getInstalledAccessibilityServiceList(
                anyInt())).thenReturn(new ParceledListSlice<>(
                Collections.singletonList(mAccessibilityServiceInfo)));
        when(mAccessibilityManagerService.isAccessibilityServiceWarningRequired(any()))
                .thenReturn(true);
        when(mAccessibilityManagerService.isAccessibilityTargetAllowed(
                anyString(), anyInt(), anyInt())).thenReturn(true);
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(false);
        when(mPackageManager.getPackageInstaller()).thenReturn(mPackageInstaller);

        TestAccessibilityShortcutChooserActivity.setupForTesting(
                mAccessibilityManagerService, mKeyguardManager,
                mPackageManager);
    }

    @After
    public void cleanUp() {
        if (mScenario != null) {
            mScenario.close();
        }
        if (mActivity != null) {
            Dialog permissionDialog = mActivity.getPermissionDialog();
            if (permissionDialog != null && permissionDialog.isShowing()) {
                permissionDialog.dismiss();
            }
        }
    }

    @Test
    public void selectTestService_permissionDialog_allow_rowChecked() {
        launchActivity();
        openShortcutsList();

        mDevice.findObject(By.text(TEST_LABEL)).clickAndWait(Until.newWindow(), UI_TIMEOUT_MS);
        clickPermissionDialogButton(R.id.accessibility_permission_enable_allow_button);

        assertThat(mDevice.wait(Until.hasObject(By.checked(true)), UI_TIMEOUT_MS)).isTrue();
    }

    @Test
    public void selectTestService_permissionDialog_deny_rowNotChecked() {
        launchActivity();
        openShortcutsList();

        mDevice.findObject(By.text(TEST_LABEL)).clickAndWait(Until.newWindow(), UI_TIMEOUT_MS);
        clickPermissionDialogButton(R.id.accessibility_permission_enable_deny_button);

        assertThat(mDevice.wait(Until.hasObject(By.checked(true)), UI_TIMEOUT_MS)).isFalse();
    }

    @Test
    public void selectTestService_permissionDialog_uninstall_callsUninstaller_rowRemoved() {
        launchActivity();
        openShortcutsList();

        mDevice.findObject(By.text(TEST_LABEL)).clickAndWait(Until.newWindow(), UI_TIMEOUT_MS);
        clickPermissionDialogButton(R.id.accessibility_permission_enable_uninstall_button);

        verify(mPackageInstaller).uninstall(eq(TEST_PACKAGE), any());
        assertThat(mDevice.wait(Until.hasObject(By.textStartsWith(TEST_LABEL)),
                UI_TIMEOUT_MS)).isFalse();
    }

    @Test
    public void selectTestService_permissionDialog_notShownWhenNotRequired() throws Exception {
        when(mAccessibilityManagerService.isAccessibilityServiceWarningRequired(any()))
                .thenReturn(false);
        launchActivity();
        openShortcutsList();

        // Clicking the test service should not show a permission dialog window,
        assertThat(mDevice.findObject(By.text(TEST_LABEL)).clickAndWait(
                Until.newWindow(), UI_TIMEOUT_MS)).isFalse();
        // and should become checked.
        assertThat(mDevice.findObject(By.checked(true))).isNotNull();
    }

    @Test
    public void selectTestService_notPermittedByAdmin_blockedEvenIfNoWarningRequired()
            throws Exception {
        when(mAccessibilityManagerService.isAccessibilityServiceWarningRequired(any()))
                .thenReturn(false);
        when(mAccessibilityManagerService.isAccessibilityTargetAllowed(
                eq(TEST_COMPONENT_NAME.getPackageName()), anyInt(), anyInt())).thenReturn(false);
        // This test class mocks AccessibilityManagerService, so the restricted dialog window
        // will not actually appear and therefore cannot be used for a wait Until.newWindow().
        // To still allow smart waiting in this test we can instead set up the mocked method
        // to update an atomic boolean and wait for that to be set.
        final Object waitObject = new Object();
        final AtomicBoolean calledSendRestrictedDialogIntent = new AtomicBoolean(false);
        Mockito.doAnswer((Answer<Void>) invocation -> {
            synchronized (waitObject) {
                calledSendRestrictedDialogIntent.set(true);
                waitObject.notify();
            }
            return null;
        }).when(mAccessibilityManagerService).sendRestrictedDialogIntent(
                eq(TEST_COMPONENT_NAME.getPackageName()), anyInt(), anyInt());
        launchActivity();
        openShortcutsList();

        mDevice.findObject(By.text(TEST_LABEL)).click();
        final long timeout = System.currentTimeMillis() + UI_TIMEOUT_MS;
        synchronized (waitObject) {
            while (!calledSendRestrictedDialogIntent.get() &&
                    (System.currentTimeMillis() < timeout)) {
                waitObject.wait(timeout - System.currentTimeMillis());
            }
        }

        assertThat(calledSendRestrictedDialogIntent.get()).isTrue();
        assertThat(mDevice.findObject(By.checked(true))).isNull();
    }

    @Test
    public void clickServiceTarget_notPermittedByAdmin_sendRestrictedDialogIntent()
            throws Exception {
        when(mAccessibilityManagerService.isAccessibilityTargetAllowed(
                eq(TEST_COMPONENT_NAME.getPackageName()), anyInt(), anyInt())).thenReturn(false);
        launchActivity();
        openShortcutsList();

        onView(withText(TEST_LABEL)).perform(scrollTo(), click());

        verify(mAccessibilityManagerService).sendRestrictedDialogIntent(
                eq(TEST_COMPONENT_NAME.getPackageName()), anyInt(), anyInt());
    }

    @Test
    public void popEditShortcutMenuList_oneHandedModeEnabled_shouldBeInListView() {
        TestUtils.setOneHandedModeEnabled(this, /* enabled= */ true);
        launchActivity();
        openShortcutsList();

        onView(allOf(withClassName(endsWith("ListView")), isDisplayed())).perform(swipeUp());
        mDevice.wait(Until.hasObject(By.text(ONE_HANDED_MODE)), UI_TIMEOUT_MS);

        onView(withText(ONE_HANDED_MODE)).inRoot(isDialog()).check(matches(isDisplayed()));
    }

    @Test
    public void popEditShortcutMenuList_oneHandedModeDisabled_shouldNotBeInListView() {
        TestUtils.setOneHandedModeEnabled(this, /* enabled= */ false);
        launchActivity();
        openShortcutsList();

        onView(allOf(withClassName(endsWith("ListView")), isDisplayed())).perform(swipeUp());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        onView(withText(ONE_HANDED_MODE)).inRoot(isDialog()).check(doesNotExist());
    }

    @Test
    public void createDialog_onLockscreen_hasExpectedContent() {
        when(mKeyguardManager.isKeyguardLocked()).thenReturn(true);
        launchActivity();

        final AlertDialog dialog = mActivity.getMenuDialog();

        assertThat(dialog.getButton(AlertDialog.BUTTON_POSITIVE).getVisibility())
                .isEqualTo(View.GONE);
        assertThat(dialog.getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
                .isEqualTo(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
    }

    private void launchActivity() {
        mScenario = ActivityScenario.launch(TestAccessibilityShortcutChooserActivity.class);
        mScenario.onActivity(activity -> mActivity = activity);
        mScenario.moveToState(Lifecycle.State.CREATED);
        mScenario.moveToState(Lifecycle.State.STARTED);
        mScenario.moveToState(Lifecycle.State.RESUMED);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private void openShortcutsList() {
        UiObject2 editButton = mDevice.findObject(By.text(EDIT_LABEL));
        if (editButton != null) {
            editButton.click();
        }
        mDevice.wait(Until.hasObject(By.textStartsWith(LIST_TITLE_LABEL)), UI_TIMEOUT_MS);
    }

    private void clickPermissionDialogButton(int buttonId) {
        Button button = mActivity.getPermissionDialog().findViewById(buttonId);
        mActivity.runOnUiThread(button::performClick);
        // Wait for the dialog to go away by waiting for the shortcut chooser
        // to become visible again.
        assertThat(mDevice.wait(Until.hasObject(By.textStartsWith(LIST_TITLE_LABEL)),
                UI_TIMEOUT_MS)).isTrue();
    }

    /**
     * Used for testing.
     */
    public static class TestAccessibilityShortcutChooserActivity extends
            AccessibilityShortcutChooserActivity {
        private static IAccessibilityManager sAccessibilityManagerService;
        private static KeyguardManager sKeyguardManager;
        private static PackageManager sPackageManager;

        public static void setupForTesting(
                IAccessibilityManager accessibilityManagerService,
                KeyguardManager keyguardManager,
                PackageManager packageManager) {
            sAccessibilityManagerService = accessibilityManagerService;
            sKeyguardManager = keyguardManager;
            sPackageManager = packageManager;
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            // Setting the Theme is necessary here for the dialog to use the proper style
            // resources as designated in its layout XML.
            setTheme(R.style.Theme_DeviceDefault_DayNight);
        }

        @Override
        public PackageManager getPackageManager() {
            return sPackageManager;
        }

        @Override
        public Object getSystemService(String name) {
            if (Context.ACCESSIBILITY_SERVICE.equals(name)
                    && sAccessibilityManagerService != null) {
                // Warning: This new AccessibilityManager complicates UI inspection
                // because it breaks the expected "singleton per process" quality of
                // AccessibilityManager. Debug here if observing unexpected issues
                // with UI inspection or interaction.
                return new AccessibilityManager(this, new Handler(getMainLooper()),
                        sAccessibilityManagerService, /* userId= */ 0, /* serviceConnect= */ true);
            }
            if (Context.KEYGUARD_SERVICE.equals(name)) {
                return sKeyguardManager;
            }

            return super.getSystemService(name);
        }
    }
}
