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

import static android.view.inputmethod.ImeTracker.DEBUG_IME_VISIBILITY;

import static com.android.internal.inputmethod.SoftInputShowHideReason.REMOVE_IME_SCREENSHOT_FROM_IMMS;
import static com.android.internal.inputmethod.SoftInputShowHideReason.SHOW_IME_SCREENSHOT_FROM_IMMS;
import static com.android.server.EventLogTags.IMF_HIDE_IME;
import static com.android.server.EventLogTags.IMF_SHOW_IME;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.STATE_HIDE_IME;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.STATE_HIDE_IME_EXPLICIT;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.STATE_HIDE_IME_NOT_ALWAYS;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.STATE_REMOVE_IME_SNAPSHOT;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.STATE_SHOW_IME;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.STATE_SHOW_IME_IMPLICIT;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.STATE_SHOW_IME_SNAPSHOT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.util.EventLog;
import android.util.Slog;
import android.view.MotionEvent;
import android.view.inputmethod.Flags;
import android.view.inputmethod.ImeTracker;
import android.view.inputmethod.InputMethod;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.inputmethod.InputMethodDebug;
import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.server.LocalServices;
import com.android.server.wm.ImeTargetVisibilityPolicy;
import com.android.server.wm.WindowManagerInternal;

import java.util.Objects;

/**
 * A stateless helper class for IME visibility operations like show/hide and update Z-ordering
 * relative to the IME targeted window.
 */
final class DefaultImeVisibilityApplier {

    private static final String TAG = "DefaultImeVisibilityApplier";

    private static final boolean DEBUG = InputMethodManagerService.DEBUG;

    private InputMethodManagerService mService;

    private final WindowManagerInternal mWindowManagerInternal;

    @NonNull
    private final ImeTargetVisibilityPolicy mImeTargetVisibilityPolicy;

    DefaultImeVisibilityApplier(InputMethodManagerService service) {
        mService = service;
        mWindowManagerInternal = LocalServices.getService(WindowManagerInternal.class);
        mImeTargetVisibilityPolicy = LocalServices.getService(ImeTargetVisibilityPolicy.class);
    }

    /**
     * Performs showing IME on top of the given window.
     *
     * @param showInputToken a token that represents the requester to show IME
     * @param statsToken     the token tracking the current IME request
     * @param resultReceiver if non-null, this will be called back to the caller when
     *                       it has processed request to tell what it has done
     * @param reason         yhe reason for requesting to show IME
     * @param userId         the target user when performing show IME
     */
    @GuardedBy("ImfLock.class")
    void performShowIme(IBinder showInputToken, @NonNull ImeTracker.Token statsToken,
            @InputMethod.ShowFlags int showFlags, ResultReceiver resultReceiver,
            @SoftInputShowHideReason int reason, @UserIdInt int userId) {
        final var userData = mService.getUserData(userId);
        final var bindingController = userData.mBindingController;
        final IInputMethodInvoker curMethod = bindingController.getCurMethod();
        if (curMethod != null) {
            if (DEBUG) {
                Slog.v(TAG, "Calling " + curMethod + ".showSoftInput(" + showInputToken
                        + ", " + showFlags + ", " + resultReceiver + ") for reason: "
                        + InputMethodDebug.softInputDisplayReasonToString(reason));
            }
            // TODO(b/192412909): Check if we can always call onShowHideSoftInputRequested() or not.
            if (curMethod.showSoftInput(showInputToken, statsToken, showFlags, resultReceiver)) {
                if (DEBUG_IME_VISIBILITY) {
                    EventLog.writeEvent(IMF_SHOW_IME,
                            statsToken != null ? statsToken.getTag() : ImeTracker.TOKEN_NONE,
                            Objects.toString(userData.mImeBindingState.mFocusedWindow),
                            InputMethodDebug.softInputDisplayReasonToString(reason),
                            InputMethodDebug.softInputModeToString(
                                    userData.mImeBindingState.mFocusedWindowSoftInputMode));
                }
                mService.onShowHideSoftInputRequested(true /* show */, showInputToken, reason,
                        statsToken, userId);
            }
        }
    }

