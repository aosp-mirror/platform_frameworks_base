/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.test.uibench;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.util.ArrayList;

/**
 * Tests invalidation/record performance by invalidating a large number of easily rendered
 * nested views.
 */
public class InvalidateTreeActivity extends AppCompatActivity {
    private final ArrayList<LinearLayout> mLayouts = new ArrayList<>();

    private int mColorToggle = 0;

    private void createQuadTree(LinearLayout parent, int remainingDepth) {
        mLayouts.add(parent);
        if (remainingDepth <= 0) {
            mColorToggle = (mColorToggle + 1) % 4;
            parent.setBackgroundColor((mColorToggle < 2) ? Color.RED : Color.BLUE);
            return;
        }

        boolean vertical = remainingDepth % 2 == 0;
        parent.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);

        for (int i = 0; i < 2; i++) {
            LinearLayout child = new LinearLayout(this);
            // vertical: match parent in x axis, horizontal: y axis.
            parent.addView(child, new LinearLayout.LayoutParams(
                    (vertical ? ViewGroup.LayoutParams.MATCH_PARENT : 0),
                    (vertical ? 0 : ViewGroup.LayoutParams.MATCH_PARENT),
                    1.0f));

            createQuadTree(child, remainingDepth - 1);
        }
    }

    @SuppressWarnings("unused")
    public void setIgnoredValue(int ignoredValue) {
        for (int i = 0; i < mLayouts.size(); i++) {
            mLayouts.get(i).invalidate();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        createQuadTree(root, 8);
        setContentView(root);

        ObjectAnimator animator = ObjectAnimator.ofInt(this, "ignoredValue", 0, 1000);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.start();
    }
}
