/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.transitiontests;

import android.app.Activity;
import android.os.Bundle;
import android.transition.ChangeBounds;
import android.view.View;
import android.view.ViewGroup;
import android.transition.Scene;
import android.transition.TransitionSet;
import android.widget.Button;
import android.transition.Fade;
import android.transition.Transition;
import android.transition.TransitionManager;


public class SequenceTestSimple extends Activity {

    Button mRemovingButton, mInvisibleButton, mGoneButton;
    Scene mScene1, mScene2;
    ViewGroup mSceneRoot;
    Transition sequencedFade;
    TransitionSet sequencedFadeReverse;
    Scene mCurrentScene;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fading_test_simple);

        View container = findViewById(R.id.container);
        mSceneRoot = (ViewGroup) container.getParent();

        mRemovingButton = findViewById(R.id.removingButton);

        mScene1 = Scene.getSceneForLayout(mSceneRoot, R.layout.fading_test_simple, this);
        mScene2 = Scene.getSceneForLayout(mSceneRoot, R.layout.fading_test_simple2, this);

        TransitionSet fader = new TransitionSet().
                setOrdering(TransitionSet.ORDERING_SEQUENTIAL);
        fader.addTransition(new Fade().addTarget(R.id.removingButton));
        fader.addTransition(new ChangeBounds().addTarget(R.id.sceneSwitchButton));
        sequencedFade = fader;

        sequencedFadeReverse = new TransitionSet().
                setOrdering(TransitionSet.ORDERING_SEQUENTIAL);
        sequencedFadeReverse.addTransition(new ChangeBounds().addTarget(R.id.sceneSwitchButton));
        sequencedFadeReverse.addTransition(new Fade().addTarget(R.id.removingButton));

        mCurrentScene = mScene1;
    }

    public void sendMessage(View view) {
        if (mCurrentScene == mScene1) {
            TransitionManager.go(mScene2, sequencedFade);
            mCurrentScene = mScene2;
        } else {
            TransitionManager.go(mScene1, sequencedFadeReverse);
            mCurrentScene = mScene1;
        }
    }}
