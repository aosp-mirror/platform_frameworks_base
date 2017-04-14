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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.transition.Scene;
import android.transition.Transition;
import android.widget.Button;
import android.widget.LinearLayout;
import android.transition.TransitionManager;


import java.util.HashMap;

public class UniqueIds extends Activity {
    ViewGroup mSceneRoot;
    static Scene mCurrentScene;
    TransitionManager mTransitionManager = null;
    HashMap<Button, ToggleScene> mSceneMap = new HashMap<Button, ToggleScene>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.unique_id_test);

        LinearLayout container = findViewById(R.id.container);
        LayoutInflater inflater = getLayoutInflater();
        Button button = (Button) inflater.inflate(R.layout.button_template, null);
        container.addView(button);
        ToggleScene scene = new ToggleScene(container, button);
        mSceneMap.put(button, scene);
        button = (Button) inflater.inflate(R.layout.button_template, null);
        container.addView(button);
        scene = new ToggleScene(container, button);
        mSceneMap.put(button, scene);
    }

    public void sendMessage(View view) {
        mSceneMap.get(view).changeToScene();
    }

    class ToggleScene {
        Scene mScene;
        Transition mTransition;
        Button mButton;

        ToggleScene(ViewGroup rootView, Button button) {
            mScene = new Scene(rootView);
            mButton = button;
            mScene.setEnterAction(new Runnable() {
                @Override
                public void run() {
                    if (mButton.getLeft() == 0) {
                        mButton.offsetLeftAndRight(500);
                    } else {
                        int width = mButton.getWidth();
                        mButton.setLeft(0);
                        mButton.setRight(width);
                    }
                }
            });
        }

        void changeToScene() {
            TransitionManager.go(mScene);
        }
    }
}
