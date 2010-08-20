/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.annotation.Widget;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.res.TypedArray;
import android.database.DataSetObserver;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;


/**
 * A view that displays one child at a time and lets the user pick among them.
 * The items in the Spinner come from the {@link Adapter} associated with
 * this view.
 *
 * <p>See the <a href="{@docRoot}resources/tutorials/views/hello-spinner.html">Spinner
 * tutorial</a>.</p>
 * 
 * @attr ref android.R.styleable#Spinner_prompt
 */
@Widget
public class Spinner extends AbsSpinner implements OnClickListener {
    
    private CharSequence mPrompt;
    private AlertDialog mPopup;

    public Spinner(Context context) {
        this(context, null);
    }

    public Spinner(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.spinnerStyle);
    }

    public Spinner(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.Spinner, defStyle, 0);
        
        mPrompt = a.getString(com.android.internal.R.styleable.Spinner_prompt);

        a.recycle();
    }

    @Override
    public int getBaseline() {
        View child = null;

        if (getChildCount() > 0) {
            child = getChildAt(0);
        } else if (mAdapter != null && mAdapter.getCount() > 0) {
            child = makeAndAddView(0);
            // TODO: We should probably put the child in the recycler
        }

        if (child != null) {
            return child.getTop() + child.getBaseline();
        } else {
            return -1;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        
        if (mPopup != null && mPopup.isShowing()) {
            mPopup.dismiss();
            mPopup = null;
        }
    }

    /**
     * <p>A spinner does not support item click events. Calling this method
     * will raise an exception.</p>
     *
     * @param l this listener will be ignored
     */
    @Override
    public void setOnItemClickListener(OnItemClickListener l) {
        throw new RuntimeException("setOnItemClickListener cannot be used with a spinner.");
    }

    /**
     * @see android.view.View#onLayout(boolean,int,int,int,int)
     *
     * Creates and positions all views
     *
     */
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        mInLayout = true;
        layout(0, false);
        mInLayout = false;
    }

    /**
     * Creates and positions all views for this Spinner.
     *
     * @param delta Change in the selected position. +1 moves selection is moving to the right,
     * so views are scrolling to the left. -1 means selection is moving to the left.
     */
    @Override
    void layout(int delta, boolean animate) {
        int childrenLeft = mSpinnerPadding.left;
        int childrenWidth = mRight - mLeft - mSpinnerPadding.left - mSpinnerPadding.right;

        if (mDataChanged) {
            handleDataChanged();
        }

        // Handle the empty set by removing all views
        if (mItemCount == 0) {
            resetList();
            return;
        }

        if (mNextSelectedPosition >= 0) {
            setSelectedPositionInt(mNextSelectedPosition);
        }

        recycleAllViews();

        // Clear out old views
        removeAllViewsInLayout();

        // Make selected view and center it
        mFirstPosition = mSelectedPosition;
        View sel = makeAndAddView(mSelectedPosition);
        int width = sel.getMeasuredWidth();
        int selectedOffset = childrenLeft + (childrenWidth / 2) - (width / 2);
        sel.offsetLeftAndRight(selectedOffset);

        // Flush any cached views that did not get reused above
        mRecycler.clear();

        invalidate();

        checkSelectionChanged();

        mDataChanged = false;
        mNeedSync = false;
        setNextSelectedPositionInt(mSelectedPosition);
    }

    /**
     * Obtain a view, either by pulling an existing view from the recycler or
     * by getting a new one from the adapter. If we are animating, make sure
     * there is enough information in the view's layout parameters to animate
     * from the old to new positions.
     *
     * @param position Position in the spinner for the view to obtain
     * @return A view that has been added to the spinner
     */
    private View makeAndAddView(int position) {

        View child;

        if (!mDataChanged) {
            child = mRecycler.get(position);
            if (child != null) {
                // Position the view
                setUpChild(child);

                return child;
            }
        }

        // Nothing found in the recycler -- ask the adapter for a view
        child = mAdapter.getView(position, null, this);

        // Position the view
        setUpChild(child);

        return child;
    }



    /**
     * Helper for makeAndAddView to set the position of a view
     * and fill out its layout paramters.
     *
     * @param child The view to position
     */
    private void setUpChild(View child) {

        // Respect layout params that are already in the view. Otherwise
        // make some up...
        ViewGroup.LayoutParams lp = child.getLayoutParams();
        if (lp == null) {
            lp = generateDefaultLayoutParams();
        }

        addViewInLayout(child, 0, lp);

        child.setSelected(hasFocus());

        // Get measure specs
        int childHeightSpec = ViewGroup.getChildMeasureSpec(mHeightMeasureSpec,
                mSpinnerPadding.top + mSpinnerPadding.bottom, lp.height);
        int childWidthSpec = ViewGroup.getChildMeasureSpec(mWidthMeasureSpec,
                mSpinnerPadding.left + mSpinnerPadding.right, lp.width);

        // Measure child
        child.measure(childWidthSpec, childHeightSpec);

        int childLeft;
        int childRight;

        // Position vertically based on gravity setting
        int childTop = mSpinnerPadding.top
                + ((mMeasuredHeight - mSpinnerPadding.bottom - 
                        mSpinnerPadding.top - child.getMeasuredHeight()) / 2);
        int childBottom = childTop + child.getMeasuredHeight();

        int width = child.getMeasuredWidth();
        childLeft = 0;
        childRight = childLeft + width;

        child.layout(childLeft, childTop, childRight, childBottom);
    }

    @Override
    public boolean performClick() {
        boolean handled = super.performClick();
        
        if (!handled) {
            handled = true;
            Context context = getContext();
            
            final DropDownAdapter adapter = new DropDownAdapter(getAdapter());

            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            if (mPrompt != null) {
                builder.setTitle(mPrompt);
            }
            mPopup = builder.setSingleChoiceItems(adapter, getSelectedItemPosition(), this).show();
        }

        return handled;
    }
    
    public void onClick(DialogInterface dialog, int which) {
        setSelection(which);
        dialog.dismiss();
        mPopup = null;
    }

    /**
     * Sets the prompt to display when the dialog is shown.
     * @param prompt the prompt to set
     */
    public void setPrompt(CharSequence prompt) {
        mPrompt = prompt;
    }

    /**
     * Sets the prompt to display when the dialog is shown.
     * @param promptId the resource ID of the prompt to display when the dialog is shown
     */
    public void setPromptId(int promptId) {
        mPrompt = getContext().getText(promptId);
    }

    /**
     * @return The prompt to display when the dialog is shown
     */
    public CharSequence getPrompt() {
        return mPrompt;
    }
    
    /**
     * <p>Wrapper class for an Adapter. Transforms the embedded Adapter instance
     * into a ListAdapter.</p>
     */
    private static class DropDownAdapter implements ListAdapter, SpinnerAdapter {
        private SpinnerAdapter mAdapter;
        private ListAdapter mListAdapter;

        /**
         * <p>Creates a new ListAdapter wrapper for the specified adapter.</p>
         *
         * @param adapter the Adapter to transform into a ListAdapter
         */
        public DropDownAdapter(SpinnerAdapter adapter) {
            this.mAdapter = adapter;
            if (adapter instanceof ListAdapter) {
                this.mListAdapter = (ListAdapter) adapter;
            }
        }

        public int getCount() {
            return mAdapter == null ? 0 : mAdapter.getCount();
        }

        public Object getItem(int position) {
            return mAdapter == null ? null : mAdapter.getItem(position);
        }

        public long getItemId(int position) {
            return mAdapter == null ? -1 : mAdapter.getItemId(position);
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            return getDropDownView(position, convertView, parent);
        }

        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return mAdapter == null ? null :
                    mAdapter.getDropDownView(position, convertView, parent);
        }

        public boolean hasStableIds() {
            return mAdapter != null && mAdapter.hasStableIds();
        }

        public void registerDataSetObserver(DataSetObserver observer) {
            if (mAdapter != null) {
                mAdapter.registerDataSetObserver(observer);
            }
        }

        public void unregisterDataSetObserver(DataSetObserver observer) {
            if (mAdapter != null) {
                mAdapter.unregisterDataSetObserver(observer);
            }
        }

        /**
         * If the wrapped SpinnerAdapter is also a ListAdapter, delegate this call.
         * Otherwise, return true. 
         */
        public boolean areAllItemsEnabled() {
            final ListAdapter adapter = mListAdapter;
            if (adapter != null) {
                return adapter.areAllItemsEnabled();
            } else {
                return true;
            }
        }

        /**
         * If the wrapped SpinnerAdapter is also a ListAdapter, delegate this call.
         * Otherwise, return true.
         */
        public boolean isEnabled(int position) {
            final ListAdapter adapter = mListAdapter;
            if (adapter != null) {
                return adapter.isEnabled(position);
            } else {
                return true;
            }
        }

        public int getItemViewType(int position) {
            return 0;
        }

        public int getViewTypeCount() {
            return 1;
        }
        
        public boolean isEmpty() {
            return getCount() == 0;
        }
    }
}
