/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.shade;

import static android.os.Trace.TRACE_TAG_ALWAYS;
import static android.view.WindowInsets.Type.systemBars;

import static com.android.systemui.statusbar.phone.CentralSurfaces.DEBUG;

import android.annotation.ColorInt;
import android.annotation.DrawableRes;
import android.annotation.LayoutRes;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Trace;
import android.util.AttributeSet;
import android.util.Pair;
import android.view.ActionMode;
import android.view.DisplayCutout;
import android.view.InputQueue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;

import com.android.internal.view.FloatingActionMode;
import com.android.internal.widget.floatingtoolbar.FloatingToolbar;
import com.android.systemui.R;

/**
 * Combined keyguard and notification panel view. Also holding backdrop and scrims.
 */
public class NotificationShadeWindowView extends FrameLayout {
    public static final String TAG = "NotificationShadeWindowView";

    private int mRightInset = 0;
    private int mLeftInset = 0;

    // Implements the floating action mode for TextView's Cut/Copy/Past menu. Normally provided by
    // DecorView, but since this is a special window we have to roll our own.
    private View mFloatingActionModeOriginatingView;
    private ActionMode mFloatingActionMode;
    private FloatingToolbar mFloatingToolbar;
    private ViewTreeObserver.OnPreDrawListener mFloatingToolbarPreDrawListener;

    private InteractionEventHandler mInteractionEventHandler;
    private LayoutInsetsController mLayoutInsetProvider;

