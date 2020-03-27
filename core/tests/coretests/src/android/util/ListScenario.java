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

package android.util;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.google.android.collect.Maps;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utility base class for creating various List scenarios.  Configurable by the number
 * of items, how tall each item should be (in relation to the screen height), and
 * what item should start with selection.
 */
public abstract class ListScenario extends Activity {

    private ListView mListView;
    private TextView mHeaderTextView;

    private int mNumItems;
    protected boolean mItemsFocusable;

    private int mStartingSelectionPosition;
    private double mItemScreenSizeFactor;
    private Map<Integer, Double> mOverrideItemScreenSizeFactors = Maps.newHashMap();

    private int mScreenHeight;

    // whether to include a text view above the list
    private boolean mIncludeHeader;

    // separators
    private Set<Integer> mUnselectableItems = new HashSet<Integer>();

    private boolean mStackFromBottom;

    private int mClickedPosition = -1;

    private int mLongClickedPosition = -1;

    private int mConvertMisses = 0;

    private int mHeaderViewCount;
    private boolean mHeadersFocusable;

    private int mFooterViewCount;
    private LinearLayout mLinearLayout;

    public ListView getListView() {
        return mListView;
    }

    protected int getScreenHeight() {
        return mScreenHeight;
    }

    /**
     * Return whether the item at position is selectable (i.e is a separator).
     * (external users can access this info using the adapter)
     */
    private boolean isItemAtPositionSelectable(int position) {
        return !mUnselectableItems.contains(position);
    }

    /**
     * Better way to pass in optional params than a honkin' paramater list :)
     */
    public static class Params {
        private int mNumItems = 4;
        private boolean mItemsFocusable = false;
        private int mStartingSelectionPosition = 0;
        private double mItemScreenSizeFactor = 1 / 5;
        private Double mFadingEdgeScreenSizeFactor = null;

        private Map<Integer, Double> mOverrideItemScreenSizeFactors = Maps.newHashMap();

        // separators
        private List<Integer> mUnselectableItems = new ArrayList<Integer>(8);
        // whether to include a text view above the list
        private boolean mIncludeHeader = false;
        private boolean mStackFromBottom = false;
        public boolean mMustFillScreen = true;
        private int mHeaderViewCount;
        private boolean mHeaderFocusable = false;
        private int mFooterViewCount;

        private boolean mConnectAdapter = true;

        /**
         * Set the number of items in the list.
         */
        public Params setNumItems(int numItems) {
            mNumItems = numItems;
            return this;
        }

        /**
         * Set whether the items are focusable.
         */
        public Params setItemsFocusable(boolean itemsFocusable) {
            mItemsFocusable = itemsFocusable;
            return this;
        }

        /**
         * Set the position that starts selected.
         *
         * @param startingSelectionPosition The selected position within the adapter's data set.
         * Pass -1 if you do not want to force a selection.
         * @return
         */
        public Params setStartingSelectionPosition(int startingSelectionPosition) {
            mStartingSelectionPosition = startingSelectionPosition;
            return this;
        }

        /**
         * Set the factor that determines how tall each item is in relation to the
         * screen height.
         */
        public Params setItemScreenSizeFactor(double itemScreenSizeFactor) {
            mItemScreenSizeFactor = itemScreenSizeFactor;
            return this;
        }

        /**
         * Override the item screen size factor for a particular item.  Useful for
         * creating lists with non-uniform item height.
         * @param position The position in the list.
         * @param itemScreenSizeFactor The screen size factor to use for the height.
         */
        public Params setPositionScreenSizeFactorOverride(
                int position, double itemScreenSizeFactor) {
            mOverrideItemScreenSizeFactors.put(position, itemScreenSizeFactor);
            return this;
        }

        /**
         * Set a position as unselectable (a.k.a a separator)
         * @param position
         * @return
         */
        public Params setPositionUnselectable(int position) {
            mUnselectableItems.add(position);
            return this;
        }

        /**
         * Set positions as unselectable (a.k.a a separator)
         */
        public Params setPositionsUnselectable(int ...positions) {
            for (int pos : positions) {
                setPositionUnselectable(pos);
            }
            return this;
        }

