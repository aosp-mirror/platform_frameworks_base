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

package android.widget.focus;

import android.app.Activity;
import android.graphics.Point;
import android.os.Bundle;
import android.util.InternalSelectionView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;

/**
 * A list of {@link InternalSelectionView}s paramatarized by the number of items,
 * how many rows in each item, and how tall each item is.
 */
public class ListOfInternalSelectionViews extends Activity {

    private ListView mListView;


    // keys for initializing via Intent params
    public static final String BUNDLE_PARAM_NUM_ITEMS = "com.google.test.numItems";
    public static final String BUNDLE_PARAM_NUM_ROWS_PER_ITEM = "com.google.test.numRowsPerItem";
    public static final String BUNDLE_PARAM_ITEM_SCREEN_HEIGHT_FACTOR = "com.google.test.itemScreenHeightFactor";

    private int mScreenHeight;

    private int mNumItems = 5;
    private int mNumRowsPerItem = 4;
    private double mItemScreenSizeFactor = 5 / 4;

    public ListView getListView() {
        return mListView;
    }

    /**
     * Each item is screen height * this factor tall.
     */
    public double getItemScreenSizeFactor() {
        return mItemScreenSizeFactor;
    }

    /**
     * @return The number of rows per item.
     */
    public int getNumRowsPerItem() {
        return mNumRowsPerItem;
    }

    /**
     * @return The number of items in the list.
     */
    public int getNumItems() {
        return mNumItems;
    }

    /**
     * @param position The position
     * @return The label (closest thing to a value) for the item at position
     */
    public String getLabelForPosition(int position) {
        return "position " + position;
    }

    /**
     * Get the currently selected view.
     */
    public InternalSelectionView getSelectedView() {
        return (InternalSelectionView) getListView().getSelectedView();
    }

    /**
     * Get the screen height.
     */
    public int getScreenHeight() {
        return mScreenHeight;
    }

    /**
     * Initialize a bundle suitable for sending as the params of the intent that
     * launches this activity.
     * @param numItems The number of items in the list.
     * @param numRowsPerItem The number of rows per item.
     * @param itemScreenHeightFactor see {@link #getScreenHeight()}
     * @return the intialized bundle.
     */
    public static Bundle getBundleFor(int numItems, int numRowsPerItem, double itemScreenHeightFactor) {
        Bundle bundle = new Bundle();
        bundle.putInt(BUNDLE_PARAM_NUM_ITEMS, numItems);
        bundle.putInt(BUNDLE_PARAM_NUM_ROWS_PER_ITEM, numRowsPerItem);
        bundle.putDouble(BUNDLE_PARAM_ITEM_SCREEN_HEIGHT_FACTOR, itemScreenHeightFactor);
        return bundle;
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        Point size = new Point();
        getWindowManager().getDefaultDisplay().getSize(size);
        mScreenHeight = size.y;

        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            initFromBundle(extras);
        }

        mListView = new ListView(this);
        mListView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        mListView.setDrawSelectorOnTop(false);
        mListView.setAdapter(new MyAdapter());
        mListView.setItemsCanFocus(true);
        setContentView(mListView);
    }

    private void initFromBundle(Bundle icicle) {

        int numItems = icicle.getInt(BUNDLE_PARAM_NUM_ITEMS, -1);
        if (numItems != -1) {
            mNumItems = numItems;
        }
        int numRowsPerItem = icicle.getInt(BUNDLE_PARAM_NUM_ROWS_PER_ITEM, -1);
        if (numRowsPerItem != -1) {
            mNumRowsPerItem = numRowsPerItem;
        }
        double screenHeightFactor = icicle.getDouble(BUNDLE_PARAM_ITEM_SCREEN_HEIGHT_FACTOR, -1.0);
        if (screenHeightFactor > 0) {
            mItemScreenSizeFactor = screenHeightFactor;
        }
    }

    private class MyAdapter extends BaseAdapter {

        public int getCount() {
            return mNumItems;
        }

        public Object getItem(int position) {
            return getLabelForPosition(position);
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            InternalSelectionView item =
                    new InternalSelectionView(
                            parent.getContext(),
                            mNumRowsPerItem,
                            getLabelForPosition(position));
            item.setDesiredHeight((int) (mScreenHeight * mItemScreenSizeFactor));
            return item;
        }
    }
}
