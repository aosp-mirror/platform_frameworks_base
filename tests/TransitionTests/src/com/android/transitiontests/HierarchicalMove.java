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
import android.transition.Transition;
import android.transition.TransitionSet;
import android.transition.TransitionManager;
import android.widget.Button;

import static android.widget.LinearLayout.LayoutParams;

public class HierarchicalMove extends Activity {

    Button[] buttons = new Button[6];
    ViewGroup mSceneRoot;
    boolean wide = false;
    Transition mTransition;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.hierarchical_move);

        View container = (View) findViewById(R.id.container);
        mSceneRoot = (ViewGroup) container.getParent();

        buttons[0] = (Button) findViewById(R.id.button0);
        buttons[1] = (Button) findViewById(R.id.button1);
        buttons[2] = (Button) findViewById(R.id.button2);
        buttons[3] = (Button) findViewById(R.id.button3);
        buttons[4] = (Button) findViewById(R.id.button4);
        buttons[5] = (Button) findViewById(R.id.button5);

        // Move button0, then buttons 1/2 together, then buttons 3/4/5 sequentially:
        // group (seq)
        //    Move 0
        //    group (seq)
        //       group (together)
        //          Move 1
        //          Move 2
        //       group (sequentially)
        //          Move 3
        //          Move 4/5
        TransitionSet rootTransition = new TransitionSet().
                setOrdering(TransitionSet.ORDERING_SEQUENTIAL);

        // button0
        Transition move0 = new ChangeBounds();
        move0.addTarget(buttons[0]);

        // buttons 1/2/3/4/5
        TransitionSet group12345 = new TransitionSet().
                setOrdering(TransitionSet.ORDERING_SEQUENTIAL);

        // buttons 1/2
        TransitionSet group12 = new TransitionSet().
                setOrdering(TransitionSet.ORDERING_TOGETHER);
        ChangeBounds changeBounds1 = new ChangeBounds();
        changeBounds1.addTarget(buttons[1]);
        ChangeBounds changeBounds2 = new ChangeBounds();
        changeBounds2.addTarget(buttons[2]);
        group12.addTransition(changeBounds1).addTransition(changeBounds2);

        TransitionSet group345 = new TransitionSet().
                setOrdering(TransitionSet.ORDERING_SEQUENTIAL);
        ChangeBounds changeBounds3 = new ChangeBounds();
        changeBounds3.addTarget(buttons[3]);
        ChangeBounds changeBounds45 = new ChangeBounds();
        changeBounds45.addTarget(buttons[4]).addTarget(buttons[5]);
        group345.addTransition(changeBounds3).addTransition(changeBounds45);

        group12345.addTransition(move0).addTransition(group12).addTransition(group345);

        rootTransition.addTransition(group12345);
        rootTransition.setDuration(1000);
        mTransition = rootTransition;

    }

    public void sendMessage(View view) {
        TransitionManager.beginDelayedTransition(mSceneRoot, mTransition);
        int widthSpec = wide ? LayoutParams.WRAP_CONTENT : LayoutParams.MATCH_PARENT;
        LayoutParams params = new LayoutParams(widthSpec, LayoutParams.WRAP_CONTENT);
        for (int i = 0; i < buttons.length; ++i) {
            buttons[i].setLayoutParams(params);
        }
        wide = !wide;
    }

}
