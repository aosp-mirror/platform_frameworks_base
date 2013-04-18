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
import android.view.transition.Transition;
import android.widget.Button;
import android.view.transition.Fade;
import android.view.transition.Move;
import android.view.transition.TransitionGroup;
import android.view.transition.TransitionManager;


public class SequenceTest extends Activity {

    Button mRemovingButton, mInvisibleButton, mGoneButton;
    Scene mScene1, mScene2;
    ViewGroup mSceneRoot;
    TransitionGroup sequencedFade, reverseSequencedFade;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fading_test);

        View container = (View) findViewById(R.id.container);
        mSceneRoot = (ViewGroup) container.getParent();

        mRemovingButton = (Button) findViewById(R.id.removingButton);
        mInvisibleButton = (Button) findViewById(R.id.invisibleButton);
        mGoneButton = (Button) findViewById(R.id.goneButton);

        mScene1 = new Scene(mSceneRoot, R.layout.fading_test, this);
        mScene2 = new Scene(mSceneRoot, R.layout.fading_test_scene_2, this);

        Transition fade1 = new Fade().setTargetIds(R.id.removingButton);
        Transition fade2 = new Fade().setTargetIds(R.id.invisibleButton);
        Transition fade3 = new Fade().setTargetIds(R.id.goneButton);
        TransitionGroup fader = new TransitionGroup(TransitionGroup.SEQUENTIALLY);
        fader.addTransitions(fade1, fade2, fade3, new Move());
        sequencedFade = fader;

        reverseSequencedFade = new TransitionGroup(TransitionGroup.SEQUENTIALLY);
        reverseSequencedFade.addTransitions(new Move(), fade3, fade2, fade1);

        mSceneRoot.setCurrentScene(mScene1);
    }

    public void sendMessage(View view) {
        if (mSceneRoot.getCurrentScene() == mScene1) {
            TransitionManager.go(mScene2, sequencedFade);
        } else {
            TransitionManager.go(mScene1, reverseSequencedFade);
        }
    }
}
