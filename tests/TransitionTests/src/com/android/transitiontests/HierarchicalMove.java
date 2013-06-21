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
import android.view.transition.Move;
import android.view.transition.Transition;
import android.view.transition.TransitionGroup;
import android.view.transition.TransitionManager;
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
        TransitionGroup rootTransition = new TransitionGroup(TransitionGroup.SEQUENTIALLY);

        // button0
        Transition move0 = new Move();
        move0.setTargets(buttons[0]);

        // buttons 1/2/3/4/5
        TransitionGroup group12345 = new TransitionGroup(TransitionGroup.SEQUENTIALLY);

        // buttons 1/2
        TransitionGroup group12 = new TransitionGroup(TransitionGroup.TOGETHER);
        Move move1 = new Move();
        move1.setTargets(buttons[1]);
        Move move2 = new Move();
        move2.setTargets(buttons[2]);
        group12.addTransitions(move1, move2);

        TransitionGroup group345 = new TransitionGroup(TransitionGroup.SEQUENTIALLY);
        Move move3 = new Move();
        move3.setTargets(buttons[3]);
        Move move45 = new Move();
        move45.setTargets(buttons[4], buttons[5]);
        group345.addTransitions(move3, move45);

        group12345.addTransitions(move0, group12, group345);

        rootTransition.addTransitions(group12345);
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
