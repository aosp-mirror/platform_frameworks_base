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
import android.view.transition.Crossfade;
import android.view.transition.Move;
import android.view.transition.Scene;
import android.view.transition.TransitionGroup;
import android.view.transition.TransitionManager;
import com.android.transitiontest.R;


public class CrossFadeDemo extends Activity {

    ViewGroup mSceneRoot;
    static int mCurrentScene;
    Scene mScene1, mScene2;
    TransitionManager mTransitionManager;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.crossfade);

        View container = (View) findViewById(R.id.container);
        mSceneRoot = (ViewGroup) container.getParent();

        mScene1 = new Scene(mSceneRoot, R.layout.crossfade, this);
        mScene2 = new Scene(mSceneRoot, R.layout.crossfade_1, this);

        Crossfade crossfade = new Crossfade();
        crossfade.setTargetIds(R.id.textview, R.id.textview1, R.id.textview2);
        mTransitionManager = new TransitionManager();
        TransitionGroup moveCrossFade = new TransitionGroup();
        moveCrossFade.addTransitions(crossfade, new Move());
        mTransitionManager.setTransition(mScene1, moveCrossFade);
        mTransitionManager.setTransition(mScene2, moveCrossFade);
        mCurrentScene = 1;
    }

    public void sendMessage(View view) {
        if (mCurrentScene == 1) {
            mTransitionManager.transitionTo(mScene2);
            mCurrentScene = 2;
        } else {
            mTransitionManager.transitionTo(mScene1);
            mCurrentScene = 1;
        }
    }
}
