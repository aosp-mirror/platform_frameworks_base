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


public class Demo0 extends Activity {

    private static final int SEARCH_SCREEN = 0;
    private static final int RESULTS_SCREEN = 1;
    ViewGroup mSceneRoot;
    static int mCurrentScene;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.search_screen);

        View container = findViewById(R.id.container);
        mSceneRoot = (ViewGroup) container.getParent();

        mCurrentScene = SEARCH_SCREEN;
    }

    public void sendMessage(View view) {
        if (mCurrentScene == RESULTS_SCREEN) {
            mSceneRoot.removeAllViews();
            LayoutInflater inflater = (LayoutInflater)
                    getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            inflater.inflate(R.layout.search_screen, mSceneRoot);
            mCurrentScene = SEARCH_SCREEN;
        } else {
            mSceneRoot.removeAllViews();
            LayoutInflater inflater = (LayoutInflater)
                    getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            inflater.inflate(R.layout.results_screen, mSceneRoot);
            mCurrentScene = RESULTS_SCREEN;
        }
    }
}
