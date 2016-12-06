/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.volume;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.animation.ObjectAnimator;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings.Global;
import android.transition.AutoTransition;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.AccessibilityDelegate;
import android.view.View.OnAttachStateChangeListener;
import android.view.View.OnClickListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityManager.AccessibilityStateChangeListener;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;

import com.android.settingslib.Utils;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerZenModePanel;
import com.android.systemui.volume.VolumeDialogController.State;
import com.android.systemui.volume.VolumeDialogController.StreamState;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import static android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK;
import static android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC;

/**
 * Visual presentation of the volume dialog.
 *
 * A client of VolumeDialogController and its state model.
 *
 * Methods ending in "H" must be called on the (ui) handler.
 */
public class VolumeDialog implements TunerService.Tunable {
    private static final String TAG = Util.logTag(VolumeDialog.class);

    public static final String SHOW_FULL_ZEN = "sysui_show_full_zen";

    private static final long USER_ATTEMPT_GRACE_PERIOD = 1000;
    private static final int UPDATE_ANIMATION_DURATION = 80;

    private final Context mContext;
    private final H mHandler = new H();
    private final VolumeDialogController mController;

    private Window mWindow;
    private CustomDialog mDialog;
    private ViewGroup mDialogView;
    private ViewGroup mDialogRowsView;
    private ViewGroup mDialogContentView;
    private ImageButton mExpandButton;
    private final List<VolumeRow> mRows = new ArrayList<>();
    private SpTexts mSpTexts;
    private final SparseBooleanArray mDynamic = new SparseBooleanArray();
    private final KeyguardManager mKeyguard;
    private final AudioManager mAudioManager;
    private final AccessibilityManager mAccessibilityMgr;
    private int mExpandButtonAnimationDuration;
    private ZenFooter mZenFooter;
    private final Object mSafetyWarningLock = new Object();
    private final Accessibility mAccessibility = new Accessibility();
    private final ColorStateList mActiveSliderTint;
    private final ColorStateList mInactiveSliderTint;
    private VolumeDialogMotion mMotion;
    private final int mWindowType;
    private final ZenModeController mZenModeController;

    private boolean mShowing;
    private boolean mExpanded;

    private int mActiveStream;
    private boolean mShowHeaders = VolumePrefs.DEFAULT_SHOW_HEADERS;
    private boolean mAutomute = VolumePrefs.DEFAULT_ENABLE_AUTOMUTE;
    private boolean mSilentMode = VolumePrefs.DEFAULT_ENABLE_SILENT_MODE;
    private State mState;
    private boolean mExpandButtonAnimationRunning;
    private SafetyWarningDialog mSafetyWarning;
    private Callback mCallback;
    private boolean mPendingStateChanged;
    private boolean mPendingRecheckAll;
    private long mCollapseTime;
    private boolean mHovering = false;
    private int mDensity;

    private boolean mShowFullZen;
    private TunerZenModePanel mZenPanel;

    public VolumeDialog(Context context, int windowType, VolumeDialogController controller,
            ZenModeController zenModeController, Callback callback) {
        mContext = context;
        mController = controller;
        mCallback = callback;
        mWindowType = windowType;
        mZenModeController = zenModeController;
        mKeyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mAccessibilityMgr =
                (AccessibilityManager) mContext.getSystemService(Context.ACCESSIBILITY_SERVICE);
        mActiveSliderTint = ColorStateList.valueOf(Utils.getColorAccent(mContext));
        mInactiveSliderTint = loadColorStateList(R.color.volume_slider_inactive);

        initDialog();

        mAccessibility.init();

        controller.addCallback(mControllerCallbackH, mHandler);
        controller.getState();
        TunerService.get(mContext).addTunable(this, SHOW_FULL_ZEN);

        final Configuration currentConfig = mContext.getResources().getConfiguration();
        mDensity = currentConfig.densityDpi;
    }

    private void initDialog() {
        mDialog = new CustomDialog(mContext);

        mSpTexts = new SpTexts(mContext);
        mHovering = false;
        mShowing = false;
        mWindow = mDialog.getWindow();
        mWindow.requestFeature(Window.FEATURE_NO_TITLE);
        mWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        mWindow.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        mWindow.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        mDialog.setCanceledOnTouchOutside(true);
        final Resources res = mContext.getResources();
        final WindowManager.LayoutParams lp = mWindow.getAttributes();
        lp.type = mWindowType;
        lp.format = PixelFormat.TRANSLUCENT;
        lp.setTitle(VolumeDialog.class.getSimpleName());
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = res.getDimensionPixelSize(R.dimen.volume_offset_top);
        lp.gravity = Gravity.TOP;
        lp.windowAnimations = -1;
        mWindow.setAttributes(lp);
        mWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);

        mDialog.setContentView(R.layout.volume_dialog);
        mDialogView = (ViewGroup) mDialog.findViewById(R.id.volume_dialog);
        mDialogView.setOnHoverListener(new View.OnHoverListener() {
            @Override
            public boolean onHover(View v, MotionEvent event) {
                int action = event.getActionMasked();
                mHovering = (action == MotionEvent.ACTION_HOVER_ENTER)
                        || (action == MotionEvent.ACTION_HOVER_MOVE);
                rescheduleTimeoutH();
                return true;
            }
        });
        mDialogContentView = (ViewGroup) mDialog.findViewById(R.id.volume_dialog_content);
        mDialogRowsView = (ViewGroup) mDialogContentView.findViewById(R.id.volume_dialog_rows);
        mExpanded = false;
        mExpandButton = (ImageButton) mDialogView.findViewById(R.id.volume_expand_button);
        mExpandButton.setOnClickListener(mClickExpand);
        updateWindowWidthH();
        updateExpandButtonH();

