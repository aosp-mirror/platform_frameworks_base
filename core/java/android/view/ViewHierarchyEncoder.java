package android.view;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link ViewHierarchyEncoder} is a serializer that is tailored towards writing out
 * view hierarchies (the view tree, along with the properties for each view) to a stream.
 *
 * It is typically used as follows:
 * <pre>
 *   ViewHierarchyEncoder e = new ViewHierarchyEncoder();
 *
 *   for (View view : views) {
 *      e.beginObject(view);
 *      e.addProperty("prop1", value);
 *      ...
 *      e.endObject();
 *   }
 *
 *   // repeat above snippet for each view, finally end with:
 *   e.endStream();
 * </pre>
 *
 * <p>On the stream, a snippet such as the above gets encoded as a series of Map's (one
 * corresponding to each view) with the property name as the key and the property value
 * as the value.
 *
 * <p>Since the property names are practically the same across all views, rather than using
 * the property name directly as the key, we use a short integer id corresponding to each
 * property name as the key. A final map is added at the end which contains the mapping
 * from the integer to its property name.
 *
 * <p>A value is encoded as a single byte type identifier followed by the encoding of the
 * value. Only primitive types are supported as values, in addition to the Map type.
 *
 * @hide
 */
public class ViewHierarchyEncoder {
    // Prefixes for simple primitives. These match the JNI definitions.
    private static final byte SIG_BOOLEAN = 'Z';
    private static final byte SIG_BYTE = 'B';
    private static final byte SIG_SHORT = 'S';
    private static final byte SIG_INT = 'I';
    private static final byte SIG_LONG = 'J';
    private static final byte SIG_FLOAT = 'F';
    private static final byte SIG_DOUBLE = 'D';

    // Prefixes for some commonly used objects
    private static final byte SIG_STRING = 'R';

    private static final byte SIG_MAP = 'M'; // a map with an short key
    private static final short SIG_END_MAP = 0;

    private final DataOutputStream mStream;

    private final Map<String,Short> mPropertyNames = new HashMap<String, Short>(200);
    private short mPropertyId = 1;
    private Charset mCharset = Charset.forName("utf-8");

    public ViewHierarchyEncoder(@NonNull ByteArrayOutputStream stream) {
        mStream = new DataOutputStream(stream);
    }

    public void beginObject(@NonNull Object o) {
        startPropertyMap();
        addProperty("meta:__name__", o.getClass().getName());
        addProperty("meta:__hash__", o.hashCode());
    }

    public void endObject() {
        endPropertyMap();
    }

    public void endStream() {
        // write out the string table
        startPropertyMap();
        addProperty("__name__", "propertyIndex");
        for (Map.Entry<String,Short> entry : mPropertyNames.entrySet()) {
            writeShort(entry.getValue());
            writeString(entry.getKey());
        }
        endPropertyMap();
    }

    public void addProperty(@NonNull String name, boolean v) {
        writeShort(createPropertyIndex(name));
        writeBoolean(v);
    }

    public void addProperty(@NonNull String name, short s) {
        writeShort(createPropertyIndex(name));
        writeShort(s);
    }

    public void addProperty(@NonNull String name, int v) {
        writeShort(createPropertyIndex(name));
        writeInt(v);
    }

    public void addProperty(@NonNull String name, float v) {
        writeShort(createPropertyIndex(name));
        writeFloat(v);
    }

    public void addProperty(@NonNull String name, @Nullable String s) {
        writeShort(createPropertyIndex(name));
        writeString(s);
    }

    /**
     * Writes the given name as the property name, and leaves it to the callee
     * to fill in value for this property.
     */
    public void addPropertyKey(@NonNull String name) {
        writeShort(createPropertyIndex(name));
    }

    private short createPropertyIndex(@NonNull String name) {
        Short index = mPropertyNames.get(name);
        if (index == null) {
            index = mPropertyId++;
            mPropertyNames.put(name, index);
        }

        return index;
    }

    private void startPropertyMap() {
        try {
            mStream.write(SIG_MAP);
        } catch (IOException e) {
            // does not happen since the stream simply wraps a ByteArrayOutputStream
        }
    }

    private void endPropertyMap() {
        writeShort(SIG_END_MAP);
    }

    private void writeBoolean(boolean v) {
        try {
            mStream.write(SIG_BOOLEAN);
            mStream.write(v ? 1 : 0);
        } catch (IOException e) {
            // does not happen since the stream simply wraps a ByteArrayOutputStream
        }
    }

    private void writeShort(short s) {
        try {
            mStream.write(SIG_SHORT);
            mStream.writeShort(s);
        } catch (IOException e) {
            // does not happen since the stream simply wraps a ByteArrayOutputStream
        }
    }

    private void writeInt(int i) {
        try {
            mStream.write(SIG_INT);
            mStream.writeInt(i);
        } catch (IOException e) {
            // does not happen since the stream simply wraps a ByteArrayOutputStream
        }
    }

    private void writeFloat(float v) {
        try {
            mStream.write(SIG_FLOAT);
            mStream.writeFloat(v);
        } catch (IOException e) {
            // does not happen since the stream simply wraps a ByteArrayOutputStream
        }
    }

    private void writeString(@Nullable String s) {
        if (s == null) {
            s = "";
        }

        try {
            mStream.write(SIG_STRING);
            byte[] bytes = s.getBytes(mCharset);

            short len = (short)Math.min(bytes.length, Short.MAX_VALUE);
            mStream.writeShort(len);

            mStream.write(bytes, 0, len);
        } catch (IOException e) {
            // does not happen since the stream simply wraps a ByteArrayOutputStream
        }
    }
}
