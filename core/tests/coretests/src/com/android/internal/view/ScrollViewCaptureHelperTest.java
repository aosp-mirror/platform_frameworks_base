/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.view;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.graphics.Color;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Random;

public class ScrollViewCaptureHelperTest
        extends AbsCaptureHelperTest<ViewGroup, ScrollViewCaptureHelper> {

    private static final int CHILD_VIEW_HEIGHT = 300;
    private static final int CHILD_VIEW_COUNT = CONTENT_HEIGHT / CHILD_VIEW_HEIGHT;

    private Random mRandom = new Random(0L);

    @Override
    protected ScrollViewCaptureHelper createHelper() {
        return new ScrollViewCaptureHelper();
    }

    @Override
    protected ScrollView createScrollableContent(ViewGroup parent) {
        Context mContext = parent.getContext();
        ScrollView scrollView = new ScrollView(mContext);
        LinearLayout content = new LinearLayout(mContext);
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content, new ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        for (int i = 0; i < CHILD_VIEW_COUNT; i++) {
            TextView view = new TextView(mContext);
            view.setText("Item #" + i);
            view.setTextColor(Color.WHITE);
            view.setTextSize(30f);
            view.setBackgroundColor(ITEM_COLORS[i % ITEM_COLORS.length]);
            content.addView(view, new ViewGroup.LayoutParams(MATCH_PARENT, CHILD_VIEW_HEIGHT));
        }
        return scrollView;
    }
}
