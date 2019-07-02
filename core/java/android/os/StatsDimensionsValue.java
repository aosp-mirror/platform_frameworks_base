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
import android.util.Slog;

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
    // Keep constants in sync with services/include/android/os/StatsDimensionsValue.h.
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

    /** Value of a stats_log.proto DimensionsValue.field. */
    private final int mField;

    /** Type of stats_log.proto DimensionsValue.value, according to the VALUE_TYPEs above. */
    private final int mValueType;

    /**
     * Value of a stats_log.proto DimensionsValue.value.
     * String, Integer, Long, Boolean, Float, or StatsDimensionsValue[].
     */
    private final Object mValue; // immutable or array of immutables

    /**
     * Creates a {@code StatsDimensionValue} from a parcel.
     *
     * @hide
     */
    public StatsDimensionsValue(Parcel in) {
        mField = in.readInt();
        mValueType = in.readInt();
        mValue = readValueFromParcel(mValueType, in);
    }

    /**
     * Return the field, i.e. the tag of a statsd atom.
     *
     * @return the field
     */
    public int getField() {
        return mField;
    }

    /**
     * Retrieve the String held, if any.
     *
     * @return the {@link String} held if {@link #getValueType()} == {@link #STRING_VALUE_TYPE},
     *         null otherwise
     */
    public String getStringValue() {
        try {
            if (mValueType == STRING_VALUE_TYPE) return (String) mValue;
        } catch (ClassCastException e) {
            Slog.w(TAG, "Failed to successfully get value", e);
        }
        return null;
    }

    /**
     * Retrieve the int held, if any.
     *
     * @return the int held if {@link #getValueType()} == {@link #INT_VALUE_TYPE}, 0 otherwise
     */
    public int getIntValue() {
        try {
            if (mValueType == INT_VALUE_TYPE) return (Integer) mValue;
        } catch (ClassCastException e) {
            Slog.w(TAG, "Failed to successfully get value", e);
        }
        return 0;
    }

    /**
     * Retrieve the long held, if any.
     *
     * @return the long held if {@link #getValueType()} == {@link #LONG_VALUE_TYPE}, 0 otherwise
     */
    public long getLongValue() {
        try {
            if (mValueType == LONG_VALUE_TYPE) return (Long) mValue;
        } catch (ClassCastException e) {
            Slog.w(TAG, "Failed to successfully get value", e);
        }
        return 0;
    }

    /**
     * Retrieve the boolean held, if any.
     *
     * @return the boolean held if {@link #getValueType()} == {@link #BOOLEAN_VALUE_TYPE},
     *         false otherwise
     */
    public boolean getBooleanValue() {
        try {
            if (mValueType == BOOLEAN_VALUE_TYPE) return (Boolean) mValue;
        } catch (ClassCastException e) {
            Slog.w(TAG, "Failed to successfully get value", e);
        }
        return false;
    }

    /**
     * Retrieve the float held, if any.
     *
     * @return the float held if {@link #getValueType()} == {@link #FLOAT_VALUE_TYPE}, 0 otherwise
     */
    public float getFloatValue() {
        try {
            if (mValueType == FLOAT_VALUE_TYPE) return (Float) mValue;
        } catch (ClassCastException e) {
            Slog.w(TAG, "Failed to successfully get value", e);
        }
        return 0;
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
        if (mValueType != TUPLE_VALUE_TYPE) {
            return null;
        }
        try {
            StatsDimensionsValue[] orig = (StatsDimensionsValue[]) mValue;
            List<StatsDimensionsValue> copy = new ArrayList<>(orig.length);
            // Shallow copy since StatsDimensionsValue is immutable anyway
            for (int i = 0; i < orig.length; i++) {
                copy.add(orig[i]);
            }
            return copy;
        } catch (ClassCastException e) {
            Slog.w(TAG, "Failed to successfully get value", e);
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
        return mValueType;
    }

    /**
     * Returns whether the type of value stored is equal to the given type.
     *
     * @param valueType int representing the type of value stored, as used in {@link #getValueType}
     * @return true if {@link #getValueType()} is equal to {@code valueType}.
     */
    public boolean isValueType(int valueType) {
        return mValueType == valueType;
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
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(mField);
            sb.append(":");
            if (mValueType == TUPLE_VALUE_TYPE) {
                sb.append("{");
                StatsDimensionsValue[] sbvs = (StatsDimensionsValue[]) mValue;
                for (int i = 0; i < sbvs.length; i++) {
                    sb.append(sbvs[i].toString());
                    sb.append("|");
                }
                sb.append("}");
            } else {
                sb.append(mValue.toString());
            }
            return sb.toString();
        } catch (ClassCastException e) {
            Slog.w(TAG, "Failed to successfully get value", e);
        }
        return "";
    }

    /**
     * Parcelable Creator for StatsDimensionsValue.
     */
    public static final @android.annotation.NonNull Parcelable.Creator<StatsDimensionsValue> CREATOR = new
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
        out.writeInt(mField);
        out.writeInt(mValueType);
        writeValueToParcel(mValueType, mValue, out, flags);
    }

    /** Writes mValue to a parcel. Returns true if succeeds. */
    private static boolean writeValueToParcel(int valueType, Object value, Parcel out, int flags) {
        try {
            switch (valueType) {
                case STRING_VALUE_TYPE:
                    out.writeString((String) value);
                    return true;
                case INT_VALUE_TYPE:
                    out.writeInt((Integer) value);
                    return true;
                case LONG_VALUE_TYPE:
                    out.writeLong((Long) value);
                    return true;
                case BOOLEAN_VALUE_TYPE:
                    out.writeBoolean((Boolean) value);
                    return true;
                case FLOAT_VALUE_TYPE:
                    out.writeFloat((Float) value);
                    return true;
                case TUPLE_VALUE_TYPE: {
                    StatsDimensionsValue[] values = (StatsDimensionsValue[]) value;
                    out.writeInt(values.length);
                    for (int i = 0; i < values.length; i++) {
                        values[i].writeToParcel(out, flags);
                    }
                    return true;
                }
                default:
                    Slog.w(TAG, "readValue of an impossible type " + valueType);
                    return false;
            }
        } catch (ClassCastException e) {
            Slog.w(TAG, "writeValue cast failed", e);
            return false;
        }
    }

    /** Reads mValue from a parcel. */
    private static Object readValueFromParcel(int valueType, Parcel parcel) {
        switch (valueType) {
            case STRING_VALUE_TYPE:
                return parcel.readString();
            case INT_VALUE_TYPE:
                return parcel.readInt();
            case LONG_VALUE_TYPE:
                return parcel.readLong();
            case BOOLEAN_VALUE_TYPE:
                return parcel.readBoolean();
            case FLOAT_VALUE_TYPE:
                return parcel.readFloat();
            case TUPLE_VALUE_TYPE: {
                final int sz = parcel.readInt();
                StatsDimensionsValue[] values = new StatsDimensionsValue[sz];
                for (int i = 0; i < sz; i++) {
                    values[i] = new StatsDimensionsValue(parcel);
                }
                return values;
            }
            default:
                Slog.w(TAG, "readValue of an impossible type " + valueType);
                return null;
        }
    }
}
