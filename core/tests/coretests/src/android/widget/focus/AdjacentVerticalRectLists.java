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
import android.os.Bundle;
import android.util.InternalSelectionView;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/**
 * {@link android.view.FocusFinder#findNextFocus(android.view.ViewGroup, android.view.View, int)}
 * and
 * {@link android.view.View#requestFocus(int, android.graphics.Rect)}
 * work together to give a newly focused item a hint about the most interesting
 * rectangle of the previously focused view.  The view taking focus can use this
 * to set an internal selection more appropriate using this rect.
 *
 * This Activity excercises that behavior using three adjacent {@link android.util.InternalSelectionView}
 * that report interesting rects when giving up focus, and use interesting rects
 * when taking focus to best select the internal row to show as selected.
 */
public class AdjacentVerticalRectLists extends Activity {

    private LinearLayout mLayout;
    private InternalSelectionView mLeftColumn;
    private InternalSelectionView mMiddleColumn;
    private InternalSelectionView mRightColumn;


    public LinearLayout getLayout() {
        return mLayout;
    }

    public InternalSelectionView getLeftColumn() {
        return mLeftColumn;
    }

    public InternalSelectionView getMiddleColumn() {
        return mMiddleColumn;
    }

    public InternalSelectionView getRightColumn() {
        return mRightColumn;
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mLayout = new LinearLayout(this);
        mLayout.setOrientation(LinearLayout.HORIZONTAL);
        mLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1);

        mLeftColumn = new InternalSelectionView(this, 5, "left column");
        mLeftColumn.setLayoutParams(params);
        mLeftColumn.setPadding(10, 10, 10, 10);
        mLayout.addView(mLeftColumn);

        mMiddleColumn = new InternalSelectionView(this, 5, "middle column");
        mMiddleColumn.setLayoutParams(params);
        mMiddleColumn.setPadding(10, 10, 10, 10);
        mLayout.addView(mMiddleColumn);

        mRightColumn = new InternalSelectionView(this, 5, "right column");
        mRightColumn.setLayoutParams(params);
        mRightColumn.setPadding(10, 10, 10, 10);
        mLayout.addView(mRightColumn);

        setContentView(mLayout);
    }


}
