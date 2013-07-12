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
import android.view.transition.Crossfade;
import android.view.transition.Move;
import android.view.transition.TextChange;
import android.view.transition.Transition;
import android.view.transition.TransitionGroup;
import android.view.transition.TransitionManager;
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
    TransitionGroup mCrossfadeGroup;
    TransitionGroup mTextChangeGroup1, mTextChangeGroup2;
    TransitionGroup mInOutGroup;

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
        mCrossfade.setTargetIds(R.id.button, R.id.textview, R.id.imageview);

        mCrossfadeGroup = new TransitionGroup();
        mCrossfadeGroup.setDuration(300);
        mCrossfadeGroup.addTransitions(mCrossfade, new Move());
        mTransition = mCrossfadeGroup;

        mInOutGroup = new TransitionGroup();
        Crossfade inOut = new Crossfade();
        inOut.setDuration(300);
        inOut.setFadeBehavior(Crossfade.FADE_BEHAVIOR_OUT_IN);
        Move move = new Move();
        move.setStartDelay(150);
        move.setDuration(0);
        mInOutGroup.addTransitions(inOut, move);

        mTextChangeGroup1 = new TransitionGroup();
        TextChange textChangeInOut = new TextChange();
        textChangeInOut.setChangeBehavior(TextChange.CHANGE_BEHAVIOR_OUT_IN);
        mTextChangeGroup1.addTransitions(textChangeInOut, new Move());

        mTextChangeGroup2 = new TransitionGroup();
        mTextChangeGroup2.setOrdering(TransitionGroup.SEQUENTIALLY);
        TextChange textChangeOut = new TextChange();
        textChangeOut.setChangeBehavior(TextChange.CHANGE_BEHAVIOR_OUT);
        TextChange textChangeIn = new TextChange();
        textChangeIn.setChangeBehavior(TextChange.CHANGE_BEHAVIOR_IN);
        mTextChangeGroup2.addTransitions(textChangeOut, new Move(), textChangeIn);
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
        }
    }
}
