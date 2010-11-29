<<<<<<< HEAD
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

package android.preference;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * @hide
 */
public class PreferenceFrameLayout extends FrameLayout {
    private static final int DEFAULT_TOP_PADDING = 0;
    private static final int DEFAULT_BOTTOM_PADDING = 0;
    private final int mTopPadding;
    private final int mBottomPadding;
    private boolean mPaddingApplied = false;

    public PreferenceFrameLayout(Context context) {
        this(context, null);
    }

    public PreferenceFrameLayout(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.preferenceFrameLayoutStyle);
    }

    public PreferenceFrameLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.PreferenceFrameLayout, defStyle, 0);

        mTopPadding = (int) a.getDimension(
                com.android.internal.R.styleable.PreferenceFrameLayout_topPadding,
                DEFAULT_TOP_PADDING);
        mBottomPadding = (int) a.getDimension(
                com.android.internal.R.styleable.PreferenceFrameLayout_bottomPadding,
                DEFAULT_BOTTOM_PADDING);

        a.recycle();
    }

    @Override
    public void addView(View child) {
        int topPadding = getPaddingTop();
        int bottomPadding = getPaddingBottom();
        // Check on the id of the child before adding it.
        if (child != null && child.getId() != com.android.internal.R.id.default_preference_layout) {
            // Add the padding to the view group after determining if the padding already exists.
            if (!mPaddingApplied) {
                topPadding += mTopPadding;
                bottomPadding += mBottomPadding;
                mPaddingApplied = true;
            }
        } else {
            if (mPaddingApplied) {
                topPadding -= mTopPadding;
                bottomPadding -= mBottomPadding;
                mPaddingApplied = false;
            }
        }
        int previousTop = getPaddingTop();
        int previousBottom = getPaddingBottom();
        if (previousTop != topPadding || previousBottom != bottomPadding) {
            setPadding(getPaddingLeft(), topPadding, getPaddingRight(), bottomPadding);
        }
        super.addView(child);
    }
}
||||||| merged common ancestors
=======
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

package android.preference;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * @hide
 */
public class PreferenceFrameLayout extends FrameLayout {
    private static final int DEFAULT_TOP_PADDING = 0;
    private static final int DEFAULT_BOTTOM_PADDING = 0;
    private final int mTopPadding;
    private final int mBottomPadding;
    private boolean mPaddingApplied = false;

    public PreferenceFrameLayout(Context context) {
        this(context, null);
    }

    public PreferenceFrameLayout(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.preferenceFrameLayoutStyle);
    }

    public PreferenceFrameLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.PreferenceFrameLayout, defStyle, 0);

        float density = context.getResources().getDisplayMetrics().density;
        int defaultTopPadding = (int) (density * DEFAULT_TOP_PADDING + 0.5f);
        int defaultBottomPadding = (int) (density * DEFAULT_BOTTOM_PADDING + 0.5f);

        mTopPadding = a.getDimensionPixelSize(
                com.android.internal.R.styleable.PreferenceFrameLayout_topPadding,
                defaultTopPadding);
        mBottomPadding = a.getDimensionPixelSize(
                com.android.internal.R.styleable.PreferenceFrameLayout_bottomPadding,
                defaultBottomPadding);



        a.recycle();
    }

    @Override
    public void addView(View child) {
        int topPadding = getPaddingTop();
        int bottomPadding = getPaddingBottom();
        // Check on the id of the child before adding it.
        if (child != null && child.getId() != com.android.internal.R.id.default_preference_layout) {
            // Add the padding to the view group after determining if the padding already exists.
            if (!mPaddingApplied) {
                topPadding += mTopPadding;
                bottomPadding += mBottomPadding;
                mPaddingApplied = true;
            }
        } else {
            if (mPaddingApplied) {
                topPadding -= mTopPadding;
                bottomPadding -= mBottomPadding;
                mPaddingApplied = false;
            }
        }
        int previousTop = getPaddingTop();
        int previousBottom = getPaddingBottom();
        if (previousTop != topPadding || previousBottom != bottomPadding) {
            setPadding(getPaddingLeft(), topPadding, getPaddingRight(), bottomPadding);
        }
        super.addView(child);
    }
}
>>>>>>> master
