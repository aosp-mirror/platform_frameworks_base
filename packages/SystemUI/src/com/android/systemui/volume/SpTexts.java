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

package com.android.systemui.volume;

import android.content.Context;
import android.content.res.Resources;
import android.util.ArrayMap;
import android.util.TypedValue;
import android.view.View;
import android.view.View.OnAttachStateChangeListener;
import android.widget.TextView;

/**
 * Capture initial sp values for registered textviews, and update properly when configuration
 * changes.
 */
public class SpTexts {

    private final Context mContext;
    private final ArrayMap<TextView, Integer> mTexts = new ArrayMap<>();

    public SpTexts(Context context) {
        mContext = context;
    }

    public int add(final TextView text) {
        if (text == null) return 0;
        final Resources res = mContext.getResources();
        final float fontScale = res.getConfiguration().fontScale;
        final float density = res.getDisplayMetrics().density;
        final float px = text.getTextSize();
        final int sp = (int)(px / fontScale / density);
        mTexts.put(text, sp);
        text.addOnAttachStateChangeListener(new OnAttachStateChangeListener() {
            @Override
            public void onViewDetachedFromWindow(View v) {
            }

            @Override
            public void onViewAttachedToWindow(View v) {
               setTextSizeH(text, sp);
            }
        });
        return sp;
    }

    public void update() {
        if (mTexts.isEmpty()) return;
        mTexts.keyAt(0).post(mUpdateAll);
    }

    private void setTextSizeH(TextView text, int sp) {
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    }

    private final Runnable mUpdateAll = new Runnable() {
        @Override
        public void run() {
            for (int i = 0; i < mTexts.size(); i++) {
                setTextSizeH(mTexts.keyAt(i), mTexts.valueAt(i));
            }
        }
    };
}
