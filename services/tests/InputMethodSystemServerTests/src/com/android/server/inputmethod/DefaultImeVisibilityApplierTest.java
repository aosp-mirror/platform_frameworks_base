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
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE;

import static com.android.internal.inputmethod.SoftInputShowHideReason.HIDE_SOFT_INPUT;
import static com.android.internal.inputmethod.SoftInputShowHideReason.HIDE_SWITCH_USER;
import static com.android.internal.inputmethod.SoftInputShowHideReason.SHOW_SOFT_INPUT;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.STATE_HIDE_IME;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.STATE_HIDE_IME_EXPLICIT;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.STATE_HIDE_IME_NOT_ALWAYS;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.STATE_INVALID;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.STATE_SHOW_IME;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.STATE_SHOW_IME_IMPLICIT;

import static org.junit.Assert.assertThrows;
import static org.mockito.AdditionalMatchers.and;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.util.Objects.requireNonNull;

import android.annotation.Nullable;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.view.Display;
import android.view.inputmethod.Flags;
import android.view.inputmethod.ImeTracker;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.inputmethod.InputBindResult;
import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.internal.inputmethod.StartInputFlags;
import com.android.internal.inputmethod.StartInputReason;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test the behavior of {@link DefaultImeVisibilityApplier} when performing or applying the IME
 * visibility state.
 *
 * <p>Build/Install/Run:
 * atest FrameworksInputMethodSystemServerTests:DefaultImeVisibilityApplierTest
 */
@RunWith(AndroidJUnit4.class)
public class DefaultImeVisibilityApplierTest extends InputMethodManagerServiceTestBase {

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();
    private DefaultImeVisibilityApplier mVisibilityApplier;

    @Before
    public void setUp() throws RemoteException {
        super.setUp();
        synchronized (ImfLock.class) {
            mVisibilityApplier = mInputMethodManagerService.getVisibilityApplierLocked();
            setAttachedClientLocked(requireNonNull(
                    mInputMethodManagerService.getClientStateLocked(mMockInputMethodClient)));
        }
    }

    @Test
    public void testPerformShowIme() throws Exception {
        synchronized (ImfLock.class) {
            mVisibilityApplier.performShowIme(new Binder() /* showInputToken */,
                    ImeTracker.Token.empty(), 0 /* showFlags */, null /* resultReceiver */,
                    SHOW_SOFT_INPUT, mUserId);
        }
        verifyShowSoftInput(false, true, 0 /* showFlags */);
    }

    @Test
    public void testPerformHideIme() throws Exception {
        synchronized (ImfLock.class) {
            mVisibilityApplier.performHideIme(new Binder() /* hideInputToken */,
                    ImeTracker.Token.empty(), null /* resultReceiver */, HIDE_SOFT_INPUT, mUserId);
        }
        verifyHideSoftInput(false, true);
    }

    @Test
    public void testApplyImeVisibility_throwForInvalidState() {
        assertThrows(IllegalArgumentException.class, () -> {
            synchronized (ImfLock.class) {
                mVisibilityApplier.applyImeVisibility(mWindowToken, ImeTracker.Token.empty(),
                        STATE_INVALID, eq(SoftInputShowHideReason.NOT_SET), mUserId);
            }
        });
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_REFACTOR_INSETS_CONTROLLER)
    public void testApplyImeVisibility_showIme() {
        final var statsToken = ImeTracker.Token.empty();
        synchronized (ImfLock.class) {
            mVisibilityApplier.applyImeVisibility(mWindowToken, statsToken, STATE_SHOW_IME,
                    eq(SoftInputShowHideReason.NOT_SET), mUserId);
        }
        verify(mMockWindowManagerInternal).showImePostLayout(eq(mWindowToken), eq(statsToken));
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_REFACTOR_INSETS_CONTROLLER)
    public void testApplyImeVisibility_hideIme() {
        final var statsToken = ImeTracker.Token.empty();
        synchronized (ImfLock.class) {
            mVisibilityApplier.applyImeVisibility(mWindowToken, statsToken, STATE_HIDE_IME,
                    eq(SoftInputShowHideReason.NOT_SET), mUserId);
        }
        verify(mMockWindowManagerInternal).hideIme(eq(mWindowToken), anyInt() /* displayId */,
                eq(statsToken));
    }

    @Test
    public void testApplyImeVisibility_hideImeExplicit() throws Exception {
        synchronized (ImfLock.class) {
            final var bindingController =
                    mInputMethodManagerService.getInputMethodBindingController(mUserId);
            when(bindingController.getImeWindowVis()).thenReturn(IME_ACTIVE);
            mVisibilityApplier.applyImeVisibility(mWindowToken, ImeTracker.Token.empty(),
                    STATE_HIDE_IME_EXPLICIT, eq(SoftInputShowHideReason.NOT_SET), mUserId);
        }
        if (Flags.refactorInsetsController()) {
            verifySetImeVisibility(true /* setVisible */, false /* invoked */);
            verifySetImeVisibility(false /* setVisible */, true /* invoked */);
        } else {
            verifyHideSoftInput(true, true);
        }
    }

    @Test
    public void testApplyImeVisibility_hideNotAlways() throws Exception {
        synchronized (ImfLock.class) {
            final var bindingController =
                    mInputMethodManagerService.getInputMethodBindingController(mUserId);
            when(bindingController.getImeWindowVis()).thenReturn(IME_ACTIVE);
            mVisibilityApplier.applyImeVisibility(mWindowToken, ImeTracker.Token.empty(),
                    STATE_HIDE_IME_NOT_ALWAYS, eq(SoftInputShowHideReason.NOT_SET), mUserId);
        }
        if (Flags.refactorInsetsController()) {
            verifySetImeVisibility(true /* setVisible */, false /* invoked */);
            verifySetImeVisibility(false /* setVisible */, true /* invoked */);
        } else {
            verifyHideSoftInput(true, true);
        }
    }

