/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.google.android.systemui.lowlightclock;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.Nullable;
import android.service.dreams.DreamService;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextClock;
import android.widget.TextView;

import com.android.dream.lowlight.LowLightTransitionCoordinator;
import com.android.systemui.lowlightclock.ChargingStatusProvider;
import com.android.systemui.lowlightclock.LowLightClockAnimationProvider;
import com.android.systemui.lowlightclock.LowLightDisplayController;
import com.android.systemui.res.R;

import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * A dark themed text clock dream to be shown when the device is in a low light environment.
 */
public class LowLightClockDreamService extends DreamService implements
        LowLightTransitionCoordinator.LowLightExitListener {
    private static final String TAG = "LowLightClockDreamService";

    private final ChargingStatusProvider mChargingStatusProvider;
    private final LowLightDisplayController mDisplayController;
    private final LowLightClockAnimationProvider mAnimationProvider;
    private final LowLightTransitionCoordinator mLowLightTransitionCoordinator;
    private boolean mIsDimBrightnessSupported = false;

    private TextView mChargingStatusTextView;
    private TextClock mTextClock;
    @Nullable
    private Animator mAnimationIn;
    @Nullable
    private Animator mAnimationOut;

    @Inject
    public LowLightClockDreamService(
            ChargingStatusProvider chargingStatusProvider,
            LowLightClockAnimationProvider animationProvider,
            LowLightTransitionCoordinator lowLightTransitionCoordinator,
            Optional<Provider<LowLightDisplayController>> displayController) {
        super();

        mAnimationProvider = animationProvider;
        mDisplayController = displayController.map(Provider::get).orElse(null);
        mChargingStatusProvider = chargingStatusProvider;
        mLowLightTransitionCoordinator = lowLightTransitionCoordinator;
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        setInteractive(false);
        setFullscreen(true);

        setContentView(LayoutInflater.from(getApplicationContext()).inflate(
                R.layout.low_light_clock_dream, null));

        mTextClock = findViewById(R.id.low_light_text_clock);

        mChargingStatusTextView = findViewById(R.id.charging_status_text_view);

        mChargingStatusProvider.startUsing(this::updateChargingMessage);

        mLowLightTransitionCoordinator.setLowLightExitListener(this);
    }

    @Override
    public void onDreamingStarted() {
        mAnimationIn = mAnimationProvider.provideAnimationIn(mTextClock, mChargingStatusTextView);
        mAnimationIn.start();

        if (mDisplayController != null) {
            mIsDimBrightnessSupported = mDisplayController.isDisplayBrightnessModeSupported();

            if (mIsDimBrightnessSupported) {
                Log.v(TAG, "setting dim brightness state");
                mDisplayController.setDisplayBrightnessModeEnabled(true);
            } else {
                Log.v(TAG, "dim brightness not supported");
            }
        }
    }

    @Override
    public void onDreamingStopped() {
        if (mIsDimBrightnessSupported) {
            Log.v(TAG, "clearing dim brightness state");
            mDisplayController.setDisplayBrightnessModeEnabled(false);
        }
    }

    @Override
    public void onWakeUp() {
        if (mAnimationIn != null) {
            mAnimationIn.cancel();
        }
        mAnimationOut = mAnimationProvider.provideAnimationOut(mTextClock, mChargingStatusTextView);
        mAnimationOut.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animator) {
                LowLightClockDreamService.super.onWakeUp();
            }
        });
        mAnimationOut.start();
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (mAnimationOut != null) {
            mAnimationOut.cancel();
        }

        mChargingStatusProvider.stopUsing();

        mLowLightTransitionCoordinator.setLowLightExitListener(null);
    }

    private void updateChargingMessage(boolean showChargingStatus, String chargingStatusMessage) {
        mChargingStatusTextView.setText(chargingStatusMessage);
        mChargingStatusTextView.setVisibility(showChargingStatus ? View.VISIBLE : View.INVISIBLE);
    }

    @Override
    public Animator onBeforeExitLowLight() {
        mAnimationOut = mAnimationProvider.provideAnimationOut(mTextClock, mChargingStatusTextView);
        mAnimationOut.start();

        // Return the animator so that the transition coordinator waits for the low light exit
        // animations to finish before entering low light, as otherwise the default DreamActivity
        // animation plays immediately and there's no time for this animation to play.
        return mAnimationOut;
    }
}
