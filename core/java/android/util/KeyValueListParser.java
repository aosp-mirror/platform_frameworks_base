/*
 * Copyright (C) 2015 The Android Open Source Project
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
package android.util;

import android.text.TextUtils;
import android.util.proto.ProtoOutputStream;

import java.io.PrintWriter;
import java.time.Duration;
import java.time.format.DateTimeParseException;

/**
 * Parses a list of key=value pairs, separated by some delimiter, and puts the results in
 * an internal Map. Values can be then queried by key, or if not found, a default value
 * can be used.
 * @hide
 */
public class KeyValueListParser {
    private final ArrayMap<String, String> mValues = new ArrayMap<>();
    private final TextUtils.StringSplitter mSplitter;

    /**
     * Constructs a new KeyValueListParser. This can be reused for different strings
     * by calling {@link #setString(String)}.
     * @param delim The delimiter that separates key=value pairs.
     */
    public KeyValueListParser(char delim) {
        mSplitter = new TextUtils.SimpleStringSplitter(delim);
    }

    /**
     * Resets the parser with a new string to parse. The string is expected to be in the following
     * format:
     * <pre>key1=value,key2=value,key3=value</pre>
     *
     * where the delimiter is a comma.
     *
     * @param str the string to parse.
     * @throws IllegalArgumentException if the string is malformed.
     */
    public void setString(String str) throws IllegalArgumentException {
        mValues.clear();
        if (str != null) {
            mSplitter.setString(str);
            for (String pair : mSplitter) {
                int sep = pair.indexOf('=');
                if (sep < 0) {
                    mValues.clear();
                    throw new IllegalArgumentException(
                            "'" + pair + "' in '" + str + "' is not a valid key-value pair");
                }
                mValues.put(pair.substring(0, sep).trim(), pair.substring(sep + 1).trim());
            }
        }
    }

