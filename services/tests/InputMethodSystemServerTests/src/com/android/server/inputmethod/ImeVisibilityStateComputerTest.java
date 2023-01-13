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

package com.android.server.inputmethod;

import static android.accessibilityservice.AccessibilityService.SHOW_MODE_HIDDEN;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
import static android.view.WindowManager.DISPLAY_IME_POLICY_HIDE;
import static android.view.WindowManager.DISPLAY_IME_POLICY_LOCAL;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED;

import static com.android.server.inputmethod.ImeVisibilityStateComputer.ImeTargetWindowState;
import static com.android.server.inputmethod.InputMethodManagerService.FALLBACK_DISPLAY_ID;
import static com.android.server.inputmethod.InputMethodManagerService.ImeDisplayValidator;

import static com.google.common.truth.Truth.assertThat;

import android.os.IBinder;
import android.os.RemoteException;
import android.view.inputmethod.InputMethodManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.server.wm.WindowManagerInternal;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test the behavior of {@link ImeVisibilityStateComputer} and {@link ImeVisibilityApplier} when
 * requesting the IME visibility.
 *
 * Build/Install/Run:
 * atest FrameworksInputMethodSystemServerTests:ImeVisibilityStateComputerTest
 */
@RunWith(AndroidJUnit4.class)
public class ImeVisibilityStateComputerTest extends InputMethodManagerServiceTestBase {
    private ImeVisibilityStateComputer mComputer;
    private int mImeDisplayPolicy = DISPLAY_IME_POLICY_LOCAL;

    @Before
    public void setUp() throws RemoteException {
        super.setUp();
        ImeVisibilityStateComputer.Injector injector = new ImeVisibilityStateComputer.Injector() {
            @Override
            public WindowManagerInternal getWmService() {
                return mMockWindowManagerInternal;
            }

            @Override
            public ImeDisplayValidator getImeValidator() {
                return displayId -> mImeDisplayPolicy;
            }
        };
        mComputer = new ImeVisibilityStateComputer(mInputMethodManagerService, injector);
    }

    @Test
    public void testRequestImeVisibility_showImplicit() {
        initImeTargetWindowState(mWindowToken);
        boolean res = mComputer.onImeShowFlags(null, InputMethodManager.SHOW_IMPLICIT);
        mComputer.requestImeVisibility(mWindowToken, res);

        final ImeTargetWindowState state = mComputer.getWindowStateOrNull(mWindowToken);
        assertThat(state).isNotNull();
        assertThat(state.hasEdiorFocused()).isTrue();
        assertThat(state.getSoftInputModeState()).isEqualTo(SOFT_INPUT_STATE_UNCHANGED);
        assertThat(state.isRequestedImeVisible()).isTrue();

        assertThat(mComputer.mRequestedShowExplicitly).isFalse();
    }

    @Test
    public void testRequestImeVisibility_showExplicit() {
        initImeTargetWindowState(mWindowToken);
        boolean res = mComputer.onImeShowFlags(null, 0 /* show explicit */);
        mComputer.requestImeVisibility(mWindowToken, res);

        final ImeTargetWindowState state = mComputer.getWindowStateOrNull(mWindowToken);
        assertThat(state).isNotNull();
        assertThat(state.hasEdiorFocused()).isTrue();
        assertThat(state.getSoftInputModeState()).isEqualTo(SOFT_INPUT_STATE_UNCHANGED);
        assertThat(state.isRequestedImeVisible()).isTrue();

        assertThat(mComputer.mRequestedShowExplicitly).isTrue();
    }

