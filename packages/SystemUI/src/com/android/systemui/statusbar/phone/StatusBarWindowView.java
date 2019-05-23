/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.annotation.ColorInt;
import android.annotation.DrawableRes;
import android.annotation.LayoutRes;
import android.app.StatusBarManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.display.AmbientDisplayConfiguration;
import android.media.AudioManager;
import android.media.session.MediaSessionLegacyHelper;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.ActionMode;
import android.view.DisplayCutout;
import android.view.GestureDetector;
import android.view.InputDevice;
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
import android.view.WindowInsetsController;
import android.widget.FrameLayout;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.view.FloatingActionMode;
import com.android.internal.widget.FloatingToolbar;
import com.android.systemui.Dependency;
import com.android.systemui.ExpandHelper;
import com.android.systemui.R;
import com.android.systemui.classifier.FalsingManagerFactory;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.DragDownHelper;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout;
import com.android.systemui.statusbar.phone.ScrimController.ScrimVisibility;
import com.android.systemui.tuner.TunerService;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Combined status bar and notification panel view. Also holding backdrop and scrims.
 */
public class StatusBarWindowView extends FrameLayout {
    public static final String TAG = "StatusBarWindowView";
    public static final boolean DEBUG = StatusBar.DEBUG;

    private final GestureDetector mGestureDetector;
    private final StatusBarStateController mStatusBarStateController;
    private boolean mDoubleTapEnabled;
    private boolean mSingleTapEnabled;
    private DragDownHelper mDragDownHelper;
    private NotificationStackScrollLayout mStackScrollLayout;
    private NotificationPanelView mNotificationPanel;
    private View mBrightnessMirror;
    private LockIcon mLockIcon;
    private PhoneStatusBarView mStatusBarView;

    private int mRightInset = 0;
    private int mLeftInset = 0;

    private StatusBar mService;
    private final Paint mTransparentSrcPaint = new Paint();
    private FalsingManager mFalsingManager;

    // Implements the floating action mode for TextView's Cut/Copy/Past menu. Normally provided by
    // DecorView, but since this is a special window we have to roll our own.
    private View mFloatingActionModeOriginatingView;
    private ActionMode mFloatingActionMode;
    private FloatingToolbar mFloatingToolbar;
    private ViewTreeObserver.OnPreDrawListener mFloatingToolbarPreDrawListener;
    private boolean mTouchCancelled;
    private boolean mTouchActive;
    private boolean mExpandAnimationRunning;
    private boolean mExpandAnimationPending;
    private boolean mSuppressingWakeUpGesture;

