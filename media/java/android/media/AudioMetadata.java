/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.media;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Log;
import android.util.Pair;

import java.lang.reflect.ParameterizedType;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * AudioMetadata class is used to manage typed key-value pairs for
 * configuration and capability requests within the Audio Framework.
 */
public final class AudioMetadata {
    private static final String TAG = "AudioMetadata";

    /**
     * Key interface for the {@code AudioMetadata} map.
     *
     * <p>The presence of this {@code Key} interface on an object allows
     * it to reference metadata in the Audio Framework.</p>
     *
     * <p>Vendors are allowed to implement this {@code Key} interface for their debugging or
     * private application use. To avoid name conflicts, vendor key names should be qualified by
     * the vendor company name followed by a dot; for example, "vendorCompany.someVolume".</p>
     *
     * @param <T> type of value associated with {@code Key}.
     */
    /*
     * Internal details:
     * Conceivably metadata keys exposing multiple interfaces
     * could be eligible to work in multiple framework domains.
     */
    public interface Key<T> {
        /**
         * Returns the internal name of the key.  The name should be unique in the
         * {@code AudioMetadata} namespace.  Vendors should prefix their keys with
         * the company name followed by a dot.
         */
        @NonNull
        String getName();

        /**
         * Returns the class type {@code T} of the associated value.  Valid class types for
         * {@link android.os.Build.VERSION_CODES#R} are
         * {@code Integer.class}, {@code Long.class}, {@code Float.class}, {@code Double.class},
         * {@code String.class}.
         */
        @NonNull
        Class<T> getValueClass();

        // TODO: consider adding bool isValid(@NonNull T value)
    }

    /**
     * Creates a {@link AudioMetadataMap} suitable for adding keys.
     * @return an empty {@link AudioMetadataMap} instance.
     */
    @NonNull
    public static AudioMetadataMap createMap() {
        return new BaseMap();
    }

    /**
     * A container class for AudioMetadata Format keys.
     *
     * @see AudioTrack.OnCodecFormatChangedListener
     */
    public static class Format {
        // The key name strings used here must match that of the native framework, but are
        // allowed to change between API releases.  This due to the Java specification
        // on what is a compile time constant.
        //
        // Key<?> are final variables but not constant variables (per Java spec 4.12.4) because
        // the keys are not a primitive type nor a String initialized by a constant expression.
        // Hence (per Java spec 13.1.3), they are not resolved at compile time,
        // rather are picked up by applications at run time.
        //
        // So the contractual API behavior of AudioMetadata.Key<> are different than Strings
        // initialized by a constant expression (for example MediaFormat.KEY_*).

        // See MediaFormat
        /**
         * A key representing the bitrate of the encoded stream used in
         *
         * If the stream is variable bitrate, this is the average bitrate of the stream.
         * The unit is bits per second.
         *
         * An Integer value.
         *
         * @see MediaFormat#KEY_BIT_RATE
         */
        @NonNull public static final Key<Integer> KEY_BIT_RATE =
                createKey("bitrate", Integer.class);

        /**
         * A key representing the audio channel mask of the stream.
         *
         * An Integer value.
         *
         * @see AudioTrack#getChannelConfiguration()
         * @see MediaFormat#KEY_CHANNEL_MASK
         */
        @NonNull public static final Key<Integer> KEY_CHANNEL_MASK =
                createKey("channel-mask", Integer.class);


        /**
         * A key representing the codec mime string.
         *
         * A String value.
         *
         * @see MediaFormat#KEY_MIME
         */
        @NonNull public static final Key<String> KEY_MIME = createKey("mime", String.class);

        /**
         * A key representing the audio sample rate in Hz of the stream.
         *
         * An Integer value.
         *
         * @see AudioFormat#getSampleRate()
         * @see MediaFormat#KEY_SAMPLE_RATE
         */
        @NonNull public static final Key<Integer> KEY_SAMPLE_RATE =
                createKey("sample-rate", Integer.class);

        // Unique to Audio

