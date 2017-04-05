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
import android.transition.TransitionManager;
import android.widget.Button;

public class FadingHierarchy extends Activity {

    ViewGroup mRemovingContainer, mContainer;
    Button mRemovingButton;
    boolean mVisible = true;
    ViewGroup mInnerContainerParent;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fading_hierarchy);

        mContainer = findViewById(R.id.container);
        mRemovingContainer = findViewById(R.id.removingContainer);
        mInnerContainerParent = (ViewGroup) mRemovingContainer.getParent();

        mRemovingButton = findViewById(R.id.removingButton);
    }

    public void sendMessage(View view) {
        TransitionManager.beginDelayedTransition(mContainer, null);
        if (mVisible) {
            mInnerContainerParent.removeView(mRemovingContainer);
        } else {
            mInnerContainerParent.addView(mRemovingContainer);
        }
        mVisible = !mVisible;
    }
}
