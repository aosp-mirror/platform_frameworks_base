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
import android.animation.AnimatorSet;
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

    private static final int ANIMATION_DURATION = 600;

    private final Context mContext;
    private boolean mIsEnabled;

    private View mIndicatorView;
    private boolean mIsLtr;

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
            showIfNotShown();
        } else {
            hideIndicatorIfNeeded();
        }
    }

    @UiThread
    private void hideIndicatorIfNeeded() {
        // If not STATE_APPEARING, will check whether the indicator should be hidden when the
        // indicator comes to the STATE_SHOWN.
        // If STATE_DISAPPEARING or STATE_SHOWN - nothing else for us to do here.
        if (mState != STATE_SHOWN) return;

        // If is in the STATE_SHOWN and there are no active recorders - hide.
        if (!hasActiveRecorders()) {
            hide();
        }
    }

    @UiThread
    private void showIfNotShown() {
        if (mState != STATE_NOT_SHOWN) return;
        if (DEBUG) Log.d(TAG, "Showing indicator");

        mIsLtr = mContext.getResources().getConfiguration().getLayoutDirection()
                == View.LAYOUT_DIRECTION_LTR;

        // Inflate the indicator view
        mIndicatorView = LayoutInflater.from(mContext).inflate(
                R.layout.tv_audio_recording_indicator,
                null);

        // Initially change the visibility to INVISIBLE, wait until and receives the size and
        // then animate it moving from "off" the screen correctly
        mIndicatorView.setVisibility(View.INVISIBLE);
        mIndicatorView
                .getViewTreeObserver()
                .addOnGlobalLayoutListener(
                        new ViewTreeObserver.OnGlobalLayoutListener() {
                            @Override
                            public void onGlobalLayout() {
                                if (mState == STATE_STOPPED) {
                                    return;
                                }

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
                                                if (mState == STATE_STOPPED) return;

                                                // Indicator is INVISIBLE at the moment, change it.
                                                mIndicatorView.setVisibility(View.VISIBLE);
                                            }

                                            @Override
                                            public void onAnimationEnd(Animator animation) {
                                                onAppeared();
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
    private void hide() {
        if (DEBUG) Log.d(TAG, "Hide indicator");

        final int targetOffset = (mIsLtr ? 1 : -1) * mIndicatorView.getWidth();
        final AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(mIndicatorView, View.TRANSLATION_X, targetOffset),
                ObjectAnimator.ofFloat(mIndicatorView, View.ALPHA, 0f));
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
    private void onAppeared() {
        if (mState == STATE_STOPPED) return;

        mState = STATE_SHOWN;

        hideIndicatorIfNeeded();
    }

    @UiThread
    private void onHidden() {
        if (mState == STATE_STOPPED) return;

        removeIndicatorView();
        mState = STATE_NOT_SHOWN;

        if (hasActiveRecorders()) {
            // Got new recorders, show again.
            showIfNotShown();
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
        final WindowManager windowManager = (WindowManager) mContext.getSystemService(
                Context.WINDOW_SERVICE);
        windowManager.removeView(mIndicatorView);

        mIndicatorView = null;
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
