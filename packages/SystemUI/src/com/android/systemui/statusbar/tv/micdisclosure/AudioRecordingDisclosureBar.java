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

package com.android.systemui.statusbar.tv.micdisclosure;

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.annotation.IntDef;
import android.annotation.UiThread;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.tv.TvStatusBar;

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
public class AudioRecordingDisclosureBar implements
        AudioActivityObserver.OnAudioActivityStateChangeListener {
    private static final String TAG = "AudioRecordingDisclosure";
    static final boolean DEBUG = false;

    // This title is used to test the microphone disclosure indicator in
    // CtsSystemUiHostTestCases:TvMicrophoneCaptureIndicatorTest
    private static final String LAYOUT_PARAMS_TITLE = "MicrophoneCaptureIndicator";

    private static final String EXEMPT_PACKAGES_LIST = "sysui_mic_disclosure_exempt";
    private static final String FORCED_PACKAGES_LIST = "sysui_mic_disclosure_forced";

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
    private View mBgEnd;
    private View mTextsContainers;
    private TextView mTextView;
    private boolean mIsLtr;

    @State private int mState = STATE_NOT_SHOWN;

    /**
     * Array of the observers that monitor different aspects of the system, such as AppOps and
     * microphone foreground services
     */
    private final AudioActivityObserver[] mAudioActivityObservers;
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
    /**
     * Set of applications for which we make an exception and do not show the indicator. This gets
     * populated once - in {@link #AudioRecordingDisclosureBar(Context)}.
     */
    private final Set<String> mExemptPackages;

    public AudioRecordingDisclosureBar(Context context) {
        mContext = context;

        mExemptPackages = new ArraySet<>(
                Arrays.asList(mContext.getResources().getStringArray(
                        R.array.audio_recording_disclosure_exempt_apps)));
        mExemptPackages.addAll(Arrays.asList(getGlobalStringArray(EXEMPT_PACKAGES_LIST)));
        mExemptPackages.removeAll(Arrays.asList(getGlobalStringArray(FORCED_PACKAGES_LIST)));

        mAudioActivityObservers = new AudioActivityObserver[]{
                new RecordAudioAppOpObserver(mContext, this),
                new MicrophoneForegroundServicesObserver(mContext, this),
        };
    }

    private String[] getGlobalStringArray(String setting) {
        String result = Settings.Global.getString(mContext.getContentResolver(), setting);
        return TextUtils.isEmpty(result) ? new String[0] : result.split(",");
    }

    @UiThread
    @Override
    public void onAudioActivityStateChange(boolean active, String packageName) {
        if (DEBUG) {
            Log.d(TAG,
                    "onAudioActivityStateChange, packageName=" + packageName + ", active="
                            + active);
        }

        if (mExemptPackages.contains(packageName)) {
            if (DEBUG) Log.d(TAG, "   - exempt package: ignoring");
            return;
        }

        if (active) {
            showIndicatorForPackageIfNeeded(packageName);
        } else {
            hideIndicatorIfNeeded();
        }
    }

    @UiThread
    private void showIndicatorForPackageIfNeeded(String packageName) {
        if (DEBUG) Log.d(TAG, "showIndicatorForPackageIfNeeded, packageName=" + packageName);
        if (!mSessionNotifiedPackages.add(packageName)) {
            // We've already notified user about this app, no need to do it again.
            if (DEBUG) Log.d(TAG, "   - already notified");
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
    private void hideIndicatorIfNeeded() {
        if (DEBUG) Log.d(TAG, "hideIndicatorIfNeeded");
        // If not MINIMIZED, will check whether the indicator should be hidden when the indicator
        // comes to the STATE_MINIMIZED eventually.
        if (mState != STATE_MINIMIZED) return;

        // If is in the STATE_MINIMIZED, but there are other active recorders - simply ignore.
        for (int index = mAudioActivityObservers.length - 1; index >= 0; index--) {
            for (String activePackage : mAudioActivityObservers[index].getActivePackages()) {
                if (mExemptPackages.contains(activePackage)) continue;
                if (DEBUG) Log.d(TAG, "   - there are still ongoing activities");
                return;
            }
        }

        // Clear the state and hide the indicator.
        mSessionNotifiedPackages.clear();
        hide();
    }

    @UiThread
    private void show(String packageName) {
        final String label = getApplicationLabel(packageName);
        if (DEBUG) {
            Log.d(TAG, "Showing indicator for " + packageName + " (" + label + ")...");
        }

        mIsLtr = mContext.getResources().getConfiguration().getLayoutDirection()
                == View.LAYOUT_DIRECTION_LTR;

        // Inflate the indicator view
        mIndicatorView = LayoutInflater.from(mContext).inflate(
                R.layout.tv_audio_recording_indicator,
                null);
        mIconTextsContainer = mIndicatorView.findViewById(R.id.icon_texts_container);
        mIconContainerBg = mIconTextsContainer.findViewById(R.id.icon_container_bg);
        mIcon = mIconTextsContainer.findViewById(R.id.icon_mic);
        mTextsContainers = mIconTextsContainer.findViewById(R.id.texts_container);
        mTextView = mTextsContainers.findViewById(R.id.text);
        mBgEnd = mIndicatorView.findViewById(R.id.bg_end);

        // Swap background drawables depending on layout directions (both drawables have rounded
        // corners only on one side)
        if (mIsLtr) {
            mBgEnd.setBackgroundResource(R.drawable.tv_rect_dark_right_rounded);
            mIconContainerBg.setBackgroundResource(R.drawable.tv_rect_dark_left_rounded);
        } else {
            mBgEnd.setBackgroundResource(R.drawable.tv_rect_dark_left_rounded);
            mIconContainerBg.setBackgroundResource(R.drawable.tv_rect_dark_right_rounded);
        }

        // Set up the notification text
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
                                final int initialOffset =
                                        (mIsLtr ? 1 : -1) * mIndicatorView.getWidth();
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
        layoutParams.gravity = Gravity.TOP | (mIsLtr ? Gravity.RIGHT : Gravity.LEFT);
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
        if (DEBUG) {
            Log.d(TAG, "Expanding for " + packageName + " (" + label + ")...");
        }
        mTextView.setText(mContext.getString(R.string.app_accessed_mic, label));

        final AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(mIconTextsContainer, View.TRANSLATION_X, 0),
                ObjectAnimator.ofFloat(mIconContainerBg, View.ALPHA, 1f),
                ObjectAnimator.ofFloat(mTextsContainers, View.ALPHA, 1f),
                ObjectAnimator.ofFloat(mBgEnd, View.ALPHA, 1f));
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
        if (DEBUG) Log.d(TAG, "Minimizing...");
        final int targetOffset = (mIsLtr ? 1 : -1) * mTextsContainers.getWidth();
        final AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(mIconTextsContainer, View.TRANSLATION_X, targetOffset),
                ObjectAnimator.ofFloat(mIconContainerBg, View.ALPHA, 0f),
                ObjectAnimator.ofFloat(mTextsContainers, View.ALPHA, 0f),
                ObjectAnimator.ofFloat(mBgEnd, View.ALPHA, 0f));
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
        if (DEBUG) Log.d(TAG, "Hiding...");
        final int targetOffset = (mIsLtr ? 1 : -1) * (mIndicatorView.getWidth()
                - (int) mIconTextsContainer.getTranslationX());
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
        if (DEBUG) Log.d(TAG, "Expanded");
        mState = STATE_SHOWN;

        mIndicatorView.postDelayed(this::minimize, MAXIMIZED_DURATION);
    }

    @UiThread
    private void onMinimized() {
        if (DEBUG) Log.d(TAG, "Minimized");
        mState = STATE_MINIMIZED;

        if (!mPendingNotificationPackages.isEmpty()) {
            // There is a new application that started recording, tell the user about it.
            expand(mPendingNotificationPackages.poll());
        } else {
            hideIndicatorIfNeeded();
        }
    }

    @UiThread
    private void onHidden() {
        if (DEBUG) Log.d(TAG, "Hidden");

        final WindowManager windowManager = (WindowManager) mContext.getSystemService(
                Context.WINDOW_SERVICE);
        windowManager.removeView(mIndicatorView);

        mIndicatorView = null;
        mIconTextsContainer = null;
        mIconContainerBg = null;
        mIcon = null;
        mTextsContainers = null;
        mTextView = null;
        mBgEnd = null;

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
}
