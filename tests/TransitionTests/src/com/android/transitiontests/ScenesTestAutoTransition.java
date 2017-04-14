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
import android.transition.AutoTransition;
import android.transition.Scene;
import android.transition.Transition;
import android.transition.TransitionManager;


public class ScenesTestAutoTransition extends Activity {
    ViewGroup mSceneRoot;
    static Scene mCurrentScene;
    Transition mTransition = new AutoTransition();
    Scene mResultsScreen, mSearchScreen;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.search_screen);

        View container = findViewById(R.id.container);
        mSceneRoot = (ViewGroup) container.getParent();

        mSearchScreen = Scene.getSceneForLayout(mSceneRoot, R.layout.search_screen, this);
        mResultsScreen = Scene.getSceneForLayout(mSceneRoot, R.layout.results_screen, this);
    }

    public void sendMessage(View view) {
        if (mCurrentScene == mResultsScreen) {
            TransitionManager.go(mSearchScreen, mTransition);
            mCurrentScene = mSearchScreen;
        } else {
            TransitionManager.go(mResultsScreen, mTransition);
            mCurrentScene = mResultsScreen;
        }
    }
}
