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
import android.transition.Scene;
import android.widget.Button;
import android.transition.Fade;
import android.transition.ChangeText;
import android.transition.TransitionSet;
import android.transition.TransitionManager;

public class ClippingText extends Activity {

    Button mRemovingButton, mInvisibleButton, mGoneButton;
    Scene mScene1, mScene2;
    ViewGroup mSceneRoot;
    //    static Fade sFade = new Fade(R.id.removingButton, R.id.invisibleButton, R.id.goneButton);
    Fade fader;
    TransitionSet mChanger;
    Scene mCurrentScene;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.clipping_text_1);

        View container = (View) findViewById(R.id.container);
        mSceneRoot = (ViewGroup) container.getParent();

        mScene1 = Scene.getSceneForLayout(mSceneRoot, R.layout.clipping_text_1, this);
        mScene2 = Scene.getSceneForLayout(mSceneRoot, R.layout.clipping_text_2, this);

        mChanger = new TransitionSet().setOrdering(TransitionSet.ORDERING_TOGETHER);
        ChangeBounds changeBounds = new ChangeBounds();
        changeBounds.setResizeClip(true);
        mChanger.addTransition(changeBounds).addTransition(new ChangeText());

        mCurrentScene = mScene1;
    }

    public void sendMessage(View view) {
        if (mCurrentScene == mScene1) {
            TransitionManager.go(mScene2, mChanger);
            mCurrentScene = mScene2;
        } else {
            TransitionManager.go(mScene1, mChanger);
            mCurrentScene = mScene1;
        }
    }
}
