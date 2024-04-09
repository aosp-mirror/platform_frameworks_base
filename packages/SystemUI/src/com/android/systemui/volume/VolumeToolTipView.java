/*
 * Copyright (C) 2019 The Android Open Source Project
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
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.drawable.ShapeDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;

import com.android.systemui.recents.TriangleShape;
import com.android.systemui.res.R;

/**
 * Tool tip view that draws an arrow that points to the volume dialog.
 */
public class VolumeToolTipView extends LinearLayout {
    public VolumeToolTipView(Context context) {
        super(context);
    }

    public VolumeToolTipView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public VolumeToolTipView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public VolumeToolTipView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        drawArrow();
    }

    private void drawArrow() {
        View arrowView = findViewById(R.id.arrow);
        ViewGroup.LayoutParams arrowLp = arrowView.getLayoutParams();
        ShapeDrawable arrowDrawable = new ShapeDrawable(TriangleShape.createHorizontal(
                arrowLp.width, arrowLp.height, false));
        Paint arrowPaint = arrowDrawable.getPaint();
        TypedValue typedValue = new TypedValue();
        getContext().getTheme().resolveAttribute(android.R.attr.colorAccent, typedValue, true);
        arrowPaint.setColor(ContextCompat.getColor(getContext(), typedValue.resourceId));
        // The corner path effect won't be reflected in the shadow, but shouldn't be noticeable.
        arrowPaint.setPathEffect(new CornerPathEffect(
                getResources().getDimension(R.dimen.volume_tool_tip_arrow_corner_radius)));
        arrowView.setBackground(arrowDrawable);

    }
}
