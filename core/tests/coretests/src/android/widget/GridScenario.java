/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ListAdapter;
import android.widget.TextView;

import com.google.android.collect.Maps;

import java.util.Map;

/**
 * Utility base class for creating various GridView scenarios.  Configurable by the number
 * of items, how tall each item should be (in relation to the screen height), and
 * what item should start with selection.
 */
public abstract class GridScenario extends Activity {

    private GridView mGridView;

    private int mNumItems;

    private int mStartingSelectionPosition;
    private double mItemScreenSizeFactor;
    private Map<Integer, Double> mOverrideItemScreenSizeFactors = Maps.newHashMap();

    private int mScreenHeight;

    private boolean mStackFromBottom;

    private int mColumnWidth;
    
    private int mNumColumns;
    
    private int mStretchMode;

    private int mVerticalSpacing;

    public GridView getGridView() {
        return mGridView;
    }

    protected int getScreenHeight() {
        return mScreenHeight;
    }
    
    /**
     * @return The initial number of items in the grid as specified by the scenario.
     * This number may change over time.
     */
    protected int getInitialNumItems() {
        return mNumItems;
    }
    
    /**
     * @return The desired height of 1 item, ignoring overrides
     */
    public int getDesiredItemHeight() {
        return (int) (mScreenHeight * mItemScreenSizeFactor);
    }

    /**
     * Better way to pass in optional params than a honkin' paramater list :)
     */
    public static class Params {
        private int mNumItems = 4;
        private int mStartingSelectionPosition = -1;
        private double mItemScreenSizeFactor = 1 / 5;

        private Map<Integer, Double> mOverrideItemScreenSizeFactors = Maps.newHashMap();

        private boolean mStackFromBottom = false;
        private boolean mMustFillScreen = true;

        private int mColumnWidth = 0;
        private int mNumColumns = GridView.AUTO_FIT;
        private int mStretchMode = GridView.STRETCH_COLUMN_WIDTH;
        private int mVerticalSpacing = 0;

        /**
         * Set the number of items in the grid.
         */
        public Params setNumItems(int numItems) {
            mNumItems = numItems;
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
         * creating grids with non-uniform item height.
         * @param position The position in the grid.
         * @param itemScreenSizeFactor The screen size factor to use for the height.
         */
        public Params setPositionScreenSizeFactorOverride(
                int position, double itemScreenSizeFactor) {
            mOverrideItemScreenSizeFactors.put(position, itemScreenSizeFactor);
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
         * Sets whether the sum of the height of the grid items must be at least the
         * height of the grid view.
         */
        public Params setMustFillScreen(boolean fillScreen) {
            mMustFillScreen = fillScreen;
            return this;
        }

        /**
         * Sets the individual width of each column.
         *
         * @param requestedWidth the width in pixels of the column
         */
        public Params setColumnWidth(int requestedWidth) {
            mColumnWidth = requestedWidth;
            return this;
        }

        /**
         * Sets the number of columns in the grid.
         */
        public Params setNumColumns(int numColumns) {
            mNumColumns = numColumns;
            return this;
        }
        
        /**
         * Sets the stretch mode.
         */
        public Params setStretchMode(int stretchMode) {
            mStretchMode = stretchMode;
            return this;
        }
        
        /**
         * Sets the spacing between rows in the grid
         */
        public Params setVerticalSpacing(int verticalSpacing) {
            mVerticalSpacing  = verticalSpacing;
            return this;
        }
    }

    /**
     * How each scenario customizes its behavior.
     * @param params
     */
    protected abstract void init(Params params);
    
    /**
     * Override this to provide an different adapter for your scenario
     * @return The adapter that this scenario will use
     */
    protected ListAdapter createAdapter() {
        return new MyAdapter();
    }

    /**
     * Override this if you want to know when something has been selected (perhaps
     * more importantly, that {@link android.widget.AdapterView.OnItemSelectedListener} has
     * been triggered).
     */
    @SuppressWarnings({ "UnusedDeclaration" })
    protected void positionSelected(int positon) {

    }

    /**
     * Override this if you want to know that nothing is selected.
     */
    protected void nothingSelected() {

    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        // turn off title bar
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        mScreenHeight = getWindowManager().getCurrentWindowMetrics().getBounds().height();

        final Params params = new Params();
        init(params);

        readAndValidateParams(params);

        mGridView = new GridView(this);
        mGridView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        mGridView.setDrawSelectorOnTop(false);
        if (mNumColumns >= GridView.AUTO_FIT) {
            mGridView.setNumColumns(mNumColumns);
        }
        if (mColumnWidth > 0) {
            mGridView.setColumnWidth(mColumnWidth);
        }
        if (mVerticalSpacing > 0) {
            mGridView.setVerticalSpacing(mVerticalSpacing);
        }
        mGridView.setStretchMode(mStretchMode);
        mGridView.setAdapter(createAdapter());
        if (mStartingSelectionPosition >= 0) {
            mGridView.setSelection(mStartingSelectionPosition);
        }
        mGridView.setPadding(10, 10, 10, 10);
        mGridView.setStackFromBottom(mStackFromBottom);

        mGridView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView parent, View v, int position, long id) {
                positionSelected(position);
            }

            public void onNothingSelected(AdapterView parent) {
                nothingSelected();
            }
        });

        setContentView(mGridView);
    }
    
    

    /**
     * Read in and validate all of the params passed in by the scenario.
     * @param params
     */
    private void readAndValidateParams(Params params) {
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
                throw new IllegalArgumentException("grid items must combine to be at least " +
                        "the height of the screen.  this is not the case with " + params.mNumItems
                        + " items and " + params.mItemScreenSizeFactor + " screen factor and " +
                        "screen height of " + mScreenHeight);
            }
        }

        mNumItems = params.mNumItems;
        mStartingSelectionPosition = params.mStartingSelectionPosition;
        mItemScreenSizeFactor = params.mItemScreenSizeFactor;

        mOverrideItemScreenSizeFactors.putAll(params.mOverrideItemScreenSizeFactors);

        mStackFromBottom = params.mStackFromBottom;
        mColumnWidth = params.mColumnWidth;
        mNumColumns = params.mNumColumns;
        mStretchMode = params.mStretchMode;
        mVerticalSpacing = params.mVerticalSpacing;
    }

    public final String getValueAtPosition(int position) {
        return "postion " + position;
    }

    /**
     * Create a view for a grid item.  Override this to create a custom view beyond
     * the simple focusable / unfocusable text view.
     * @param position The position.
     * @param parent The parent
     * @param desiredHeight The height the view should be to respect the desired item
     *   to screen height ratio.
     * @return a view for the grid.
     */
    protected View createView(int position, ViewGroup parent, int desiredHeight) {
        TextView result = new TextView(parent.getContext());
        result.setHeight(desiredHeight);
        result.setText(getValueAtPosition(position));
        final ViewGroup.LayoutParams lp = new AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        result.setLayoutParams(lp);
        result.setId(position);
        result.setBackgroundColor(0x55ffffff);
        return result;
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

        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView != null) {
                ((TextView) convertView).setText(getValueAtPosition(position));
                convertView.setId(position);
                return convertView;
            }

            int desiredHeight = getDesiredItemHeight();
            if (mOverrideItemScreenSizeFactors.containsKey(position)) {
                desiredHeight = (int) (mScreenHeight * mOverrideItemScreenSizeFactors.get(position));
            }
            return createView(position, parent, desiredHeight);
        }
    }
}
