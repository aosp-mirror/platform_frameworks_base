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
import static android.server.inputmethod.InputMethodManagerServiceProto.INPUT_SHOWN;
import static android.server.inputmethod.InputMethodManagerServiceProto.SHOW_EXPLICITLY_REQUESTED;
import static android.server.inputmethod.InputMethodManagerServiceProto.SHOW_FORCED;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED;
import static android.view.WindowManager.LayoutParams.SoftInputModeFlags;

import static com.android.internal.inputmethod.InputMethodDebug.softInputModeToString;
import static com.android.server.inputmethod.InputMethodManagerService.computeImeDisplayIdForTarget;

import android.accessibilityservice.AccessibilityService;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.content.res.Configuration;
import android.os.IBinder;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.WindowManager;
import android.view.inputmethod.ImeTracker;
import android.view.inputmethod.InputMethod;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.inputmethod.SoftInputShowHideReason;
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

    final InputMethodManagerService.ImeDisplayValidator mImeDisplayValidator;

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

    /**
     * Set if we last told the input method to show itself.
     */
    private boolean mInputShown;

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

    public static final int STATE_HIDE_IME_EXPLICIT = 5;

    public static final int STATE_HIDE_IME_NOT_ALWAYS = 6;

    public static final int STATE_SHOW_IME_IMPLICIT = 7;
    @IntDef({
            STATE_INVALID,
            STATE_HIDE_IME,
            STATE_SHOW_IME,
            STATE_SHOW_IME_ABOVE_OVERLAY,
            STATE_SHOW_IME_BEHIND_OVERLAY,
            STATE_SHOW_IME_SNAPSHOT,
            STATE_HIDE_IME_EXPLICIT,
            STATE_HIDE_IME_NOT_ALWAYS,
            STATE_SHOW_IME_IMPLICIT,
    })
    @interface VisibilityState {}

    /**
     * The policy to configure the IME visibility.
     */
    private final ImeVisibilityPolicy mPolicy;

    public ImeVisibilityStateComputer(@NonNull InputMethodManagerService service) {
        this(service,
                LocalServices.getService(WindowManagerInternal.class),
                LocalServices.getService(WindowManagerInternal.class)::getDisplayImePolicy,
                new ImeVisibilityPolicy());
    }

    @VisibleForTesting
    public ImeVisibilityStateComputer(@NonNull InputMethodManagerService service,
            @NonNull Injector injector) {
        this(service, injector.getWmService(), injector.getImeValidator(),
                new ImeVisibilityPolicy());
    }

    interface Injector {
        default WindowManagerInternal getWmService() {
            return null;
        }

        default InputMethodManagerService.ImeDisplayValidator getImeValidator() {
            return null;
        }
    }

    private ImeVisibilityStateComputer(InputMethodManagerService service,
            WindowManagerInternal wmService,
            InputMethodManagerService.ImeDisplayValidator imeDisplayValidator,
            ImeVisibilityPolicy imePolicy) {
        mService = service;
        mWindowManagerInternal = wmService;
        mImeDisplayValidator = imeDisplayValidator;
        mPolicy = imePolicy;
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
            ImeTracker.forLogging().onFailed(statsToken, ImeTracker.PHASE_SERVER_ACCESSIBILITY);
            return false;
        }
        ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_SERVER_ACCESSIBILITY);
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
            ImeTracker.forLogging().onFailed(statsToken, ImeTracker.PHASE_SERVER_HIDE_IMPLICIT);
            return false;
        }
        if (mShowForced && (hideFlags & InputMethodManager.HIDE_NOT_ALWAYS) != 0) {
            if (DEBUG) Slog.v(TAG, "Not hiding: forced show not cancelled by not-always hide");
            ImeTracker.forLogging().onFailed(statsToken, ImeTracker.PHASE_SERVER_HIDE_NOT_ALWAYS);
            return false;
        }
        ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_SERVER_HIDE_NOT_ALWAYS);
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
        mInputShown = false;
    }

    int computeImeDisplayId(@NonNull ImeTargetWindowState state, int displayId) {
        final int displayToShowIme = computeImeDisplayIdForTarget(displayId, mImeDisplayValidator);
        state.setImeDisplayId(displayToShowIme);
        final boolean imeHiddenByPolicy = displayToShowIme == INVALID_DISPLAY;
        mPolicy.setImeHiddenByDisplayPolicy(imeHiddenByPolicy);
        return displayToShowIme;
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
        ImeTargetWindowState state = getOrCreateWindowState(windowToken);
        if (!mPolicy.mPendingA11yRequestingHideKeyboard) {
            state.setRequestedImeVisible(showIme);
        } else {
            // As A11y requests no IME is just a temporary, so we don't change the requested IME
            // visible in case the last visibility state goes wrong after leaving from the a11y
            // policy.
            mPolicy.mPendingA11yRequestingHideKeyboard = false;
        }
        setWindowStateInner(windowToken, state);
    }

    ImeTargetWindowState getOrCreateWindowState(IBinder windowToken) {
        ImeTargetWindowState state = mRequestWindowStateMap.get(windowToken);
        if (state == null) {
            state = new ImeTargetWindowState(SOFT_INPUT_STATE_UNSPECIFIED, 0, false, false, false);
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
            setWindowStateInner(windowToken, state);
        }
    }

    void setWindowState(IBinder windowToken, @NonNull ImeTargetWindowState newState) {
        final ImeTargetWindowState state = mRequestWindowStateMap.get(windowToken);
        if (state != null && newState.hasEdiorFocused()) {
            // Inherit the last requested IME visible state when the target window is still
            // focused with an editor.
            newState.setRequestedImeVisible(state.mRequestedImeVisible);
        }
        setWindowStateInner(windowToken, newState);
    }

    private void setWindowStateInner(IBinder windowToken, @NonNull ImeTargetWindowState newState) {
        if (DEBUG) Slog.d(TAG, "setWindowStateInner, windowToken=" + windowToken
                + ", state=" + newState);
        mRequestWindowStateMap.put(windowToken, newState);
    }

    static class ImeVisibilityResult {
        private final @VisibilityState int mState;
        private final @SoftInputShowHideReason int mReason;

        ImeVisibilityResult(@VisibilityState int state, @SoftInputShowHideReason int reason) {
            mState = state;
            mReason = reason;
        }

        @VisibilityState int getState() {
            return mState;
        }

        @SoftInputShowHideReason int getReason() {
            return mReason;
        }
    }

    ImeVisibilityResult computeState(ImeTargetWindowState state, boolean allowVisible) {
        // TODO: Output the request IME visibility state according to the requested window state
        final int softInputVisibility = state.mSoftInputModeState & SOFT_INPUT_MASK_STATE;
        // Should we auto-show the IME even if the caller has not
        // specified what should be done with it?
        // We only do this automatically if the window can resize
        // to accommodate the IME (so what the user sees will give
        // them good context without input information being obscured
        // by the IME) or if running on a large screen where there
        // is more room for the target window + IME.
        final boolean doAutoShow =
                (state.mSoftInputModeState & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
                        == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        || mService.mRes.getConfiguration().isLayoutSizeAtLeast(
                        Configuration.SCREENLAYOUT_SIZE_LARGE);
        final boolean isForwardNavigation = (state.mSoftInputModeState
                & WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION) != 0;

        // We shows the IME when the system allows the IME focused target window to restore the
        // IME visibility (e.g. switching to the app task when last time the IME is visible).
        // Note that we don't restore IME visibility for some cases (e.g. when the soft input
        // state is ALWAYS_HIDDEN or STATE_HIDDEN with forward navigation).
        // Because the app might leverage these flags to hide soft-keyboard with showing their own
        // UI for input.
        if (state.hasEdiorFocused() && shouldRestoreImeVisibility(state)) {
            if (DEBUG) Slog.v(TAG, "Will show input to restore visibility");
            // Inherit the last requested IME visible state when the target window is still
            // focused with an editor.
            state.setRequestedImeVisible(true);
            setWindowStateInner(getWindowTokenFrom(state), state);
            return new ImeVisibilityResult(STATE_SHOW_IME_IMPLICIT,
                    SoftInputShowHideReason.SHOW_RESTORE_IME_VISIBILITY);
        }

        switch (softInputVisibility) {
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED:
                if (state.hasImeFocusChanged() && (!state.hasEdiorFocused() || !doAutoShow)) {
                    if (WindowManager.LayoutParams.mayUseInputMethod(state.getWindowFlags())) {
                        // There is no focus view, and this window will
                        // be behind any soft input window, so hide the
                        // soft input window if it is shown.
                        if (DEBUG) Slog.v(TAG, "Unspecified window will hide input");
                        return new ImeVisibilityResult(STATE_HIDE_IME_NOT_ALWAYS,
                                SoftInputShowHideReason.HIDE_UNSPECIFIED_WINDOW);
                    }
                } else if (state.hasEdiorFocused() && doAutoShow && isForwardNavigation) {
                    // There is a focus view, and we are navigating forward
                    // into the window, so show the input window for the user.
                    // We only do this automatically if the window can resize
                    // to accommodate the IME (so what the user sees will give
                    // them good context without input information being obscured
                    // by the IME) or if running on a large screen where there
                    // is more room for the target window + IME.
                    if (DEBUG) Slog.v(TAG, "Unspecified window will show input");
                    return new ImeVisibilityResult(STATE_SHOW_IME_IMPLICIT,
                            SoftInputShowHideReason.SHOW_AUTO_EDITOR_FORWARD_NAV);
                }
                break;
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED:
                // Do nothing but preserving the last IME requested visibility state.
                final ImeTargetWindowState lastState =
                        getWindowStateOrNull(mService.mLastImeTargetWindow);
                if (lastState != null) {
                    state.setRequestedImeVisible(lastState.mRequestedImeVisible);
                }
                break;
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN:
                if (isForwardNavigation) {
                    if (DEBUG) Slog.v(TAG, "Window asks to hide input going forward");
                    return new ImeVisibilityResult(STATE_HIDE_IME_EXPLICIT,
                            SoftInputShowHideReason.HIDE_STATE_HIDDEN_FORWARD_NAV);
                }
                break;
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN:
                if (state.hasImeFocusChanged()) {
                    if (DEBUG) Slog.v(TAG, "Window asks to hide input");
                    return new ImeVisibilityResult(STATE_HIDE_IME_EXPLICIT,
                            SoftInputShowHideReason.HIDE_ALWAYS_HIDDEN_STATE);
                }
                break;
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE:
                if (isForwardNavigation) {
                    if (allowVisible) {
                        if (DEBUG) Slog.v(TAG, "Window asks to show input going forward");
                        return new ImeVisibilityResult(STATE_SHOW_IME_IMPLICIT,
                                SoftInputShowHideReason.SHOW_STATE_VISIBLE_FORWARD_NAV);
                    } else {
                        Slog.e(TAG, "SOFT_INPUT_STATE_VISIBLE is ignored because"
                                + " there is no focused view that also returns true from"
                                + " View#onCheckIsTextEditor()");
                    }
                }
                break;
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE:
                if (DEBUG) Slog.v(TAG, "Window asks to always show input");
                if (allowVisible) {
                    if (state.hasImeFocusChanged()) {
                        return new ImeVisibilityResult(STATE_SHOW_IME_IMPLICIT,
                                SoftInputShowHideReason.SHOW_STATE_ALWAYS_VISIBLE);
                    }
                } else {
                    Slog.e(TAG, "SOFT_INPUT_STATE_ALWAYS_VISIBLE is ignored because"
                            + " there is no focused view that also returns true from"
                            + " View#onCheckIsTextEditor()");
                }
                break;
        }

        if (!state.hasImeFocusChanged()) {
            // On previous platforms, when Dialogs re-gained focus, the Activity behind
            // would briefly gain focus first, and dismiss the IME.
            // On R that behavior has been fixed, but unfortunately apps have come
            // to rely on this behavior to hide the IME when the editor no longer has focus
            // To maintain compatibility, we are now hiding the IME when we don't have
            // an editor upon refocusing a window.
            if (state.isStartInputByGainFocus()) {
                if (DEBUG) Slog.v(TAG, "Same window without editor will hide input");
                return new ImeVisibilityResult(STATE_HIDE_IME_EXPLICIT,
                        SoftInputShowHideReason.HIDE_SAME_WINDOW_FOCUSED_WITHOUT_EDITOR);
            }
        }
        if (!state.hasEdiorFocused() && mInputShown && state.isStartInputByGainFocus()
                && mService.mInputMethodDeviceConfigs.shouldHideImeWhenNoEditorFocus()) {
            // Hide the soft-keyboard when the system do nothing for softInputModeState
            // of the window being gained focus without an editor. This behavior benefits
            // to resolve some unexpected IME visible cases while that window with following
            // configurations being switched from an IME shown window:
            // 1) SOFT_INPUT_STATE_UNCHANGED state without an editor
            // 2) SOFT_INPUT_STATE_VISIBLE state without an editor
            // 3) SOFT_INPUT_STATE_ALWAYS_VISIBLE state without an editor
            if (DEBUG) Slog.v(TAG, "Window without editor will hide input");
            return new ImeVisibilityResult(STATE_HIDE_IME_EXPLICIT,
                    SoftInputShowHideReason.HIDE_WINDOW_GAINED_FOCUS_WITHOUT_EDITOR);
        }
        return null;
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

    IBinder getWindowTokenFrom(ImeTargetWindowState windowState) {
        for (IBinder windowToken : mRequestWindowStateMap.keySet()) {
            final ImeTargetWindowState state = mRequestWindowStateMap.get(windowToken);
            if (state == windowState) {
                return windowToken;
            }
        }
        return null;
    }

    boolean shouldRestoreImeVisibility(@NonNull ImeTargetWindowState state) {
        final int softInputMode = state.getSoftInputModeState();
        switch (softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE) {
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN:
                return false;
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN:
                if ((softInputMode & SOFT_INPUT_IS_FORWARD_NAVIGATION) != 0) {
                    return false;
                }
        }
        return mWindowManagerInternal.shouldRestoreImeVisibility(getWindowTokenFrom(state));
    }

    boolean isInputShown() {
        return mInputShown;
    }

    void setInputShown(boolean inputShown) {
        mInputShown = inputShown;
    }

    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        proto.write(SHOW_EXPLICITLY_REQUESTED, mRequestedShowExplicitly);
        proto.write(SHOW_FORCED, mShowForced);
        proto.write(ACCESSIBILITY_REQUESTING_NO_SOFT_KEYBOARD,
                mPolicy.isA11yRequestNoSoftKeyboard());
        proto.write(INPUT_SHOWN, mInputShown);
    }

    void dump(PrintWriter pw) {
        final Printer p = new PrintWriterPrinter(pw);
        p.println(" mRequestedShowExplicitly=" + mRequestedShowExplicitly
                + " mShowForced=" + mShowForced);
        p.println("  mImeHiddenByDisplayPolicy=" + mPolicy.isImeHiddenByDisplayPolicy());
        p.println("  mInputShown=" + mInputShown);
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

        /**
         * Used when A11y request to hide IME temporary when receiving
         * {@link AccessibilityService#SHOW_MODE_HIDDEN} from
         * {@link android.provider.Settings.Secure#ACCESSIBILITY_SOFT_KEYBOARD_MODE} without
         * changing the requested IME visible state.
         */
        private boolean mPendingA11yRequestingHideKeyboard;

        void setImeHiddenByDisplayPolicy(boolean hideIme) {
            mImeHiddenByDisplayPolicy = hideIme;
        }

        boolean isImeHiddenByDisplayPolicy() {
            return mImeHiddenByDisplayPolicy;
        }

        void setA11yRequestNoSoftKeyboard(int keyboardShowMode) {
            mA11yRequestingNoSoftKeyboard =
                    (keyboardShowMode & AccessibilityService.SHOW_MODE_MASK) == SHOW_MODE_HIDDEN;
            if (mA11yRequestingNoSoftKeyboard) {
                mPendingA11yRequestingHideKeyboard = true;
            }
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
        ImeTargetWindowState(@SoftInputModeFlags int softInputModeState, int windowFlags,
                boolean imeFocusChanged, boolean hasFocusedEditor,
                boolean isStartInputByGainFocus) {
            mSoftInputModeState = softInputModeState;
            mWindowFlags = windowFlags;
            mImeFocusChanged = imeFocusChanged;
            mHasFocusedEditor = hasFocusedEditor;
            mIsStartInputByGainFocus = isStartInputByGainFocus;
        }

        /**
         * Visibility state for this window. By default no state has been specified.
         */
        private final @SoftInputModeFlags int mSoftInputModeState;

        private final int mWindowFlags;

        /**
         * {@code true} means the IME focus changed from the previous window, {@code false}
         * otherwise.
         */
        private final boolean mImeFocusChanged;

        /**
         * {@code true} when the window has focused an editor, {@code false} otherwise.
         */
        private final boolean mHasFocusedEditor;

        private final boolean mIsStartInputByGainFocus;

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

        boolean isStartInputByGainFocus() {
            return mIsStartInputByGainFocus;
        }

        int getSoftInputModeState() {
            return mSoftInputModeState;
        }

        int getWindowFlags() {
            return mWindowFlags;
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
                    + " isStartInputByGainFocus " + mIsStartInputByGainFocus
                    + "}";
        }
    }
}
