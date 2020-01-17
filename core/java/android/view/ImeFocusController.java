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

    @UiThread
    ImeFocusController(@NonNull ViewRootImpl viewRootImpl) {
        mViewRootImpl = viewRootImpl;
    }

    private InputMethodManagerDelegate getImmDelegate() {
        return mViewRootImpl.mContext.getSystemService(InputMethodManager.class).getDelegate();
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
        if (getImmDelegate().isRestartOnNextWindowFocus(true /* reset */)) {
            if (DEBUG) Log.v(TAG, "Restarting due to isRestartOnNextWindowFocus as true");
            forceFocus = true;
        }
        // Update mNextServedView when focusedView changed.
        final View viewForWindowFocus = focusedView != null ? focusedView : mViewRootImpl.mView;
        onViewFocusChanged(viewForWindowFocus, true);

        getImmDelegate().startInputAsyncOnWindowFocusGain(viewForWindowFocus,
                windowAttribute.softInputMode, windowAttribute.flags, forceFocus);
    }

    public boolean checkFocus(boolean forceNewFocus, boolean startInput) {
        if (!getImmDelegate().isCurrentRootView(mViewRootImpl)
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
            getImmDelegate().finishInput();
            getImmDelegate().closeCurrentIme();
            return false;
        }
        mServedView = mNextServedView;
        getImmDelegate().finishComposingText();

        if (startInput) {
            getImmDelegate().startInput(StartInputReason.CHECK_FOCUS, null, 0, 0, 0);
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
        if (mServedView == view || !view.hasImeFocus() || !view.hasWindowFocus()) {
            return;
        }
        mNextServedView = hasFocus ? view : null;
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
        if (!getImmDelegate().isCurrentRootView(mViewRootImpl)) {
            return;
        }
        if (mServedView != null) {
            getImmDelegate().finishInput();
        }
        getImmDelegate().setCurrentRootView(null);
        mHasImeFocus = false;
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
}