    public NotificationShadeWindowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setMotionEventSplittingEnabled(false);
    }

    public NotificationPanelView getNotificationPanelView() {
        return findViewById(R.id.notification_panel);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets windowInsets) {
        final Insets insets = windowInsets.getInsetsIgnoringVisibility(systemBars());
        if (getFitsSystemWindows()) {
            boolean paddingChanged = insets.top != getPaddingTop()
                    || insets.bottom != getPaddingBottom();

            // Drop top inset, and pass through bottom inset.
            if (paddingChanged) {
                setPadding(0, 0, 0, 0);
            }
        } else {
            boolean changed = getPaddingLeft() != 0
                    || getPaddingRight() != 0
                    || getPaddingTop() != 0
                    || getPaddingBottom() != 0;
            if (changed) {
                setPadding(0, 0, 0, 0);
            }
        }

        mLeftInset = 0;
        mRightInset = 0;
        DisplayCutout displayCutout = getRootWindowInsets().getDisplayCutout();
        Pair<Integer, Integer> pairInsets = mLayoutInsetProvider
                .getinsets(windowInsets, displayCutout);
        mLeftInset = pairInsets.first;
        mRightInset = pairInsets.second;
        applyMargins();
        return windowInsets;
    }

    private void applyMargins() {
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getLayoutParams() instanceof LayoutParams) {
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                if (!lp.ignoreRightInset
                        && (lp.rightMargin != mRightInset || lp.leftMargin != mLeftInset)) {
                    lp.rightMargin = mRightInset;
                    lp.leftMargin = mLeftInset;
                    child.requestLayout();
                }
            }
        }
    }

    @Override
    public FrameLayout.LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new LayoutParams(getContext(), attrs);
    }

    @Override
    protected FrameLayout.LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setWillNotDraw(!DEBUG);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mInteractionEventHandler.interceptMediaKey(event)) {
            return true;
        }

        if (super.dispatchKeyEvent(event)) {
            return true;
        }

        return mInteractionEventHandler.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchKeyEventPreIme(KeyEvent event) {
        return mInteractionEventHandler.dispatchKeyEventPreIme(event);
    }

    protected void setInteractionEventHandler(InteractionEventHandler listener) {
        mInteractionEventHandler = listener;
    }

    protected void setLayoutInsetsController(LayoutInsetsController provider) {
        mLayoutInsetProvider = provider;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        Boolean result = mInteractionEventHandler.handleDispatchTouchEvent(ev);

        result = result != null ? result : super.dispatchTouchEvent(ev);

        mInteractionEventHandler.dispatchTouchEventComplete();

        return result;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        boolean intercept = mInteractionEventHandler.shouldInterceptTouchEvent(ev);
        if (!intercept) {
            intercept = super.onInterceptTouchEvent(ev);
        }
        if (intercept) {
            mInteractionEventHandler.didIntercept(ev);
        }

        return intercept;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean handled = mInteractionEventHandler.handleTouchEvent(ev);

        if (!handled) {
            handled = super.onTouchEvent(ev);
        }

        if (!handled) {
            mInteractionEventHandler.didNotHandleTouchEvent(ev);
        }

        return handled;
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (DEBUG) {
            Paint pt = new Paint();
            pt.setColor(0x80FFFF00);
            pt.setStrokeWidth(12.0f);
            pt.setStyle(Paint.Style.STROKE);
            canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), pt);
        }
    }

    private static class LayoutParams extends FrameLayout.LayoutParams {

        public boolean ignoreRightInset;

        LayoutParams(int width, int height) {
            super(width, height);
        }

        LayoutParams(Context c, AttributeSet attrs) {
            super(c, attrs);

            TypedArray a = c.obtainStyledAttributes(attrs, R.styleable.StatusBarWindowView_Layout);
            ignoreRightInset = a.getBoolean(
                    R.styleable.StatusBarWindowView_Layout_ignoreRightInset, false);
            a.recycle();
        }
    }

    @Override
    public ActionMode startActionModeForChild(View originalView, ActionMode.Callback callback,
            int type) {
        if (type == ActionMode.TYPE_FLOATING) {
            return startActionMode(originalView, callback);
        }
        return super.startActionModeForChild(originalView, callback, type);
    }

    private ActionMode createFloatingActionMode(
            View originatingView, ActionMode.Callback2 callback) {
        if (mFloatingActionMode != null) {
            mFloatingActionMode.finish();
        }
        cleanupFloatingActionModeViews();
        mFloatingToolbar = new FloatingToolbar(mFakeWindow);
        final FloatingActionMode mode =
                new FloatingActionMode(mContext, callback, originatingView, mFloatingToolbar);
        mFloatingActionModeOriginatingView = originatingView;
        mFloatingToolbarPreDrawListener = () -> {
            mode.updateViewLocationInWindow();
            return true;
        };
        return mode;
    }

    private void setHandledFloatingActionMode(ActionMode mode) {
        mFloatingActionMode = mode;
        mFloatingActionMode.invalidate();  // Will show the floating toolbar if necessary.
        mFloatingActionModeOriginatingView.getViewTreeObserver()
                .addOnPreDrawListener(mFloatingToolbarPreDrawListener);
    }

    private void cleanupFloatingActionModeViews() {
        if (mFloatingToolbar != null) {
            mFloatingToolbar.dismiss();
            mFloatingToolbar = null;
        }
        if (mFloatingActionModeOriginatingView != null) {
            if (mFloatingToolbarPreDrawListener != null) {
                mFloatingActionModeOriginatingView.getViewTreeObserver()
                        .removeOnPreDrawListener(mFloatingToolbarPreDrawListener);
                mFloatingToolbarPreDrawListener = null;
            }
            mFloatingActionModeOriginatingView = null;
        }
    }

    private ActionMode startActionMode(
            View originatingView, ActionMode.Callback callback) {
        ActionMode.Callback2 wrappedCallback = new ActionModeCallback2Wrapper(callback);
        ActionMode mode = createFloatingActionMode(originatingView, wrappedCallback);
        if (wrappedCallback.onCreateActionMode(mode, mode.getMenu())) {
            setHandledFloatingActionMode(mode);
        } else {
            mode = null;
        }
        return mode;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Trace.beginSection("NotificationShadeWindowView#onMeasure");
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        Trace.endSection();
    }

    @Override
    public void requestLayout() {
        Trace.instant(TRACE_TAG_ALWAYS, "NotificationShadeWindowView#requestLayout");
        super.requestLayout();
    }

    private class ActionModeCallback2Wrapper extends ActionMode.Callback2 {
        private final ActionMode.Callback mWrapped;

        ActionModeCallback2Wrapper(ActionMode.Callback wrapped) {
            mWrapped = wrapped;
        }

        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            return mWrapped.onCreateActionMode(mode, menu);
        }

        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            requestFitSystemWindows();
            return mWrapped.onPrepareActionMode(mode, menu);
        }

        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            return mWrapped.onActionItemClicked(mode, item);
        }

        public void onDestroyActionMode(ActionMode mode) {
            mWrapped.onDestroyActionMode(mode);
            if (mode == mFloatingActionMode) {
                cleanupFloatingActionModeViews();
                mFloatingActionMode = null;
            }
            requestFitSystemWindows();
        }

        @Override
        public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
            if (mWrapped instanceof ActionMode.Callback2) {
                ((ActionMode.Callback2) mWrapped).onGetContentRect(mode, view, outRect);
            } else {
                super.onGetContentRect(mode, view, outRect);
            }
        }
    }

    /**
     * Controller responsible for calculating insets for the shade window.
     */
    public interface LayoutInsetsController {

        /**
         * Update the insets and calculate them accordingly.
         */
        Pair<Integer, Integer> getinsets(@Nullable WindowInsets windowInsets,
                @Nullable DisplayCutout displayCutout);
    }

    interface InteractionEventHandler {
        /**
         * Returns a result for {@link ViewGroup#dispatchTouchEvent(MotionEvent)} or null to defer
         * to the super method.
         */
        Boolean handleDispatchTouchEvent(MotionEvent ev);

        /**
         * Called after all dispatching is done.
         */

        void dispatchTouchEventComplete();

        /**
         * Returns if the view should intercept the touch event.
         *
         * The touch event may still be interecepted if
         * {@link ViewGroup#onInterceptTouchEvent(MotionEvent)} decides to do so.
         */
        boolean shouldInterceptTouchEvent(MotionEvent ev);

        /**
         * Called when the view decides to intercept the touch event.
         */
        void didIntercept(MotionEvent ev);

        boolean handleTouchEvent(MotionEvent ev);

        void didNotHandleTouchEvent(MotionEvent ev);

        boolean interceptMediaKey(KeyEvent event);

        boolean dispatchKeyEvent(KeyEvent event);

        boolean dispatchKeyEventPreIme(KeyEvent event);
    }

    /**
     * Minimal window to satisfy FloatingToolbar.
     */
    private final Window mFakeWindow = new Window(mContext) {
        @Override
        public void takeSurface(SurfaceHolder.Callback2 callback) {
        }

        @Override
        public void takeInputQueue(InputQueue.Callback callback) {
        }

        @Override
        public boolean isFloating() {
            return false;
        }

        @Override
        public void alwaysReadCloseOnTouchAttr() {
        }

        @Override
        public void setContentView(@LayoutRes int layoutResID) {
        }

        @Override
        public void setContentView(View view) {
        }

        @Override
        public void setContentView(View view, ViewGroup.LayoutParams params) {
        }

        @Override
        public void addContentView(View view, ViewGroup.LayoutParams params) {
        }

        @Override
        public void clearContentView() {
        }

        @Override
        public View getCurrentFocus() {
            return null;
        }

        @Override
        public LayoutInflater getLayoutInflater() {
            return null;
        }

        @Override
        public void setTitle(CharSequence title) {
        }

        @Override
        public void setTitleColor(@ColorInt int textColor) {
        }

        @Override
        public void openPanel(int featureId, KeyEvent event) {
        }

        @Override
        public void closePanel(int featureId) {
        }

        @Override
        public void togglePanel(int featureId, KeyEvent event) {
        }

        @Override
        public void invalidatePanelMenu(int featureId) {
        }

        @Override
        public boolean performPanelShortcut(int featureId, int keyCode, KeyEvent event, int flags) {
            return false;
        }

        @Override
        public boolean performPanelIdentifierAction(int featureId, int id, int flags) {
            return false;
        }

        @Override
        public void closeAllPanels() {
        }

        @Override
        public boolean performContextMenuIdentifierAction(int id, int flags) {
            return false;
        }

        @Override
        public void onConfigurationChanged(Configuration newConfig) {
        }

        @Override
        public void setBackgroundDrawable(Drawable drawable) {
        }

        @Override
        public void setFeatureDrawableResource(int featureId, @DrawableRes int resId) {
        }

        @Override
        public void setFeatureDrawableUri(int featureId, Uri uri) {
        }

        @Override
        public void setFeatureDrawable(int featureId, Drawable drawable) {
        }

        @Override
        public void setFeatureDrawableAlpha(int featureId, int alpha) {
        }

        @Override
        public void setFeatureInt(int featureId, int value) {
        }

        @Override
        public void takeKeyEvents(boolean get) {
        }

        @Override
        public boolean superDispatchKeyEvent(KeyEvent event) {
            return false;
        }

        @Override
        public boolean superDispatchKeyShortcutEvent(KeyEvent event) {
            return false;
        }

        @Override
        public boolean superDispatchTouchEvent(MotionEvent event) {
            return false;
        }

        @Override
        public boolean superDispatchTrackballEvent(MotionEvent event) {
            return false;
        }

        @Override
        public boolean superDispatchGenericMotionEvent(MotionEvent event) {
            return false;
        }

        @Override
        public View getDecorView() {
            return NotificationShadeWindowView.this;
        }

        @Override
        public View peekDecorView() {
            return null;
        }

        @Override
        public Bundle saveHierarchyState() {
            return null;
        }

        @Override
        public void restoreHierarchyState(Bundle savedInstanceState) {
        }

        @Override
        protected void onActive() {
        }

        @Override
        public void setChildDrawable(int featureId, Drawable drawable) {
        }

        @Override
        public void setChildInt(int featureId, int value) {
        }

        @Override
        public boolean isShortcutKey(int keyCode, KeyEvent event) {
            return false;
        }

        @Override
        public void setVolumeControlStream(int streamType) {
        }

        @Override
        public int getVolumeControlStream() {
            return 0;
        }

        @Override
        public int getStatusBarColor() {
            return 0;
        }

        @Override
        public void setStatusBarColor(@ColorInt int color) {
        }

        @Override
        public int getNavigationBarColor() {
            return 0;
        }

        @Override
        public void setNavigationBarColor(@ColorInt int color) {
        }

        @Override
        public void setDecorCaptionShade(int decorCaptionShade) {
        }

        @Override
        public void setResizingCaptionDrawable(Drawable drawable) {
        }

        @Override
        public void onMultiWindowModeChanged() {
        }

        @Override
        public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        }

        @Override
        public void reportActivityRelaunched() {
        }

        @Override
        public WindowInsetsController getInsetsController() {
            return null;
        }
    };

}

