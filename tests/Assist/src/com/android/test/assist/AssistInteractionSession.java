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
 * limitations under the License
 */

package com.android.test.assist;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.app.VoiceInteractor;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.service.voice.VoiceInteractionSession;
import android.util.Log;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewTreeObserver;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

/**
 * Sample session to show test assist transition.
 */
public class AssistInteractionSession extends VoiceInteractionSession {

    private View mScrim;
    private View mBackground;
    private View mNavbarScrim;
    private View mCard1;
    private View mCard2;

    private float mDensity;

    public AssistInteractionSession(Context context) {
        super(context);
    }

    public AssistInteractionSession(Context context, Handler handler) {
        super(context, handler);
    }

    @Override
    public void onRequestConfirmation(ConfirmationRequest request) {
    }

    @Override
    public void onRequestPickOption(PickOptionRequest request) {
    }

    @Override
    public void onRequestCommand(CommandRequest request) {
    }

    @Override
    public void onCancelRequest(Request request) {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Simulate slowness of Assist app
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        getWindow().getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    @Override
    public View onCreateContentView() {
        View v = getLayoutInflater().inflate(R.layout.assist, null);
        mScrim = v.findViewById(R.id.scrim);
        mBackground = v.findViewById(R.id.background);
        mDensity = mScrim.getResources().getDisplayMetrics().density;
        mCard1 = v.findViewById(R.id.card1);
        mCard2 = v.findViewById(R.id.card2);
        mNavbarScrim = v.findViewById(R.id.navbar_scrim);
        return v;
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        if ((showFlags & SHOW_SOURCE_ASSIST_GESTURE) != 0) {
            mBackground.getViewTreeObserver().addOnPreDrawListener(
                    new ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            mBackground.getViewTreeObserver().removeOnPreDrawListener(this);
                            playAssistAnimation();
                            return true;
                        }
                    });
        }
    }

    @Override
    public void onLockscreenShown() {
        super.onLockscreenShown();
        Log.i("Assistant", "Lockscreen was shown");
    }

    private void playAssistAnimation() {
        Interpolator linearOutSlowIn = AnimationUtils.loadInterpolator(mBackground.getContext(),
                android.R.interpolator.linear_out_slow_in);
        Interpolator fastOutSlowIn = AnimationUtils.loadInterpolator(mBackground.getContext(),
                android.R.interpolator.fast_out_slow_in);
        mScrim.setAlpha(0f);
        mScrim.animate()
                .alpha(1f)
                .setStartDelay(100)
                .setDuration(500);
        mBackground.setTranslationY(50 * mDensity);
        mBackground.animate()
                .translationY(0)
                .setDuration(300)
                .setInterpolator(linearOutSlowIn);
        int centerX = mBackground.getWidth()/2;
        int centerY = (int) (mBackground.getHeight()/5*3.8f);
        int radius = (int) Math.sqrt(centerX*centerX + centerY*centerY) + 1;
        Animator animator = ViewAnimationUtils.createCircularReveal(mBackground, centerX, centerY,
                0, radius);
        animator.setDuration(300);
        animator.setInterpolator(fastOutSlowIn);
        animator.start();

        ValueAnimator colorAnim = ValueAnimator.ofArgb(Color.WHITE, 0xffe0e0e0);
        colorAnim.setDuration(300);
        colorAnim.setInterpolator(fastOutSlowIn);
        colorAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mBackground.setBackgroundColor((Integer) animation.getAnimatedValue());
            }
        });
        colorAnim.start();


        mCard1.setY(mBackground.getHeight());
        mCard2.setTranslationY(mCard1.getTranslationY());
        mCard1.animate()
                .translationY(0)
                .setDuration(500)
                .setInterpolator(linearOutSlowIn)
                .setStartDelay(100);
        mCard2.animate()
                .translationY(0)
                .setInterpolator(linearOutSlowIn)
                .setStartDelay(150)
                .setDuration(500);

        mNavbarScrim.setAlpha(0f);
        mNavbarScrim.animate()
                .alpha(1f)
                .setDuration(500)
                .setStartDelay(100);
    }

    @Override
    public void onHide() {
        super.onHide();
    }
}
