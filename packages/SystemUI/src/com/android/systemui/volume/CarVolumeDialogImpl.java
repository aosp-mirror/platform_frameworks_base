/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.AnimatorSet;
import android.annotation.Nullable;
import android.app.Dialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;

import androidx.car.widget.ListItem;
import androidx.car.widget.ListItemAdapter;
import androidx.car.widget.ListItemAdapter.BackgroundStyle;
import androidx.car.widget.ListItemProvider.ListProvider;
import androidx.car.widget.PagedListView;
import androidx.car.widget.SeekbarListItem;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.VolumeDialog;
import com.android.systemui.plugins.VolumeDialogController;
import com.android.systemui.plugins.VolumeDialogController.State;
import com.android.systemui.plugins.VolumeDialogController.StreamState;

/**
 * Car version of the volume dialog.
 *
 * A client of VolumeDialogControllerImpl and its state model.
 *
 * Methods ending in "H" must be called on the (ui) handler.
 */
public class CarVolumeDialogImpl implements VolumeDialog {
    private static final String TAG = Util.logTag(CarVolumeDialogImpl.class);

    private static final long USER_ATTEMPT_GRACE_PERIOD = 1000;

    private final Context mContext;
    private final H mHandler = new H();
    private final VolumeDialogController mController;
    private final AudioManager mAudioManager;

    private Window mWindow;
    private CustomDialog mDialog;
    private PagedListView mListView;
    private ListItemAdapter mPagedListAdapter;
    private final List<ListItem> mVolumeLineItems = new ArrayList<>();
    private final List<VolumeRow> mRows = new ArrayList<>();
    private ConfigurableTexts mConfigurableTexts;
    private final SparseBooleanArray mDynamic = new SparseBooleanArray();
    private final KeyguardManager mKeyguard;
    private final Object mSafetyWarningLock = new Object();

    private boolean mShowing;

    private boolean mAutomute = VolumePrefs.DEFAULT_ENABLE_AUTOMUTE;
    private boolean mSilentMode = VolumePrefs.DEFAULT_ENABLE_SILENT_MODE;
    private State mState;
    private SafetyWarningDialog mSafetyWarning;
    private boolean mHovering = false;
    private boolean mExpanded;

    public CarVolumeDialogImpl(Context context) {
        mContext = new ContextThemeWrapper(context, com.android.systemui.R.style.qs_theme);
        mController = Dependency.get(VolumeDialogController.class);
        mKeyguard = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
    }

    public void init(int windowType, Callback callback) {
        initDialog();

        mController.addCallback(mControllerCallbackH, mHandler);
        mController.getState();
    }

    @Override
    public void destroy() {
        mController.removeCallback(mControllerCallbackH);
        mHandler.removeCallbacksAndMessages(null);
    }

    private void initDialog() {
        mRows.clear();
        mVolumeLineItems.clear();
        mDialog = new CustomDialog(mContext);

        mConfigurableTexts = new ConfigurableTexts(mContext);
        mHovering = false;
        mShowing = false;
        mExpanded = false;
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
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.windowAnimations = -1;
        mWindow.setAttributes(lp);
        mWindow.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        mDialog.setCanceledOnTouchOutside(true);
        mDialog.setContentView(R.layout.car_volume_dialog);
        mDialog.setOnShowListener(dialog -> {
            mListView.setTranslationY(-mListView.getHeight());
            mListView.setAlpha(0);
            mListView.animate()
                .alpha(1)
                .translationY(0)
                .setDuration(300)
                .setInterpolator(new SystemUIInterpolators.LogDecelerateInterpolator())
                .start();
        });
        mListView = (PagedListView) mWindow.findViewById(R.id.volume_list);
        mListView.setOnHoverListener((v, event) -> {
            int action = event.getActionMasked();
            mHovering = (action == MotionEvent.ACTION_HOVER_ENTER)
                || (action == MotionEvent.ACTION_HOVER_MOVE);
            rescheduleTimeoutH();
            return true;
        });

        addSeekbarListItem(addVolumeRow(AudioManager.STREAM_MUSIC, R.drawable.car_ic_music,
            R.drawable.car_ic_keyboard_arrow_down, true, true),
            new ExpandIconListener());
        // We map AudioManager.STREAM_RING to a navigation icon for demo.
        addVolumeRow(AudioManager.STREAM_RING, R.drawable.car_ic_navigation, 0,
            true, false);
        addVolumeRow(AudioManager.STREAM_NOTIFICATION, R.drawable.car_ic_notification_2, 0,
            true, false);

        mPagedListAdapter = new ListItemAdapter(mContext, new ListProvider(mVolumeLineItems),
            BackgroundStyle.PANEL);
        mListView.setAdapter(mPagedListAdapter);
        mListView.setMaxPages(PagedListView.UNLIMITED_PAGES);
    }

