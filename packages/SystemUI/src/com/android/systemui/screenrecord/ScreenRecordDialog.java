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

package com.android.systemui.screenrecord;

import static android.app.Activity.RESULT_OK;

import static com.android.systemui.media.MediaProjectionAppSelectorActivity.EXTRA_CAPTURE_REGION_RESULT_RECEIVER;
import static com.android.systemui.media.MediaProjectionAppSelectorActivity.KEY_CAPTURE_TARGET;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.INTERNAL;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.MIC;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.MIC_AND_INTERNAL;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.NONE;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.android.systemui.Prefs;

import androidx.annotation.Nullable;

import com.android.systemui.R;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.media.MediaProjectionAppSelectorActivity;
import com.android.systemui.media.MediaProjectionCaptureTarget;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.settings.UserContextProvider;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import java.util.Arrays;
import java.util.List;

/**
 * Dialog to select screen recording options
 */
public class ScreenRecordDialog extends SystemUIDialog {
    private static final List<ScreenRecordingAudioSource> MODES = Arrays.asList(INTERNAL, MIC,
            MIC_AND_INTERNAL);
    private static final long DELAY_MS = 3000;
    private static final long INTERVAL_MS = 1000;
    private static final String TAG = "ScreenRecordDialog";
    private static final String PREFS = "screenrecord_";
    private static final String PREF_TAPS = "show_taps";
    private static final String PREF_DOT = "show_dot";
    private static final String PREF_LOW = "use_low_quality";
    private static final String PREF_LONGER = "use_longer_timeout";
    private static final String PREF_AUDIO = "use_audio";
    private static final String PREF_AUDIO_SOURCE = "audio_source";

    private final RecordingController mController;
    private final UserContextProvider mUserContextProvider;
    @Nullable
    private final Runnable mOnStartRecordingClicked;
    private final ActivityStarter mActivityStarter;
    private final FeatureFlags mFlags;
    private final DialogLaunchAnimator mDialogLaunchAnimator;
    private Switch mTapsSwitch;
    private Switch mStopDotSwitch;
    private Switch mLowQualitySwitch;
    private Switch mLongerSwitch;
    private Switch mAudioSwitch;
    private Spinner mOptions;

