/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.accessibility.floatingmenu;

import static android.util.TypedValue.COMPLEX_UNIT_PX;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.annotation.IntDef;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.text.Layout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.android.systemui.res.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * The message view with the action prompt to whether to undo operation for users when removing
 * the {@link MenuView}.
 */
class MenuMessageView extends LinearLayout implements
        ViewTreeObserver.OnComputeInternalInsetsListener, ComponentCallbacks {
    private final TextView mTextView;
    private final Button mUndoButton;

    @IntDef({
            Index.TEXT_VIEW,
            Index.UNDO_BUTTON
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Index {
        int TEXT_VIEW = 0;
        int UNDO_BUTTON = 1;
    }

    MenuMessageView(Context context) {
        super(context);

        setLayoutDirection(LAYOUT_DIRECTION_LOCALE);
        setVisibility(GONE);

        mTextView = new TextView(context);
        mUndoButton = new Button(context);

        addView(mTextView, Index.TEXT_VIEW,
                new LayoutParams(/* width= */ 0, WRAP_CONTENT, /* weight= */ 1));
        addView(mUndoButton, Index.UNDO_BUTTON, new LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

        // The message box is not focusable, but will announce its contents when it appears.
        // The textView and button are still interactable.
        setClickable(false);
        setFocusable(false);
        setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        updateResources();
    }

    @Override
    public void onLowMemory() {
        // Do nothing.
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        final FrameLayout.LayoutParams containerParams = new FrameLayout.LayoutParams(WRAP_CONTENT,
                WRAP_CONTENT);
        containerParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        setLayoutParams(containerParams);
        setGravity(Gravity.CENTER_VERTICAL);

        mUndoButton.setBackground(null);

        updateResources();

        getContext().registerComponentCallbacks(this);
        getViewTreeObserver().addOnComputeInternalInsetsListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        getContext().unregisterComponentCallbacks(this);
        getViewTreeObserver().removeOnComputeInternalInsetsListener(this);
    }

    @Override
    public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo inoutInfo) {
        inoutInfo.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);

        if (getVisibility() == VISIBLE) {
            final int x = (int) getX();
            final int y = (int) getY();
            inoutInfo.touchableRegion.union(new Rect(x, y, x + getWidth(), y + getHeight()));
        }
    }

    /**
     * Registers a listener to be invoked when this undo action button is clicked. It should be
     * called after {@link View#onAttachedToWindow()}.
     *
     * @param listener The listener that will run
     */
    void setUndoListener(OnClickListener listener) {
        mUndoButton.setOnClickListener(listener);
    }

    private void updateResources() {
        final Resources res = getResources();

        final int containerPadding =
                res.getDimensionPixelSize(
                        R.dimen.accessibility_floating_menu_message_container_horizontal_padding);
        final int margin = res.getDimensionPixelSize(
                R.dimen.accessibility_floating_menu_message_margin);
        final FrameLayout.LayoutParams containerParams =
                (FrameLayout.LayoutParams) getLayoutParams();
        containerParams.setMargins(margin, margin, margin, margin);
        setLayoutParams(containerParams);
        setBackground(res.getDrawable(R.drawable.accessibility_floating_message_background));
        setPadding(containerPadding, /* top= */ 0, containerPadding, /* bottom= */ 0);
        setMinimumWidth(
                res.getDimensionPixelSize(R.dimen.accessibility_floating_menu_message_min_width));
        setMinimumHeight(
                res.getDimensionPixelSize(R.dimen.accessibility_floating_menu_message_min_height));
        setElevation(
                res.getDimensionPixelSize(R.dimen.accessibility_floating_menu_message_elevation));

        final int textPadding =
                res.getDimensionPixelSize(
                        R.dimen.accessibility_floating_menu_message_text_vertical_padding);
        final int textColor = res.getColor(R.color.accessibility_floating_menu_message_text);
        final int textSize = res.getDimensionPixelSize(
                R.dimen.accessibility_floating_menu_message_text_size);
        mTextView.setPadding(/* left= */ 0, textPadding, /* right= */ 0, textPadding);
        mTextView.setTextSize(COMPLEX_UNIT_PX, textSize);
        mTextView.setTextColor(textColor);
        mTextView.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL);

        mUndoButton.setText(res.getString(R.string.accessibility_floating_button_undo));
        mUndoButton.setTextSize(COMPLEX_UNIT_PX, textSize);
        mUndoButton.setTextColor(textColor);
        mUndoButton.setAllCaps(true);
    }
}
