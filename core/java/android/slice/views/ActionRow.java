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
import android.app.RemoteInput;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.os.AsyncTask;
import android.slice.Slice;
import android.slice.SliceItem;
import android.slice.SliceQuery;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * @hide
 */
public class ActionRow extends FrameLayout {

    private static final int MAX_ACTIONS = 5;
    private final int mSize;
    private final int mIconPadding;
    private final LinearLayout mActionsGroup;
    private final boolean mFullActions;
    private int mColor = Color.BLACK;

    public ActionRow(Context context, boolean fullActions) {
        super(context);
        mFullActions = fullActions;
        mSize = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48,
                context.getResources().getDisplayMetrics());
        mIconPadding = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12,
                context.getResources().getDisplayMetrics());
        mActionsGroup = new LinearLayout(context);
        mActionsGroup.setOrientation(LinearLayout.HORIZONTAL);
        mActionsGroup.setLayoutParams(
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(mActionsGroup);
    }

    private void setColor(int color) {
        mColor = color;
        for (int i = 0; i < mActionsGroup.getChildCount(); i++) {
            View view = mActionsGroup.getChildAt(i);
            SliceItem item = (SliceItem) view.getTag();
            boolean tint = !item.hasHint(Slice.HINT_NO_TINT);
            if (tint) {
                ((ImageView) view).setImageTintList(ColorStateList.valueOf(mColor));
            }
        }
    }

    private ImageView addAction(Icon icon, boolean allowTint, SliceItem image) {
        ImageView imageView = new ImageView(getContext());
        imageView.setPadding(mIconPadding, mIconPadding, mIconPadding, mIconPadding);
        imageView.setScaleType(ScaleType.FIT_CENTER);
        imageView.setImageIcon(icon);
        if (allowTint) {
            imageView.setImageTintList(ColorStateList.valueOf(mColor));
        }
        imageView.setBackground(SliceViewUtil.getDrawable(getContext(),
                android.R.attr.selectableItemBackground));
        imageView.setTag(image);
        addAction(imageView);
        return imageView;
    }

    /**
     * Set the actions and color for this action row.
     */
    public void setActions(SliceItem actionRow, SliceItem defColor) {
        removeAllViews();
        mActionsGroup.removeAllViews();
        addView(mActionsGroup);

        SliceItem color = SliceQuery.find(actionRow, SliceItem.TYPE_COLOR);
        if (color == null) {
            color = defColor;
        }
        if (color != null) {
            setColor(color.getColor());
        }
        SliceQuery.findAll(actionRow, SliceItem.TYPE_ACTION).forEach(action -> {
            if (mActionsGroup.getChildCount() >= MAX_ACTIONS) {
                return;
            }
            SliceItem image = SliceQuery.find(action, SliceItem.TYPE_IMAGE);
            if (image == null) {
                return;
            }
            boolean tint = !image.hasHint(Slice.HINT_NO_TINT);
            SliceItem input = SliceQuery.find(action, SliceItem.TYPE_REMOTE_INPUT);
            if (input != null && input.getRemoteInput().getAllowFreeFormInput()) {
                addAction(image.getIcon(), tint, image).setOnClickListener(
                        v -> handleRemoteInputClick(v, action.getAction(), input.getRemoteInput()));
                createRemoteInputView(mColor, getContext());
            } else {
                addAction(image.getIcon(), tint, image).setOnClickListener(v -> AsyncTask.execute(
                        () -> {
                            try {
                                action.getAction().send();
                            } catch (CanceledException e) {
                                e.printStackTrace();
                            }
                        }));
            }
        });
        setVisibility(getChildCount() != 0 ? View.VISIBLE : View.GONE);
    }

    private void addAction(View child) {
        mActionsGroup.addView(child, new LinearLayout.LayoutParams(mSize, mSize, 1));
    }

    private void createRemoteInputView(int color, Context context) {
        View riv = RemoteInputView.inflate(context, this);
        riv.setVisibility(View.INVISIBLE);
        addView(riv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        riv.setBackgroundColor(color);
    }

    private boolean handleRemoteInputClick(View view, PendingIntent pendingIntent,
            RemoteInput input) {
        if (input == null) {
            return false;
        }

        ViewParent p = view.getParent().getParent();
        RemoteInputView riv = null;
        while (p != null) {
            if (p instanceof View) {
                View pv = (View) p;
                riv = findRemoteInputView(pv);
                if (riv != null) {
                    break;
                }
            }
            p = p.getParent();
        }
        if (riv == null) {
            return false;
        }

        int width = view.getWidth();
        if (view instanceof TextView) {
            // Center the reveal on the text which might be off-center from the TextView
            TextView tv = (TextView) view;
            if (tv.getLayout() != null) {
                int innerWidth = (int) tv.getLayout().getLineWidth(0);
                innerWidth += tv.getCompoundPaddingLeft() + tv.getCompoundPaddingRight();
                width = Math.min(width, innerWidth);
            }
        }
        int cx = view.getLeft() + width / 2;
        int cy = view.getTop() + view.getHeight() / 2;
        int w = riv.getWidth();
        int h = riv.getHeight();
        int r = Math.max(
                Math.max(cx + cy, cx + (h - cy)),
                Math.max((w - cx) + cy, (w - cx) + (h - cy)));

        riv.setRevealParameters(cx, cy, r);
        riv.setPendingIntent(pendingIntent);
        riv.setRemoteInput(new RemoteInput[] {
                input
        }, input);
        riv.focusAnimated();
        return true;
    }

    private RemoteInputView findRemoteInputView(View v) {
        if (v == null) {
            return null;
        }
        return (RemoteInputView) v.findViewWithTag(RemoteInputView.VIEW_TAG);
    }
}
