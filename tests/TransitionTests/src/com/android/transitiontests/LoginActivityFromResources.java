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
import android.view.transition.Scene;
import android.view.transition.TransitionInflater;
import android.widget.TextView;
import android.view.transition.TransitionManager;


public class LoginActivityFromResources extends Activity {
    ViewGroup mSceneRoot;
    Scene mCurrentScene;
    TransitionManager mTransitionManager = null;
    Scene mLoginScene, mPasswordScene, mIncorrectPasswordScene, mSuccessScene, mUsernameTakenScene,
            mNewUserScene;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        View container = (View) findViewById(R.id.container);
        mSceneRoot = (ViewGroup) container.getParent();

    }

    public void applyScene(Scene scene) {
        mTransitionManager.transitionTo(scene);
        mCurrentScene = scene;
    }

    public void sendMessage(View view) {
        if (mTransitionManager == null) {
            TransitionInflater inflater = TransitionInflater.from(this);

            mLoginScene = inflater.inflateScene(R.scene.login_scene, mSceneRoot);
            mPasswordScene = inflater.inflateScene(R.scene.password_scene, mSceneRoot);
            mIncorrectPasswordScene =
                    inflater.inflateScene(R.scene.incorrect_password_scene,mSceneRoot);
            mUsernameTakenScene =
                    inflater.inflateScene(R.scene.username_taken_scene, mSceneRoot);
            mSuccessScene = inflater.inflateScene(R.scene.success_scene, mSceneRoot);
            mNewUserScene = inflater.inflateScene(R.scene.new_user_scene, mSceneRoot);

            mTransitionManager =
                    inflater.inflateTransitionManager(R.transition.login_transition_mgr,
                            mSceneRoot);

            mCurrentScene = mLoginScene;
            mSceneRoot.setCurrentScene(mLoginScene);
        }
        TextView textView = (TextView) view;
        CharSequence text = textView.getText();
        if (text.equals("Cancel")) {
            applyScene(mLoginScene);
        } else if (text.equals("Submit")) {
            if (mCurrentScene == mLoginScene) {
                applyScene(mPasswordScene);
            } else if (mCurrentScene == mPasswordScene) {
                applyScene(Math.random() < .5 ? mSuccessScene : mIncorrectPasswordScene);
            } else if (mCurrentScene == mNewUserScene) {
                applyScene(Math.random() < .5 ? mSuccessScene : mUsernameTakenScene);
            }
        } else if (text.equals("New User?")) {
            applyScene(mNewUserScene);
        } else if (text.equals("Okay")) {
            if (mCurrentScene == mIncorrectPasswordScene) {
                applyScene(mPasswordScene);
            } else { // username taken scene
                applyScene(mNewUserScene);
            }
        } else if (text.equals("Reset")) {
            applyScene(mLoginScene);
        }
    }
}
