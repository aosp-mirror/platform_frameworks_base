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
 * limitations under the License
 */

package com.android.keyguard;

import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.annotation.ColorInt;
import android.annotation.StyleRes;
import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.text.LineBreaker;
import android.net.Uri;
import android.os.Trace;
import android.text.TextUtils;
import android.text.TextUtils.TruncateAt;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.Animation;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.slice.SliceItem;
import androidx.slice.core.SliceQuery;
import androidx.slice.widget.RowContent;
import androidx.slice.widget.SliceContent;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.ColorUtils;
import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.util.wakelock.KeepAwakeAnimationListener;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * View visible under the clock on the lock screen and AoD.
 */
public class KeyguardSliceView extends LinearLayout {

    private static final String TAG = "KeyguardSliceView";
    public static final int DEFAULT_ANIM_DURATION = 550;

    private final LayoutTransition mLayoutTransition;
    @VisibleForTesting
    TextView mTitle;
    private Row mRow;
    private int mTextColor;
    private float mDarkAmount = 0;

    private int mIconSize;
    private int mIconSizeWithHeader;
    /**
     * Runnable called whenever the view contents change.
     */
    private Runnable mContentChangeListener;
    private boolean mHasHeader;
    private View.OnClickListener mOnClickListener;

    private int mLockScreenMode = KeyguardUpdateMonitor.LOCK_SCREEN_MODE_NORMAL;

