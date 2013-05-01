/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.content;

import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Inherited;

/**
 * Applications can expose restrictions for a restricted user on a
 * multiuser device. The administrator can configure these restrictions that will then be
 * applied to the restricted user. Each RestrictionsEntry is one configurable restriction.
 * <p/>
 * Any application that chooses to expose such restrictions does so by implementing a
 * receiver that handles the {@link Intent#ACTION_GET_RESTRICTION_ENTRIES} action.
 * The receiver then returns a result bundle that contains an entry called "restrictions", whose
 * value is an ArrayList<RestrictionsEntry>.
 */
public class RestrictionEntry implements Parcelable {

    /**
     * A type of restriction. Use this type for information that needs to be transferred across
     * but shouldn't be presented to the user in the UI. Stores a single String value.
     */
    public static final int TYPE_NULL         = 0;

    /**
     * A type of restriction. Use this for storing a boolean value, typically presented as
     * a checkbox in the UI.
     */
    public static final int TYPE_BOOLEAN      = 1;

    /**
     * A type of restriction. Use this for storing a string value, typically presented as
     * a single-select list. Call {@link #setChoiceEntries(String[])} and
     * {@link #setChoiceValues(String[])} to set the localized list entries to present to the user
     * and the corresponding values, respectively.
     */
    public static final int TYPE_CHOICE       = 2;

    /**
     * A type of restriction. Use this for storing a string value, typically presented as
     * a single-select list. Call {@link #setChoiceEntries(String[])} and
     * {@link #setChoiceValues(String[])} to set the localized list entries to present to the user
     * and the corresponding values, respectively.
     * The presentation could imply that values in lower array indices are included when a
     * particular value is chosen.
     * @hide
     */
    public static final int TYPE_CHOICE_LEVEL = 3;

    /**
     * A type of restriction. Use this for presenting a multi-select list where more than one
     * entry can be selected, such as for choosing specific titles to white-list.
     * Call {@link #setChoiceEntries(String[])} and
     * {@link #setChoiceValues(String[])} to set the localized list entries to present to the user
     * and the corresponding values, respectively.
     * Use {@link #getAllSelectedStrings()} and {@link #setAllSelectedStrings(String[])} to
     * manipulate the selections.
     */
    public static final int TYPE_MULTI_SELECT = 4;

    /** The type of restriction. */
    private int type;

    /** The unique key that identifies the restriction. */
    private String key;

    /** The user-visible title of the restriction. */
    private String title;

    /** The user-visible secondary description of the restriction. */
    private String description;

    /** The user-visible set of choices used for single-select and multi-select lists. */
    private String [] choices;

    /** The values corresponding to the user-visible choices. The value(s) of this entry will
     * one or more of these, returned by {@link #getAllSelectedStrings()} and
     * {@link #getSelectedString()}.
     */
    private String [] values;

    /* The chosen value, whose content depends on the type of the restriction. */
    private String currentValue;

    /* List of selected choices in the multi-select case. */
    private String[] currentValues;

    /**
     * Constructor for {@link #TYPE_CHOICE} type.
     * @param key the unique key for this restriction
     * @param selectedString the current value
     */
    public RestrictionEntry(String key, String selectedString) {
        this.key = key;
        this.type = TYPE_CHOICE;
        this.currentValue = selectedString;
    }

    /**
     * Constructor for {@link #TYPE_BOOLEAN} type.
     * @param key the unique key for this restriction
     * @param selectedState whether this restriction is selected or not
     */
    public RestrictionEntry(String key, boolean selectedState) {
        this.key = key;
        this.type = TYPE_BOOLEAN;
        setSelectedState(selectedState);
    }

    /**
     * Constructor for {@link #TYPE_MULTI_SELECT} type.
     * @param key the unique key for this restriction
     * @param selectedStrings the list of values that are currently selected
     */
    public RestrictionEntry(String key, String[] selectedStrings) {
        this.key = key;
        this.type = TYPE_MULTI_SELECT;
        this.currentValues = selectedStrings;
    }

    /**
     * Sets the type for this restriction.
     * @param type the type for this restriction.
     */
    public void setType(int type) {
        this.type = type;
    }

    /**
     * Returns the type for this restriction.
     * @return the type for this restriction
     */
    public int getType() {
        return type;
    }

    /**
     * Returns the currently selected string value.
     * @return the currently selected value, which can be null for types that aren't for holding
     * single string values.
     */
    public String getSelectedString() {
        return currentValue;
    }

    /**
     * Returns the list of currently selected values.
     * @return the list of current selections, if type is {@link #TYPE_MULTI_SELECT},
     *  null otherwise.
     */
    public String[] getAllSelectedStrings() {
        return currentValues;
    }

    /**
     * Returns the current selected state for an entry of type {@link #TYPE_BOOLEAN}.
     * @return the current selected state of the entry.
     */
    public boolean getSelectedState() {
        return Boolean.parseBoolean(currentValue);
    }

    /**
     * Sets the string value to use as the selected value for this restriction. This value will
     * be persisted by the system for later use by the application.
     * @param selectedString the string value to select.
     */
    public void setSelectedString(String selectedString) {
        currentValue = selectedString;
    }

    /**
     * Sets the current selected state for an entry of type {@link #TYPE_BOOLEAN}. This value will
     * be persisted by the system for later use by the application.
     * @param state the current selected state
     */
    public void setSelectedState(boolean state) {
        currentValue = Boolean.toString(state);
    }

    /**
     * Sets the current list of selected values for an entry of type {@link #TYPE_MULTI_SELECT}.
     * These values will be persisted by the system for later use by the application.
     * @param allSelectedStrings the current list of selected values.
     */
    public void setAllSelectedStrings(String[] allSelectedStrings) {
        currentValues = allSelectedStrings;
    }

