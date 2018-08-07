/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.widget.listview;

import android.app.Activity;
import android.os.Bundle;
import android.util.InternalSelectionView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;

/**
 * Most bodacious scenario yet!
 */
public class AdjacentListsWithAdjacentISVsInside extends Activity {

    private ListView mLeftListView;
    private ListView mRightListView;

    public ListView getLeftListView() {
        return mLeftListView;
    }

    public ListView getRightListView() {
        return mRightListView;
    }

    public InternalSelectionView getLeftIsv() {
        return (InternalSelectionView)
                ((ViewGroup) mLeftListView.getChildAt(0)).getChildAt(0);
    }

    public InternalSelectionView getLeftMiddleIsv() {
        return (InternalSelectionView)
                ((ViewGroup) mLeftListView.getChildAt(0)).getChildAt(1);
    }

    public InternalSelectionView getRightMiddleIsv() {
        return (InternalSelectionView)
                ((ViewGroup) mRightListView.getChildAt(0)).getChildAt(0);
    }

    public InternalSelectionView getRightIsv() {
        return (InternalSelectionView)
                ((ViewGroup) mRightListView.getChildAt(0)).getChildAt(1);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final int desiredHeight = (int) (0.8 * getWindowManager().getDefaultDisplay().getHeight());

        mLeftListView = new ListView(this);
        mLeftListView.setAdapter(new AdjacentISVAdapter(desiredHeight));
        mLeftListView.setItemsCanFocus(true);


        mRightListView = new ListView(this);
        mRightListView.setAdapter(new AdjacentISVAdapter(desiredHeight));
        mRightListView.setItemsCanFocus(true);



        setContentView(combineAdjacent(mLeftListView, mRightListView));
        getWindow().getDecorView().restoreDefaultFocus();
    }

    private static View combineAdjacent(View... views) {
        if (views.length < 2) {
            throw new IllegalArgumentException("you should pass at least 2 views in");
        }

        final LinearLayout ll = new LinearLayout(views[0].getContext());
        ll.setOrientation(LinearLayout.HORIZONTAL);
        final LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);

        for (View view : views) {
            ll.addView(view, lp);
        }
        return ll;
    }

    static class AdjacentISVAdapter extends BaseAdapter {

        private final int mItemHeight;

        AdjacentISVAdapter(int itemHeight) {
            mItemHeight = itemHeight;
        }

        public int getCount() {
            return 1;
        }

        public Object getItem(int position) {
            return position;
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            final InternalSelectionView isvLeft = new InternalSelectionView(
                    parent.getContext(), 5, "isv left");
            isvLeft.setDesiredHeight(mItemHeight);
            final InternalSelectionView isvRight = new InternalSelectionView(
                    parent.getContext(), 5, "isv right");
            isvRight.setDesiredHeight(mItemHeight);
            return combineAdjacent(isvLeft, isvRight);
        }
    }
}