    @Test
    public void testRequestImeVisibility_showImplicit_a11yNoImePolicy() {
        // Precondition: set AccessibilityService#SHOW_MODE_HIDDEN policy
        mComputer.getImePolicy().setA11yRequestNoSoftKeyboard(SHOW_MODE_HIDDEN);

        initImeTargetWindowState(mWindowToken);
        boolean res = mComputer.onImeShowFlags(null, InputMethodManager.SHOW_IMPLICIT);
        mComputer.requestImeVisibility(mWindowToken, res);

        final ImeTargetWindowState state = mComputer.getWindowStateOrNull(mWindowToken);
        assertThat(state).isNotNull();
        assertThat(state.hasEdiorFocused()).isTrue();
        assertThat(state.getSoftInputModeState()).isEqualTo(SOFT_INPUT_STATE_UNCHANGED);
        assertThat(state.isRequestedImeVisible()).isFalse();

        assertThat(mComputer.mRequestedShowExplicitly).isFalse();
    }

    @Test
    public void testRequestImeVisibility_showImplicit_imeHiddenPolicy() {
        // Precondition: set IME hidden display policy before calling showSoftInput
        mComputer.getImePolicy().setImeHiddenByDisplayPolicy(true);

        initImeTargetWindowState(mWindowToken);
        boolean res = mComputer.onImeShowFlags(null, InputMethodManager.SHOW_IMPLICIT);
        mComputer.requestImeVisibility(mWindowToken, res);

        final ImeTargetWindowState state = mComputer.getWindowStateOrNull(mWindowToken);
        assertThat(state).isNotNull();
        assertThat(state.hasEdiorFocused()).isTrue();
        assertThat(state.getSoftInputModeState()).isEqualTo(SOFT_INPUT_STATE_UNCHANGED);
        assertThat(state.isRequestedImeVisible()).isFalse();

        assertThat(mComputer.mRequestedShowExplicitly).isFalse();
    }

    @Test
    public void testRequestImeVisibility_hideNotAlways() {
        // Precondition: ensure IME has shown before hiding request.
        mComputer.setInputShown(true);

        initImeTargetWindowState(mWindowToken);
        assertThat(mComputer.canHideIme(null, InputMethodManager.HIDE_NOT_ALWAYS)).isTrue();
        mComputer.requestImeVisibility(mWindowToken, false);

        final ImeTargetWindowState state = mComputer.getWindowStateOrNull(mWindowToken);
        assertThat(state).isNotNull();
        assertThat(state.hasEdiorFocused()).isTrue();
        assertThat(state.getSoftInputModeState()).isEqualTo(SOFT_INPUT_STATE_UNCHANGED);
        assertThat(state.isRequestedImeVisible()).isFalse();
    }

    @Test
    public void testComputeImeDisplayId() {
        final ImeTargetWindowState state = mComputer.getOrCreateWindowState(mWindowToken);

        mImeDisplayPolicy = DISPLAY_IME_POLICY_LOCAL;
        mComputer.computeImeDisplayId(state, DEFAULT_DISPLAY);
        assertThat(mComputer.getImePolicy().isImeHiddenByDisplayPolicy()).isFalse();
        assertThat(state.getImeDisplayId()).isEqualTo(DEFAULT_DISPLAY);

        mComputer.computeImeDisplayId(state, 10 /* displayId */);
        assertThat(mComputer.getImePolicy().isImeHiddenByDisplayPolicy()).isFalse();
        assertThat(state.getImeDisplayId()).isEqualTo(10);

        mImeDisplayPolicy = DISPLAY_IME_POLICY_HIDE;
        mComputer.computeImeDisplayId(state, 10 /* displayId */);
        assertThat(mComputer.getImePolicy().isImeHiddenByDisplayPolicy()).isTrue();
        assertThat(state.getImeDisplayId()).isEqualTo(INVALID_DISPLAY);

        mImeDisplayPolicy = DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
        mComputer.computeImeDisplayId(state, 10 /* displayId */);
        assertThat(mComputer.getImePolicy().isImeHiddenByDisplayPolicy()).isFalse();
        assertThat(state.getImeDisplayId()).isEqualTo(FALLBACK_DISPLAY_ID);
    }

    private void initImeTargetWindowState(IBinder windowToken) {
        final ImeTargetWindowState state = new ImeTargetWindowState(SOFT_INPUT_STATE_UNCHANGED,
                0, true, true, true);
        mComputer.setWindowState(windowToken, state);
    }
}
