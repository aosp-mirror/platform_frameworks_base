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

import static android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK;
import static android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC;
import static android.media.AudioManager.RINGER_MODE_NORMAL;
import static android.media.AudioManager.RINGER_MODE_SILENT;
import static android.media.AudioManager.RINGER_MODE_VIBRATE;
import static android.media.AudioManager.STREAM_ACCESSIBILITY;
import static android.media.AudioManager.STREAM_ALARM;
import static android.media.AudioManager.STREAM_MUSIC;
import static android.media.AudioManager.STREAM_RING;
import static android.media.AudioManager.STREAM_VOICE_CALL;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import static com.android.systemui.volume.Events.DISMISS_REASON_SETTINGS_CLICKED;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.text.InputFilter;
import android.util.Log;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.AccessibilityDelegate;
import android.view.View.OnAttachStateChangeListener;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager.AccessibilityServicesStateChangeListener;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;
import android.widget.Toast;

import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.VolumeDialog;
import com.android.systemui.plugins.VolumeDialogController;
import com.android.systemui.plugins.VolumeDialogController.State;
import com.android.systemui.plugins.VolumeDialogController.StreamState;
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Visual presentation of the volume dialog.
 *
 * A client of VolumeDialogControllerImpl and its state model.
 *
 * Methods ending in "H" must be called on the (ui) handler.
 */
public class VolumeDialogImpl implements VolumeDialog {
    private static final String TAG = Util.logTag(VolumeDialogImpl.class);

    private static final long USER_ATTEMPT_GRACE_PERIOD = 1000;
    private static final int UPDATE_ANIMATION_DURATION = 80;

    private final Context mContext;
    private final H mHandler = new H();
    private final VolumeDialogController mController;
    private final DeviceProvisionedController mDeviceProvisionedController;

    private Window mWindow;
    private CustomDialog mDialog;
    private ViewGroup mDialogView;
    private ViewGroup mDialogRowsView;
    private ViewGroup mRinger;
    private ImageButton mRingerIcon;
    private View mSettingsView;
    private ImageButton mSettingsIcon;
    private ImageView mZenIcon;
    private final List<VolumeRow> mRows = new ArrayList<>();
    private ConfigurableTexts mConfigurableTexts;
    private final SparseBooleanArray mDynamic = new SparseBooleanArray();
    private final KeyguardManager mKeyguard;
    private final AccessibilityManagerWrapper mAccessibilityMgr;
    private final Object mSafetyWarningLock = new Object();
    private final Accessibility mAccessibility = new Accessibility();
    private final ColorStateList mActiveTint;
    private final ColorStateList mInactiveTint;
    private final Vibrator mVibrator;

    private boolean mShowing;
    private boolean mShowA11yStream;

    private int mActiveStream;
    private int mPrevActiveStream;
    private boolean mAutomute = VolumePrefs.DEFAULT_ENABLE_AUTOMUTE;
    private boolean mSilentMode = VolumePrefs.DEFAULT_ENABLE_SILENT_MODE;
    private State mState;
    private SafetyWarningDialog mSafetyWarning;
    private boolean mHovering = false;

