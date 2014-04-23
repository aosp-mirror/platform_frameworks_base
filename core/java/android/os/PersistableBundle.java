/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.util.ArrayMap;
import com.android.internal.util.XmlUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * A mapping from String values to various types that can be saved to persistent and later
 * restored.
 *
 */
public final class PersistableBundle extends CommonBundle implements XmlUtils.WriteMapCallback {
    private static final String TAG_PERSISTABLEMAP = "pbundle_as_map";
    public static final PersistableBundle EMPTY;
    static final Parcel EMPTY_PARCEL;

    static {
        EMPTY = new PersistableBundle();
        EMPTY.mMap = ArrayMap.EMPTY;
        EMPTY_PARCEL = CommonBundle.EMPTY_PARCEL;
    }

    /**
     * Constructs a new, empty PersistableBundle.
     */
    public PersistableBundle() {
        super();
    }

    /**
     * Constructs a PersistableBundle whose data is stored as a Parcel.  The data
     * will be unparcelled on first contact, using the assigned ClassLoader.
     *
     * @param parcelledData a Parcel containing a PersistableBundle
     */
    PersistableBundle(Parcel parcelledData) {
        super(parcelledData);
    }

    /* package */ PersistableBundle(Parcel parcelledData, int length) {
        super(parcelledData, length);
    }

    /**
     * Constructs a new, empty PersistableBundle that uses a specific ClassLoader for
     * instantiating Parcelable and Serializable objects.
     *
     * @param loader An explicit ClassLoader to use when instantiating objects
     * inside of the PersistableBundle.
     */
    public PersistableBundle(ClassLoader loader) {
        super(loader);
    }

    /**
     * Constructs a new, empty PersistableBundle sized to hold the given number of
     * elements. The PersistableBundle will grow as needed.
     *
     * @param capacity the initial capacity of the PersistableBundle
     */
    public PersistableBundle(int capacity) {
        super(capacity);
    }

    /**
     * Constructs a PersistableBundle containing a copy of the mappings from the given
     * PersistableBundle.
     *
     * @param b a PersistableBundle to be copied.
     */
    public PersistableBundle(PersistableBundle b) {
        super(b);
    }

    /**
     * Constructs a PersistableBundle containing the mappings passed in.
     *
     * @param map a Map containing only those items that can be persisted.
     * @throws IllegalArgumentException if any element of #map cannot be persisted.
     */
    private PersistableBundle(Map<String, Object> map) {
        super();

        // First stuff everything in.
        putAll(map);

        // Now verify each item throwing an exception if there is a violation.
        Set<String> keys = map.keySet();
        Iterator<String> iterator = keys.iterator();
        while (iterator.hasNext()) {
            String key = iterator.next();
            Object value = map.get(key);
            if (value instanceof Map) {
                // Fix up any Maps by replacing them with PersistableBundles.
                putPersistableBundle(key, new PersistableBundle((Map<String, Object>) value));
            } else if (!(value instanceof Integer) && !(value instanceof Long) &&
                    !(value instanceof Double) && !(value instanceof String) &&
                    !(value instanceof int[]) && !(value instanceof long[]) &&
                    !(value instanceof double[]) && !(value instanceof String[]) &&
                    !(value instanceof PersistableBundle) && (value != null)) {
                throw new IllegalArgumentException("Bad value in PersistableBundle key=" + key +
                        " value=" + value);
            }
        }
    }

    /**
     * Make a PersistableBundle for a single key/value pair.
     *
     * @hide
     */
    public static PersistableBundle forPair(String key, String value) {
        PersistableBundle b = new PersistableBundle(1);
        b.putString(key, value);
        return b;
    }

    /**
     * @hide
     */
    @Override
    public String getPairValue() {
        return super.getPairValue();
    }

    /**
     * Changes the ClassLoader this PersistableBundle uses when instantiating objects.
     *
     * @param loader An explicit ClassLoader to use when instantiating objects
     * inside of the PersistableBundle.
     */
    @Override
    public void setClassLoader(ClassLoader loader) {
        super.setClassLoader(loader);
    }

