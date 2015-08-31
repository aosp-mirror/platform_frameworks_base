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
package android.surfacecomposition;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

public class CustomLayout extends ViewGroup {
    public CustomLayout(Context context) {
        super(context);
    }

    public static class LayoutParams extends ViewGroup.LayoutParams {
        private int mLeft, mTop, mRight, mBottom;

        public LayoutParams(int left, int top, int right, int bottom) {
            super(0, 0);
            mLeft = left;
            mTop = top;
            mRight = right;
            mBottom = bottom;
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            CustomLayout.LayoutParams lp = (CustomLayout.LayoutParams) child.getLayoutParams();
            child.layout(lp.mLeft, lp.mTop, lp.mRight, lp.mBottom);
        }
    }
}
