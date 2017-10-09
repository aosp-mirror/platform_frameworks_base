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

import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.net.Uri;
import android.slice.Slice;
import android.slice.SliceItem;
import android.slice.SliceQuery;
import android.slice.views.SliceView.SliceModeView;
import android.view.ViewGroup;

import com.android.internal.R;

/**
 * @hide
 */
public class ShortcutView extends SliceModeView {

    private static final String TAG = "ShortcutView";

    private PendingIntent mAction;
    private Uri mUri;
    private int mLargeIconSize;
    private int mSmallIconSize;

    public ShortcutView(Context context) {
        super(context);
        mLargeIconSize = getContext().getResources()
                .getDimensionPixelSize(R.dimen.slice_shortcut_size);
        mSmallIconSize = getContext().getResources().getDimensionPixelSize(R.dimen.slice_icon_size);
        setLayoutParams(new ViewGroup.LayoutParams(mLargeIconSize, mLargeIconSize));
    }

    @Override
    public void setSlice(Slice slice) {
        removeAllViews();
        SliceItem sliceItem = SliceQuery.find(slice, SliceItem.TYPE_ACTION);
        SliceItem iconItem = slice.getPrimaryIcon();
        SliceItem textItem = sliceItem != null
                ? SliceQuery.find(sliceItem, SliceItem.TYPE_TEXT)
                : SliceQuery.find(slice, SliceItem.TYPE_TEXT);
        SliceItem colorItem = sliceItem != null
                ? SliceQuery.find(sliceItem, SliceItem.TYPE_COLOR)
                : SliceQuery.find(slice, SliceItem.TYPE_COLOR);
        if (colorItem == null) {
            colorItem = SliceQuery.find(slice, SliceItem.TYPE_COLOR);
        }
        // TODO: pick better default colour
        final int color = colorItem != null ? colorItem.getColor() : Color.GRAY;
        ShapeDrawable circle = new ShapeDrawable(new OvalShape());
        circle.setTint(color);
        setBackground(circle);
        if (iconItem != null) {
            final boolean isLarge = iconItem.hasHint(Slice.HINT_LARGE);
            final int iconSize = isLarge ? mLargeIconSize : mSmallIconSize;
            SliceViewUtil.createCircledIcon(getContext(), color, iconSize, iconItem.getIcon(),
                    isLarge, this /* parent */);
            mAction = sliceItem != null ? sliceItem.getAction()
                    : null;
            mUri = slice.getUri();
            setClickable(true);
        } else {
            setClickable(false);
        }
    }

    @Override
    public String getMode() {
        return SliceView.MODE_SHORTCUT;
    }

    @Override
    public boolean performClick() {
        if (!callOnClick()) {
            try {
                if (mAction != null) {
                    mAction.send();
                } else {
                    Intent intent = new Intent(Intent.ACTION_VIEW).setData(mUri);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(intent);
                }
            } catch (CanceledException e) {
                e.printStackTrace();
            }
        }
        return true;
    }
}
