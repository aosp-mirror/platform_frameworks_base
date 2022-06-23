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
import static androidx.test.espresso.action.ViewActions.doubleClick;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IAccessibilityManager;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;
import com.android.internal.accessibility.dialog.AccessibilityShortcutChooserActivity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;

/**
 * Tests for {@link AccessibilityShortcutChooserActivity}.
 */
@RunWith(AndroidJUnit4.class)
public class AccessibilityShortcutChooserActivityTest {
    private static final String TEST_LABEL = "TEST_LABEL";
    private static final Context sContext =
            InstrumentationRegistry.getInstrumentation().getContext();
    private ActivityScenario<TestAccessibilityShortcutChooserActivity> mScenario;
    private static IAccessibilityManager sAccessibilityManagerService;

    @Mock
    private AccessibilityServiceInfo mAccessibilityServiceInfo;

    @Mock
    private ResolveInfo mResolveInfo;

    @Mock
    private ServiceInfo mServiceInfo;

    @Mock
    private ApplicationInfo mApplicationInfo;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        sAccessibilityManagerService = mock(IAccessibilityManager.class);

        when(mAccessibilityServiceInfo.getResolveInfo()).thenReturn(mResolveInfo);
        mResolveInfo.serviceInfo = mServiceInfo;
        mServiceInfo.applicationInfo = mApplicationInfo;
        when(mResolveInfo.loadLabel(any(PackageManager.class))).thenReturn(TEST_LABEL);
        when(mAccessibilityServiceInfo.getComponentName()).thenReturn(
                new ComponentName("package", "class"));
        when(sAccessibilityManagerService.getInstalledAccessibilityServiceList(
                anyInt())).thenReturn(Collections.singletonList(mAccessibilityServiceInfo));

        mScenario = ActivityScenario.launch(TestAccessibilityShortcutChooserActivity.class);
    }

    @Test
    public void doubleClickServiceTargetAndClickDenyButton_permissionDialogDoesNotExist() {
        mScenario.moveToState(Lifecycle.State.CREATED);
        mScenario.moveToState(Lifecycle.State.STARTED);
        mScenario.moveToState(Lifecycle.State.RESUMED);
        onView(withText(R.string.accessibility_select_shortcut_menu_title)).inRoot(
                isDialog()).check(matches(isDisplayed()));
        onView(withText(R.string.edit_accessibility_shortcut_menu_button)).perform(click());

        onView(withText(TEST_LABEL)).perform(scrollTo(), doubleClick());
        onView(withId(R.id.accessibility_permission_enable_deny_button)).perform(scrollTo(),
                click());

        onView(withId(R.id.accessibility_permissionDialog_title)).inRoot(isDialog()).check(
                doesNotExist());
        mScenario.moveToState(Lifecycle.State.DESTROYED);
    }

    /**
     * Used for testing.
     */
    public static class TestAccessibilityShortcutChooserActivity extends
            AccessibilityShortcutChooserActivity {
        private AccessibilityManager mAccessibilityManager;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            mAccessibilityManager = new AccessibilityManager(sContext, new Handler(getMainLooper()),
                    sAccessibilityManagerService, /* userId= */ 0, /* serviceConnect= */ true);
            super.onCreate(savedInstanceState);
        }

        @Override
        public Object getSystemService(String name) {
            if (Context.ACCESSIBILITY_SERVICE.equals(name)) {
                return mAccessibilityManager;
            }

            return super.getSystemService(name);
        }
    }
}
