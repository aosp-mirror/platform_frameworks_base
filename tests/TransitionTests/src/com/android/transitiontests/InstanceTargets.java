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
import android.view.transition.Move;
import android.view.transition.Scene;
import android.view.transition.TransitionManager;
import android.widget.Button;
import android.widget.RelativeLayout;
import com.android.transitiontest.R;

import static android.widget.RelativeLayout.ALIGN_PARENT_LEFT;
import static android.widget.RelativeLayout.ALIGN_PARENT_RIGHT;
import static android.widget.RelativeLayout.LayoutParams;

public class InstanceTargets extends Activity {

    ViewGroup mSceneRoot;
    static int mCurrentScene;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.instance_targets);

        View container = (View) findViewById(R.id.container);
        mSceneRoot = (ViewGroup) container;
    }

    public void sendMessage(final View view) {
        TransitionManager.go(mSceneRoot, new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < mSceneRoot.getChildCount(); ++i) {
                    Button button = (Button) mSceneRoot.getChildAt(i);
                    LayoutParams params = (LayoutParams) button.getLayoutParams();
                    int rules[] = params.getRules();
                    if (rules[ALIGN_PARENT_RIGHT] != 0) {
                        params.removeRule(ALIGN_PARENT_RIGHT);
                        params.addRule(ALIGN_PARENT_LEFT);
                    } else {
                        params.removeRule(ALIGN_PARENT_LEFT);
                        params.addRule(ALIGN_PARENT_RIGHT);
                    }
                    button.setLayoutParams(params);
                }
            }
        }, new Move().setTargets(view));
    }
}
