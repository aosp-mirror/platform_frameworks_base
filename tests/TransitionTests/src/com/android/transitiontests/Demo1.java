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
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.transition.Fade;
import android.transition.ChangeBounds;
import android.transition.Scene;
import android.transition.TransitionSet;
import android.transition.TransitionManager;


public class Demo1 extends Activity {
    ViewGroup mSceneRoot;
    static Scene mCurrentScene;
    boolean mFirstTime = true;
    Scene mSearchScreen, mResultsScreen;
    TransitionManager mTransitionManager = null;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.search_screen);

        View container = findViewById(R.id.container);
        mSceneRoot = (ViewGroup) container.getParent();

//        mResultsScreen = new MyScene(mSceneRoot, R.layout.results_screen);
//        mSearchScreen = new MyScene(mSceneRoot, R.layout.search_screen);
        mResultsScreen = new Scene(mSceneRoot);
        mResultsScreen.setEnterAction(new Runnable() {
            @Override
            public void run() {
                LayoutInflater inflater = (LayoutInflater)
                        getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                inflater.inflate(R.layout.results_screen, mSceneRoot);
            }
        });
        mSearchScreen = new Scene(mSceneRoot);
        mSearchScreen.setEnterAction(new Runnable() {
            @Override
            public void run() {
                LayoutInflater inflater = (LayoutInflater)
                        getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                inflater.inflate(R.layout.search_screen, mSceneRoot);
            }
        });

    }

    public void sendMessage(View view) {
        if (mFirstTime) {
            mFirstTime = false;
            TransitionSet transition = new TransitionSet();
            transition.addTransition(new Fade().addTarget(R.id.resultsText).
                    addTarget(R.id.resultsList)).
                    addTransition(new ChangeBounds().addTarget(R.id.searchContainer));
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