    /**
     * Return the ClassLoader currently associated with this PersistableBundle.
     */
    @Override
    public ClassLoader getClassLoader() {
        return super.getClassLoader();
    }

    /**
     * Clones the current PersistableBundle. The internal map is cloned, but the keys and
     * values to which it refers are copied by reference.
     */
    @Override
    public Object clone() {
        return new PersistableBundle(this);
    }

    /**
     * @hide
     */
    @Override
    public boolean isParcelled() {
        return super.isParcelled();
    }

    /**
     * Returns the number of mappings contained in this PersistableBundle.
     *
     * @return the number of mappings as an int.
     */
    @Override
    public int size() {
        return super.size();
    }

    /**
     * Returns true if the mapping of this PersistableBundle is empty, false otherwise.
     */
    @Override
    public boolean isEmpty() {
        return super.isEmpty();
    }

    /**
     * Removes all elements from the mapping of this PersistableBundle.
     */
    @Override
    public void clear() {
        super.clear();
    }

    /**
     * Returns true if the given key is contained in the mapping
     * of this PersistableBundle.
     *
     * @param key a String key
     * @return true if the key is part of the mapping, false otherwise
     */
    @Override
    public boolean containsKey(String key) {
        return super.containsKey(key);
    }

    /**
     * Returns the entry with the given key as an object.
     *
     * @param key a String key
     * @return an Object, or null
     */
    @Override
    public Object get(String key) {
        return super.get(key);
    }

    /**
     * Removes any entry with the given key from the mapping of this PersistableBundle.
     *
     * @param key a String key
     */
    @Override
    public void remove(String key) {
        super.remove(key);
    }

    /**
     * Inserts all mappings from the given PersistableBundle into this Bundle.
     *
     * @param bundle a PersistableBundle
     */
    @Override
    public void putAll(PersistableBundle bundle) {
        super.putAll(bundle);
    }

    /**
     * Returns a Set containing the Strings used as keys in this PersistableBundle.
     *
     * @return a Set of String keys
     */
    @Override
    public Set<String> keySet() {
        return super.keySet();
    }

    /**
     * Inserts an int value into the mapping of this PersistableBundle, replacing
     * any existing value for the given key.
     *
     * @param key a String, or null
     * @param value an int, or null
     */
    @Override
    public void putInt(String key, int value) {
        super.putInt(key, value);
    }

    /**
     * Inserts a long value into the mapping of this PersistableBundle, replacing
     * any existing value for the given key.
     *
     * @param key a String, or null
     * @param value a long
     */
    @Override
    public void putLong(String key, long value) {
        super.putLong(key, value);
    }

    /**
     * Inserts a double value into the mapping of this PersistableBundle, replacing
     * any existing value for the given key.
     *
     * @param key a String, or null
     * @param value a double
     */
    @Override
    public void putDouble(String key, double value) {
        super.putDouble(key, value);
    }

    /**
     * Inserts a String value into the mapping of this PersistableBundle, replacing
     * any existing value for the given key.  Either key or value may be null.
     *
     * @param key a String, or null
     * @param value a String, or null
     */
    @Override
    public void putString(String key, String value) {
        super.putString(key, value);
    }

    /**
     * Inserts an int array value into the mapping of this PersistableBundle, replacing
     * any existing value for the given key.  Either key or value may be null.
     *
     * @param key a String, or null
     * @param value an int array object, or null
     */
    @Override
    public void putIntArray(String key, int[] value) {
        super.putIntArray(key, value);
    }

    /**
     * Inserts a long array value into the mapping of this PersistableBundle, replacing
     * any existing value for the given key.  Either key or value may be null.
     *
     * @param key a String, or null
     * @param value a long array object, or null
     */
    @Override
    public void putLongArray(String key, long[] value) {
        super.putLongArray(key, value);
    }

    /**
     * Inserts a double array value into the mapping of this PersistableBundle, replacing
     * any existing value for the given key.  Either key or value may be null.
     *
     * @param key a String, or null
     * @param value a double array object, or null
     */
    @Override
    public void putDoubleArray(String key, double[] value) {
        super.putDoubleArray(key, value);
    }

