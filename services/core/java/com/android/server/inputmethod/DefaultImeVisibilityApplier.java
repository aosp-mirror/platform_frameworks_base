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

import static com.android.server.EventLogTags.IMF_HIDE_IME;
import static com.android.server.EventLogTags.IMF_SHOW_IME;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.STATE_HIDE_IME;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.STATE_HIDE_IME_EXPLICIT;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.STATE_HIDE_IME_NOT_ALWAYS;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.STATE_SHOW_IME;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.STATE_SHOW_IME_IMPLICIT;

import android.annotation.Nullable;
import android.os.Binder;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.util.EventLog;
import android.util.Slog;
import android.view.inputmethod.ImeTracker;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.inputmethod.InputMethodDebug;
import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.server.LocalServices;
import com.android.server.wm.WindowManagerInternal;

import java.util.Objects;

/**
 * The default implementation of {@link ImeVisibilityApplier} used in
 * {@link InputMethodManagerService}.
 */
final class DefaultImeVisibilityApplier implements ImeVisibilityApplier {

    private static final String TAG = "DefaultImeVisibilityApplier";

    private static final boolean DEBUG = InputMethodManagerService.DEBUG;

    private InputMethodManagerService mService;

    private final WindowManagerInternal mWindowManagerInternal;


    DefaultImeVisibilityApplier(InputMethodManagerService service) {
        mService = service;
        mWindowManagerInternal = LocalServices.getService(WindowManagerInternal.class);
    }

    @GuardedBy("ImfLock.class")
    @Override
    public void performShowIme(IBinder windowToken, @Nullable ImeTracker.Token statsToken,
            int showFlags, ResultReceiver resultReceiver, @SoftInputShowHideReason int reason) {
        final IInputMethodInvoker curMethod = mService.getCurMethodLocked();
        if (curMethod != null) {
            // create a placeholder token for IMS so that IMS cannot inject windows into client app.
            final IBinder showInputToken = new Binder();
            mService.setRequestImeTokenToWindow(windowToken, showInputToken);
            if (DEBUG) {
                Slog.v(TAG, "Calling " + curMethod + ".showSoftInput(" + showInputToken
                        + ", " + showFlags + ", " + resultReceiver + ") for reason: "
                        + InputMethodDebug.softInputDisplayReasonToString(reason));
            }
            // TODO(b/192412909): Check if we can always call onShowHideSoftInputRequested() or not.
            if (curMethod.showSoftInput(showInputToken, statsToken, showFlags, resultReceiver)) {
                if (DEBUG_IME_VISIBILITY) {
                    EventLog.writeEvent(IMF_SHOW_IME, statsToken.getTag(),
                            Objects.toString(mService.mCurFocusedWindow),
                            InputMethodDebug.softInputDisplayReasonToString(reason),
                            InputMethodDebug.softInputModeToString(
                                    mService.mCurFocusedWindowSoftInputMode));
                }
                mService.onShowHideSoftInputRequested(true /* show */, windowToken, reason,
                        statsToken);
            }
        }
    }

    @GuardedBy("ImfLock.class")
    @Override
    public void performHideIme(IBinder windowToken, @Nullable ImeTracker.Token statsToken,
            ResultReceiver resultReceiver, @SoftInputShowHideReason int reason) {
        final IInputMethodInvoker curMethod = mService.getCurMethodLocked();
        if (curMethod != null) {
            final Binder hideInputToken = new Binder();
            mService.setRequestImeTokenToWindow(windowToken, hideInputToken);
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
                    EventLog.writeEvent(IMF_HIDE_IME, statsToken.getTag(),
                            Objects.toString(mService.mCurFocusedWindow),
                            InputMethodDebug.softInputDisplayReasonToString(reason),
                            InputMethodDebug.softInputModeToString(
                                    mService.mCurFocusedWindowSoftInputMode));
                }
                mService.onShowHideSoftInputRequested(false /* show */, windowToken, reason,
                        statsToken);
            }
        }
    }

    @GuardedBy("ImfLock.class")
    @Override
    public void applyImeVisibility(IBinder windowToken, @Nullable ImeTracker.Token statsToken,
            @ImeVisibilityStateComputer.VisibilityState int state) {
        applyImeVisibility(windowToken, statsToken, state, -1 /* ignore reason */);
    }

    @GuardedBy("ImfLock.class")
    void applyImeVisibility(IBinder windowToken, @Nullable ImeTracker.Token statsToken,
            @ImeVisibilityStateComputer.VisibilityState int state, int reason) {
        switch (state) {
            case STATE_SHOW_IME:
                ImeTracker.forLogging().onProgress(statsToken,
                        ImeTracker.PHASE_SERVER_APPLY_IME_VISIBILITY);
                // Send to window manager to show IME after IME layout finishes.
                mWindowManagerInternal.showImePostLayout(windowToken, statsToken);
                break;
            case STATE_HIDE_IME:
                if (mService.mCurFocusedWindowClient != null) {
                    ImeTracker.forLogging().onProgress(statsToken,
                            ImeTracker.PHASE_SERVER_APPLY_IME_VISIBILITY);
                    // IMMS only knows of focused window, not the actual IME target.
                    // e.g. it isn't aware of any window that has both
                    // NOT_FOCUSABLE, ALT_FOCUSABLE_IM flags set and can the IME target.
                    // Send it to window manager to hide IME from IME target window.
                    // TODO(b/139861270): send to mCurClient.client once IMMS is aware of
                    // actual IME target.
                    mWindowManagerInternal.hideIme(windowToken,
                            mService.mCurFocusedWindowClient.mSelfReportedDisplayId, statsToken);
                } else {
                    ImeTracker.forLogging().onFailed(statsToken,
                            ImeTracker.PHASE_SERVER_APPLY_IME_VISIBILITY);
                }
                break;
            case STATE_HIDE_IME_EXPLICIT:
                mService.hideCurrentInputLocked(windowToken, statsToken, 0, null, reason);
                break;
            case STATE_HIDE_IME_NOT_ALWAYS:
                mService.hideCurrentInputLocked(windowToken, statsToken,
                        InputMethodManager.HIDE_NOT_ALWAYS, null, reason);
                break;
            case STATE_SHOW_IME_IMPLICIT:
                mService.showCurrentInputLocked(windowToken, statsToken,
                        InputMethodManager.SHOW_IMPLICIT, null, reason);
                break;
            default:
                throw new IllegalArgumentException("Invalid IME visibility state: " + state);
        }
    }
}
