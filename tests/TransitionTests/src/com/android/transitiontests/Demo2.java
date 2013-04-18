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
import android.view.transition.Fade;
import android.view.transition.Move;
import android.view.transition.Recolor;
import android.view.transition.Scene;
import android.view.transition.TransitionInflater;
import android.view.transition.TransitionGroup;
import android.view.transition.TransitionManager;

public class Demo2 extends Activity {
    ViewGroup mSceneRoot;
    static Scene mCurrentScene;
    boolean mFirstTime = true;
    Scene mSearchScreen, mResultsScreen;
    TransitionManager mTransitionManager = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.search_screen);

        View container = (View) findViewById(R.id.container);
        mSceneRoot = (ViewGroup) container.getParent();

    }

    public void sendMessage(View view) {
        if (mFirstTime) {
            mFirstTime = false;
            // Non-resource approach of creating scenes
//        mSearchScreen = new Scene(this, mSceneRoot, R.layout.search_screen);
//        mResultsScreen = new Scene(this, mSceneRoot, R.layout.results_screen);
            try {
                mSearchScreen = TransitionInflater.from(this).
                        inflateScene(R.scene.search_scene, mSceneRoot);
                mResultsScreen = TransitionInflater.from(this).
                        inflateScene(R.scene.results_scene, mSceneRoot);
            } catch (Exception e) {
                System.out.println("Problem loading scene resource: " + e);
            }

            TransitionGroup transition = new TransitionGroup();
            transition.addTransitions(new Fade().setTargetIds(R.id.resultsText, R.id.resultsList),
                    new Move().setTargetIds(R.id.searchContainer),
                    new Recolor().setTargetIds(R.id.container));
            mTransitionManager = new TransitionManager();
            mTransitionManager.setTransition(mSearchScreen, transition);
            mTransitionManager.setTransition(mResultsScreen, transition);
        }
        if (mCurrentScene == mResultsScreen) {
            mTransitionManager.transitionTo(mSearchScreen);
            mCurrentScene = mSearchScreen;
        } else {
            mTransitionManager.transitionTo(mResultsScreen);
            mCurrentScene = mResultsScreen;
        }
    }
}
