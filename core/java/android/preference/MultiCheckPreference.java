/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.preference;

import java.util.Arrays;

import android.app.AlertDialog.Builder;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;

/**
 * @hide
 * A {@link Preference} that displays a list of entries as
 * a dialog which allow the user to toggle each individually on and off.
 * 
 * @attr ref android.R.styleable#ListPreference_entries
 * @attr ref android.R.styleable#ListPreference_entryValues
 */
public class MultiCheckPreference extends DialogPreference {
    private CharSequence[] mEntries;
    private String[] mEntryValues;
    private boolean[] mSetValues;
    private boolean[] mOrigValues;
    private String mSummary;

    public MultiCheckPreference(
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        TypedArray a = context.obtainStyledAttributes(
                attrs, com.android.internal.R.styleable.ListPreference, defStyleAttr, defStyleRes);
        mEntries = a.getTextArray(com.android.internal.R.styleable.ListPreference_entries);
        if (mEntries != null) {
            setEntries(mEntries);
        }
        setEntryValuesCS(a.getTextArray(
                com.android.internal.R.styleable.ListPreference_entryValues));
        a.recycle();

        /* Retrieve the Preference summary attribute since it's private
         * in the Preference class.
         */
        a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.Preference, 0, 0);
        mSummary = a.getString(com.android.internal.R.styleable.Preference_summary);
        a.recycle();
    }

    public MultiCheckPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public MultiCheckPreference(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.dialogPreferenceStyle);
    }

    public MultiCheckPreference(Context context) {
        this(context, null);
    }

    /**
     * Sets the human-readable entries to be shown in the list. This will be
     * shown in subsequent dialogs.
     * <p>
     * Each entry must have a corresponding index in
     * {@link #setEntryValues(CharSequence[])}.
     * 
     * @param entries The entries.
     * @see #setEntryValues(CharSequence[])
     */
    public void setEntries(CharSequence[] entries) {
        mEntries = entries;
        mSetValues = new boolean[entries.length];
        mOrigValues = new boolean[entries.length];
    }
    
    /**
     * @see #setEntries(CharSequence[])
     * @param entriesResId The entries array as a resource.
     */
    public void setEntries(int entriesResId) {
        setEntries(getContext().getResources().getTextArray(entriesResId));
    }
    
    /**
     * The list of entries to be shown in the list in subsequent dialogs.
     * 
     * @return The list as an array.
     */
    public CharSequence[] getEntries() {
        return mEntries;
    }
    
    /**
     * The array to find the value to save for a preference when an entry from
     * entries is selected. If a user clicks on the second item in entries, the
     * second item in this array will be saved to the preference.
     * 
     * @param entryValues The array to be used as values to save for the preference.
     */
    public void setEntryValues(String[] entryValues) {
        mEntryValues = entryValues;
        Arrays.fill(mSetValues, false);
        Arrays.fill(mOrigValues, false);
    }

    /**
     * @see #setEntryValues(CharSequence[])
     * @param entryValuesResId The entry values array as a resource.
     */
    public void setEntryValues(int entryValuesResId) {
        setEntryValuesCS(getContext().getResources().getTextArray(entryValuesResId));
    }

    private void setEntryValuesCS(CharSequence[] values) {
        setValues(null);
        if (values != null) {
            mEntryValues = new String[values.length];
            for (int i=0; i<values.length; i++) {
                mEntryValues[i] = values[i].toString();
            }
        }
    }

    /**
     * Returns the array of values to be saved for the preference.
     * 
     * @return The array of values.
     */
    public String[] getEntryValues() {
        return mEntryValues;
    }

    /**
     * Get the boolean state of a given value.
     */
    public boolean getValue(int index) {
        return mSetValues[index];
    }

    /**
     * Set the boolean state of a given value.
     */
    public void setValue(int index, boolean state) {
        mSetValues[index] = state;
    }

    /**
     * Sets the current values.
     */
    public void setValues(boolean[] values) {
        if (mSetValues != null) {
            Arrays.fill(mSetValues, false);
            Arrays.fill(mOrigValues, false);
            if (values != null) {
                System.arraycopy(values, 0, mSetValues, 0,
                        values.length < mSetValues.length ? values.length : mSetValues.length);
            }
        }
    }

    /**
     * Returns the summary of this ListPreference. If the summary
     * has a {@linkplain java.lang.String#format String formatting}
     * marker in it (i.e. "%s" or "%1$s"), then the current entry
     * value will be substituted in its place.
     *
     * @return the summary with appropriate string substitution
     */
    @Override
    public CharSequence getSummary() {
        if (mSummary == null) {
            return super.getSummary();
        } else {
            return mSummary;
        }
    }

    /**
     * Sets the summary for this Preference with a CharSequence.
     * If the summary has a
     * {@linkplain java.lang.String#format String formatting}
     * marker in it (i.e. "%s" or "%1$s"), then the current entry
     * value will be substituted in its place when it's retrieved.
     *
     * @param summary The summary for the preference.
     */
    @Override
    public void setSummary(CharSequence summary) {
        super.setSummary(summary);
        if (summary == null && mSummary != null) {
            mSummary = null;
        } else if (summary != null && !summary.equals(mSummary)) {
            mSummary = summary.toString();
        }
    }
    
    /**
     * Returns the currently selected values.
     */
    public boolean[] getValues() {
        return mSetValues;
    }
    
    /**
     * Returns the index of the given value (in the entry values array).
     * 
     * @param value The value whose index should be returned.
     * @return The index of the value, or -1 if not found.
     */
    public int findIndexOfValue(String value) {
        if (value != null && mEntryValues != null) {
            for (int i = mEntryValues.length - 1; i >= 0; i--) {
                if (mEntryValues[i].equals(value)) {
                    return i;
                }
            }
        }
        return -1;
    }
    
    @Override
    protected void onPrepareDialogBuilder(Builder builder) {
        super.onPrepareDialogBuilder(builder);
        
        if (mEntries == null || mEntryValues == null) {
            throw new IllegalStateException(
                    "ListPreference requires an entries array and an entryValues array.");
        }

        mOrigValues = Arrays.copyOf(mSetValues, mSetValues.length);
        builder.setMultiChoiceItems(mEntries, mSetValues,
                new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                        mSetValues[which] = isChecked;
                    }
        });
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);

        if (positiveResult) {
            if (callChangeListener(getValues())) {
                return;
            }
        }
        System.arraycopy(mOrigValues, 0, mSetValues, 0, mSetValues.length);
    }

    @Override
    protected Object onGetDefaultValue(TypedArray a, int index) {
        return a.getString(index);
    }

    @Override
    protected void onSetInitialValue(boolean restoreValue, Object defaultValue) {
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        final Parcelable superState = super.onSaveInstanceState();
        if (isPersistent()) {
            // No need to save instance state since it's persistent
            return superState;
        }
        
        final SavedState myState = new SavedState(superState);
        myState.values = getValues();
        return myState;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        if (state == null || !state.getClass().equals(SavedState.class)) {
            // Didn't save state for us in onSaveInstanceState
            super.onRestoreInstanceState(state);
            return;
        }
         
        SavedState myState = (SavedState) state;
        super.onRestoreInstanceState(myState.getSuperState());
        setValues(myState.values);
    }
    
    private static class SavedState extends BaseSavedState {
        boolean[] values;
        
        public SavedState(Parcel source) {
            super(source);
            values = source.createBooleanArray();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeBooleanArray(values);
        }

        public SavedState(Parcelable superState) {
            super(superState);
        }

        public static final Parcelable.Creator<SavedState> CREATOR =
                new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }
    
}
