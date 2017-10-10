/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.slice.views;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.graphics.Color;
import android.slice.Slice;
import android.slice.SliceItem;
import android.slice.views.LargeSliceAdapter.SliceListView;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * @hide
 */
public class GridView extends LinearLayout implements SliceListView {

    private static final String TAG = "GridView";

    private static final int MAX_IMAGES = 3;
    private static final int MAX_ALL = 5;
    private boolean mIsAllImages;

    public GridView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mIsAllImages) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int height = width / getChildCount();
            heightMeasureSpec = MeasureSpec.makeMeasureSpec(MeasureSpec.EXACTLY,
                    height);
            getLayoutParams().height = height;
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).getLayoutParams().height = height;
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    public void setSliceItem(SliceItem slice) {
        mIsAllImages = true;
        removeAllViews();
        int total = 1;
        if (slice.getType() == SliceItem.TYPE_SLICE) {
            SliceItem[] items = slice.getSlice().getItems();
            total = items.length;
            for (int i = 0; i < total; i++) {
                SliceItem item = items[i];
                if (isFull()) {
                    continue;
                }
                if (!addItem(item)) {
                    mIsAllImages = false;
                }
            }
        } else {
            if (!isFull()) {
                if (!addItem(slice)) {
                    mIsAllImages = false;
                }
            }
        }
        if (total > getChildCount() && mIsAllImages) {
            addExtraCount(total - getChildCount());
        }
    }

    private void addExtraCount(int numExtra) {
        View last = getChildAt(getChildCount() - 1);
        FrameLayout frame = new FrameLayout(getContext());
        frame.setLayoutParams(last.getLayoutParams());

        removeView(last);
        frame.addView(last, new LayoutParams(MATCH_PARENT, MATCH_PARENT));

        TextView v = new TextView(getContext());
        v.setTextColor(Color.WHITE);
        v.setBackgroundColor(0x4d000000);
        v.setText(getResources().getString(R.string.slice_more_content, numExtra));
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        v.setGravity(Gravity.CENTER);
        frame.addView(v, new LayoutParams(MATCH_PARENT, MATCH_PARENT));

        addView(frame);
    }

    private boolean isFull() {
        return getChildCount() >= (mIsAllImages ? MAX_IMAGES : MAX_ALL);
    }

    /**
     * Returns true if this item is just an image.
     */
    private boolean addItem(SliceItem item) {
        if (item.getType() == SliceItem.TYPE_IMAGE) {
            ImageView v = new ImageView(getContext());
            v.setImageIcon(item.getIcon());
            v.setScaleType(ScaleType.CENTER_CROP);
            addView(v, new LayoutParams(0, MATCH_PARENT, 1));
            return true;
        } else {
            LinearLayout v = new LinearLayout(getContext());
            int s = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                    12, getContext().getResources().getDisplayMetrics());
            v.setPadding(0, s, 0, 0);
            v.setOrientation(LinearLayout.VERTICAL);
            v.setGravity(Gravity.CENTER_HORIZONTAL);
            // TODO: Unify sporadic inflates that happen throughout the code.
            ArrayList<SliceItem> items = new ArrayList<>();
            if (item.getType() == SliceItem.TYPE_SLICE) {
                items.addAll(Arrays.asList(item.getSlice().getItems()));
            }
            items.forEach(i -> {
                Context context = getContext();
                switch (i.getType()) {
                    case SliceItem.TYPE_TEXT:
                        boolean title = false;
                        if ((item.hasAnyHints(new String[] {
                                Slice.HINT_LARGE, Slice.HINT_TITLE
                        }))) {
                            title = true;
                        }
                        TextView tv = (TextView) LayoutInflater.from(context).inflate(
                                title ? R.layout.slice_title : R.layout.slice_secondary_text, null);
                        tv.setText(i.getText());
                        v.addView(tv);
                        break;
                    case SliceItem.TYPE_IMAGE:
                        ImageView iv = new ImageView(context);
                        iv.setImageIcon(i.getIcon());
                        if (item.hasHint(Slice.HINT_LARGE)) {
                            iv.setLayoutParams(new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
                        } else {
                            int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                                    48, context.getResources().getDisplayMetrics());
                            iv.setLayoutParams(new LayoutParams(size, size));
                        }
                        v.addView(iv);
                        break;
                    case SliceItem.TYPE_REMOTE_VIEW:
                        v.addView(i.getRemoteView().apply(context, v));
                        break;
                    case SliceItem.TYPE_COLOR:
                        // TODO: Support color to tint stuff here.
                        break;
                }
            });
            addView(v, new LayoutParams(0, WRAP_CONTENT, 1));
            return false;
        }
    }
}
