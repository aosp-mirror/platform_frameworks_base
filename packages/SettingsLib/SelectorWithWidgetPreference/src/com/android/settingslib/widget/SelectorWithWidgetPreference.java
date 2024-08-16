/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.settingslib.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.preference.CheckBoxPreference;
import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.widget.preference.selector.R;
import com.android.settingslib.widget.selectorwithwidgetpreference.flags.Flags;

/**
 * Selector preference (checkbox or radio button) with an optional additional widget.
 *
 * Functionally speaking, it's a CheckBoxPreference. When styled like a radio button,
 * it only "looks like" a RadioButtonPreference.
 *
 * In other words, there's no "RadioButtonPreferenceGroup" in this
 * implementation. When you check one preference, if you want to
 * uncheck all the other preferences, you should do that by code yourself.
 *
 * SelectorWithWidgetPreference can assign a extraWidgetListener to show a gear icon
 * on the right side that can open another page.
 */
public class SelectorWithWidgetPreference extends CheckBoxPreference {
    @VisibleForTesting
    static final int DEFAULT_MAX_LINES = 2;

    /**
     * Interface definition for a callback to be invoked when the preference is clicked.
     */
    public interface OnClickListener {
        /**
         * Called when a preference has been clicked.
         *
         * @param emiter The clicked preference
         */
        void onRadioButtonClicked(SelectorWithWidgetPreference emiter);
    }

    private OnClickListener mListener = null;
    private View mAppendix;
    private int mAppendixVisibility = -1;

    private View mExtraWidgetContainer;
    private ImageView mExtraWidget;
    private boolean mIsCheckBox = false;  // whether to display this button as a checkbox

    private View.OnClickListener mExtraWidgetOnClickListener;
    private int mTitleMaxLines;


    /**
     * Perform inflation from XML and apply a class-specific base style.
     *
     * @param context  The {@link Context} this is associated with, through which it can
     *                 access the current theme, resources, {@link SharedPreferences}, etc.
     * @param attrs    The attributes of the XML tag that is inflating the preference
     * @param defStyle An attribute in the current theme that contains a reference to a style
     *                 resource that supplies default values for the view. Can be 0 to not
     *                 look for defaults.
     */
    public SelectorWithWidgetPreference(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyle) {
        super(context, attrs, defStyle);
        init(context, attrs, defStyle, /* defStyleRes= */ 0);
    }

    /**
     * Perform inflation from XML and apply a class-specific base style.
     *
     * @param context The {@link Context} this is associated with, through which it can
     *                access the current theme, resources, {@link SharedPreferences}, etc.
     * @param attrs   The attributes of the XML tag that is inflating the preference
     */
    public SelectorWithWidgetPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs, /* defStyleAttr= */ 0, /* defStyleRes= */ 0);
    }

    /**
     * Constructor to create a preference, which will display with a checkbox style.
     *
     * @param context    The {@link Context} this is associated with.
     * @param isCheckbox Whether this preference should display as a checkbox.
     */
    public SelectorWithWidgetPreference(Context context, boolean isCheckbox) {
        super(context, null);
        mIsCheckBox = isCheckbox;
        init(context, /* attrs= */ null, /* defStyleAttr= */ 0, /* defStyleRes= */ 0);
    }

    /**
     * Constructor to create a preference.
     *
     * @param context The Context this is associated with.
     */
    public SelectorWithWidgetPreference(Context context) {
        this(context, null);
    }

    /**
     * Sets the callback to be invoked when this preference is clicked by the user.
     *
     * @param listener The callback to be invoked
     */
    public void setOnClickListener(OnClickListener listener) {
        mListener = listener;
    }

    /**
     * Processes a click on the preference.
     */
    @Override
    public void onClick() {
        if (mListener != null) {
            mListener.onRadioButtonClicked(this);
        }
    }

    /**
     * Binds the created View to the data for this preference.
     *
     * <p>This is a good place to grab references to custom Views in the layout and set
     * properties on them.
     *
     * <p>Make sure to call through to the superclass's implementation.
     *
     * @param holder The ViewHolder that provides references to the views to fill in. These views
     *               will be recycled, so you should not hold a reference to them after this method
     *               returns.
     */
    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        View summaryContainer = holder.findViewById(R.id.summary_container);
        if (summaryContainer != null) {
            summaryContainer.setVisibility(
                    TextUtils.isEmpty(getSummary()) ? View.GONE : View.VISIBLE);
            mAppendix = holder.findViewById(R.id.appendix);
            if (mAppendix != null && mAppendixVisibility != -1) {
                mAppendix.setVisibility(mAppendixVisibility);
            }
        }

        mExtraWidget = (ImageView) holder.findViewById(R.id.selector_extra_widget);
        mExtraWidgetContainer = holder.findViewById(R.id.selector_extra_widget_container);

        setExtraWidgetOnClickListener(mExtraWidgetOnClickListener);

        if (Flags.allowSetTitleMaxLines()) {
            TextView title = (TextView) holder.findViewById(android.R.id.title);
            title.setMaxLines(mTitleMaxLines);
        }
    }

    /**
     * Set the visibility state of appendix view.
     *
     * @param visibility One of {@link View#VISIBLE}, {@link View#INVISIBLE}, or {@link View#GONE}.
     */
    public void setAppendixVisibility(int visibility) {
        if (mAppendix != null) {
            mAppendix.setVisibility(visibility);
        }
        mAppendixVisibility = visibility;
    }

    /**
     * Sets the callback to be invoked when extra widget is clicked by the user.
     *
     * @param listener The callback to be invoked
     */
    public void setExtraWidgetOnClickListener(View.OnClickListener listener) {
        mExtraWidgetOnClickListener = listener;

        if (mExtraWidget == null || mExtraWidgetContainer == null) {
            return;
        }

        mExtraWidget.setOnClickListener(mExtraWidgetOnClickListener);

        mExtraWidgetContainer.setVisibility((mExtraWidgetOnClickListener != null)
                ? View.VISIBLE : View.GONE);
    }

    /**
     * Returns whether this preference is a checkbox.
     */
    public boolean isCheckBox() {
        return mIsCheckBox;
    }

    private void init(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        if (mIsCheckBox) {
            setWidgetLayoutResource(R.layout.preference_widget_checkbox);
        } else {
            setWidgetLayoutResource(R.layout.preference_widget_radiobutton);
        }
        setLayoutResource(R.layout.preference_selector_with_widget);
        setIconSpaceReserved(false);

        if (Flags.allowSetTitleMaxLines()) {
            final TypedArray a =
                    context.obtainStyledAttributes(
                            attrs, R.styleable.SelectorWithWidgetPreference, defStyleAttr,
                            defStyleRes);
            mTitleMaxLines =
                    a.getInt(R.styleable.SelectorWithWidgetPreference_titleMaxLines,
                            DEFAULT_MAX_LINES);
            a.recycle();
        }
    }
}
