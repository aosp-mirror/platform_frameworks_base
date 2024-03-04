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

import static java.nio.charset.StandardCharsets.UTF_8;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.ArrayMap;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;

/**
 * A mapping from String keys to values of various types. The set of types
 * supported by this class is purposefully restricted to simple objects that can
 * safely be persisted to and restored from disk.
 *
 * <p><b>Warning:</b> Note that {@link PersistableBundle} is a lazy container and as such it does
 * NOT implement {@link #equals(Object)} or {@link #hashCode()}.
 *
 * @see Bundle
 */
public final class PersistableBundle extends BaseBundle implements Cloneable, Parcelable,
        XmlUtils.WriteMapCallback {
    private static final String TAG_PERSISTABLEMAP = "pbundle_as_map";

    /** An unmodifiable {@code PersistableBundle} that is always {@link #isEmpty() empty}. */
    public static final PersistableBundle EMPTY;

    static {
        EMPTY = new PersistableBundle();
        EMPTY.mMap = ArrayMap.EMPTY;
    }

    /** @hide */
    public static boolean isValidType(Object value) {
        return (value instanceof Integer) || (value instanceof Long) ||
                (value instanceof Double) || (value instanceof String) ||
                (value instanceof int[]) || (value instanceof long[]) ||
                (value instanceof double[]) || (value instanceof String[]) ||
                (value instanceof PersistableBundle) || (value == null) ||
                (value instanceof Boolean) || (value instanceof boolean[]);
    }

    /**
     * Constructs a new, empty PersistableBundle.
     */
    public PersistableBundle() {
        super();
        mFlags = FLAG_DEFUSABLE;
    }

    /**
     * Constructs a new, empty PersistableBundle sized to hold the given number of
     * elements. The PersistableBundle will grow as needed.
     *
     * @param capacity the initial capacity of the PersistableBundle
     */
    public PersistableBundle(int capacity) {
        super(capacity);
        mFlags = FLAG_DEFUSABLE;
    }

    /**
     * Constructs a PersistableBundle containing a copy of the mappings from the given
     * PersistableBundle.  Does only a shallow copy of the original PersistableBundle -- see
     * {@link #deepCopy()} if that is not what you want.
     *
     * @param b a PersistableBundle to be copied.
     *
     * @see #deepCopy()
     */
    public PersistableBundle(PersistableBundle b) {
        super(b);
        mFlags = b.mFlags;
    }


    /**
     * Constructs a PersistableBundle from a Bundle.  Does only a shallow copy of the Bundle.
     *
     * <p><b>Warning:</b> This method will deserialize every item on the bundle, including custom
     * types such as {@link Parcelable} and {@link Serializable}, so only use this when you trust
     * the source. Specifically don't use this method on app-provided bundles.
     *
     * @param b a Bundle to be copied.
     *
     * @throws IllegalArgumentException if any element of {@code b} cannot be persisted.
     *
     * @hide
     */
    public PersistableBundle(Bundle b) {
        this(b.getItemwiseMap());
    }

    /**
     * Constructs a PersistableBundle containing the mappings passed in.
     *
     * @param map a Map containing only those items that can be persisted.
     * @throws IllegalArgumentException if any element of #map cannot be persisted.
     */
    private PersistableBundle(ArrayMap<String, Object> map) {
        super();
        mFlags = FLAG_DEFUSABLE;

        // First stuff everything in.
        putAll(map);

        // Now verify each item throwing an exception if there is a violation.
        final int N = mMap.size();
        for (int i=0; i<N; i++) {
            Object value = mMap.valueAt(i);
            if (value instanceof ArrayMap) {
                // Fix up any Maps by replacing them with PersistableBundles.
                mMap.setValueAt(i, new PersistableBundle((ArrayMap<String, Object>) value));
            } else if (value instanceof Bundle) {
                mMap.setValueAt(i, new PersistableBundle(((Bundle) value)));
            } else if (!isValidType(value)) {
                throw new IllegalArgumentException("Bad value in PersistableBundle key="
                        + mMap.keyAt(i) + " value=" + value);
            }
        }
    }

    /* package */ PersistableBundle(Parcel parcelledData, int length) {
        super(parcelledData, length);
        mFlags = FLAG_DEFUSABLE;
    }

    /**
     * Constructs a {@link PersistableBundle} containing a copy of {@code from}.
     *
     * @param from The bundle to be copied.
     * @param deep Whether is a deep or shallow copy.
     *
     * @hide
     */
    PersistableBundle(PersistableBundle from, boolean deep) {
        super(from, deep);
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
     * Clones the current PersistableBundle. The internal map is cloned, but the keys and
     * values to which it refers are copied by reference.
     */
    @Override
    public Object clone() {
        return new PersistableBundle(this);
    }

    /**
     * Make a deep copy of the given bundle.  Traverses into inner containers and copies
     * them as well, so they are not shared across bundles.  Will traverse in to
     * {@link Bundle}, {@link PersistableBundle}, {@link ArrayList}, and all types of
     * primitive arrays.  Other types of objects (such as Parcelable or Serializable)
     * are referenced as-is and not copied in any way.
     */
    public PersistableBundle deepCopy() {
        return new PersistableBundle(this, /* deep */ true);
    }

    /**
     * Inserts a PersistableBundle value into the mapping of this Bundle, replacing
     * any existing value for the given key.  Either key or value may be null.
     *
     * @param key a String, or null
     * @param value a Bundle object, or null
     */
    public void putPersistableBundle(@Nullable String key, @Nullable PersistableBundle value) {
        unparcel();
        mMap.put(key, value);
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
    public PersistableBundle getPersistableBundle(@Nullable String key) {
        unparcel();
        Object o = mMap.get(key);
        if (o == null) {
            return null;
        }
        try {
            return (PersistableBundle) o;
        } catch (ClassCastException e) {
            typeWarning(key, o, "Bundle", e);
            return null;
        }
    }

    public static final @android.annotation.NonNull Parcelable.Creator<PersistableBundle> CREATOR =
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

    /** @hide */
    @Override
    public void writeUnknownObject(Object v, String name, TypedXmlSerializer out)
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
        saveToXml(XmlUtils.makeTyped(out));
    }

    /** @hide */
    public void saveToXml(TypedXmlSerializer out) throws IOException, XmlPullParserException {
        unparcel();
        XmlUtils.writeMapXml(mMap, out, this);
    }

    /**
     * Checks whether all keys and values are within the given character limit.
     * Note: Maximum character limit of String that can be saved to XML as part of bundle is 65535.
     * Otherwise IOException is thrown.
     * @param limit length of String keys and values in the PersistableBundle, including nested
     *                    PersistableBundles to check against.
     *
     * @hide
     */
    public boolean isBundleContentsWithinLengthLimit(int limit) {
        unparcel();
        if (mMap == null) {
            return true;
        }
        for (int i = 0; i < mMap.size(); i++) {
            if (mMap.keyAt(i) != null && mMap.keyAt(i).length() > limit) {
                return false;
            }
            final Object value = mMap.valueAt(i);
            if (value instanceof String && ((String) value).length() > limit) {
                return false;
            } else if (value instanceof String[]) {
                String[] stringArray =  (String[]) value;
                for (int j = 0; j < stringArray.length; j++) {
                    if (stringArray[j] != null
                            && stringArray[j].length() > limit) {
                        return false;
                    }
                }
            } else if (value instanceof PersistableBundle
                    && !((PersistableBundle) value).isBundleContentsWithinLengthLimit(limit)) {
                return false;
            }
        }
        return true;
    }

    /** @hide */
    static class MyReadMapCallback implements  XmlUtils.ReadMapCallback {
        @Override
        public Object readThisUnknownObjectXml(TypedXmlPullParser in, String tag)
                throws XmlPullParserException, IOException {
            if (TAG_PERSISTABLEMAP.equals(tag)) {
                return restoreFromXml(in);
            }
            throw new XmlPullParserException("Unknown tag=" + tag);
        }
    }

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
            writeToParcelInner(parcel, flags);
        } finally {
            parcel.restoreAllowFds(oldAllowFds);
        }
    }

    /** @hide */
    public static PersistableBundle restoreFromXml(XmlPullParser in) throws IOException,
            XmlPullParserException {
        return restoreFromXml(XmlUtils.makeTyped(in));
    }

    /** @hide */
    public static PersistableBundle restoreFromXml(TypedXmlPullParser in) throws IOException,
            XmlPullParserException {
        final int outerDepth = in.getDepth();
        final String startTag = in.getName();
        final String[] tagName = new String[1];
        int event;
        while (((event = in.next()) != XmlPullParser.END_DOCUMENT) &&
                (event != XmlPullParser.END_TAG || in.getDepth() < outerDepth)) {
            if (event == XmlPullParser.START_TAG) {
                return new PersistableBundle((ArrayMap<String, Object>)
                        XmlUtils.readThisArrayMapXml(in, startTag, tagName,
                        new MyReadMapCallback()));
            }
        }
        return new PersistableBundle();  // An empty mutable PersistableBundle
    }

    /**
     * Returns a string representation of the {@link PersistableBundle} that may be suitable for
     * debugging. It won't print the internal map if its content hasn't been unparcelled.
     */
    @Override
    public synchronized String toString() {
        if (mParcelledData != null) {
            if (isEmptyParcel()) {
                return "PersistableBundle[EMPTY_PARCEL]";
            } else {
                return "PersistableBundle[mParcelledData.dataSize=" +
                        mParcelledData.dataSize() + "]";
            }
        }
        return "PersistableBundle[" + mMap.toString() + "]";
    }

    /** @hide */
    synchronized public String toShortString() {
        if (mParcelledData != null) {
            if (isEmptyParcel()) {
                return "EMPTY_PARCEL";
            } else {
                return "mParcelledData.dataSize=" + mParcelledData.dataSize();
            }
        }
        return mMap.toString();
    }

    /** @hide */
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);

        if (mParcelledData != null) {
            if (isEmptyParcel()) {
                proto.write(PersistableBundleProto.PARCELLED_DATA_SIZE, 0);
            } else {
                proto.write(PersistableBundleProto.PARCELLED_DATA_SIZE, mParcelledData.dataSize());
            }
        } else {
            proto.write(PersistableBundleProto.MAP_DATA, mMap.toString());
        }

        proto.end(token);
    }

    /**
     * Writes the content of the {@link PersistableBundle} to a {@link OutputStream}.
     *
     * <p>The content can be read by a {@link #readFromStream}.
     *
     * @see #readFromStream
     */
    public void writeToStream(@NonNull OutputStream outputStream) throws IOException {
        TypedXmlSerializer serializer = Xml.newFastSerializer();
        serializer.setOutput(outputStream, UTF_8.name());
        serializer.startTag(null, "bundle");
        try {
            saveToXml(serializer);
        } catch (XmlPullParserException e) {
            throw new IOException(e);
        }
        serializer.endTag(null, "bundle");
        serializer.flush();
    }

    /**
     * Reads a {@link PersistableBundle} from an {@link InputStream}.
     *
     * <p>The stream must be generated by {@link #writeToStream}.
     *
     * @see #writeToStream
     */
    @NonNull
    public static PersistableBundle readFromStream(@NonNull InputStream inputStream)
            throws IOException {
        try {
            TypedXmlPullParser parser = Xml.newFastPullParser();
            parser.setInput(inputStream, UTF_8.name());
            parser.next();
            return PersistableBundle.restoreFromXml(parser);
        } catch (XmlPullParserException e) {
            throw new IOException(e);
        }
    }
}
