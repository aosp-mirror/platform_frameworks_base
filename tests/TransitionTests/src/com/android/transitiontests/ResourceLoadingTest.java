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
import android.transition.Scene;
import android.transition.TransitionInflater;
import android.transition.Transition;
import android.transition.TransitionManager;


public class ResourceLoadingTest extends Activity {

    private static final int SEARCH_SCREEN = 0;
    private static final int RESULTS_SCREEN = 1;
    ViewGroup mSceneRoot;
    static int mCurrentScene;
    TransitionManager mTransitionManager = null;
    TransitionInflater mInflater;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.search_screen);

        View container = (View) findViewById(R.id.container);
        mSceneRoot = (ViewGroup) container.getParent();

        mCurrentScene = SEARCH_SCREEN;

        mInflater = TransitionInflater.from(this);
    }

    public void sendMessage(View view) {
        if (mTransitionManager == null) {
            try {
                TransitionInflater inflater = TransitionInflater.from(this);
                mTransitionManager =
                        inflater.inflateTransitionManager(R.transition.my_transition_mgr,
                                mSceneRoot);
                Scene loadedScene = new Scene(mSceneRoot);
                System.out.println("loadedScene = " + loadedScene);
                Transition loadedTransition = inflater.inflateTransition(R.transition.my_transition);
                System.out.println("loadedTransition = " + loadedTransition);
            } catch (Exception e) {
                System.out.println("Problem loading scene resource: " + e);
            }
        }
        if (mCurrentScene == RESULTS_SCREEN) {
            Scene scene = Scene.getSceneForLayout(mSceneRoot, R.layout.search_screen, this);
            mTransitionManager.transitionTo(scene);
            mCurrentScene = SEARCH_SCREEN;
        } else {
            Scene scene = Scene.getSceneForLayout(mSceneRoot, R.layout.results_screen, this);
            mTransitionManager.transitionTo(scene);
            mCurrentScene = RESULTS_SCREEN;
        }
    }
}