    public KeyguardSliceView(Context context, AttributeSet attrs) {
        super(context, attrs);

        Resources resources = context.getResources();
        mLayoutTransition = new LayoutTransition();
        mLayoutTransition.setStagger(LayoutTransition.CHANGE_APPEARING, DEFAULT_ANIM_DURATION / 2);
        mLayoutTransition.setDuration(LayoutTransition.APPEARING, DEFAULT_ANIM_DURATION);
        mLayoutTransition.setDuration(LayoutTransition.DISAPPEARING, DEFAULT_ANIM_DURATION / 2);
        mLayoutTransition.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
        mLayoutTransition.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
        mLayoutTransition.setInterpolator(LayoutTransition.APPEARING,
                Interpolators.FAST_OUT_SLOW_IN);
        mLayoutTransition.setInterpolator(LayoutTransition.DISAPPEARING, Interpolators.ALPHA_OUT);
        mLayoutTransition.setAnimateParentHierarchy(false);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mTitle = findViewById(R.id.title);
        mRow = findViewById(R.id.row);
        mTextColor = Utils.getColorAttrDefaultColor(mContext, R.attr.wallpaperTextColor);
        mIconSize = (int) mContext.getResources().getDimension(R.dimen.widget_icon_size);
        mIconSizeWithHeader = (int) mContext.getResources().getDimension(R.dimen.header_icon_size);
        mTitle.setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED);
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        setLayoutTransition(isVisible ? mLayoutTransition : null);
    }

    /**
     * Returns whether the current visible slice has a title/header.
     */
    public boolean hasHeader() {
        return mHasHeader;
    }

    void hideSlice() {
        mTitle.setVisibility(GONE);
        mRow.setVisibility(GONE);
        mHasHeader = false;
        if (mContentChangeListener != null) {
            mContentChangeListener.run();
        }
    }

    /**
     * Updates the lockscreen mode which may change the layout of the keyguard slice view.
     */
    public void updateLockScreenMode(int mode) {
        mLockScreenMode = mode;
        if (mLockScreenMode == KeyguardUpdateMonitor.LOCK_SCREEN_MODE_LAYOUT_1) {
            mTitle.setPaddingRelative(0, 0, 0, 0);
            mTitle.setGravity(Gravity.START);
            setGravity(Gravity.START);
            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) getLayoutParams();
            lp.removeRule(RelativeLayout.CENTER_HORIZONTAL);
            setLayoutParams(lp);
        } else {
            final int horizontalPaddingDpValue = (int) TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    44,
                    getResources().getDisplayMetrics()
            );
            mTitle.setPaddingRelative(horizontalPaddingDpValue, 0, horizontalPaddingDpValue, 0);
            mTitle.setGravity(Gravity.CENTER_HORIZONTAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) getLayoutParams();
            lp.addRule(RelativeLayout.CENTER_HORIZONTAL);
            setLayoutParams(lp);
        }
        mRow.setLockscreenMode(mode);
        requestLayout();
    }

    Map<View, PendingIntent> showSlice(RowContent header, List<SliceContent> subItems) {
        Trace.beginSection("KeyguardSliceView#showSlice");
        mHasHeader = header != null;
        Map<View, PendingIntent> clickActions = new HashMap<>();

        if (!mHasHeader) {
            mTitle.setVisibility(GONE);
        } else {
            mTitle.setVisibility(VISIBLE);

            SliceItem mainTitle = header.getTitleItem();
            CharSequence title = mainTitle != null ? mainTitle.getText() : null;
            mTitle.setText(title);
            if (header.getPrimaryAction() != null
                    && header.getPrimaryAction().getAction() != null) {
                clickActions.put(mTitle, header.getPrimaryAction().getAction());
            }
        }

        final int subItemsCount = subItems.size();
        final int blendedColor = getTextColor();
        final int startIndex = mHasHeader ? 1 : 0; // First item is header; skip it
        mRow.setVisibility(subItemsCount > 0 ? VISIBLE : GONE);
        LinearLayout.LayoutParams layoutParams = (LayoutParams) mRow.getLayoutParams();
        layoutParams.gravity = mLockScreenMode !=  KeyguardUpdateMonitor.LOCK_SCREEN_MODE_NORMAL
                ? Gravity.START :  Gravity.CENTER;
        mRow.setLayoutParams(layoutParams);

        for (int i = startIndex; i < subItemsCount; i++) {
            RowContent rc = (RowContent) subItems.get(i);
            SliceItem item = rc.getSliceItem();
            final Uri itemTag = item.getSlice().getUri();
            // Try to reuse the view if already exists in the layout
            KeyguardSliceTextView button = mRow.findViewWithTag(itemTag);
            if (button == null) {
                button = new KeyguardSliceTextView(mContext);
                button.setTextColor(blendedColor);
                button.setTag(itemTag);
                final int viewIndex = i - (mHasHeader ? 1 : 0);
                mRow.addView(button, viewIndex);
            }

            PendingIntent pendingIntent = null;
            if (rc.getPrimaryAction() != null) {
                pendingIntent = rc.getPrimaryAction().getAction();
            }
            clickActions.put(button, pendingIntent);

            final SliceItem titleItem = rc.getTitleItem();
            button.setText(titleItem == null ? null : titleItem.getText());
            button.setContentDescription(rc.getContentDescription());

            Drawable iconDrawable = null;
            SliceItem icon = SliceQuery.find(item.getSlice(),
                    android.app.slice.SliceItem.FORMAT_IMAGE);
            if (icon != null) {
                final int iconSize = mHasHeader ? mIconSizeWithHeader : mIconSize;
                iconDrawable = icon.getIcon().loadDrawable(mContext);
                if (iconDrawable != null) {
                    if ((iconDrawable instanceof InsetDrawable)
                            && mLockScreenMode == KeyguardUpdateMonitor.LOCK_SCREEN_MODE_LAYOUT_1) {
                        // System icons (DnD) use insets which are fine for centered slice content
                        // but will cause a slight indent for left/right-aligned slice views
                        iconDrawable = ((InsetDrawable) iconDrawable).getDrawable();
                    }
                    final int width = (int) (iconDrawable.getIntrinsicWidth()
                            / (float) iconDrawable.getIntrinsicHeight() * iconSize);
                    iconDrawable.setBounds(0, 0, Math.max(width, 1), iconSize);
                }
            }
            button.setCompoundDrawablesRelative(iconDrawable, null, null, null);
            button.setOnClickListener(mOnClickListener);
            button.setClickable(pendingIntent != null);
        }

        // Removing old views
        for (int i = 0; i < mRow.getChildCount(); i++) {
            View child = mRow.getChildAt(i);
            if (!clickActions.containsKey(child)) {
                mRow.removeView(child);
                i--;
            }
        }

        if (mContentChangeListener != null) {
            mContentChangeListener.run();
        }
        Trace.endSection();

        return clickActions;
    }

    public void setDarkAmount(float darkAmount) {
        mDarkAmount = darkAmount;
        mRow.setDarkAmount(darkAmount);
        updateTextColors();
    }

    private void updateTextColors() {
        final int blendedColor = getTextColor();
        mTitle.setTextColor(blendedColor);
        int childCount = mRow.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View v = mRow.getChildAt(i);
            if (v instanceof TextView) {
                ((TextView) v).setTextColor(blendedColor);
            }
        }
    }

    /**
     * Runnable that gets invoked every time the title or the row visibility changes.
     * @param contentChangeListener The listener.
     */
    public void setContentChangeListener(Runnable contentChangeListener) {
        mContentChangeListener = contentChangeListener;
    }

    @VisibleForTesting
    int getTextColor() {
        return ColorUtils.blendARGB(mTextColor, Color.WHITE, mDarkAmount);
    }

    @VisibleForTesting
    void setTextColor(@ColorInt int textColor) {
        mTextColor = textColor;
        updateTextColors();
    }

    void onDensityOrFontScaleChanged() {
        mIconSize = mContext.getResources().getDimensionPixelSize(R.dimen.widget_icon_size);
        mIconSizeWithHeader = (int) mContext.getResources().getDimension(R.dimen.header_icon_size);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("KeyguardSliceView:");
        pw.println("  mTitle: " + (mTitle == null ? "null" : mTitle.getVisibility() == VISIBLE));
        pw.println("  mRow: " + (mRow == null ? "null" : mRow.getVisibility() == VISIBLE));
        pw.println("  mTextColor: " + Integer.toHexString(mTextColor));
        pw.println("  mDarkAmount: " + mDarkAmount);
        pw.println("  mHasHeader: " + mHasHeader);
        pw.println("  mLockScreenMode: " + mLockScreenMode);
    }

    @Override
    public void setOnClickListener(View.OnClickListener onClickListener) {
        mOnClickListener = onClickListener;
        mTitle.setOnClickListener(onClickListener);
    }

    public static class Row extends LinearLayout {
        private Set<KeyguardSliceTextView> mKeyguardSliceTextViewSet = new HashSet();
        private int mLockScreenModeRow = KeyguardUpdateMonitor.LOCK_SCREEN_MODE_NORMAL;

        /**
         * This view is visible in AOD, which means that the device will sleep if we
         * don't hold a wake lock. We want to enter doze only after all views have reached
         * their desired positions.
         */
        private final Animation.AnimationListener mKeepAwakeListener;
        private LayoutTransition mLayoutTransition;
        private float mDarkAmount;

        public Row(Context context) {
            this(context, null);
        }

        public Row(Context context, AttributeSet attrs) {
            this(context, attrs, 0);
        }

        public Row(Context context, AttributeSet attrs, int defStyleAttr) {
            this(context, attrs, defStyleAttr, 0);
        }

        public Row(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
            super(context, attrs, defStyleAttr, defStyleRes);
            mKeepAwakeListener = new KeepAwakeAnimationListener(mContext);
        }

        @Override
        protected void onFinishInflate() {
            mLayoutTransition = new LayoutTransition();
            mLayoutTransition.setDuration(DEFAULT_ANIM_DURATION);

            PropertyValuesHolder left = PropertyValuesHolder.ofInt("left", 0, 1);
            PropertyValuesHolder right = PropertyValuesHolder.ofInt("right", 0, 1);
            ObjectAnimator changeAnimator = ObjectAnimator.ofPropertyValuesHolder((Object) null,
                    left, right);
            mLayoutTransition.setAnimator(LayoutTransition.CHANGE_APPEARING, changeAnimator);
            mLayoutTransition.setAnimator(LayoutTransition.CHANGE_DISAPPEARING, changeAnimator);
            mLayoutTransition.setInterpolator(LayoutTransition.CHANGE_APPEARING,
                    Interpolators.ACCELERATE_DECELERATE);
            mLayoutTransition.setInterpolator(LayoutTransition.CHANGE_DISAPPEARING,
                    Interpolators.ACCELERATE_DECELERATE);
            mLayoutTransition.setStartDelay(LayoutTransition.CHANGE_APPEARING,
                    DEFAULT_ANIM_DURATION);
            mLayoutTransition.setStartDelay(LayoutTransition.CHANGE_DISAPPEARING,
                    DEFAULT_ANIM_DURATION);

            ObjectAnimator appearAnimator = ObjectAnimator.ofFloat(null, "alpha", 0f, 1f);
            mLayoutTransition.setAnimator(LayoutTransition.APPEARING, appearAnimator);
            mLayoutTransition.setInterpolator(LayoutTransition.APPEARING, Interpolators.ALPHA_IN);

            ObjectAnimator disappearAnimator = ObjectAnimator.ofFloat(null, "alpha", 1f, 0f);
            mLayoutTransition.setInterpolator(LayoutTransition.DISAPPEARING,
                    Interpolators.ALPHA_OUT);
            mLayoutTransition.setDuration(LayoutTransition.DISAPPEARING, DEFAULT_ANIM_DURATION / 4);
            mLayoutTransition.setAnimator(LayoutTransition.DISAPPEARING, disappearAnimator);

            mLayoutTransition.setAnimateParentHierarchy(false);
        }

        @Override
        public void onVisibilityAggregated(boolean isVisible) {
            super.onVisibilityAggregated(isVisible);
            setLayoutTransition(isVisible ? mLayoutTransition : null);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int childCount = getChildCount();

            for (int i = 0; i < childCount; i++) {
                View child = getChildAt(i);
                if (child instanceof KeyguardSliceTextView) {
                    if (mLockScreenModeRow == KeyguardUpdateMonitor.LOCK_SCREEN_MODE_LAYOUT_1) {
                        ((KeyguardSliceTextView) child).setMaxWidth(Integer.MAX_VALUE);
                    } else {
                        ((KeyguardSliceTextView) child).setMaxWidth(width / 3);
                    }
                }
            }

            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }

        /**
         * Set the amount (ratio) that the device has transitioned to doze.
         *
         * @param darkAmount Amount of transition to doze: 1f for doze and 0f for awake.
         */
        public void setDarkAmount(float darkAmount) {
            boolean isDozing = darkAmount != 0;
            boolean wasDozing = mDarkAmount != 0;
            if (isDozing == wasDozing) {
                return;
            }
            mDarkAmount = darkAmount;
            setLayoutAnimationListener(isDozing ? null : mKeepAwakeListener);
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }

        @Override
        public void addView(View view, int index) {
            super.addView(view, index);

            if (view instanceof KeyguardSliceTextView) {
                ((KeyguardSliceTextView) view).setLockScreenMode(mLockScreenModeRow);
                mKeyguardSliceTextViewSet.add((KeyguardSliceTextView) view);
            }
        }

        @Override
        public void removeView(View view) {
            super.removeView(view);
            if (view instanceof KeyguardSliceTextView) {
                mKeyguardSliceTextViewSet.remove((KeyguardSliceTextView) view);
            }
        }

        /**
         * Updates the lockscreen mode which may change the layout of this view.
         */
        public void setLockscreenMode(int mode) {
            mLockScreenModeRow = mode;
            if (mLockScreenModeRow == KeyguardUpdateMonitor.LOCK_SCREEN_MODE_LAYOUT_1) {
                setOrientation(LinearLayout.VERTICAL);
                setGravity(Gravity.START);
            } else {
                setOrientation(LinearLayout.HORIZONTAL);
                setGravity(Gravity.CENTER);
            }

            for (KeyguardSliceTextView textView : mKeyguardSliceTextViewSet) {
                textView.setLockScreenMode(mLockScreenModeRow);
            }
        }
    }

    /**
     * Representation of an item that appears under the clock on main keyguard message.
     */
    @VisibleForTesting
    static class KeyguardSliceTextView extends TextView implements
            ConfigurationController.ConfigurationListener {
        private int mLockScreenMode = KeyguardUpdateMonitor.LOCK_SCREEN_MODE_NORMAL;

        @StyleRes
        private static int sStyleId = R.style.TextAppearance_Keyguard_Secondary;

        KeyguardSliceTextView(Context context) {
            super(context, null /* attrs */, 0 /* styleAttr */, sStyleId);
            onDensityOrFontScaleChanged();
            setEllipsize(TruncateAt.END);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            Dependency.get(ConfigurationController.class).addCallback(this);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            Dependency.get(ConfigurationController.class).removeCallback(this);
        }

        @Override
        public void onDensityOrFontScaleChanged() {
            updatePadding();
        }

        @Override
        public void onOverlayChanged() {
            setTextAppearance(sStyleId);
        }

        @Override
        public void setText(CharSequence text, BufferType type) {
            super.setText(text, type);
            updatePadding();
        }

        private void updatePadding() {
            boolean hasText = !TextUtils.isEmpty(getText());
            int padding = (int) getContext().getResources()
                    .getDimension(R.dimen.widget_horizontal_padding) / 2;
            if (mLockScreenMode == KeyguardUpdateMonitor.LOCK_SCREEN_MODE_LAYOUT_1) {
                // orientation is vertical, so add padding to top & bottom
                setPadding(0, padding, 0, hasText ? padding : 0);
            } else {
                // orientation is horizontal, so add padding to left & right
                setPadding(padding, 0, padding * (hasText ? 1 : -1), 0);
            }

            setCompoundDrawablePadding((int) mContext.getResources()
                    .getDimension(R.dimen.widget_icon_padding));
        }

        @Override
        public void setTextColor(int color) {
            super.setTextColor(color);
            updateDrawableColors();
        }

        @Override
        public void setCompoundDrawablesRelative(Drawable start, Drawable top, Drawable end,
                Drawable bottom) {
            super.setCompoundDrawablesRelative(start, top, end, bottom);
            updateDrawableColors();
            updatePadding();
        }

        private void updateDrawableColors() {
            final int color = getCurrentTextColor();
            for (Drawable drawable : getCompoundDrawables()) {
                if (drawable != null) {
                    drawable.setTint(color);
                }
            }
        }

        /**
         * Updates the lockscreen mode which may change the layout of this view.
         */
        public void setLockScreenMode(int mode) {
            mLockScreenMode = mode;
            if (mLockScreenMode == KeyguardUpdateMonitor.LOCK_SCREEN_MODE_LAYOUT_1) {
                setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            } else {
                setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            }
            updatePadding();
        }
    }
}
