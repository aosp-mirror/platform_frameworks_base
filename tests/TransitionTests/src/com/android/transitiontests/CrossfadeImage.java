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
import android.transition.Crossfade;
import android.transition.ChangeBounds;
import android.transition.Scene;
import android.transition.Transition;
import android.transition.TransitionSet;
import android.transition.TransitionManager;
import android.widget.ImageView;

public class CrossfadeImage extends Activity {
    ViewGroup mSceneRoot;
    static int mCurrentScene;
    Scene mScene1, mScene2;
    TransitionManager mTransitionManager;
    boolean mExpanded = false;
    Transition mTransition;
    ImageView mImageView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.crossfade_image);

        ViewGroup container = findViewById(R.id.container);
        mSceneRoot = container;

        mImageView = findViewById(R.id.contact_picture);
        mImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);

        Crossfade mCrossfade = new Crossfade();
        mCrossfade.addTarget(R.id.contact_picture);

        TransitionSet group = new TransitionSet();
        group.setDuration(1500);
        group.addTransition(mCrossfade).addTransition(new ChangeBounds());
        mTransition = group;
    }

    public void sendMessage(View view) {
        TransitionManager.beginDelayedTransition(mSceneRoot, mTransition);
        if (mExpanded) {
            mImageView.setImageResource(R.drawable.self_portrait_square_100);
        } else {
            mImageView.setImageResource(R.drawable.self_portrait_square_200);
        }
        mExpanded = !mExpanded;
    }
}
