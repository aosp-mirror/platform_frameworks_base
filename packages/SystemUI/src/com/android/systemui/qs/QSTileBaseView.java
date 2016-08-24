/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.systemui.qs;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.Switch;

import com.android.systemui.R;

public class QSTileBaseView extends LinearLayout {

    private final H mHandler = new H();
    private QSIconView mIcon;
    private RippleDrawable mRipple;
    private Drawable mTileBackground;
    private String mAccessibilityClass;
    private boolean mTileState;
    private boolean mCollapsedView;

    public QSTileBaseView(Context context, QSIconView icon) {
        this(context, icon, false);
    }

    public QSTileBaseView(Context context, QSIconView icon, boolean collapsedView) {
        super(context);
        mIcon = icon;
        addView(mIcon);

        mTileBackground = newTileBackground();
        if (mTileBackground instanceof RippleDrawable) {
            setRipple((RippleDrawable) mTileBackground);
        }
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        setBackground(mTileBackground);

        // Default to Quick Tile padding, and QSTileView will specify its own padding.
        int padding = context.getResources().getDimensionPixelSize(R.dimen.qs_quick_tile_padding);
        setPadding(0, padding, 0, padding);
        setClipChildren(false);
        setClipToPadding(false);
        mCollapsedView = collapsedView;
    }

    private Drawable newTileBackground() {
        final int[] attrs = new int[] { android.R.attr.selectableItemBackgroundBorderless };
        final TypedArray ta = mContext.obtainStyledAttributes(attrs);
        final Drawable d = ta.getDrawable(0);
        ta.recycle();
        return d;
    }

    private void setRipple(RippleDrawable tileBackground) {
        mRipple = tileBackground;
        if (getWidth() != 0) {
            updateRippleSize(getWidth(), getHeight());
        }
    }

    private void updateRippleSize(int width, int height) {
        // center the touch feedback on the center of the icon, and dial it down a bit
        final int cx = width / 2;
        final int cy = height / 2;
        final int rad = (int)(mIcon.getHeight() * .85f);
        mRipple.setHotspotBounds(cx - rad, cy - rad, cx + rad, cy + rad);
    }

    public void init(OnClickListener click, OnLongClickListener longClick) {
        setClickable(true);
        setOnClickListener(click);
        setOnLongClickListener(longClick);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        final int w = getMeasuredWidth();
        final int h = getMeasuredHeight();

        if (mRipple != null) {
            updateRippleSize(w, h);
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        // Avoid layers for this layout - we don't need them.
        return false;
    }

    /**
     * Update the accessibility order for this view.
     *
     * @param previousView the view which should be before this one
     * @return the last view in this view which is accessible
     */
    public View updateAccessibilityOrder(View previousView) {
        setAccessibilityTraversalAfter(previousView.getId());
        return this;
    }

    public void onStateChanged(QSTile.State state) {
        mHandler.obtainMessage(H.STATE_CHANGED, state).sendToTarget();
    }

    protected void handleStateChanged(QSTile.State state) {
        mIcon.setIcon(state);
        if (mCollapsedView && !TextUtils.isEmpty(state.minimalContentDescription)) {
            setContentDescription(state.minimalContentDescription);
        } else {
            setContentDescription(state.contentDescription);
        }
        if (mCollapsedView) {
            mAccessibilityClass = state.minimalAccessibilityClassName;
        } else {
            mAccessibilityClass = state.expandedAccessibilityClassName;
        }
        if (state instanceof QSTile.BooleanState) {
            mTileState = ((QSTile.BooleanState) state).value;
        }
    }

    public QSIconView getIcon() {
        return mIcon;
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        if (!TextUtils.isEmpty(mAccessibilityClass)) {
            event.setClassName(mAccessibilityClass);
            if (Switch.class.getName().equals(mAccessibilityClass)) {
                String label = getResources()
                        .getString(!mTileState ? R.string.switch_bar_on : R.string.switch_bar_off);
                event.setContentDescription(label);
                event.setChecked(!mTileState);
            }
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (!TextUtils.isEmpty(mAccessibilityClass)) {
            info.setClassName(mAccessibilityClass);
            if (Switch.class.getName().equals(mAccessibilityClass)) {
                String label = getResources()
                        .getString(mTileState ? R.string.switch_bar_on : R.string.switch_bar_off);
                info.setText(label);
                info.setChecked(mTileState);
                info.setCheckable(true);
            }
        }
    }

    private class H extends Handler {
        private static final int STATE_CHANGED = 1;
        public H() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == STATE_CHANGED) {
                handleStateChanged((QSTile.State) msg.obj);
            }
        }
    }
}
