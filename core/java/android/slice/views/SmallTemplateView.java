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

import android.app.PendingIntent.CanceledException;
import android.content.Context;
import android.os.AsyncTask;
import android.slice.Slice;
import android.slice.SliceItem;
import android.slice.SliceQuery;
import android.slice.views.LargeSliceAdapter.SliceListView;
import android.slice.views.SliceView.SliceModeView;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.R;

import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * Small template is also used to construct list items for use with {@link LargeTemplateView}.
 *
 * @hide
 */
public class SmallTemplateView extends SliceModeView implements SliceListView {

    private static final String TAG = "SmallTemplateView";

    private int mIconSize;
    private int mPadding;

    private LinearLayout mStartContainer;
    private TextView mTitleText;
    private TextView mSecondaryText;
    private LinearLayout mEndContainer;

    public SmallTemplateView(Context context) {
        super(context);
        inflate(context, R.layout.slice_small_template, this);
        mIconSize = getContext().getResources().getDimensionPixelSize(R.dimen.slice_icon_size);
        mPadding = getContext().getResources().getDimensionPixelSize(R.dimen.slice_padding);

        mStartContainer = (LinearLayout) findViewById(android.R.id.icon_frame);
        mTitleText = (TextView) findViewById(android.R.id.title);
        mSecondaryText = (TextView) findViewById(android.R.id.summary);
        mEndContainer = (LinearLayout) findViewById(android.R.id.widget_frame);
    }

    @Override
    public String getMode() {
        return SliceView.MODE_SMALL;
    }

    @Override
    public void setSliceItem(SliceItem slice) {
        resetViews();
        SliceItem colorItem = SliceQuery.find(slice, SliceItem.TYPE_COLOR);
        int color = colorItem != null ? colorItem.getColor() : -1;

        // Look for any title elements
        List<SliceItem> titleItems = SliceQuery.findAll(slice, -1, Slice.HINT_TITLE,
                null);
        boolean hasTitleText = false;
        boolean hasTitleItem = false;
        for (int i = 0; i < titleItems.size(); i++) {
            SliceItem item = titleItems.get(i);
            if (!hasTitleItem) {
                // icon, action icon, or timestamp
                if (item.getType() == SliceItem.TYPE_ACTION) {
                    hasTitleItem = addIcon(item, color, mStartContainer);
                } else if (item.getType() == SliceItem.TYPE_IMAGE) {
                    addIcon(item, color, mStartContainer);
                    hasTitleItem = true;
                } else if (item.getType() == SliceItem.TYPE_TIMESTAMP) {
                    TextView tv = new TextView(getContext());
                    tv.setText(convertTimeToString(item.getTimestamp()));
                    hasTitleItem = true;
                }
            }
            if (!hasTitleText && item.getType() == SliceItem.TYPE_TEXT) {
                mTitleText.setText(item.getText());
                hasTitleText = true;
            }
            if (hasTitleText && hasTitleItem) {
                break;
            }
        }
        mTitleText.setVisibility(hasTitleText ? View.VISIBLE : View.GONE);
        mStartContainer.setVisibility(hasTitleItem ? View.VISIBLE : View.GONE);

        if (slice.getType() != SliceItem.TYPE_SLICE) {
            return;
        }

        // Deal with remaining items
        int itemCount = 0;
        boolean hasSummary = false;
        ArrayList<SliceItem> sliceItems = new ArrayList<SliceItem>(
                Arrays.asList(slice.getSlice().getItems()));
        for (int i = 0; i < sliceItems.size(); i++) {
            SliceItem item = sliceItems.get(i);
            if (!hasSummary && item.getType() == SliceItem.TYPE_TEXT
                    && !item.hasHint(Slice.HINT_TITLE)) {
                // TODO -- Should combine all text items?
                mSecondaryText.setText(item.getText());
                hasSummary = true;
            }
            if (itemCount <= 3) {
                if (item.getType() == SliceItem.TYPE_ACTION) {
                    if (addIcon(item, color, mEndContainer)) {
                        itemCount++;
                    }
                } else if (item.getType() == SliceItem.TYPE_IMAGE) {
                    addIcon(item, color, mEndContainer);
                    itemCount++;
                } else if (item.getType() == SliceItem.TYPE_TIMESTAMP) {
                    TextView tv = new TextView(getContext());
                    tv.setText(convertTimeToString(item.getTimestamp()));
                    mEndContainer.addView(tv);
                    itemCount++;
                } else if (item.getType() == SliceItem.TYPE_SLICE) {
                    SliceItem[] subItems = item.getSlice().getItems();
                    for (int j = 0; j < subItems.length; j++) {
                        sliceItems.add(subItems[j]);
                    }
                }
            }
        }
    }

    @Override
    public void setSlice(Slice slice) {
        setSliceItem(new SliceItem(slice, SliceItem.TYPE_SLICE, slice.getHints()));
    }

    /**
     * @return Whether an icon was added.
     */
    private boolean addIcon(SliceItem sliceItem, int color, LinearLayout container) {
        SliceItem image = null;
        SliceItem action = null;
        if (sliceItem.getType() == SliceItem.TYPE_ACTION) {
            image = SliceQuery.find(sliceItem.getSlice(), SliceItem.TYPE_IMAGE);
            action = sliceItem;
        } else if (sliceItem.getType() == SliceItem.TYPE_IMAGE) {
            image = sliceItem;
        }
        if (image != null) {
            ImageView iv = new ImageView(getContext());
            iv.setImageIcon(image.getIcon());
            if (action != null) {
                final SliceItem sliceAction = action;
                iv.setOnClickListener(v -> AsyncTask.execute(
                        () -> {
                            try {
                                sliceAction.getAction().send();
                            } catch (CanceledException e) {
                                e.printStackTrace();
                            }
                        }));
                iv.setBackground(SliceViewUtil.getDrawable(getContext(),
                        android.R.attr.selectableItemBackground));
            }
            if (color != -1 && !sliceItem.hasHint(Slice.HINT_NO_TINT)) {
                iv.setColorFilter(color);
            }
            container.addView(iv);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) iv.getLayoutParams();
            lp.width = mIconSize;
            lp.height = mIconSize;
            lp.setMarginStart(mPadding);
            return true;
        }
        return false;
    }

    private String convertTimeToString(long time) {
        // TODO -- figure out what format(s) we support
        Date date = new Date(time);
        Format format = new SimpleDateFormat("MM dd yyyy HH:mm:ss");
        return format.format(date);
    }

    private void resetViews() {
        mStartContainer.removeAllViews();
        mEndContainer.removeAllViews();
        mTitleText.setText(null);
        mSecondaryText.setText(null);
    }
}
