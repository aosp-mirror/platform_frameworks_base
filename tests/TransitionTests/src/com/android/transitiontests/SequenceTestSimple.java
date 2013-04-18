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
import android.view.View;
import android.view.ViewGroup;
import android.view.transition.Scene;
import android.widget.Button;
import android.view.transition.Fade;
import android.view.transition.Move;
import android.view.transition.Transition;
import android.view.transition.TransitionGroup;
import android.view.transition.TransitionManager;
import com.android.transitiontest.R;


public class SequenceTestSimple extends Activity {

    Button mRemovingButton, mInvisibleButton, mGoneButton;
    Scene mScene1, mScene2;
    ViewGroup mSceneRoot;
    Transition sequencedFade;
    TransitionGroup sequencedFadeReverse;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fading_test_simple);

        View container = (View) findViewById(R.id.container);
        mSceneRoot = (ViewGroup) container.getParent();

        mRemovingButton = (Button) findViewById(R.id.removingButton);

        mScene1 = new Scene(mSceneRoot, R.layout.fading_test_simple, this);
        mScene2 = new Scene(mSceneRoot, R.layout.fading_test_simple2, this);

        TransitionGroup fader = new TransitionGroup(TransitionGroup.SEQUENTIALLY);
        fader.addTransitions(new Fade().setTargetIds(R.id.removingButton));
        fader.addTransitions(new Move().setTargetIds(R.id.sceneSwitchButton));
        sequencedFade = fader;

        sequencedFadeReverse = new TransitionGroup(TransitionGroup.SEQUENTIALLY);
        sequencedFadeReverse.addTransitions(new Move().setTargetIds(R.id.sceneSwitchButton));
        sequencedFadeReverse.addTransitions(new Fade().setTargetIds(R.id.removingButton));

        mSceneRoot.setCurrentScene(mScene1);
    }

    public void sendMessage(View view) {
        if (mSceneRoot.getCurrentScene() == mScene1) {
            TransitionManager.go(mScene2, sequencedFade);
        } else {
            TransitionManager.go(mScene1, sequencedFadeReverse);
        }
    }}
