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
 * Class for updating textviews on configuration change.
 */
public class ConfigurableTexts {

    private final Context mContext;
    private final ArrayMap<TextView, Integer> mTexts = new ArrayMap<>();
    private final ArrayMap<TextView, Integer> mTextLabels = new ArrayMap<>();

    public ConfigurableTexts(Context context) {
        mContext = context;
    }

    public int add(final TextView text) {
        return add(text, -1);
    }

    public int add(final TextView text, final int labelResId) {
        if (text == null) return 0;
        if (mTexts.containsKey(text)) {
            return mTexts.get(text);
        }
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
        mTextLabels.put(text, labelResId);
        return sp;
    }

    public void remove(final TextView text) {
        mTexts.remove(text);
        mTextLabels.remove(text);
    }

    public void update() {
        if (mTexts.isEmpty()) return;
        mTexts.keyAt(0).post(mUpdateAll);
    }

    private void setTextSizeH(TextView text, int sp) {
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    }

    private void setTextLabelH(TextView text, int labelResId) {
        try {
            if (labelResId >= 0) {
                Util.setText(text, mContext.getString(labelResId));
            }
        } catch (Resources.NotFoundException e) {
            // oh well.
        }
    }

    private final Runnable mUpdateAll = new Runnable() {
        @Override
        public void run() {
            for (int i = 0; i < mTexts.size(); i++) {
                setTextSizeH(mTexts.keyAt(i), mTexts.valueAt(i));
            }
            for (int i = 0; i < mTextLabels.size(); i++) {
                setTextLabelH(mTextLabels.keyAt(i), mTextLabels.valueAt(i));
            }
        }
    };
}
