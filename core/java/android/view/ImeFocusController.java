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

import static android.view.ImeFocusControllerProto.HAS_IME_FOCUS;

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.UiThread;
import android.util.Log;
import android.util.proto.ProtoOutputStream;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.inputmethod.InputMethodDebug;

/**
 * Responsible for IME focus handling inside {@link ViewRootImpl}.
 * @hide
 */
public final class ImeFocusController {
    private static final boolean DEBUG = false;
    private static final String TAG = "ImeFocusController";

    private final ViewRootImpl mViewRootImpl;
    private boolean mHasImeFocus = false;
    private InputMethodManagerDelegate mDelegate;

    @UiThread
    ImeFocusController(@NonNull ViewRootImpl viewRootImpl) {
        mViewRootImpl = viewRootImpl;
    }

    @NonNull
    private InputMethodManagerDelegate getImmDelegate() {
        if (mDelegate == null) {
            mDelegate = mViewRootImpl.mContext.getSystemService(
                    InputMethodManager.class).getDelegate();
        }
        return mDelegate;
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
        final boolean hasImeFocus = WindowManager.LayoutParams.mayUseInputMethod(
                windowAttribute.flags);
        if (!hasWindowFocus || isInLocalFocusMode(windowAttribute)) {
            return;
        }
        if (hasImeFocus == mHasImeFocus) {
            return;
        }
        mHasImeFocus = hasImeFocus;
        if (mHasImeFocus) {
            getImmDelegate().onPreWindowGainedFocus(mViewRootImpl);
            final View focusedView = mViewRootImpl.mView.findFocus();
            View viewForWindowFocus = focusedView != null ? focusedView : mViewRootImpl.mView;
            getImmDelegate().onPostWindowGainedFocus(viewForWindowFocus, windowAttribute);
        }
    }

    @UiThread
    void onPreWindowFocus(boolean hasWindowFocus, WindowManager.LayoutParams windowAttribute) {
        mHasImeFocus = WindowManager.LayoutParams.mayUseInputMethod(windowAttribute.flags);
        if (!hasWindowFocus || !mHasImeFocus || isInLocalFocusMode(windowAttribute)) {
            if (!hasWindowFocus) {
                getImmDelegate().onWindowLostFocus(mViewRootImpl);
            }
        } else {
            getImmDelegate().onPreWindowGainedFocus(mViewRootImpl);
        }
    }

    @UiThread
    void onPostWindowFocus(View focusedView, boolean hasWindowFocus,
            WindowManager.LayoutParams windowAttribute) {
        if (!hasWindowFocus || !mHasImeFocus || isInLocalFocusMode(windowAttribute)) {
            return;
        }
        View viewForWindowFocus = focusedView != null ? focusedView : mViewRootImpl.mView;
        if (DEBUG) {
            Log.v(TAG, "onWindowFocus: " + viewForWindowFocus
                    + " softInputMode=" + InputMethodDebug.softInputModeToString(
                    windowAttribute.softInputMode));
        }

        getImmDelegate().onPostWindowGainedFocus(viewForWindowFocus, windowAttribute);
    }

    /**
     * @see ViewRootImpl#dispatchCheckFocus()
     */
    @UiThread
    void onScheduledCheckFocus() {
        getImmDelegate().onScheduledCheckFocus(mViewRootImpl);
    }

    @UiThread
    void onViewFocusChanged(View view, boolean hasFocus) {
        getImmDelegate().onViewFocusChanged(view, hasFocus);
    }

    @UiThread
    void onViewDetachedFromWindow(View view) {
        getImmDelegate().onViewDetachedFromWindow(view, mViewRootImpl);

    }

    @UiThread
    void onWindowDismissed() {
        getImmDelegate().onWindowDismissed(mViewRootImpl);
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
        void onPreWindowGainedFocus(ViewRootImpl viewRootImpl);
        void onPostWindowGainedFocus(View viewForWindowFocus,
                @NonNull WindowManager.LayoutParams windowAttribute);
        void onWindowLostFocus(@NonNull ViewRootImpl viewRootImpl);
        void onViewFocusChanged(@NonNull View view, boolean hasFocus);
        void onScheduledCheckFocus(@NonNull ViewRootImpl viewRootImpl);
        void onViewDetachedFromWindow(View view, ViewRootImpl viewRootImpl);
        void onWindowDismissed(ViewRootImpl viewRootImpl);
    }

    /**
     * Indicates whether the view's window has IME focused.
     */
    @UiThread
    boolean hasImeFocus() {
        return mHasImeFocus;
    }

    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(HAS_IME_FOCUS, mHasImeFocus);
        proto.end(token);
    }
}