    public VolumeDialogImpl(Context context) {
        mContext = new ContextThemeWrapper(context, com.android.systemui.R.style.qs_theme);
        mController = Dependency.get(VolumeDialogController.class);
        mKeyguard = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        mAccessibilityMgr = Dependency.get(AccessibilityManagerWrapper.class);
        mActiveTint = ColorStateList.valueOf(Utils.getColorAccent(mContext));
        mInactiveTint = loadColorStateList(R.color.volume_slider_inactive);
        mDeviceProvisionedController = Dependency.get(DeviceProvisionedController.class);
        mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    public void init(int windowType, Callback callback) {
        initDialog();

        mAccessibility.init();

        mController.addCallback(mControllerCallbackH, mHandler);
        mController.getState();
    }

    @Override
    public void destroy() {
        mAccessibility.destroy();
        mController.removeCallback(mControllerCallbackH);
        mHandler.removeCallbacksAndMessages(null);
    }

    private void initDialog() {
        mDialog = new CustomDialog(mContext);

        mConfigurableTexts = new ConfigurableTexts(mContext);
        mHovering = false;
        mShowing = false;
        mWindow = mDialog.getWindow();
        mWindow.requestFeature(Window.FEATURE_NO_TITLE);
        mWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        mWindow.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR);
        mWindow.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        mWindow.setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY);
        mWindow.setWindowAnimations(com.android.internal.R.style.Animation_Toast);
        final WindowManager.LayoutParams lp = mWindow.getAttributes();
        lp.format = PixelFormat.TRANSLUCENT;
        lp.setTitle(VolumeDialogImpl.class.getSimpleName());
        lp.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        lp.windowAnimations = -1;
        mWindow.setAttributes(lp);
        mWindow.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        mDialog.setCanceledOnTouchOutside(true);
        mDialog.setContentView(R.layout.volume_dialog);
        mDialog.setOnShowListener(dialog -> {
            if (!isLandscape()) mDialogView.setTranslationX(mDialogView.getWidth() / 2);
            mDialogView.setAlpha(0);
            mDialogView.animate()
                    .alpha(1)
                    .translationX(0)
                    .setDuration(300)
                    .setInterpolator(new SystemUIInterpolators.LogDecelerateInterpolator())
                    .withEndAction(() -> {
                        if (!Prefs.getBoolean(mContext, Prefs.Key.TOUCHED_RINGER_TOGGLE, false)) {
                            mRingerIcon.postOnAnimationDelayed(mSinglePress, 1500);
                        }
                    })
                    .start();
        });
        mDialogView = mDialog.findViewById(R.id.volume_dialog);
        mDialogView.setOnHoverListener((v, event) -> {
            int action = event.getActionMasked();
            mHovering = (action == MotionEvent.ACTION_HOVER_ENTER)
                    || (action == MotionEvent.ACTION_HOVER_MOVE);
            rescheduleTimeoutH();
            return true;
        });

        mDialogRowsView = mDialog.findViewById(R.id.volume_dialog_rows);
        mRinger = mDialog.findViewById(R.id.ringer);
        mRingerIcon = mRinger.findViewById(R.id.ringer_icon);
        mZenIcon = mRinger.findViewById(R.id.dnd_icon);
        mSettingsView = mDialog.findViewById(R.id.settings_container);
        mSettingsIcon = mDialog.findViewById(R.id.settings);

        if (mRows.isEmpty()) {
            if (!AudioSystem.isSingleVolume(mContext)) {
                addRow(STREAM_ACCESSIBILITY, R.drawable.ic_volume_accessibility,
                        R.drawable.ic_volume_accessibility, true, false);
            }
            addRow(AudioManager.STREAM_MUSIC,
                    R.drawable.ic_volume_media, R.drawable.ic_volume_media_mute, true, true);
            if (!AudioSystem.isSingleVolume(mContext)) {
                addRow(AudioManager.STREAM_RING,
                        R.drawable.ic_volume_ringer, R.drawable.ic_volume_ringer_mute, true, false);
                addRow(STREAM_ALARM,
                        R.drawable.ic_volume_alarm, R.drawable.ic_volume_alarm_mute, true, false);
                addRow(AudioManager.STREAM_VOICE_CALL,
                        R.drawable.ic_volume_voice, R.drawable.ic_volume_voice, false, false);
                addRow(AudioManager.STREAM_BLUETOOTH_SCO,
                        R.drawable.ic_volume_bt_sco, R.drawable.ic_volume_bt_sco, false, false);
                addRow(AudioManager.STREAM_SYSTEM, R.drawable.ic_volume_system,
                        R.drawable.ic_volume_system_mute, false, false);
            }
        } else {
            addExistingRows();
        }