    /**
     * Performs hiding IME to the given window
     *
     * @param hideInputToken a token that represents the requester to hide IME
     * @param statsToken     the token tracking the current IME request
     * @param resultReceiver if non-null, this will be called back to the caller when
     *                       it has processed request to tell what it has done
     * @param reason         the reason for requesting to hide IME
     * @param userId         the target user when performing hide IME
     */
    @GuardedBy("ImfLock.class")
    void performHideIme(IBinder hideInputToken, @NonNull ImeTracker.Token statsToken,
            ResultReceiver resultReceiver, @SoftInputShowHideReason int reason,
            @UserIdInt int userId) {
        final var userData = mService.getUserData(userId);
        final var bindingController = userData.mBindingController;
        final IInputMethodInvoker curMethod = bindingController.getCurMethod();
        if (curMethod != null) {
            // The IME will report its visible state again after the following message finally
            // delivered to the IME process as an IPC.  Hence the inconsistency between
            // IMMS#mInputShown and IMMS#mImeWindowVis should be resolved spontaneously in
            // the final state.
            if (DEBUG) {
                Slog.v(TAG, "Calling " + curMethod + ".hideSoftInput(0, " + hideInputToken
                        + ", " + resultReceiver + ") for reason: "
                        + InputMethodDebug.softInputDisplayReasonToString(reason));
            }
            // TODO(b/192412909): Check if we can always call onShowHideSoftInputRequested() or not.
            if (curMethod.hideSoftInput(hideInputToken, statsToken, 0, resultReceiver)) {
                if (DEBUG_IME_VISIBILITY) {
                    EventLog.writeEvent(IMF_HIDE_IME,
                            statsToken != null ? statsToken.getTag() : ImeTracker.TOKEN_NONE,
                            Objects.toString(userData.mImeBindingState.mFocusedWindow),
                            InputMethodDebug.softInputDisplayReasonToString(reason),
                            InputMethodDebug.softInputModeToString(
                                    userData.mImeBindingState.mFocusedWindowSoftInputMode));
                }
                mService.onShowHideSoftInputRequested(false /* show */, hideInputToken, reason,
                        statsToken, userId);
            }
        }
    }

