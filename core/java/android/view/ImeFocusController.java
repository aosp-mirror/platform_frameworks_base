/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.view;

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.UiThread;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.inputmethod.InputMethodDebug;
import com.android.internal.inputmethod.StartInputFlags;
import com.android.internal.inputmethod.StartInputReason;

/**
 * Responsible for IME focus handling inside {@link ViewRootImpl}.
 * @hide
 */
public final class ImeFocusController {
    private static final boolean DEBUG = false;
    private static final String TAG = "ImeFocusController";

    private final ViewRootImpl mViewRootImpl;
    private boolean mHasImeFocus = false;
    private View mServedView;
    private View mNextServedView;
    private InputMethodManagerDelegate mDelegate;

    @UiThread
    ImeFocusController(@NonNull ViewRootImpl viewRootImpl) {
        mViewRootImpl = viewRootImpl;
    }

    @NonNull
    private InputMethodManagerDelegate getImmDelegate() {
        InputMethodManagerDelegate delegate = mDelegate;
        if (delegate != null) {
            return delegate;
        }
        delegate = mViewRootImpl.mContext.getSystemService(InputMethodManager.class).getDelegate();
        mDelegate = delegate;
        return delegate;
    }

    /** Called when the view root is moved to a different display. */
    @UiThread
    void onMovedToDisplay() {
        // InputMethodManager managed its instances for different displays. So if the associated
        // display is changed, the delegate also needs to be refreshed (by getImmDelegate).
        // See the comment in {@link android.app.SystemServiceRegistry} for InputMethodManager
        // and {@link android.view.inputmethod.InputMethodManager#forContext}.
        mDelegate = null;
    }

    @UiThread
    void onTraversal(boolean hasWindowFocus, WindowManager.LayoutParams windowAttribute) {
        final boolean hasImeFocus = updateImeFocusable(windowAttribute, false /* force */);
        if (!hasWindowFocus || isInLocalFocusMode(windowAttribute)) {
            return;
        }
        if (hasImeFocus == mHasImeFocus) {
            return;
        }
        mHasImeFocus = hasImeFocus;
        if (mHasImeFocus) {
            onPreWindowFocus(true /* hasWindowFocus */, windowAttribute);
            onPostWindowFocus(mViewRootImpl.mView.findFocus(), true /* hasWindowFocus */,
                    windowAttribute);
        }
    }

    @UiThread
    void onPreWindowFocus(boolean hasWindowFocus, WindowManager.LayoutParams windowAttribute) {
        if (!mHasImeFocus || isInLocalFocusMode(windowAttribute)) {
            return;
        }
        if (hasWindowFocus) {
            getImmDelegate().setCurrentRootView(mViewRootImpl);
        }
    }

    @UiThread
    boolean updateImeFocusable(WindowManager.LayoutParams windowAttribute, boolean force) {
        final boolean hasImeFocus = WindowManager.LayoutParams.mayUseInputMethod(
                windowAttribute.flags);
        if (force) {
            mHasImeFocus = hasImeFocus;
        }
        return hasImeFocus;
    }

    @UiThread
    void onPostWindowFocus(View focusedView, boolean hasWindowFocus,
            WindowManager.LayoutParams windowAttribute) {
        if (!hasWindowFocus || !mHasImeFocus || isInLocalFocusMode(windowAttribute)) {
            return;
        }
        if (DEBUG) {
            Log.v(TAG, "onWindowFocus: " + focusedView
                    + " softInputMode=" + InputMethodDebug.softInputModeToString(
                    windowAttribute.softInputMode));
        }

        boolean forceFocus = false;
        final InputMethodManagerDelegate immDelegate = getImmDelegate();
        if (immDelegate.isRestartOnNextWindowFocus(true /* reset */)) {
            if (DEBUG) Log.v(TAG, "Restarting due to isRestartOnNextWindowFocus as true");
            forceFocus = true;
        }
        // Update mNextServedView when focusedView changed.
        final View viewForWindowFocus = focusedView != null ? focusedView : mViewRootImpl.mView;
        onViewFocusChanged(viewForWindowFocus, true);

        immDelegate.startInputAsyncOnWindowFocusGain(viewForWindowFocus,
                windowAttribute.softInputMode, windowAttribute.flags, forceFocus);
    }

    public boolean checkFocus(boolean forceNewFocus, boolean startInput) {
        final InputMethodManagerDelegate immDelegate = getImmDelegate();
        if (!immDelegate.isCurrentRootView(mViewRootImpl)
                || (mServedView == mNextServedView && !forceNewFocus)) {
            return false;
        }
        if (DEBUG) Log.v(TAG, "checkFocus: view=" + mServedView
                + " next=" + mNextServedView
                + " force=" + forceNewFocus
                + " package="
                + (mServedView != null ? mServedView.getContext().getPackageName() : "<none>"));

        // Close the connection when no next served view coming.
        if (mNextServedView == null) {
            immDelegate.finishInput();
            immDelegate.closeCurrentIme();
            return false;
        }
        mServedView = mNextServedView;
        immDelegate.finishComposingText();

        if (startInput) {
            immDelegate.startInput(StartInputReason.CHECK_FOCUS, null /* focusedView */,
                    0 /* startInputFlags */, 0 /* softInputMode */, 0 /* windowFlags */);
        }
        return true;
    }

