/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.content.res.TypedArray;
import android.os.Bundle;
import android.widget.ArrayAdapter;

import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AlertDialog.Builder;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceDialogFragmentCompat;

import com.android.settingslib.core.instrumentation.Instrumentable;

import java.util.ArrayList;

/**
 * {@link PreferenceDialogFragmentCompat} that updates the available options
 * when {@code onListPreferenceUpdated} is called."
 */
public class UpdatableListPreferenceDialogFragment extends PreferenceDialogFragmentCompat implements
        Instrumentable {

    private static final String SAVE_STATE_INDEX = "UpdatableListPreferenceDialogFragment.index";
    private static final String SAVE_STATE_ENTRIES =
            "UpdatableListPreferenceDialogFragment.entries";
    private static final String SAVE_STATE_ENTRY_VALUES =
            "UpdatableListPreferenceDialogFragment.entryValues";
    private static final String METRICS_CATEGORY_KEY = "metrics_category_key";
    private ArrayAdapter mAdapter;
    private int mClickedDialogEntryIndex;
    private ArrayList<CharSequence> mEntries;
    private CharSequence[] mEntryValues;
    private int mMetricsCategory = METRICS_CATEGORY_UNKNOWN;

    /**
     * Creates a new instance of {@link UpdatableListPreferenceDialogFragment}.
     */
    public static UpdatableListPreferenceDialogFragment newInstance(
            String key, int metricsCategory) {
        UpdatableListPreferenceDialogFragment fragment =
                new UpdatableListPreferenceDialogFragment();
        Bundle args = new Bundle(1);
        args.putString(ARG_KEY, key);
        args.putInt(METRICS_CATEGORY_KEY, metricsCategory);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Bundle bundle = getArguments();
        mMetricsCategory =
                bundle.getInt(METRICS_CATEGORY_KEY, METRICS_CATEGORY_UNKNOWN);
        if (savedInstanceState == null) {
            mEntries = new ArrayList<>();
            setPreferenceData(getListPreference());
        } else {
            mClickedDialogEntryIndex = savedInstanceState.getInt(SAVE_STATE_INDEX, 0);
            mEntries = savedInstanceState.getCharSequenceArrayList(SAVE_STATE_ENTRIES);
            mEntryValues =
                    savedInstanceState.getCharSequenceArray(SAVE_STATE_ENTRY_VALUES);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(SAVE_STATE_INDEX, mClickedDialogEntryIndex);
        outState.putCharSequenceArrayList(SAVE_STATE_ENTRIES, mEntries);
        outState.putCharSequenceArray(SAVE_STATE_ENTRY_VALUES, mEntryValues);
    }

    @Override
    public void onDialogClosed(boolean positiveResult) {
        if (positiveResult && mClickedDialogEntryIndex >= 0) {
            final ListPreference preference = getListPreference();
            final String value = mEntryValues[mClickedDialogEntryIndex].toString();
            if (preference.callChangeListener(value)) {
                preference.setValue(value);
            }
        }
    }

    @VisibleForTesting
    void setAdapter(ArrayAdapter adapter) {
        mAdapter = adapter;
    }

    @VisibleForTesting
    void setEntries(ArrayList<CharSequence> entries) {
        mEntries = entries;
    }

    @VisibleForTesting
    ArrayAdapter getAdapter() {
        return mAdapter;
    }

    @VisibleForTesting
    void setMetricsCategory(Bundle bundle) {
        mMetricsCategory =
                bundle.getInt(METRICS_CATEGORY_KEY, METRICS_CATEGORY_UNKNOWN);
    }

    @Override
    protected void onPrepareDialogBuilder(Builder builder) {
        super.onPrepareDialogBuilder(builder);
        final TypedArray a = getContext().obtainStyledAttributes(
                null,
                com.android.internal.R.styleable.AlertDialog,
                com.android.internal.R.attr.alertDialogStyle, 0);

        mAdapter = new ArrayAdapter<>(
                getContext(),
                a.getResourceId(
                        com.android.internal.R.styleable.AlertDialog_singleChoiceItemLayout,
                        com.android.internal.R.layout.select_dialog_singlechoice),
                mEntries);

        builder.setSingleChoiceItems(mAdapter, mClickedDialogEntryIndex,
                (dialog, which) -> {
                    mClickedDialogEntryIndex = which;
                    onClick(dialog, -1);
                    dialog.dismiss();
                });
        builder.setPositiveButton(null, null);
        a.recycle();
    }

    @Override
    public int getMetricsCategory() {
        return mMetricsCategory;
    }

    @VisibleForTesting
    ListPreference getListPreference() {
        return (ListPreference) getPreference();
    }

    private void setPreferenceData(ListPreference preference) {
        mEntries.clear();
        mClickedDialogEntryIndex = preference.findIndexOfValue(preference.getValue());
        for (CharSequence entry : preference.getEntries()) {
            mEntries.add(entry);
        }
        mEntryValues = preference.getEntryValues();
    }

    /**
     * Update new data set for list preference.
     */
    public void onListPreferenceUpdated(ListPreference preference) {
        if (mAdapter != null) {
            setPreferenceData(preference);
            mAdapter.notifyDataSetChanged();
        }
    }
}
