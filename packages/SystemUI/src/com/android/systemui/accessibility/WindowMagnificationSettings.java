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

package com.android.systemui.accessibility;

import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import android.annotation.IntDef;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.MathUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.AccessibilityDelegate;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.systemui.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;

/**
 * Class to set value about WindowManificationSettings.
 */
class WindowMagnificationSettings implements MagnificationGestureDetector.OnGestureListener {
    private static final String TAG = "WindowMagnificationSettings";
    private final Context mContext;
    private final AccessibilityManager mAccessibilityManager;
    private final WindowManager mWindowManager;

    private final Runnable mWindowInsetChangeRunnable;
    private final SfVsyncFrameCallbackProvider mSfVsyncFrameProvider;

    private final LayoutParams mParams;
    @VisibleForTesting
    final Rect mDraggableWindowBounds = new Rect();
    private boolean mIsVisible = false;
    private final MagnificationGestureDetector mGestureDetector;
    private boolean mSingleTapDetected = false;

    private SeekBar mZoomSeekbar;
    private Switch mAllowDiagonalScrollingSwitch;
    private LinearLayout mPanelView;
    private LinearLayout mSettingView;
    private LinearLayout mButtonView;
    private ImageButton mSmallButton;
    private ImageButton mMediumButton;
    private ImageButton mLargeButton;
    private Button mCloseButton;
    private Button mEditButton;
    private ImageButton mChangeModeButton;
    private boolean mAllowDiagonalScrolling = false;
    private static final float A11Y_CHANGE_SCALE_DIFFERENCE = 1.0f;
    private static final float A11Y_SCALE_MIN_VALUE = 2.0f;
    private WindowMagnificationSettingsCallback mCallback;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            MagnificationSize.NONE,
            MagnificationSize.SMALL,
            MagnificationSize.MEDIUM,
            MagnificationSize.LARGE,
    })
    /** Denotes the Magnification size type. */
    public @interface MagnificationSize {
        int NONE = 0;
        int SMALL = 1;
        int MEDIUM = 2;
        int LARGE = 3;
    }

    @VisibleForTesting
    WindowMagnificationSettings(Context context, WindowMagnificationSettingsCallback callback,
            SfVsyncFrameCallbackProvider sfVsyncFrameProvider) {
        mContext = context;
        mAccessibilityManager = mContext.getSystemService(AccessibilityManager.class);
        mWindowManager = mContext.getSystemService(WindowManager.class);
        mSfVsyncFrameProvider = sfVsyncFrameProvider;
        mCallback = callback;

        mAllowDiagonalScrolling = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ALLOW_DIAGONAL_SCROLLING, 0,
                UserHandle.USER_CURRENT) == 1;

        inflateView();

        mParams = createLayoutParams(context);
        mWindowInsetChangeRunnable = this::onWindowInsetChanged;
        mGestureDetector = new MagnificationGestureDetector(context,
                context.getMainThreadHandler(), this);
    }

    private class ZoomSeekbarChangeListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            float scale = progress * A11Y_CHANGE_SCALE_DIFFERENCE + A11Y_SCALE_MIN_VALUE;
            Settings.Secure.putFloatForUser(mContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE, scale,
                    UserHandle.USER_CURRENT);
            mCallback.onMagnifierScale(scale);
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            // Do nothing
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            // Do nothing
        }
    }

    private CharSequence formatContentDescription(int viewId) {
        if (viewId == R.id.magnifier_small_button) {
            return mContext.getResources().getString(
                    R.string.accessibility_magnification_small);
        } else if (viewId == R.id.magnifier_medium_button) {
            return mContext.getResources().getString(
                    R.string.accessibility_magnification_medium);
        } else if (viewId == R.id.magnifier_large_button) {
            return mContext.getResources().getString(
                    R.string.accessibility_magnification_large);
        } else if (viewId == R.id.magnifier_close_button) {
            return mContext.getResources().getString(
                    R.string.accessibility_magnification_close);
        } else if (viewId == R.id.magnifier_edit_button) {
            return mContext.getResources().getString(
                    R.string.accessibility_resize);
        } else {
            return mContext.getResources().getString(
                    R.string.magnification_mode_switch_description);
        }
    }

    private final AccessibilityDelegate mButtonDelegate = new AccessibilityDelegate() {
        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);

            info.setContentDescription(formatContentDescription(host.getId()));
            final AccessibilityAction clickAction = new AccessibilityAction(
                    AccessibilityAction.ACTION_CLICK.getId(), mContext.getResources().getString(
                    R.string.magnification_mode_switch_click_label));
            info.addAction(clickAction);
            info.setClickable(true);
            info.addAction(new AccessibilityAction(R.id.accessibility_action_move_up,
                    mContext.getString(R.string.accessibility_control_move_up)));
            info.addAction(new AccessibilityAction(R.id.accessibility_action_move_down,
                    mContext.getString(R.string.accessibility_control_move_down)));
            info.addAction(new AccessibilityAction(R.id.accessibility_action_move_left,
                    mContext.getString(R.string.accessibility_control_move_left)));
            info.addAction(new AccessibilityAction(R.id.accessibility_action_move_right,
                    mContext.getString(R.string.accessibility_control_move_right)));
        }

        @Override
        public boolean performAccessibilityAction(View host, int action, Bundle args) {
            if (performA11yAction(host, action)) {
                return true;
            }
            return super.performAccessibilityAction(host, action, args);
        }

        private boolean performA11yAction(View view, int action) {
            final Rect windowBounds = mWindowManager.getCurrentWindowMetrics().getBounds();
            if (action == AccessibilityAction.ACTION_CLICK.getId()) {
                handleSingleTap(view);
            } else if (action == R.id.accessibility_action_move_up) {
                moveButton(0, -windowBounds.height());
            } else if (action == R.id.accessibility_action_move_down) {
                moveButton(0, windowBounds.height());
            } else if (action == R.id.accessibility_action_move_left) {
                moveButton(-windowBounds.width(), 0);
            } else if (action == R.id.accessibility_action_move_right) {
                moveButton(windowBounds.width(), 0);
            } else {
                return false;
            }
            return true;
        }
    };

    private void applyResourcesValuesWithDensityChanged() {
        if (mIsVisible) {
            // Reset button to make its window layer always above the mirror window.
            hideSettingPanel();
            showSettingPanel(false);
        }
    }

    private boolean onTouch(View v, MotionEvent event) {
        if (!mIsVisible) {
            return false;
        }
        return mGestureDetector.onTouch(v, event);
    }

    private View.OnClickListener mButtonClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            int id = view.getId();
            if (id == R.id.magnifier_small_button) {
                setMagnifierSize(MagnificationSize.SMALL);
            } else if (id == R.id.magnifier_medium_button) {
                setMagnifierSize(MagnificationSize.MEDIUM);
            } else if (id == R.id.magnifier_large_button) {
                setMagnifierSize(MagnificationSize.LARGE);
            } else if (id == R.id.magnifier_edit_button) {
                editMagnifierSizeMode(true);
            } else if (id == R.id.magnifier_close_button) {
                hideSettingPanel();
            } else if (id == R.id.magnifier_full_button) {
                hideSettingPanel();
                toggleMagnificationMode();
            } else {
                hideSettingPanel();
            }
        }
    };

    @Override
    public boolean onSingleTap(View view) {
        mSingleTapDetected = true;
        handleSingleTap(view);
        return true;
    }

    @Override
    public boolean onDrag(View v, float offsetX, float offsetY) {
        moveButton(offsetX, offsetY);
        return true;
    }

    @Override
    public boolean onStart(float x, float y) {
        return true;
    }

    @Override
    public boolean onFinish(float xOffset, float yOffset) {
        if (!mSingleTapDetected) {
            showSettingPanel();
        }
        mSingleTapDetected = false;
        return true;
    }

    @VisibleForTesting
    public ViewGroup getSettingView() {
        return mSettingView;
    }

    private void moveButton(float offsetX, float offsetY) {
        mSfVsyncFrameProvider.postFrameCallback(l -> {
            mParams.x += offsetX;
            mParams.y += offsetY;
            updateButtonViewLayoutIfNeeded();
        });
    }

    public void hideSettingPanel() {
        hideSettingPanel(true);
    }

    public void hideSettingPanel(boolean resetPosition) {
        if (!mIsVisible) {
            return;
        }

        // Reset button status.
        mWindowManager.removeView(mSettingView);
        mIsVisible = false;
        if (resetPosition) {
            mParams.x = 0;
            mParams.y = 0;
        }

        mContext.unregisterReceiver(mScreenOffReceiver);
    }

    public void showSettingPanel() {
        showSettingPanel(true);
    }

    public void setScaleSeekbar(float scale) {
        setSeekbarProgress(scale);
    }

    private void toggleMagnificationMode() {
        mCallback.onModeSwitch(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
    }

    /**
     * Shows magnification panel for set window magnification.
     * When the panel is going to be visible by calling this method, the layout position can be
     * reset depending on the flag.
     *
     * @param resetPosition if the button position needs be reset
     */
    private void showSettingPanel(boolean resetPosition) {
        if (!mIsVisible) {
            if (resetPosition) {
                mDraggableWindowBounds.set(getDraggableWindowBounds());
                mParams.x = mDraggableWindowBounds.right;
                mParams.y = mDraggableWindowBounds.bottom;
            }

            mWindowManager.addView(mSettingView, mParams);

            // Exclude magnification switch button from system gesture area.
            setSystemGestureExclusion();
            mIsVisible = true;
        }
        mContext.registerReceiver(mScreenOffReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    private final BroadcastReceiver mScreenOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            hideSettingPanel();
        }
    };

    private void setSeekbarProgress(float scale) {
        int index = (int) ((scale - A11Y_SCALE_MIN_VALUE) / A11Y_CHANGE_SCALE_DIFFERENCE);
        if (index < 0) {
            index = 0;
        }
        mZoomSeekbar.setProgress(index);
    }

    void inflateView() {
        mSettingView = (LinearLayout) View.inflate(mContext,
                R.layout.window_magnification_settings_view, null);

        mSettingView.setClickable(true);
        mSettingView.setFocusable(true);
        mSettingView.setOnTouchListener(this::onTouch);

        mPanelView = mSettingView.findViewById(R.id.magnifier_panel_view);
        mSmallButton = mSettingView.findViewById(R.id.magnifier_small_button);
        mMediumButton = mSettingView.findViewById(R.id.magnifier_medium_button);
        mLargeButton = mSettingView.findViewById(R.id.magnifier_large_button);
        mCloseButton = mSettingView.findViewById(R.id.magnifier_close_button);
        mEditButton = mSettingView.findViewById(R.id.magnifier_edit_button);
        mChangeModeButton = mSettingView.findViewById(R.id.magnifier_full_button);

        mZoomSeekbar = mSettingView.findViewById(R.id.magnifier_zoom_seekbar);
        mZoomSeekbar.setOnSeekBarChangeListener(new ZoomSeekbarChangeListener());

        float scale = Settings.Secure.getFloatForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE, 0,
                UserHandle.USER_CURRENT);
        setSeekbarProgress(scale);
        mAllowDiagonalScrollingSwitch =
                (Switch) mSettingView.findViewById(R.id.magnifier_horizontal_lock_switch);
        mAllowDiagonalScrollingSwitch.setChecked(mAllowDiagonalScrolling);
        mAllowDiagonalScrollingSwitch.setOnCheckedChangeListener((view, checked) -> {
            toggleDiagonalScrolling();
        });

        mSmallButton.setAccessibilityDelegate(mButtonDelegate);
        mSmallButton.setOnClickListener(mButtonClickListener);

        mMediumButton.setAccessibilityDelegate(mButtonDelegate);
        mMediumButton.setOnClickListener(mButtonClickListener);

        mLargeButton.setAccessibilityDelegate(mButtonDelegate);
        mLargeButton.setOnClickListener(mButtonClickListener);

        mCloseButton.setAccessibilityDelegate(mButtonDelegate);
        mCloseButton.setOnClickListener(mButtonClickListener);

        mChangeModeButton.setAccessibilityDelegate(mButtonDelegate);
        mChangeModeButton.setOnClickListener(mButtonClickListener);

        mEditButton.setAccessibilityDelegate(mButtonDelegate);
        mEditButton.setOnClickListener(mButtonClickListener);

        mSettingView.setOnApplyWindowInsetsListener((v, insets) -> {
            // Adds a pending post check to avoiding redundant calculation because this callback
            // is sent frequently when the switch icon window dragged by the users.
            if (!mSettingView.getHandler().hasCallbacks(mWindowInsetChangeRunnable)) {
                mSettingView.getHandler().post(mWindowInsetChangeRunnable);
            }
            return v.onApplyWindowInsets(insets);
        });
    }

    void onConfigurationChanged(int configDiff) {
        if ((configDiff & ActivityInfo.CONFIG_UI_MODE) != 0) {
            boolean showSettingPanelAfterThemeChange = mIsVisible;
            hideSettingPanel(/* resetPosition= */ false);
            inflateView();
            if (showSettingPanelAfterThemeChange) {
                showSettingPanel(/* resetPosition= */ false);
            }
            return;
        }
        if ((configDiff & ActivityInfo.CONFIG_ORIENTATION) != 0) {
            final Rect previousDraggableBounds = new Rect(mDraggableWindowBounds);
            mDraggableWindowBounds.set(getDraggableWindowBounds());
            // Keep the Y position with the same height ratio before the window bounds and
            // draggable bounds are changed.
            final float windowHeightFraction = (float) (mParams.y - previousDraggableBounds.top)
                    / previousDraggableBounds.height();
            mParams.y = (int) (windowHeightFraction * mDraggableWindowBounds.height())
                    + mDraggableWindowBounds.top;
            return;
        }
        if ((configDiff & ActivityInfo.CONFIG_DENSITY) != 0) {
            applyResourcesValuesWithDensityChanged();
            return;
        }
        if ((configDiff & ActivityInfo.CONFIG_LOCALE) != 0) {
            updateAccessibilityWindowTitle();
            return;
        }
    }

    private void onWindowInsetChanged() {
        final Rect newBounds = getDraggableWindowBounds();
        if (mDraggableWindowBounds.equals(newBounds)) {
            return;
        }
        mDraggableWindowBounds.set(newBounds);
    }

    private void updateButtonViewLayoutIfNeeded() {
        if (mIsVisible) {
            mParams.x = MathUtils.constrain(mParams.x, mDraggableWindowBounds.left,
                    mDraggableWindowBounds.right);
            mParams.y = MathUtils.constrain(mParams.y, mDraggableWindowBounds.top,
                    mDraggableWindowBounds.bottom);
            mWindowManager.updateViewLayout(mSettingView, mParams);
        }
    }

    private void updateAccessibilityWindowTitle() {
        mParams.accessibilityTitle = getAccessibilityWindowTitle(mContext);
        if (mIsVisible) {
            mWindowManager.updateViewLayout(mSettingView, mParams);
        }
    }

    private void handleSingleTap(View view) {
        int id = view.getId();
        if (id == R.id.magnifier_small_button) {
            setMagnifierSize(MagnificationSize.SMALL);
        } else if (id == R.id.magnifier_medium_button) {
            setMagnifierSize(MagnificationSize.MEDIUM);
        } else if (id == R.id.magnifier_large_button) {
            setMagnifierSize(MagnificationSize.LARGE);
        } else if (id == R.id.magnifier_full_button) {
            hideSettingPanel();
            toggleMagnificationMode();
        } else {
            hideSettingPanel();
        }
    }

    public void editMagnifierSizeMode(boolean enable) {
        setEditMagnifierSizeMode(enable);
        hideSettingPanel();
    }

    private void setMagnifierSize(@MagnificationSize int index) {
        mCallback.onSetMagnifierSize(index);
    }

    private void toggleDiagonalScrolling() {
        boolean enabled = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ALLOW_DIAGONAL_SCROLLING, 0,
                UserHandle.USER_CURRENT) == 1;

        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ALLOW_DIAGONAL_SCROLLING, enabled ? 0 : 1,
                UserHandle.USER_CURRENT);

        mCallback.onSetDiagonalScrolling(!enabled);
    }

    private void setEditMagnifierSizeMode(boolean enable) {
        mCallback.onEditMagnifierSizeMode(enable);
    }

    private static LayoutParams createLayoutParams(Context context) {
        final LayoutParams params = new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY,
                LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSPARENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.accessibilityTitle = getAccessibilityWindowTitle(context);
        params.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        return params;
    }

    private Rect getDraggableWindowBounds() {
        final int layoutMargin = mContext.getResources().getDimensionPixelSize(
                R.dimen.magnification_switch_button_margin);
        final WindowMetrics windowMetrics = mWindowManager.getCurrentWindowMetrics();
        final Insets windowInsets = windowMetrics.getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        final Rect boundRect = new Rect(windowMetrics.getBounds());
        boundRect.offsetTo(0, 0);
        boundRect.inset(0, 0, mParams.width, mParams.height);
        boundRect.inset(windowInsets);
        boundRect.inset(layoutMargin, layoutMargin);

        return boundRect;
    }

    private static String getAccessibilityWindowTitle(Context context) {
        return context.getString(com.android.internal.R.string.android_system_label);
    }

    private void setSystemGestureExclusion() {
        mSettingView.post(() -> {
            mSettingView.setSystemGestureExclusionRects(
                    Collections.singletonList(
                            new Rect(0, 0, mSettingView.getWidth(), mSettingView.getHeight())));
        });
    }
}