    /**
     * Get the value for key as an int.
     * @param key The key to lookup.
     * @param def The value to return if the key was not found, or the value was not a long.
     * @return the int value associated with the key.
     */
    public int getInt(String key, int def) {
        String value = mValues.get(key);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                // fallthrough
            }
        }
        return def;
    }

    /**
     * Get the value for key as a long.
     * @param key The key to lookup.
     * @param def The value to return if the key was not found, or the value was not a long.
     * @return the long value associated with the key.
     */
    public long getLong(String key, long def) {
        String value = mValues.get(key);
        if (value != null) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                // fallthrough
            }
        }
        return def;
    }

    /**
     * Get the value for key as a float.
     * @param key The key to lookup.
     * @param def The value to return if the key was not found, or the value was not a float.
     * @return the float value associated with the key.
     */
    public float getFloat(String key, float def) {
        String value = mValues.get(key);
        if (value != null) {
            try {
                return Float.parseFloat(value);
            } catch (NumberFormatException e) {
                // fallthrough
            }
        }
        return def;
    }

    /**
     * Get the value for key as a string.
     * @param key The key to lookup.
     * @param def The value to return if the key was not found.
     * @return the string value associated with the key.
     */
    public String getString(String key, String def) {
        String value = mValues.get(key);
        if (value != null) {
            return value;
        }
        return def;
    }

    /**
     * Get the value for key as a boolean.
     * @param key The key to lookup.
     * @param def The value to return if the key was not found.
     * @return the string value associated with the key.
     */
    public boolean getBoolean(String key, boolean def) {
        String value = mValues.get(key);
        if (value != null) {
            try {
                return Boolean.parseBoolean(value);
            } catch (NumberFormatException e) {
                // fallthrough
            }
        }
        return def;
    }

    /**
     * Get the value for key as an integer array.
     *
     * The value should be encoded as "0:1:2:3:4"
     *
     * @param key The key to lookup.
     * @param def The value to return if the key was not found.
     * @return the int[] value associated with the key.
     */
    public int[] getIntArray(String key, int[] def) {
        String value = mValues.get(key);
        if (value != null) {
            try {
                String[] parts = value.split(":");
                if (parts.length > 0) {
                    int[] ret = new int[parts.length];
                    for (int i = 0; i < parts.length; i++) {
                        ret[i] = Integer.parseInt(parts[i]);
                    }
                    return ret;
                }
            } catch (NumberFormatException e) {
                // fallthrough
            }
        }
        return def;
    }

    /**
     * @return the number of keys.
     */
    public int size() {
        return mValues.size();
    }

    /**
     * @return the key at {@code index}. Use with {@link #size()} to enumerate all key-value pairs.
     */
    public String keyAt(int index) {
        return mValues.keyAt(index);
    }

    /**
     * {@hide}
     * Parse a duration in millis based on java.time.Duration or just a number (millis)
     */
    public long getDurationMillis(String key, long def) {
        String value = mValues.get(key);
        if (value != null) {
            try {
                if (value.startsWith("P") || value.startsWith("p")) {
                    return Duration.parse(value).toMillis();
                } else {
                    return Long.parseLong(value);
                }
            } catch (NumberFormatException | DateTimeParseException e) {
                // fallthrough
            }
        }
        return def;
    }

    /** Represents an integer config value. */
    public static class IntValue {
        private final String mKey;
        private final int mDefaultValue;
        private int mValue;

        /** Constructor, initialize with a config key and a default value. */
        public IntValue(String key, int defaultValue) {
            mKey = key;
            mDefaultValue = defaultValue;
            mValue = mDefaultValue;
        }

        /** Read a value from {@link KeyValueListParser} */
        public void parse(KeyValueListParser parser) {
            mValue = parser.getInt(mKey, mDefaultValue);
        }

        /** Return the config key. */
        public String getKey() {
            return mKey;
        }

        /** Return the default value. */
        public int getDefaultValue() {
            return mDefaultValue;
        }

        /** Return the actual config value. */
        public int getValue() {
            return mValue;
        }

        /** Overwrites with a value. */
        public void setValue(int value) {
            mValue = value;
        }

        /** Used for dumpsys */
        public void dump(PrintWriter pw, String prefix) {
            pw.print(prefix);
            pw.print(mKey);
            pw.print("=");
            pw.print(mValue);
            pw.println();
        }

        /** Used for proto dumpsys */
        public void dumpProto(ProtoOutputStream proto, long tag) {
            proto.write(tag, mValue);
        }
    }

    /** Represents an long config value. */
    public static class LongValue {
        private final String mKey;
        private final long mDefaultValue;
        private long mValue;

        /** Constructor, initialize with a config key and a default value. */
        public LongValue(String key, long defaultValue) {
            mKey = key;
            mDefaultValue = defaultValue;
            mValue = mDefaultValue;
        }

        /** Read a value from {@link KeyValueListParser} */
        public void parse(KeyValueListParser parser) {
            mValue = parser.getLong(mKey, mDefaultValue);
        }

        /** Return the config key. */
        public String getKey() {
            return mKey;
        }

        /** Return the default value. */
        public long getDefaultValue() {
            return mDefaultValue;
        }

        /** Return the actual config value. */
        public long getValue() {
            return mValue;
        }

        /** Overwrites with a value. */
        public void setValue(long value) {
            mValue = value;
        }

        /** Used for dumpsys */
        public void dump(PrintWriter pw, String prefix) {
            pw.print(prefix);
            pw.print(mKey);
            pw.print("=");
            pw.print(mValue);
            pw.println();
        }

        /** Used for proto dumpsys */
        public void dumpProto(ProtoOutputStream proto, long tag) {
            proto.write(tag, mValue);
        }
    }

    /** Represents an string config value. */
    public static class StringValue {
        private final String mKey;
        private final String mDefaultValue;
        private String mValue;

        /** Constructor, initialize with a config key and a default value. */
        public StringValue(String key, String defaultValue) {
            mKey = key;
            mDefaultValue = defaultValue;
            mValue = mDefaultValue;
        }

        /** Read a value from {@link KeyValueListParser} */
        public void parse(KeyValueListParser parser) {
            mValue = parser.getString(mKey, mDefaultValue);
        }

        /** Return the config key. */
        public String getKey() {
            return mKey;
        }

        /** Return the default value. */
        public String getDefaultValue() {
            return mDefaultValue;
        }

        /** Return the actual config value. */
        public String getValue() {
            return mValue;
        }

        /** Overwrites with a value. */
        public void setValue(String value) {
            mValue = value;
        }

        /** Used for dumpsys */
        public void dump(PrintWriter pw, String prefix) {
            pw.print(prefix);
            pw.print(mKey);
            pw.print("=");
            pw.print(mValue);
            pw.println();
        }

        /** Used for proto dumpsys */
        public void dumpProto(ProtoOutputStream proto, long tag) {
            proto.write(tag, mValue);
        }
    }
}
