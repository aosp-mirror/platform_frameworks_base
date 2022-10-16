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
import static android.server.inputmethod.InputMethodManagerServiceProto.ACCESSIBILITY_REQUESTING_NO_SOFT_KEYBOARD;
import static android.server.inputmethod.InputMethodManagerServiceProto.SHOW_EXPLICITLY_REQUESTED;
import static android.server.inputmethod.InputMethodManagerServiceProto.SHOW_FORCED;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED;
import static android.view.WindowManager.LayoutParams.SoftInputModeFlags;

import static com.android.internal.inputmethod.InputMethodDebug.softInputModeToString;

import android.accessibilityservice.AccessibilityService;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.IBinder;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.WindowManager;
import android.view.inputmethod.ImeTracker;
import android.view.inputmethod.InputMethod;
import android.view.inputmethod.InputMethodManager;

import com.android.server.LocalServices;
import com.android.server.wm.WindowManagerInternal;

import java.io.PrintWriter;
import java.util.WeakHashMap;

/**
 * A computer used by {@link InputMethodManagerService} that computes the IME visibility state
 * according the given {@link ImeTargetWindowState} from the focused window or the app requested IME
 * visibility from {@link InputMethodManager}.
 */
public final class ImeVisibilityStateComputer {

    private static final String TAG = "ImeVisibilityStateComputer";

    private static final boolean DEBUG = InputMethodManagerService.DEBUG;

    private final InputMethodManagerService mService;
    private final WindowManagerInternal mWindowManagerInternal;

    /**
     * A map used to track the requested IME target window and its state. The key represents the
     * token of the window and the value is the corresponding IME window state.
     */
    private final WeakHashMap<IBinder, ImeTargetWindowState> mRequestWindowStateMap =
            new WeakHashMap<>();

    /**
     * Set if IME was explicitly told to show the input method.
     *
     * @see InputMethodManager#SHOW_IMPLICIT that we set the value is {@code false}.
     * @see InputMethodManager#HIDE_IMPLICIT_ONLY that system will not hide IME when the value is
     * {@code true}.
     */
    boolean mRequestedShowExplicitly;

    /**
     * Set if we were forced to be shown.
     *
     * @see InputMethodManager#SHOW_FORCED
     * @see InputMethodManager#HIDE_NOT_ALWAYS
     */
    boolean mShowForced;

    /** Represent the invalid IME visibility state */
    public static final int STATE_INVALID = -1;

    /** State to handle hiding the IME window requested by the app. */
    public static final int STATE_HIDE_IME = 0;

    /** State to handle showing the IME window requested by the app. */
    public static final int STATE_SHOW_IME = 1;

    /** State to handle showing the IME window with making the overlay window above it.  */
    public static final int STATE_SHOW_IME_ABOVE_OVERLAY = 2;

    /** State to handle showing the IME window with making the overlay window behind it.  */
    public static final int STATE_SHOW_IME_BEHIND_OVERLAY = 3;

    /** State to handle showing an IME preview surface during the app was loosing the IME focus */
    public static final int STATE_SHOW_IME_SNAPSHOT = 4;
    @IntDef({
            STATE_INVALID,
            STATE_HIDE_IME,
            STATE_SHOW_IME,
            STATE_SHOW_IME_ABOVE_OVERLAY,
            STATE_SHOW_IME_BEHIND_OVERLAY,
            STATE_SHOW_IME_SNAPSHOT,
    })
    @interface VisibilityState {}

    /**
     * The policy to configure the IME visibility.
     */
    private final ImeVisibilityPolicy mPolicy;

    public ImeVisibilityStateComputer(InputMethodManagerService service) {
        mService = service;
        mWindowManagerInternal = LocalServices.getService(WindowManagerInternal.class);
        mPolicy = new ImeVisibilityPolicy();
    }

    /**
     * Called when {@link InputMethodManagerService} is processing the show IME request.
     * @param statsToken The token for tracking this show request
     * @param showFlags The additional operation flags to indicate whether this show request mode is
     *                  implicit or explicit.
     * @return {@code true} when the computer has proceed this show request operation.
     */
    boolean onImeShowFlags(@NonNull ImeTracker.Token statsToken, int showFlags) {
        if (mPolicy.mA11yRequestingNoSoftKeyboard || mPolicy.mImeHiddenByDisplayPolicy) {
            ImeTracker.get().onFailed(statsToken, ImeTracker.PHASE_SERVER_ACCESSIBILITY);
            return false;
        }
        ImeTracker.get().onProgress(statsToken, ImeTracker.PHASE_SERVER_ACCESSIBILITY);
        if ((showFlags & InputMethodManager.SHOW_FORCED) != 0) {
            mRequestedShowExplicitly = true;
            mShowForced = true;
        } else if ((showFlags & InputMethodManager.SHOW_IMPLICIT) == 0) {
            mRequestedShowExplicitly = true;
        }
        return true;
    }