        /**
         * A key representing the bit width of an element of decoded data.
         *
         * An Integer value.
         */
        @NonNull public static final Key<Integer> KEY_BIT_WIDTH =
                createKey("bit-width", Integer.class);

        /**
         * A key representing the presence of Atmos in an E-AC3 stream.
         *
         * A Boolean value which is true if Atmos is present in an E-AC3 stream.
         */

        // Since Boolean isn't handled by Parceling, we translate
        // internally to KEY_HAS_ATMOS when sending through JNI.
        // Consider deprecating this key for KEY_HAS_ATMOS in the future.
        //
        @NonNull public static final Key<Boolean> KEY_ATMOS_PRESENT =
                createKey("atmos-present", Boolean.class);

        /**
         * A key representing the presence of Atmos in an E-AC3 stream.
         *
         * An Integer value which is nonzero if Atmos is present in an E-AC3 stream.
         * The integer representation is used for communication to the native side.
         * @hide
         */
        @NonNull public static final Key<Integer> KEY_HAS_ATMOS =
                createKey("has-atmos", Integer.class);

        /**
         * A key representing the audio encoding used for the stream.
         * This is the same encoding used in {@link AudioFormat#getEncoding()}.
         *
         * An Integer value.
         *
         * @see AudioFormat#getEncoding()
         */
        @NonNull public static final Key<Integer> KEY_AUDIO_ENCODING =
                createKey("audio-encoding", Integer.class);


        /**
         * A key representing the audio presentation id being decoded by a next generation
         * audio decoder.
         *
         * An Integer value representing presentation id.
         *
         * @see AudioPresentation#getPresentationId()
         */
        @NonNull public static final Key<Integer> KEY_PRESENTATION_ID =
                createKey("presentation-id", Integer.class);

         /**
         * A key representing the audio program id being decoded by a next generation
         * audio decoder.
         *
         * An Integer value representing program id.
         *
         * @see AudioPresentation#getProgramId()
         */
        @NonNull public static final Key<Integer> KEY_PROGRAM_ID =
                createKey("program-id", Integer.class);


         /**
         * A key representing the audio presentation content classifier being rendered
         * by a next generation audio decoder.
         *
         * An Integer value representing presentation content classifier.
         *
         * @see AudioPresentation#CONTENT_UNKNOWN
         * @see AudioPresentation#CONTENT_MAIN
         * @see AudioPresentation#CONTENT_MUSIC_AND_EFFECTS
         * @see AudioPresentation#CONTENT_VISUALLY_IMPAIRED
         * @see AudioPresentation#CONTENT_HEARING_IMPAIRED
         * @see AudioPresentation#CONTENT_DIALOG
         * @see AudioPresentation#CONTENT_COMMENTARY
         * @see AudioPresentation#CONTENT_EMERGENCY
         * @see AudioPresentation#CONTENT_VOICEOVER
         */
        @NonNull public static final Key<Integer> KEY_PRESENTATION_CONTENT_CLASSIFIER =
                createKey("presentation-content-classifier", Integer.class);

        /**
         * A key representing the audio presentation language being rendered by a next
         * generation audio decoder.
         *
         * A String value representing ISO 639-2 (three letter code).
         *
         * @see AudioPresentation#getLocale()
         */
        @NonNull public static final Key<String> KEY_PRESENTATION_LANGUAGE =
                createKey("presentation-language", String.class);

        private Format() {} // delete constructor
    }

    /////////////////////////////////////////////////////////////////////////
    // Hidden methods and functions.

    /**
     * Returns a Key object with the correct interface for the AudioMetadata.
     *
     * An interface with the same name and type will be treated as
     * identical for the purposes of value storage, even though
     * other methods or hidden parameters may return different values.
     *
     * @param name The name of the key.
     * @param type The class type of the value represented by the key.
     * @param <T> The type of value.
     * @return a new key interface.
     *
     * Creating keys is currently only allowed by the Framework.
     * @hide
     */
    @NonNull
    public static <T> Key<T> createKey(@NonNull String name, @NonNull Class<T> type) {
        // Implementation specific.
        return new Key<T>() {
            private final String mName = name;
            private final Class<T> mType = type;

            @Override
            @NonNull
            public String getName() {
                return mName;
            }

            @Override
            @NonNull
            public Class<T> getValueClass() {
                return mType;
            }

            /**
             * Return true if the name and the type of two objects are the same.
             */
            @Override
            public boolean equals(Object obj) {
                if (obj == this) {
                    return true;
                }
                if (!(obj instanceof Key)) {
                    return false;
                }
                Key<?> other = (Key<?>) obj;
                return mName.equals(other.getName()) && mType.equals(other.getValueClass());
            }

            @Override
            public int hashCode() {
                return Objects.hash(mName, mType);
            }
        };
    }

