/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.inputmethodservice;

import static android.view.WindowManager.LayoutParams;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

import android.annotation.NonNull;
import android.content.Context;
import android.os.IBinder;
import android.util.Slog;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import com.android.internal.policy.PhoneWindow;

import java.util.Objects;

/**
 * Window of type {@code LayoutParams.TYPE_INPUT_METHOD_DIALOG} for drawing
 * Handwriting Ink on screen.
 * @hide
 */
final class InkWindow extends PhoneWindow {

    private final WindowManager mWindowManager;
    private boolean mIsViewAdded;
    private View mInkView;
    private InkVisibilityListener mInkViewVisibilityListener;
    private ViewTreeObserver.OnGlobalLayoutListener mGlobalLayoutListener;

    public InkWindow(@NonNull Context context) {
        super(context);

        setType(LayoutParams.TYPE_INPUT_METHOD);
        final LayoutParams attrs = getAttributes();
        attrs.layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        attrs.setFitInsetsTypes(0);
        // disable window animations.
        // TODO(b/253477462): replace with API when available
        attrs.windowAnimations = -1;
        // TODO(b/210039666): use INPUT_FEATURE_NO_INPUT_CHANNEL once b/216179339 is fixed.
        setAttributes(attrs);
        // Ink window is not touchable with finger.
        addFlags(FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS | FLAG_NOT_TOUCHABLE
                | FLAG_NOT_FOCUSABLE);
        setBackgroundDrawableResource(android.R.color.transparent);
        setLayout(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        mWindowManager = context.getSystemService(WindowManager.class);
    }

    /**
     * Initialize InkWindow if we only want to create and draw surface but not show it.
     */
    void initOnly() {
        show(true /* keepInvisible */);
    }

    /**
     * Method to show InkWindow on screen.
     * Emulates internal behavior similar to Dialog.show().
     */
    void show() {
        show(false /* keepInvisible */);
    }

    private void show(boolean keepInvisible) {
        if (getDecorView() == null) {
            Slog.i(InputMethodService.TAG, "DecorView is not set for InkWindow. show() failed.");
            return;
        }
        getDecorView().setVisibility(keepInvisible ? View.INVISIBLE : View.VISIBLE);
        if (!mIsViewAdded) {
            mWindowManager.addView(getDecorView(), getAttributes());
            mIsViewAdded = true;
        }
    }

    /**
     * Method to hide InkWindow from screen.
     * Emulates internal behavior similar to Dialog.hide().
     * @param remove set {@code true} to remove InkWindow surface completely.
     */
    void hide(boolean remove) {
        if (getDecorView() != null) {
            if (remove) {
                mWindowManager.removeViewImmediate(getDecorView());
            } else {
                getDecorView().setVisibility(View.INVISIBLE);
            }
        }
    }

    void setToken(@NonNull IBinder token) {
        WindowManager.LayoutParams lp = getAttributes();
        lp.token = token;
        setAttributes(lp);
    }

    @Override
    public void addContentView(View view, ViewGroup.LayoutParams params) {
        if (mInkView == null) {
            mInkView = view;
        } else if (mInkView != view) {
            throw new IllegalStateException("Only one Child Inking view is permitted.");
        }
        super.addContentView(view, params);
        initInkViewVisibilityListener();
    }

    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        mInkView = view;
        super.setContentView(view, params);
        initInkViewVisibilityListener();
    }

    @Override
    public void setContentView(View view) {
        mInkView = view;
        super.setContentView(view);
        initInkViewVisibilityListener();
    }

    @Override
    public void clearContentView() {
        if (mGlobalLayoutListener != null && mInkView != null) {
            mInkView.getViewTreeObserver().removeOnGlobalLayoutListener(mGlobalLayoutListener);
        }
        mGlobalLayoutListener = null;
        mInkView = null;
        super.clearContentView();
    }

    /**
    * Listener used by InkWindow to time the dispatching of {@link MotionEvent}s to Ink view, once
    * it is visible to user.
    */
    interface InkVisibilityListener {
        void onInkViewVisible();
    }

    void setInkViewVisibilityListener(InkVisibilityListener listener) {
        mInkViewVisibilityListener = listener;
        initInkViewVisibilityListener();
    }

    void initInkViewVisibilityListener() {
        if (mInkView == null || mInkViewVisibilityListener == null
                || mGlobalLayoutListener != null) {
            return;
        }
        mGlobalLayoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (mInkView == null) {
                    return;
                }
                if (mInkView.isVisibleToUser()) {
                    if (mInkViewVisibilityListener != null) {
                        mInkViewVisibilityListener.onInkViewVisible();
                    }
                    mInkView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    mGlobalLayoutListener = null;
                }
            }
        };
        mInkView.getViewTreeObserver().addOnGlobalLayoutListener(mGlobalLayoutListener);
    }

    boolean isInkViewVisible() {
        return getDecorView().getVisibility() == View.VISIBLE
                && mInkView != null && mInkView.isVisibleToUser();
    }

    void dispatchHandwritingEvent(@NonNull MotionEvent event) {
        final View decor = getDecorView();
        Objects.requireNonNull(decor);
        final ViewRootImpl viewRoot = decor.getViewRootImpl();
        Objects.requireNonNull(viewRoot);
        // The view root will own the event that we enqueue, so provide a copy of the event.
        viewRoot.enqueueInputEvent(MotionEvent.obtain(event));
    }
}