    /**
     * Called when {@link InputMethodManagerService} is processing the hide IME request.
     * @param statsToken The token for tracking this hide request
     * @param hideFlags The additional operation flags to indicate whether this hide request mode is
     *                  implicit or explicit.
     * @return {@code true} when the computer has proceed this hide request operations.
     */
    boolean canHideIme(@NonNull ImeTracker.Token statsToken, int hideFlags) {
        if ((hideFlags & InputMethodManager.HIDE_IMPLICIT_ONLY) != 0
                && (mRequestedShowExplicitly || mShowForced)) {
            if (DEBUG) Slog.v(TAG, "Not hiding: explicit show not cancelled by non-explicit hide");
            ImeTracker.get().onFailed(statsToken, ImeTracker.PHASE_SERVER_HIDE_IMPLICIT);
            return false;
        }
        if (mShowForced && (hideFlags & InputMethodManager.HIDE_NOT_ALWAYS) != 0) {
            if (DEBUG) Slog.v(TAG, "Not hiding: forced show not cancelled by not-always hide");
            ImeTracker.get().onFailed(statsToken, ImeTracker.PHASE_SERVER_HIDE_NOT_ALWAYS);
            return false;
        }
        ImeTracker.get().onProgress(statsToken, ImeTracker.PHASE_SERVER_HIDE_NOT_ALWAYS);
        return true;
    }

    int getImeShowFlags() {
        int flags = 0;
        if (mShowForced) {
            flags |= InputMethod.SHOW_FORCED | InputMethod.SHOW_EXPLICIT;
        } else if (mRequestedShowExplicitly) {
            flags |= InputMethod.SHOW_EXPLICIT;
        } else {
            flags |= InputMethodManager.SHOW_IMPLICIT;
        }
        return flags;
    }

    void clearImeShowFlags() {
        mRequestedShowExplicitly = false;
        mShowForced = false;
    }

    /**
     * Request to show/hide IME from the given window.
     *
     * @param windowToken The window which requests to show/hide IME.
     * @param showIme {@code true} means to show IME, {@code false} otherwise.
     *                            Note that in the computer will take this option to compute the
     *                            visibility state, it could be {@link #STATE_SHOW_IME} or
     *                            {@link #STATE_HIDE_IME}.
     */
    void requestImeVisibility(IBinder windowToken, boolean showIme) {
        final ImeTargetWindowState state = getOrCreateWindowState(windowToken);
        state.setRequestedImeVisible(showIme);
        setWindowState(windowToken, state);
    }

    ImeTargetWindowState getOrCreateWindowState(IBinder windowToken) {
        ImeTargetWindowState state = mRequestWindowStateMap.get(windowToken);
        if (state == null) {
            state = new ImeTargetWindowState(SOFT_INPUT_STATE_UNSPECIFIED, false, false);
        }
        return state;
    }

    ImeTargetWindowState getWindowStateOrNull(IBinder windowToken) {
        ImeTargetWindowState state = mRequestWindowStateMap.get(windowToken);
        return state;
    }

    void setRequestImeTokenToWindow(IBinder windowToken, IBinder token) {
        ImeTargetWindowState state = getWindowStateOrNull(windowToken);
        if (state != null) {
            state.setRequestImeToken(token);
            setWindowState(windowToken, state);
        }
    }

    void setWindowState(IBinder windowToken, ImeTargetWindowState newState) {
        if (DEBUG) Slog.d(TAG, "setWindowState, windowToken=" + windowToken
                + ", state=" + newState);
        mRequestWindowStateMap.put(windowToken, newState);
    }