    /**
     * @hide
     *
     * AudioMetadata is based on interfaces in order to allow multiple inheritance
     * and maximum flexibility in implementation.
     *
     * Here, we provide a simple implementation of {@link Map} interface;
     * Note that the Keys are not specific to this Map implementation.
     *
     * It is possible to require the keys to be of a certain class
     * before allowing a set or get operation.
     */
    public static class BaseMap implements AudioMetadataMap {
        @Override
        public <T> boolean containsKey(@NonNull Key<T> key) {
            Pair<Key<?>, Object> valuePair = mHashMap.get(pairFromKey(key));
            return valuePair != null;
        }

        @Override
        @NonNull
        public AudioMetadataMap dup() {
            BaseMap map = new BaseMap();
            map.mHashMap.putAll(this.mHashMap);
            return map;
        }

        @Override
        @Nullable
        public <T> T get(@NonNull Key<T> key) {
            Pair<Key<?>, Object> valuePair = mHashMap.get(pairFromKey(key));
            return (T) getValueFromValuePair(valuePair);
        }

        @Override
        @NonNull
        public Set<Key<?>> keySet() {
            HashSet<Key<?>> set = new HashSet();
            for (Pair<Key<?>, Object> pair : mHashMap.values()) {
                set.add(pair.first);
            }
            return set;
        }

        @Override
        @Nullable
        public <T> T remove(@NonNull Key<T> key) {
            Pair<Key<?>, Object> valuePair = mHashMap.remove(pairFromKey(key));
            return (T) getValueFromValuePair(valuePair);
        }

        @Override
        @Nullable
        public <T> T set(@NonNull Key<T> key, @NonNull T value) {
            Objects.requireNonNull(value);
            Pair<Key<?>, Object> valuePair = mHashMap
                    .put(pairFromKey(key), new Pair<Key<?>, Object>(key, value));
            return (T) getValueFromValuePair(valuePair);
        }

        @Override
        public int size() {
            return mHashMap.size();
        }

