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
import android.transition.Crossfade;
import android.transition.ChangeText;
import android.transition.Transition;
import android.transition.TransitionSet;
import android.transition.TransitionManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import static android.widget.LinearLayout.LayoutParams;

public class CrossfadeMultiple extends Activity {
    ViewGroup mSceneRoot;
    static int mCurrentScene;
    TransitionManager mTransitionManager;
    Transition mTransition;
    ImageView mImageView;
    TextView mTextView;
    Button mButton;
    Crossfade mCrossfade;
    TransitionSet mCrossfadeGroup;
    TransitionSet mTextChangeGroup1, mTextChangeGroup2, mTextChangeGroup3;
    TransitionSet mInOutGroup;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.crossfade_multiple);

        ViewGroup container = (ViewGroup) findViewById(R.id.container);
        mSceneRoot = container;

        mButton = (Button) findViewById(R.id.button);
        mImageView = (ImageView) findViewById(R.id.imageview);
        mTextView = (TextView) findViewById(R.id.textview);

        mCrossfade = new Crossfade();
        mCrossfade.addTarget(R.id.button).addTarget(R.id.textview).addTarget(R.id.imageview);

        mCrossfadeGroup = new TransitionSet();
        mCrossfadeGroup.setDuration(300);
        mCrossfadeGroup.addTransition(mCrossfade).addTransition(new ChangeBounds());
        mTransition = mCrossfadeGroup;

        mInOutGroup = new TransitionSet();
        Crossfade inOut = new Crossfade();
        inOut.setDuration(300);
        inOut.setFadeBehavior(Crossfade.FADE_BEHAVIOR_OUT_IN);
        ChangeBounds changeBounds = new ChangeBounds();
        changeBounds.setStartDelay(150);
        changeBounds.setDuration(0);
        mInOutGroup.addTransition(inOut).addTransition(changeBounds);

        mTextChangeGroup1 = new TransitionSet();
        ChangeText changeTextInOut = new ChangeText();
        changeTextInOut.setChangeBehavior(ChangeText.CHANGE_BEHAVIOR_OUT_IN);
        mTextChangeGroup1.addTransition(changeTextInOut).addTransition(new ChangeBounds());

        mTextChangeGroup2 = new TransitionSet();
        mTextChangeGroup2.setOrdering(TransitionSet.ORDERING_SEQUENTIAL);
        ChangeText changeTextOut = new ChangeText();
        changeTextOut.setChangeBehavior(ChangeText.CHANGE_BEHAVIOR_OUT);
        mTextChangeGroup2.addTransition(changeTextOut).addTransition(new ChangeBounds());

        mTextChangeGroup3 = new TransitionSet();
        mTextChangeGroup3.setOrdering(TransitionSet.ORDERING_SEQUENTIAL);
        ChangeText changeTextIn = new ChangeText();
        changeTextIn.setChangeBehavior(ChangeText.CHANGE_BEHAVIOR_IN);
        mTextChangeGroup3.addTransition(changeTextIn).addTransition(new ChangeBounds());
    }

    public void sendMessage(View view) {
        TransitionManager.beginDelayedTransition(mSceneRoot, mTransition);
        int id = view.getId();
        LayoutParams params = null;
        switch (id) {
            case R.id.button1:
                params = new LayoutParams(200, 200);
                mButton.setText("A");
                mTextView.setText("1111111");
                mImageView.setImageResource(R.drawable.self_portrait_square_100);
                break;
            case R.id.button2:
                params = new LayoutParams(400, 200);
                mButton.setText("B");
                mTextView.setText("2222222");
                mImageView.setImageResource(R.drawable.self_portrait_square_200);
                break;
            case R.id.button3:
                params = new LayoutParams(200, 400);
                mButton.setText("C");
                mTextView.setText("3333333");
                mImageView.setImageResource(R.drawable.self_portrait_square_400);
                break;
        }
        mButton.setLayoutParams(params);
    }

    public void changeTransitionType(View view) {
        int id = view.getId();
        switch (id) {
            case R.id.reveal:
                mCrossfade.setFadeBehavior(Crossfade.FADE_BEHAVIOR_REVEAL);
                mTransition = mCrossfadeGroup;
                break;
            case R.id.crossfade:
                mCrossfade.setFadeBehavior(Crossfade.FADE_BEHAVIOR_CROSSFADE);
                mTransition = mCrossfadeGroup;
                break;
            case R.id.inout:
                mTransition = mInOutGroup;
                break;
            case R.id.textfade1:
                mTransition = mTextChangeGroup1;
                break;
            case R.id.textfade2:
                mTransition = mTextChangeGroup2;
                break;
            case R.id.textfade3:
                mTransition = mTextChangeGroup3;
                break;
        }
    }
}
