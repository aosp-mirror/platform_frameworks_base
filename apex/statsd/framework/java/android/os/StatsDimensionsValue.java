/*
 * Copyright 2018 The Android Open Source Project
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
package android.os;

import android.annotation.SystemApi;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Container for statsd dimension value information, corresponding to a
 * stats_log.proto's DimensionValue.
 *
 * This consists of a field (an int representing a statsd atom field)
 * and a value (which may be one of a number of types).
 *
 * <p>
 * Only a single value is held, and it is necessarily one of the following types:
 * {@link String}, int, long, boolean, float,
 * or tuple (i.e. {@link List} of {@code StatsDimensionsValue}).
 *
 * The type of value held can be retrieved using {@link #getValueType()}, which returns one of the
 * following ints, depending on the type of value:
 * <ul>
 *  <li>{@link #STRING_VALUE_TYPE}</li>
 *  <li>{@link #INT_VALUE_TYPE}</li>
 *  <li>{@link #LONG_VALUE_TYPE}</li>
 *  <li>{@link #BOOLEAN_VALUE_TYPE}</li>
 *  <li>{@link #FLOAT_VALUE_TYPE}</li>
 *  <li>{@link #TUPLE_VALUE_TYPE}</li>
 * </ul>
 * Alternatively, this can be determined using {@link #isValueType(int)} with one of these constants
 * as a parameter.
 * The value itself can be retrieved using the correct get...Value() function for its type.
 *
 * <p>
 * The field is always an int, and always exists; it can be obtained using {@link #getField()}.
 *
 *
 * @hide
 */
@SystemApi
public final class StatsDimensionsValue implements Parcelable {
    private static final String TAG = "StatsDimensionsValue";

    // Values of the value type correspond to stats_log.proto's DimensionValue fields.
    // Keep constants in sync with frameworks/base/cmds/statsd/src/HashableDimensionKey.cpp.
    /** Indicates that this holds a String. */
    public static final int STRING_VALUE_TYPE = 2;
    /** Indicates that this holds an int. */
    public static final int INT_VALUE_TYPE = 3;
    /** Indicates that this holds a long. */
    public static final int LONG_VALUE_TYPE = 4;
    /** Indicates that this holds a boolean. */
    public static final int BOOLEAN_VALUE_TYPE = 5;
    /** Indicates that this holds a float. */
    public static final int FLOAT_VALUE_TYPE = 6;
    /** Indicates that this holds a List of StatsDimensionsValues. */
    public static final int TUPLE_VALUE_TYPE = 7;

    private final StatsDimensionsValueParcel mInner;

    /**
     * Creates a {@code StatsDimensionValue} from a parcel.
     *
     * @hide
     */
    public StatsDimensionsValue(Parcel in) {
        mInner = StatsDimensionsValueParcel.CREATOR.createFromParcel(in);
    }

    /**
     * Creates a {@code StatsDimensionsValue} from a StatsDimensionsValueParcel
     *
     * @hide
     */
    public StatsDimensionsValue(StatsDimensionsValueParcel parcel) {
        mInner = parcel;
    }

    /**
     * Return the field, i.e. the tag of a statsd atom.
     *
     * @return the field
     */
    public int getField() {
        return mInner.field;
    }

    /**
     * Retrieve the String held, if any.
     *
     * @return the {@link String} held if {@link #getValueType()} == {@link #STRING_VALUE_TYPE},
     *         null otherwise
     */
    public String getStringValue() {
        if (mInner.valueType == STRING_VALUE_TYPE) {
            return mInner.stringValue;
        } else {
            Log.w(TAG, "Value type is " + getValueTypeAsString() + ", not string.");
            return null;
        }
    }

    /**
     * Retrieve the int held, if any.
     *
     * @return the int held if {@link #getValueType()} == {@link #INT_VALUE_TYPE}, 0 otherwise
     */
    public int getIntValue() {
        if (mInner.valueType == INT_VALUE_TYPE) {
            return mInner.intValue;
        } else {
            Log.w(TAG, "Value type is " + getValueTypeAsString() + ", not int.");
            return 0;
        }
    }

    /**
     * Retrieve the long held, if any.
     *
     * @return the long held if {@link #getValueType()} == {@link #LONG_VALUE_TYPE}, 0 otherwise
     */
    public long getLongValue() {
        if (mInner.valueType == LONG_VALUE_TYPE) {
            return mInner.longValue;
        } else {
            Log.w(TAG, "Value type is " + getValueTypeAsString() + ", not long.");
            return 0;
        }
    }

    /**
     * Retrieve the boolean held, if any.
     *
     * @return the boolean held if {@link #getValueType()} == {@link #BOOLEAN_VALUE_TYPE},
     *         false otherwise
     */
    public boolean getBooleanValue() {
        if (mInner.valueType == BOOLEAN_VALUE_TYPE) {
            return mInner.boolValue;
        } else {
            Log.w(TAG, "Value type is " + getValueTypeAsString() + ", not boolean.");
            return false;
        }
    }

