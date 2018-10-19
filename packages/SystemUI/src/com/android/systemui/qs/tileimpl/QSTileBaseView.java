/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.qs.tileimpl;

import static com.android.systemui.qs.tileimpl.QSIconViewImpl.QS_ANIM_LENGTH;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.service.quicksettings.Tile;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.Switch;

import com.android.settingslib.Utils;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSIconView;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTile.BooleanState;

public class QSTileBaseView extends com.android.systemui.plugins.qs.QSTileView {

    private static final String TAG = "QSTileBaseView";
    private final H mHandler = new H();
    private final FrameLayout mIconFrame;
    protected QSIconView mIcon;
    protected RippleDrawable mRipple;
    private Drawable mTileBackground;
    private String mAccessibilityClass;
    private boolean mTileState;
    private boolean mCollapsedView;
    private boolean mClicked;

    private final ImageView mBg;
    private final int mColorActive;
    private final int mColorInactive;
    private final int mColorDisabled;
    private int mCircleColor;

    public QSTileBaseView(Context context, QSIconView icon) {
        this(context, icon, false);
    }

    public QSTileBaseView(Context context, QSIconView icon, boolean collapsedView) {
        super(context);
        // Default to Quick Tile padding, and QSTileView will specify its own padding.
        int padding = context.getResources().getDimensionPixelSize(R.dimen.qs_quick_tile_padding);

        mIconFrame = new FrameLayout(context);
        mIconFrame.setForegroundGravity(Gravity.CENTER);
        int size = context.getResources().getDimensionPixelSize(R.dimen.qs_quick_tile_size);
        addView(mIconFrame, new LayoutParams(size, size));
        mBg = new ImageView(getContext());
        mBg.setScaleType(ScaleType.FIT_CENTER);
        mBg.setImageResource(R.drawable.ic_qs_circle);
        mIconFrame.addView(mBg);
        mIcon = icon;
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, padding, 0, padding);
        mIconFrame.addView(mIcon, params);
        mIconFrame.setClipChildren(false);
        mIconFrame.setClipToPadding(false);

        mTileBackground = newTileBackground();
        if (mTileBackground instanceof RippleDrawable) {
            setRipple((RippleDrawable) mTileBackground);
        }
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        setBackground(mTileBackground);

        mColorActive = Utils.getColorAttr(context, android.R.attr.colorAccent);
        mColorDisabled = Utils.getDisabled(context,
                Utils.getColorAttr(context, android.R.attr.textColorTertiary));
        mColorInactive = Utils.getColorAttr(context, android.R.attr.textColorSecondary);

        setPadding(0, 0, 0, 0);
        setClipChildren(false);
        setClipToPadding(false);
        mCollapsedView = collapsedView;
        setFocusable(true);
    }

    public View getBgCicle() {
        return mBg;
    }

    protected Drawable newTileBackground() {
        final int[] attrs = new int[]{android.R.attr.selectableItemBackgroundBorderless};
        final TypedArray ta = getContext().obtainStyledAttributes(attrs);
        final Drawable d = ta.getDrawable(0);
        ta.recycle();
        return d;
    }

    private void setRipple(RippleDrawable tileBackground) {
        mRipple = tileBackground;
        if (getWidth() != 0) {
            updateRippleSize();
        }
    }

    private void updateRippleSize() {
        // center the touch feedback on the center of the icon, and dial it down a bit
        final int cx = mIconFrame.getMeasuredWidth() / 2 + mIconFrame.getLeft();
        final int cy = mIconFrame.getMeasuredHeight() / 2 + mIconFrame.getTop();
        final int rad = (int) (mIcon.getHeight() * .85f);
        mRipple.setHotspotBounds(cx - rad, cy - rad, cx + rad, cy + rad);
    }

    @Override
    public void init(QSTile tile) {
        init(v -> tile.click(), v -> tile.secondaryClick(), view -> {
            tile.longClick();
            return true;
        });
    }

    public void init(OnClickListener click, OnClickListener secondaryClick,
            OnLongClickListener longClick) {
        setOnClickListener(click);
        setOnLongClickListener(longClick);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (mRipple != null) {
            updateRippleSize();
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
        int circleColor = getCircleColor(state.state);
        if (circleColor != mCircleColor) {
            if (mBg.isShown() && animationsEnabled()) {
                ValueAnimator animator = ValueAnimator.ofArgb(mCircleColor, circleColor)
                        .setDuration(QS_ANIM_LENGTH);
                animator.addUpdateListener(animation -> mBg.setImageTintList(ColorStateList.valueOf(
                        (Integer) animation.getAnimatedValue())));
                animator.start();
            } else {
                QSIconViewImpl.setTint(mBg, circleColor);
            }
            mCircleColor = circleColor;
        }

        setClickable(state.state != Tile.STATE_UNAVAILABLE);
        mIcon.setIcon(state);
        setContentDescription(state.contentDescription);

        mAccessibilityClass = state.expandedAccessibilityClassName;
        if (state instanceof QSTile.BooleanState) {
            boolean newState = ((BooleanState) state).value;
            if (mTileState != newState) {
                mClicked = false;
                mTileState = newState;
            }
        }
    }

    protected boolean animationsEnabled() {
        return true;
    }

    private int getCircleColor(int state) {
        switch (state) {
            case Tile.STATE_ACTIVE:
                return mColorActive;
            case Tile.STATE_INACTIVE:
            case Tile.STATE_UNAVAILABLE:
                return mColorDisabled;
            default:
                Log.e(TAG, "Invalid state " + state);
                return 0;
        }
    }

    @Override
    public void setClickable(boolean clickable) {
        super.setClickable(clickable);
        setBackground(clickable ? mRipple : null);
    }

    @Override
    public int getDetailY() {
        return getTop() + getHeight() / 2;
    }

    public QSIconView getIcon() {
        return mIcon;
    }

    public View getIconWithBackground() {
        return mIconFrame;
    }

    @Override
    public boolean performClick() {
        mClicked = true;
        return super.performClick();
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        if (!TextUtils.isEmpty(mAccessibilityClass)) {
            event.setClassName(mAccessibilityClass);
            if (Switch.class.getName().equals(mAccessibilityClass)) {
                boolean b = mClicked ? !mTileState : mTileState;
                String label = getResources()
                        .getString(b ? R.string.switch_bar_on : R.string.switch_bar_off);
                event.setContentDescription(label);
                event.setChecked(b);
            }
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        // Clear selected state so it is not announce by talkback.
        info.setSelected(false);
        if (!TextUtils.isEmpty(mAccessibilityClass)) {
            info.setClassName(mAccessibilityClass);
            if (Switch.class.getName().equals(mAccessibilityClass)) {
                boolean b = mClicked ? !mTileState : mTileState;
                String label = getResources()
                        .getString(b ? R.string.switch_bar_on : R.string.switch_bar_off);
                info.setText(label);
                info.setChecked(b);
                info.setCheckable(true);
                info.addAction(
                        new AccessibilityNodeInfo.AccessibilityAction(
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK.getId(),
                                getResources().getString(R.string.accessibility_long_click_tile)));
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
