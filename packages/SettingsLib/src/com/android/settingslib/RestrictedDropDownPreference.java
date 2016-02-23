/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.settingslib;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.support.v7.preference.DropDownPreference;
import android.support.v7.preference.PreferenceViewHolder;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import static com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

public class RestrictedDropDownPreference extends DropDownPreference {
    private Spinner mSpinner;
    private final Drawable mRestrictedPadlock;
    private final int mRestrictedPadlockPadding;
    private List<RestrictedItem> mRestrictedItems = new ArrayList<>();

    public RestrictedDropDownPreference(Context context) {
        this(context, null);
    }

    public RestrictedDropDownPreference(Context context, AttributeSet attrs) {
        super(context, attrs);

        mRestrictedPadlock = RestrictedLockUtils.getRestrictedPadlock(context);
        mRestrictedPadlockPadding = context.getResources().getDimensionPixelSize(
                R.dimen.restricted_icon_padding);
    }

    private final OnItemSelectedListener mItemSelectedListener = new OnItemSelectedListener() {
        @Override
        public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
            if (position >= 0) {
                String value = getEntryValues()[position].toString();
                RestrictedItem item = getRestrictedItemForEntryValue(value);
                if (item != null) {
                    RestrictedLockUtils.sendShowAdminSupportDetailsIntent(getContext(),
                            item.enforcedAdmin);
                    mSpinner.setSelection(findIndexOfValue(getValue()));
                } else if (!value.equals(getValue()) && callChangeListener(value)) {
                    setValue(value);
                }
            }
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
            // noop
        }
    };

    @Override
    protected ArrayAdapter createAdapter() {
        return new RestrictedArrayItemAdapter(getContext());
    }

    @Override
    public void setValue(String value) {
        if (getRestrictedItemForEntryValue(value) != null) {
            return;
        }
        super.setValue(value);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder view) {
        super.onBindViewHolder(view);
        mSpinner = (Spinner) view.itemView.findViewById(R.id.spinner);
        mSpinner.setOnItemSelectedListener(mItemSelectedListener);
    }

    private class RestrictedArrayItemAdapter extends ArrayAdapter<String> {
        public RestrictedArrayItemAdapter(Context context) {
            super(context, R.layout.spinner_dropdown_restricted_item);
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            TextView view = (TextView) super.getView(position, convertView, parent);
            CharSequence entry = getItem(position);
            boolean isEntryRestricted = isRestrictedForEntry(entry);
            RestrictedLockUtils.setTextViewPadlock(getContext(), view, isEntryRestricted);
            view.setEnabled(!isEntryRestricted);
            return view;
        }
    }

    private boolean isRestrictedForEntry(CharSequence entry) {
        if (entry == null) {
            return false;
        }
        for (RestrictedItem item : mRestrictedItems) {
            if (entry.equals(item.entry)) {
                return true;
            }
        }
        return false;
    }

    private RestrictedItem getRestrictedItemForEntryValue(CharSequence entryValue) {
        if (entryValue == null) {
            return null;
        }
        for (RestrictedItem item : mRestrictedItems) {
            if (entryValue.equals(item.entryValue)) {
                return item;
            }
        }
        return null;
    }

    public void addRestrictedItem(RestrictedItem item) {
        mRestrictedItems.add(item);
    }

    public static class RestrictedItem {
        public CharSequence entry;
        public CharSequence entryValue;
        public EnforcedAdmin enforcedAdmin;

        public RestrictedItem(CharSequence entry, CharSequence entryValue,
                EnforcedAdmin enforcedAdmin) {
            this.entry = entry;
            this.entryValue = entryValue;
            this.enforcedAdmin = enforcedAdmin;
        }
    }
}