    /**
     * Retrieve the float held, if any.
     *
     * @return the float held if {@link #getValueType()} == {@link #FLOAT_VALUE_TYPE}, 0 otherwise
     */
    public float getFloatValue() {
        if (mInner.valueType == FLOAT_VALUE_TYPE) {
            return mInner.floatValue;
        } else {
            Log.w(TAG, "Value type is " + getValueTypeAsString() + ", not float.");
            return 0;
        }
    }

    /**
     * Retrieve the tuple, in the form of a {@link List} of {@link StatsDimensionsValue}, held,
     * if any.
     *
     * @return the {@link List} of {@link StatsDimensionsValue} held
     *         if {@link #getValueType()} == {@link #TUPLE_VALUE_TYPE},
     *         null otherwise
     */
    public List<StatsDimensionsValue> getTupleValueList() {
        if (mInner.valueType == TUPLE_VALUE_TYPE) {
            int length = (mInner.tupleValue == null) ? 0 : mInner.tupleValue.length;
            List<StatsDimensionsValue> tupleValues = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                tupleValues.add(new StatsDimensionsValue(mInner.tupleValue[i]));
            }
            return tupleValues;
        } else {
            Log.w(TAG, "Value type is " + getValueTypeAsString() + ", not tuple.");
            return null;
        }
    }

    /**
     * Returns the constant representing the type of value stored, namely one of
     * <ul>
     *   <li>{@link #STRING_VALUE_TYPE}</li>
     *   <li>{@link #INT_VALUE_TYPE}</li>
     *   <li>{@link #LONG_VALUE_TYPE}</li>
     *   <li>{@link #BOOLEAN_VALUE_TYPE}</li>
     *   <li>{@link #FLOAT_VALUE_TYPE}</li>
     *   <li>{@link #TUPLE_VALUE_TYPE}</li>
     * </ul>
     *
     * @return the constant representing the type of value stored
     */
    public int getValueType() {
        return mInner.valueType;
    }

    /**
     * Returns whether the type of value stored is equal to the given type.
     *
     * @param valueType int representing the type of value stored, as used in {@link #getValueType}
     * @return true if {@link #getValueType()} is equal to {@code valueType}.
     */
    public boolean isValueType(int valueType) {
        return mInner.valueType == valueType;
    }

    /**
     * Returns a String representing the information in this StatsDimensionValue.
     * No guarantees are made about the format of this String.
     *
     * @return String representation
     *
     * @hide
     */
    // Follows the format of statsd's dimension.h toString.
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(mInner.field);
        sb.append(":");
        switch (mInner.valueType) {
            case STRING_VALUE_TYPE:
                sb.append(mInner.stringValue);
                break;
            case INT_VALUE_TYPE:
                sb.append(String.valueOf(mInner.intValue));
                break;
            case LONG_VALUE_TYPE:
                sb.append(String.valueOf(mInner.longValue));
                break;
            case BOOLEAN_VALUE_TYPE:
                sb.append(String.valueOf(mInner.boolValue));
                break;
            case FLOAT_VALUE_TYPE:
                sb.append(String.valueOf(mInner.floatValue));
                break;
            case TUPLE_VALUE_TYPE:
                sb.append("{");
                int length = (mInner.tupleValue == null) ? 0 : mInner.tupleValue.length;
                for (int i = 0; i < length; i++) {
                    StatsDimensionsValue child = new StatsDimensionsValue(mInner.tupleValue[i]);
                    sb.append(child.toString());
                    sb.append("|");
                }
                sb.append("}");
                break;
            default:
                Log.w(TAG, "Incorrect value type");
                break;
        }
        return sb.toString();
    }

    /**
     * Parcelable Creator for StatsDimensionsValue.
     */
    public static final @android.annotation.NonNull
            Parcelable.Creator<StatsDimensionsValue> CREATOR = new
            Parcelable.Creator<StatsDimensionsValue>() {
                public StatsDimensionsValue createFromParcel(Parcel in) {
                    return new StatsDimensionsValue(in);
                }

                public StatsDimensionsValue[] newArray(int size) {
                    return new StatsDimensionsValue[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        mInner.writeToParcel(out, flags);
    }

    /**
     * Returns a string representation of the type of value stored.
     */
    private String getValueTypeAsString() {
        switch (mInner.valueType) {
            case STRING_VALUE_TYPE:
                return "string";
            case INT_VALUE_TYPE:
                return "int";
            case LONG_VALUE_TYPE:
                return "long";
            case BOOLEAN_VALUE_TYPE:
                return "boolean";
            case FLOAT_VALUE_TYPE:
                return "float";
            case TUPLE_VALUE_TYPE:
                return "tuple";
            default:
                return "unknown";
        }
    }
}
