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
import android.app.PendingIntent;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.text.Layout;
import android.text.TextUtils;
import android.text.TextUtils.TruncateAt;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.ColorUtils;
import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.keyguard.KeyguardSliceProvider;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.wakelock.KeepAwakeAnimationListener;

import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

import androidx.slice.Slice;
import androidx.slice.SliceItem;
import androidx.slice.core.SliceQuery;
import androidx.slice.widget.ListContent;
import androidx.slice.widget.RowContent;
import androidx.slice.widget.SliceLiveData;

/**
 * View visible under the clock on the lock screen and AoD.
 */
public class KeyguardSliceView extends LinearLayout implements View.OnClickListener,
        Observer<Slice>, TunerService.Tunable, ConfigurationController.ConfigurationListener {

    private static final String TAG = "KeyguardSliceView";
    public static final int DEFAULT_ANIM_DURATION = 550;

    private final HashMap<View, PendingIntent> mClickActions;
    private Uri mKeyguardSliceUri;
    @VisibleForTesting
    TextView mTitle;
    private Row mRow;
    private int mTextColor;
    private float mDarkAmount = 0;

    private LiveData<Slice> mLiveData;
    private int mIconSize;
    /**
     * Listener called whenever the view contents change.
     * Boolean will be true when the change happens animated.
     */
    private Consumer<Boolean> mContentChangeListener;
    private boolean mHasHeader;
    private Slice mSlice;
    private boolean mPulsing;

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
        mTitle = findViewById(R.id.title);
        mRow = findViewById(R.id.row);
        mTextColor = Utils.getColorAttrDefaultColor(mContext, R.attr.wallpaperTextColor);
        mIconSize = (int) mContext.getResources().getDimension(R.dimen.widget_icon_size);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        // Make sure we always have the most current slice
        mLiveData.observeForever(this);
        Dependency.get(ConfigurationController.class).addCallback(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        mLiveData.removeObserver(this);
        Dependency.get(ConfigurationController.class).removeCallback(this);
    }

    private void showSlice() {
        if (mPulsing || mSlice == null) {
            mTitle.setVisibility(GONE);
            mRow.setVisibility(GONE);
            mContentChangeListener.accept(getLayoutTransition() != null);
            return;
        }

        ListContent lc = new ListContent(getContext(), mSlice);
        mHasHeader = lc.hasHeader();
        List<SliceItem> subItems = lc.getRowItems();
        if (!mHasHeader) {
            mTitle.setVisibility(GONE);
        } else {
            mTitle.setVisibility(VISIBLE);

            // If there's a header it'll be the first subitem
            RowContent header = new RowContent(getContext(), subItems.get(0),
                    true /* showStartItem */);
            SliceItem mainTitle = header.getTitleItem();
            CharSequence title = mainTitle != null ? mainTitle.getText() : null;
            mTitle.setText(title);

            // Check if we're already ellipsizing the text.
            // We're going to figure out the best possible line break if not.
            Layout layout = mTitle.getLayout();
            if (layout != null){
                final int lineCount = layout.getLineCount();
                if (lineCount > 0) {
                    if (layout.getEllipsisCount(lineCount - 1) == 0) {
                        mTitle.setText(findBestLineBreak(title));
                    }
                }
            }
        }

        mClickActions.clear();
        final int subItemsCount = subItems.size();
        final int blendedColor = getTextColor();
        final int startIndex = mHasHeader ? 1 : 0; // First item is header; skip it
        mRow.setVisibility(subItemsCount > 0 ? VISIBLE : GONE);
        for (int i = startIndex; i < subItemsCount; i++) {
            SliceItem item = subItems.get(i);
            RowContent rc = new RowContent(getContext(), item, true /* showStartItem */);
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

            Drawable iconDrawable = null;
            SliceItem icon = SliceQuery.find(item.getSlice(),
                    android.app.slice.SliceItem.FORMAT_IMAGE);
            if (icon != null) {
                iconDrawable = icon.getIcon().loadDrawable(mContext);
                final int width = (int) (iconDrawable.getIntrinsicWidth()
                        / (float) iconDrawable.getIntrinsicHeight() * mIconSize);
                iconDrawable.setBounds(0, 0, Math.max(width, 1), mIconSize);
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
            mContentChangeListener.accept(getLayoutTransition() != null);
        }
    }

    public void setPulsing(boolean pulsing, boolean animate) {
        mPulsing = pulsing;
        LayoutTransition transition = getLayoutTransition();
        if (!animate) {
            setLayoutTransition(null);
        }
        showSlice();
        if (!animate) {
            setLayoutTransition(transition);
        }
    }

    /**
     * Breaks a string in 2 lines where both have similar character count
     * but first line is always longer.
     *
     * @param charSequence Original text.
     * @return Optimal string.
     */
    private CharSequence findBestLineBreak(CharSequence charSequence) {
        if (TextUtils.isEmpty(charSequence)) {
            return charSequence;
        }

        String source = charSequence.toString();
        // Ignore if there is only 1 word,
        // or if line breaks were manually set.
        if (source.contains("\n") || !source.contains(" ")) {
            return source;
        }

        final String[] words = source.split(" ");
        final StringBuilder optimalString = new StringBuilder(source.length());
        int current = 0;
        while (optimalString.length() < source.length() - optimalString.length()) {
            optimalString.append(words[current]);
            if (current < words.length - 1) {
                optimalString.append(" ");
            }
            current++;
        }
        optimalString.append("\n");
        for (int i = current; i < words.length; i++) {
            optimalString.append(words[i]);
            if (current < words.length - 1) {
                optimalString.append(" ");
            }
        }

        return optimalString.toString();
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
     * Listener that gets invoked every time the title or the row visibility changes.
     * Parameter will be {@code true} whenever the change happens animated.
     * @param contentChangeListener The listener.
     */
    public void setContentChangeListener(Consumer<Boolean> contentChangeListener) {
        mContentChangeListener = contentChangeListener;
    }

    public boolean hasHeader() {
        return mTitle.getVisibility() == VISIBLE;
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

    public void setupUri(String uriString) {
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
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child instanceof KeyguardSliceButton) {
                    ((KeyguardSliceButton) child).setMaxWidth(width / 2);
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
        private final Context mContext;

        public KeyguardSliceButton(Context context) {
            super(context, null /* attrs */, 0 /* styleAttr */,
                    com.android.keyguard.R.style.TextAppearance_Keyguard_Secondary);
            mContext = context;
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
            int horizontalPadding = (int) mContext.getResources()
                    .getDimension(R.dimen.widget_horizontal_padding);
            setPadding(horizontalPadding / 2, 0, horizontalPadding / 2, 0);
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
                case LayoutTransition.DISAPPEARING:
                    if (view == mTitle) {
                        // Translate the view to the inverse of its height, so the layout event
                        // won't misposition it.
                        LayoutParams params = (LayoutParams) mTitle.getLayoutParams();
                        int margin = params.topMargin + params.bottomMargin;
                        mTitle.setTranslationY(-mTitle.getHeight() - margin);
                    }
                    break;
            }
        }

        @Override
        public void endTransition(LayoutTransition transition, ViewGroup container, View view,
                int transitionType) {

        }
    }
}
