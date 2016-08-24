/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.tv.pip;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.app.Activity;
import android.graphics.drawable.AnimationDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.KeyEvent;
import android.widget.ImageView;

import com.android.systemui.R;

/**
 * Activity to show an overlay on top of PIP activity to show how to pop up PIP menu.
 */
public class PipOnboardingActivity extends Activity implements PipManager.Listener {
    private final PipManager mPipManager = PipManager.getInstance();
    private AnimatorSet mEnterAnimator;

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.tv_pip_onboarding);
        findViewById(R.id.button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        mPipManager.addListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        mEnterAnimator = new AnimatorSet();
        mEnterAnimator.playTogether(
                loadAnimator(R.id.background, R.anim.tv_pip_onboarding_background_enter_animation),
                loadAnimator(R.id.remote, R.anim.tv_pip_onboarding_image_enter_animation),
                loadAnimator(R.id.remote_button, R.anim.tv_pip_onboarding_image_enter_animation),
                loadAnimator(R.id.title, R.anim.tv_pip_onboarding_title_enter_animation),
                loadAnimator(R.id.description,
                        R.anim.tv_pip_onboarding_description_enter_animation),
                loadAnimator(R.id.button, R.anim.tv_pip_onboarding_button_enter_animation));
        mEnterAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                ImageView button = (ImageView) findViewById(R.id.remote_button);
                ((AnimationDrawable) button.getDrawable()).start();
            }
        });
        int delay = getResources().getInteger(R.integer.tv_pip_onboarding_anim_start_delay);
        mEnterAnimator.setStartDelay(delay);
        mEnterAnimator.start();
    }

    private Animator loadAnimator(int viewResId, int animResId) {
        Animator animator = AnimatorInflater.loadAnimator(this, animResId);
        animator.setTarget(findViewById(viewResId));
        return animator;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (mEnterAnimator.isStarted()) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mEnterAnimator.isStarted()) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onPause() {
        super.onPause();
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mPipManager.removeListener(this);
    }

    @Override
    public void onPipEntered() { }

    @Override
    public void onPipActivityClosed() {
        finish();
    }

    @Override
    public void onShowPipMenu() {
        finish();
    }

    @Override
    public void onMoveToFullscreen() {
        finish();
    }

    @Override
    public void onPipResizeAboutToStart() { }
}