    /**
     * Applies the IME visibility from {@link android.inputmethodservice.InputMethodService} with
     * according to the given visibility state.
     *
     * @param windowToken the token of a window for applying the IME visibility
     * @param statsToken  the token tracking the current IME request
     * @param state       the new IME visibility state for the applier to handle
     * @param reason      one of {@link SoftInputShowHideReason}
     * @param userId      the target user when applying the IME visibility state
     */
    @GuardedBy("ImfLock.class")
    void applyImeVisibility(IBinder windowToken, @Nullable ImeTracker.Token statsToken,
            @ImeVisibilityStateComputer.VisibilityState int state,
            @SoftInputShowHideReason int reason, @UserIdInt int userId) {
        final var userData = mService.getUserData(userId);
        final var bindingController = userData.mBindingController;
        final int displayIdToShowIme = bindingController.getDisplayIdToShowIme();
        switch (state) {
            case STATE_SHOW_IME:
                if (!Flags.refactorInsetsController()) {
                    ImeTracker.forLogging().onProgress(statsToken,
                            ImeTracker.PHASE_SERVER_APPLY_IME_VISIBILITY);
                    // Send to window manager to show IME after IME layout finishes.
                    mWindowManagerInternal.showImePostLayout(windowToken, statsToken);
                }
                break;
            case STATE_HIDE_IME:
                if (!Flags.refactorInsetsController()) {
                    if (userData.mCurClient != null) {
                        ImeTracker.forLogging().onProgress(statsToken,
                                ImeTracker.PHASE_SERVER_APPLY_IME_VISIBILITY);
                        // IMMS only knows of focused window, not the actual IME target.
                        // e.g. it isn't aware of any window that has both
                        // NOT_FOCUSABLE, ALT_FOCUSABLE_IM flags set and can the IME target.
                        // Send it to window manager to hide IME from the actual IME control target
                        // of the target display.
                        mWindowManagerInternal.hideIme(windowToken, displayIdToShowIme, statsToken);
                    } else {
                        ImeTracker.forLogging().onFailed(statsToken,
                                ImeTracker.PHASE_SERVER_APPLY_IME_VISIBILITY);
                    }
                }
                break;
            case STATE_HIDE_IME_EXPLICIT:
                if (Flags.refactorInsetsController()) {
                    mService.setImeVisibilityOnFocusedWindowClient(false, userData, statsToken);
                } else {
                    mService.hideCurrentInputLocked(windowToken, statsToken,
                            0 /* flags */, null /* resultReceiver */, reason, userId);
                }
                break;
            case STATE_HIDE_IME_NOT_ALWAYS:
                if (Flags.refactorInsetsController()) {
                    mService.setImeVisibilityOnFocusedWindowClient(false, userData, statsToken);
                } else {
                    mService.hideCurrentInputLocked(windowToken, statsToken,
                            InputMethodManager.HIDE_NOT_ALWAYS, null /* resultReceiver */, reason,
                            userId);
                }
                break;
            case STATE_SHOW_IME_IMPLICIT:
                if (Flags.refactorInsetsController()) {
                    // This can be triggered by IMMS#startInputOrWindowGainedFocus. We need to
                    // set the requestedVisibleTypes in InsetsController first, before applying it.
                    mService.setImeVisibilityOnFocusedWindowClient(true, userData, statsToken);
                } else {
                    mService.showCurrentInputLocked(windowToken, statsToken,
                            InputMethodManager.SHOW_IMPLICIT, MotionEvent.TOOL_TYPE_UNKNOWN,
                            null /* resultReceiver */, reason, userId);
                }
                break;
            case STATE_SHOW_IME_SNAPSHOT:
                showImeScreenshot(windowToken, displayIdToShowIme, userId);
                break;
            case STATE_REMOVE_IME_SNAPSHOT:
                removeImeScreenshot(displayIdToShowIme, userId);
                break;
            default:
                throw new IllegalArgumentException("Invalid IME visibility state: " + state);
        }
    }

    /**
     * Shows the IME screenshot and attach it to the given IME target window.
     *
     * @param imeTarget   the token of a window to show the IME screenshot
     * @param displayId   the unique id to identify the display
     * @param userId      the target user when when showing the IME screenshot
     * @return {@code true} if success, {@code false} otherwise
     */
    @GuardedBy("ImfLock.class")
    boolean showImeScreenshot(@NonNull IBinder imeTarget, int displayId,
            @UserIdInt int userId) {
        if (mImeTargetVisibilityPolicy.showImeScreenshot(imeTarget, displayId)) {
            mService.onShowHideSoftInputRequested(false /* show */, imeTarget,
                    SHOW_IME_SCREENSHOT_FROM_IMMS, null /* statsToken */, userId);
            return true;
        }
        return false;
    }

    /**
     * Removes the IME screenshot on the given display.
     *
     * @param displayId the target display of showing IME screenshot
     * @param userId    the target user of showing IME screenshot
     * @return {@code true} if success, {@code false} otherwise
     */
    @GuardedBy("ImfLock.class")
    boolean removeImeScreenshot(int displayId, @UserIdInt int userId) {
        final var userData = mService.getUserData(userId);
        if (mImeTargetVisibilityPolicy.removeImeScreenshot(displayId)) {
            mService.onShowHideSoftInputRequested(false /* show */,
                    userData.mImeBindingState.mFocusedWindow,
                    REMOVE_IME_SCREENSHOT_FROM_IMMS, null /* statsToken */, userId);
            return true;
        }
        return false;
    }
}