        /**
         * Include a header text view above the list.
         * @param includeHeader
         * @return
         */
        public Params includeHeaderAboveList(boolean includeHeader) {
            mIncludeHeader = includeHeader;
            return this;
        }

        /**
         * Sets the stacking direction
         * @param stackFromBottom
         * @return
         */
        public Params setStackFromBottom(boolean stackFromBottom) {
            mStackFromBottom = stackFromBottom;
            return this;
        }

        /**
         * Sets whether the sum of the height of the list items must be at least the
         * height of the list view.
         */
        public Params setMustFillScreen(boolean fillScreen) {
            mMustFillScreen = fillScreen;
            return this;
        }

        /**
         * Set the factor for the fading edge length.
         */
        public Params setFadingEdgeScreenSizeFactor(double fadingEdgeScreenSizeFactor) {
            mFadingEdgeScreenSizeFactor = fadingEdgeScreenSizeFactor;
            return this;
        }

        /**
         * Set the number of header views to appear within the list
         */
        public Params setHeaderViewCount(int headerViewCount) {
            mHeaderViewCount = headerViewCount;
            return this;
        }

        /**
         * Set whether the headers should be focusable.
         * @param headerFocusable Whether the headers should be focusable (i.e
         *   created as edit texts rather than text views).
         */
        public Params setHeaderFocusable(boolean headerFocusable) {
            mHeaderFocusable = headerFocusable;
            return this;
        }

        /**
         * Set the number of footer views to appear within the list
         */
        public Params setFooterViewCount(int footerViewCount) {
            mFooterViewCount = footerViewCount;
            return this;
        }

        /**
         * Sets whether the {@link ListScenario} will automatically set the
         * adapter on the list view. If this is false, the client MUST set it
         * manually (this is useful when adding headers to the list view, which
         * must be done before the adapter is set).
         */
        public Params setConnectAdapter(boolean connectAdapter) {
            mConnectAdapter = connectAdapter;
            return this;
        }
    }

    /**
     * How each scenario customizes its behavior.
     * @param params
     */
    protected abstract void init(Params params);

    /**
     * Override this if you want to know when something has been selected (perhaps
     * more importantly, that {@link android.widget.AdapterView.OnItemSelectedListener} has
     * been triggered).
     */
    protected void positionSelected(int positon) {
    }

    /**
     * Override this if you want to know that nothing is selected.
     */
    protected void nothingSelected() {
    }

    /**
     * Override this if you want to know when something has been clicked (perhaps
     * more importantly, that {@link android.widget.AdapterView.OnItemClickListener} has
     * been triggered).
     */
    protected void positionClicked(int position) {
        setClickedPosition(position);
    }

    /**
     * Override this if you want to know when something has been long clicked (perhaps
     * more importantly, that {@link android.widget.AdapterView.OnItemLongClickListener} has
     * been triggered).
     */
    protected void positionLongClicked(int position) {
        setLongClickedPosition(position);
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        // for test stability, turn off title bar
        requestWindowFeature(Window.FEATURE_NO_TITLE);


        mScreenHeight = getWindowManager().getCurrentWindowMetrics().getBounds().height();

        final Params params = createParams();
        init(params);

        readAndValidateParams(params);


        mListView = createListView();
        mListView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        mListView.setDrawSelectorOnTop(false);

        for (int i=0; i<mHeaderViewCount; i++) {
            TextView header = mHeadersFocusable ?
                    new EditText(this) :
                    new TextView(this);
            header.setText("Header: " + i);
            mListView.addHeaderView(header);
        }

        for (int i=0; i<mFooterViewCount; i++) {
            TextView header = new TextView(this);
            header.setText("Footer: " + i);
            mListView.addFooterView(header);
        }

        if (params.mConnectAdapter) {
            setAdapter(mListView);
        }

        mListView.setItemsCanFocus(mItemsFocusable);
        if (mStartingSelectionPosition >= 0) {
            mListView.setSelection(mStartingSelectionPosition);
        }
        mListView.setPadding(0, 0, 0, 0);
        mListView.setStackFromBottom(mStackFromBottom);
        mListView.setDivider(null);

        mListView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView parent, View v, int position, long id) {
                positionSelected(position);
            }