    public void setStreamImportant(int stream, boolean important) {
        mHandler.obtainMessage(H.SET_STREAM_IMPORTANT, stream, important ? 1 : 0).sendToTarget();
    }

    public void setAutomute(boolean automute) {
        if (mAutomute == automute) {
            return;
        }
        mAutomute = automute;
        mHandler.sendEmptyMessage(H.RECHECK_ALL);
    }

    public void setSilentMode(boolean silentMode) {
        if (mSilentMode == silentMode) {
            return;
        }
        mSilentMode = silentMode;
        mHandler.sendEmptyMessage(H.RECHECK_ALL);
    }

    private VolumeRow addVolumeRow(int stream, int primaryActionIcon, int supplementalIcon,
        boolean important, boolean defaultStream) {
        VolumeRow volumeRow = new VolumeRow();
        volumeRow.stream = stream;
        volumeRow.primaryActionIcon = primaryActionIcon;
        volumeRow.supplementalIcon = supplementalIcon;
        volumeRow.important = important;
        volumeRow.defaultStream = defaultStream;
        volumeRow.listItem = null;
        mRows.add(volumeRow);
        return volumeRow;
    }

    private SeekbarListItem addSeekbarListItem(
        VolumeRow volumeRow, @Nullable View.OnClickListener supplementalIconOnClickListener) {
        int volumeMax = mAudioManager.getStreamMaxVolume(volumeRow.stream);
        int currentVolume = mAudioManager.getStreamVolume(volumeRow.stream);
        SeekbarListItem listItem =
            new SeekbarListItem(mContext, volumeMax, currentVolume,
                new VolumeSeekBarChangeListener(volumeRow), null);
        Drawable primaryIcon = mContext.getResources().getDrawable(volumeRow.primaryActionIcon);
        listItem.setPrimaryActionIcon(primaryIcon);
        if (volumeRow.supplementalIcon != 0) {
            Drawable supplementalIcon = mContext.getResources()
                .getDrawable(volumeRow.supplementalIcon);
            listItem.setSupplementalIcon(supplementalIcon, true,
                supplementalIconOnClickListener);
        } else {
            listItem.setSupplementalEmptyIcon(true);
        }

        mVolumeLineItems.add(listItem);
        volumeRow.listItem = listItem;

        return listItem;
    }

    private static int getImpliedLevel(SeekBar seekBar, int progress) {
        final int m = seekBar.getMax();
        final int n = m / 100 - 1;
        final int level = progress == 0 ? 0
            : progress == m ? (m / 100) : (1 + (int)((progress / (float) m) * n));
        return level;
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
        if (mHovering) return 16000;
        if (mSafetyWarning != null) return 5000;
        return 3000;
    }

    protected void dismissH(int reason) {
        mHandler.removeMessages(H.DISMISS);
        mHandler.removeMessages(H.SHOW);
        if (!mShowing) return;
        mListView.animate().cancel();
        mShowing = false;

        mListView.setTranslationY(0);
        mListView.setAlpha(1);
        mListView.animate()
            .alpha(0)
            .translationY(-mListView.getHeight())
            .setDuration(250)
            .setInterpolator(new SystemUIInterpolators.LogAccelerateInterpolator())
            .withEndAction(() -> mHandler.postDelayed(() -> {
                if (D.BUG) Log.d(TAG, "mDialog.dismiss()");
                mDialog.dismiss();
            }, 50))
            .start();

        Events.writeEvent(mContext, Events.EVENT_DISMISS_DIALOG, reason);
        mController.notifyVisible(false);
        synchronized (mSafetyWarningLock) {
            if (mSafetyWarning != null) {
                if (D.BUG) Log.d(TAG, "SafetyWarning dismissed");
                mSafetyWarning.dismiss();
            }
        }
    }

    private void trimObsoleteH() {
        int initialVolumeItemSize = mVolumeLineItems.size();
        for (int i = mRows.size() - 1; i >= 0; i--) {
            final VolumeRow row = mRows.get(i);
            if (row.ss == null || !row.ss.dynamic) continue;
            if (!mDynamic.get(row.stream)) {
                mRows.remove(i);
                mVolumeLineItems.remove(row.listItem);
            }
        }

        if (mVolumeLineItems.size() != initialVolumeItemSize) {
            mPagedListAdapter.notifyDataSetChanged();
        }
    }

    private void onStateChangedH(State state) {
        mState = state;
        mDynamic.clear();
        // add any new dynamic rows
        for (int i = 0; i < state.states.size(); i++) {
            final int stream = state.states.keyAt(i);
            final StreamState ss = state.states.valueAt(i);
            if (!ss.dynamic) {
                continue;
            }
            mDynamic.put(stream, true);
            if (findRow(stream) == null) {
                VolumeRow row = addVolumeRow(stream, R.drawable.ic_volume_remote,
                    0, true,false);
                if (mExpanded) {
                    addSeekbarListItem(row, null);
                }
            }
        }

        for (VolumeRow row : mRows) {
            updateVolumeRowH(row);
        }
    }

