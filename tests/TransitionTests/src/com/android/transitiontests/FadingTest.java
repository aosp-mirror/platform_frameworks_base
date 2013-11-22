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
import android.widget.Button;
import android.transition.Fade;
import android.transition.TransitionManager;


public class FadingTest extends Activity {

    Button mRemovingButton, mInvisibleButton, mGoneButton;
    Scene mScene1, mScene2;
    ViewGroup mSceneRoot;
    static Fade sFade = new Fade();
    Scene mCurrentScene;

    static {
        sFade.addTarget(R.id.removingButton).addTarget(R.id.invisibleButton).
                addTarget(R.id.goneButton);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fading_test);

        View container = (View) findViewById(R.id.container);
        mSceneRoot = (ViewGroup) container.getParent();


        mRemovingButton = (Button) findViewById(R.id.removingButton);
        mInvisibleButton = (Button) findViewById(R.id.invisibleButton);
        mGoneButton = (Button) findViewById(R.id.goneButton);

        mGoneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mGoneButton.setAlpha(mGoneButton.getAlpha() < 1 ? 1 : .6f);
            }
        });

        mScene1 = Scene.getSceneForLayout(mSceneRoot, R.layout.fading_test, this);
        mScene2 = Scene.getSceneForLayout(mSceneRoot, R.layout.fading_test_scene_2, this);

        mCurrentScene = mScene1;
    }

    public void sendMessage(View view) {
        if (mCurrentScene == mScene1) {
            TransitionManager.go(mScene2);
            mCurrentScene = mScene2;
        } else {
            TransitionManager.go(mScene1);
            mCurrentScene = mScene1;
        }
    }

}
