/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.test.voiceinteraction;

import android.annotation.Nullable;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewStructure;
import android.widget.TextView;

/**
 * Test for asynchronously creating additional assist structure.
 */
public class AsyncStructure extends TextView {
    public AsyncStructure(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void onProvideVirtualStructure(ViewStructure structure) {
        structure.setChildCount(1);
        final ViewStructure child = structure.asyncNewChild(0);
        final int width = getWidth();
        final int height = getHeight();
        (new Thread() {
            @Override
            public void run() {
                // Simulate taking a long time to build this.
                try {
                    sleep(2000);
                } catch (InterruptedException e) {
                }
                child.setClassName(AsyncStructure.class.getName());
                child.setVisibility(View.VISIBLE);
                child.setDimens(width / 4, height / 4, 0, 0, width / 2, height / 2);
                child.setEnabled(true);
                child.setContentDescription("This is some async content");
                child.setText("We could have lots and lots of async text!");
                child.asyncCommit();
            }
        }).start();
    }
}