    private void updateVolumeRowH(VolumeRow row) {
        if (D.BUG) Log.d(TAG, "updateVolumeRowH s=" + row.stream);
        if (mState == null) {
            return;
        }
        final StreamState ss = mState.states.get(row.stream);
        if (ss == null) {
            return;
        }
        row.ss = ss;
        if (ss.level == row.requestedLevel) {
            row.requestedLevel = -1;
        }
        // TODO: update Seekbar progress and change the mute icon if necessary.
    }

    private VolumeRow findRow(int stream) {
        for (VolumeRow row : mRows) {
            if (row.stream == stream) {
                return row;
            }
        }
        return null;
    }

    private VolumeRow findRow(SeekbarListItem targetItem) {
        for (VolumeRow row : mRows) {
            if (row.listItem == targetItem) {
                return row;
            }
        }
        return null;
    }

    public void dump(PrintWriter writer) {
        writer.println(VolumeDialogImpl.class.getSimpleName() + " state:");
        writer.print("  mShowing: "); writer.println(mShowing);
        writer.print("  mDynamic: "); writer.println(mDynamic);
        writer.print("  mAutomute: "); writer.println(mAutomute);
        writer.print("  mSilentMode: "); writer.println(mSilentMode);
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
            mListView.setLayoutDirection(layoutDirection);
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

        private VolumeSeekBarChangeListener(VolumeRow volumeRow) {
            mRow = volumeRow;
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (mRow.ss == null) {
                return;
            }
            if (D.BUG) {
                Log.d(TAG, AudioSystem.streamToString(mRow.stream)
                    + " onProgressChanged " + progress + " fromUser=" + fromUser);
            }
            if (!fromUser) {
                return;
            }
            if (mRow.ss.levelMin > 0) {
                final int minProgress = mRow.ss.levelMin;
                if (progress < minProgress) {
                    seekBar.setProgress(minProgress);
                    progress = minProgress;
                }
            }
            final int userLevel = getImpliedLevel(seekBar, progress);
            if (mRow.ss.level != userLevel || mRow.ss.muted && userLevel > 0) {
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
            if (D.BUG) {
                Log.d(TAG, "onStartTrackingTouch"+ " " + mRow.stream);
            }
            mController.setActiveStream(mRow.stream);
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            if (D.BUG) {
                Log.d(TAG, "onStopTrackingTouch"+ " " + mRow.stream);
            }
            final int userLevel = getImpliedLevel(seekBar, seekBar.getProgress());
            Events.writeEvent(mContext, Events.EVENT_TOUCH_LEVEL_DONE, mRow.stream, userLevel);
            if (mRow.ss.level != userLevel) {
                mHandler.sendMessageDelayed(mHandler.obtainMessage(H.RECHECK, mRow),
                    USER_ATTEMPT_GRACE_PERIOD);
            }
        }
    }

    private final class ExpandIconListener implements View.OnClickListener {
        @Override
        public void onClick(final View v) {
            mExpanded = !mExpanded;
            Animator inAnimator;
            if (mExpanded) {
                for (VolumeRow row : mRows) {
                    // Adding the items which are not coming from default stream.
                    if (!row.defaultStream) {
                        addSeekbarListItem(row, null);
                    }
                }
                inAnimator = AnimatorInflater.loadAnimator(
                    mContext, R.anim.car_arrow_fade_in_rotate_up);
            } else {
                // Only keeping the default stream if it is not expended.
                Iterator itr = mVolumeLineItems.iterator();
                while (itr.hasNext()) {
                    SeekbarListItem item = (SeekbarListItem) itr.next();
                    VolumeRow row = findRow(item);
                    if (!row.defaultStream) {
                        itr.remove();
                    }
                }
                inAnimator = AnimatorInflater.loadAnimator(
                    mContext, R.anim.car_arrow_fade_in_rotate_down);
            }

            Animator outAnimator = AnimatorInflater.loadAnimator(
                mContext, R.anim.car_arrow_fade_out);
            inAnimator.setStartDelay(100);
            AnimatorSet animators = new AnimatorSet();
            animators.playTogether(outAnimator, inAnimator);
            animators.setTarget(v);
            animators.start();
            mPagedListAdapter.notifyDataSetChanged();
        }
    }

    private static class VolumeRow {
        private int stream;
        private StreamState ss;
        private boolean important;
        private boolean defaultStream;
        private int primaryActionIcon;
        private int supplementalIcon;
        private SeekbarListItem listItem;
        private int requestedLevel = -1;  // pending user-requested level via progress changed
    }
}