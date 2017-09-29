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

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.content.Context;
import android.slice.Slice;
import android.slice.SliceItem;
import android.slice.SliceQuery;
import android.slice.views.SliceView.SliceModeView;
import android.util.TypedValue;

import com.android.internal.widget.LinearLayoutManager;
import com.android.internal.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @hide
 */
public class LargeTemplateView extends SliceModeView {
    private final LargeSliceAdapter mAdapter;
    private final RecyclerView mRecyclerView;
    private final int mDefaultHeight;
    private final int mMaxHeight;
    private Slice mSlice;

    public LargeTemplateView(Context context) {
        super(context);

        mRecyclerView = new RecyclerView(getContext());
        mRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        mAdapter = new LargeSliceAdapter(context);
        mRecyclerView.setAdapter(mAdapter);
        addView(mRecyclerView);
        int width = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 300,
                getResources().getDisplayMetrics());
        setLayoutParams(new LayoutParams(width, WRAP_CONTENT));
        mDefaultHeight = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 200,
                getResources().getDisplayMetrics());
        mMaxHeight = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 200,
                getResources().getDisplayMetrics());
    }

    @Override
    public String getMode() {
        return SliceView.MODE_LARGE;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        mRecyclerView.getLayoutParams().height = WRAP_CONTENT;
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (mRecyclerView.getMeasuredHeight() > mMaxHeight
                || mSlice.hasHint(Slice.HINT_PARTIAL)) {
            mRecyclerView.getLayoutParams().height = mDefaultHeight;
        } else {
            mRecyclerView.getLayoutParams().height = mRecyclerView.getMeasuredHeight();
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    public void setSlice(Slice slice) {
        SliceItem color = SliceQuery.find(slice, SliceItem.TYPE_COLOR);
        mSlice = slice;
        List<SliceItem> items = new ArrayList<>();
        boolean[] hasHeader = new boolean[1];
        if (slice.hasHint(Slice.HINT_LIST)) {
            addList(slice, items);
        } else {
            Arrays.asList(slice.getItems()).forEach(item -> {
                if (item.hasHint(Slice.HINT_ACTIONS)) {
                    return;
                } else if (item.getType() == SliceItem.TYPE_COLOR) {
                    return;
                } else if (item.getType() == SliceItem.TYPE_SLICE
                        && item.hasHint(Slice.HINT_LIST)) {
                    addList(item.getSlice(), items);
                } else if (item.hasHint(Slice.HINT_LIST_ITEM)) {
                    items.add(item);
                } else if (!hasHeader[0]) {
                    hasHeader[0] = true;
                    items.add(0, item);
                } else {
                    item.addHint(Slice.HINT_LIST_ITEM);
                    items.add(item);
                }
            });
        }
        mAdapter.setSliceItems(items, color);
    }

    private void addList(Slice slice, List<SliceItem> items) {
        List<SliceItem> sliceItems = Arrays.asList(slice.getItems());
        sliceItems.forEach(i -> i.addHint(Slice.HINT_LIST_ITEM));
        items.addAll(sliceItems);
    }
}