            public void onNothingSelected(AdapterView parent) {
                nothingSelected();
            }
        });

        mListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView parent, View v, int position, long id) {
                positionClicked(position);
            }
        });

        // set the fading edge length porportionally to the screen
        // height for test stability
        if (params.mFadingEdgeScreenSizeFactor != null) {
            mListView.setFadingEdgeLength(
                    (int) (params.mFadingEdgeScreenSizeFactor * mScreenHeight));
        } else {
            mListView.setFadingEdgeLength((int) ((64.0 / 480) * mScreenHeight));
        }

        if (mIncludeHeader) {
            mLinearLayout = new LinearLayout(this);

            mHeaderTextView = new TextView(this);
            mHeaderTextView.setText("hi");
            mHeaderTextView.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            mLinearLayout.addView(mHeaderTextView);

            mLinearLayout.setOrientation(LinearLayout.VERTICAL);
            mLinearLayout.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            mListView.setLayoutParams((new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f)));

            mLinearLayout.addView(mListView);
            setContentView(mLinearLayout);
        } else {
            mLinearLayout = new LinearLayout(this);
            mLinearLayout.setOrientation(LinearLayout.VERTICAL);
            mLinearLayout.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            mListView.setLayoutParams((new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f)));
            mLinearLayout.addView(mListView);
            setContentView(mLinearLayout);
        }
        mLinearLayout.restoreDefaultFocus();
    }

    /**
     * Returns the LinearLayout containing the ListView in this scenario.
     *
     * @return The LinearLayout in which the ListView is held.
     */
    protected LinearLayout getListViewContainer() {
        return mLinearLayout;
    }

    /**
     * Attaches a long press listener. You can find out which views were clicked by calling
     * {@link #getLongClickedPosition()}.
     */
    public void enableLongPress() {
        mListView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView parent, View v, int position, long id) {
                positionLongClicked(position);
                return true;
            }
        });
    }

    /**
     * @return The newly created ListView widget.
     */
    protected ListView createListView() {
        return new ListView(this);
    }

    /**
     * @return The newly created Params object.
     */
    protected Params createParams() {
        return new Params();
    }

    /**
     * Sets an adapter on a ListView.
     *
     * @param listView The ListView to set the adapter on.
     */
    protected void setAdapter(ListView listView) {
        listView.setAdapter(new MyAdapter());
    }

    /**
     * Read in and validate all of the params passed in by the scenario.
     * @param params
     */
    protected void readAndValidateParams(Params params) {
        if (params.mMustFillScreen ) {
            double totalFactor = 0.0;
            for (int i = 0; i < params.mNumItems; i++) {
                if (params.mOverrideItemScreenSizeFactors.containsKey(i)) {
                    totalFactor += params.mOverrideItemScreenSizeFactors.get(i);
                } else {
                    totalFactor += params.mItemScreenSizeFactor;
                }
            }
            if (totalFactor < 1.0) {
                throw new IllegalArgumentException("list items must combine to be at least " +
                        "the height of the screen.  this is not the case with " + params.mNumItems
                        + " items and " + params.mItemScreenSizeFactor + " screen factor and " +
                        "screen height of " + mScreenHeight);
            }
        }

        mNumItems = params.mNumItems;
        mItemsFocusable = params.mItemsFocusable;
        mStartingSelectionPosition = params.mStartingSelectionPosition;
        mItemScreenSizeFactor = params.mItemScreenSizeFactor;

        mOverrideItemScreenSizeFactors.putAll(params.mOverrideItemScreenSizeFactors);

        mUnselectableItems.addAll(params.mUnselectableItems);
        mIncludeHeader = params.mIncludeHeader;
        mStackFromBottom = params.mStackFromBottom;
        mHeaderViewCount = params.mHeaderViewCount;
        mHeadersFocusable = params.mHeaderFocusable;
        mFooterViewCount = params.mFooterViewCount;
    }

    public final String getValueAtPosition(int position) {
        return isItemAtPositionSelectable(position)
                ?
                "position " + position:
                "------- " + position;
    }

    /**
     * @return The height that will be set for a particular position.
     */
    public int getHeightForPosition(int position) {
        int desiredHeight = (int) (mScreenHeight * mItemScreenSizeFactor);
        if (mOverrideItemScreenSizeFactors.containsKey(position)) {
            desiredHeight = (int) (mScreenHeight * mOverrideItemScreenSizeFactors.get(position));
        }
        return desiredHeight;
    }


    /**
     * @return The contents of the header above the list.
     * @throws IllegalArgumentException if there is no header.
     */
    public final String getHeaderValue() {
        if (!mIncludeHeader) {
            throw new IllegalArgumentException("no header above list");
        }
        return mHeaderTextView.getText().toString();
    }

    /**
     * @param value What to put in the header text view
     * @throws IllegalArgumentException if there is no header.
     */
    protected final void setHeaderValue(String value) {
        if (!mIncludeHeader) {
            throw new IllegalArgumentException("no header above list");
        }
        mHeaderTextView.setText(value);
    }

    /**
     * Create a view for a list item.  Override this to create a custom view beyond
     * the simple focusable / unfocusable text view.
     * @param position The position.
     * @param parent The parent
     * @param desiredHeight The height the view should be to respect the desired item
     *   to screen height ratio.
     * @return a view for the list.
     */
    protected View createView(int position, ViewGroup parent, int desiredHeight) {
        return ListItemFactory.text(position, parent.getContext(), getValueAtPosition(position),
                desiredHeight);
    }

    /**
     * Convert a non-null view.
     */
    public View convertView(int position, View convertView, ViewGroup parent) {
        return ListItemFactory.convertText(convertView, getValueAtPosition(position), position);
    }

    public void setClickedPosition(int clickedPosition) {
        mClickedPosition = clickedPosition;
    }

    public int getClickedPosition() {
        return mClickedPosition;
    }

    public void setLongClickedPosition(int longClickedPosition) {
        mLongClickedPosition = longClickedPosition;
    }

    public int getLongClickedPosition() {
        return mLongClickedPosition;
    }

    /**
     * Have a child of the list view call {@link View#requestRectangleOnScreen(android.graphics.Rect)}.
     * @param childIndex The index into the viewgroup children (i.e the children that are
     *   currently visible).
     * @param rect The rectangle, in the child's coordinates.
     */
    public void requestRectangleOnScreen(int childIndex, final Rect rect) {
        final View child = getListView().getChildAt(childIndex);

        child.post(new Runnable() {
            public void run() {
                child.requestRectangleOnScreen(rect);
            }
        });
    }

    /**
     * Return an item type for the specified position in the adapter. Override if your
     * adapter creates more than one type.
     */
    public int getItemViewType(int position) {
        return 0;
    }

    /**
     * Return the number of types created by the adapter. Override if your
     * adapter creates more than one type.
     */
    public int getViewTypeCount() {
        return 1;
    }

    /**
     * @return The number of times convertView failed
     */
    public int getConvertMisses() {
        return mConvertMisses;
    }

    private class MyAdapter extends BaseAdapter {

        public int getCount() {
            return mNumItems;
        }

        public Object getItem(int position) {
            return getValueAtPosition(position);
        }

        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return mUnselectableItems.isEmpty();
        }

        @Override
        public boolean isEnabled(int position) {
            return isItemAtPositionSelectable(position);
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View result = null;
            if (position >= mNumItems || position < 0) {
                throw new IllegalStateException("position out of range for adapter!");
            }

            if (convertView != null) {
                result = convertView(position, convertView, parent);
                if (result == null) {
                    mConvertMisses++;
                }
            }

            if (result == null) {
                int desiredHeight = getHeightForPosition(position);
                result = createView(position, parent, desiredHeight);
            }
            return result;
        }

        @Override
        public int getItemViewType(int position) {
            return ListScenario.this.getItemViewType(position);
        }

        @Override
        public int getViewTypeCount() {
            return ListScenario.this.getViewTypeCount();
        }

    }
}
