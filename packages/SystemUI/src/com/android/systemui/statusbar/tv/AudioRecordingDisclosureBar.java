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

package com.android.systemui.statusbar.tv;

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.annotation.IntDef;
import android.annotation.UiThread;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.util.ArraySet;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.TextView;

import com.android.systemui.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

/**
 * A component of {@link TvStatusBar} responsible for notifying the user whenever an application is
 * recording audio.
 *
 * @see TvStatusBar
 */
class AudioRecordingDisclosureBar {
    private static final String TAG = "AudioRecordingDisclosureBar";
    private static final boolean DEBUG = false;

    // This title is used to test the microphone disclosure indicator in
    // CtsSystemUiHostTestCases:TvMicrophoneCaptureIndicatorTest
    private static final String LAYOUT_PARAMS_TITLE = "MicrophoneCaptureIndicator";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"STATE_"}, value = {
            STATE_NOT_SHOWN,
            STATE_APPEARING,
            STATE_SHOWN,
            STATE_MINIMIZING,
            STATE_MINIMIZED,
            STATE_MAXIMIZING,
            STATE_DISAPPEARING
    })
    public @interface State {}

    private static final int STATE_NOT_SHOWN = 0;
    private static final int STATE_APPEARING = 1;
    private static final int STATE_SHOWN = 2;
    private static final int STATE_MINIMIZING = 3;
    private static final int STATE_MINIMIZED = 4;
    private static final int STATE_MAXIMIZING = 5;
    private static final int STATE_DISAPPEARING = 6;

    private static final int ANIMATION_DURATION = 600;
    private static final int MAXIMIZED_DURATION = 3000;
    private static final int PULSE_BIT_DURATION = 1000;
    private static final float PULSE_SCALE = 1.25f;

    private final Context mContext;

    private View mIndicatorView;
    private View mIconTextsContainer;
    private View mIconContainerBg;
    private View mIcon;
    private View mBgRight;
    private View mTextsContainers;
    private TextView mTextView;

    @State private int mState = STATE_NOT_SHOWN;
    /**
     * Set of the applications that currently are conducting audio recording.
     */
    private final Set<String> mActiveAudioRecordingPackages = new ArraySet<>();
    /**
     * Set of applications that we've notified the user about since the indicator came up. Meaning
     * that if an application is in this list then at some point since the indicator came up, it
     * was expanded showing this application's title.
     * Used not to notify the user about the same application again while the indicator is shown.
     * We empty this set every time the indicator goes off the screen (we always call {@code
     * mSessionNotifiedPackages.clear()} before calling {@link #hide()}).
     */
    private final Set<String> mSessionNotifiedPackages = new ArraySet<>();
    /**
     * If an application starts recording while the TV indicator is neither in {@link
     * #STATE_NOT_SHOWN} nor in {@link #STATE_MINIMIZED}, then we add the application's package
     * name to the queue, from which we take packages names one by one to disclose the
     * corresponding applications' titles to the user, whenever the indicator eventually comes to
     * one of the two aforementioned states.
     */
    private final Queue<String> mPendingNotificationPackages = new LinkedList<>();

    AudioRecordingDisclosureBar(Context context) {
        mContext = context;
    }

    void start() {
        // Register AppOpsManager callback
        final AppOpsManager appOpsManager = (AppOpsManager) mContext.getSystemService(
                Context.APP_OPS_SERVICE);
        appOpsManager.startWatchingActive(
                new String[]{AppOpsManager.OPSTR_RECORD_AUDIO},
                mContext.getMainExecutor(),
                new OnActiveRecordingListener());
    }

    @UiThread
    private void onStartedRecording(String packageName) {
        if (!mActiveAudioRecordingPackages.add(packageName)) {
            // This app is already known to perform recording
            return;
        }
        if (!mSessionNotifiedPackages.add(packageName)) {
            // We've already notified user about this app, no need to do it again.
            return;
        }

        switch (mState) {
            case STATE_NOT_SHOWN:
                show(packageName);
                break;

            case STATE_MINIMIZED:
                expand(packageName);
                break;

            case STATE_DISAPPEARING:
            case STATE_APPEARING:
            case STATE_MAXIMIZING:
            case STATE_SHOWN:
            case STATE_MINIMIZING:
                // Currently animating or expanded. Thus add to the pending notifications, and it
                // will be picked up once the indicator comes to the STATE_MINIMIZED.
                mPendingNotificationPackages.add(packageName);
                break;
        }
    }

    @UiThread
    private void onDoneRecording(String packageName) {
        if (!mActiveAudioRecordingPackages.remove(packageName)) {
            // Was not marked as an active recorder, do nothing
            return;
        }

        // If not MINIMIZED, will check whether the indicator should be hidden when the indicator
        // comes to the STATE_MINIMIZED eventually. If is in the STATE_MINIMIZED, but there are
        // other active recorders - simply ignore.
        if (mState == STATE_MINIMIZED && mActiveAudioRecordingPackages.isEmpty()) {
            mSessionNotifiedPackages.clear();
            hide();
        }
    }

    @UiThread
    private void show(String packageName) {
        // Inflate the indicator view
        mIndicatorView = LayoutInflater.from(mContext).inflate(
                R.layout.tv_audio_recording_indicator,
                null);
        mIconTextsContainer = mIndicatorView.findViewById(R.id.icon_texts_container);
        mIconContainerBg = mIconTextsContainer.findViewById(R.id.icon_container_bg);
        mIcon = mIconTextsContainer.findViewById(R.id.icon_mic);
        mTextsContainers = mIconTextsContainer.findViewById(R.id.texts_container);
        mTextView = mTextsContainers.findViewById(R.id.text);
        mBgRight = mIndicatorView.findViewById(R.id.bg_right);

        // Set up the notification text
        final String label = getApplicationLabel(packageName);
        mTextView.setText(mContext.getString(R.string.app_accessed_mic, label));

        // Initially change the visibility to INVISIBLE, wait until and receives the size and
        // then animate it moving from "off" the screen correctly
        mIndicatorView.setVisibility(View.INVISIBLE);
        mIndicatorView
                .getViewTreeObserver()
                .addOnGlobalLayoutListener(
                        new ViewTreeObserver.OnGlobalLayoutListener() {
                            @Override
                            public void onGlobalLayout() {
                                // Remove the observer
                                mIndicatorView.getViewTreeObserver().removeOnGlobalLayoutListener(
                                        this);

                                // Now that the width of the indicator has been assigned, we can
                                // move it in from off the screen.
                                final int initialOffset = mIndicatorView.getWidth();
                                final AnimatorSet set = new AnimatorSet();
                                set.setDuration(ANIMATION_DURATION);
                                set.playTogether(
                                        ObjectAnimator.ofFloat(mIndicatorView,
                                                View.TRANSLATION_X, initialOffset, 0),
                                        ObjectAnimator.ofFloat(mIndicatorView, View.ALPHA, 0f,
                                                1f));
                                set.addListener(
                                        new AnimatorListenerAdapter() {
                                            @Override
                                            public void onAnimationStart(Animator animation,
                                                    boolean isReverse) {
                                                // Indicator is INVISIBLE at the moment, change it.
                                                mIndicatorView.setVisibility(View.VISIBLE);
                                            }

                                            @Override
                                            public void onAnimationEnd(Animator animation) {
                                                startPulsatingAnimation();
                                                onExpanded();
                                            }
                                        });
                                set.start();
                            }
                        });

        final WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                WRAP_CONTENT,
                WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | Gravity.RIGHT;
        layoutParams.setTitle(LAYOUT_PARAMS_TITLE);
        layoutParams.packageName = mContext.getPackageName();
        final WindowManager windowManager = (WindowManager) mContext.getSystemService(
                Context.WINDOW_SERVICE);
        windowManager.addView(mIndicatorView, layoutParams);

        mState = STATE_APPEARING;
    }

    @UiThread
    private void expand(String packageName) {
        final String label = getApplicationLabel(packageName);
        mTextView.setText(mContext.getString(R.string.app_accessed_mic, label));

        final AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(mIconTextsContainer, View.TRANSLATION_X, 0),
                ObjectAnimator.ofFloat(mIconContainerBg, View.ALPHA, 1f),
                ObjectAnimator.ofFloat(mTextsContainers, View.ALPHA, 1f),
                ObjectAnimator.ofFloat(mBgRight, View.ALPHA, 1f));
        set.setDuration(ANIMATION_DURATION);
        set.addListener(
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        onExpanded();
                    }
                });
        set.start();

        mState = STATE_MAXIMIZING;
    }

    @UiThread
    private void minimize() {
        final int targetOffset = mTextsContainers.getWidth();
        final AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(mIconTextsContainer, View.TRANSLATION_X, targetOffset),
                ObjectAnimator.ofFloat(mIconContainerBg, View.ALPHA, 0f),
                ObjectAnimator.ofFloat(mTextsContainers, View.ALPHA, 0f),
                ObjectAnimator.ofFloat(mBgRight, View.ALPHA, 0f));
        set.setDuration(ANIMATION_DURATION);
        set.addListener(
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        onMinimized();
                    }
                });
        set.start();

        mState = STATE_MINIMIZING;
    }

    @UiThread
    private void hide() {
        final int targetOffset =
                mIndicatorView.getWidth() - (int) mIconTextsContainer.getTranslationX();
        final AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(mIndicatorView, View.TRANSLATION_X, targetOffset),
                ObjectAnimator.ofFloat(mIcon, View.ALPHA, 0f));
        set.setDuration(ANIMATION_DURATION);
        set.addListener(
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        onHidden();
                    }
                });
        set.start();

        mState = STATE_DISAPPEARING;
    }

    @UiThread
    private void onExpanded() {
        mState = STATE_SHOWN;

        mIndicatorView.postDelayed(this::minimize, MAXIMIZED_DURATION);
    }

    @UiThread
    private void onMinimized() {
        mState = STATE_MINIMIZED;

        if (!mPendingNotificationPackages.isEmpty()) {
            // There is a new application that started recording, tell the user about it.
            expand(mPendingNotificationPackages.poll());
        } else if (mActiveAudioRecordingPackages.isEmpty()) {
            // Nobody is recording anymore, clear state and remove the indicator.
            mSessionNotifiedPackages.clear();
            hide();
        }
    }

    @UiThread
    private void onHidden() {
        final WindowManager windowManager = (WindowManager) mContext.getSystemService(
                Context.WINDOW_SERVICE);
        windowManager.removeView(mIndicatorView);

        mIndicatorView = null;
        mIconTextsContainer = null;
        mIconContainerBg = null;
        mIcon = null;
        mTextsContainers = null;
        mTextView = null;
        mBgRight = null;

        mState = STATE_NOT_SHOWN;

        // Check if anybody started recording while we were in STATE_DISAPPEARING
        if (!mPendingNotificationPackages.isEmpty()) {
            // There is a new application that started recording, tell the user about it.
            show(mPendingNotificationPackages.poll());
        }
    }

    @UiThread
    private void startPulsatingAnimation() {
        final View pulsatingView = mIconTextsContainer.findViewById(R.id.pulsating_circle);
        final ObjectAnimator animator =
                ObjectAnimator.ofPropertyValuesHolder(
                        pulsatingView,
                        PropertyValuesHolder.ofFloat(View.SCALE_X, PULSE_SCALE),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, PULSE_SCALE));
        animator.setDuration(PULSE_BIT_DURATION);
        animator.setRepeatCount(ObjectAnimator.INFINITE);
        animator.setRepeatMode(ObjectAnimator.REVERSE);
        animator.start();
    }

    private String getApplicationLabel(String packageName) {
        final PackageManager pm = mContext.getPackageManager();
        final ApplicationInfo appInfo;
        try {
            appInfo = pm.getApplicationInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
        return pm.getApplicationLabel(appInfo).toString();
    }

    private class OnActiveRecordingListener implements AppOpsManager.OnOpActiveChangedListener {
        private final Set<String> mExemptApps;

        private OnActiveRecordingListener() {
            mExemptApps = new ArraySet<>(Arrays.asList(mContext.getResources().getStringArray(
                    R.array.audio_recording_disclosure_exempt_apps)));
        }

        @Override
        public void onOpActiveChanged(String op, int uid, String packageName, boolean active) {
            if (DEBUG) {
                Log.d(TAG,
                        "OP_RECORD_AUDIO active change, active=" + active + ", app="
                                + packageName);
            }

            if (mExemptApps.contains(packageName)) {
                if (DEBUG) {
                    Log.d(TAG, "\t- exempt app");
                }
                return;
            }

            if (active) {
                onStartedRecording(packageName);
            } else {
                onDoneRecording(packageName);
            }
        }
    }
}
