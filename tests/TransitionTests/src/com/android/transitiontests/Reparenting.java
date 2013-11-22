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
import android.transition.TransitionManager;
import android.widget.Button;

public class Reparenting extends Activity {

    ViewGroup mSceneRoot;
    ViewGroup mContainer1, mContainer2;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.reparenting);

        ViewGroup container = (ViewGroup) findViewById(R.id.container);
        mContainer1 = (ViewGroup) findViewById(R.id.container1);
        mContainer2 = (ViewGroup) findViewById(R.id.container2);
        System.out.println("container 1 and 2 " + mContainer1 + ", " + mContainer2);

        setupButtons(0, mContainer1);
        setupButtons(3, mContainer2);

        mSceneRoot = container;
    }

    private void setupButtons(int startIndex, ViewGroup parent) {
        for (int i = startIndex; i < (startIndex + 3); ++i) {
            Button button = new Button(this);
            button.setText(Integer.toString(i));
            button.setOnClickListener(mButtonListener);
            parent.addView(button);
        }
    }

    private View.OnClickListener mButtonListener = new View.OnClickListener() {
        @Override
        public void onClick(final View v) {
            Scene newScene = new Scene(mSceneRoot);
            newScene.setEnterAction(new Runnable() {
                @Override
                public void run() {
                    ViewGroup oldParent = (ViewGroup) v.getParent();
                    ViewGroup newParent = oldParent == mContainer1 ? mContainer2 : mContainer1;
                    oldParent.removeView(v);
                    newParent.addView(v);
                }
            });
            ChangeBounds reparent = new ChangeBounds();
            reparent.setReparent(true);
            TransitionManager.go(newScene, reparent);
        }
    };
}