    /**
     * Sets a list of string values that can be selected by the user. If no user-visible entries
     * are set by a call to {@link #setChoiceEntries(String[])}, these values will be the ones
     * shown to the user. Values will be chosen from this list as the user's selection and the
     * selected values can be retrieved by a call to {@link #getAllSelectedStrings()}, or
     * {@link #getSelectedString()}, depending on whether it is a multi-select type or choice type.
     * This method is not relevant for types other than
     * {@link #TYPE_CHOICE}, and {@link #TYPE_MULTI_SELECT}.
     * @param choiceValues an array of Strings which will be the selected values for the user's
     * selections.
     * @see #getChoiceValues()
     * @see #getAllSelectedStrings()
     */
    public void setChoiceValues(String[] choiceValues) {
        values = choiceValues;
    }

    /**
     * Sets a list of string values that can be selected by the user, similar to
     * {@link #setChoiceValues(String[])}.
     * @param context the application context for retrieving the resources.
     * @param stringArrayResId the resource id for a string array containing the possible values.
     * @see #setChoiceValues(String[])
     */
    public void setChoiceValues(Context context, int stringArrayResId) {
        values = context.getResources().getStringArray(stringArrayResId);
    }

    /**
     * Returns the list of possible string values set earlier.
     * @return the list of possible values.
     */
    public String[] getChoiceValues() {
        return values;
    }

    /**
     * Sets a list of strings that will be presented as choices to the user. When the
     * user selects one or more of these choices, the corresponding value from the possible values
     * are stored as the selected strings. The size of this array must match the size of the array
     * set in {@link #setChoiceValues(String[])}. This method is not relevant for types other
     * than {@link #TYPE_CHOICE}, and {@link #TYPE_MULTI_SELECT}.
     * @param choiceEntries the list of user-visible choices.
     * @see #setChoiceValues(String[])
     */
    public void setChoiceEntries(String[] choiceEntries) {
        choices = choiceEntries;
    }

    /** Sets a list of strings that will be presented as choices to the user. This is similar to
     * {@link #setChoiceEntries(String[])}.
     * @param context the application context, used for retrieving the resources.
     * @param stringArrayResId the resource id of a string array containing the possible entries.
     */
    public void setChoiceEntries(Context context, int stringArrayResId) {
        choices = context.getResources().getStringArray(stringArrayResId);
    }

    /**
     * Returns the list of strings, set earlier, that will be presented as choices to the user.
     * @return the list of choices presented to the user.
     */
    public String[] getChoiceEntries() {
        return choices;
    }

    /**
     * Returns the provided user-visible description of the entry, if any.
     * @return the user-visible description, null if none was set earlier.
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the user-visible description of the entry, as a possible sub-text for the title.
     * You can use this to describe the entry in more detail or to display the current state of
     * the restriction.
     * @param description the user-visible description string.
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * This is the unique key for the restriction entry.
     * @return the key for the restriction.
     */
    public String getKey() {
        return key;
    }

    /**
     * Returns the user-visible title for the entry, if any.
     * @return the user-visible title for the entry, null if none was set earlier.
     */
    public String getTitle() {
        return title;
    }

    /**
     * Sets the user-visible title for the entry.
     * @param title the user-visible title for the entry.
     */
    public void setTitle(String title) {
        this.title = title;
    }

    private boolean equalArrays(String[] one, String[] other) {
        if (one.length != other.length) return false;
        for (int i = 0; i < one.length; i++) {
            if (!one[i].equals(other[i])) return false;
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof RestrictionEntry)) return false;
        final RestrictionEntry other = (RestrictionEntry) o;
        // Make sure that either currentValue matches or currentValues matches.
        return type == other.type && key.equals(other.key)
                &&
                ((currentValues == null && other.currentValues == null
                  && currentValue != null && currentValue.equals(other.currentValue))
                 ||
                 (currentValue == null && other.currentValue == null
                  && currentValues != null && equalArrays(currentValues, other.currentValues)));
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + key.hashCode();
        if (currentValue != null) {
            result = 31 * result + currentValue.hashCode();
        } else if (currentValues != null) {
            for (String value : currentValues) {
                if (value != null) {
                    result = 31 * result + value.hashCode();
                }
            }
        }
        return result;
    }

    private String[] readArray(Parcel in) {
        int count = in.readInt();
        String[] values = new String[count];
        for (int i = 0; i < count; i++) {
            values[i] = in.readString();
        }
        return values;
    }

    public RestrictionEntry(Parcel in) {
        type = in.readInt();
        key = in.readString();
        title = in.readString();
        description = in.readString();
        choices = readArray(in);
        values = readArray(in);
        currentValue = in.readString();
        currentValues = readArray(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    private void writeArray(Parcel dest, String[] values) {
        if (values == null) {
            dest.writeInt(0);
        } else {
            dest.writeInt(values.length);
            for (int i = 0; i < values.length; i++) {
                dest.writeString(values[i]);
            }
        }
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(type);
        dest.writeString(key);
        dest.writeString(title);
        dest.writeString(description);
        writeArray(dest, choices);
        writeArray(dest, values);
        dest.writeString(currentValue);
        writeArray(dest, currentValues);
    }

    public static final Creator<RestrictionEntry> CREATOR = new Creator<RestrictionEntry>() {
        public RestrictionEntry createFromParcel(Parcel source) {
            return new RestrictionEntry(source);
        }

        public RestrictionEntry[] newArray(int size) {
            return new RestrictionEntry[size];
        }
    };

    @Override
    public String toString() {
        return "RestrictionsEntry {type=" + type + ", key=" + key + ", value=" + currentValue + "}";
    }
}
