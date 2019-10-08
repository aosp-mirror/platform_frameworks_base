/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.tuner;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.os.Bundle;
import android.util.AttributeSet;

import androidx.preference.ListPreference;
import androidx.preference.ListPreferenceDialogFragment;

public class CustomListPreference extends ListPreference {

    public CustomListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CustomListPreference(Context context, AttributeSet attrs, int defStyleAttr,
                                int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    protected void onPrepareDialogBuilder(AlertDialog.Builder builder,
            OnClickListener listener) {
    }

    protected void onDialogClosed(boolean positiveResult) {
    }

    protected Dialog onDialogCreated(DialogFragment fragment, Dialog dialog) {
        return dialog;
    }

    protected boolean isAutoClosePreference() {
        return true;
    }

    /**
     * Called when a user is about to choose the given value, to determine if we
     * should show a confirmation dialog.
     *
     * @param value the value the user is about to choose
     * @return the message to show in a confirmation dialog, or {@code null} to
     *         not request confirmation
     */
    protected CharSequence getConfirmationMessage(String value) {
        return null;
    }

    protected void onDialogStateRestored(DialogFragment fragment, Dialog dialog,
            Bundle savedInstanceState) {
    }

    public static class CustomListPreferenceDialogFragment extends ListPreferenceDialogFragment {

        private static final String KEY_CLICKED_ENTRY_INDEX
                = "settings.CustomListPrefDialog.KEY_CLICKED_ENTRY_INDEX";

        private int mClickedDialogEntryIndex;

        public static ListPreferenceDialogFragment newInstance(String key) {
            final ListPreferenceDialogFragment fragment = new CustomListPreferenceDialogFragment();
            final Bundle b = new Bundle(1);
            b.putString(ARG_KEY, key);
            fragment.setArguments(b);
            return fragment;
        }

        public CustomListPreference getCustomizablePreference() {
            return (CustomListPreference) getPreference();
        }

        @Override
        protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
            super.onPrepareDialogBuilder(builder);
            mClickedDialogEntryIndex = getCustomizablePreference()
                    .findIndexOfValue(getCustomizablePreference().getValue());
            getCustomizablePreference().onPrepareDialogBuilder(builder, getOnItemClickListener());
            if (!getCustomizablePreference().isAutoClosePreference()) {
                builder.setPositiveButton(com.android.internal.R.string.ok, new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        onItemConfirmed();
                    }
                });
            }
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            Dialog dialog = super.onCreateDialog(savedInstanceState);
            if (savedInstanceState != null) {
                mClickedDialogEntryIndex = savedInstanceState.getInt(KEY_CLICKED_ENTRY_INDEX,
                        mClickedDialogEntryIndex);
            }
            return getCustomizablePreference().onDialogCreated(this, dialog);
        }

        @Override
        public void onSaveInstanceState(Bundle outState) {
            super.onSaveInstanceState(outState);
            outState.putInt(KEY_CLICKED_ENTRY_INDEX, mClickedDialogEntryIndex);
        }

        @Override
        public void onActivityCreated(Bundle savedInstanceState) {
            super.onActivityCreated(savedInstanceState);
            getCustomizablePreference().onDialogStateRestored(this, getDialog(), savedInstanceState);
        }

        protected OnClickListener getOnItemClickListener() {
            return new OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    setClickedDialogEntryIndex(which);
                    if (getCustomizablePreference().isAutoClosePreference()) {
                        onItemConfirmed();
                    }
                }
            };
        }

        protected void setClickedDialogEntryIndex(int which) {
            mClickedDialogEntryIndex = which;
        }

        private String getValue() {
            final ListPreference preference = getCustomizablePreference();
            if (mClickedDialogEntryIndex >= 0 && preference.getEntryValues() != null) {
                return preference.getEntryValues()[mClickedDialogEntryIndex].toString();
            } else {
                return null;
            }
        }

        protected void onItemConfirmed() {
            onClick(getDialog(), DialogInterface.BUTTON_POSITIVE);
            getDialog().dismiss();
        }

        @Override
        public void onDialogClosed(boolean positiveResult) {
            getCustomizablePreference().onDialogClosed(positiveResult);
            final ListPreference preference = getCustomizablePreference();
            final String value = getValue();
            if (positiveResult && value != null) {
                if (preference.callChangeListener(value)) {
                    preference.setValue(value);
                }
            }
        }
    }
}
