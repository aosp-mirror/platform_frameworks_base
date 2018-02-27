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

import android.annotation.ColorInt;
import android.app.PendingIntent;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.Observer;
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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.ColorUtils;
import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.keyguard.KeyguardSliceProvider;
import com.android.systemui.tuner.TunerService;

import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

import androidx.app.slice.Slice;
import androidx.app.slice.SliceItem;
import androidx.app.slice.core.SliceQuery;
import androidx.app.slice.widget.ListContent;
import androidx.app.slice.widget.RowContent;
import androidx.app.slice.widget.SliceLiveData;

/**
 * View visible under the clock on the lock screen and AoD.
 */
public class KeyguardSliceView extends LinearLayout implements View.OnClickListener,
        Observer<Slice>, TunerService.Tunable {

    private static final String TAG = "KeyguardSliceView";
    private final HashMap<View, PendingIntent> mClickActions;
    private Uri mKeyguardSliceUri;
    private TextView mTitle;
    private LinearLayout mRow;
    private int mTextColor;
    private float mDarkAmount = 0;

    private LiveData<Slice> mLiveData;
    private int mIconSize;
    private Consumer<Boolean> mListener;
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
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mTitle = findViewById(R.id.title);
        mRow = findViewById(R.id.row);
        mTextColor = Utils.getColorAttr(mContext, R.attr.wallpaperTextColor);
        mIconSize = (int) mContext.getResources().getDimension(R.dimen.widget_icon_size);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        // Make sure we always have the most current slice
        mLiveData.observeForever(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        mLiveData.removeObserver(this);
    }

    private void showSlice(Slice slice) {

        ListContent lc = new ListContent(slice);
        mHasHeader = lc.hasHeader();
        List<SliceItem> subItems = lc.getRowItems();
        if (!mHasHeader) {
            mTitle.setVisibility(GONE);
        } else {
            mTitle.setVisibility(VISIBLE);
            // If there's a header it'll be the first subitem
            RowContent header = new RowContent(subItems.get(0), true /* showStartItem */);
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
        for (int i = startIndex; i < subItemsCount; i++) {
            SliceItem item = subItems.get(i);
            RowContent rc = new RowContent(item, true /* showStartItem */);
            final Uri itemTag = item.getSlice().getUri();
            // Try to reuse the view if already exists in the layout
            KeyguardSliceButton button = mRow.findViewWithTag(itemTag);
            if (button == null) {
                button = new KeyguardSliceButton(mContext);
                button.setTextColor(blendedColor);
                button.setTag(itemTag);
            } else {
                mRow.removeView(button);
            }
            mRow.addView(button);

            PendingIntent pendingIntent = null;
            if (rc.getContentIntent() != null) {
                pendingIntent = rc.getContentIntent().getAction();
            }
            mClickActions.put(button, pendingIntent);

            button.setText(rc.getTitleItem().getText());

            Drawable iconDrawable = null;
            SliceItem icon = SliceQuery.find(item.getSlice(),
                    android.app.slice.SliceItem.FORMAT_IMAGE);
            if (icon != null) {
                iconDrawable = icon.getIcon().loadDrawable(mContext);
                final int width = (int) (iconDrawable.getIntrinsicWidth()
                        / (float) iconDrawable.getIntrinsicHeight() * mIconSize);
                iconDrawable.setBounds(0, 0, Math.max(width, 1), mIconSize);
            }
            button.setCompoundDrawablesRelative(iconDrawable, null, null, null);
            button.setOnClickListener(this);
        }

        // Removing old views
        for (int i = 0; i < mRow.getChildCount(); i++) {
            View child = mRow.getChildAt(i);
            if (!mClickActions.containsKey(child)) {
                mRow.removeView(child);
                i--;
            }
        }

        final int visibility = mHasHeader || subItemsCount > 0 ? VISIBLE : GONE;
        if (visibility != getVisibility()) {
            setVisibility(visibility);
        }

        mListener.accept(mHasHeader);
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

    public void setDark(float darkAmount) {
        mDarkAmount = darkAmount;
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

    public void setListener(Consumer<Boolean> listener) {
        mListener = listener;
    }

    public boolean hasHeader() {
        return mHasHeader;
    }

    /**
     * LiveData observer lifecycle.
     * @param slice the new slice content.
     */
    @Override
    public void onChanged(Slice slice) {
        showSlice(slice);
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

    /**
     * Representation of an item that appears under the clock on main keyguard message.
     */
    private class KeyguardSliceButton extends Button {

        public KeyguardSliceButton(Context context) {
            super(context, null /* attrs */, 0 /* styleAttr */,
                    com.android.keyguard.R.style.TextAppearance_Keyguard_Secondary);
            int horizontalPadding = (int) context.getResources()
                    .getDimension(R.dimen.widget_horizontal_padding);
            setPadding(horizontalPadding / 2, 0, horizontalPadding / 2, 0);
            setCompoundDrawablePadding((int) context.getResources()
                    .getDimension(R.dimen.widget_icon_padding));
            setMaxWidth(KeyguardSliceView.this.getWidth() / 2);
            setMaxLines(1);
            setEllipsize(TruncateAt.END);
        }
    }
}