    private final GestureDetector.SimpleOnGestureListener mGestureListener =
            new GestureDetector.SimpleOnGestureListener() {
        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            if (mSingleTapEnabled && !mSuppressingWakeUpGesture) {
                mService.wakeUpIfDozing(SystemClock.uptimeMillis(), StatusBarWindowView.this,
                        "SINGLE_TAP");
                return true;
            }
            return false;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (mDoubleTapEnabled || mSingleTapEnabled) {
                mService.wakeUpIfDozing(SystemClock.uptimeMillis(), StatusBarWindowView.this,
                        "DOUBLE_TAP");
                return true;
            }
            return false;
        }
    };
    private final TunerService.Tunable mTunable = (key, newValue) -> {
        AmbientDisplayConfiguration configuration = new AmbientDisplayConfiguration(mContext);
        switch (key) {
            case Settings.Secure.DOZE_DOUBLE_TAP_GESTURE:
                mDoubleTapEnabled = configuration.doubleTapGestureEnabled(UserHandle.USER_CURRENT);
                break;
            case Settings.Secure.DOZE_TAP_SCREEN_GESTURE:
                mSingleTapEnabled = configuration.tapGestureEnabled(UserHandle.USER_CURRENT);
        }
    };

    /**
     * If set to true, the current gesture started below the notch and we need to dispatch touch
     * events manually as it's outside of the regular view bounds.
     */
    private boolean mExpandingBelowNotch;

    public StatusBarWindowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setMotionEventSplittingEnabled(false);
        mTransparentSrcPaint.setColor(0);
        mTransparentSrcPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
        mFalsingManager = FalsingManagerFactory.getInstance(context);
        mGestureDetector = new GestureDetector(context, mGestureListener);
        mStatusBarStateController = Dependency.get(StatusBarStateController.class);
        Dependency.get(TunerService.class).addTunable(mTunable,
                Settings.Secure.DOZE_DOUBLE_TAP_GESTURE,
                Settings.Secure.DOZE_TAP_SCREEN_GESTURE);
    }

    @Override
    protected boolean fitSystemWindows(Rect insets) {
        if (getFitsSystemWindows()) {
            boolean paddingChanged = insets.top != getPaddingTop()
                    || insets.bottom != getPaddingBottom();

            int rightCutout = 0;
            int leftCutout = 0;
            DisplayCutout displayCutout = getRootWindowInsets().getDisplayCutout();
            if (displayCutout != null) {
                leftCutout = displayCutout.getSafeInsetLeft();
                rightCutout = displayCutout.getSafeInsetRight();
            }

            int targetLeft = Math.max(insets.left, leftCutout);
            int targetRight = Math.max(insets.right, rightCutout);

            // Super-special right inset handling, because scrims and backdrop need to ignore it.
            if (targetRight != mRightInset || targetLeft != mLeftInset) {
                mRightInset = targetRight;
                mLeftInset = targetLeft;
                applyMargins();
            }
            // Drop top inset, and pass through bottom inset.
            if (paddingChanged) {
                setPadding(0, 0, 0, 0);
            }
            insets.left = 0;
            insets.top = 0;
            insets.right = 0;
        } else {
            if (mRightInset != 0 || mLeftInset != 0) {
                mRightInset = 0;
                mLeftInset = 0;
                applyMargins();
            }
            boolean changed = getPaddingLeft() != 0
                    || getPaddingRight() != 0
                    || getPaddingTop() != 0
                    || getPaddingBottom() != 0;
            if (changed) {
                setPadding(0, 0, 0, 0);
            }
            insets.top = 0;
        }
        return false;
    }

    private void applyMargins() {
        final int N = getChildCount();
        for (int i = 0; i < N; i++) {
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

    @VisibleForTesting
    protected NotificationStackScrollLayout getStackScrollLayout() {
        return mStackScrollLayout;
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
    protected void onFinishInflate() {
        super.onFinishInflate();
        mStackScrollLayout = findViewById(R.id.notification_stack_scroller);
        mNotificationPanel = findViewById(R.id.notification_panel);
        mBrightnessMirror = findViewById(R.id.brightness_mirror);
        mLockIcon = findViewById(R.id.lock_icon);
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        if (child.getId() == R.id.brightness_mirror) {
            mBrightnessMirror = child;
        }
    }

    /**
     * Propagate {@link StatusBar} pulsing state.
     */
    public void setPulsing(boolean pulsing) {
        if (mLockIcon != null) {
            mLockIcon.setPulsing(pulsing);
        }
    }

    /**
     * Called when the biometric authentication mode changes.
     * @param wakeAndUnlock If the type is {@link BiometricUnlockController#isWakeAndUnlock()}
     */
    public void onBiometricAuthModeChanged(boolean wakeAndUnlock) {
        if (mLockIcon != null) {
            mLockIcon.onBiometricAuthModeChanged(wakeAndUnlock);
        }
    }

    public void setStatusBarView(PhoneStatusBarView statusBarView) {
        mStatusBarView = statusBarView;
    }

    public void setService(StatusBar service) {
        mService = service;
        NotificationStackScrollLayout stackScrollLayout = getStackScrollLayout();
        ExpandHelper.Callback expandHelperCallback = stackScrollLayout.getExpandHelperCallback();
        DragDownHelper.DragDownCallback dragDownCallback = stackScrollLayout.getDragDownCallback();
        setDragDownHelper(new DragDownHelper(getContext(), this, expandHelperCallback,
                dragDownCallback));
    }

    @VisibleForTesting
    void setDragDownHelper(DragDownHelper dragDownHelper) {
        mDragDownHelper = dragDownHelper;
    }

    @Override
    protected void onAttachedToWindow () {
        super.onAttachedToWindow();
        setWillNotDraw(!DEBUG);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mService.interceptMediaKey(event)) {
            return true;
        }
        if (super.dispatchKeyEvent(event)) {
            return true;
        }
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
                if (!down) {
                    mService.onBackPressed();
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (!down) {
                    return mService.onMenuPressed();
                }
            case KeyEvent.KEYCODE_SPACE:
                if (!down) {
                    return mService.onSpacePressed();
                }
                break;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_UP:
                if (mService.isDozing()) {
                    MediaSessionLegacyHelper.getHelper(mContext).sendVolumeKeyEvent(
                            event, AudioManager.USE_DEFAULT_STREAM_TYPE, true);
                    return true;
                }
                break;
        }
        return false;
    }

    public void setTouchActive(boolean touchActive) {
        mTouchActive = touchActive;
    }

    void suppressWakeUpGesture(boolean suppress) {
        mSuppressingWakeUpGesture = suppress;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        boolean isDown = ev.getActionMasked() == MotionEvent.ACTION_DOWN;
        boolean isUp = ev.getActionMasked() == MotionEvent.ACTION_UP;
        boolean isCancel = ev.getActionMasked() == MotionEvent.ACTION_CANCEL;

        // Reset manual touch dispatch state here but make sure the UP/CANCEL event still gets
        // delivered.
        boolean expandingBelowNotch = mExpandingBelowNotch;
        if (isUp || isCancel) {
            mExpandingBelowNotch = false;
        }

        if (!isCancel && mService.shouldIgnoreTouch()) {
            return false;
        }
        if (isDown && mNotificationPanel.isFullyCollapsed()) {
            mNotificationPanel.startExpandLatencyTracking();
        }
        if (isDown) {
            setTouchActive(true);
            mTouchCancelled = false;
        } else if (ev.getActionMasked() == MotionEvent.ACTION_UP
                || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            setTouchActive(false);
        }
        if (mTouchCancelled || mExpandAnimationRunning || mExpandAnimationPending) {
            return false;
        }
        mFalsingManager.onTouchEvent(ev, getWidth(), getHeight());
        mGestureDetector.onTouchEvent(ev);
        if (mBrightnessMirror != null && mBrightnessMirror.getVisibility() == VISIBLE) {
            // Disallow new pointers while the brightness mirror is visible. This is so that you
            // can't touch anything other than the brightness slider while the mirror is showing
            // and the rest of the panel is transparent.
            if (ev.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
                return false;
            }
        }
        if (isDown) {
            getStackScrollLayout().closeControlsIfOutsideTouch(ev);
        }
        if (mService.isDozing()) {
            mService.mDozeScrimController.extendPulse();
        }

        // In case we start outside of the view bounds (below the status bar), we need to dispatch
        // the touch manually as the view system can't accomodate for touches outside of the
        // regular view bounds.
        if (isDown && ev.getY() >= mBottom) {
            mExpandingBelowNotch = true;
            expandingBelowNotch = true;
        }
        if (expandingBelowNotch) {
            return mStatusBarView.dispatchTouchEvent(ev);
        }

        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        NotificationStackScrollLayout stackScrollLayout = getStackScrollLayout();
        if (mService.isDozing() && !mService.isPulsing()) {
            // Capture all touch events in always-on.
            return true;
        }
        boolean intercept = false;
        if (mNotificationPanel.isFullyExpanded()
                && stackScrollLayout.getVisibility() == View.VISIBLE
                && mStatusBarStateController.getState() == StatusBarState.KEYGUARD
                && !mService.isBouncerShowing()
                && !mService.isDozing()) {
            intercept = mDragDownHelper.onInterceptTouchEvent(ev);
        }
        if (!intercept) {
            super.onInterceptTouchEvent(ev);
        }
        if (intercept) {
            MotionEvent cancellation = MotionEvent.obtain(ev);
            cancellation.setAction(MotionEvent.ACTION_CANCEL);
            stackScrollLayout.onInterceptTouchEvent(cancellation);
            mNotificationPanel.onInterceptTouchEvent(cancellation);
            cancellation.recycle();
        }
        return intercept;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        boolean handled = false;
        if (mService.isDozing()) {
            handled = !mService.isPulsing();
        }
        if ((mStatusBarStateController.getState() == StatusBarState.KEYGUARD && !handled)
                || mDragDownHelper.isDraggingDown()) {
            // we still want to finish our drag down gesture when locking the screen
            handled = mDragDownHelper.onTouchEvent(ev);
        }
        if (!handled) {
            handled = super.onTouchEvent(ev);
        }
        final int action = ev.getAction();
        if (!handled && (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
            mService.setInteracting(StatusBarManager.WINDOW_STATUS_BAR, false);
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

    public void cancelExpandHelper() {
        NotificationStackScrollLayout stackScrollLayout = getStackScrollLayout();
        if (stackScrollLayout != null) {
            stackScrollLayout.cancelExpandHelper();
        }
    }

    public void cancelCurrentTouch() {
        if (mTouchActive) {
            final long now = SystemClock.uptimeMillis();
            MotionEvent event = MotionEvent.obtain(now, now,
                    MotionEvent.ACTION_CANCEL, 0.0f, 0.0f, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            dispatchTouchEvent(event);
            event.recycle();
            mTouchCancelled = true;
        }
    }

    public void setExpandAnimationRunning(boolean expandAnimationRunning) {
        mExpandAnimationRunning = expandAnimationRunning;
    }

    public void setExpandAnimationPending(boolean pending) {
        mExpandAnimationPending = pending;
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.print("  mExpandAnimationPending="); pw.println(mExpandAnimationPending);
        pw.print("  mExpandAnimationRunning="); pw.println(mExpandAnimationRunning);
        pw.print("  mTouchCancelled="); pw.println(mTouchCancelled);
        pw.print("  mTouchActive="); pw.println(mTouchActive);
    }

    /**
     * Called whenever the scrims become opaque, transparent or semi-transparent.
     */
    public void onScrimVisibilityChanged(@ScrimVisibility int scrimsVisible) {
        if (mLockIcon != null) {
            mLockIcon.onScrimVisibilityChanged(scrimsVisible);
        }
    }

    /**
     * When we're launching an affordance, like double pressing power to open camera.
     */
    public void onShowingLaunchAffordanceChanged(boolean showing) {
        if (mLockIcon != null) {
            mLockIcon.onShowingLaunchAffordanceChanged(showing);
        }
    }

    public class LayoutParams extends FrameLayout.LayoutParams {

        public boolean ignoreRightInset;

        public LayoutParams(int width, int height) {
            super(width, height);
        }

        public LayoutParams(Context c, AttributeSet attrs) {
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
            return startActionMode(originalView, callback, type);
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
        mFloatingToolbarPreDrawListener =
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        mode.updateViewLocationInWindow();
                        return true;
                    }
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
            View originatingView, ActionMode.Callback callback, int type) {
        ActionMode.Callback2 wrappedCallback = new ActionModeCallback2Wrapper(callback);
        ActionMode mode = createFloatingActionMode(originatingView, wrappedCallback);
        if (mode != null && wrappedCallback.onCreateActionMode(mode, mode.getMenu())) {
            setHandledFloatingActionMode(mode);
        } else {
            mode = null;
        }
        return mode;
    }

    private class ActionModeCallback2Wrapper extends ActionMode.Callback2 {
        private final ActionMode.Callback mWrapped;

        public ActionModeCallback2Wrapper(ActionMode.Callback wrapped) {
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
     * Minimal window to satisfy FloatingToolbar.
     */
    private Window mFakeWindow = new Window(mContext) {
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
            return StatusBarWindowView.this;
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