    IBinder getWindowTokenFrom(IBinder requestImeToken) {
        for (IBinder windowToken : mRequestWindowStateMap.keySet()) {
            final ImeTargetWindowState state = mRequestWindowStateMap.get(windowToken);
            if (state.getRequestImeToken() == requestImeToken) {
                return windowToken;
            }
        }
        // Fallback to the focused window for some edge cases (e.g. relaunching the activity)
        return mService.mCurFocusedWindow;
    }

    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        proto.write(SHOW_EXPLICITLY_REQUESTED, mRequestedShowExplicitly);
        proto.write(SHOW_FORCED, mShowForced);
        proto.write(ACCESSIBILITY_REQUESTING_NO_SOFT_KEYBOARD,
                mPolicy.isA11yRequestNoSoftKeyboard());
    }

    void dump(PrintWriter pw) {
        final Printer p = new PrintWriterPrinter(pw);
        p.println(" mRequestedShowExplicitly=" + mRequestedShowExplicitly
                + " mShowForced=" + mShowForced);
        p.println("  mImeHiddenByDisplayPolicy=" + mPolicy.isImeHiddenByDisplayPolicy());
    }

    /**
     * A settings class to manage all IME related visibility policies or settings.
     *
     * This is used for the visibility computer to manage and tell
     * {@link InputMethodManagerService} if the requested IME visibility is valid from
     * application call or the focus window.
     */
    static class ImeVisibilityPolicy {
        /**
         * {@code true} if the Ime policy has been set to
         * {@link WindowManager#DISPLAY_IME_POLICY_HIDE}.
         *
         * This prevents the IME from showing when it otherwise may have shown.
         */
        private boolean mImeHiddenByDisplayPolicy;

        /**
         * Set when the accessibility service requests to hide IME by
         * {@link AccessibilityService.SoftKeyboardController#setShowMode}
         */
        private boolean mA11yRequestingNoSoftKeyboard;

        void setImeHiddenByDisplayPolicy(boolean hideIme) {
            mImeHiddenByDisplayPolicy = hideIme;
        }

        boolean isImeHiddenByDisplayPolicy() {
            return mImeHiddenByDisplayPolicy;
        }

        void setA11yRequestNoSoftKeyboard(int keyboardShowMode) {
            mA11yRequestingNoSoftKeyboard =
                    (keyboardShowMode & AccessibilityService.SHOW_MODE_MASK) == SHOW_MODE_HIDDEN;
        }

        boolean isA11yRequestNoSoftKeyboard() {
            return mA11yRequestingNoSoftKeyboard;
        }
    }

    ImeVisibilityPolicy getImePolicy() {
        return mPolicy;
    }

    /**
     * A class that represents the current state of the IME target window.
     */
    static class ImeTargetWindowState {
        ImeTargetWindowState(@SoftInputModeFlags int softInputModeState, boolean imeFocusChanged,
                boolean hasFocusedEditor) {
            mSoftInputModeState = softInputModeState;
            mImeFocusChanged = imeFocusChanged;
            mHasFocusedEditor = hasFocusedEditor;
        }

        /**
         * Visibility state for this window. By default no state has been specified.
         */
        private final @SoftInputModeFlags int mSoftInputModeState;

        /**
         * {@code true} means the IME focus changed from the previous window, {@code false}
         * otherwise.
         */
        private final boolean mImeFocusChanged;

        /**
         * {@code true} when the window has focused an editor, {@code false} otherwise.
         */
        private final boolean mHasFocusedEditor;

        /**
         * Set if the client has asked for the input method to be shown.
         */
        private boolean mRequestedImeVisible;

        /**
         * A identifier for knowing the requester of {@link InputMethodManager#showSoftInput} or
         * {@link InputMethodManager#hideSoftInputFromWindow}.
         */
        private IBinder mRequestImeToken;

        /**
         * The IME target display id for which the latest startInput was called.
         */
        private int mImeDisplayId = DEFAULT_DISPLAY;

        boolean hasImeFocusChanged() {
            return mImeFocusChanged;
        }

        boolean hasEdiorFocused() {
            return mHasFocusedEditor;
        }

        int getSoftInputModeState() {
            return mSoftInputModeState;
        }

        private void setImeDisplayId(int imeDisplayId) {
            mImeDisplayId = imeDisplayId;
        }

        int getImeDisplayId() {
            return mImeDisplayId;
        }

        private void setRequestedImeVisible(boolean requestedImeVisible) {
            mRequestedImeVisible = requestedImeVisible;
        }

        boolean isRequestedImeVisible() {
            return mRequestedImeVisible;
        }

        void setRequestImeToken(IBinder token) {
            mRequestImeToken = token;
        }

        IBinder getRequestImeToken() {
            return mRequestImeToken;
        }

        @Override
        public String toString() {
            return "ImeTargetWindowState{ imeToken " + mRequestImeToken
                    + " imeFocusChanged " + mImeFocusChanged
                    + " hasEditorFocused " + mHasFocusedEditor
                    + " requestedImeVisible " + mRequestedImeVisible
                    + " imeDisplayId " + mImeDisplayId
                    + " softInputModeState " + softInputModeToString(mSoftInputModeState)
                    + "}";
        }
    }
}
