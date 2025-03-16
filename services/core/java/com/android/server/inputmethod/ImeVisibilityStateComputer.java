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
import static android.view.MotionEvent.TOOL_TYPE_UNKNOWN;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED;
import static android.view.WindowManager.LayoutParams.SoftInputModeFlags;

import static com.android.internal.inputmethod.InputMethodDebug.softInputModeToString;
import static com.android.internal.inputmethod.SoftInputShowHideReason.REMOVE_IME_SCREENSHOT_FROM_IMMS;
import static com.android.internal.inputmethod.SoftInputShowHideReason.SHOW_IME_SCREENSHOT_FROM_IMMS;
import static com.android.server.inputmethod.InputMethodManagerService.computeImeDisplayIdForTarget;

import android.accessibilityservice.AccessibilityService;
import android.annotation.AnyThread;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.IBinder;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.inputmethod.Flags;
import android.view.inputmethod.ImeTracker;
import android.view.inputmethod.InputMethod;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.annotations.GuardedBy;
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

    @UserIdInt
    private final int mUserId;

    private final InputMethodManagerService mService;
    private final WindowManagerInternal mWindowManagerInternal;

    final InputMethodManagerService.ImeDisplayValidator mImeDisplayValidator;

    /**
     * A map used to track the requested IME target window and its state. The key represents the
     * token of the window and the value is the corresponding IME window state.
     */
    @GuardedBy("ImfLock.class")
    private final WeakHashMap<IBinder, ImeTargetWindowState> mRequestWindowStateMap =
            new WeakHashMap<>();

    /**
     * Set if IME was explicitly told to show the input method.
     *
     * @see InputMethodManager#SHOW_IMPLICIT that we set the value is {@code false}.
     * @see InputMethodManager#HIDE_IMPLICIT_ONLY that system will not hide IME when the value is
     * {@code true}.
     */
    @GuardedBy("ImfLock.class")
    boolean mRequestedShowExplicitly;

    /**
     * Set if we were forced to be shown.
     *
     * @see InputMethodManager#SHOW_FORCED
     * @see InputMethodManager#HIDE_NOT_ALWAYS
     */
    @GuardedBy("ImfLock.class")
    boolean mShowForced;

    /**
     * Set if we last told the input method to show itself.
     */
    @GuardedBy("ImfLock.class")
    private boolean mInputShown;

    /**
     * Set if we called
     * {@link com.android.server.wm.ImeTargetVisibilityPolicy#showImeScreenshot(IBinder, int)}.
     */
    @GuardedBy("ImfLock.class")
    private boolean mRequestedImeScreenshot;

    /** Whether there is a visible IME layering target overlay. */
    @GuardedBy("ImfLock.class")
    private boolean mHasVisibleImeLayeringOverlay;

    /** The window token of the current visible IME input target. */
    @GuardedBy("ImfLock.class")
    private IBinder mCurVisibleImeInputTarget;

    /**
     * The last window token that we confirmed that IME started talking to.  This is always updated
     * upon reports from the input method.  If the window state is already changed before the report
     * is handled, this field just keeps the last value.
     */
    @GuardedBy("ImfLock.class")
    @Nullable
    private IBinder mLastImeTargetWindow;

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

    /** State to handle removing an IME preview surface when necessary. */
    public static final int STATE_REMOVE_IME_SNAPSHOT = 8;

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
            STATE_REMOVE_IME_SNAPSHOT,
    })
    @interface VisibilityState {}

    /**
     * The policy to configure the IME visibility.
     */
    private final ImeVisibilityPolicy mPolicy;

    public ImeVisibilityStateComputer(@NonNull InputMethodManagerService service,
            @UserIdInt int userId) {
        this(service,
                LocalServices.getService(WindowManagerInternal.class),
                LocalServices.getService(WindowManagerInternal.class)::getDisplayImePolicy,
                new ImeVisibilityPolicy(), userId);
    }

    @VisibleForTesting
    public ImeVisibilityStateComputer(@NonNull InputMethodManagerService service,
            @NonNull Injector injector) {
        this(service, injector.getWmService(), injector.getImeValidator(),
                new ImeVisibilityPolicy(), injector.getUserId());
    }

    interface Injector {
        @NonNull
        WindowManagerInternal getWmService();

        @NonNull
        InputMethodManagerService.ImeDisplayValidator getImeValidator();

        @UserIdInt
        int getUserId();
    }

    private ImeVisibilityStateComputer(InputMethodManagerService service,
            WindowManagerInternal wmService,
            InputMethodManagerService.ImeDisplayValidator imeDisplayValidator,
            ImeVisibilityPolicy imePolicy, @UserIdInt int userId) {
        mUserId = userId;
        mService = service;
        mWindowManagerInternal = wmService;
        mImeDisplayValidator = imeDisplayValidator;
        mPolicy = imePolicy;
    }

    @GuardedBy("ImfLock.class")
    void setHasVisibleImeLayeringOverlay(boolean hasVisibleOverlay) {
        mHasVisibleImeLayeringOverlay = hasVisibleOverlay;
    }

    @GuardedBy("ImfLock.class")
    void onImeInputTargetVisibilityChanged(@NonNull IBinder imeInputTarget,
            boolean visibleAndNotRemoved) {
        if (visibleAndNotRemoved) {
            mCurVisibleImeInputTarget = imeInputTarget;
            return;
        }
        if (mHasVisibleImeLayeringOverlay
                && mCurVisibleImeInputTarget == imeInputTarget) {
            final int reason = SoftInputShowHideReason.HIDE_WHEN_INPUT_TARGET_INVISIBLE;
            final var statsToken = ImeTracker.forLogging().onStart(ImeTracker.TYPE_HIDE,
                    ImeTracker.ORIGIN_SERVER, reason, false /* fromUser */);
            mService.onApplyImeVisibilityFromComputerLocked(imeInputTarget, statsToken,
                    new ImeVisibilityResult(STATE_HIDE_IME_EXPLICIT, reason), mUserId);
        }
        mCurVisibleImeInputTarget = null;
    }

    /**
     * Called when {@link InputMethodManagerService} is processing the show IME request.
     *
     * @param statsToken The token tracking the current IME request.
     * @return {@code true} when the show request can proceed.
     */
    @GuardedBy("ImfLock.class")
    boolean onImeShowFlags(@NonNull ImeTracker.Token statsToken,
            @InputMethodManager.ShowFlags int showFlags) {
        if (mPolicy.mA11yRequestingNoSoftKeyboard || mPolicy.mImeHiddenByDisplayPolicy) {
            ImeTracker.forLogging().onFailed(statsToken, ImeTracker.PHASE_SERVER_ACCESSIBILITY);
            return false;
        }
        ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_SERVER_ACCESSIBILITY);
        // We only "set" the state corresponding to the flags, as this will be reset
        // in clearImeShowFlags during a hide request.
        // Thus, we keep the strongest values set (e.g. an implicit show right after
        // an explicit show will still be considered explicit, likewise for forced).
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
     *
     * @param statsToken The token tracking the current IME request.
     * @return {@code true} when the hide request can proceed.
     */
    @GuardedBy("ImfLock.class")
    boolean canHideIme(@NonNull ImeTracker.Token statsToken,
            @InputMethodManager.HideFlags int hideFlags) {
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

    /**
     * Returns the show flags for IME. This translates from {@link InputMethodManager.ShowFlags}
     * to {@link InputMethod.ShowFlags}.
     */
    @GuardedBy("ImfLock.class")
    @InputMethod.ShowFlags
    int getShowFlagsForInputMethodServiceOnly() {
        int flags = 0;
        if (mShowForced) {
            flags |= InputMethod.SHOW_FORCED | InputMethod.SHOW_EXPLICIT;
        } else if (mRequestedShowExplicitly) {
            flags |= InputMethod.SHOW_EXPLICIT;
        }
        return flags;
    }

    /**
     * Returns the show flags for IMM. This translates from {@link InputMethod.ShowFlags}
     * to {@link InputMethodManager.ShowFlags}.
     */
    @GuardedBy("ImfLock.class")
    @InputMethodManager.ShowFlags
    int getShowFlags() {
        int flags = 0;
        if (mShowForced) {
            flags |= InputMethodManager.SHOW_FORCED;
        } else if (!mRequestedShowExplicitly) {
            flags |= InputMethodManager.SHOW_IMPLICIT;
        }
        return flags;
    }

    @GuardedBy("ImfLock.class")
    void clearImeShowFlags() {
        mRequestedShowExplicitly = false;
        mShowForced = false;
        mInputShown = false;
    }

    @GuardedBy("ImfLock.class")
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
    @GuardedBy("ImfLock.class")
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
        // create a placeholder token for IMS so that IMS cannot inject windows into client app.
        state.setRequestImeToken(new Binder());
        setWindowStateInner(windowToken, state);
    }

    @GuardedBy("ImfLock.class")
    ImeTargetWindowState getOrCreateWindowState(IBinder windowToken) {
        ImeTargetWindowState state = mRequestWindowStateMap.get(windowToken);
        if (state == null) {
            state = new ImeTargetWindowState(SOFT_INPUT_STATE_UNSPECIFIED, 0, false, false, false);
        }
        return state;
    }

    @GuardedBy("ImfLock.class")
    ImeTargetWindowState getWindowStateOrNull(IBinder windowToken) {
        ImeTargetWindowState state = mRequestWindowStateMap.get(windowToken);
        return state;
    }

    @GuardedBy("ImfLock.class")
    void setWindowState(IBinder windowToken, @NonNull ImeTargetWindowState newState) {
        final ImeTargetWindowState state = mRequestWindowStateMap.get(windowToken);
        if (state != null && newState.hasEditorFocused() && (
                newState.mToolType != MotionEvent.TOOL_TYPE_STYLUS
                        || Flags.refactorInsetsController())) {
            // Inherit the last requested IME visible state when the target window is still
            // focused with an editor.
            newState.setRequestedImeVisible(state.mRequestedImeVisible);
        }
        setWindowStateInner(windowToken, newState);
    }

    @GuardedBy("ImfLock.class")
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

    @GuardedBy("ImfLock.class")
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
        if (state.hasEditorFocused() && shouldRestoreImeVisibility(state)) {
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
                if (state.hasImeFocusChanged() && (!state.hasEditorFocused() || (!doAutoShow
                        && !Flags.refactorInsetsController()))) {
                    if (WindowManager.LayoutParams.mayUseInputMethod(state.getWindowFlags())) {
                        // There is no focus view, and this window will
                        // be behind any soft input window, so hide the
                        // soft input window if it is shown.
                        if (DEBUG) Slog.v(TAG, "Unspecified window will hide input");
                        return new ImeVisibilityResult(STATE_HIDE_IME_NOT_ALWAYS,
                                SoftInputShowHideReason.HIDE_UNSPECIFIED_WINDOW);
                    }
                } else if (state.hasEditorFocused() && doAutoShow && isForwardNavigation) {
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
                final ImeTargetWindowState lastState = getWindowStateOrNull(mLastImeTargetWindow);
                if (lastState != null) {
                    state.setRequestedImeVisible(lastState.mRequestedImeVisible);
                }
                break;
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN:
                if (Flags.refactorInsetsController()) {
                    // In this case, we don't have to manipulate the requested visible types of
                    // the WindowState, as they're already in the correct state
                    break;
                } else if (isForwardNavigation) {
                    if (DEBUG) Slog.v(TAG, "Window asks to hide input going forward");
                    return new ImeVisibilityResult(STATE_HIDE_IME_EXPLICIT,
                            SoftInputShowHideReason.HIDE_STATE_HIDDEN_FORWARD_NAV);
                }
                break;
            case WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN:
                if (Flags.refactorInsetsController()) {
                    // In this case, we don't have to manipulate the requested visible types of
                    // the WindowState, as they're already in the correct state
                    break;
                } else if (state.hasImeFocusChanged()) {
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
        if (!state.hasEditorFocused() && mInputShown && state.isStartInputByGainFocus()
                && mService.mInputMethodDeviceConfigs.shouldHideImeWhenNoEditorFocus()) {
            // Hide the soft-keyboard when the system do nothing for softInputModeState
            // of the window being gained focus without an editor. This behavior benefits
            // to resolve some unexpected IME visible cases while that window with following
            // configurations being switched from an IME shown window:
            // 1) SOFT_INPUT_STATE_UNCHANGED state without an editor
            // 2) SOFT_INPUT_STATE_VISIBLE state without an editor
            // 3) SOFT_INPUT_STATE_ALWAYS_VISIBLE state without an editor
            if (DEBUG) Slog.v(TAG, "Window without editor will hide input");
            if (Flags.refactorInsetsController()) {
                state.setRequestedImeVisible(false);
            }
            return new ImeVisibilityResult(STATE_HIDE_IME_EXPLICIT,
                    SoftInputShowHideReason.HIDE_WINDOW_GAINED_FOCUS_WITHOUT_EDITOR);
        }
        return null;
    }

    @GuardedBy("ImfLock.class")
    ImeVisibilityResult onInteractiveChanged(IBinder windowToken, boolean interactive) {
        final ImeTargetWindowState state = getWindowStateOrNull(windowToken);
        if (state != null && state.isRequestedImeVisible() && mInputShown && !interactive) {
            mRequestedImeScreenshot = true;
            return new ImeVisibilityResult(STATE_SHOW_IME_SNAPSHOT, SHOW_IME_SCREENSHOT_FROM_IMMS);
        }
        if (interactive && mRequestedImeScreenshot) {
            mRequestedImeScreenshot = false;
            return new ImeVisibilityResult(STATE_REMOVE_IME_SNAPSHOT,
                    REMOVE_IME_SCREENSHOT_FROM_IMMS);
        }
        return null;
    }

    @GuardedBy("ImfLock.class")
    IBinder getWindowTokenFrom(IBinder requestImeToken, @UserIdInt int userId) {
        for (IBinder windowToken : mRequestWindowStateMap.keySet()) {
            final ImeTargetWindowState state = mRequestWindowStateMap.get(windowToken);
            if (state.getRequestImeToken() == requestImeToken) {
                return windowToken;
            }
        }
        final var userData = mService.getUserData(userId);
        // Fallback to the focused window for some edge cases (e.g. relaunching the activity)
        return userData.mImeBindingState.mFocusedWindow;
    }

    @GuardedBy("ImfLock.class")
    IBinder getWindowTokenFrom(ImeTargetWindowState windowState) {
        for (IBinder windowToken : mRequestWindowStateMap.keySet()) {
            final ImeTargetWindowState state = mRequestWindowStateMap.get(windowToken);
            if (state == windowState) {
                return windowToken;
            }
        }
        return null;
    }

    @GuardedBy("ImfLock.class")
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

    @UserIdInt
    @VisibleForTesting
    int getUserId() {
        return mUserId;
    }

    @GuardedBy("ImfLock.class")
    boolean isInputShown() {
        return mInputShown;
    }

    @GuardedBy("ImfLock.class")
    void setInputShown(boolean inputShown) {
        mInputShown = inputShown;
    }

    @GuardedBy("ImfLock.class")
    @Nullable
    IBinder getLastImeTargetWindow() {
        return mLastImeTargetWindow;
    }

    @GuardedBy("ImfLock.class")
    void setLastImeTargetWindow(@Nullable IBinder imeTargetWindow) {
        mLastImeTargetWindow = imeTargetWindow;
    }

    @GuardedBy("ImfLock.class")
    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        proto.write(SHOW_EXPLICITLY_REQUESTED, mRequestedShowExplicitly);
        proto.write(SHOW_FORCED, mShowForced);
        proto.write(ACCESSIBILITY_REQUESTING_NO_SOFT_KEYBOARD,
                mPolicy.isA11yRequestNoSoftKeyboard());
        proto.write(INPUT_SHOWN, mInputShown);
    }

    @GuardedBy("ImfLock.class")
    void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
        final Printer p = new PrintWriterPrinter(pw);
        p.println(prefix + "mRequestedShowExplicitly=" + mRequestedShowExplicitly
                + " mShowForced=" + mShowForced);
        p.println(prefix + "mImeHiddenByDisplayPolicy=" + mPolicy.isImeHiddenByDisplayPolicy());
        p.println(prefix + "mInputShown=" + mInputShown);
        p.println(prefix + "mLastImeTargetWindow=" + mLastImeTargetWindow);
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
        @GuardedBy("ImfLock.class")
        private boolean mImeHiddenByDisplayPolicy;

        /**
         * Set when the accessibility service requests to hide IME by
         * {@link AccessibilityService.SoftKeyboardController#setShowMode}
         */
        @GuardedBy("ImfLock.class")
        private boolean mA11yRequestingNoSoftKeyboard;

        /**
         * Used when A11y request to hide IME temporary when receiving
         * {@link AccessibilityService#SHOW_MODE_HIDDEN} from
         * {@link android.provider.Settings.Secure#ACCESSIBILITY_SOFT_KEYBOARD_MODE} without
         * changing the requested IME visible state.
         */
        @GuardedBy("ImfLock.class")
        private boolean mPendingA11yRequestingHideKeyboard;

        @GuardedBy("ImfLock.class")
        void setImeHiddenByDisplayPolicy(boolean hideIme) {
            mImeHiddenByDisplayPolicy = hideIme;
        }

        @GuardedBy("ImfLock.class")
        boolean isImeHiddenByDisplayPolicy() {
            return mImeHiddenByDisplayPolicy;
        }

        @GuardedBy("ImfLock.class")
        void setA11yRequestNoSoftKeyboard(int keyboardShowMode) {
            mA11yRequestingNoSoftKeyboard =
                    (keyboardShowMode & AccessibilityService.SHOW_MODE_MASK) == SHOW_MODE_HIDDEN;
            if (mA11yRequestingNoSoftKeyboard) {
                mPendingA11yRequestingHideKeyboard = true;
            }
        }

        @GuardedBy("ImfLock.class")
        boolean isA11yRequestNoSoftKeyboard() {
            return mA11yRequestingNoSoftKeyboard;
        }
    }

    @GuardedBy("ImfLock.class")
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
            this(softInputModeState, windowFlags, imeFocusChanged, hasFocusedEditor,
                    isStartInputByGainFocus, TOOL_TYPE_UNKNOWN);
        }

        ImeTargetWindowState(@SoftInputModeFlags int softInputModeState, int windowFlags,
                boolean imeFocusChanged, boolean hasFocusedEditor,
                boolean isStartInputByGainFocus, @MotionEvent.ToolType int toolType) {
            mSoftInputModeState = softInputModeState;
            mWindowFlags = windowFlags;
            mImeFocusChanged = imeFocusChanged;
            mHasFocusedEditor = hasFocusedEditor;
            mIsStartInputByGainFocus = isStartInputByGainFocus;
            mToolType = toolType;
        }

        /**
         * Visibility state for this window. By default no state has been specified.
         */
        private final @SoftInputModeFlags int mSoftInputModeState;

        private final int mWindowFlags;

        /**
         * {@link MotionEvent#getToolType(int)} that was used to click editor.
         */
        private final int mToolType;

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
        @GuardedBy("ImfLock.class")
        private boolean mRequestedImeVisible;

        /**
         * A identifier for knowing the requester of {@link InputMethodManager#showSoftInput} or
         * {@link InputMethodManager#hideSoftInputFromWindow}.
         */
        @GuardedBy("ImfLock.class")
        private IBinder mRequestImeToken;

        /**
         * The IME target display id for which the latest startInput was called.
         */
        @GuardedBy("ImfLock.class")
        private int mImeDisplayId = DEFAULT_DISPLAY;

        @AnyThread
        boolean hasImeFocusChanged() {
            return mImeFocusChanged;
        }

        @AnyThread
        boolean hasEditorFocused() {
            return mHasFocusedEditor;
        }

        @AnyThread
        boolean isStartInputByGainFocus() {
            return mIsStartInputByGainFocus;
        }

        @AnyThread
        int getSoftInputModeState() {
            return mSoftInputModeState;
        }

        @AnyThread
        int getWindowFlags() {
            return mWindowFlags;
        }

        @AnyThread
        int getToolType() {
            return mToolType;
        }

        @GuardedBy("ImfLock.class")
        private void setImeDisplayId(int imeDisplayId) {
            mImeDisplayId = imeDisplayId;
        }

        @GuardedBy("ImfLock.class")
        int getImeDisplayId() {
            return mImeDisplayId;
        }

        @GuardedBy("ImfLock.class")
        private void setRequestedImeVisible(boolean requestedImeVisible) {
            mRequestedImeVisible = requestedImeVisible;
        }

        @GuardedBy("ImfLock.class")
        boolean isRequestedImeVisible() {
            return mRequestedImeVisible;
        }

        @GuardedBy("ImfLock.class")
        void setRequestImeToken(IBinder token) {
            mRequestImeToken = token;
        }

        @GuardedBy("ImfLock.class")
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
