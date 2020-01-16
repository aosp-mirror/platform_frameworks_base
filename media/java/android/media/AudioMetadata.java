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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Pair;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * AudioMetadata class is used to manage typed key-value pairs for
 * configuration and capability requests within the Audio Framework.
 */
public final class AudioMetadata {
    /**
     * Key interface for the map.
     *
     * The presence of this {@code Key} interface on an object allows
     * it to be used to reference metadata in the Audio Framework.
     *
     * @param <T> type of value associated with {@code Key}.
     */
    // Conceivably metadata keys exposing multiple interfaces
    // could be eligible to work in multiple framework domains.
    public interface Key<T> {
        /**
         * Returns the internal name of the key.
         */
        @NonNull
        String getName();

        /**
         * Returns the class type of the associated value.
         */
        @NonNull
        Class<T> getValueClass();

        // TODO: consider adding bool isValid(@NonNull T value)

        /**
         * Do not allow non-framework apps to create their own keys
         * by implementing this interface; keep a method hidden.
         *
         * @hide
         */
        boolean isFromFramework();
    }

    /**
     * A read only {@code Map} interface of {@link Key} value pairs.
     *
     * Using a {@link Key} interface, look up the corresponding value.
     */
    public interface ReadMap {
        /**
         * Returns true if the key exists in the map.
         *
         * @param key interface for requesting the value.
         * @param <T> type of value.
         * @return true if key exists in the Map.
         */
        <T> boolean containsKey(@NonNull Key<T> key);

        /**
         * Returns a copy of the map.
         *
         * This is intended for safe conversion between a {@link ReadMap}
         * interface and a {@link Map} interface.
         * Currently only simple objects are used for key values which
         * means a shallow copy is sufficient.
         *
         * @return a Map copied from the existing map.
         */
        @NonNull
        Map dup(); // lint checker doesn't like clone().

        /**
         * Returns the value associated with the key.
         *
         * @param key interface for requesting the value.
         * @param <T> type of value.
         * @return returns the value of associated with key or null if it doesn't exist.
         */
        @Nullable
        <T> T get(@NonNull Key<T> key);

        /**
         * Returns a {@code Set} of keys associated with the map.
         * @hide
         */
        @NonNull
        Set<Key<?>> keySet();

        /**
         * Returns the number of elements in the map.
         */
        int size();
    }

    /**
     * A writeable {@link Map} interface of {@link Key} value pairs.
     * This interface is not guaranteed to be thread-safe
     * unless the supplier for the {@code Map} states it as thread safe.
     */
    // TODO: Create a wrapper like java.util.Collections.synchronizedMap?
    public interface Map extends ReadMap {
        /**
         * Removes the value associated with the key.
         * @param key interface for storing the value.
         * @param <T> type of value.
         * @return the value of the key, null if it doesn't exist.
         */
        @Nullable
        <T> T remove(@NonNull Key<T> key);

        /**
         * Sets a value for the key.
         *
         * @param key interface for storing the value.
         * @param <T> type of value.
         * @param value a non-null value of type T.
         * @return the previous value associated with key or null if it doesn't exist.
         */
        // See automatic Kotlin overloading for Java interoperability.
        // https://kotlinlang.org/docs/reference/java-interop.html#operators
        // See also Kotlin set for overloaded operator indexing.
        // https://kotlinlang.org/docs/reference/operator-overloading.html#indexed
        // Also the Kotlin mutable-list set.
        // https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-mutable-list/set.html
        @Nullable
        <T> T set(@NonNull Key<T> key, @NonNull T value);
    }

    /**
     * Creates a {@link Map} suitable for adding keys.
     * @return an empty {@link Map} instance.
     */
    @NonNull
    public static Map createMap() {
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
        @NonNull public static final Key<Boolean> KEY_ATMOS_PRESENT =
                createKey("atmos-present", Boolean.class);

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
    public static <T> Key<T> createKey(String name, Class<T> type) {
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

            // hidden interface method to prevent user class implements the of Key interface.
            @Override
            public boolean isFromFramework() {
                return true;
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
    public static class BaseMap implements Map {
        @Override
        public <T> boolean containsKey(@NonNull Key<T> key) {
            Pair<Key<?>, Object> valuePair = mHashMap.get(pairFromKey(key));
            return valuePair != null;
        }

        @Override
        @NonNull
        public Map dup() {
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

    // Delete the constructor as there is nothing to implement here.
    private AudioMetadata() {}
}
