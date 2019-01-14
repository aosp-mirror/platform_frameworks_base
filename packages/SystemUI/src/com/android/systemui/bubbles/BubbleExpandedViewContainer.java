/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.bubbles;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.ShapeDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.recents.TriangleShape;

/**
 * Container for the expanded bubble view, handles rendering the caret and header of the view.
 */
public class BubbleExpandedViewContainer extends LinearLayout {

    // The triangle pointing to the expanded view
    private View mPointerView;
    // The view displayed between the pointer and the expanded view
    private TextView mHeaderView;
    // The view that is being displayed for the expanded state
    private View mExpandedView;

    public BubbleExpandedViewContainer(Context context) {
        this(context, null);
    }

    public BubbleExpandedViewContainer(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BubbleExpandedViewContainer(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public BubbleExpandedViewContainer(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setOrientation(VERTICAL);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        Resources res = getResources();
        mPointerView = findViewById(R.id.pointer_view);
        int width = res.getDimensionPixelSize(R.dimen.bubble_pointer_width);
        int height = res.getDimensionPixelSize(R.dimen.bubble_pointer_height);
        ShapeDrawable triangleDrawable = new ShapeDrawable(
                TriangleShape.create(width, height, true /* pointUp */));
        triangleDrawable.setTint(Color.WHITE); // TODO: dark mode
        mPointerView.setBackground(triangleDrawable);
        mHeaderView = findViewById(R.id.bubble_content_header);
    }

    /**
     * Set the x position that the tip of the triangle should point to.
     */
    public void setPointerPosition(int x) {
        // Adjust for the pointer size
        x -= (mPointerView.getWidth() / 2);
        mPointerView.setTranslationX(x);
    }

    /**
     * Set the text displayed within the header.
     */
    public void setHeaderText(CharSequence text) {
        mHeaderView.setText(text);
        mHeaderView.setVisibility(TextUtils.isEmpty(text) ? GONE : VISIBLE);
    }

    /**
     * Set the view to display for the expanded state. Passing null will clear the view.
     */
    public void setExpandedView(View view) {
        if (mExpandedView == view) {
            return;
        }
        if (mExpandedView != null) {
            removeView(mExpandedView);
        }
        mExpandedView = view;
        if (mExpandedView != null) {
            addView(mExpandedView);
        }
    }

    /**
     * @return the view containing the expanded content, can be null.
     */
    @Nullable
    public View getExpandedView() {
        return mExpandedView;
    }
}
