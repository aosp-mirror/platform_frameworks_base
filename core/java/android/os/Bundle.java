/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.annotation.Nullable;
import android.util.ArrayMap;
import android.util.Size;
import android.util.SizeF;
import android.util.SparseArray;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * A mapping from String keys to various {@link Parcelable} values.
 *
 * @see PersistableBundle
 */
public final class Bundle extends BaseBundle implements Cloneable, Parcelable {
    private static final int FLAG_HAS_FDS = 1 << 8;
    private static final int FLAG_HAS_FDS_KNOWN = 1 << 9;
    private static final int FLAG_ALLOW_FDS = 1 << 10;

    public static final Bundle EMPTY;

    static {
        EMPTY = new Bundle();
        EMPTY.mMap = ArrayMap.EMPTY;
    }

    /**
     * Constructs a new, empty Bundle.
     */
    public Bundle() {
        super();
        mFlags = FLAG_HAS_FDS_KNOWN | FLAG_ALLOW_FDS;
    }

    /**
     * Constructs a Bundle whose data is stored as a Parcel.  The data
     * will be unparcelled on first contact, using the assigned ClassLoader.
     *
     * @param parcelledData a Parcel containing a Bundle
     */
    Bundle(Parcel parcelledData) {
        super(parcelledData);
        mFlags = FLAG_HAS_FDS_KNOWN | FLAG_ALLOW_FDS;
        if (mParcelledData.hasFileDescriptors()) {
            mFlags |= FLAG_HAS_FDS;
        }
    }

    /* package */ Bundle(Parcel parcelledData, int length) {
        super(parcelledData, length);
        mFlags = FLAG_HAS_FDS_KNOWN | FLAG_ALLOW_FDS;
        if (mParcelledData.hasFileDescriptors()) {
            mFlags |= FLAG_HAS_FDS;
        }
    }

    /**
     * Constructs a new, empty Bundle that uses a specific ClassLoader for
     * instantiating Parcelable and Serializable objects.
     *
     * @param loader An explicit ClassLoader to use when instantiating objects
     * inside of the Bundle.
     */
    public Bundle(ClassLoader loader) {
        super(loader);
        mFlags = FLAG_HAS_FDS_KNOWN | FLAG_ALLOW_FDS;
    }

    /**
     * Constructs a new, empty Bundle sized to hold the given number of
     * elements. The Bundle will grow as needed.
     *
     * @param capacity the initial capacity of the Bundle
     */
    public Bundle(int capacity) {
        super(capacity);
        mFlags = FLAG_HAS_FDS_KNOWN | FLAG_ALLOW_FDS;
    }

    /**
     * Constructs a Bundle containing a copy of the mappings from the given
     * Bundle.
     *
     * @param b a Bundle to be copied.
     */
    public Bundle(Bundle b) {
        super(b);
        mFlags = b.mFlags;
    }

    /**
     * Constructs a Bundle containing a copy of the mappings from the given
     * PersistableBundle.
     *
     * @param b a Bundle to be copied.
     */
    public Bundle(PersistableBundle b) {
        super(b);
        mFlags = FLAG_HAS_FDS_KNOWN | FLAG_ALLOW_FDS;
    }

    /**
     * Make a Bundle for a single key/value pair.
     *
     * @hide
     */
    public static Bundle forPair(String key, String value) {
        Bundle b = new Bundle(1);
        b.putString(key, value);
        return b;
    }

    /**
     * Changes the ClassLoader this Bundle uses when instantiating objects.
     *
     * @param loader An explicit ClassLoader to use when instantiating objects
     * inside of the Bundle.
     */
    @Override
    public void setClassLoader(ClassLoader loader) {
        super.setClassLoader(loader);
    }

    /**
     * Return the ClassLoader currently associated with this Bundle.
     */
    @Override
    public ClassLoader getClassLoader() {
        return super.getClassLoader();
    }

    /** {@hide} */
    public boolean setAllowFds(boolean allowFds) {
        final boolean orig = (mFlags & FLAG_ALLOW_FDS) != 0;
        if (allowFds) {
            mFlags |= FLAG_ALLOW_FDS;
        } else {
            mFlags &= ~FLAG_ALLOW_FDS;
        }
        return orig;
    }

