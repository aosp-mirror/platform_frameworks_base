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
import android.transition.TransitionManager;
import android.widget.RadioButton;

public class InterruptionTest extends Activity {

    RadioButton mScene1RB, mScene2RB, mScene3RB, mScene4RB;
    private Scene mScene1;
    private Scene mScene2;
    private Scene mScene3;
    private Scene mScene4;
    TransitionSet mSequencedMove = new TransitionSet().
            setOrdering(TransitionSet.ORDERING_SEQUENTIAL);

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.interruption);

        ViewGroup sceneRoot = findViewById(R.id.sceneRoot);

        mScene1 = Scene.getSceneForLayout(sceneRoot, R.layout.interruption_inner_1, this);
        mScene2 = Scene.getSceneForLayout(sceneRoot, R.layout.interruption_inner_2, this);
        mScene3 = Scene.getSceneForLayout(sceneRoot, R.layout.interruption_inner_3, this);
        mScene4 = Scene.getSceneForLayout(sceneRoot, R.layout.interruption_inner_4, this);

        mScene1RB = findViewById(R.id.scene1RB);
        mScene2RB = findViewById(R.id.scene2RB);
        mScene3RB = findViewById(R.id.scene3RB);
        mScene4RB = findViewById(R.id.scene4RB);

        ChangeBounds changeBounds1 = new ChangeBounds();
        changeBounds1.addTarget(R.id.button);
        ChangeBounds changeBounds2 = new ChangeBounds();
        changeBounds2.addTarget(R.id.button1);

        mSequencedMove.addTransition(changeBounds1).addTransition(changeBounds2);
        mSequencedMove.setDuration(1000);
    }

    public void onRadioButtonClicked(View clickedButton) {
        if (clickedButton == mScene1RB) {
            TransitionManager.go(mScene1, mSequencedMove);
        } else if (clickedButton == mScene2RB) {
            TransitionManager.go(mScene2, mSequencedMove);
        } else if (clickedButton == mScene3RB) {
            TransitionManager.go(mScene3, mSequencedMove);
        } else {
            TransitionManager.go(mScene4, mSequencedMove);
        }
    }
}