        mMotion = new VolumeDialogMotion(mDialog, mDialogView, mDialogContentView, mExpandButton,
                new VolumeDialogMotion.Callback() {
                    @Override
                    public void onAnimatingChanged(boolean animating) {
                        if (animating) return;
                        if (mPendingStateChanged) {
                            mHandler.sendEmptyMessage(H.STATE_CHANGED);
                            mPendingStateChanged = false;
                        }
                        if (mPendingRecheckAll) {
                            mHandler.sendEmptyMessage(H.RECHECK_ALL);
                            mPendingRecheckAll = false;
                        }
                    }
                });

        if (mRows.isEmpty()) {
            addRow(AudioManager.STREAM_RING,
                    R.drawable.ic_volume_ringer, R.drawable.ic_volume_ringer_mute, true);
            addRow(AudioManager.STREAM_MUSIC,
                    R.drawable.ic_volume_media, R.drawable.ic_volume_media_mute, true);
            addRow(AudioManager.STREAM_ALARM,
                    R.drawable.ic_volume_alarm, R.drawable.ic_volume_alarm_mute, false);
            addRow(AudioManager.STREAM_VOICE_CALL,
                    R.drawable.ic_volume_voice, R.drawable.ic_volume_voice, false);
            addRow(AudioManager.STREAM_BLUETOOTH_SCO,
                    R.drawable.ic_volume_bt_sco, R.drawable.ic_volume_bt_sco, false);
            addRow(AudioManager.STREAM_SYSTEM,
                    R.drawable.ic_volume_system, R.drawable.ic_volume_system_mute, false);
        } else {
            addExistingRows();
        }
        mExpandButtonAnimationDuration = res.getInteger(R.integer.volume_expand_animation_duration);
        mZenFooter = (ZenFooter) mDialog.findViewById(R.id.volume_zen_footer);
        mZenFooter.init(mZenModeController);
        mZenPanel = (TunerZenModePanel) mDialog.findViewById(R.id.tuner_zen_mode_panel);
        mZenPanel.init(mZenModeController);
        mZenPanel.setCallback(mZenPanelCallback);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (SHOW_FULL_ZEN.equals(key)) {
            mShowFullZen = newValue != null && Integer.parseInt(newValue) != 0;
        }
    }

    private ColorStateList loadColorStateList(int colorResId) {
        return ColorStateList.valueOf(mContext.getColor(colorResId));
    }

    private void updateWindowWidthH() {
        final ViewGroup.LayoutParams lp = mDialogView.getLayoutParams();
        final DisplayMetrics dm = mContext.getResources().getDisplayMetrics();
        if (D.BUG) Log.d(TAG, "updateWindowWidth dm.w=" + dm.widthPixels);
        int w = dm.widthPixels;
        final int max = mContext.getResources()
                .getDimensionPixelSize(R.dimen.volume_dialog_panel_width);
        if (w > max) {
            w = max;
        }
        lp.width = w;
        mDialogView.setLayoutParams(lp);
    }

    public void setStreamImportant(int stream, boolean important) {
        mHandler.obtainMessage(H.SET_STREAM_IMPORTANT, stream, important ? 1 : 0).sendToTarget();
    }

    public void setShowHeaders(boolean showHeaders) {
        if (showHeaders == mShowHeaders) return;
        mShowHeaders = showHeaders;
        mHandler.sendEmptyMessage(H.RECHECK_ALL);
    }

    public void setAutomute(boolean automute) {
        if (mAutomute == automute) return;
        mAutomute = automute;
        mHandler.sendEmptyMessage(H.RECHECK_ALL);
    }

    public void setSilentMode(boolean silentMode) {
        if (mSilentMode == silentMode) return;
        mSilentMode = silentMode;
        mHandler.sendEmptyMessage(H.RECHECK_ALL);
    }

    private void addRow(int stream, int iconRes, int iconMuteRes, boolean important) {
        VolumeRow row = new VolumeRow();
        initRow(row, stream, iconRes, iconMuteRes, important);
        mDialogRowsView.addView(row.view);
        mRows.add(row);
    }

    private void addExistingRows() {
        int N = mRows.size();
        for (int i = 0; i < N; i++) {
            final VolumeRow row = mRows.get(i);
            initRow(row, row.stream, row.iconRes, row.iconMuteRes, row.important);
            mDialogRowsView.addView(row.view);
        }
    }


    private boolean isAttached() {
        return mDialogContentView != null && mDialogContentView.isAttachedToWindow();
    }

    private VolumeRow getActiveRow() {
        for (VolumeRow row : mRows) {
            if (row.stream == mActiveStream) {
                return row;
            }
        }
        return mRows.get(0);
    }

    private VolumeRow findRow(int stream) {
        for (VolumeRow row : mRows) {
            if (row.stream == stream) return row;
        }
        return null;
    }

    public void dump(PrintWriter writer) {
        writer.println(VolumeDialog.class.getSimpleName() + " state:");
        writer.print("  mShowing: "); writer.println(mShowing);
        writer.print("  mExpanded: "); writer.println(mExpanded);
        writer.print("  mExpandButtonAnimationRunning: ");
        writer.println(mExpandButtonAnimationRunning);
        writer.print("  mActiveStream: "); writer.println(mActiveStream);
        writer.print("  mDynamic: "); writer.println(mDynamic);
        writer.print("  mShowHeaders: "); writer.println(mShowHeaders);
        writer.print("  mAutomute: "); writer.println(mAutomute);
        writer.print("  mSilentMode: "); writer.println(mSilentMode);
        writer.print("  mCollapseTime: "); writer.println(mCollapseTime);
        writer.print("  mAccessibility.mFeedbackEnabled: ");
        writer.println(mAccessibility.mFeedbackEnabled);
    }

    private static int getImpliedLevel(SeekBar seekBar, int progress) {
        final int m = seekBar.getMax();
        final int n = m / 100 - 1;
        final int level = progress == 0 ? 0
                : progress == m ? (m / 100) : (1 + (int)((progress / (float) m) * n));
        return level;
    }

    @SuppressLint("InflateParams")
    private void initRow(final VolumeRow row, final int stream, int iconRes, int iconMuteRes,
            boolean important) {
        row.stream = stream;
        row.iconRes = iconRes;
        row.iconMuteRes = iconMuteRes;
        row.important = important;
        row.view = mDialog.getLayoutInflater().inflate(R.layout.volume_dialog_row, null);
        row.view.setId(row.stream);
        row.view.setTag(row);
        row.header = (TextView) row.view.findViewById(R.id.volume_row_header);
        row.header.setId(20 * row.stream);
        mSpTexts.add(row.header);
        row.slider = (SeekBar) row.view.findViewById(R.id.volume_row_slider);
        row.slider.setOnSeekBarChangeListener(new VolumeSeekBarChangeListener(row));
        row.anim = null;
        row.cachedShowHeaders = VolumePrefs.DEFAULT_SHOW_HEADERS;

        // forward events above the slider into the slider
        row.view.setOnTouchListener(new OnTouchListener() {
            private final Rect mSliderHitRect = new Rect();
            private boolean mDragging;

            @SuppressLint("ClickableViewAccessibility")
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                row.slider.getHitRect(mSliderHitRect);
                if (!mDragging && event.getActionMasked() == MotionEvent.ACTION_DOWN
                        && event.getY() < mSliderHitRect.top) {
                    mDragging = true;
                }
                if (mDragging) {
                    event.offsetLocation(-mSliderHitRect.left, -mSliderHitRect.top);
                    row.slider.dispatchTouchEvent(event);
                    if (event.getActionMasked() == MotionEvent.ACTION_UP
                            || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                        mDragging = false;
                    }
                    return true;
                }
                return false;
            }
        });
        row.icon = (ImageButton) row.view.findViewById(R.id.volume_row_icon);
        row.icon.setImageResource(iconRes);
        row.icon.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Events.writeEvent(mContext, Events.EVENT_ICON_CLICK, row.stream, row.iconState);
                mController.setActiveStream(row.stream);
                if (row.stream == AudioManager.STREAM_RING) {
                    final boolean hasVibrator = mController.hasVibrator();
                    if (mState.ringerModeInternal == AudioManager.RINGER_MODE_NORMAL) {
                        if (hasVibrator) {
                            mController.setRingerMode(AudioManager.RINGER_MODE_VIBRATE, false);
                        } else {
                            final boolean wasZero = row.ss.level == 0;
                            mController.setStreamVolume(stream, wasZero ? row.lastAudibleLevel : 0);
                        }
                    } else {
                        mController.setRingerMode(AudioManager.RINGER_MODE_NORMAL, false);
                        if (row.ss.level == 0) {
                            mController.setStreamVolume(stream, 1);
                        }
                    }
                } else {
                    final boolean vmute = row.ss.level == row.ss.levelMin;
                    mController.setStreamVolume(stream,
                            vmute ? row.lastAudibleLevel : row.ss.levelMin);
                }
                row.userAttempt = 0;  // reset the grace period, slider should update immediately
            }
        });
    }

    public void destroy() {
        mController.removeCallback(mControllerCallbackH);
    }

    public void show(int reason) {
        mHandler.obtainMessage(H.SHOW, reason, 0).sendToTarget();
    }

    public void dismiss(int reason) {
        mHandler.obtainMessage(H.DISMISS, reason, 0).sendToTarget();
    }

    private void showH(int reason) {
        if (D.BUG) Log.d(TAG, "showH r=" + Events.DISMISS_REASONS[reason]);
        mHandler.removeMessages(H.SHOW);
        mHandler.removeMessages(H.DISMISS);
        rescheduleTimeoutH();
        if (mShowing) return;
        mShowing = true;
        mMotion.startShow();
        Events.writeEvent(mContext, Events.EVENT_SHOW_DIALOG, reason, mKeyguard.isKeyguardLocked());
        mController.notifyVisible(true);
    }

    protected void rescheduleTimeoutH() {
        mHandler.removeMessages(H.DISMISS);
        final int timeout = computeTimeoutH();
        mHandler.sendMessageDelayed(mHandler
                .obtainMessage(H.DISMISS, Events.DISMISS_REASON_TIMEOUT, 0), timeout);
        if (D.BUG) Log.d(TAG, "rescheduleTimeout " + timeout + " " + Debug.getCaller());
        mController.userActivity();
    }

    private int computeTimeoutH() {
        if (mAccessibility.mFeedbackEnabled) return 20000;
        if (mHovering) return 16000;
        if (mSafetyWarning != null) return 5000;
        if (mExpanded || mExpandButtonAnimationRunning) return 5000;
        if (mActiveStream == AudioManager.STREAM_MUSIC) return 1500;
        return 3000;
    }

    protected void dismissH(int reason) {
        if (mMotion.isAnimating()) {
            return;
        }
        mHandler.removeMessages(H.DISMISS);
        mHandler.removeMessages(H.SHOW);
        if (!mShowing) return;
        mShowing = false;
        mMotion.startDismiss(new Runnable() {
            @Override
            public void run() {
                updateExpandedH(false /* expanding */, true /* dismissing */);
            }
        });
        if (mAccessibilityMgr.isEnabled()) {
            AccessibilityEvent event =
                    AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            event.setPackageName(mContext.getPackageName());
            event.setClassName(CustomDialog.class.getSuperclass().getName());
            event.getText().add(mContext.getString(
                    R.string.volume_dialog_accessibility_dismissed_message));
            mAccessibilityMgr.sendAccessibilityEvent(event);
        }
        Events.writeEvent(mContext, Events.EVENT_DISMISS_DIALOG, reason);
        mController.notifyVisible(false);
        synchronized (mSafetyWarningLock) {
            if (mSafetyWarning != null) {
                if (D.BUG) Log.d(TAG, "SafetyWarning dismissed");
                mSafetyWarning.dismiss();
            }
        }
    }

    private void updateDialogBottomMarginH() {
        final long diff = System.currentTimeMillis() - mCollapseTime;
        final boolean collapsing = mCollapseTime != 0 && diff < getConservativeCollapseDuration();
        final ViewGroup.MarginLayoutParams mlp = (MarginLayoutParams) mDialogView.getLayoutParams();
        final int bottomMargin = collapsing ? mDialogContentView.getHeight() :
                mContext.getResources().getDimensionPixelSize(R.dimen.volume_dialog_margin_bottom);
        if (bottomMargin != mlp.bottomMargin) {
            if (D.BUG) Log.d(TAG, "bottomMargin " + mlp.bottomMargin + " -> " + bottomMargin);
            mlp.bottomMargin = bottomMargin;
            mDialogView.setLayoutParams(mlp);
        }
    }

    private long getConservativeCollapseDuration() {
        return mExpandButtonAnimationDuration * 3;
    }

    private void prepareForCollapse() {
        mHandler.removeMessages(H.UPDATE_BOTTOM_MARGIN);
        mCollapseTime = System.currentTimeMillis();
        updateDialogBottomMarginH();
        mHandler.sendEmptyMessageDelayed(H.UPDATE_BOTTOM_MARGIN, getConservativeCollapseDuration());
    }

    private void updateExpandedH(final boolean expanded, final boolean dismissing) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        mExpandButtonAnimationRunning = isAttached();
        if (D.BUG) Log.d(TAG, "updateExpandedH " + expanded);
        updateExpandButtonH();
        updateFooterH();
        TransitionManager.endTransitions(mDialogView);
        final VolumeRow activeRow = getActiveRow();
        if (!dismissing) {
            mWindow.setLayout(mWindow.getAttributes().width, ViewGroup.LayoutParams.MATCH_PARENT);
            AutoTransition transition = new AutoTransition();
            transition.setDuration(mExpandButtonAnimationDuration);
            transition.setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN);
            transition.addListener(new Transition.TransitionListener() {
                @Override
                public void onTransitionStart(Transition transition) {
                }

                @Override
                public void onTransitionEnd(Transition transition) {
                    mWindow.setLayout(
                            mWindow.getAttributes().width, ViewGroup.LayoutParams.WRAP_CONTENT);
                }

                @Override
                public void onTransitionCancel(Transition transition) {
                }

                @Override
                public void onTransitionPause(Transition transition) {
                    mWindow.setLayout(
                            mWindow.getAttributes().width, ViewGroup.LayoutParams.WRAP_CONTENT);
                }

                @Override
                public void onTransitionResume(Transition transition) {
                }
            });
            TransitionManager.beginDelayedTransition(mDialogView, transition);
        }
        updateRowsH(activeRow);
        rescheduleTimeoutH();
    }

    private void updateExpandButtonH() {
        if (D.BUG) Log.d(TAG, "updateExpandButtonH");
        mExpandButton.setClickable(!mExpandButtonAnimationRunning);
        if (!(mExpandButtonAnimationRunning && isAttached())) {
            final int res = mExpanded ? R.drawable.ic_volume_collapse_animation
                    : R.drawable.ic_volume_expand_animation;
            if (hasTouchFeature()) {
                mExpandButton.setImageResource(res);
            } else {
                // if there is no touch feature, show the volume ringer instead
                mExpandButton.setImageResource(R.drawable.ic_volume_ringer);
                mExpandButton.setBackgroundResource(0);  // remove gray background emphasis
            }
            mExpandButton.setContentDescription(mContext.getString(mExpanded ?
                    R.string.accessibility_volume_collapse : R.string.accessibility_volume_expand));
        }
        if (mExpandButtonAnimationRunning) {
            final Drawable d = mExpandButton.getDrawable();
            if (d instanceof AnimatedVectorDrawable) {
                // workaround to reset drawable
                final AnimatedVectorDrawable avd = (AnimatedVectorDrawable) d.getConstantState()
                        .newDrawable();
                mExpandButton.setImageDrawable(avd);
                avd.start();
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mExpandButtonAnimationRunning = false;
                        updateExpandButtonH();
                        rescheduleTimeoutH();
                    }
                }, mExpandButtonAnimationDuration);
            }
        }
    }

    private boolean shouldBeVisibleH(VolumeRow row, boolean isActive) {
        return mExpanded && row.view.getVisibility() == View.VISIBLE
                || (mExpanded && (row.important || isActive))
                || !mExpanded && isActive;
    }

    private void updateRowsH(final VolumeRow activeRow) {
        if (D.BUG) Log.d(TAG, "updateRowsH");
        if (!mShowing) {
            trimObsoleteH();
        }
        Util.setVisOrGone(mDialogRowsView.findViewById(R.id.spacer), mExpanded);
        // apply changes to all rows
        for (final VolumeRow row : mRows) {
            final boolean isActive = row == activeRow;
            final boolean shouldBeVisible = shouldBeVisibleH(row, isActive);
            Util.setVisOrGone(row.view, shouldBeVisible);
            if (row.view.isShown()) {
                updateVolumeRowHeaderVisibleH(row);
                updateVolumeRowSliderTintH(row, isActive);
            }
        }

    }

    private void trimObsoleteH() {
        if (D.BUG) Log.d(TAG, "trimObsoleteH");
        for (int i = mRows.size() - 1; i >= 0; i--) {
            final VolumeRow row = mRows.get(i);
            if (row.ss == null || !row.ss.dynamic) continue;
            if (!mDynamic.get(row.stream)) {
                mRows.remove(i);
                mDialogRowsView.removeView(row.view);
            }
        }
    }

    private void onStateChangedH(State state) {
        final boolean animating = mMotion.isAnimating();
        if (D.BUG) Log.d(TAG, "onStateChangedH animating=" + animating);
        mState = state;
        if (animating) {
            mPendingStateChanged = true;
            return;
        }
        mDynamic.clear();
        // add any new dynamic rows
        for (int i = 0; i < state.states.size(); i++) {
            final int stream = state.states.keyAt(i);
            final StreamState ss = state.states.valueAt(i);
            if (!ss.dynamic) continue;
            mDynamic.put(stream, true);
            if (findRow(stream) == null) {
                addRow(stream, R.drawable.ic_volume_remote, R.drawable.ic_volume_remote_mute, true);
            }
        }

        if (mActiveStream != state.activeStream) {
            mActiveStream = state.activeStream;
            updateRowsH(getActiveRow());
            rescheduleTimeoutH();
        }
        for (VolumeRow row : mRows) {
            updateVolumeRowH(row);
        }
        updateFooterH();
    }

    private void updateFooterH() {
        if (D.BUG) Log.d(TAG, "updateFooterH");
        final boolean wasVisible = mZenFooter.getVisibility() == View.VISIBLE;
        final boolean visible = mState.zenMode != Global.ZEN_MODE_OFF
                && (mAudioManager.isStreamAffectedByRingerMode(mActiveStream) || mExpanded)
                && !mZenPanel.isEditing();
        if (wasVisible != visible && !visible) {
            prepareForCollapse();
        }
        Util.setVisOrGone(mZenFooter, visible);
        mZenFooter.update();

        final boolean fullWasVisible = mZenPanel.getVisibility() == View.VISIBLE;
        final boolean fullVisible = mShowFullZen && !visible;
        if (fullWasVisible != fullVisible && !fullVisible) {
            prepareForCollapse();
        }
        Util.setVisOrGone(mZenPanel, fullVisible);
        if (fullVisible) {
            mZenPanel.setZenState(mState.zenMode);
            mZenPanel.setDoneListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    prepareForCollapse();
                    mHandler.sendEmptyMessage(H.UPDATE_FOOTER);
                }
            });
        }
    }

    private void updateVolumeRowH(VolumeRow row) {
        if (D.BUG) Log.d(TAG, "updateVolumeRowH s=" + row.stream);
        if (mState == null) return;
        final StreamState ss = mState.states.get(row.stream);
        if (ss == null) return;
        row.ss = ss;
        if (ss.level > 0) {
            row.lastAudibleLevel = ss.level;
        }
        if (ss.level == row.requestedLevel) {
            row.requestedLevel = -1;
        }
        final boolean isRingStream = row.stream == AudioManager.STREAM_RING;
        final boolean isSystemStream = row.stream == AudioManager.STREAM_SYSTEM;
        final boolean isAlarmStream = row.stream == AudioManager.STREAM_ALARM;
        final boolean isMusicStream = row.stream == AudioManager.STREAM_MUSIC;
        final boolean isRingVibrate = isRingStream
                && mState.ringerModeInternal == AudioManager.RINGER_MODE_VIBRATE;
        final boolean isRingSilent = isRingStream
                && mState.ringerModeInternal == AudioManager.RINGER_MODE_SILENT;
        final boolean isZenAlarms = mState.zenMode == Global.ZEN_MODE_ALARMS;
        final boolean isZenNone = mState.zenMode == Global.ZEN_MODE_NO_INTERRUPTIONS;
        final boolean zenMuted = isZenAlarms ? (isRingStream || isSystemStream)
                : isZenNone ? (isRingStream || isSystemStream || isAlarmStream || isMusicStream)
                : false;

        // update slider max
        final int max = ss.levelMax * 100;
        if (max != row.slider.getMax()) {
            row.slider.setMax(max);
        }

        // update header visible
        updateVolumeRowHeaderVisibleH(row);

        // update header text
        Util.setText(row.header, ss.name);

        // update icon
        final boolean iconEnabled = (mAutomute || ss.muteSupported) && !zenMuted;
        row.icon.setEnabled(iconEnabled);
        row.icon.setAlpha(iconEnabled ? 1 : 0.5f);
        final int iconRes =
                isRingVibrate ? R.drawable.ic_volume_ringer_vibrate
                : isRingSilent || zenMuted ? row.cachedIconRes
                : ss.routedToBluetooth ?
                        (ss.muted ? R.drawable.ic_volume_media_bt_mute
                                : R.drawable.ic_volume_media_bt)
                : mAutomute && ss.level == 0 ? row.iconMuteRes
                : (ss.muted ? row.iconMuteRes : row.iconRes);
        if (iconRes != row.cachedIconRes) {
            if (row.cachedIconRes != 0 && isRingVibrate) {
                mController.vibrate();
            }
            row.cachedIconRes = iconRes;
            row.icon.setImageResource(iconRes);
        }
        row.iconState =
                iconRes == R.drawable.ic_volume_ringer_vibrate ? Events.ICON_STATE_VIBRATE
                : (iconRes == R.drawable.ic_volume_media_bt_mute || iconRes == row.iconMuteRes)
                        ? Events.ICON_STATE_MUTE
                : (iconRes == R.drawable.ic_volume_media_bt || iconRes == row.iconRes)
                        ? Events.ICON_STATE_UNMUTE
                : Events.ICON_STATE_UNKNOWN;
        if (iconEnabled) {
            if (isRingStream) {
                if (isRingVibrate) {
                    row.icon.setContentDescription(mContext.getString(
                            R.string.volume_stream_content_description_unmute,
                            ss.name));
                } else {
                    if (mController.hasVibrator()) {
                        row.icon.setContentDescription(mContext.getString(
                                R.string.volume_stream_content_description_vibrate,
                                ss.name));
                    } else {
                        row.icon.setContentDescription(mContext.getString(
                                R.string.volume_stream_content_description_mute,
                                ss.name));
                    }
                }
            } else {
                if (ss.muted || mAutomute && ss.level == 0) {
                   row.icon.setContentDescription(mContext.getString(
                           R.string.volume_stream_content_description_unmute,
                           ss.name));
                } else {
                    row.icon.setContentDescription(mContext.getString(
                            R.string.volume_stream_content_description_mute,
                            ss.name));
                }
            }
        } else {
            row.icon.setContentDescription(ss.name);
        }

        // update slider
        final boolean enableSlider = !zenMuted;
        final int vlevel = row.ss.muted && (isRingVibrate || !isRingStream && !zenMuted) ? 0
                : row.ss.level;
        updateVolumeRowSliderH(row, enableSlider, vlevel);
    }

    private void updateVolumeRowHeaderVisibleH(VolumeRow row) {
        final boolean dynamic = row.ss != null && row.ss.dynamic;
        final boolean showHeaders = mExpanded && (mShowHeaders || dynamic);
        if (row.cachedShowHeaders != showHeaders) {
            row.cachedShowHeaders = showHeaders;
            Util.setVisOrGone(row.header, showHeaders);
        }
    }

    private void updateVolumeRowSliderTintH(VolumeRow row, boolean isActive) {
        if (isActive && mExpanded) {
            row.slider.requestFocus();
        }
        final ColorStateList tint = isActive && row.slider.isEnabled() ? mActiveSliderTint
                : mInactiveSliderTint;
        if (tint == row.cachedSliderTint) return;
        row.cachedSliderTint = tint;
        row.slider.setProgressTintList(tint);
        row.slider.setThumbTintList(tint);
    }

    private void updateVolumeRowSliderH(VolumeRow row, boolean enable, int vlevel) {
        row.slider.setEnabled(enable);
        updateVolumeRowSliderTintH(row, row.stream == mActiveStream);
        if (row.tracking) {
            return;  // don't update if user is sliding
        }
        final int progress = row.slider.getProgress();
        final int level = getImpliedLevel(row.slider, progress);
        final boolean rowVisible = row.view.getVisibility() == View.VISIBLE;
        final boolean inGracePeriod = (SystemClock.uptimeMillis() - row.userAttempt)
                < USER_ATTEMPT_GRACE_PERIOD;
        mHandler.removeMessages(H.RECHECK, row);
        if (mShowing && rowVisible && inGracePeriod) {
            if (D.BUG) Log.d(TAG, "inGracePeriod");
            mHandler.sendMessageAtTime(mHandler.obtainMessage(H.RECHECK, row),
                    row.userAttempt + USER_ATTEMPT_GRACE_PERIOD);
            return;  // don't update if visible and in grace period
        }
        if (vlevel == level) {
            if (mShowing && rowVisible) {
                return;  // don't clamp if visible
            }
        }
        final int newProgress = vlevel * 100;
        if (progress != newProgress) {
            if (mShowing && rowVisible) {
                // animate!
                if (row.anim != null && row.anim.isRunning()
                        && row.animTargetProgress == newProgress) {
                    return;  // already animating to the target progress
                }
                // start/update animation
                if (row.anim == null) {
                    row.anim = ObjectAnimator.ofInt(row.slider, "progress", progress, newProgress);
                    row.anim.setInterpolator(new DecelerateInterpolator());
                } else {
                    row.anim.cancel();
                    row.anim.setIntValues(progress, newProgress);
                }
                row.animTargetProgress = newProgress;
                row.anim.setDuration(UPDATE_ANIMATION_DURATION);
                row.anim.start();
            } else {
                // update slider directly to clamped value
                if (row.anim != null) {
                    row.anim.cancel();
                }
                row.slider.setProgress(newProgress);
            }
        }
    }

    private void recheckH(VolumeRow row) {
        if (row == null) {
            if (D.BUG) Log.d(TAG, "recheckH ALL");
            trimObsoleteH();
            for (VolumeRow r : mRows) {
                updateVolumeRowH(r);
            }
        } else {
            if (D.BUG) Log.d(TAG, "recheckH " + row.stream);
            updateVolumeRowH(row);
        }
    }

    private void setStreamImportantH(int stream, boolean important) {
        for (VolumeRow row : mRows) {
            if (row.stream == stream) {
                row.important = important;
                return;
            }
        }
    }

    private void showSafetyWarningH(int flags) {
        if ((flags & (AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_SHOW_UI_WARNINGS)) != 0
                || mShowing) {
            synchronized (mSafetyWarningLock) {
                if (mSafetyWarning != null) {
                    return;
                }
                mSafetyWarning = new SafetyWarningDialog(mContext, mController.getAudioManager()) {
                    @Override
                    protected void cleanUp() {
                        synchronized (mSafetyWarningLock) {
                            mSafetyWarning = null;
                        }
                        recheckH(null);
                    }
                };
                mSafetyWarning.show();
            }
            recheckH(null);
        }
        rescheduleTimeoutH();
    }

    private boolean hasTouchFeature() {
        final PackageManager pm = mContext.getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
    }

    private final VolumeDialogController.Callbacks mControllerCallbackH
            = new VolumeDialogController.Callbacks() {
        @Override
        public void onShowRequested(int reason) {
            showH(reason);
        }

        @Override
        public void onDismissRequested(int reason) {
            dismissH(reason);
        }

        @Override
        public void onScreenOff() {
            dismissH(Events.DISMISS_REASON_SCREEN_OFF);
        }

        @Override
        public void onStateChanged(State state) {
            onStateChangedH(state);
        }

        @Override
        public void onLayoutDirectionChanged(int layoutDirection) {
            mDialogView.setLayoutDirection(layoutDirection);
        }

        @Override
        public void onConfigurationChanged() {
            Configuration newConfig = mContext.getResources().getConfiguration();
            final int density = newConfig.densityDpi;
            if (density != mDensity) {
                mDialog.dismiss();
                mZenFooter.cleanup();
                initDialog();
                mDensity = density;
            }
            updateWindowWidthH();
            mSpTexts.update();
            mZenFooter.onConfigurationChanged();
        }

        @Override
        public void onShowVibrateHint() {
            if (mSilentMode) {
                mController.setRingerMode(AudioManager.RINGER_MODE_SILENT, false);
            }
        }

        @Override
        public void onShowSilentHint() {
            if (mSilentMode) {
                mController.setRingerMode(AudioManager.RINGER_MODE_NORMAL, false);
            }
        }

        @Override
        public void onShowSafetyWarning(int flags) {
            showSafetyWarningH(flags);
        }
    };

    private final ZenModePanel.Callback mZenPanelCallback = new ZenModePanel.Callback() {
        @Override
        public void onPrioritySettings() {
            mCallback.onZenPrioritySettingsClicked();
        }

        @Override
        public void onInteraction() {
            mHandler.sendEmptyMessage(H.RESCHEDULE_TIMEOUT);
        }

        @Override
        public void onExpanded(boolean expanded) {
            // noop.
        }
    };

    private final OnClickListener mClickExpand = new OnClickListener() {
        @Override
        public void onClick(View v) {
            if (mExpandButtonAnimationRunning) return;
            final boolean newExpand = !mExpanded;
            Events.writeEvent(mContext, Events.EVENT_EXPAND, newExpand);
            updateExpandedH(newExpand, false /* dismissing */);
        }
    };

    private final class H extends Handler {
        private static final int SHOW = 1;
        private static final int DISMISS = 2;
        private static final int RECHECK = 3;
        private static final int RECHECK_ALL = 4;
        private static final int SET_STREAM_IMPORTANT = 5;
        private static final int RESCHEDULE_TIMEOUT = 6;
        private static final int STATE_CHANGED = 7;
        private static final int UPDATE_BOTTOM_MARGIN = 8;
        private static final int UPDATE_FOOTER = 9;

        public H() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case SHOW: showH(msg.arg1); break;
                case DISMISS: dismissH(msg.arg1); break;
                case RECHECK: recheckH((VolumeRow) msg.obj); break;
                case RECHECK_ALL: recheckH(null); break;
                case SET_STREAM_IMPORTANT: setStreamImportantH(msg.arg1, msg.arg2 != 0); break;
                case RESCHEDULE_TIMEOUT: rescheduleTimeoutH(); break;
                case STATE_CHANGED: onStateChangedH(mState); break;
                case UPDATE_BOTTOM_MARGIN: updateDialogBottomMarginH(); break;
                case UPDATE_FOOTER: updateFooterH(); break;
            }
        }
    }

    private final class CustomDialog extends Dialog {
        public CustomDialog(Context context) {
            super(context);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            rescheduleTimeoutH();
            return super.dispatchTouchEvent(ev);
        }

        @Override
        protected void onStop() {
            super.onStop();
            final boolean animating = mMotion.isAnimating();
            if (D.BUG) Log.d(TAG, "onStop animating=" + animating);
            if (animating) {
                mPendingRecheckAll = true;
                return;
            }
            mHandler.sendEmptyMessage(H.RECHECK_ALL);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (isShowing()) {
                if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                    dismissH(Events.DISMISS_REASON_TOUCH_OUTSIDE);
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean dispatchPopulateAccessibilityEvent(@NonNull AccessibilityEvent event) {
            event.setClassName(getClass().getSuperclass().getName());
            event.setPackageName(mContext.getPackageName());

            ViewGroup.LayoutParams params = getWindow().getAttributes();
            boolean isFullScreen = (params.width == ViewGroup.LayoutParams.MATCH_PARENT) &&
                    (params.height == ViewGroup.LayoutParams.MATCH_PARENT);
            event.setFullScreen(isFullScreen);

            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (mShowing) {
                    event.getText().add(mContext.getString(
                            R.string.volume_dialog_accessibility_shown_message,
                            getActiveRow().ss.name));
                    return true;
                }
            }
            return false;
        }
    }

    private final class VolumeSeekBarChangeListener implements OnSeekBarChangeListener {
        private final VolumeRow mRow;

        private VolumeSeekBarChangeListener(VolumeRow row) {
            mRow = row;
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (mRow.ss == null) return;
            if (D.BUG) Log.d(TAG, AudioSystem.streamToString(mRow.stream)
                    + " onProgressChanged " + progress + " fromUser=" + fromUser);
            if (!fromUser) return;
            if (mRow.ss.levelMin > 0) {
                final int minProgress = mRow.ss.levelMin * 100;
                if (progress < minProgress) {
                    seekBar.setProgress(minProgress);
                    progress = minProgress;
                }
            }
            final int userLevel = getImpliedLevel(seekBar, progress);
            if (mRow.ss.level != userLevel || mRow.ss.muted && userLevel > 0) {
                mRow.userAttempt = SystemClock.uptimeMillis();
                if (mRow.requestedLevel != userLevel) {
                    mController.setStreamVolume(mRow.stream, userLevel);
                    mRow.requestedLevel = userLevel;
                    Events.writeEvent(mContext, Events.EVENT_TOUCH_LEVEL_CHANGED, mRow.stream,
                            userLevel);
                }
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            if (D.BUG) Log.d(TAG, "onStartTrackingTouch"+ " " + mRow.stream);
            mController.setActiveStream(mRow.stream);
            mRow.tracking = true;
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            if (D.BUG) Log.d(TAG, "onStopTrackingTouch"+ " " + mRow.stream);
            mRow.tracking = false;
            mRow.userAttempt = SystemClock.uptimeMillis();
            final int userLevel = getImpliedLevel(seekBar, seekBar.getProgress());
            Events.writeEvent(mContext, Events.EVENT_TOUCH_LEVEL_DONE, mRow.stream, userLevel);
            if (mRow.ss.level != userLevel) {
                mHandler.sendMessageDelayed(mHandler.obtainMessage(H.RECHECK, mRow),
                        USER_ATTEMPT_GRACE_PERIOD);
            }
        }
    }

    private final class Accessibility extends AccessibilityDelegate {
        private boolean mFeedbackEnabled;

        public void init() {
            mDialogView.addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
                @Override
                public void onViewDetachedFromWindow(View v) {
                    if (D.BUG) Log.d(TAG, "onViewDetachedFromWindow");
                }

                @Override
                public void onViewAttachedToWindow(View v) {
                    if (D.BUG) Log.d(TAG, "onViewAttachedToWindow");
                    updateFeedbackEnabled();
                }
            });
            mDialogView.setAccessibilityDelegate(this);
            mAccessibilityMgr.addAccessibilityStateChangeListener(
                    new AccessibilityStateChangeListener() {
                        @Override
                        public void onAccessibilityStateChanged(boolean enabled) {
                            updateFeedbackEnabled();
                        }
                    });
            updateFeedbackEnabled();
        }

        @Override
        public boolean onRequestSendAccessibilityEvent(ViewGroup host, View child,
                AccessibilityEvent event) {
            rescheduleTimeoutH();
            return super.onRequestSendAccessibilityEvent(host, child, event);
        }

        private void updateFeedbackEnabled() {
            mFeedbackEnabled = computeFeedbackEnabled();
        }

        private boolean computeFeedbackEnabled() {
            // are there any enabled non-generic a11y services?
            final List<AccessibilityServiceInfo> services =
                    mAccessibilityMgr.getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK);
            for (AccessibilityServiceInfo asi : services) {
                if (asi.feedbackType != 0 && asi.feedbackType != FEEDBACK_GENERIC) {
                    return true;
                }
            }
            return false;
        }
    }

    private static class VolumeRow {
        private View view;
        private TextView header;
        private ImageButton icon;
        private SeekBar slider;
        private int stream;
        private StreamState ss;
        private long userAttempt;  // last user-driven slider change
        private boolean tracking;  // tracking slider touch
        private int requestedLevel = -1;  // pending user-requested level via progress changed
        private int iconRes;
        private int iconMuteRes;
        private boolean important;
        private int cachedIconRes;
        private ColorStateList cachedSliderTint;
        private int iconState;  // from Events
        private boolean cachedShowHeaders = VolumePrefs.DEFAULT_SHOW_HEADERS;
        private ObjectAnimator anim;  // slider progress animation for non-touch-related updates
        private int animTargetProgress;
        private int lastAudibleLevel = 1;
    }

    public interface Callback {
        void onZenSettingsClicked();
        void onZenPrioritySettingsClicked();
    }
}
