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
import android.widget.Button;


public class OverlayTest extends Activity {

    ViewGroup mContainer;
    ViewGroup mRoot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.overlay_test);

        mContainer = (ViewGroup) findViewById(R.id.container);
        mRoot = (ViewGroup) mContainer.getParent();
    }

    public void onClick(View view) {
        final Button fadingButton = (Button) findViewById(R.id.fadingButton);
        if (fadingButton != null) {
            mContainer.removeView(fadingButton);
            mRoot.getOverlay().add(fadingButton);
            fadingButton.animate().alpha(0).setDuration(1000).withEndAction(new Runnable() {
                @Override
                public void run() {
                    fadingButton.setAlpha(1);
                    mRoot.getOverlay().remove(fadingButton);
                    mContainer.addView(fadingButton);
                }
            });
        }
    }
}
