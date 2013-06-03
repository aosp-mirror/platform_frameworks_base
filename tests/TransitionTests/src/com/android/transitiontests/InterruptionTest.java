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
import android.view.transition.AutoTransition;
import android.view.transition.Move;
import android.view.transition.Scene;
import android.view.transition.TextChange;
import android.view.transition.Transition;
import android.view.transition.TransitionGroup;
import android.view.transition.TransitionManager;
import android.widget.RadioButton;

public class InterruptionTest extends Activity {

    RadioButton mScene1RB, mScene2RB, mScene3RB, mScene4RB;
    private Scene mScene1;
    private Scene mScene2;
    private Scene mScene3;
    private Scene mScene4;
    Transition mAutoTransition = new AutoTransition();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.interruption);

        View container = (View) findViewById(R.id.container);
        ViewGroup sceneRoot = (ViewGroup) findViewById(R.id.sceneRoot);

        mScene1 = new Scene(sceneRoot, R.layout.interruption_inner_1, this);
        mScene2 = new Scene(sceneRoot, R.layout.interruption_inner_2, this);
        mScene3 = new Scene(sceneRoot, R.layout.interruption_inner_3, this);
        mScene4 = new Scene(sceneRoot, R.layout.interruption_inner_4, this);

        mScene1RB = (RadioButton) findViewById(R.id.scene1RB);
        mScene2RB = (RadioButton) findViewById(R.id.scene2RB);
        mScene3RB = (RadioButton) findViewById(R.id.scene3RB);
        mScene4RB = (RadioButton) findViewById(R.id.scene4RB);

        sceneRoot.setCurrentScene(mScene1);

        mAutoTransition.setDuration(1500);
    }

    public void onRadioButtonClicked(View clickedButton) {
        if (clickedButton == mScene1RB) {
            TransitionManager.go(mScene1, mAutoTransition);
        } else if (clickedButton == mScene2RB) {
            TransitionManager.go(mScene2, mAutoTransition);
        } else if (clickedButton == mScene3RB) {
            TransitionManager.go(mScene3, mAutoTransition);
        } else {
            TransitionManager.go(mScene4, mAutoTransition);
        }
    }
}