    /**
     * Inserts a String array value into the mapping of this PersistableBundle, replacing
     * any existing value for the given key.  Either key or value may be null.
     *
     * @param key a String, or null
     * @param value a String array object, or null
     */
    @Override
    public void putStringArray(String key, String[] value) {
        super.putStringArray(key, value);
    }

    /**
     * Inserts a PersistableBundle value into the mapping of this Bundle, replacing
     * any existing value for the given key.  Either key or value may be null.
     *
     * @param key a String, or null
     * @param value a Bundle object, or null
     */
    @Override
    public void putPersistableBundle(String key, PersistableBundle value) {
        super.putPersistableBundle(key, value);
    }

    /**
     * Returns the value associated with the given key, or 0 if
     * no mapping of the desired type exists for the given key.
     *
     * @param key a String
     * @return an int value
     */
    @Override
    public int getInt(String key) {
        return super.getInt(key);
    }

    /**
     * Returns the value associated with the given key, or defaultValue if
     * no mapping of the desired type exists for the given key.
     *
     * @param key a String
     * @param defaultValue Value to return if key does not exist
     * @return an int value
     */
    @Override
    public int getInt(String key, int defaultValue) {
        return super.getInt(key, defaultValue);
    }

    /**
     * Returns the value associated with the given key, or 0L if
     * no mapping of the desired type exists for the given key.
     *
     * @param key a String
     * @return a long value
     */
    @Override
    public long getLong(String key) {
        return super.getLong(key);
    }

    /**
     * Returns the value associated with the given key, or defaultValue if
     * no mapping of the desired type exists for the given key.
     *
     * @param key a String
     * @param defaultValue Value to return if key does not exist
     * @return a long value
     */
    @Override
    public long getLong(String key, long defaultValue) {
        return super.getLong(key, defaultValue);
    }

    /**
     * Returns the value associated with the given key, or 0.0 if
     * no mapping of the desired type exists for the given key.
     *
     * @param key a String
     * @return a double value
     */
    @Override
    public double getDouble(String key) {
        return super.getDouble(key);
    }

    /**
     * Returns the value associated with the given key, or defaultValue if
     * no mapping of the desired type exists for the given key.
     *
     * @param key a String
     * @param defaultValue Value to return if key does not exist
     * @return a double value
     */
    @Override
    public double getDouble(String key, double defaultValue) {
        return super.getDouble(key, defaultValue);
    }

    /**
     * Returns the value associated with the given key, or null if
     * no mapping of the desired type exists for the given key or a null
     * value is explicitly associated with the key.
     *
     * @param key a String, or null
     * @return a String value, or null
     */
    @Override
    public String getString(String key) {
        return super.getString(key);
    }

    /**
     * Returns the value associated with the given key, or defaultValue if
     * no mapping of the desired type exists for the given key.
     *
     * @param key a String, or null
     * @param defaultValue Value to return if key does not exist
     * @return the String value associated with the given key, or defaultValue
     *     if no valid String object is currently mapped to that key.
     */
    @Override
    public String getString(String key, String defaultValue) {
        return super.getString(key, defaultValue);
    }

    /**
     * Returns the value associated with the given key, or null if
     * no mapping of the desired type exists for the given key or a null
     * value is explicitly associated with the key.
     *
     * @param key a String, or null
     * @return a Bundle value, or null
     */
    @Override
    public PersistableBundle getPersistableBundle(String key) {
        return super.getPersistableBundle(key);
    }

    /**
     * Returns the value associated with the given key, or null if
     * no mapping of the desired type exists for the given key or a null
     * value is explicitly associated with the key.
     *
     * @param key a String, or null
     * @return an int[] value, or null
     */
    @Override
    public int[] getIntArray(String key) {
        return super.getIntArray(key);
    }

    /**
     * Returns the value associated with the given key, or null if
     * no mapping of the desired type exists for the given key or a null
     * value is explicitly associated with the key.
     *
     * @param key a String, or null
     * @return a long[] value, or null
     */
    @Override
    public long[] getLongArray(String key) {
        return super.getLongArray(key);
    }

