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

package android.app;

import android.app.FragmentManager.BackStackEntry;
import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Helper class for showing "bread crumbs" representing the fragment
 * stack in an activity.  This is intended to be used with
 * {@link ActionBar#setCustomNavigationMode(View)
 * ActionBar.setCustomNavigationMode(View)} to place the bread crumbs in
 * the navigation area of the action bar.
 *
 * <p>The default style for this view is
 * {@link android.R.style#Widget_FragmentBreadCrumbs}.
 */
public class FragmentBreadCrumbs extends ViewGroup
        implements FragmentManager.OnBackStackChangedListener {
    Activity mActivity;
    LayoutInflater mInflater;
    LinearLayout mContainer;

    // Hahah
    BackStackRecord mTopEntry;

    public FragmentBreadCrumbs(Context context) {
        this(context, null);
    }

    public FragmentBreadCrumbs(Context context, AttributeSet attrs) {
        this(context, attrs, android.R.style.Widget_FragmentBreadCrumbs);
    }

    public FragmentBreadCrumbs(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    /**
     * Attach the bread crumbs to their activity.  This must be called once
     * when creating the bread crumbs.
     */
    public void setActivity(Activity a) {
        mActivity = a;
        mInflater = (LayoutInflater)a.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mContainer = (LinearLayout)mInflater.inflate(
                com.android.internal.R.layout.fragment_bread_crumbs,
                this, false);
        addView(mContainer);
        a.getFragmentManager().addOnBackStackChangedListener(this);
        updateCrumbs();
    }

    /**
     * Set a custom title for the bread crumbs.  This will be the first entry
     * shown at the left, representing the root of the bread crumbs.  If the
     * title is null, it will not be shown.
     */
    public void setTitle(CharSequence title, CharSequence shortTitle) {
        if (title == null) {
            mTopEntry = null;
        } else {
            mTopEntry = new BackStackRecord((FragmentManagerImpl)
                    mActivity.getFragmentManager());
            mTopEntry.setBreadCrumbTitle(title);
            mTopEntry.setBreadCrumbShortTitle(shortTitle);
        }
        updateCrumbs();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        // Eventually we should implement our own layout of the views,
        // rather than relying on a linear layout.
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);

            int childRight = mPaddingLeft + child.getMeasuredWidth() - mPaddingRight;
            int childBottom = mPaddingTop + child.getMeasuredHeight() - mPaddingBottom;
            child.layout(mPaddingLeft, mPaddingTop, childRight, childBottom);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int count = getChildCount();

        int maxHeight = 0;
        int maxWidth = 0;

        // Find rightmost and bottom-most child
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                measureChild(child, widthMeasureSpec, heightMeasureSpec);
                maxWidth = Math.max(maxWidth, child.getMeasuredWidth());
                maxHeight = Math.max(maxHeight, child.getMeasuredHeight());
            }
        }

        // Account for padding too
        maxWidth += mPaddingLeft + mPaddingRight;
        maxHeight += mPaddingTop + mPaddingBottom;

        // Check against our minimum height and width
        maxHeight = Math.max(maxHeight, getSuggestedMinimumHeight());
        maxWidth = Math.max(maxWidth, getSuggestedMinimumWidth());

        setMeasuredDimension(resolveSize(maxWidth, widthMeasureSpec),
                resolveSize(maxHeight, heightMeasureSpec));
    }

    @Override
    public void onBackStackChanged() {
        updateCrumbs();
    }

    void updateCrumbs() {
        FragmentManager fm = mActivity.getFragmentManager();
        int numEntries = fm.countBackStackEntries();
        int numViews = mContainer.getChildCount();
        for (int i = mTopEntry != null ? -1 : 0; i < numEntries; i++) {
            BackStackEntry bse = i == -1 ? mTopEntry : fm.getBackStackEntry(i);
            int viewI = mTopEntry != null ? i + 1 : i;
            if (viewI < numViews) {
                View v = mContainer.getChildAt(viewI);
                Object tag = v.getTag();
                if (tag != bse) {
                    for (int j = viewI; j < numViews; j++) {
                        mContainer.removeViewAt(viewI);
                    }
                    numViews = viewI;
                }
            }
            if (viewI >= numViews) {
                View item = mInflater.inflate(
                        com.android.internal.R.layout.fragment_bread_crumb_item,
                        this, false);
                TextView text = (TextView)item.findViewById(com.android.internal.R.id.title);
                text.setText(bse.getBreadCrumbTitle());
                item.setTag(bse);
                if (viewI == 0) {
                    text.setCompoundDrawables(null, null, null, null);
                }
                mContainer.addView(item);
                item.setOnClickListener(mOnClickListener);
            }
        }
        int viewI = mTopEntry != null ? numEntries + 1 : numEntries;
        numViews = mContainer.getChildCount();
        while (numViews > viewI) {
            mContainer.removeViewAt(numViews-1);
            numViews--;
        }
    }

    private OnClickListener mOnClickListener = new OnClickListener() {
        public void onClick(View v) {
            if (v.getTag() instanceof BackStackEntry) {
                BackStackEntry bse = (BackStackEntry) v.getTag();
                mActivity.getFragmentManager().popBackStack(bse.getId(),
                        bse == mTopEntry? FragmentManager.POP_BACK_STACK_INCLUSIVE : 0);
            }
        }
    };
}
