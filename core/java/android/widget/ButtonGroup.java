/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

public class ButtonGroup extends LinearLayout {
    private Drawable mDivider;
    private Drawable mButtonBackground;

    public ButtonGroup(Context context) {
        this(context, null);
    }
    
    public ButtonGroup(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.buttonGroupStyle);
    }
    
    public ButtonGroup(Context context, AttributeSet attrs, int defStyleRes) {
        super(context, attrs, defStyleRes);
        
        TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.ButtonGroup);
        
        mDivider = a.getDrawable(com.android.internal.R.styleable.ButtonGroup_divider);
        mButtonBackground = a.getDrawable(
                com.android.internal.R.styleable.ButtonGroup_buttonBackground);
        a.recycle();
    }
    
    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        if (getChildCount() > 0) {
            super.addView(makeDividerView(), index, makeDividerLayoutParams());
            if (index >= 0) {
                index++;
            }
        }
        child.setBackgroundDrawable(mButtonBackground);
        super.addView(child, index, params);
    }
    
    private ImageView makeDividerView() {
        ImageView result = new ImageView(mContext);
        result.setImageDrawable(mDivider);
        result.setScaleType(ImageView.ScaleType.FIT_XY);
        return result;
    }

    private LayoutParams makeDividerLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
    }
}