    public ScreenRecordDialog(Context context, RecordingController controller,
            ActivityStarter activityStarter, UserContextProvider userContextProvider,
            FeatureFlags flags, DialogLaunchAnimator dialogLaunchAnimator,
            @Nullable Runnable onStartRecordingClicked) {
        super(context);
        mController = controller;
        mUserContextProvider = userContextProvider;
        mActivityStarter = activityStarter;
        mDialogLaunchAnimator = dialogLaunchAnimator;
        mFlags = flags;
        mOnStartRecordingClicked = onStartRecordingClicked;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();

        window.addPrivateFlags(WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS);

        window.setGravity(Gravity.BOTTOM);
        setTitle(R.string.screenrecord_name);

        setContentView(R.layout.screen_record_dialog);

        TextView cancelBtn = findViewById(R.id.button_cancel);
        cancelBtn.setOnClickListener(v -> dismiss());

        TextView startBtn = findViewById(R.id.button_start);
        startBtn.setOnClickListener(v -> {
            if (mOnStartRecordingClicked != null) {
                // Note that it is important to run this callback before dismissing, so that the
                // callback can disable the dialog exit animation if it wants to.
                mOnStartRecordingClicked.run();
            }

            // Start full-screen recording
            requestScreenCapture(/* captureTarget= */ null);
            dismiss();
        });

        if (mFlags != null && mFlags.isEnabled(Flags.WM_ENABLE_PARTIAL_SCREEN_SHARING)) {
            TextView appBtn = findViewById(R.id.button_app);

            appBtn.setVisibility(View.VISIBLE);
            appBtn.setOnClickListener(v -> {
                Intent intent = new Intent(getContext(), MediaProjectionAppSelectorActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                // We can't start activity for result here so we use result receiver to get
                // the selected target to capture
                intent.putExtra(EXTRA_CAPTURE_REGION_RESULT_RECEIVER,
                        new CaptureTargetResultReceiver());

                ActivityLaunchAnimator.Controller animationController =
                        mDialogLaunchAnimator.createActivityLaunchController(appBtn);

                if (animationController == null) {
                    dismiss();
                }

                mActivityStarter.startActivity(intent, /* dismissShade= */ true,
                        animationController);
            });
        }

        mAudioSwitch = findViewById(R.id.screenrecord_audio_switch);
        mTapsSwitch = findViewById(R.id.screenrecord_taps_switch);
        mStopDotSwitch = findViewById(R.id.screenrecord_stopdot_switch);
        mLowQualitySwitch = findViewById(R.id.screenrecord_lowquality_switch);
        mLongerSwitch = findViewById(R.id.screenrecord_longer_timeout_switch);
        mOptions = findViewById(R.id.screen_recording_options);
        ArrayAdapter a = new ScreenRecordingAdapter(getContext().getApplicationContext(),
                android.R.layout.simple_spinner_dropdown_item,
                MODES);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mOptions.setAdapter(a);
        mOptions.setOnItemClickListenerInt((parent, view, position, id) -> {
            mAudioSwitch.setChecked(true);
        });

        loadPrefs();
    }

    /**
     * Starts screen capture after some countdown
     * @param captureTarget target to capture (could be e.g. a task) or
     *                      null to record the whole screen
     */
    private void requestScreenCapture(@Nullable MediaProjectionCaptureTarget captureTarget) {
        Context userContext = mUserContextProvider.getUserContext();
        savePrefs();
        boolean showTaps = mTapsSwitch.isChecked();
        boolean showStopDot = mStopDotSwitch.isChecked();
        boolean lowQuality = mLowQualitySwitch.isChecked();
        boolean longerDuration = mLongerSwitch.isChecked();
        ScreenRecordingAudioSource audioMode = mAudioSwitch.isChecked()
                ? (ScreenRecordingAudioSource) mOptions.getSelectedItem()
                : NONE;
        PendingIntent startIntent = PendingIntent.getForegroundService(userContext,
                RecordingService.REQUEST_CODE,
                RecordingService.getStartIntent(
                        userContext, Activity.RESULT_OK,
                        audioMode.ordinal(), showTaps, showStopDot, lowQuality,
                        longerDuration, captureTarget),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopIntent = PendingIntent.getService(userContext,
                RecordingService.REQUEST_CODE,
                RecordingService.getStopIntent(userContext),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        mController.startCountdown(DELAY_MS, INTERVAL_MS, startIntent, stopIntent);
    }

    private class CaptureTargetResultReceiver extends ResultReceiver {

        CaptureTargetResultReceiver() {
            super(new Handler(Looper.getMainLooper()));
        }

        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            if (resultCode == RESULT_OK) {
                MediaProjectionCaptureTarget captureTarget = resultData
                        .getParcelable(KEY_CAPTURE_TARGET, MediaProjectionCaptureTarget.class);

                // Start recording of the selected target
                requestScreenCapture(captureTarget);
            }
        }
    }

    private void savePrefs() {
        Context userContext = mUserContextProvider.getUserContext();
        Prefs.putInt(userContext, PREFS + PREF_TAPS, mTapsSwitch.isChecked() ? 1 : 0);
        Prefs.putInt(userContext, PREFS + PREF_DOT, mStopDotSwitch.isChecked() ? 1 : 0);
        Prefs.putInt(userContext, PREFS + PREF_LOW, mLowQualitySwitch.isChecked() ? 1 : 0);
        Prefs.putInt(userContext, PREFS + PREF_LONGER, mLongerSwitch.isChecked() ? 1 : 0);
        Prefs.putInt(userContext, PREFS + PREF_AUDIO, mAudioSwitch.isChecked() ? 1 : 0);
        Prefs.putInt(userContext, PREFS + PREF_AUDIO_SOURCE, mOptions.getSelectedItemPosition());
    }

    private void loadPrefs() {
        Context userContext = mUserContextProvider.getUserContext();
        mTapsSwitch.setChecked(Prefs.getInt(userContext, PREFS + PREF_TAPS, 0) == 1);
        mStopDotSwitch.setChecked(Prefs.getInt(userContext, PREFS + PREF_DOT, 0) == 1);
        mLowQualitySwitch.setChecked(Prefs.getInt(userContext, PREFS + PREF_LOW, 0) == 1);
        mLongerSwitch.setChecked(Prefs.getInt(userContext, PREFS + PREF_LONGER, 0) == 1);
        mAudioSwitch.setChecked(Prefs.getInt(userContext, PREFS + PREF_AUDIO, 0) == 1);
        mOptions.setSelection(Prefs.getInt(userContext, PREFS + PREF_AUDIO_SOURCE, 0));
    }
}
