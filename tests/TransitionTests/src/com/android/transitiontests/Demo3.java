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
import android.transition.Fade;
import android.transition.Recolor;
import android.transition.Scene;
import android.transition.TransitionSet;
import android.transition.TransitionManager;


public class Demo3 extends Activity {
    ViewGroup mSceneRoot;
    static Scene mCurrentScene;
    Scene mSearchScreen, mResultsScreen;
    TransitionManager mTransitionManager = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.search_screen);

        View container = (View) findViewById(R.id.container);
        mSceneRoot = (ViewGroup) container.getParent();

        mSearchScreen = Scene.getSceneForLayout(mSceneRoot, R.layout.search_screen, this);
        mResultsScreen = Scene.getSceneForLayout(mSceneRoot, R.layout.results_screen, this);

        TransitionSet transition = new TransitionSet();
        transition.addTransition(new Fade()).addTransition(new ChangeBounds()).addTransition(new Recolor());

        mTransitionManager = new TransitionManager();
        mTransitionManager.setTransition(mSearchScreen, transition);
        mTransitionManager.setTransition(mResultsScreen, transition);
    }

    public void sendMessage(View view) {
        if (mCurrentScene == mResultsScreen) {
            mTransitionManager.transitionTo(mSearchScreen);
            mCurrentScene = mSearchScreen;
        } else {
            mTransitionManager.transitionTo(mResultsScreen);
            mCurrentScene = mResultsScreen;
        }
    }
}