    @UiThread
    void onViewFocusChanged(View view, boolean hasFocus) {
        if (view == null || view.isTemporarilyDetached()) {
            return;
        }
        if (!getImmDelegate().isCurrentRootView(view.getViewRootImpl())) {
            return;
        }
        if (!view.hasImeFocus() || !view.hasWindowFocus()) {
            return;
        }
        if (DEBUG) Log.d(TAG, "onViewFocusChanged, view=" + view + ", mServedView=" + mServedView);

        // We don't need to track the next served view when the view lost focus here because:
        // 1) The current view focus may be cleared temporary when in touch mode, closing input
        //    at this moment isn't the right way.
        // 2) We only care about the served view change when it focused, since changing input
        //    connection when the focus target changed is reasonable.
        // 3) Setting the next served view as null when no more served view should be handled in
        //    other special events (e.g. view detached from window or the window dismissed).
        if (hasFocus) {
            mNextServedView = view;
        }
        mViewRootImpl.dispatchCheckFocus();
    }

    @UiThread
    void onViewDetachedFromWindow(View view) {
        if (!getImmDelegate().isCurrentRootView(view.getViewRootImpl())) {
            return;
        }
        if (mServedView == view) {
            mNextServedView = null;
            mViewRootImpl.dispatchCheckFocus();
        }
    }

    @UiThread
    void onWindowDismissed() {
        final InputMethodManagerDelegate immDelegate = getImmDelegate();
        if (!immDelegate.isCurrentRootView(mViewRootImpl)) {
            return;
        }
        if (mServedView != null) {
            immDelegate.finishInput();
        }
        immDelegate.setCurrentRootView(null);
        mHasImeFocus = false;
    }

    /**
     * Called by {@link ViewRootImpl} to feedback the state of the screen for this view.
     * @param newScreenState The new state of the screen. Can be either
     *                       {@link View#SCREEN_STATE_ON} or {@link View#SCREEN_STATE_OFF}
     */
    @UiThread
    void onScreenStateChanged(int newScreenState) {
        if (!getImmDelegate().isCurrentRootView(mViewRootImpl)) {
            return;
        }
        // Close input connection and IME when the screen is turn off for security concern.
        if (newScreenState == View.SCREEN_STATE_OFF && mServedView != null) {
            if (DEBUG) {
                Log.d(TAG, "onScreenStateChanged, disconnect input when screen turned off");
            }
            mNextServedView = null;
            mViewRootImpl.dispatchCheckFocus();
        }
    }

    /**
     * @param windowAttribute {@link WindowManager.LayoutParams} to be checked.
     * @return Whether the window is in local focus mode or not.
     */
    @AnyThread
    private static boolean isInLocalFocusMode(WindowManager.LayoutParams windowAttribute) {
        return (windowAttribute.flags & WindowManager.LayoutParams.FLAG_LOCAL_FOCUS_MODE) != 0;
    }

    int onProcessImeInputStage(Object token, InputEvent event,
            WindowManager.LayoutParams windowAttribute,
            InputMethodManager.FinishedInputEventCallback callback) {
        if (!mHasImeFocus || isInLocalFocusMode(windowAttribute)) {
            return InputMethodManager.DISPATCH_NOT_HANDLED;
        }
        final InputMethodManager imm =
                mViewRootImpl.mContext.getSystemService(InputMethodManager.class);
        if (imm == null) {
            return InputMethodManager.DISPATCH_NOT_HANDLED;
        }
        return imm.dispatchInputEvent(event, token, callback, mViewRootImpl.mHandler);
    }

    /**
     * A delegate implementing some basic {@link InputMethodManager} APIs.
     * @hide
     */
    public interface InputMethodManagerDelegate {
        boolean startInput(@StartInputReason int startInputReason, View focusedView,
                @StartInputFlags int startInputFlags,
                @WindowManager.LayoutParams.SoftInputModeFlags int softInputMode, int windowFlags);
        void startInputAsyncOnWindowFocusGain(View rootView,
                @WindowManager.LayoutParams.SoftInputModeFlags int softInputMode, int windowFlags,
                boolean forceNewFocus);
        void finishInput();
        void closeCurrentIme();
        void finishComposingText();
        void setCurrentRootView(ViewRootImpl rootView);
        boolean isCurrentRootView(ViewRootImpl rootView);
        boolean isRestartOnNextWindowFocus(boolean reset);
    }

    public View getServedView() {
        return mServedView;
    }

    public View getNextServedView() {
        return mNextServedView;
    }

    public void setServedView(View view) {
        mServedView = view;
    }

    public void setNextServedView(View view) {
        mNextServedView = view;
    }

    /**
     * Indicates whether the view's window has IME focused.
     */
    @UiThread
    boolean hasImeFocus() {
        return mHasImeFocus;
    }
}
