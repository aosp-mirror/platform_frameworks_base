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

import static android.app.slice.Slice.HINT_LIST_ITEM;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;

import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.annotation.ColorInt;
import android.annotation.StyleRes;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Trace;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.TextUtils.TruncateAt;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.slice.Slice;
import androidx.slice.SliceItem;
import androidx.slice.SliceViewManager;
import androidx.slice.core.SliceQuery;
import androidx.slice.widget.ListContent;
import androidx.slice.widget.RowContent;
import androidx.slice.widget.SliceContent;
import androidx.slice.widget.SliceLiveData;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.ColorUtils;
import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.keyguard.KeyguardSliceProvider;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.wakelock.KeepAwakeAnimationListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * View visible under the clock on the lock screen and AoD.
 */
public class KeyguardSliceView extends LinearLayout implements View.OnClickListener,
        Observer<Slice>, TunerService.Tunable, ConfigurationController.ConfigurationListener {

    private static final String TAG = "KeyguardSliceView";
    public static final int DEFAULT_ANIM_DURATION = 550;

    private final HashMap<View, PendingIntent> mClickActions;
    private Uri mKeyguardSliceUri;
    private Row mRow;
    private int mTextColor;
    private float mDarkAmount = 0;

    private LiveData<Slice> mLiveData;
    private int mDisplayId = INVALID_DISPLAY;
    private int mIconSize;
    /**
     * Runnable called whenever the view contents change.
     */
    private Runnable mContentChangeListener;
    private Slice mSlice;
    private boolean mHasHeader;

    public KeyguardSliceView(Context context) {
        this(context, null, 0);
    }

    public KeyguardSliceView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardSliceView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        TunerService tunerService = Dependency.get(TunerService.class);
        tunerService.addTunable(this, Settings.Secure.KEYGUARD_SLICE_URI);

        mClickActions = new HashMap<>();

        LayoutTransition transition = new LayoutTransition();
        transition.setStagger(LayoutTransition.CHANGE_APPEARING, DEFAULT_ANIM_DURATION / 2);
        transition.setDuration(LayoutTransition.APPEARING, DEFAULT_ANIM_DURATION);
        transition.setDuration(LayoutTransition.DISAPPEARING, DEFAULT_ANIM_DURATION / 2);
        transition.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
        transition.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
        transition.setInterpolator(LayoutTransition.APPEARING, Interpolators.FAST_OUT_SLOW_IN);
        transition.setInterpolator(LayoutTransition.DISAPPEARING, Interpolators.ALPHA_OUT);
        transition.setAnimateParentHierarchy(false);
        transition.addTransitionListener(new SliceViewTransitionListener());
        setLayoutTransition(transition);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mRow = findViewById(R.id.row);
        mTextColor = Utils.getColorAttrDefaultColor(mContext, R.attr.wallpaperTextColor);
        mIconSize = (int) mContext.getResources().getDimension(R.dimen.widget_icon_size);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        mDisplayId = getDisplay().getDisplayId();
        // Make sure we always have the most current slice
        mLiveData.observeForever(this);
        Dependency.get(ConfigurationController.class).addCallback(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        // TODO(b/117344873) Remove below work around after this issue be fixed.
        if (mDisplayId == DEFAULT_DISPLAY) {
            mLiveData.removeObserver(this);
        }
        Dependency.get(ConfigurationController.class).removeCallback(this);
    }

    /**
     * Returns whether the current visible slice has a title/header.
     */
    public boolean hasHeader() {
        return mHasHeader;
    }

    private void showSlice() {
        Trace.beginSection("KeyguardSliceView#showSlice");
        if (mSlice == null) {
            mRow.setVisibility(GONE);
            mHasHeader = false;
            if (mContentChangeListener != null) {
                mContentChangeListener.run();
            }
            return;
        }

        ListContent lc = new ListContent(getContext(), mSlice);
        SliceContent headerContent = lc.getHeader();
        mHasHeader = headerContent != null
                && !headerContent.getSliceItem().hasHint(HINT_LIST_ITEM);
        List<SliceContent> subItems = new ArrayList<>();
        for (int i = 0; i < lc.getRowItems().size(); i++) {
            SliceContent subItem = lc.getRowItems().get(i);
            String itemUri = subItem.getSliceItem().getSlice().getUri().toString();
            // Filter out the action row
            if (!KeyguardSliceProvider.KEYGUARD_ACTION_URI.equals(itemUri)) {
                subItems.add(subItem);
            }
        }

        mClickActions.clear();
        final int subItemsCount = subItems.size();
        final int blendedColor = getTextColor();
        final int startIndex = mHasHeader ? 1 : 0; // First item is header; skip it
        mRow.setVisibility(subItemsCount > 0 ? VISIBLE : GONE);
        for (int i = startIndex; i < subItemsCount; i++) {
            RowContent rc = (RowContent) subItems.get(i);
            SliceItem item = rc.getSliceItem();
            final Uri itemTag = item.getSlice().getUri();
            // Try to reuse the view if already exists in the layout
            KeyguardSliceButton button = mRow.findViewWithTag(itemTag);
            if (button == null) {
                button = new KeyguardSliceButton(mContext);
                button.setTextColor(blendedColor);
                button.setTag(itemTag);
                final int viewIndex = i - (mHasHeader ? 1 : 0);
                mRow.addView(button, viewIndex);
            }

            PendingIntent pendingIntent = null;
            if (rc.getPrimaryAction() != null) {
                pendingIntent = rc.getPrimaryAction().getAction();
            }
            mClickActions.put(button, pendingIntent);

            final SliceItem titleItem = rc.getTitleItem();
            button.setText(titleItem == null ? null : titleItem.getText());
            button.setContentDescription(rc.getContentDescription());

            Drawable iconDrawable = null;
            SliceItem icon = SliceQuery.find(item.getSlice(),
                    android.app.slice.SliceItem.FORMAT_IMAGE);
            if (icon != null) {
                iconDrawable = icon.getIcon().loadDrawable(mContext);
                if (iconDrawable != null) {
                    final int width = (int) (iconDrawable.getIntrinsicWidth()
                            / (float) iconDrawable.getIntrinsicHeight() * mIconSize);
                    iconDrawable.setBounds(0, 0, Math.max(width, 1), mIconSize);
                }
            }
            button.setCompoundDrawables(iconDrawable, null, null, null);
            button.setOnClickListener(this);
            button.setClickable(pendingIntent != null);
        }

        // Removing old views
        for (int i = 0; i < mRow.getChildCount(); i++) {
            View child = mRow.getChildAt(i);
            if (!mClickActions.containsKey(child)) {
                mRow.removeView(child);
                i--;
            }
        }

        if (mContentChangeListener != null) {
            mContentChangeListener.run();
        }
        Trace.endSection();
    }

    public void setDarkAmount(float darkAmount) {
        mDarkAmount = darkAmount;
        mRow.setDarkAmount(darkAmount);
        updateTextColors();
    }

    private void updateTextColors() {
        final int blendedColor = getTextColor();
        int childCount = mRow.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View v = mRow.getChildAt(i);
            if (v instanceof Button) {
                ((Button) v).setTextColor(blendedColor);
            }
        }
    }

    @Override
    public void onClick(View v) {
        final PendingIntent action = mClickActions.get(v);
        if (action != null) {
            try {
                action.send();
            } catch (PendingIntent.CanceledException e) {
                Log.i(TAG, "Pending intent cancelled, nothing to launch", e);
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

    /**
     * LiveData observer lifecycle.
     * @param slice the new slice content.
     */
    @Override
    public void onChanged(Slice slice) {
        mSlice = slice;
        showSlice();
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        setupUri(newValue);
    }

    private void setupUri(String uriString) {
        if (uriString == null) {
            uriString = KeyguardSliceProvider.KEYGUARD_SLICE_URI;
        }

        boolean wasObserving = false;
        if (mLiveData != null && mLiveData.hasActiveObservers()) {
            wasObserving = true;
            mLiveData.removeObserver(this);
        }

        mKeyguardSliceUri = Uri.parse(uriString);
        mLiveData = SliceLiveData.fromUri(mContext, mKeyguardSliceUri);

        if (wasObserving) {
            mLiveData.observeForever(this);
        }
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

    @Override
    public void onDensityOrFontScaleChanged() {
        mIconSize = mContext.getResources().getDimensionPixelSize(R.dimen.widget_icon_size);
    }

    public void refresh() {
        Slice slice;
        Trace.beginSection("KeyguardSliceView#refresh");
        // We can optimize performance and avoid binder calls when we know that we're bound
        // to a Slice on the same process.
        if (KeyguardSliceProvider.KEYGUARD_SLICE_URI.equals(mKeyguardSliceUri.toString())) {
            KeyguardSliceProvider instance = KeyguardSliceProvider.getAttachedInstance();
            if (instance != null) {
                slice = instance.onBindSlice(mKeyguardSliceUri);
            } else {
                Log.w(TAG, "Keyguard slice not bound yet?");
                slice = null;
            }
        } else {
            slice = SliceViewManager.getInstance(getContext()).bindSlice(mKeyguardSliceUri);
        }
        onChanged(slice);
        Trace.endSection();
    }

    public static class Row extends LinearLayout {

        /**
         * This view is visible in AOD, which means that the device will sleep if we
         * don't hold a wake lock. We want to enter doze only after all views have reached
         * their desired positions.
         */
        private final Animation.AnimationListener mKeepAwakeListener;
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
            LayoutTransition transition = new LayoutTransition();
            transition.setDuration(DEFAULT_ANIM_DURATION);

            PropertyValuesHolder left = PropertyValuesHolder.ofInt("left", 0, 1);
            PropertyValuesHolder right = PropertyValuesHolder.ofInt("right", 0, 1);
            ObjectAnimator changeAnimator = ObjectAnimator.ofPropertyValuesHolder((Object) null,
                    left, right);
            transition.setAnimator(LayoutTransition.CHANGE_APPEARING, changeAnimator);
            transition.setAnimator(LayoutTransition.CHANGE_DISAPPEARING, changeAnimator);
            transition.setInterpolator(LayoutTransition.CHANGE_APPEARING,
                    Interpolators.ACCELERATE_DECELERATE);
            transition.setInterpolator(LayoutTransition.CHANGE_DISAPPEARING,
                    Interpolators.ACCELERATE_DECELERATE);
            transition.setStartDelay(LayoutTransition.CHANGE_APPEARING, DEFAULT_ANIM_DURATION);
            transition.setStartDelay(LayoutTransition.CHANGE_DISAPPEARING, DEFAULT_ANIM_DURATION);

            ObjectAnimator appearAnimator = ObjectAnimator.ofFloat(null, "alpha", 0f, 1f);
            transition.setAnimator(LayoutTransition.APPEARING, appearAnimator);
            transition.setInterpolator(LayoutTransition.APPEARING, Interpolators.ALPHA_IN);

            ObjectAnimator disappearAnimator = ObjectAnimator.ofFloat(null, "alpha", 1f, 0f);
            transition.setInterpolator(LayoutTransition.DISAPPEARING, Interpolators.ALPHA_OUT);
            transition.setDuration(LayoutTransition.DISAPPEARING, DEFAULT_ANIM_DURATION / 4);
            transition.setAnimator(LayoutTransition.DISAPPEARING, disappearAnimator);

            transition.setAnimateParentHierarchy(false);
            setLayoutTransition(transition);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = getChildAt(i);
                if (child instanceof KeyguardSliceButton) {
                    ((KeyguardSliceButton) child).setMaxWidth(width / childCount);
                }
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }

        public void setDarkAmount(float darkAmount) {
            boolean isAwake = darkAmount != 0;
            boolean wasAwake = mDarkAmount != 0;
            if (isAwake == wasAwake) {
                return;
            }
            mDarkAmount = darkAmount;
            setLayoutAnimationListener(isAwake ? null : mKeepAwakeListener);
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }
    }

    /**
     * Representation of an item that appears under the clock on main keyguard message.
     */
    @VisibleForTesting
    static class KeyguardSliceButton extends Button implements
            ConfigurationController.ConfigurationListener {

        @StyleRes
        private static int sStyleId = R.style.TextAppearance_Keyguard_Secondary;

        public KeyguardSliceButton(Context context) {
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
            int horizontalPadding = (int) getContext().getResources()
                    .getDimension(R.dimen.widget_horizontal_padding) / 2;
            setPadding(horizontalPadding, 0, horizontalPadding * (hasText ? 1 : -1), 0);
            setCompoundDrawablePadding((int) mContext.getResources()
                    .getDimension(R.dimen.widget_icon_padding));
        }

        @Override
        public void setTextColor(int color) {
            super.setTextColor(color);
            updateDrawableColors();
        }

        @Override
        public void setCompoundDrawables(Drawable left, Drawable top, Drawable right,
                Drawable bottom) {
            super.setCompoundDrawables(left, top, right, bottom);
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
    }

    private class SliceViewTransitionListener implements LayoutTransition.TransitionListener {
        @Override
        public void startTransition(LayoutTransition transition, ViewGroup container, View view,
                int transitionType) {
            switch (transitionType) {
                case  LayoutTransition.APPEARING:
                    int translation = getResources().getDimensionPixelSize(
                            R.dimen.pulsing_notification_appear_translation);
                    view.setTranslationY(translation);
                    view.animate()
                            .translationY(0)
                            .setDuration(DEFAULT_ANIM_DURATION)
                            .setInterpolator(Interpolators.ALPHA_IN)
                            .start();
                    break;
            }
        }

        @Override
        public void endTransition(LayoutTransition transition, ViewGroup container, View view,
                int transitionType) {

        }
    }
}