    /**
     * Mark if this Bundle is okay to "defuse." That is, it's okay for system
     * processes to ignore any {@link BadParcelableException} encountered when
     * unparceling it, leaving an empty bundle in its place.
     * <p>
     * This should <em>only</em> be set when the Bundle reaches its final
     * destination, otherwise a system process may clobber contents that were
     * destined for an app that could have unparceled them.
     *
     * @hide
     */
    public void setDefusable(boolean defusable) {
        if (defusable) {
            mFlags |= FLAG_DEFUSABLE;
        } else {
            mFlags &= ~FLAG_DEFUSABLE;
        }
    }

    /** {@hide} */
    public static Bundle setDefusable(Bundle bundle, boolean defusable) {
        if (bundle != null) {
            bundle.setDefusable(defusable);
        }
        return bundle;
    }

    /**
     * Clones the current Bundle. The internal map is cloned, but the keys and
     * values to which it refers are copied by reference.
     */
    @Override
    public Object clone() {
        return new Bundle(this);
    }

    /**
     * Removes all elements from the mapping of this Bundle.
     */
    @Override
    public void clear() {
        super.clear();
        mFlags = FLAG_HAS_FDS_KNOWN | FLAG_ALLOW_FDS;
    }

    /**
     * Removes any entry with the given key from the mapping of this Bundle.
     *
     * @param key a String key
     */
    public void remove(String key) {
        super.remove(key);
        if ((mFlags & FLAG_HAS_FDS) != 0) {
            mFlags &= ~FLAG_HAS_FDS_KNOWN;
        }
    }

    /**
     * Inserts all mappings from the given Bundle into this Bundle.
     *
     * @param bundle a Bundle
     */
    public void putAll(Bundle bundle) {
        unparcel();
        bundle.unparcel();
        mMap.putAll(bundle.mMap);

        // FD state is now known if and only if both bundles already knew
        if ((bundle.mFlags & FLAG_HAS_FDS) != 0) {
            mFlags |= FLAG_HAS_FDS;
        }
        if ((bundle.mFlags & FLAG_HAS_FDS_KNOWN) == 0) {
            mFlags &= ~FLAG_HAS_FDS_KNOWN;
        }
    }

