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

import static android.inputmethodservice.InputMethodService.IME_ACTIVE;

import static com.android.internal.inputmethod.SoftInputShowHideReason.HIDE_SOFT_INPUT;
import static com.android.internal.inputmethod.SoftInputShowHideReason.SHOW_SOFT_INPUT;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.STATE_HIDE_IME;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.STATE_HIDE_IME_EXPLICIT;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.STATE_HIDE_IME_NOT_ALWAYS;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.STATE_INVALID;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.STATE_SHOW_IME;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.STATE_SHOW_IME_IMPLICIT;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.os.RemoteException;
import android.view.inputmethod.InputMethodManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test the behavior of {@link DefaultImeVisibilityApplier} when performing or applying the IME
 * visibility state.
 *
 * Build/Install/Run:
 * atest FrameworksInputMethodSystemServerTests:DefaultImeVisibilityApplierTest
 */
@RunWith(AndroidJUnit4.class)
public class DefaultImeVisibilityApplierTest extends InputMethodManagerServiceTestBase {
    private DefaultImeVisibilityApplier mVisibilityApplier;

    @Before
    public void setUp() throws RemoteException {
        super.setUp();
        mVisibilityApplier =
                (DefaultImeVisibilityApplier) mInputMethodManagerService.getVisibilityApplier();
        mInputMethodManagerService.mCurFocusedWindowClient = mock(
                InputMethodManagerService.ClientState.class);
    }

    @Test
    public void testPerformShowIme() throws Exception {
        mVisibilityApplier.performShowIme(mWindowToken, null, null, SHOW_SOFT_INPUT);
        verifyShowSoftInput(false, true, InputMethodManager.SHOW_IMPLICIT);
    }

    @Test
    public void testPerformHideIme() throws Exception {
        mVisibilityApplier.performHideIme(mWindowToken, null, null, HIDE_SOFT_INPUT);
        verifyHideSoftInput(false, true);
    }

    @Test
    public void testApplyImeVisibility_throwForInvalidState() {
        mVisibilityApplier.applyImeVisibility(mWindowToken, null, STATE_INVALID);
        assertThrows(IllegalArgumentException.class,
                () -> mVisibilityApplier.applyImeVisibility(mWindowToken, null, STATE_INVALID));
    }

    @Test
    public void testApplyImeVisibility_showIme() {
        mVisibilityApplier.applyImeVisibility(mWindowToken, null, STATE_SHOW_IME);
        verify(mMockWindowManagerInternal).showImePostLayout(eq(mWindowToken), any());
    }

    @Test
    public void testApplyImeVisibility_hideIme() {
        mVisibilityApplier.applyImeVisibility(mWindowToken, null, STATE_HIDE_IME);
        verify(mMockWindowManagerInternal).hideIme(eq(mWindowToken), anyInt(), any());
    }

    @Test
    public void testApplyImeVisibility_hideImeExplicit() throws Exception {
        mInputMethodManagerService.mImeWindowVis = IME_ACTIVE;
        mVisibilityApplier.applyImeVisibility(mWindowToken, null, STATE_HIDE_IME_EXPLICIT);
        verifyHideSoftInput(true, true);
    }

    @Test
    public void testApplyImeVisibility_hideNotAlways() throws Exception {
        mInputMethodManagerService.mImeWindowVis = IME_ACTIVE;
        mVisibilityApplier.applyImeVisibility(mWindowToken, null, STATE_HIDE_IME_NOT_ALWAYS);
        verifyHideSoftInput(true, true);
    }

    @Test
    public void testApplyImeVisibility_showImeImplicit() throws Exception {
        mVisibilityApplier.applyImeVisibility(mWindowToken, null, STATE_SHOW_IME_IMPLICIT);
        verifyShowSoftInput(true, true, InputMethodManager.SHOW_IMPLICIT);
    }
}