        /**
         * Return true if the object is a BaseMap and the content from two BaseMap are the same.
         * Note: Need to override the equals functions of Key<T> for HashMap comparison.
         */
        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (!(obj instanceof BaseMap)) {
                return false;
            }
            BaseMap other = (BaseMap) obj;
            return mHashMap.equals(other.mHashMap);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mHashMap);
        }

        /*
         * Implementation specific.
         *
         * To store the value in the HashMap we need to convert the Key interface
         * to a hashcode() / equals() compliant Pair.
         */
        @NonNull
        private static <T> Pair<String, Class<?>> pairFromKey(@NonNull Key<T> key) {
            Objects.requireNonNull(key);
            return new Pair<String, Class<?>>(key.getName(), key.getValueClass());
        }

        /*
         * Implementation specific.
         *
         * We store in a Pair (valuePair) the key along with the Object value.
         * This helper returns the Object value from the value pair.
         */
        @Nullable
        private static Object getValueFromValuePair(@Nullable Pair<Key<?>, Object> valuePair) {
            if (valuePair == null) {
                return null;
            }
            return valuePair.second;
        }

        /*
         * Implementation specific.
         *
         * We use a HashMap to back the AudioMetadata BaseMap object.
         * This is not locked, so concurrent reads are permitted if all threads
         * have a ReadMap; this is risky with a Map.
         */
        private final HashMap<Pair<String, Class<?>>, Pair<Key<?>, Object>> mHashMap =
                new HashMap();
    }

    // The audio metadata object type index should be kept the same as
    // the ones in audio_utils::metadata::metadata_types
    private static final int AUDIO_METADATA_OBJ_TYPE_NONE = 0;
    private static final int AUDIO_METADATA_OBJ_TYPE_INT = 1;
    private static final int AUDIO_METADATA_OBJ_TYPE_LONG = 2;
    private static final int AUDIO_METADATA_OBJ_TYPE_FLOAT = 3;
    private static final int AUDIO_METADATA_OBJ_TYPE_DOUBLE = 4;
    private static final int AUDIO_METADATA_OBJ_TYPE_STRING = 5;
    // BaseMap is corresponding to audio_utils::metadata::Data
    private static final int AUDIO_METADATA_OBJ_TYPE_BASEMAP = 6;

    private static final Map<Class, Integer> AUDIO_METADATA_OBJ_TYPES = Map.of(
            Integer.class, AUDIO_METADATA_OBJ_TYPE_INT,
            Long.class, AUDIO_METADATA_OBJ_TYPE_LONG,
            Float.class, AUDIO_METADATA_OBJ_TYPE_FLOAT,
            Double.class, AUDIO_METADATA_OBJ_TYPE_DOUBLE,
            String.class, AUDIO_METADATA_OBJ_TYPE_STRING,
            BaseMap.class, AUDIO_METADATA_OBJ_TYPE_BASEMAP);

    private static final Charset AUDIO_METADATA_CHARSET = StandardCharsets.UTF_8;

    /**
     * An auto growing byte buffer
     */
    private static class AutoGrowByteBuffer {
        private static final int INTEGER_BYTE_COUNT = Integer.SIZE / Byte.SIZE;
        private static final int LONG_BYTE_COUNT = Long.SIZE / Byte.SIZE;
        private static final int FLOAT_BYTE_COUNT = Float.SIZE / Byte.SIZE;
        private static final int DOUBLE_BYTE_COUNT = Double.SIZE / Byte.SIZE;

        private ByteBuffer mBuffer;

        AutoGrowByteBuffer() {
            this(1024);
        }

        AutoGrowByteBuffer(@IntRange(from = 0) int initialCapacity) {
            mBuffer = ByteBuffer.allocateDirect(initialCapacity);
        }

        public ByteBuffer getRawByteBuffer() {
            // Slice the buffer from 0 to position.
            int limit = mBuffer.limit();
            int position = mBuffer.position();
            mBuffer.limit(position);
            mBuffer.position(0);
            ByteBuffer buffer = mBuffer.slice();

            // Restore position and limit.
            mBuffer.limit(limit);
            mBuffer.position(position);
            return buffer;
        }

        public ByteOrder order() {
            return mBuffer.order();
        }

        public int position() {
            return mBuffer.position();
        }

        public AutoGrowByteBuffer position(int newPosition) {
            mBuffer.position(newPosition);
            return this;
        }

        public AutoGrowByteBuffer order(ByteOrder order) {
            mBuffer.order(order);
            return this;
        }

        public AutoGrowByteBuffer putInt(int value) {
            ensureCapacity(INTEGER_BYTE_COUNT);
            mBuffer.putInt(value);
            return this;
        }

        public AutoGrowByteBuffer putLong(long value) {
            ensureCapacity(LONG_BYTE_COUNT);
            mBuffer.putLong(value);
            return this;
        }

        public AutoGrowByteBuffer putFloat(float value) {
            ensureCapacity(FLOAT_BYTE_COUNT);
            mBuffer.putFloat(value);
            return this;
        }

        public AutoGrowByteBuffer putDouble(double value) {
            ensureCapacity(DOUBLE_BYTE_COUNT);
            mBuffer.putDouble(value);
            return this;
        }

        public AutoGrowByteBuffer put(byte[] src) {
            ensureCapacity(src.length);
            mBuffer.put(src);
            return this;
        }

        /**
         * Ensures capacity to append at least <code>count</code> values.
         */
        private void ensureCapacity(@IntRange int count) {
            if (mBuffer.remaining() < count) {
                int newCapacity = mBuffer.position() + count;
                if (newCapacity > Integer.MAX_VALUE >> 1) {
                    throw new IllegalStateException(
                            "Item memory requirements too large: " + newCapacity);
                }
                newCapacity <<= 1;
                ByteBuffer buffer = ByteBuffer.allocateDirect(newCapacity);
                buffer.order(mBuffer.order());

                // Copy data from old buffer to new buffer
                mBuffer.flip();
                buffer.put(mBuffer);

                // Set buffer to new buffer
                mBuffer = buffer;
            }
        }
    }

    /**
     * @hide
     * Describes a unpacking/packing contract of type {@code T} out of a {@link ByteBuffer}
     *
     * @param <T> the type being unpack
     */
    private interface DataPackage<T> {
        /**
         * Read an item from a {@link ByteBuffer}.
         *
         * The parceling format is assumed the same as the one described in
         * audio_utils::Metadata.h. Copied here as a reference.
         * All values are native endian order.
         *
         * Datum = { (type_size_t)  Type (the type index from type_as_value<T>.)
         *           (datum_size_t) Size (size of datum, including the size field)
         *           (byte string)  Payload<Type>
         *         }
         *
         * Primitive types:
         * Payload<Type> = { bytes in native endian order }
         *
         * Vector, Map, Container types:
         * Payload<Type> = { (index_size_t) number of elements
         *                   (byte string)  Payload<Element_Type> * number
         *                 }
         *
         * Pair container types:
         * Payload<Type> = { (byte string) Payload<first>,
         *                   (byte string) Payload<second>
         *                 }
         *
         * @param buffer the byte buffer to read from
         * @return an object, which types is given type for {@link DataPackage}
         * @throws BufferUnderflowException when there is no enough data remaining
         *      in the buffer for unpacking.
         */
        @Nullable
        T unpack(ByteBuffer buffer);

        /**
         * Pack the item into a byte array. This is the reversed way of unpacking.
         *
         * @param output is the stream to which to write the data
         * @param obj the item to pack
         * @return true if packing successfully. Otherwise, return false.
         */
        boolean pack(AutoGrowByteBuffer output, T obj);

        /**
         * Return what kind of data is contained in the package.
         */
        default Class getMyType() {
            return (Class) ((ParameterizedType) getClass().getGenericInterfaces()[0])
                    .getActualTypeArguments()[0];
        }
    }

    /*****************************************************************************************
     * Following class are common {@link DataPackage} implementations, which include types
     * that are defined in audio_utils::metadata::metadata_types
     *
     * For Java
     *     int32_t corresponds to Integer
     *     int64_t corresponds to Long
     *     float corresponds to Float
     *     double corresponds to Double
     *     std::string corresponds to String
     *     Data corresponds to BaseMap
     *     Datum corresponds to Object
     ****************************************************************************************/

    private static final Map<Integer, DataPackage<?>> DATA_PACKAGES = Map.of(
            AUDIO_METADATA_OBJ_TYPE_INT, new DataPackage<Integer>() {
                @Override
                @Nullable
                public Integer unpack(ByteBuffer buffer) {
                    return buffer.getInt();
                }

                @Override
                public boolean pack(AutoGrowByteBuffer output, Integer obj) {
                    output.putInt(obj);
                    return true;
                }
            },
            AUDIO_METADATA_OBJ_TYPE_LONG, new DataPackage<Long>() {
                @Override
                @Nullable
                public Long unpack(ByteBuffer buffer) {
                    return buffer.getLong();
                }

                @Override
                public boolean pack(AutoGrowByteBuffer output, Long obj) {
                    output.putLong(obj);
                    return true;
                }
            },
            AUDIO_METADATA_OBJ_TYPE_FLOAT, new DataPackage<Float>() {
                @Override
                @Nullable
                public Float unpack(ByteBuffer buffer) {
                    return buffer.getFloat();
                }

                @Override
                public boolean pack(AutoGrowByteBuffer output, Float obj) {
                    output.putFloat(obj);
                    return true;
                }
            },
            AUDIO_METADATA_OBJ_TYPE_DOUBLE, new DataPackage<Double>() {
                @Override
                @Nullable
                public Double unpack(ByteBuffer buffer) {
                    return buffer.getDouble();
                }

                @Override
                public boolean pack(AutoGrowByteBuffer output, Double obj) {
                    output.putDouble(obj);
                    return true;
                }
            },
            AUDIO_METADATA_OBJ_TYPE_STRING, new DataPackage<String>() {
                @Override
                @Nullable
                public String unpack(ByteBuffer buffer) {
                    int dataSize = buffer.getInt();
                    if (buffer.position() + dataSize > buffer.limit()) {
                        return null;
                    }
                    byte[] valueArr = new byte[dataSize];
                    buffer.get(valueArr);
                    String value = new String(valueArr, AUDIO_METADATA_CHARSET);
                    return value;
                }

                /**
                 * This is a reversed operation of unpack. It is needed to write the String
                 * at bytes encoded with AUDIO_METADATA_CHARSET. There should be an integer
                 * value representing the length of the bytes written before the bytes.
                 */
                @Override
                public boolean pack(AutoGrowByteBuffer output, String obj) {
                    byte[] valueArr = obj.getBytes(AUDIO_METADATA_CHARSET);
                    output.putInt(valueArr.length);
                    output.put(valueArr);
                    return true;
                }
            },
            AUDIO_METADATA_OBJ_TYPE_BASEMAP, new BaseMapPackage());

    // ObjectPackage is a special case that it is expected to unpack audio_utils::metadata::Datum,
    // which contains data type and data size besides the payload for the data.
    private static final ObjectPackage OBJECT_PACKAGE = new ObjectPackage();

    private static class ObjectPackage implements DataPackage<Pair<Class, Object>> {
        /**
         * The {@link ObjectPackage} will unpack byte string for audio_utils::metadata::Datum.
         * Since the Datum is a std::any, {@link Object} is used to carrying the data. The
         * data type is stored in the data package header. In that case, a {@link Class}
         * will also be returned to indicate the actual type for the object.
         */
        @Override
        @Nullable
        public Pair<Class, Object> unpack(ByteBuffer buffer) {
            int dataType = buffer.getInt();
            DataPackage dataPackage = DATA_PACKAGES.get(dataType);
            if (dataPackage == null) {
                Log.e(TAG, "Cannot find DataPackage for type:" + dataType);
                return null;
            }
            int dataSize = buffer.getInt();
            int position = buffer.position();
            Object obj = dataPackage.unpack(buffer);
            if (buffer.position() - position != dataSize) {
                Log.e(TAG, "Broken data package");
                return null;
            }
            return new Pair<Class, Object>(dataPackage.getMyType(), obj);
        }

        @Override
        public boolean pack(AutoGrowByteBuffer output, Pair<Class, Object> obj) {
            final Integer dataType = AUDIO_METADATA_OBJ_TYPES.get(obj.first);
            if (dataType == null) {
                Log.e(TAG, "Cannot find data type for " + obj.first);
                return false;
            }
            DataPackage dataPackage = DATA_PACKAGES.get(dataType);
            if (dataPackage == null) {
                Log.e(TAG, "Cannot find DataPackage for type:" + dataType);
                return false;
            }
            output.putInt(dataType);
            int position = output.position(); // Keep current position.
            output.putInt(0); // Keep a place for the size of payload.
            int payloadIdx = output.position();
            if (!dataPackage.pack(output, obj.second)) {
                Log.i(TAG, "Failed to pack object: " + obj.second);
                return false;
            }
            // Put the actual payload size.
            int currentPosition = output.position();
            output.position(position);
            output.putInt(currentPosition - payloadIdx);
            output.position(currentPosition);
            return true;
        }
    }

    /**
     * BaseMap will be corresponding to audio_utils::metadata::Data.
     */
    private static class BaseMapPackage implements DataPackage<BaseMap> {
        @Override
        @Nullable
        public BaseMap unpack(ByteBuffer buffer) {
            BaseMap ret = new BaseMap();
            int mapSize = buffer.getInt();
            DataPackage<String> strDataPackage =
                    (DataPackage<String>) DATA_PACKAGES.get(AUDIO_METADATA_OBJ_TYPE_STRING);
            if (strDataPackage == null) {
                Log.e(TAG, "Cannot find DataPackage for String");
                return null;
            }
            for (int i = 0; i < mapSize; i++) {
                String key = strDataPackage.unpack(buffer);
                if (key == null) {
                    Log.e(TAG, "Failed to unpack key for map");
                    return null;
                }
                Pair<Class, Object> value = OBJECT_PACKAGE.unpack(buffer);
                if (value == null) {
                    Log.e(TAG, "Failed to unpack value for map");
                    return null;
                }

                // Special handling of KEY_ATMOS_PRESENT.
                if (key.equals(Format.KEY_HAS_ATMOS.getName())
                        && value.first == Format.KEY_HAS_ATMOS.getValueClass()) {
                    ret.set(Format.KEY_ATMOS_PRESENT,
                            (Boolean) ((int) value.second != 0));  // Translate Integer to Boolean
                    continue; // Should we store both keys in the java table?
                }

                ret.set(createKey(key, value.first), value.first.cast(value.second));
            }
            return ret;
        }

        @Override
        public boolean pack(AutoGrowByteBuffer output, BaseMap obj) {
            output.putInt(obj.size());
            DataPackage<String> strDataPackage =
                    (DataPackage<String>) DATA_PACKAGES.get(AUDIO_METADATA_OBJ_TYPE_STRING);
            if (strDataPackage == null) {
                Log.e(TAG, "Cannot find DataPackage for String");
                return false;
            }
            for (Key<?> key : obj.keySet()) {
                Object value = obj.get(key);

                // Special handling of KEY_ATMOS_PRESENT.
                if (key == Format.KEY_ATMOS_PRESENT) {
                    key = Format.KEY_HAS_ATMOS;
                    value = (Integer) ((boolean) value ? 1 : 0); // Translate Boolean to Integer
                }

                if (!strDataPackage.pack(output, key.getName())) {
                    Log.i(TAG, "Failed to pack key: " + key.getName());
                    return false;
                }
                if (!OBJECT_PACKAGE.pack(output, new Pair<>(key.getValueClass(), value))) {
                    Log.i(TAG, "Failed to pack value: " + obj.get(key));
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * @hide
     * Extract a {@link BaseMap} from a given {@link ByteBuffer}
     * @param buffer is a byte string that contains information to unpack.
     * @return a {@link BaseMap} object if extracting successfully from given byte buffer.
     *     Otherwise, returns {@code null}.
     */
    @Nullable
    public static BaseMap fromByteBuffer(ByteBuffer buffer) {
        DataPackage dataPackage = DATA_PACKAGES.get(AUDIO_METADATA_OBJ_TYPE_BASEMAP);
        if (dataPackage == null) {
            Log.e(TAG, "Cannot find DataPackage for BaseMap");
            return null;
        }
        try {
            return (BaseMap) dataPackage.unpack(buffer);
        } catch (BufferUnderflowException e) {
            Log.e(TAG, "No enough data to unpack");
        }
        return null;
    }

    /**
     * @hide
     * Pack a {link BaseMap} to a {@link ByteBuffer}
     * @param data is the object for packing
     * @param order is the byte order
     * @return a {@link ByteBuffer} if successfully packing the data.
     *     Otherwise, returns {@code null};
     */
    @Nullable
    public static ByteBuffer toByteBuffer(BaseMap data, ByteOrder order) {
        DataPackage dataPackage = DATA_PACKAGES.get(AUDIO_METADATA_OBJ_TYPE_BASEMAP);
        if (dataPackage == null) {
            Log.e(TAG, "Cannot find DataPackage for BaseMap");
            return null;
        }
        AutoGrowByteBuffer output = new AutoGrowByteBuffer();
        output.order(order);
        if (dataPackage.pack(output, data)) {
            return output.getRawByteBuffer();
        }
        return null;
    }

    // Delete the constructor as there is nothing to implement here.
    private AudioMetadata() {}
}
