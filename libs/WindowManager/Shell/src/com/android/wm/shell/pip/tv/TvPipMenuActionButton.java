/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.pip.tv;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import com.android.wm.shell.R;

/**
 * A View that represents Pip Menu action button, such as "Fullscreen" and "Close" as well custom
 * (provided by the application in Pip) and media buttons.
 */
public class TvPipMenuActionButton extends RelativeLayout implements View.OnClickListener {
    private final ImageView mIconImageView;
    private final View mButtonBackgroundView;
    private final View mButtonView;
    private OnClickListener mOnClickListener;

    public TvPipMenuActionButton(Context context) {
        this(context, null, 0, 0);
    }

    public TvPipMenuActionButton(Context context, AttributeSet attrs) {
        this(context, attrs, 0, 0);
    }

    public TvPipMenuActionButton(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public TvPipMenuActionButton(
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        final LayoutInflater inflater = (LayoutInflater) getContext()
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(R.layout.tv_pip_menu_action_button, this);

        mIconImageView = findViewById(R.id.icon);
        mButtonView = findViewById(R.id.button);
        mButtonBackgroundView = findViewById(R.id.background);

        final int[] values = new int[]{android.R.attr.src, android.R.attr.text};
        final TypedArray typedArray = context.obtainStyledAttributes(attrs, values, defStyleAttr,
                defStyleRes);

        setImageResource(typedArray.getResourceId(0, 0));
        final int textResId = typedArray.getResourceId(1, 0);
        if (textResId != 0) {
            setTextAndDescription(textResId);
        }
        typedArray.recycle();
    }

    @Override
    public void setOnClickListener(OnClickListener listener) {
        // We do not want to set an OnClickListener to the TvPipMenuActionButton itself, but only to
        // the ImageView. So let's "cash" the listener we've been passed here and set a "proxy"
        // listener to the ImageView.
        mOnClickListener = listener;
        mButtonView.setOnClickListener(listener != null ? this : null);
    }

    @Override
    public void onClick(View v) {
        if (mOnClickListener != null) {
            // Pass the correct view - this.
            mOnClickListener.onClick(this);
        }
    }

    /**
     * Sets the drawable for the button with the given drawable.
     */
    public void setImageDrawable(Drawable d) {
        mIconImageView.setImageDrawable(d);
    }

    /**
     * Sets the drawable for the button with the given resource id.
     */
    public void setImageResource(int resId) {
        if (resId != 0) {
            mIconImageView.setImageResource(resId);
        }
    }

    /**
     * Sets the text for description the with the given string.
     */
    public void setTextAndDescription(CharSequence text) {
        mButtonView.setContentDescription(text);
    }

    /**
     * Sets the text and description with the given string resource id.
     */
    public void setTextAndDescription(int resId) {
        setTextAndDescription(getContext().getString(resId));
    }

    @Override
    public void setEnabled(boolean enabled) {
        mButtonView.setEnabled(enabled);
    }

    @Override
    public boolean isEnabled() {
        return mButtonView.isEnabled();
    }

    void setIsCustomCloseAction(boolean isCustomCloseAction) {
        mIconImageView.setImageTintList(
                getResources().getColorStateList(
                        isCustomCloseAction ? R.color.tv_pip_menu_close_icon
                                : R.color.tv_pip_menu_icon));
        mButtonBackgroundView.setBackgroundTintList(getResources()
                .getColorStateList(isCustomCloseAction ? R.color.tv_pip_menu_close_icon_bg
                        : R.color.tv_pip_menu_icon_bg));
    }

    @Override
    public String toString() {
        if (mButtonView.getContentDescription() == null) {
            return TvPipMenuActionButton.class.getSimpleName();
        }
        return mButtonView.getContentDescription().toString();
    }

}
