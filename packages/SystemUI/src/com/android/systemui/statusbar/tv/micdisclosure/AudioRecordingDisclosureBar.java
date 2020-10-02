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

import static android.provider.DeviceConfig.NAMESPACE_PRIVACY;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.annotation.IntDef;
import android.annotation.UiThread;
import android.content.Context;
import android.graphics.PixelFormat;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import com.android.systemui.R;
import com.android.systemui.statusbar.tv.TvStatusBar;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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

    private static final String ENABLED_FLAG = "mic_disclosure_enabled";
    private static final String EXEMPT_PACKAGES_LIST = "mic_disclosure_exempt_packages";
    private static final String FORCED_PACKAGES_LIST = "mic_disclosure_forced_packages";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"STATE_"}, value = {
            STATE_STOPPED,
            STATE_NOT_SHOWN,
            STATE_APPEARING,
            STATE_SHOWN,
            STATE_DISAPPEARING
    })
    public @interface State {}

    private static final int STATE_STOPPED = -1;
    private static final int STATE_NOT_SHOWN = 0;
    private static final int STATE_APPEARING = 1;
    private static final int STATE_SHOWN = 2;
    private static final int STATE_DISAPPEARING = 3;

    private static final int ANIMATION_DURATION_MS = 200;

    private final Context mContext;
    private boolean mIsEnabled;

    private View mIndicatorView;
    private boolean mViewAndWindowAdded;
    private ObjectAnimator mAnimator;

    @State private int mState = STATE_STOPPED;

    /**
     * Array of the observers that monitor different aspects of the system, such as AppOps and
     * microphone foreground services
     */
    private AudioActivityObserver[] mAudioActivityObservers;
    /**
     * Set of applications for which we make an exception and do not show the indicator. This gets
     * populated once - in {@link #AudioRecordingDisclosureBar(Context)}.
     */
    private final Set<String> mExemptPackages = new ArraySet<>();

    public AudioRecordingDisclosureBar(Context context) {
        mContext = context;

        // Load configs
        reloadExemptPackages();

        mIsEnabled = DeviceConfig.getBoolean(NAMESPACE_PRIVACY, ENABLED_FLAG, true);
        // Start if enabled
        if (mIsEnabled) {
            start();
        }

        // Set up a config change listener
        DeviceConfig.addOnPropertiesChangedListener(NAMESPACE_PRIVACY, mContext.getMainExecutor(),
                mConfigChangeListener);
    }

    private void reloadExemptPackages() {
        mExemptPackages.clear();
        mExemptPackages.addAll(Arrays.asList(mContext.getResources().getStringArray(
                R.array.audio_recording_disclosure_exempt_apps)));
        mExemptPackages.addAll(
                splitByComma(
                        DeviceConfig.getString(NAMESPACE_PRIVACY, EXEMPT_PACKAGES_LIST, null)));
        mExemptPackages.removeAll(
                splitByComma(
                        DeviceConfig.getString(NAMESPACE_PRIVACY, FORCED_PACKAGES_LIST, null)));
    }

    @UiThread
    private void start() {
        if (mState != STATE_STOPPED) {
            return;
        }
        mState = STATE_NOT_SHOWN;

        if (mAudioActivityObservers == null) {
            mAudioActivityObservers = new AudioActivityObserver[]{
                    new RecordAudioAppOpObserver(mContext, this),
                    new MicrophoneForegroundServicesObserver(mContext, this),
            };
        }

        for (int i = mAudioActivityObservers.length - 1; i >= 0; i--) {
            mAudioActivityObservers[i].start();
        }
    }

    @UiThread
    private void stop() {
        if (mState == STATE_STOPPED) {
            return;
        }
        mState = STATE_STOPPED;

        for (int i = mAudioActivityObservers.length - 1; i >= 0; i--) {
            mAudioActivityObservers[i].stop();
        }

        // Remove the view if shown.
        if (mState != STATE_NOT_SHOWN) {
            removeIndicatorView();
        }
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
            showIfNeeded();
        } else {
            hideIndicatorIfNeeded();
        }
    }

    @UiThread
    private void hideIndicatorIfNeeded() {
        // If STOPPED, NOT_SHOWN or DISAPPEARING - nothing else for us to do here.
        if (mState != STATE_SHOWN && mState != STATE_APPEARING) return;

        if (hasActiveRecorders()) {
            return;
        }

        if (mViewAndWindowAdded) {
            mState = STATE_DISAPPEARING;
            animateDisappearance();
        } else {
            // Appearing animation has not started yet, as we were still waiting for the View to be
            // laid out.
            mState = STATE_NOT_SHOWN;
            removeIndicatorView();
        }
    }

    @UiThread
    private void showIfNeeded() {
        // If STOPPED, SHOWN or APPEARING - nothing else for us to do here.
        if (mState != STATE_NOT_SHOWN && mState != STATE_DISAPPEARING) return;

        if (DEBUG) Log.d(TAG, "Showing indicator");

        final int prevState = mState;
        mState = STATE_APPEARING;

        if (prevState == STATE_DISAPPEARING) {
            animateAppearance();
            return;
        }

        // Inflate the indicator view
        mIndicatorView = LayoutInflater.from(mContext).inflate(
                R.layout.tv_audio_recording_indicator, null);

        // 1. Set alpha to 0.
        // 2. Wait until the window is shown and the view is laid out.
        // 3. Start a "fade in" (alpha) animation.
        mIndicatorView.setAlpha(0f);
        mIndicatorView
                .getViewTreeObserver()
                .addOnGlobalLayoutListener(
                        new ViewTreeObserver.OnGlobalLayoutListener() {
                            @Override
                            public void onGlobalLayout() {
                                // State could have changed to NOT_SHOWN (if all the recorders are
                                // already gone) to STOPPED (if the indicator was disabled)
                                if (mState != STATE_APPEARING) return;

                                mViewAndWindowAdded = true;
                                // Remove the observer
                                mIndicatorView.getViewTreeObserver().removeOnGlobalLayoutListener(
                                        this);

                                animateAppearance();
                            }
                        });

        final boolean isLtr = mContext.getResources().getConfiguration().getLayoutDirection()
                == View.LAYOUT_DIRECTION_LTR;
        final WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                WRAP_CONTENT,
                WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | (isLtr ? Gravity.RIGHT : Gravity.LEFT);
        layoutParams.setTitle(LAYOUT_PARAMS_TITLE);
        layoutParams.packageName = mContext.getPackageName();
        final WindowManager windowManager = (WindowManager) mContext.getSystemService(
                Context.WINDOW_SERVICE);
        windowManager.addView(mIndicatorView, layoutParams);
    }


    private void animateAppearance() {
        animateAlphaTo(1f);
    }

    private void animateDisappearance() {
        animateAlphaTo(0f);
    }

    private void animateAlphaTo(final float endValue) {
        if (mAnimator == null) {
            if (DEBUG) Log.d(TAG, "set up animator");

            mAnimator = new ObjectAnimator();
            mAnimator.setTarget(mIndicatorView);
            mAnimator.setProperty(View.ALPHA);
            mAnimator.addListener(new AnimatorListenerAdapter() {
                boolean mCancelled;

                @Override
                public void onAnimationStart(Animator animation, boolean isReverse) {
                    if (DEBUG) Log.d(TAG, "AnimatorListenerAdapter#onAnimationStart");
                    mCancelled = false;
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    if (DEBUG) Log.d(TAG, "AnimatorListenerAdapter#onAnimationCancel");
                    mCancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (DEBUG) Log.d(TAG, "AnimatorListenerAdapter#onAnimationEnd");
                    // When ValueAnimator#cancel() is called it always calls onAnimationCancel(...)
                    // and then onAnimationEnd(...). We, however, only want to proceed here if the
                    // animation ended "naturally".
                    if (!mCancelled) {
                        onAnimationFinished();
                    }
                }
            });
        } else if (mAnimator.isRunning()) {
            if (DEBUG) Log.d(TAG, "cancel running animation");
            mAnimator.cancel();
        }

        final float currentValue = mIndicatorView.getAlpha();
        if (DEBUG) Log.d(TAG, "animate alpha to " + endValue + " from " + currentValue);

        mAnimator.setDuration((int) (Math.abs(currentValue - endValue) * ANIMATION_DURATION_MS));
        mAnimator.setFloatValues(endValue);
        mAnimator.start();
    }

    private void onAnimationFinished() {
        if (DEBUG) Log.d(TAG, "onAnimationFinished");

        if (mState == STATE_APPEARING) {
            mState = STATE_SHOWN;
        } else if (mState == STATE_DISAPPEARING) {
            removeIndicatorView();
            mState = STATE_NOT_SHOWN;
        }
    }

    private boolean hasActiveRecorders() {
        for (int index = mAudioActivityObservers.length - 1; index >= 0; index--) {
            for (String activePackage : mAudioActivityObservers[index].getActivePackages()) {
                if (mExemptPackages.contains(activePackage)) continue;
                return true;
            }
        }
        return false;
    }

    private void removeIndicatorView() {
        if (DEBUG) Log.d(TAG, "removeIndicatorView");

        final WindowManager windowManager = (WindowManager) mContext.getSystemService(
                Context.WINDOW_SERVICE);
        windowManager.removeView(mIndicatorView);

        mIndicatorView = null;
        mAnimator = null;

        mViewAndWindowAdded = false;
    }

    private static List<String> splitByComma(String string) {
        return TextUtils.isEmpty(string) ? Collections.emptyList() : Arrays.asList(
                string.split(","));
    }

    private final DeviceConfig.OnPropertiesChangedListener mConfigChangeListener =
            new DeviceConfig.OnPropertiesChangedListener() {
                @Override
                public void onPropertiesChanged(DeviceConfig.Properties properties) {
                    reloadExemptPackages();

                    // Check if was enabled/disabled
                    if (mIsEnabled != properties.getBoolean(ENABLED_FLAG, true)) {
                        mIsEnabled = !mIsEnabled;
                        if (mIsEnabled) {
                            start();
                        } else {
                            stop();
                        }
                    }
                }
            };
}
