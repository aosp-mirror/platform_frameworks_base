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

import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_ALL;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import static com.android.internal.accessibility.common.MagnificationConstants.SCALE_MAX_VALUE;
import static com.android.internal.accessibility.common.MagnificationConstants.SCALE_MIN_VALUE;

import android.annotation.IntDef;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.database.ContentObserver;
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
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.systemui.common.ui.view.SeekBarWithIconButtonsView;
import com.android.systemui.res.R;
import com.android.systemui.util.settings.SecureSettings;

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
    private final SecureSettings mSecureSettings;

    private final Runnable mWindowInsetChangeRunnable;
    private final SfVsyncFrameCallbackProvider mSfVsyncFrameProvider;

    @VisibleForTesting
    final LayoutParams mParams;
    @VisibleForTesting
    final Rect mDraggableWindowBounds = new Rect();
    private boolean mIsVisible = false;
    private final MagnificationGestureDetector mGestureDetector;
    private boolean mSingleTapDetected = false;

    private SeekBarWithIconButtonsView mZoomSeekbar;
    private LinearLayout mAllowDiagonalScrollingView;
    private TextView mAllowDiagonalScrollingTitle;
    private Switch mAllowDiagonalScrollingSwitch;
    private LinearLayout mPanelView;
    private LinearLayout mSettingView;
    private ImageButton mSmallButton;
    private ImageButton mMediumButton;
    private ImageButton mLargeButton;
    private Button mDoneButton;
    private Button mEditButton;
    private ImageButton mFullScreenButton;
    private int mLastSelectedButtonIndex = MagnificationSize.NONE;

    private boolean mAllowDiagonalScrolling = false;

    /**
     * Amount by which magnification scale changes compared to seekbar in settings.
     * magnitude = 10 means, for every 1 scale increase, 10 progress increase in seekbar.
     */
    private int mSeekBarMagnitude;
    private float mScale = SCALE_MIN_VALUE;

    private WindowMagnificationSettingsCallback mCallback;

    private ContentObserver mMagnificationCapabilityObserver;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            MagnificationSize.NONE,
            MagnificationSize.SMALL,
            MagnificationSize.MEDIUM,
            MagnificationSize.LARGE,
            MagnificationSize.FULLSCREEN
    })
    /** Denotes the Magnification size type. */
    public @interface MagnificationSize {
        int NONE = 0;
        int SMALL = 1;
        int MEDIUM = 2;
        int LARGE = 3;
        int FULLSCREEN = 4;
    }

    @VisibleForTesting
    WindowMagnificationSettings(Context context, WindowMagnificationSettingsCallback callback,
            SfVsyncFrameCallbackProvider sfVsyncFrameProvider, SecureSettings secureSettings) {
        mContext = context;
        mAccessibilityManager = mContext.getSystemService(AccessibilityManager.class);
        mWindowManager = mContext.getSystemService(WindowManager.class);
        mSfVsyncFrameProvider = sfVsyncFrameProvider;
        mCallback = callback;
        mSecureSettings = secureSettings;

        mAllowDiagonalScrolling = mSecureSettings.getIntForUser(
                Settings.Secure.ACCESSIBILITY_ALLOW_DIAGONAL_SCROLLING, 1,
                UserHandle.USER_CURRENT) == 1;

        mParams = createLayoutParams(context);
        mWindowInsetChangeRunnable = this::onWindowInsetChanged;

        inflateView();

        mGestureDetector = new MagnificationGestureDetector(context,
                context.getMainThreadHandler(), this);

        mMagnificationCapabilityObserver = new ContentObserver(
                mContext.getMainThreadHandler()) {
            @Override
            public void onChange(boolean selfChange) {
                mSettingView.post(() -> {
                    updateUIControlsIfNeeded();
                });
            }
        };
    }

    private class ZoomSeekbarChangeListener implements
            SeekBarWithIconButtonsView.OnSeekBarWithIconButtonsChangeListener {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            // Notify the service to update the magnifier scale only when the progress changed is
            // triggered by user interaction on seekbar
            if (fromUser) {
                final float scale = transformProgressToScale(progress);
                // We don't need to update the persisted scale when the seekbar progress is
                // changing. The update should be triggered when the changing is ended.
                mCallback.onMagnifierScale(scale, /* updatePersistence= */ false);
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            // Do nothing
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            // Do nothing
        }

        @Override
        public void onUserInteractionFinalized(SeekBar seekBar, @ControlUnitType int control) {
            // Update the Settings persisted scale only when user interaction with seekbar ends
            final int progress = seekBar.getProgress();
            final float scale = transformProgressToScale(progress);
            mCallback.onMagnifierScale(scale, /* updatePersistence= */ true);
        }

        private float transformProgressToScale(float progress) {
            return (progress / (float) mSeekBarMagnitude) + SCALE_MIN_VALUE;
        }
    }

    private final AccessibilityDelegate mPanelDelegate = new AccessibilityDelegate() {
        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);

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
            if (action == R.id.accessibility_action_move_up) {
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
            } else if (id == R.id.magnifier_full_button) {
                setMagnifierSize(MagnificationSize.FULLSCREEN);
            } else if (id == R.id.magnifier_edit_button) {
                editMagnifierSizeMode(true);
            } else if (id == R.id.magnifier_done_button) {
                hideSettingPanel();
            }
        }
    };

    @Override
    public boolean onSingleTap(View view) {
        mSingleTapDetected = true;
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

        // Unregister observer before removing view
        mSecureSettings.unregisterContentObserver(mMagnificationCapabilityObserver);
        mWindowManager.removeView(mSettingView);
        mIsVisible = false;
        if (resetPosition) {
            mParams.x = 0;
            mParams.y = 0;
        }

        mContext.unregisterReceiver(mScreenOffReceiver);
        mCallback.onSettingsPanelVisibilityChanged(/* shown= */ false);
    }

    public void toggleSettingsPanelVisibility() {
        if (!mIsVisible) {
            showSettingPanel();
        } else {
            hideSettingPanel();
        }
    }

    public void showSettingPanel() {
        showSettingPanel(true);
    }

    public boolean isSettingPanelShowing() {
        return mIsVisible;
    }

    public void setScaleSeekbar(float scale) {
        int index = (int) ((scale - SCALE_MIN_VALUE) * mSeekBarMagnitude);
        if (index < 0) {
            index = 0;
        } else if (index > mZoomSeekbar.getMax()) {
            index = mZoomSeekbar.getMax();
        }
        mZoomSeekbar.setProgress(index);
    }

    private void transitToMagnificationMode(int mode) {
        mCallback.onModeSwitch(mode);
    }

    /**
     * Shows the panel for magnification settings.
     * When the panel is going to be visible by calling this method, the layout position can be
     * reset depending on the flag.
     *
     * @param resetPosition if the panel position needs to be reset
     */
    private void showSettingPanel(boolean resetPosition) {
        if (!mIsVisible) {
            updateUIControlsIfNeeded();
            setScaleSeekbar(getMagnificationScale());
            if (resetPosition) {
                mDraggableWindowBounds.set(getDraggableWindowBounds());
                mParams.x = mDraggableWindowBounds.right;
                mParams.y = mDraggableWindowBounds.bottom;
            }

            mWindowManager.addView(mSettingView, mParams);

            mSecureSettings.registerContentObserverForUser(
                    Settings.Secure.ACCESSIBILITY_MAGNIFICATION_CAPABILITY,
                    mMagnificationCapabilityObserver,
                    UserHandle.USER_CURRENT);

            // Exclude magnification switch button from system gesture area.
            setSystemGestureExclusion();
            mIsVisible = true;
            mCallback.onSettingsPanelVisibilityChanged(/* shown= */ true);

            if (resetPosition) {
                // We could not put focus on the settings panel automatically
                // since it is an inactive window. Therefore, we announce the existence of
                // magnification settings for accessibility when it is opened.
                mSettingView.announceForAccessibility(
                        mContext.getResources().getString(
                                R.string.accessibility_magnification_settings_panel_description));
            }
        }
        mContext.registerReceiver(mScreenOffReceiver, new IntentFilter(Intent.ACTION_SCREEN_OFF));
    }

    private int getMagnificationMode() {
        // If current capability is window mode, we would like the default value of the mode to
        // be WINDOW, otherwise, the default value would be FULLSCREEN.
        int defaultValue =
                (getMagnificationCapability() == ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW)
                        ? ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW
                        : ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;

        return mSecureSettings.getIntForUser(
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE,
                defaultValue,
                UserHandle.USER_CURRENT);
    }

    private int getMagnificationCapability() {
        return mSecureSettings.getIntForUser(
                Settings.Secure.ACCESSIBILITY_MAGNIFICATION_CAPABILITY,
                ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN,
                UserHandle.USER_CURRENT);
    }

    @VisibleForTesting
    boolean isDiagonalScrollingEnabled() {
        return mAllowDiagonalScrolling;
    }

    /**
     * Only called from outside to notify the controlling magnifier scale changed
     *
     * @param scale The new controlling magnifier scale
     */
    public void setMagnificationScale(float scale) {
        mScale = scale;

        if (isSettingPanelShowing()) {
            setScaleSeekbar(scale);
        }
    }

    private float getMagnificationScale() {
        return mScale;
    }

    private void updateUIControlsIfNeeded() {
        int capability = getMagnificationCapability();
        int selectedButtonIndex = mLastSelectedButtonIndex;
        switch (capability) {
            case ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW:
                mEditButton.setVisibility(View.VISIBLE);
                mAllowDiagonalScrollingView.setVisibility(View.VISIBLE);
                mFullScreenButton.setVisibility(View.GONE);
                if (selectedButtonIndex == MagnificationSize.FULLSCREEN) {
                    selectedButtonIndex = MagnificationSize.NONE;
                }
                break;

            case ACCESSIBILITY_MAGNIFICATION_MODE_ALL:
                int mode = getMagnificationMode();
                mFullScreenButton.setVisibility(View.VISIBLE);
                if (mode == ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN) {
                    // set the edit button visibility to View.INVISIBLE to keep the height, to
                    // prevent the size title from too close to the size buttons
                    mEditButton.setVisibility(View.INVISIBLE);
                    mAllowDiagonalScrollingView.setVisibility(View.GONE);
                    // force the fullscreen button showing
                    selectedButtonIndex = MagnificationSize.FULLSCREEN;
                } else { // mode = ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW
                    mEditButton.setVisibility(View.VISIBLE);
                    mAllowDiagonalScrollingView.setVisibility(View.VISIBLE);
                }
                break;

            case ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN:
                // We will never fall into this case since we never show settings panel when
                // capability equals to ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN.
                // Currently, the case follows the UI controls when capability equals to
                // ACCESSIBILITY_MAGNIFICATION_MODE_ALL and mode equals to
                // ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN, but we could also consider to
                // remove the whole icon button selections int the future since they are no use
                // for fullscreen only capability.

                mFullScreenButton.setVisibility(View.VISIBLE);
                // set the edit button visibility to View.INVISIBLE to keep the height, to
                // prevent the size title from too close to the size buttons
                mEditButton.setVisibility(View.INVISIBLE);
                mAllowDiagonalScrollingView.setVisibility(View.GONE);
                // force the fullscreen button showing
                selectedButtonIndex = MagnificationSize.FULLSCREEN;
                break;

            default:
                break;
        }

        updateSelectedButton(selectedButtonIndex);
        mSettingView.requestLayout();
    }

    private final BroadcastReceiver mScreenOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            hideSettingPanel();
        }
    };

    void inflateView() {
        mSettingView = (LinearLayout) View.inflate(mContext,
                R.layout.window_magnification_settings_view, null);

        mSettingView.setFocusable(true);
        mSettingView.setFocusableInTouchMode(true);
        mSettingView.setOnTouchListener(this::onTouch);

        mSettingView.setAccessibilityDelegate(mPanelDelegate);

        mPanelView = mSettingView.findViewById(R.id.magnifier_panel_view);
        mSmallButton = mSettingView.findViewById(R.id.magnifier_small_button);
        mMediumButton = mSettingView.findViewById(R.id.magnifier_medium_button);
        mLargeButton = mSettingView.findViewById(R.id.magnifier_large_button);
        mDoneButton = mSettingView.findViewById(R.id.magnifier_done_button);
        mEditButton = mSettingView.findViewById(R.id.magnifier_edit_button);
        mFullScreenButton = mSettingView.findViewById(R.id.magnifier_full_button);
        mAllowDiagonalScrollingTitle =
                mSettingView.findViewById(R.id.magnifier_horizontal_lock_title);

        mZoomSeekbar = mSettingView.findViewById(R.id.magnifier_zoom_slider);
        mZoomSeekbar.setMax((int) (mZoomSeekbar.getChangeMagnitude()
                * (SCALE_MAX_VALUE - SCALE_MIN_VALUE)));
        mSeekBarMagnitude = mZoomSeekbar.getChangeMagnitude();
        setScaleSeekbar(mScale);
        mZoomSeekbar.setOnSeekBarWithIconButtonsChangeListener(new ZoomSeekbarChangeListener());

        mAllowDiagonalScrollingView =
                (LinearLayout) mSettingView.findViewById(R.id.magnifier_horizontal_lock_view);
        mAllowDiagonalScrollingSwitch =
                (Switch) mSettingView.findViewById(R.id.magnifier_horizontal_lock_switch);
        mAllowDiagonalScrollingSwitch.setChecked(mAllowDiagonalScrolling);
        mAllowDiagonalScrollingSwitch.setOnCheckedChangeListener((view, checked) -> {
            toggleDiagonalScrolling();
        });

        mSmallButton.setOnClickListener(mButtonClickListener);
        mMediumButton.setOnClickListener(mButtonClickListener);
        mLargeButton.setOnClickListener(mButtonClickListener);
        mDoneButton.setOnClickListener(mButtonClickListener);
        mFullScreenButton.setOnClickListener(mButtonClickListener);
        mEditButton.setOnClickListener(mButtonClickListener);
        mAllowDiagonalScrollingTitle.setSelected(true);

        mSettingView.setOnApplyWindowInsetsListener((v, insets) -> {
            // Adds a pending post check to avoiding redundant calculation because this callback
            // is sent frequently when the switch icon window dragged by the users.
            if (mSettingView.isAttachedToWindow()
                    && !mSettingView.getHandler().hasCallbacks(mWindowInsetChangeRunnable)) {
                mSettingView.getHandler().post(mWindowInsetChangeRunnable);
            }
            return v.onApplyWindowInsets(insets);
        });

        updateSelectedButton(mLastSelectedButtonIndex);
    }

    void onConfigurationChanged(int configDiff) {
        if ((configDiff & ActivityInfo.CONFIG_UI_MODE) != 0
                || (configDiff & ActivityInfo.CONFIG_ASSETS_PATHS) != 0
                || (configDiff & ActivityInfo.CONFIG_FONT_SCALE) != 0
                || (configDiff & ActivityInfo.CONFIG_LOCALE) != 0
                || (configDiff & ActivityInfo.CONFIG_DENSITY) != 0) {
            // We listen to following config changes to trigger layout inflation:
            // CONFIG_UI_MODE: theme change
            // CONFIG_ASSETS_PATHS: wallpaper change
            // CONFIG_FONT_SCALE: font size change
            // CONFIG_LOCALE: language change
            // CONFIG_DENSITY: display size change
            mParams.accessibilityTitle = getAccessibilityWindowTitle(mContext);

            boolean showSettingPanelAfterConfigChange = mIsVisible;
            hideSettingPanel(/* resetPosition= */ false);
            inflateView();
            if (showSettingPanelAfterConfigChange) {
                showSettingPanel(/* resetPosition= */ false);
            }
            return;
        }

        if ((configDiff & ActivityInfo.CONFIG_ORIENTATION) != 0
                || (configDiff & ActivityInfo.CONFIG_SCREEN_SIZE) != 0) {
            mDraggableWindowBounds.set(getDraggableWindowBounds());
            // reset the panel position to the right-bottom corner
            mParams.x = mDraggableWindowBounds.right;
            mParams.y = mDraggableWindowBounds.bottom;
            updateButtonViewLayoutIfNeeded();
        }
    }

    private void onWindowInsetChanged() {
        final Rect newBounds = getDraggableWindowBounds();
        if (mDraggableWindowBounds.equals(newBounds)) {
            return;
        }
        mDraggableWindowBounds.set(newBounds);
    }

    @VisibleForTesting
    void updateButtonViewLayoutIfNeeded() {
        if (mIsVisible) {
            mParams.x = MathUtils.constrain(mParams.x, mDraggableWindowBounds.left,
                    mDraggableWindowBounds.right);
            mParams.y = MathUtils.constrain(mParams.y, mDraggableWindowBounds.top,
                    mDraggableWindowBounds.bottom);
            mWindowManager.updateViewLayout(mSettingView, mParams);
        }
    }

    public void editMagnifierSizeMode(boolean enable) {
        setEditMagnifierSizeMode(enable);
        updateSelectedButton(MagnificationSize.NONE);
        hideSettingPanel();
    }

    private void setMagnifierSize(@MagnificationSize int index) {
        if (index == MagnificationSize.FULLSCREEN) {
            // transit to fullscreen magnifier if needed
            transitToMagnificationMode(ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN);
        } else if (index != MagnificationSize.NONE) {
            // update the window magnifier size
            mCallback.onSetMagnifierSize(index);
            // transit to window magnifier if needed
            transitToMagnificationMode(ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW);
        } else {
            return;
        }

        updateSelectedButton(index);
    }

    private void toggleDiagonalScrolling() {
        boolean enabled = mSecureSettings.getIntForUser(
                Settings.Secure.ACCESSIBILITY_ALLOW_DIAGONAL_SCROLLING, 1,
                UserHandle.USER_CURRENT) == 1;
        setDiagonalScrolling(!enabled);
    }

    @VisibleForTesting
    void setDiagonalScrolling(boolean enabled) {
        mSecureSettings.putIntForUser(
                Settings.Secure.ACCESSIBILITY_ALLOW_DIAGONAL_SCROLLING, enabled ? 1 : 0,
                UserHandle.USER_CURRENT);

        mCallback.onSetDiagonalScrolling(enabled);
    }

    private void setEditMagnifierSizeMode(boolean enable) {
        mCallback.onEditMagnifierSizeMode(enable);
    }

    private static LayoutParams createLayoutParams(Context context) {
        final LayoutParams params = new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSPARENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.accessibilityTitle = getAccessibilityWindowTitle(context);
        params.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        return params;
    }

    private Rect getDraggableWindowBounds() {
        final WindowMetrics windowMetrics = mWindowManager.getCurrentWindowMetrics();
        final Insets windowInsets = windowMetrics.getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        // re-measure the settings panel view so that we can get the correct view size to inset
        int unspecificSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        mSettingView.measure(unspecificSpec, unspecificSpec);

        final Rect boundRect = new Rect(windowMetrics.getBounds());
        boundRect.offsetTo(0, 0);
        boundRect.inset(0, 0, mSettingView.getMeasuredWidth(), mSettingView.getMeasuredHeight());
        boundRect.inset(windowInsets);
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

    private void updateSelectedButton(@MagnificationSize int index) {
        // Clear the state of last selected button
        if (mLastSelectedButtonIndex == MagnificationSize.SMALL) {
            mSmallButton.setSelected(false);
        } else if (mLastSelectedButtonIndex == MagnificationSize.MEDIUM) {
            mMediumButton.setSelected(false);
        } else if (mLastSelectedButtonIndex == MagnificationSize.LARGE) {
            mLargeButton.setSelected(false);
        } else if (mLastSelectedButtonIndex == MagnificationSize.FULLSCREEN) {
            mFullScreenButton.setSelected(false);
        }

        // Set the state for selected button
        if (index == MagnificationSize.SMALL) {
            mSmallButton.setSelected(true);
        } else if (index == MagnificationSize.MEDIUM) {
            mMediumButton.setSelected(true);
        } else if (index == MagnificationSize.LARGE) {
            mLargeButton.setSelected(true);
        } else if (index == MagnificationSize.FULLSCREEN) {
            mFullScreenButton.setSelected(true);
        }

        mLastSelectedButtonIndex = index;
    }
}