        updateRowsH(getActiveRow());
        initRingerH();
        initSettingsH();
    }

    protected ViewGroup getDialogView() {
        return mDialogView;
    }

    private ColorStateList loadColorStateList(int colorResId) {
        return ColorStateList.valueOf(mContext.getColor(colorResId));
    }

    private boolean isLandscape() {
        return mContext.getResources().getConfiguration().orientation ==
                Configuration.ORIENTATION_LANDSCAPE;
    }

    public void setStreamImportant(int stream, boolean important) {
        mHandler.obtainMessage(H.SET_STREAM_IMPORTANT, stream, important ? 1 : 0).sendToTarget();
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

    private void addRow(int stream, int iconRes, int iconMuteRes, boolean important,
            boolean defaultStream) {
        addRow(stream, iconRes, iconMuteRes, important, defaultStream, false);
    }

    private void addRow(int stream, int iconRes, int iconMuteRes, boolean important,
            boolean defaultStream, boolean dynamic) {
        if (D.BUG) Slog.d(TAG, "Adding row for stream " + stream);
        VolumeRow row = new VolumeRow();
        initRow(row, stream, iconRes, iconMuteRes, important, defaultStream);
        mDialogRowsView.addView(row.view);
        mRows.add(row);
    }

    private void addExistingRows() {
        int N = mRows.size();
        for (int i = 0; i < N; i++) {
            final VolumeRow row = mRows.get(i);
            initRow(row, row.stream, row.iconRes, row.iconMuteRes, row.important,
                    row.defaultStream);
            mDialogRowsView.addView(row.view);
            updateVolumeRowH(row);
        }
    }

    private VolumeRow getActiveRow() {
        for (VolumeRow row : mRows) {
            if (row.stream == mActiveStream) {
                return row;
            }
        }
        for (VolumeRow row : mRows) {
            if (row.stream == STREAM_MUSIC) {
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
        writer.println(VolumeDialogImpl.class.getSimpleName() + " state:");
        writer.print("  mShowing: "); writer.println(mShowing);
        writer.print("  mActiveStream: "); writer.println(mActiveStream);
        writer.print("  mDynamic: "); writer.println(mDynamic);
        writer.print("  mAutomute: "); writer.println(mAutomute);
        writer.print("  mSilentMode: "); writer.println(mSilentMode);
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
            boolean important, boolean defaultStream) {
        row.stream = stream;
        row.iconRes = iconRes;
        row.iconMuteRes = iconMuteRes;
        row.important = important;
        row.defaultStream = defaultStream;
        row.view = mDialog.getLayoutInflater().inflate(R.layout.volume_dialog_row, null);
        row.view.setId(row.stream);
        row.view.setTag(row);
        row.header = row.view.findViewById(R.id.volume_row_header);
        row.header.setId(20 * row.stream);
        if (stream == STREAM_ACCESSIBILITY) {
            row.header.setFilters(new InputFilter[] {new InputFilter.LengthFilter(13)});
        }
        row.dndIcon = row.view.findViewById(R.id.dnd_icon);
        row.slider =  row.view.findViewById(R.id.volume_row_slider);
        row.slider.setOnSeekBarChangeListener(new VolumeSeekBarChangeListener(row));
        row.anim = null;

        row.icon = row.view.findViewById(R.id.volume_row_icon);
        row.icon.setImageResource(iconRes);
        if (row.stream != AudioSystem.STREAM_ACCESSIBILITY) {
            row.icon.setOnClickListener(v -> {
                Events.writeEvent(mContext, Events.EVENT_ICON_CLICK, row.stream, row.iconState);
                mController.setActiveStream(row.stream);
                if (row.stream == AudioManager.STREAM_RING) {
                    final boolean hasVibrator = mController.hasVibrator();
                    if (mState.ringerModeInternal == AudioManager.RINGER_MODE_NORMAL) {
                        if (hasVibrator) {
                            mController.setRingerMode(AudioManager.RINGER_MODE_VIBRATE, false);
                        } else {
                            final boolean wasZero = row.ss.level == 0;
                            mController.setStreamVolume(stream,
                                    wasZero ? row.lastAudibleLevel : 0);
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
                row.userAttempt = 0;  // reset the grace period, slider updates immediately
            });
        } else {
            row.icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
    }

    public void initSettingsH() {
        mSettingsView.setVisibility(
                mDeviceProvisionedController.isCurrentUserSetup() ? VISIBLE : GONE);
        mSettingsIcon.setOnClickListener(v -> {
            Events.writeEvent(mContext, Events.EVENT_SETTINGS_CLICK);
            Intent intent = new Intent(Settings.ACTION_SOUND_SETTINGS);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            dismissH(DISMISS_REASON_SETTINGS_CLICKED);
            Dependency.get(ActivityStarter.class).startActivity(intent, true /* dismissShade */);
        });
    }

    public void initRingerH() {
        mRingerIcon.setOnClickListener(v -> {
            Prefs.putBoolean(mContext, Prefs.Key.TOUCHED_RINGER_TOGGLE, true);
            final StreamState ss = mState.states.get(AudioManager.STREAM_RING);
            if (ss == null) {
                return;
            }
            // normal -> vibrate -> silent -> normal (skip vibrate if device doesn't have
            // a vibrator.
            int newRingerMode;
            final boolean hasVibrator = mController.hasVibrator();
            if (mState.ringerModeInternal == AudioManager.RINGER_MODE_NORMAL) {
                if (hasVibrator) {
                    newRingerMode = AudioManager.RINGER_MODE_VIBRATE;
                } else {
                    newRingerMode = AudioManager.RINGER_MODE_SILENT;
                }
            } else if (mState.ringerModeInternal == AudioManager.RINGER_MODE_VIBRATE) {
                newRingerMode = AudioManager.RINGER_MODE_SILENT;
            } else {
                newRingerMode = AudioManager.RINGER_MODE_NORMAL;
                if (ss.level == 0) {
                    mController.setStreamVolume(AudioManager.STREAM_RING, 1);
                }
            }
            Events.writeEvent(mContext, Events.EVENT_RINGER_TOGGLE, newRingerMode);
            updateRingerH();
            provideTouchFeedbackH(newRingerMode);
            mController.setRingerMode(newRingerMode, false);
            maybeShowToastH(newRingerMode);
        });
        updateRingerH();
    }


    private void provideTouchFeedbackH(int newRingerMode) {
        VibrationEffect effect = null;
        switch (newRingerMode) {
            case RINGER_MODE_NORMAL:
                mController.scheduleTouchFeedback();
                break;
            case RINGER_MODE_SILENT:
                effect = VibrationEffect.get(VibrationEffect.EFFECT_CLICK);
                break;
            case RINGER_MODE_VIBRATE:
            default:
                effect = VibrationEffect.get(VibrationEffect.EFFECT_DOUBLE_CLICK);
        }
        if (effect != null) {
            mController.vibrate(effect);
        }
    }

    private void maybeShowToastH(int newRingerMode) {
        int seenToastCount = Prefs.getInt(mContext, Prefs.Key.SEEN_RINGER_GUIDANCE_COUNT, 0);

        if (seenToastCount > VolumePrefs.SHOW_RINGER_TOAST_COUNT) {
            return;
        }
        CharSequence toastText = null;
        switch (newRingerMode) {
            case RINGER_MODE_NORMAL:
                final StreamState ss = mState.states.get(AudioManager.STREAM_RING);
                if (ss != null) {
                    toastText = mContext.getString(
                            R.string.volume_dialog_ringer_guidance_ring,
                            Utils.formatPercentage(ss.level, ss.levelMax));
                }
                break;
            case RINGER_MODE_SILENT:
                toastText = mContext.getString(
                        com.android.internal.R.string.volume_dialog_ringer_guidance_silent);
                break;
            case RINGER_MODE_VIBRATE:
            default:
                toastText = mContext.getString(
                        com.android.internal.R.string.volume_dialog_ringer_guidance_vibrate);
        }

        Toast.makeText(mContext, toastText, Toast.LENGTH_SHORT).show();
        seenToastCount++;
        Prefs.putInt(mContext, Prefs.Key.SEEN_RINGER_GUIDANCE_COUNT, seenToastCount);
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
        mShowing = true;

        initSettingsH();
        mDialog.show();
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
        return 3000;
    }

    protected void dismissH(int reason) {
        mHandler.removeMessages(H.DISMISS);
        mHandler.removeMessages(H.SHOW);
        mDialogView.animate().cancel();
        mShowing = false;

        mDialogView.setTranslationX(0);
        mDialogView.setAlpha(1);
        ViewPropertyAnimator animator = mDialogView.animate()
                .alpha(0)
                .setDuration(250)
                .setInterpolator(new SystemUIInterpolators.LogAccelerateInterpolator())
                .withEndAction(() -> mHandler.postDelayed(() -> {
                    if (D.BUG) Log.d(TAG, "mDialog.dismiss()");
                    mDialog.dismiss();
                }, 50));
        if (!isLandscape()) animator.translationX(mDialogView.getWidth() / 2);
        animator.start();

        Events.writeEvent(mContext, Events.EVENT_DISMISS_DIALOG, reason);
        mController.notifyVisible(false);
        synchronized (mSafetyWarningLock) {
            if (mSafetyWarning != null) {
                if (D.BUG) Log.d(TAG, "SafetyWarning dismissed");
                mSafetyWarning.dismiss();
            }
        }
    }

    private boolean shouldBeVisibleH(VolumeRow row, VolumeRow activeRow) {
        boolean isActive = row.stream == activeRow.stream;
        if (row.stream == AudioSystem.STREAM_ACCESSIBILITY) {
            return mShowA11yStream;
        }

        // if the active row is accessibility, then continue to display previous
        // active row since accessibility is displayed under it
        if (activeRow.stream == AudioSystem.STREAM_ACCESSIBILITY &&
                row.stream == mPrevActiveStream) {
            return true;
        }

        if (isActive) {
            return true;
        }

        if (row.defaultStream) {
            return activeRow.stream == STREAM_RING
                    || activeRow.stream == STREAM_ALARM
                    || activeRow.stream == STREAM_VOICE_CALL
                    || activeRow.stream == STREAM_ACCESSIBILITY
                    || mDynamic.get(activeRow.stream);
        }

        return false;
    }

    private void updateRowsH(final VolumeRow activeRow) {
        if (D.BUG) Log.d(TAG, "updateRowsH");
        if (!mShowing) {
            trimObsoleteH();
        }
        // apply changes to all rows
        for (final VolumeRow row : mRows) {
            final boolean isActive = row == activeRow;
            final boolean shouldBeVisible = shouldBeVisibleH(row, activeRow);
            Util.setVisOrGone(row.view, shouldBeVisible);
            if (row.view.isShown()) {
                updateVolumeRowTintH(row, isActive);
            }
        }
    }

    protected void updateRingerH() {
        if (mState != null) {
            final StreamState ss = mState.states.get(AudioManager.STREAM_RING);
            if (ss == null) {
                return;
            }

            boolean isZenMuted = mState.zenMode == Global.ZEN_MODE_ALARMS
                    || mState.zenMode == Global.ZEN_MODE_NO_INTERRUPTIONS
                    || (mState.zenMode == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
                        && mState.disallowRinger);
            enableRingerViewsH(!isZenMuted);
            switch (mState.ringerModeInternal) {
                case AudioManager.RINGER_MODE_VIBRATE:
                    mRingerIcon.setImageResource(R.drawable.ic_volume_ringer_vibrate);
                    mRingerIcon.setTag(Events.ICON_STATE_VIBRATE);
                    break;
                case AudioManager.RINGER_MODE_SILENT:
                    mRingerIcon.setImageResource(R.drawable.ic_volume_ringer_mute);
                    mRingerIcon.setContentDescription(mContext.getString(
                            R.string.volume_stream_content_description_unmute,
                            getStreamLabelH(ss)));
                    mRingerIcon.setTag(Events.ICON_STATE_MUTE);
                    break;
                case AudioManager.RINGER_MODE_NORMAL:
                default:
                    boolean muted = (mAutomute && ss.level == 0) || ss.muted;
                    if (!isZenMuted && muted) {
                        mRingerIcon.setImageResource(R.drawable.ic_volume_ringer_mute);
                        mRingerIcon.setContentDescription(mContext.getString(
                                R.string.volume_stream_content_description_unmute,
                                getStreamLabelH(ss)));
                        mRingerIcon.setTag(Events.ICON_STATE_MUTE);
                    } else {
                        mRingerIcon.setImageResource(R.drawable.ic_volume_ringer);
                        if (mController.hasVibrator()) {
                            mRingerIcon.setContentDescription(mContext.getString(
                                    mShowA11yStream
                                            ? R.string.volume_stream_content_description_vibrate_a11y
                                            : R.string.volume_stream_content_description_vibrate,
                                    getStreamLabelH(ss)));

                        } else {
                            mRingerIcon.setContentDescription(getStreamLabelH(ss));
                        }
                        mRingerIcon.setTag(Events.ICON_STATE_UNMUTE);
                    }
                    break;
            }
        }
    }

    /**
     * Toggles enable state of views in a VolumeRow (not including seekbar or icon)
     * Hides/shows zen icon
     * @param enable whether to enable volume row views and hide dnd icon
     */
    private void enableVolumeRowViewsH(VolumeRow row, boolean enable) {
        row.dndIcon.setVisibility(enable ? GONE : VISIBLE);
    }

    /**
     * Toggles enable state of footer/ringer views
     * Hides/shows zen icon
     * @param enable whether to enable ringer views and hide dnd icon
     */
    private void enableRingerViewsH(boolean enable) {
        mRingerIcon.setEnabled(enable);
        mZenIcon.setVisibility(enable ? GONE : VISIBLE);
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

    protected void onStateChangedH(State state) {
        mState = state;
        mDynamic.clear();
        // add any new dynamic rows
        for (int i = 0; i < state.states.size(); i++) {
            final int stream = state.states.keyAt(i);
            final StreamState ss = state.states.valueAt(i);
            if (!ss.dynamic) continue;
            mDynamic.put(stream, true);
            if (findRow(stream) == null) {
                addRow(stream, R.drawable.ic_volume_remote, R.drawable.ic_volume_remote_mute, true,
                        false, true);
            }
        }

        if (mActiveStream != state.activeStream) {
            mPrevActiveStream = mActiveStream;
            mActiveStream = state.activeStream;
            VolumeRow activeRow = getActiveRow();
            updateRowsH(activeRow);
            rescheduleTimeoutH();
        }
        for (VolumeRow row : mRows) {
            updateVolumeRowH(row);
        }
        updateRingerH();
        mWindow.setTitle(mContext.getString(R.string.volume_dialog_title,
                getStreamLabelH(getActiveRow().ss)));
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
        final boolean isA11yStream = row.stream == STREAM_ACCESSIBILITY;
        final boolean isRingStream = row.stream == AudioManager.STREAM_RING;
        final boolean isSystemStream = row.stream == AudioManager.STREAM_SYSTEM;
        final boolean isAlarmStream = row.stream == STREAM_ALARM;
        final boolean isMusicStream = row.stream == AudioManager.STREAM_MUSIC;
        final boolean isRingVibrate = isRingStream
                && mState.ringerModeInternal == AudioManager.RINGER_MODE_VIBRATE;
        final boolean isRingSilent = isRingStream
                && mState.ringerModeInternal == AudioManager.RINGER_MODE_SILENT;
        final boolean isZenPriorityOnly = mState.zenMode == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        final boolean isZenAlarms = mState.zenMode == Global.ZEN_MODE_ALARMS;
        final boolean isZenNone = mState.zenMode == Global.ZEN_MODE_NO_INTERRUPTIONS;
        final boolean zenMuted = isZenAlarms ? (isRingStream || isSystemStream)
                : isZenNone ? (isRingStream || isSystemStream || isAlarmStream || isMusicStream)
                : isZenPriorityOnly ? ((isAlarmStream && mState.disallowAlarms) ||
                        (isMusicStream && mState.disallowMedia) ||
                        (isRingStream && mState.disallowRinger) ||
                        (isSystemStream && mState.disallowSystem))
                : false;

        // update slider max
        final int max = ss.levelMax * 100;
        if (max != row.slider.getMax()) {
            row.slider.setMax(max);
        }
        // update A11y slider min
        final int min = ss.levelMin * 100;
        if (isA11yStream && min != row.slider.getMin()) {
            row.slider.setMin(min);
        }

        // update header text
        Util.setText(row.header, getStreamLabelH(ss));
        row.slider.setContentDescription(row.header.getText());
        mConfigurableTexts.add(row.header, ss.name);

        // update icon
        final boolean iconEnabled = (mAutomute || ss.muteSupported) && !zenMuted;
        row.icon.setEnabled(iconEnabled);
        row.icon.setAlpha(iconEnabled ? 1 : 0.5f);
        final int iconRes =
                isRingVibrate ? R.drawable.ic_volume_ringer_vibrate
                : isRingSilent || zenMuted ? row.iconMuteRes
                : ss.routedToBluetooth ?
                        (ss.muted ? R.drawable.ic_volume_media_bt_mute
                                : R.drawable.ic_volume_media_bt)
                : mAutomute && ss.level == 0 ? row.iconMuteRes
                : (ss.muted ? row.iconMuteRes : row.iconRes);
        row.icon.setImageResource(iconRes);
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
                            getStreamLabelH(ss)));
                } else {
                    if (mController.hasVibrator()) {
                        row.icon.setContentDescription(mContext.getString(
                                mShowA11yStream
                                        ? R.string.volume_stream_content_description_vibrate_a11y
                                        : R.string.volume_stream_content_description_vibrate,
                                getStreamLabelH(ss)));
                    } else {
                        row.icon.setContentDescription(mContext.getString(
                                mShowA11yStream
                                        ? R.string.volume_stream_content_description_mute_a11y
                                        : R.string.volume_stream_content_description_mute,
                                getStreamLabelH(ss)));
                    }
                }
            } else if (isA11yStream) {
                row.icon.setContentDescription(getStreamLabelH(ss));
            } else {
                if (ss.muted || mAutomute && ss.level == 0) {
                   row.icon.setContentDescription(mContext.getString(
                           R.string.volume_stream_content_description_unmute,
                           getStreamLabelH(ss)));
                } else {
                    row.icon.setContentDescription(mContext.getString(
                            mShowA11yStream
                                    ? R.string.volume_stream_content_description_mute_a11y
                                    : R.string.volume_stream_content_description_mute,
                            getStreamLabelH(ss)));
                }
            }
        } else {
            row.icon.setContentDescription(getStreamLabelH(ss));
        }

        // ensure tracking is disabled if zenMuted
        if (zenMuted) {
            row.tracking = false;
        }
        enableVolumeRowViewsH(row, !zenMuted);

        // update slider
        final boolean enableSlider = !zenMuted;
        final int vlevel = row.ss.muted && (!isRingStream && !zenMuted) ? 0
                : row.ss.level;
        updateVolumeRowSliderH(row, enableSlider, vlevel);
    }

    private void updateVolumeRowTintH(VolumeRow row, boolean isActive) {
        if (isActive) {
            row.slider.requestFocus();
        }
        final ColorStateList tint = isActive && row.slider.isEnabled() ? mActiveTint
                : mInactiveTint;
        if (tint == row.cachedTint) return;
        row.slider.setProgressTintList(tint);
        row.slider.setThumbTintList(tint);
        row.icon.setImageTintList(tint);
        row.cachedTint = tint;
    }

    private void updateVolumeRowSliderH(VolumeRow row, boolean enable, int vlevel) {
        row.slider.setEnabled(enable);
        updateVolumeRowTintH(row, row.stream == mActiveStream);
        if (row.tracking) {
            return;  // don't update if user is sliding
        }
        final int progress = row.slider.getProgress();
        final int level = getImpliedLevel(row.slider, progress);
        final boolean rowVisible = row.view.getVisibility() == VISIBLE;
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
                row.slider.setProgress(newProgress, true);
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

    private String getStreamLabelH(StreamState ss) {
        if (ss == null) {
            return "";
        }
        if (ss.remoteLabel != null) {
            return ss.remoteLabel;
        }
        try {
            return mContext.getResources().getString(ss.name);
        } catch (Resources.NotFoundException e) {
            Slog.e(TAG, "Can't find translation for stream " + ss);
            return "";
        }
    }

    private Runnable mSinglePress = new Runnable() {
        @Override
        public void run() {
            mRingerIcon.setPressed(true);
            mRingerIcon.postOnAnimationDelayed(mSingleUnpress, 200);
        }
    };

    private Runnable mSingleUnpress = new Runnable() {
        @Override
        public void run() {
            mRingerIcon.setPressed(false);
        }
    };

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
            mDialog.dismiss();
            initDialog();
            mConfigurableTexts.update();
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

        @Override
        public void onAccessibilityModeChanged(Boolean showA11yStream) {
            mShowA11yStream = showA11yStream == null ? false : showA11yStream;
            VolumeRow activeRow = getActiveRow();
            if (!mShowA11yStream && STREAM_ACCESSIBILITY == activeRow.stream) {
                dismissH(Events.DISMISS_STREAM_GONE);
            } else {
                updateRowsH(activeRow);
            }

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
            }
        }
    }

    private final class CustomDialog extends Dialog implements DialogInterface {
        public CustomDialog(Context context) {
            super(context, com.android.systemui.R.style.qs_theme);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            rescheduleTimeoutH();
            return super.dispatchTouchEvent(ev);
        }

        @Override
        protected void onStart() {
            super.setCanceledOnTouchOutside(true);
            super.onStart();
        }

        @Override
        protected void onStop() {
            super.onStop();
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
            mAccessibilityMgr.addCallback(mListener);
            updateFeedbackEnabled();
        }

        public void destroy() {
            mAccessibilityMgr.removeCallback(mListener);
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

        private final AccessibilityServicesStateChangeListener mListener =
                enabled -> updateFeedbackEnabled();
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
        private boolean defaultStream;
        private ColorStateList cachedTint;
        private int iconState;  // from Events
        private ObjectAnimator anim;  // slider progress animation for non-touch-related updates
        private int animTargetProgress;
        private int lastAudibleLevel = 1;
        private ImageView dndIcon;
    }
}