    @Test
    public void testApplyImeVisibility_showImeImplicit() throws Exception {
        synchronized (ImfLock.class) {
            mVisibilityApplier.applyImeVisibility(mWindowToken, ImeTracker.Token.empty(),
                    STATE_SHOW_IME_IMPLICIT, eq(SoftInputShowHideReason.NOT_SET), mUserId);
        }
        if (Flags.refactorInsetsController()) {
            verifySetImeVisibility(true /* setVisible */, true /* invoked */);
            verifySetImeVisibility(false /* setVisible */, false /* invoked */);
        } else {
            verifyShowSoftInput(true, true, 0 /* showFlags */);
        }
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_REFACTOR_INSETS_CONTROLLER)
    public void testApplyImeVisibility_hideImeFromTargetOnSecondaryDisplay() {
        // Init a IME target client on the secondary display to show IME.
        mInputMethodManagerService.addClient(mMockInputMethodClient, mMockRemoteInputConnection,
                10 /* selfReportedDisplayId */);
        synchronized (ImfLock.class) {
            setAttachedClientLocked(null);
        }
        startInputOrWindowGainedFocus(mWindowToken, SOFT_INPUT_STATE_ALWAYS_VISIBLE);

        final var statsToken = ImeTracker.Token.empty();
        synchronized (ImfLock.class) {
            final var bindingController =
                    mInputMethodManagerService.getInputMethodBindingController(mUserId);
            final int displayIdToShowIme = bindingController.getDisplayIdToShowIme();
            // Verify hideIme will apply the expected displayId when the default IME
            // visibility applier app STATE_HIDE_IME.
            mVisibilityApplier.applyImeVisibility(mWindowToken, statsToken, STATE_HIDE_IME,
                    eq(SoftInputShowHideReason.NOT_SET), mUserId);
            verify(mInputMethodManagerService.mWindowManagerInternal).hideIme(
                    eq(mWindowToken), eq(displayIdToShowIme), eq(statsToken));
        }
    }

    @Test
    public void testShowImeScreenshot() {
        synchronized (ImfLock.class) {
            mVisibilityApplier.showImeScreenshot(mWindowToken, Display.DEFAULT_DISPLAY, mUserId);
        }

        verify(mMockImeTargetVisibilityPolicy).showImeScreenshot(eq(mWindowToken),
                eq(Display.DEFAULT_DISPLAY));
    }

    @Test
    public void testRemoveImeScreenshot() {
        synchronized (ImfLock.class) {
            mVisibilityApplier.removeImeScreenshot(Display.DEFAULT_DISPLAY, mUserId);
        }

        verify(mMockImeTargetVisibilityPolicy).removeImeScreenshot(eq(Display.DEFAULT_DISPLAY));
    }

    @Test
    public void testApplyImeVisibility_hideImeWhenUnbinding() {
        synchronized (ImfLock.class) {
            setAttachedClientLocked(null);
        }
        startInputOrWindowGainedFocus(mWindowToken, SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        ExtendedMockito.spyOn(mVisibilityApplier);

        synchronized (ImfLock.class) {
            // Simulate the system hides the IME when switching IME services in different users.
            // (e.g. unbinding the IME from the current user to the profile user)
            final var statsToken = ImeTracker.Token.empty();
            final var bindingController =
                    mInputMethodManagerService.getInputMethodBindingController(mUserId);
            final int displayIdToShowIme = bindingController.getDisplayIdToShowIme();
            mInputMethodManagerService.hideCurrentInputLocked(mWindowToken,
                    statsToken, 0 /* flags */, null /* resultReceiver */,
                    HIDE_SWITCH_USER, mUserId);
            mInputMethodManagerService.onUnbindCurrentMethodByReset(mUserId);

            // Expects applyImeVisibility() -> hideIme() will be called to notify WM for syncing
            // the IME hidden state.
            // The unbind will cancel the previous stats token, and create a new one internally.
            verify(mVisibilityApplier).applyImeVisibility(
                    eq(mWindowToken), any(), eq(STATE_HIDE_IME),
                    eq(SoftInputShowHideReason.NOT_SET), eq(mUserId) /* userId */);
            if (!Flags.refactorInsetsController()) {
                verify(mInputMethodManagerService.mWindowManagerInternal).hideIme(eq(mWindowToken),
                        eq(displayIdToShowIme), and(not(eq(statsToken)), notNull()));
            }
        }
    }

    @GuardedBy("ImfLock.class")
    private void setAttachedClientLocked(@Nullable ClientState cs) {
        mInputMethodManagerService.getUserData(mUserId).mCurClient = cs;
    }

    private InputBindResult startInputOrWindowGainedFocus(IBinder windowToken, int softInputMode) {
        return mInputMethodManagerService.startInputOrWindowGainedFocus(
                StartInputReason.WINDOW_FOCUS_GAIN /* startInputReason */,
                mMockInputMethodClient /* client */,
                windowToken /* windowToken */,
                StartInputFlags.VIEW_HAS_FOCUS | StartInputFlags.IS_TEXT_EDITOR,
                softInputMode /* softInputMode */,
                0 /* windowFlags */,
                mEditorInfo /* editorInfo */,
                mMockRemoteInputConnection /* inputConnection */,
                mMockRemoteAccessibilityInputConnection /* remoteAccessibilityInputConnection */,
                mTargetSdkVersion /* unverifiedTargetSdkVersion */,
                mUserId /* userId */,
                mMockImeOnBackInvokedDispatcher /* imeDispatcher */);
    }
}