    /**
     * Reports whether the bundle contains any parcelled file descriptors.
     */
    public boolean hasFileDescriptors() {
        if ((mFlags & FLAG_HAS_FDS_KNOWN) == 0) {
            boolean fdFound = false;    // keep going until we find one or run out of data

            if (mParcelledData != null) {
                if (mParcelledData.hasFileDescriptors()) {
                    fdFound = true;
                }
            } else {
                // It's been unparcelled, so we need to walk the map
                for (int i=mMap.size()-1; i>=0; i--) {
                    Object obj = mMap.valueAt(i);
                    if (obj instanceof Parcelable) {
                        if ((((Parcelable)obj).describeContents()
                                & Parcelable.CONTENTS_FILE_DESCRIPTOR) != 0) {
                            fdFound = true;
                            break;
                        }
                    } else if (obj instanceof Parcelable[]) {
                        Parcelable[] array = (Parcelable[]) obj;
                        for (int n = array.length - 1; n >= 0; n--) {
                            Parcelable p = array[n];
                            if (p != null && ((p.describeContents()
                                    & Parcelable.CONTENTS_FILE_DESCRIPTOR) != 0)) {
                                fdFound = true;
                                break;
                            }
                        }
                    } else if (obj instanceof SparseArray) {
                        SparseArray<? extends Parcelable> array =
                                (SparseArray<? extends Parcelable>) obj;
                        for (int n = array.size() - 1; n >= 0; n--) {
                            Parcelable p = array.valueAt(n);
                            if (p != null && (p.describeContents()
                                    & Parcelable.CONTENTS_FILE_DESCRIPTOR) != 0) {
                                fdFound = true;
                                break;
                            }
                        }
                    } else if (obj instanceof ArrayList) {
                        ArrayList array = (ArrayList) obj;
                        // an ArrayList here might contain either Strings or
                        // Parcelables; only look inside for Parcelables
                        if (!array.isEmpty() && (array.get(0) instanceof Parcelable)) {
                            for (int n = array.size() - 1; n >= 0; n--) {
                                Parcelable p = (Parcelable) array.get(n);
                                if (p != null && ((p.describeContents()
                                        & Parcelable.CONTENTS_FILE_DESCRIPTOR) != 0)) {
                                    fdFound = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            if (fdFound) {
                mFlags |= FLAG_HAS_FDS;
            } else {
                mFlags &= ~FLAG_HAS_FDS;
            }
            mFlags |= FLAG_HAS_FDS_KNOWN;
        }
        return (mFlags & FLAG_HAS_FDS) != 0;
    }

    /**
     * Filter values in Bundle to only basic types.
     * @hide
     */
    public Bundle filterValues() {
        unparcel();
        Bundle bundle = this;
        if (mMap != null) {
            ArrayMap<String, Object> map = mMap;
            for (int i = map.size() - 1; i >= 0; i--) {
                Object value = map.valueAt(i);
                if (PersistableBundle.isValidType(value)) {
                    continue;
                }
                if (value instanceof Bundle) {
                    Bundle newBundle = ((Bundle)value).filterValues();
                    if (newBundle != value) {
                        if (map == mMap) {
                            // The filter had to generate a new bundle, but we have not yet
                            // created a new one here.  Do that now.
                            bundle = new Bundle(this);
                            // Note the ArrayMap<> constructor is guaranteed to generate
                            // a new object with items in the same order as the original.
                            map = bundle.mMap;
                        }
                        // Replace this current entry with the new child bundle.
                        map.setValueAt(i, newBundle);
                    }
                    continue;
                }
                if (value.getClass().getName().startsWith("android.")) {
                    continue;
                }
                if (map == mMap) {
                    // This is the first time we have had to remove something, that means we
                    // need to switch to a new Bundle.
                    bundle = new Bundle(this);
                    // Note the ArrayMap<> constructor is guaranteed to generate
                    // a new object with items in the same order as the original.
                    map = bundle.mMap;
                }
                map.removeAt(i);
            }
        }
        mFlags |= FLAG_HAS_FDS_KNOWN;
        mFlags &= ~FLAG_HAS_FDS;
        return bundle;
    }

    /**
     * Inserts a byte value into the mapping of this Bundle, replacing
     * any existing value for the given key.
     *
     * @param key a String, or null
     * @param value a byte
     */
    @Override
    public void putByte(@Nullable String key, byte value) {
        super.putByte(key, value);
    }

    /**
     * Inserts a char value into the mapping of this Bundle, replacing
     * any existing value for the given key.
     *
     * @param key a String, or null
     * @param value a char
     */
    @Override
    public void putChar(@Nullable String key, char value) {
        super.putChar(key, value);
    }

    /**
     * Inserts a short value into the mapping of this Bundle, replacing
     * any existing value for the given key.
     *
     * @param key a String, or null
     * @param value a short
     */
    @Override
    public void putShort(@Nullable String key, short value) {
        super.putShort(key, value);
    }

    /**
     * Inserts a float value into the mapping of this Bundle, replacing
     * any existing value for the given key.
     *
     * @param key a String, or null
     * @param value a float
     */
    @Override
    public void putFloat(@Nullable String key, float value) {
        super.putFloat(key, value);
    }

    /**
     * Inserts a CharSequence value into the mapping of this Bundle, replacing
     * any existing value for the given key.  Either key or value may be null.
     *
     * @param key a String, or null
     * @param value a CharSequence, or null
     */
    @Override
    public void putCharSequence(@Nullable String key, @Nullable CharSequence value) {
        super.putCharSequence(key, value);
    }

    /**
     * Inserts a Parcelable value into the mapping of this Bundle, replacing
     * any existing value for the given key.  Either key or value may be null.
     *
     * @param key a String, or null
     * @param value a Parcelable object, or null
     */
    public void putParcelable(@Nullable String key, @Nullable Parcelable value) {
        unparcel();
        mMap.put(key, value);
        mFlags &= ~FLAG_HAS_FDS_KNOWN;
    }

    /**
     * Inserts a Size value into the mapping of this Bundle, replacing
     * any existing value for the given key.  Either key or value may be null.
     *
     * @param key a String, or null
     * @param value a Size object, or null
     */
    public void putSize(@Nullable String key, @Nullable Size value) {
        unparcel();
        mMap.put(key, value);
    }

    /**
     * Inserts a SizeF value into the mapping of this Bundle, replacing
     * any existing value for the given key.  Either key or value may be null.
     *
     * @param key a String, or null
     * @param value a SizeF object, or null
     */
    public void putSizeF(@Nullable String key, @Nullable SizeF value) {
        unparcel();
        mMap.put(key, value);
    }

    /**
     * Inserts an array of Parcelable values into the mapping of this Bundle,
     * replacing any existing value for the given key.  Either key or value may
     * be null.
     *
     * @param key a String, or null
     * @param value an array of Parcelable objects, or null
     */
    public void putParcelableArray(@Nullable String key, @Nullable Parcelable[] value) {
        unparcel();
        mMap.put(key, value);
        mFlags &= ~FLAG_HAS_FDS_KNOWN;
    }

    /**
     * Inserts a List of Parcelable values into the mapping of this Bundle,
     * replacing any existing value for the given key.  Either key or value may
     * be null.
     *
     * @param key a String, or null
     * @param value an ArrayList of Parcelable objects, or null
     */
    public void putParcelableArrayList(@Nullable String key,
            @Nullable ArrayList<? extends Parcelable> value) {
        unparcel();
        mMap.put(key, value);
        mFlags &= ~FLAG_HAS_FDS_KNOWN;
    }

    /** {@hide} */
    public void putParcelableList(String key, List<? extends Parcelable> value) {
        unparcel();
        mMap.put(key, value);
        mFlags &= ~FLAG_HAS_FDS_KNOWN;
    }

    /**
     * Inserts a SparceArray of Parcelable values into the mapping of this
     * Bundle, replacing any existing value for the given key.  Either key
     * or value may be null.
     *
     * @param key a String, or null
     * @param value a SparseArray of Parcelable objects, or null
     */
    public void putSparseParcelableArray(@Nullable String key,
            @Nullable SparseArray<? extends Parcelable> value) {
        unparcel();
        mMap.put(key, value);
        mFlags &= ~FLAG_HAS_FDS_KNOWN;
    }

    /**
     * Inserts an ArrayList<Integer> value into the mapping of this Bundle, replacing
     * any existing value for the given key.  Either key or value may be null.
     *
     * @param key a String, or null
     * @param value an ArrayList<Integer> object, or null
     */
    @Override
    public void putIntegerArrayList(@Nullable String key, @Nullable ArrayList<Integer> value) {
        super.putIntegerArrayList(key, value);
    }

    /**
     * Inserts an ArrayList<String> value into the mapping of this Bundle, replacing
     * any existing value for the given key.  Either key or value may be null.
     *
     * @param key a String, or null
     * @param value an ArrayList<String> object, or null
     */
    @Override
    public void putStringArrayList(@Nullable String key, @Nullable ArrayList<String> value) {
        super.putStringArrayList(key, value);
    }

    /**
     * Inserts an ArrayList<CharSequence> value into the mapping of this Bundle, replacing
     * any existing value for the given key.  Either key or value may be null.
     *
     * @param key a String, or null
     * @param value an ArrayList<CharSequence> object, or null
     */
    @Override
    public void putCharSequenceArrayList(@Nullable String key,
            @Nullable ArrayList<CharSequence> value) {
        super.putCharSequenceArrayList(key, value);
    }

    /**
     * Inserts a Serializable value into the mapping of this Bundle, replacing
     * any existing value for the given key.  Either key or value may be null.
     *
     * @param key a String, or null
     * @param value a Serializable object, or null
     */
    @Override
    public void putSerializable(@Nullable String key, @Nullable Serializable value) {
        super.putSerializable(key, value);
    }

    /**
     * Inserts a byte array value into the mapping of this Bundle, replacing
     * any existing value for the given key.  Either key or value may be null.
     *
     * @param key a String, or null
     * @param value a byte array object, or null
     */
    @Override
    public void putByteArray(@Nullable String key, @Nullable byte[] value) {
        super.putByteArray(key, value);
    }

    /**
     * Inserts a short array value into the mapping of this Bundle, replacing
     * any existing value for the given key.  Either key or value may be null.
     *
     * @param key a String, or null
     * @param value a short array object, or null
     */
    @Override
    public void putShortArray(@Nullable String key, @Nullable short[] value) {
        super.putShortArray(key, value);
    }

    /**
     * Inserts a char array value into the mapping of this Bundle, replacing
     * any existing value for the given key.  Either key or value may be null.
     *
     * @param key a String, or null
     * @param value a char array object, or null
     */
    @Override
    public void putCharArray(@Nullable String key, @Nullable char[] value) {
        super.putCharArray(key, value);
    }

    /**
     * Inserts a float array value into the mapping of this Bundle, replacing
     * any existing value for the given key.  Either key or value may be null.
     *
     * @param key a String, or null
     * @param value a float array object, or null
     */
    @Override
    public void putFloatArray(@Nullable String key, @Nullable float[] value) {
        super.putFloatArray(key, value);
    }

    /**
     * Inserts a CharSequence array value into the mapping of this Bundle, replacing
     * any existing value for the given key.  Either key or value may be null.
     *
     * @param key a String, or null
     * @param value a CharSequence array object, or null
     */
    @Override
    public void putCharSequenceArray(@Nullable String key, @Nullable CharSequence[] value) {
        super.putCharSequenceArray(key, value);
    }

    /**
     * Inserts a Bundle value into the mapping of this Bundle, replacing
     * any existing value for the given key.  Either key or value may be null.
     *
     * @param key a String, or null
     * @param value a Bundle object, or null
     */
    public void putBundle(@Nullable String key, @Nullable Bundle value) {
        unparcel();
        mMap.put(key, value);
    }

    /**
     * Inserts an {@link IBinder} value into the mapping of this Bundle, replacing
     * any existing value for the given key.  Either key or value may be null.
     *
     * <p class="note">You should be very careful when using this function.  In many
     * places where Bundles are used (such as inside of Intent objects), the Bundle
     * can live longer inside of another process than the process that had originally
     * created it.  In that case, the IBinder you supply here will become invalid
     * when your process goes away, and no longer usable, even if a new process is
     * created for you later on.</p>
     *
     * @param key a String, or null
     * @param value an IBinder object, or null
     */
    public void putBinder(@Nullable String key, @Nullable IBinder value) {
        unparcel();
        mMap.put(key, value);
    }

    /**
     * Inserts an IBinder value into the mapping of this Bundle, replacing
     * any existing value for the given key.  Either key or value may be null.
     *
     * @param key a String, or null
     * @param value an IBinder object, or null
     *
     * @deprecated
     * @hide This is the old name of the function.
     */
    @Deprecated
    public void putIBinder(@Nullable String key, @Nullable IBinder value) {
        unparcel();
        mMap.put(key, value);
    }

    /**
     * Returns the value associated with the given key, or (byte) 0 if
     * no mapping of the desired type exists for the given key.
     *
     * @param key a String
     * @return a byte value
     */
    @Override
    public byte getByte(String key) {
        return super.getByte(key);
    }

    /**
     * Returns the value associated with the given key, or defaultValue if
     * no mapping of the desired type exists for the given key.
     *
     * @param key a String
     * @param defaultValue Value to return if key does not exist
     * @return a byte value
     */
    @Override
    public Byte getByte(String key, byte defaultValue) {
        return super.getByte(key, defaultValue);
    }

    /**
     * Returns the value associated with the given key, or (char) 0 if
     * no mapping of the desired type exists for the given key.
     *
     * @param key a String
     * @return a char value
     */
    @Override
    public char getChar(String key) {
        return super.getChar(key);
    }

    /**
     * Returns the value associated with the given key, or defaultValue if
     * no mapping of the desired type exists for the given key.
     *
     * @param key a String
     * @param defaultValue Value to return if key does not exist
     * @return a char value
     */
    @Override
    public char getChar(String key, char defaultValue) {
        return super.getChar(key, defaultValue);
    }

    /**
     * Returns the value associated with the given key, or (short) 0 if
     * no mapping of the desired type exists for the given key.
     *
     * @param key a String
     * @return a short value
     */
    @Override
    public short getShort(String key) {
        return super.getShort(key);
    }

    /**
     * Returns the value associated with the given key, or defaultValue if
     * no mapping of the desired type exists for the given key.
     *
     * @param key a String
     * @param defaultValue Value to return if key does not exist
     * @return a short value
     */
    @Override
    public short getShort(String key, short defaultValue) {
        return super.getShort(key, defaultValue);
    }

    /**
     * Returns the value associated with the given key, or 0.0f if
     * no mapping of the desired type exists for the given key.
     *
     * @param key a String
     * @return a float value
     */
    @Override
    public float getFloat(String key) {
        return super.getFloat(key);
    }

    /**
     * Returns the value associated with the given key, or defaultValue if
     * no mapping of the desired type exists for the given key.
     *
     * @param key a String
     * @param defaultValue Value to return if key does not exist
     * @return a float value
     */
    @Override
    public float getFloat(String key, float defaultValue) {
        return super.getFloat(key, defaultValue);
    }

    /**
     * Returns the value associated with the given key, or null if
     * no mapping of the desired type exists for the given key or a null
     * value is explicitly associated with the key.
     *
     * @param key a String, or null
     * @return a CharSequence value, or null
     */
    @Override
    @Nullable
    public CharSequence getCharSequence(@Nullable String key) {
        return super.getCharSequence(key);
    }

    /**
     * Returns the value associated with the given key, or defaultValue if
     * no mapping of the desired type exists for the given key or if a null
     * value is explicitly associatd with the given key.
     *
     * @param key a String, or null
     * @param defaultValue Value to return if key does not exist or if a null
     *     value is associated with the given key.
     * @return the CharSequence value associated with the given key, or defaultValue
     *     if no valid CharSequence object is currently mapped to that key.
     */
    @Override
    public CharSequence getCharSequence(@Nullable String key, CharSequence defaultValue) {
        return super.getCharSequence(key, defaultValue);
    }

    /**
     * Returns the value associated with the given key, or null if
     * no mapping of the desired type exists for the given key or a null
     * value is explicitly associated with the key.
     *
     * @param key a String, or null
     * @return a Size value, or null
     */
    @Nullable
    public Size getSize(@Nullable String key) {
        unparcel();
        final Object o = mMap.get(key);
        try {
            return (Size) o;
        } catch (ClassCastException e) {
            typeWarning(key, o, "Size", e);
            return null;
        }
    }

    /**
     * Returns the value associated with the given key, or null if
     * no mapping of the desired type exists for the given key or a null
     * value is explicitly associated with the key.
     *
     * @param key a String, or null
     * @return a Size value, or null
     */
    @Nullable
    public SizeF getSizeF(@Nullable String key) {
        unparcel();
        final Object o = mMap.get(key);
        try {
            return (SizeF) o;
        } catch (ClassCastException e) {
            typeWarning(key, o, "SizeF", e);
            return null;
        }
    }

    /**
     * Returns the value associated with the given key, or null if
     * no mapping of the desired type exists for the given key or a null
     * value is explicitly associated with the key.
     *
     * @param key a String, or null
     * @return a Bundle value, or null
     */
    @Nullable
    public Bundle getBundle(@Nullable String key) {
        unparcel();
        Object o = mMap.get(key);
        if (o == null) {
            return null;
        }
        try {
            return (Bundle) o;
        } catch (ClassCastException e) {
            typeWarning(key, o, "Bundle", e);
            return null;
        }
    }

    /**
     * Returns the value associated with the given key, or null if
     * no mapping of the desired type exists for the given key or a null
     * value is explicitly associated with the key.
     *
     * @param key a String, or null
     * @return a Parcelable value, or null
     */
    @Nullable
    public <T extends Parcelable> T getParcelable(@Nullable String key) {
        unparcel();
        Object o = mMap.get(key);
        if (o == null) {
            return null;
        }
        try {
            return (T) o;
        } catch (ClassCastException e) {
            typeWarning(key, o, "Parcelable", e);
            return null;
        }
    }

    /**
     * Returns the value associated with the given key, or null if
     * no mapping of the desired type exists for the given key or a null
     * value is explicitly associated with the key.
     *
     * @param key a String, or null
     * @return a Parcelable[] value, or null
     */
    @Nullable
    public Parcelable[] getParcelableArray(@Nullable String key) {
        unparcel();
        Object o = mMap.get(key);
        if (o == null) {
            return null;
        }
        try {
            return (Parcelable[]) o;
        } catch (ClassCastException e) {
            typeWarning(key, o, "Parcelable[]", e);
            return null;
        }
    }

    /**
     * Returns the value associated with the given key, or null if
     * no mapping of the desired type exists for the given key or a null
     * value is explicitly associated with the key.
     *
     * @param key a String, or null
     * @return an ArrayList<T> value, or null
     */
    @Nullable
    public <T extends Parcelable> ArrayList<T> getParcelableArrayList(@Nullable String key) {
        unparcel();
        Object o = mMap.get(key);
        if (o == null) {
            return null;
        }
        try {
            return (ArrayList<T>) o;
        } catch (ClassCastException e) {
            typeWarning(key, o, "ArrayList", e);
            return null;
        }
    }

    /**
     * Returns the value associated with the given key, or null if
     * no mapping of the desired type exists for the given key or a null
     * value is explicitly associated with the key.
     *
     * @param key a String, or null
     *
     * @return a SparseArray of T values, or null
     */
    @Nullable
    public <T extends Parcelable> SparseArray<T> getSparseParcelableArray(@Nullable String key) {
        unparcel();
        Object o = mMap.get(key);
        if (o == null) {
            return null;
        }
        try {
            return (SparseArray<T>) o;
        } catch (ClassCastException e) {
            typeWarning(key, o, "SparseArray", e);
            return null;
        }
    }

    /**
     * Returns the value associated with the given key, or null if
     * no mapping of the desired type exists for the given key or a null
     * value is explicitly associated with the key.
     *
     * @param key a String, or null
     * @return a Serializable value, or null
     */
    @Override
    @Nullable
    public Serializable getSerializable(@Nullable String key) {
        return super.getSerializable(key);
    }

    /**
     * Returns the value associated with the given key, or null if
     * no mapping of the desired type exists for the given key or a null
     * value is explicitly associated with the key.
     *
     * @param key a String, or null
     * @return an ArrayList<String> value, or null
     */
    @Override
    @Nullable
    public ArrayList<Integer> getIntegerArrayList(@Nullable String key) {
        return super.getIntegerArrayList(key);
    }

    /**
     * Returns the value associated with the given key, or null if
     * no mapping of the desired type exists for the given key or a null
     * value is explicitly associated with the key.
     *
     * @param key a String, or null
     * @return an ArrayList<String> value, or null
     */
    @Override
    @Nullable
    public ArrayList<String> getStringArrayList(@Nullable String key) {
        return super.getStringArrayList(key);
    }

    /**
     * Returns the value associated with the given key, or null if
     * no mapping of the desired type exists for the given key or a null
     * value is explicitly associated with the key.
     *
     * @param key a String, or null
     * @return an ArrayList<CharSequence> value, or null
     */
    @Override
    @Nullable
    public ArrayList<CharSequence> getCharSequenceArrayList(@Nullable String key) {
        return super.getCharSequenceArrayList(key);
    }

    /**
     * Returns the value associated with the given key, or null if
     * no mapping of the desired type exists for the given key or a null
     * value is explicitly associated with the key.
     *
     * @param key a String, or null
     * @return a byte[] value, or null
     */
    @Override
    @Nullable
    public byte[] getByteArray(@Nullable String key) {
        return super.getByteArray(key);
    }

    /**
     * Returns the value associated with the given key, or null if
     * no mapping of the desired type exists for the given key or a null
     * value is explicitly associated with the key.
     *
     * @param key a String, or null
     * @return a short[] value, or null
     */
    @Override
    @Nullable
    public short[] getShortArray(@Nullable String key) {
        return super.getShortArray(key);
    }

    /**
     * Returns the value associated with the given key, or null if
     * no mapping of the desired type exists for the given key or a null
     * value is explicitly associated with the key.
     *
     * @param key a String, or null
     * @return a char[] value, or null
     */
    @Override
    @Nullable
    public char[] getCharArray(@Nullable String key) {
        return super.getCharArray(key);
    }

    /**
     * Returns the value associated with the given key, or null if
     * no mapping of the desired type exists for the given key or a null
     * value is explicitly associated with the key.
     *
     * @param key a String, or null
     * @return a float[] value, or null
     */
    @Override
    @Nullable
    public float[] getFloatArray(@Nullable String key) {
        return super.getFloatArray(key);
    }

    /**
     * Returns the value associated with the given key, or null if
     * no mapping of the desired type exists for the given key or a null
     * value is explicitly associated with the key.
     *
     * @param key a String, or null
     * @return a CharSequence[] value, or null
     */
    @Override
    @Nullable
    public CharSequence[] getCharSequenceArray(@Nullable String key) {
        return super.getCharSequenceArray(key);
    }

    /**
     * Returns the value associated with the given key, or null if
     * no mapping of the desired type exists for the given key or a null
     * value is explicitly associated with the key.
     *
     * @param key a String, or null
     * @return an IBinder value, or null
     */
    @Nullable
    public IBinder getBinder(@Nullable String key) {
        unparcel();
        Object o = mMap.get(key);
        if (o == null) {
            return null;
        }
        try {
            return (IBinder) o;
        } catch (ClassCastException e) {
            typeWarning(key, o, "IBinder", e);
            return null;
        }
    }

    /**
     * Returns the value associated with the given key, or null if
     * no mapping of the desired type exists for the given key or a null
     * value is explicitly associated with the key.
     *
     * @param key a String, or null
     * @return an IBinder value, or null
     *
     * @deprecated
     * @hide This is the old name of the function.
     */
    @Deprecated
    @Nullable
    public IBinder getIBinder(@Nullable String key) {
        unparcel();
        Object o = mMap.get(key);
        if (o == null) {
            return null;
        }
        try {
            return (IBinder) o;
        } catch (ClassCastException e) {
            typeWarning(key, o, "IBinder", e);
            return null;
        }
    }

    public static final Parcelable.Creator<Bundle> CREATOR =
        new Parcelable.Creator<Bundle>() {
        @Override
        public Bundle createFromParcel(Parcel in) {
            return in.readBundle();
        }

        @Override
        public Bundle[] newArray(int size) {
            return new Bundle[size];
        }
    };

    /**
     * Report the nature of this Parcelable's contents
     */
    @Override
    public int describeContents() {
        int mask = 0;
        if (hasFileDescriptors()) {
            mask |= Parcelable.CONTENTS_FILE_DESCRIPTOR;
        }
        return mask;
    }

    /**
     * Writes the Bundle contents to a Parcel, typically in order for
     * it to be passed through an IBinder connection.
     * @param parcel The parcel to copy this bundle to.
     */
    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        final boolean oldAllowFds = parcel.pushAllowFds((mFlags & FLAG_ALLOW_FDS) != 0);
        try {
            super.writeToParcelInner(parcel, flags);
        } finally {
            parcel.restoreAllowFds(oldAllowFds);
        }
    }

    /**
     * Reads the Parcel contents into this Bundle, typically in order for
     * it to be passed through an IBinder connection.
     * @param parcel The parcel to overwrite this bundle from.
     */
    public void readFromParcel(Parcel parcel) {
        super.readFromParcelInner(parcel);
        mFlags = FLAG_HAS_FDS_KNOWN | FLAG_ALLOW_FDS;
        if (mParcelledData.hasFileDescriptors()) {
            mFlags |= FLAG_HAS_FDS;
        }
    }

    @Override
    public synchronized String toString() {
        if (mParcelledData != null) {
            if (isEmptyParcel()) {
                return "Bundle[EMPTY_PARCEL]";
            } else {
                return "Bundle[mParcelledData.dataSize=" +
                        mParcelledData.dataSize() + "]";
            }
        }
        return "Bundle[" + mMap.toString() + "]";
    }
}
