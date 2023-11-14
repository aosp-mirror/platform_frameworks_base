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

package com.android.internal.accessibility.dialog;

import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG;

import static com.google.common.truth.Truth.assertThat;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AlertDialog;
import android.content.Context;
import android.os.RemoteException;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.TextView;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.R;
import com.android.internal.accessibility.TestUtils;

import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unit Tests for
 * {@link com.android.internal.accessibility.dialog.AccessibilityServiceWarning}
 */
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@RequiresFlagsEnabled(
        android.view.accessibility.Flags.FLAG_DEDUPLICATE_ACCESSIBILITY_WARNING_DIALOG)
public class AccessibilityServiceWarningTest {
    private static final String A11Y_SERVICE_PACKAGE_LABEL = "TestA11yService";
    private static final String A11Y_SERVICE_SUMMARY = "TestA11yService summary";
    private static final String A11Y_SERVICE_COMPONENT_NAME =
            "fake.package/test.a11yservice.name";

    private Context mContext;
    private AccessibilityServiceInfo mAccessibilityServiceInfo;
    private AtomicBoolean mAllowListener;
    private AtomicBoolean mDenyListener;
    private AtomicBoolean mUninstallListener;

    @Rule
    public final Expect expect = Expect.create();

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        mAccessibilityServiceInfo = TestUtils.createFakeServiceInfo(
                A11Y_SERVICE_PACKAGE_LABEL,
                A11Y_SERVICE_SUMMARY,
                A11Y_SERVICE_COMPONENT_NAME,
                /* isAlwaysOnService*/ false);
        mAllowListener = new AtomicBoolean(false);
        mDenyListener = new AtomicBoolean(false);
        mUninstallListener = new AtomicBoolean(false);
    }

    @Test
    public void createAccessibilityServiceWarningDialog_hasExpectedWindowParams() {
        final AlertDialog dialog =
                AccessibilityServiceWarning.createAccessibilityServiceWarningDialog(
                        mContext,
                        mAccessibilityServiceInfo,
                        null, null, null);
        final Window dialogWindow = dialog.getWindow();
        assertThat(dialogWindow).isNotNull();

        expect.that(dialogWindow.getAttributes().privateFlags
                & SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS).isEqualTo(
                SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
        expect.that(dialogWindow.getAttributes().type).isEqualTo(TYPE_SYSTEM_DIALOG);
    }

    @Test
    public void createAccessibilityServiceWarningDialog_hasExpectedServiceName() {
        final TextView title = createDialogContentView().findViewById(
                R.id.accessibility_permissionDialog_title);
        assertThat(title).isNotNull();

        assertThat(title.getText().toString()).contains(A11Y_SERVICE_PACKAGE_LABEL);
    }

    @Test
    public void createAccessibilityServiceWarningDialog_clickAllow() {
        final View allowButton = createDialogContentView().findViewById(
                R.id.accessibility_permission_enable_allow_button);
        assertThat(allowButton).isNotNull();

        allowButton.performClick();

        expect.that(mAllowListener.get()).isTrue();
        expect.that(mDenyListener.get()).isFalse();
        expect.that(mUninstallListener.get()).isFalse();
    }

    @Test
    public void createAccessibilityServiceWarningDialog_clickDeny() {
        final View denyButton = createDialogContentView().findViewById(
                R.id.accessibility_permission_enable_deny_button);
        assertThat(denyButton).isNotNull();

        denyButton.performClick();

        expect.that(mAllowListener.get()).isFalse();
        expect.that(mDenyListener.get()).isTrue();
        expect.that(mUninstallListener.get()).isFalse();
    }

    @Test
    public void createAccessibilityServiceWarningDialog_clickUninstall() {
        final View uninstallButton = createDialogContentView().findViewById(
                R.id.accessibility_permission_enable_uninstall_button);
        assertThat(uninstallButton).isNotNull();

        uninstallButton.performClick();

        expect.that(mAllowListener.get()).isFalse();
        expect.that(mDenyListener.get()).isFalse();
        expect.that(mUninstallListener.get()).isTrue();
    }

    @Test
    public void getTouchConsumingListener() {
        final View allowButton = createDialogContentView().findViewById(
                R.id.accessibility_permission_enable_allow_button);
        assertThat(allowButton).isNotNull();
        final View.OnTouchListener listener =
                AccessibilityServiceWarning.getTouchConsumingListener();

        expect.that(listener.onTouch(allowButton, createMotionEvent(0))).isFalse();
        expect.that(listener.onTouch(allowButton,
                createMotionEvent(MotionEvent.FLAG_WINDOW_IS_OBSCURED))).isTrue();
        expect.that(listener.onTouch(allowButton,
                createMotionEvent(MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED))).isTrue();
    }

    private View createDialogContentView() {
        return AccessibilityServiceWarning.createAccessibilityServiceWarningDialogContentView(
                mContext,
                mAccessibilityServiceInfo,
                (v) -> mAllowListener.set(true),
                (v) -> mDenyListener.set(true),
                (v) -> mUninstallListener.set(true));
    }

    private MotionEvent createMotionEvent(int flags) {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[]{
                new MotionEvent.PointerProperties()
        };
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[]{
                new MotionEvent.PointerCoords()
        };
        return MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1, props, coords,
                0, 0, 0, 0, -1, 0, InputDevice.SOURCE_TOUCHSCREEN, flags);
    }
}
