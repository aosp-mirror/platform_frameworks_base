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
import android.util.AttributeSet;
import android.view.View;
import android.widget.AdapterView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.widget.settingsspinner.SettingsSpinner;
import com.android.settingslib.widget.settingsspinner.SettingsSpinnerAdapter;

/**
 * This preference uses SettingsSpinner & SettingsSpinnerAdapter which provide default layouts for
 * both view and drop down view of the Spinner.
 */
public class SettingsSpinnerPreference extends Preference {

    private SettingsSpinnerAdapter mAdapter;
    private AdapterView.OnItemSelectedListener mListener;
    private int mPosition; //Default 0 for internal shard storage.
    private boolean mIsClickable = true;

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
    public SettingsSpinnerPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setLayoutResource(R.layout.settings_spinner_preference);
        setSelectable(false);
    }

    /**
     * Perform inflation from XML and apply a class-specific base style.
     *
     * @param context The {@link Context} this is associated with, through which it can
     *                access the current theme, resources, {@link SharedPreferences}, etc.
     * @param attrs   The attributes of the XML tag that is inflating the preference
     */
    public SettingsSpinnerPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.settings_spinner_preference);
        setSelectable(false);
    }

    /**
     * Constructor to create a preference.
     *
     * @param context The Context this is associated with.
     */
    public SettingsSpinnerPreference(Context context) {
        this(context, null);
    }

    /** Sets adapter of the spinner. */
    public <T extends SettingsSpinnerAdapter> void setAdapter(T adapter) {
        mAdapter = adapter;
        notifyChanged();
    }

    /** Sets item selection listener of the spinner. */
    public void setOnItemSelectedListener(AdapterView.OnItemSelectedListener listener) {
        mListener = listener;
    }

    /** Gets selected item of the spinner. */
    public Object getSelectedItem() {
        return mAdapter == null ? null : mAdapter.getItem(mPosition);
    }

    /** Gets selection position of the spinner */
    public void setSelection(int position) {
        if (mPosition == position) {
            return;
        }
        mPosition = position;
        notifyChanged();
    }

    /** Set clickable of the spinner. */
    public void setClickable(boolean isClickable) {
        if (mIsClickable == isClickable) {
            return;
        }
        mIsClickable = isClickable;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final SettingsSpinner spinner = (SettingsSpinner) holder.findViewById(R.id.spinner);
        spinner.setEnabled(mIsClickable);
        spinner.setClickable(mIsClickable);
        spinner.setAdapter(mAdapter);
        spinner.setSelection(mPosition);
        spinner.setOnItemSelectedListener(mOnSelectedListener);
    }

    private final AdapterView.OnItemSelectedListener mOnSelectedListener =
            new AdapterView.OnItemSelectedListener() {
        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            if (mPosition == position) return;
            mPosition = position;
            if (mListener != null) {
                mListener.onItemSelected(parent, view, position, id);
            }
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
            if (mListener != null) {
                mListener.onNothingSelected(parent);
            }
        }
    };
}
