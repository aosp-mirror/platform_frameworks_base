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
     * A type of restriction. Use this one for information that needs to be transferred across
     * but shouldn't be presented to the user in the UI.
     */
    public static final int TYPE_NULL         = 0;
    /**
     * A type of restriction. Use this for storing true/false values, typically presented as
     * a checkbox in the UI.
     */
    public static final int TYPE_BOOLEAN      = 1;
    /**
     * A type of restriction. Use this for storing a string value, typically presented as
     * a single-select list. The {@link #values} and {@link #choices} need to have the list of
     * possible values and the corresponding localized strings, respectively, to present in the UI.
     */
    public static final int TYPE_CHOICE       = 2;
    /**
     * A type of restriction. Use this for storing a string value, typically presented as
     * a single-select list. The {@link #values} and {@link #choices} need to have the list of
     * possible values and the corresponding localized strings, respectively, to present in the UI.
     * The presentation could imply that values in lower array indices are included when a
     * particular value is chosen.
     */
    public static final int TYPE_CHOICE_LEVEL = 3;
    /**
     * A type of restriction. Use this for presenting a multi-select list where more than one
     * entry can be selected, such as for choosing specific titles to white-list.
     * The {@link #values} and {@link #choices} need to have the list of
     * possible values and the corresponding localized strings, respectively, to present in the UI.
     * Use {@link #getMultipleValues()} and {@link #setMultipleValues(String[])} to manipulate
     * the selections.
     */
    public static final int TYPE_MULTI_SELECT = 4;

    /** The type of restriction. */
    public int type;

    /** The unique key that identifies the restriction. */
    public String key;

    /** The user-visible title of the restriction. */
    public String title;

    /** The user-visible secondary description of the restriction. */
    public String description;

    /** The user-visible set of choices used for single-select and multi-select lists. */
    public String [] choices;

    /** The values corresponding to the user-visible choices. The value(s) of this entry will
     * one or more of these, returned by {@link #getMultipleValues()} and
     * {@link #getStringValue()}.
     */
    public String [] values;

    /* The chosen value, whose content depends on the type of the restriction. */
    private String currentValue;
    /* List of selected choices in the multi-select case. */
    private String[] currentValues;

    /**
     * Constructor for {@link #TYPE_CHOICE} and {@link #TYPE_CHOICE_LEVEL} types.
     * @param key the unique key for this restriction
     * @param value the current value
     */
    public RestrictionEntry(String key, String value) {
        this.key = key;
        this.currentValue = value;
    }

    /**
     * Constructor for {@link #TYPE_BOOLEAN} type.
     * @param key the unique key for this restriction
     * @param value the current value
     */
    public RestrictionEntry(String key, boolean value) {
        this.key = key;
        setValue(value);
    }

    /**
     * Constructor for {@link #TYPE_MULTI_SELECT} type.
     * @param key the unique key for this restriction
     * @param multipleValues the list of values that are currently selected
     */
    public RestrictionEntry(String key, String[] multipleValues) {
        this.key = key;
        this.currentValues = multipleValues;
    }

    /**
     * Returns the current value. Null for {@link #TYPE_MULTI_SELECT} type.
     * @return the current value
     */
    public String getStringValue() {
        return currentValue;
    }

    /**
     * Returns the list of current selections. Null if the type is not {@link #TYPE_MULTI_SELECT}.
     * @return the list of current selections.
     */
    public String[] getMultipleValues() {
        return currentValues;
    }

    /**
     * Returns the current boolean value for entries of type {@link #TYPE_BOOLEAN}.
     * @return the current value
     */
    public boolean getBooleanValue() {
        return Boolean.parseBoolean(currentValue);
    }

    /**
     * Set the current string value.
     * @param s the current value
     */
    public void setValue(String s) {
        currentValue = s;
    }

    /**
     * Sets the current boolean value.
     * @param b the current value
     */
    public void setValue(boolean b) {
        currentValue = Boolean.toString(b);
    }

    /**
     * Sets the current list of selected values.
     * @param values the current list of selected values
     */
    public void setMultipleValues(String[] values) {
        currentValues = values;
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