    /**
     * Returns the value associated with the given key, or null if
     * no mapping of the desired type exists for the given key or a null
     * value is explicitly associated with the key.
     *
     * @param key a String, or null
     * @return a double[] value, or null
     */
    @Override
    public double[] getDoubleArray(String key) {
        return super.getDoubleArray(key);
    }

    /**
     * Returns the value associated with the given key, or null if
     * no mapping of the desired type exists for the given key or a null
     * value is explicitly associated with the key.
     *
     * @param key a String, or null
     * @return a String[] value, or null
     */
    @Override
    public String[] getStringArray(String key) {
        return super.getStringArray(key);
    }

    public static final Parcelable.Creator<PersistableBundle> CREATOR =
            new Parcelable.Creator<PersistableBundle>() {
                @Override
                public PersistableBundle createFromParcel(Parcel in) {
                    return in.readPersistableBundle();
                }

                @Override
                public PersistableBundle[] newArray(int size) {
                    return new PersistableBundle[size];
                }
            };

    /**
     * Report the nature of this Parcelable's contents
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Writes the PersistableBundle contents to a Parcel, typically in order for
     * it to be passed through an IBinder connection.
     * @param parcel The parcel to copy this bundle to.
     */
    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        final boolean oldAllowFds = parcel.pushAllowFds(false);
        try {
            super.writeToParcelInner(parcel, flags);
        } finally {
            parcel.restoreAllowFds(oldAllowFds);
        }
    }

    /**
     * Reads the Parcel contents into this PersistableBundle, typically in order for
     * it to be passed through an IBinder connection.
     * @param parcel The parcel to overwrite this bundle from.
     */
    public void readFromParcel(Parcel parcel) {
        super.readFromParcelInner(parcel);
    }

    /** @hide */
    @Override
    public void writeUnknownObject(Object v, String name, XmlSerializer out)
            throws XmlPullParserException, IOException {
        if (v instanceof PersistableBundle) {
            out.startTag(null, TAG_PERSISTABLEMAP);
            out.attribute(null, "name", name);
            ((PersistableBundle) v).saveToXml(out);
            out.endTag(null, TAG_PERSISTABLEMAP);
        } else {
            throw new XmlPullParserException("Unknown Object o=" + v);
        }
    }

    /** @hide */
    public void saveToXml(XmlSerializer out) throws IOException, XmlPullParserException {
        unparcel();
        XmlUtils.writeMapXml(mMap, out, this);
    }

    /** @hide */
    static class MyReadMapCallback implements  XmlUtils.ReadMapCallback {
        @Override
        public Object readThisUnknownObjectXml(XmlPullParser in, String tag)
                throws XmlPullParserException, IOException {
            if (TAG_PERSISTABLEMAP.equals(tag)) {
                return restoreFromXml(in);
            }
            throw new XmlPullParserException("Unknown tag=" + tag);
        }
    }

    /**
     * @hide
     */
    public static PersistableBundle restoreFromXml(XmlPullParser in) throws IOException,
            XmlPullParserException {
        final int outerDepth = in.getDepth();
        final String startTag = in.getName();
        final String[] tagName = new String[1];
        int event;
        while (((event = in.next()) != XmlPullParser.END_DOCUMENT) &&
                (event != XmlPullParser.END_TAG || in.getDepth() < outerDepth)) {
            if (event == XmlPullParser.START_TAG) {
                return new PersistableBundle((Map<String, Object>)
                        XmlUtils.readThisMapXml(in, startTag, tagName, new MyReadMapCallback()));
            }
        }
        return EMPTY;
    }

    @Override
    synchronized public String toString() {
        if (mParcelledData != null) {
            if (mParcelledData == EMPTY_PARCEL) {
                return "PersistableBundle[EMPTY_PARCEL]";
            } else {
                return "PersistableBundle[mParcelledData.dataSize=" +
                        mParcelledData.dataSize() + "]";
            }
        }
        return "PersistableBundle[" + mMap.toString() + "]";
    }

}
