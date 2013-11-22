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
import android.widget.LinearLayout;
import static android.widget.LinearLayout.LayoutParams;

public class DelayedTransition extends Activity {

    private static final int SEARCH_SCREEN = 0;
    private static final int RESULTS_SCREEN = 1;
    ViewGroup mSceneRoot;
    static int mCurrentScene;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.two_buttons);

        final Button button1 = (Button) findViewById(R.id.button1);
        final Button button2 = (Button) findViewById(R.id.button2);
        final LinearLayout container = (LinearLayout) findViewById(R.id.container);
        button1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int buttonWidth = button1.getWidth();
                int containerWidth = container.getWidth();
                if (buttonWidth < containerWidth) {
                    TransitionManager.beginDelayedTransition(container, null);
                    button1.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT,
                            LayoutParams.WRAP_CONTENT));
                    TransitionManager.beginDelayedTransition(container, null);
                    button2.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT,
                            LayoutParams.MATCH_PARENT));
                } else {
                    TransitionManager.beginDelayedTransition(container, null);
                    button1.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT,
                            LayoutParams.WRAP_CONTENT));
                    TransitionManager.beginDelayedTransition(container, null);
                    button2.setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT,
                            LayoutParams.WRAP_CONTENT));
                }
            }
        });
    }

}
