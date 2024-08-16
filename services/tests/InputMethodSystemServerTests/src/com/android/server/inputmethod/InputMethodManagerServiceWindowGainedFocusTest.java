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

import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import android.os.IBinder;
import android.os.LocaleList;
import android.os.RemoteException;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.window.ImeOnBackInvokedDispatcher;

import com.android.internal.inputmethod.IInputMethodClient;
import com.android.internal.inputmethod.IRemoteAccessibilityInputConnection;
import com.android.internal.inputmethod.IRemoteInputConnection;
import com.android.internal.inputmethod.InputBindResult;
import com.android.internal.inputmethod.InputMethodDebug;
import com.android.internal.inputmethod.StartInputFlags;
import com.android.internal.inputmethod.StartInputReason;
import com.android.server.companion.virtual.VirtualDeviceManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;

/**
 * Test the behavior of {@link InputMethodManagerService#startInputOrWindowGainedFocus(int,
 * IInputMethodClient, IBinder, int, int, int, EditorInfo, IRemoteInputConnection,
 * IRemoteAccessibilityInputConnection, int, int, ImeOnBackInvokedDispatcher)}.
 */
@RunWith(Parameterized.class)
public class InputMethodManagerServiceWindowGainedFocusTest
        extends InputMethodManagerServiceTestBase {
    private static final String TAG = "IMMSWindowGainedFocusTest";

    private static final int[] SOFT_INPUT_STATE_FLAGS =
            new int[] {
                SOFT_INPUT_STATE_UNSPECIFIED,
                SOFT_INPUT_STATE_UNCHANGED,
                SOFT_INPUT_STATE_HIDDEN,
                SOFT_INPUT_STATE_ALWAYS_HIDDEN,
                SOFT_INPUT_STATE_VISIBLE,
                SOFT_INPUT_STATE_ALWAYS_VISIBLE
            };
    private static final int[] SOFT_INPUT_ADJUST_FLAGS =
            new int[] {
                SOFT_INPUT_ADJUST_UNSPECIFIED,
                SOFT_INPUT_ADJUST_RESIZE,
                SOFT_INPUT_ADJUST_PAN,
                SOFT_INPUT_ADJUST_NOTHING
            };
    private static final int DEFAULT_SOFT_INPUT_FLAG =
            StartInputFlags.VIEW_HAS_FOCUS | StartInputFlags.IS_TEXT_EDITOR;
    @Mock
    VirtualDeviceManagerInternal mMockVdmInternal;

    @Parameterized.Parameters(name = "softInputState={0}, softInputAdjustment={1}")
    public static List<Object[]> softInputModeConfigs() {
        ArrayList<Object[]> params = new ArrayList<>();
        for (int softInputState : SOFT_INPUT_STATE_FLAGS) {
            for (int softInputAdjust : SOFT_INPUT_ADJUST_FLAGS) {
                params.add(new Object[] {softInputState, softInputAdjust});
            }
        }
        return params;
    }

    private final int mSoftInputState;
    private final int mSoftInputAdjustment;

    public InputMethodManagerServiceWindowGainedFocusTest(
            int softInputState, int softInputAdjustment) {
        mSoftInputState = softInputState;
        mSoftInputAdjustment = softInputAdjustment;
    }

    @Test
    public void startInputOrWindowGainedFocus_forwardNavigation() throws RemoteException {
        mockHasImeFocusAndRestoreImeVisibility(false /* restoreImeVisibility */);

        assertThat(
                        startInputOrWindowGainedFocus(
                                DEFAULT_SOFT_INPUT_FLAG, true /* forwardNavigation */))
                .isEqualTo(SUCCESS_WAITING_IME_BINDING_RESULT);

        switch (mSoftInputState) {
            case SOFT_INPUT_STATE_UNSPECIFIED:
                boolean showSoftInput =
                        (mSoftInputAdjustment == SOFT_INPUT_ADJUST_RESIZE) || mIsLargeScreen;
                if (android.view.inputmethod.Flags.refactorInsetsController()) {
                    verifySetImeVisibility(true /* setVisible */, showSoftInput /* invoked */);
                    // A hide can only be triggered if there is no editorFocused, which this test
                    // always sets.
                    verifySetImeVisibility(false /* setVisible */, false /* invoked */);
                } else {
                    verifyShowSoftInput(showSoftInput /* setVisible */,
                            showSoftInput /* showSoftInput */);
                    // Soft input was hidden by default, so it doesn't need to call
                    // {@code IMS#hideSoftInput()}.
                    verifyHideSoftInput(!showSoftInput /* setNotVisible */,
                            false /* hideSoftInput */);
                }
                break;
            case SOFT_INPUT_STATE_VISIBLE:
            case SOFT_INPUT_STATE_ALWAYS_VISIBLE:
                if (android.view.inputmethod.Flags.refactorInsetsController()) {
                    verifySetImeVisibility(true /* setVisible */, true /* invoked */);
                    verifySetImeVisibility(false /* setVisible */, false /* invoked */);
                } else {
                    verifyShowSoftInput(true /* setVisible */, true /* showSoftInput */);
                    verifyHideSoftInput(false /* setNotVisible */, false /* hideSoftInput */);
                }
                break;
            case SOFT_INPUT_STATE_UNCHANGED:
                if (android.view.inputmethod.Flags.refactorInsetsController()) {
                    verifySetImeVisibility(true /* setVisible */, false /* invoked */);
                    verifySetImeVisibility(false /* setVisible */, false /* invoked */);
                } else {
                    verifyShowSoftInput(false /* setVisible */, false /* showSoftInput */);
                    verifyHideSoftInput(false /* setNotVisible */, false /* hideSoftInput */);
                }
                break;
            case SOFT_INPUT_STATE_HIDDEN:
            case SOFT_INPUT_STATE_ALWAYS_HIDDEN:
                if (android.view.inputmethod.Flags.refactorInsetsController()) {
                    verifySetImeVisibility(true /* setVisible */, false /* invoked */);
                    // In this case, we don't have to manipulate the requested visible types of
                    // the WindowState, as they're already in the correct state
                    verifySetImeVisibility(false /* setVisible */, false /* invoked */);
                } else {
                    verifyShowSoftInput(false /* setVisible */, false /* showSoftInput */);
                    // Soft input was hidden by default, so it doesn't need to call
                    // {@code IMS#hideSoftInput()}.
                    verifyHideSoftInput(true /* setNotVisible */, false /* hideSoftInput */);
                }
                break;
            default:
                throw new IllegalStateException(
                        "Unhandled soft input mode: "
                                + InputMethodDebug.softInputModeToString(mSoftInputState));
        }
    }

    @Test
    public void startInputOrWindowGainedFocus_notForwardNavigation() throws RemoteException {
        mockHasImeFocusAndRestoreImeVisibility(false /* restoreImeVisibility */);

        assertThat(
                        startInputOrWindowGainedFocus(
                                DEFAULT_SOFT_INPUT_FLAG, false /* forwardNavigation */))
                .isEqualTo(SUCCESS_WAITING_IME_BINDING_RESULT);

        switch (mSoftInputState) {
            case SOFT_INPUT_STATE_UNSPECIFIED:
                boolean hideSoftInput =
                        (mSoftInputAdjustment != SOFT_INPUT_ADJUST_RESIZE) && !mIsLargeScreen;
                if (android.view.inputmethod.Flags.refactorInsetsController()) {
                    // A show can only be triggered in forward navigation
                    verifySetImeVisibility(false /* setVisible */, false /* invoked */);
                    // A hide can only be triggered if there is no editorFocused, which this test
                    // always sets.
                    verifySetImeVisibility(false /* setVisible */, false /* invoked */);
                } else {
                    verifyShowSoftInput(false /* setVisible */, false /* showSoftInput */);
                    // Soft input was hidden by default, so it doesn't need to call
                    // {@code IMS#hideSoftInput()}.
                    verifyHideSoftInput(hideSoftInput /* setNotVisible */,
                            false /* hideSoftInput */);
                }
                break;
            case SOFT_INPUT_STATE_VISIBLE:
            case SOFT_INPUT_STATE_HIDDEN:
            case SOFT_INPUT_STATE_UNCHANGED: // Do nothing
                if (android.view.inputmethod.Flags.refactorInsetsController()) {
                    verifySetImeVisibility(true /* setVisible */, false /* invoked */);
                    verifySetImeVisibility(false /* setVisible */, false /* invoked */);
                } else {
                    verifyShowSoftInput(false /* setVisible */, false /* showSoftInput */);
                    verifyHideSoftInput(false /* setNotVisible */, false /* hideSoftInput */);
                }
                break;
            case SOFT_INPUT_STATE_ALWAYS_VISIBLE:
                if (android.view.inputmethod.Flags.refactorInsetsController()) {
                    verifySetImeVisibility(true /* setVisible */, true /* invoked */);
                    verifySetImeVisibility(false /* setVisible */, false /* invoked */);
                } else {
                    verifyShowSoftInput(true /* setVisible */, true /* showSoftInput */);
                    verifyHideSoftInput(false /* setNotVisible */, false /* hideSoftInput */);
                }
                break;
            case SOFT_INPUT_STATE_ALWAYS_HIDDEN:
                if (android.view.inputmethod.Flags.refactorInsetsController()) {
                    verifySetImeVisibility(true /* setVisible */, false /* invoked */);
                    // In this case, we don't have to manipulate the requested visible types of
                    // the WindowState, as they're already in the correct state
                    verifySetImeVisibility(false /* setVisible */, false /* invoked */);
                } else {
                    verifyShowSoftInput(false /* setVisible */, false /* showSoftInput */);
                    // Soft input was hidden by default, so it doesn't need to call
                    // {@code IMS#hideSoftInput()}.
                    verifyHideSoftInput(true /* setNotVisible */, false /* hideSoftInput */);
                }
                break;
            default:
                throw new IllegalStateException(
                        "Unhandled soft input mode: "
                                + InputMethodDebug.softInputModeToString(mSoftInputState));
        }
    }

    @Test
    public void startInputOrWindowGainedFocus_userNotRunning() throws RemoteException {
        // Run blockingly on ServiceThread to avoid that interfering with our stubbing.
        mServiceThread.getThreadHandler().runWithScissors(
                () -> when(mMockUserManagerInternal.isUserRunning(anyInt())).thenReturn(false), 0);

        assertThat(
                        startInputOrWindowGainedFocus(
                                DEFAULT_SOFT_INPUT_FLAG, true /* forwardNavigation */))
                .isEqualTo(InputBindResult.INVALID_USER);
        verifyShowSoftInput(false /* setVisible */, false /* showSoftInput */);
        verifyHideSoftInput(false /* setNotVisible */, false /* hideSoftInput */);
    }

    @Test
    public void startInputOrWindowGainedFocus_invalidFocusStatus() throws RemoteException {
        int[] invalidImeClientFocus =
                new int[] {
                    WindowManagerInternal.ImeClientFocusResult.NOT_IME_TARGET_WINDOW,
                    WindowManagerInternal.ImeClientFocusResult.DISPLAY_ID_MISMATCH,
                    WindowManagerInternal.ImeClientFocusResult.INVALID_DISPLAY_ID
                };
        InputBindResult[] inputBingResult =
                new InputBindResult[] {
                    InputBindResult.NOT_IME_TARGET_WINDOW,
                    InputBindResult.DISPLAY_ID_MISMATCH,
                    InputBindResult.INVALID_DISPLAY_ID
                };

        for (int i = 0; i < invalidImeClientFocus.length; i++) {
            when(mMockWindowManagerInternal.hasInputMethodClientFocus(
                            any(), anyInt(), anyInt(), anyInt()))
                    .thenReturn(invalidImeClientFocus[i]);

            assertThat(
                            startInputOrWindowGainedFocus(
                                    DEFAULT_SOFT_INPUT_FLAG, true /* forwardNavigation */))
                    .isEqualTo(inputBingResult[i]);
            verifyShowSoftInput(false /* setVisible */, false /* showSoftInput */);
            verifyHideSoftInput(false /* setNotVisible */, false /* hideSoftInput */);
        }
    }

    private InputBindResult startInputOrWindowGainedFocus(
            int startInputFlag, boolean forwardNavigation) {
        int softInputMode = mSoftInputState | mSoftInputAdjustment;
        if (forwardNavigation) {
            softInputMode |= SOFT_INPUT_IS_FORWARD_NAVIGATION;
        }

        Log.i(
                TAG,
                "startInputOrWindowGainedFocus() softInputStateFlag="
                        + InputMethodDebug.softInputModeToString(mSoftInputState)
                        + ", softInputAdjustFlag="
                        + InputMethodDebug.softInputModeToString(mSoftInputAdjustment));

        return mInputMethodManagerService.startInputOrWindowGainedFocus(
                StartInputReason.WINDOW_FOCUS_GAIN /* startInputReason */,
                mMockInputMethodClient /* client */,
                mWindowToken /* windowToken */,
                startInputFlag /* startInputFlags */,
                softInputMode /* softInputMode */,
                0 /* windowFlags */,
                mEditorInfo /* editorInfo */,
                mMockRemoteInputConnection /* inputConnection */,
                mMockRemoteAccessibilityInputConnection /* remoteAccessibilityInputConnection */,
                mTargetSdkVersion /* unverifiedTargetSdkVersion */,
                mUserId /* userId */,
                mMockImeOnBackInvokedDispatcher /* imeDispatcher */);
    }

    @Test
    public void startInputOrWindowGainedFocus_localeHintsOverride() throws RemoteException {
        addLocalServiceMock(VirtualDeviceManagerInternal.class, mMockVdmInternal);
        LocaleList overrideLocale = LocaleList.forLanguageTags("zh-CN");
        doReturn(overrideLocale).when(mMockVdmInternal).getPreferredLocaleListForUid(anyInt());
        mockHasImeFocusAndRestoreImeVisibility(false /* restoreImeVisibility */);

        assertThat(startInputOrWindowGainedFocus(DEFAULT_SOFT_INPUT_FLAG,
                true /* forwardNavigation */)).isEqualTo(SUCCESS_WAITING_IME_BINDING_RESULT);
        assertThat(mEditorInfo.hintLocales).isEqualTo(overrideLocale);
    }

    private void mockHasImeFocusAndRestoreImeVisibility(boolean restoreImeVisibility) {
        when(mMockWindowManagerInternal.hasInputMethodClientFocus(
                        any(), anyInt(), anyInt(), anyInt()))
                .thenReturn(WindowManagerInternal.ImeClientFocusResult.HAS_IME_FOCUS);
        when(mMockWindowManagerInternal.shouldRestoreImeVisibility(any()))
                .thenReturn(restoreImeVisibility);
    }
}
