/*
 * Copyright 2019 The Android Open Source Project
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
import android.os.Bundle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * MediaMetrics is the Java interface to the MediaMetrics service.
 *
 * This is used to collect media statistics by the framework.
 * It is not intended for direct application use.
 *
 * @hide
 */
public class MediaMetrics {
    public static final String TAG = "MediaMetrics";

    public static final String SEPARATOR = ".";

    /**
     * A list of established MediaMetrics names that can be used for Items.
     */
    public static class Name {
        public static final String AUDIO = "audio";
        public static final String AUDIO_BLUETOOTH = AUDIO + SEPARATOR + "bluetooth";
        public static final String AUDIO_DEVICE = AUDIO + SEPARATOR + "device";
        public static final String AUDIO_FOCUS = AUDIO + SEPARATOR + "focus";
        public static final String AUDIO_FORCE_USE = AUDIO + SEPARATOR + "forceUse";
        public static final String AUDIO_MIC = AUDIO + SEPARATOR + "mic";
        public static final String AUDIO_MIDI = AUDIO + SEPARATOR + "midi";
        public static final String AUDIO_MODE = AUDIO + SEPARATOR + "mode";
        public static final String AUDIO_SERVICE = AUDIO + SEPARATOR + "service";
        public static final String AUDIO_VOLUME = AUDIO + SEPARATOR + "volume";
        public static final String AUDIO_VOLUME_EVENT = AUDIO_VOLUME + SEPARATOR + "event";
        public static final String METRICS_MANAGER = "metrics" + SEPARATOR + "manager";
    }

    /**
     * A list of established string values.
     */
    public static class Value {
        public static final String CONNECT = "connect";
        public static final String CONNECTED = "connected";
        public static final String DISCONNECT = "disconnect";
        public static final String DISCONNECTED = "disconnected";
        public static final String DOWN = "down";
        public static final String MUTE = "mute";
        public static final String NO = "no";
        public static final String OFF = "off";
        public static final String ON = "on";
        public static final String UNMUTE = "unmute";
        public static final String UP = "up";
        public static final String YES = "yes";
    }

    /**
     * A list of standard property keys for consistent use and type.
     */
    public static class Property {
        // A use for Bluetooth or USB device addresses
        public static final Key<String> ADDRESS = createKey("address", String.class);
        // A string representing the Audio Attributes
        public static final Key<String> ATTRIBUTES = createKey("attributes", String.class);

        // The calling package responsible for the state change
        public static final Key<String> CALLING_PACKAGE =
                createKey("callingPackage", String.class);

        // The client name
        public static final Key<String> CLIENT_NAME = createKey("clientName", String.class);

        public static final Key<Integer> CLOSED_COUNT =
                createKey("closedCount", Integer.class); // MIDI

        // The device type
        public static final Key<Integer> DELAY_MS = createKey("delayMs", Integer.class);

        // The device type
        public static final Key<String> DEVICE = createKey("device", String.class);

        // Whether the device is disconnected. This is either "true" or "false"
        public static final Key<String> DEVICE_DISCONNECTED =
                createKey("deviceDisconnected", String.class); // MIDI

        // The ID of the device
        public static final Key<Integer> DEVICE_ID =
                createKey("deviceId", Integer.class); // MIDI

        // For volume changes, up or down
        public static final Key<String> DIRECTION = createKey("direction", String.class);
        public static final Key<Long> DURATION_NS =
                createKey("durationNs", Long.class); // MIDI
        // A reason for early return or error
        public static final Key<String> EARLY_RETURN =
                createKey("earlyReturn", String.class);
        // ENCODING_ ... string to match AudioFormat encoding
        public static final Key<String> ENCODING = createKey("encoding", String.class);

        public static final Key<String> EVENT = createKey("event#", String.class);

        // Generally string "true" or "false"
        public static final Key<String> ENABLED = createKey("enabled", String.class);

        // event generated is external (yes, no)
        public static final Key<String> EXTERNAL = createKey("external", String.class);

        public static final Key<Integer> FLAGS = createKey("flags", Integer.class);
        public static final Key<String> FOCUS_CHANGE_HINT =
                createKey("focusChangeHint", String.class);
        public static final Key<String> FORCE_USE_DUE_TO =
                createKey("forceUseDueTo", String.class);
        public static final Key<String> FORCE_USE_MODE =
                createKey("forceUseMode", String.class);
        public static final Key<Double> GAIN_DB =
                createKey("gainDb", Double.class);
        public static final Key<String> GROUP =
                createKey("group", String.class);

        // Generally string "true" or "false"
        public static final Key<String> HAS_HEAD_TRACKER =
                createKey("hasHeadTracker", String.class);     // spatializer
        public static final Key<Integer> HARDWARE_TYPE =
                createKey("hardwareType", Integer.class); // MIDI
        // Generally string "true" or "false"
        public static final Key<String> HEAD_TRACKER_ENABLED =
                createKey("headTrackerEnabled", String.class); // spatializer

        public static final Key<Integer> INDEX = createKey("index", Integer.class); // volume
        public static final Key<Integer> OLD_INDEX = createKey("oldIndex", Integer.class); // volume
        public static final Key<Integer> INPUT_PORT_COUNT =
                createKey("inputPortCount", Integer.class); // MIDI
        // Either "true" or "false"
        public static final Key<String> IS_SHARED = createKey("isShared", String.class); // MIDI
        public static final Key<String> LOG_SESSION_ID = createKey("logSessionId", String.class);
        public static final Key<Integer> MAX_INDEX = createKey("maxIndex", Integer.class); // vol
        public static final Key<Integer> MIN_INDEX = createKey("minIndex", Integer.class); // vol
        public static final Key<String> MODE =
                createKey("mode", String.class); // audio_mode
        public static final Key<String> MUTE =
                createKey("mute", String.class); // microphone, on or off.

        // Bluetooth or Usb device name
        public static final Key<String> NAME =
                createKey("name", String.class);

        // Number of observers
        public static final Key<Integer> OBSERVERS =
                createKey("observers", Integer.class);

        public static final Key<Integer> OPENED_COUNT =
                createKey("openedCount", Integer.class); // MIDI
        public static final Key<Integer> OUTPUT_PORT_COUNT =
                createKey("outputPortCount", Integer.class); // MIDI

        public static final Key<String> REQUEST =
                createKey("request", String.class);

        // For audio mode
        public static final Key<String> REQUESTED_MODE =
                createKey("requestedMode", String.class); // audio_mode

        // For Bluetooth
        public static final Key<String> SCO_AUDIO_MODE =
                createKey("scoAudioMode", String.class);
        public static final Key<Integer> SDK = createKey("sdk", Integer.class);
        public static final Key<String> STATE = createKey("state", String.class);
        public static final Key<Integer> STATUS = createKey("status", Integer.class);
        public static final Key<String> STREAM_TYPE = createKey("streamType", String.class);

        // The following MIDI string is generally either "true" or "false"
        public static final Key<String> SUPPORTS_MIDI_UMP =
                createKey("supportsMidiUmp", String.class); // Universal MIDI Packets

        public static final Key<Integer> TOTAL_INPUT_BYTES =
                createKey("totalInputBytes", Integer.class); // MIDI
        public static final Key<Integer> TOTAL_OUTPUT_BYTES =
                createKey("totalOutputBytes", Integer.class); // MIDI

        // The following MIDI string is generally either "true" or "false"
        public static final Key<String> USING_ALSA = createKey("usingAlsa", String.class);
    }

    /**
     * The TYPE constants below should match those in native MediaMetricsItem.h
     */
    private static final int TYPE_NONE = 0;
    private static final int TYPE_INT32 = 1;     // Java integer
    private static final int TYPE_INT64 = 2;     // Java long
    private static final int TYPE_DOUBLE = 3;    // Java double
    private static final int TYPE_CSTRING = 4;   // Java string
    private static final int TYPE_RATE = 5;      // Two longs, ignored in Java

    // The charset used for encoding Strings to bytes.
    private static final Charset MEDIAMETRICS_CHARSET = StandardCharsets.UTF_8;

    /**
     * Key interface.
     *
     * The presence of this {@code Key} interface on an object allows
     * it to be used to set metrics.
     *
     * @param <T> type of value associated with {@code Key}.
     */
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
    }

    /**
     * Returns a Key object with the correct interface for MediaMetrics.
     *
     * @param name The name of the key.
     * @param type The class type of the value represented by the key.
     * @param <T> The type of value.
     * @return a new key interface.
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
     * Item records properties and delivers to the MediaMetrics service
     *
     */
    public static class Item {

        /*
         * MediaMetrics Item
         *
         * Creates a Byte String and sends to the MediaMetrics service.
         * The Byte String serves as a compact form for logging data
         * with low overhead for storage.
         *
         * The Byte String format is as follows:
         *
         * For Java
         *  int64 corresponds to long
         *  int32, uint32 corresponds to int
         *  uint16 corresponds to char
         *  uint8, int8 corresponds to byte
         *
         * For items transmitted from Java, uint8 and uint32 values are limited
         * to INT8_MAX and INT32_MAX.  This constrains the size of large items
         * to 2GB, which is consistent with ByteBuffer max size. A native item
         * can conceivably have size of 4GB.
         *
         * Physical layout of integers and doubles within the MediaMetrics byte string
         * is in Native / host order, which is usually little endian.
         *
         * Note that primitive data (ints, doubles) within a Byte String has
         * no extra padding or alignment requirements, like ByteBuffer.
         *
         * -- begin of item
         * -- begin of header
         * (uint32) item size: including the item size field
         * (uint32) header size, including the item size and header size fields.
         * (uint16) version: exactly 0
         * (uint16) key size, that is key strlen + 1 for zero termination.
         * (int8)+ key, a string which is 0 terminated (UTF-8).
         * (int32) pid
         * (int32) uid
         * (int64) timestamp
         * -- end of header
         * -- begin body
         * (uint32) number of properties
         * -- repeat for number of properties
         *     (uint16) property size, including property size field itself
         *     (uint8) type of property
         *     (int8)+ key string, including 0 termination
         *      based on type of property (given above), one of:
         *       (int32)
         *       (int64)
         *       (double)
         *       (int8)+ for TYPE_CSTRING, including 0 termination
         *       (int64, int64) for rate
         * -- end body
         * -- end of item
         *
         * To record a MediaMetrics event, one creates a new item with an id,
         * then use a series of puts to add properties
         * and then a record() to send to the MediaMetrics service.
         *
         * The properties may not be unique, and putting a later property with
         * the same name as an earlier property will overwrite the value and type
         * of the prior property.
         *
         * The timestamp can only be recorded by a system service (and is ignored otherwise;
         * the MediaMetrics service will fill in the timestamp as needed).
         *
         * The units of time are in SystemClock.elapsedRealtimeNanos().
         *
         * A clear() may be called to reset the properties to empty, the time to 0, but keep
         * the other entries the same. This may be called after record().
         * Additional properties may be added after calling record().  Changing the same property
         * repeatedly is discouraged as - for this particular implementation - extra data
         * is stored per change.
         *
         * new MediaMetrics.Item(mSomeId)
         *     .putString("event", "javaCreate")
         *     .putInt("value", intValue)
         *     .record();
         */

        /**
         * Creates an Item with server added uid, time.
         *
         * This is the typical way to record a MediaMetrics item.
         *
         * @param key the Metrics ID associated with the item.
         */
        public Item(String key) {
            this(key, -1 /* pid */, -1 /* uid */, 0 /* SystemClock.elapsedRealtimeNanos() */,
                    2048 /* capacity */);
        }

        /**
         * Creates an Item specifying pid, uid, time, and initial Item capacity.
         *
         * This might be used by a service to specify a different PID or UID for a client.
         *
         * @param key the Metrics ID associated with the item.
         *        An app may only set properties on an item which has already been
         *        logged previously by a service.
         * @param pid the process ID corresponding to the item.
         *        A value of -1 (or a record() from an app instead of a service) causes
         *        the MediaMetrics service to fill this in.
         * @param uid the user ID corresponding to the item.
         *        A value of -1 (or a record() from an app instead of a service) causes
         *        the MediaMetrics service to fill this in.
         * @param timeNs the time when the item occurred (may be in the past).
         *        A value of 0 (or a record() from an app instead of a service) causes
         *        the MediaMetrics service to fill it in.
         *        Should be obtained from SystemClock.elapsedRealtimeNanos().
         * @param capacity the anticipated size to use for the buffer.
         *        If the capacity is too small, the buffer will be resized to accommodate.
         *        This is amortized to copy data no more than twice.
         */
        public Item(String key, int pid, int uid, long timeNs, int capacity) {
            final byte[] keyBytes = key.getBytes(MEDIAMETRICS_CHARSET);
            final int keyLength = keyBytes.length;
            if (keyLength > Character.MAX_VALUE - 1) {
                throw new IllegalArgumentException("Key length too large");
            }

            // Version 0 - compute the header offsets here.
            mHeaderSize = 4 + 4 + 2 + 2 + keyLength + 1 + 4 + 4 + 8; // see format above.
            mPidOffset = mHeaderSize - 16;
            mUidOffset = mHeaderSize - 12;
            mTimeNsOffset = mHeaderSize - 8;
            mPropertyCountOffset = mHeaderSize;
            mPropertyStartOffset = mHeaderSize + 4;

            mKey = key;
            mBuffer = ByteBuffer.allocateDirect(
                    Math.max(capacity, mHeaderSize + MINIMUM_PAYLOAD_SIZE));

            // Version 0 - fill the ByteBuffer with the header (some details updated later).
            mBuffer.order(ByteOrder.nativeOrder())
                .putInt((int) 0)                      // total size in bytes (filled in later)
                .putInt((int) mHeaderSize)            // size of header
                .putChar((char) FORMAT_VERSION)       // version
                .putChar((char) (keyLength + 1))      // length, with zero termination
                .put(keyBytes).put((byte) 0)
                .putInt(pid)
                .putInt(uid)
                .putLong(timeNs);
            if (mHeaderSize != mBuffer.position()) {
                throw new IllegalStateException("Mismatched sizing");
            }
            mBuffer.putInt(0);     // number of properties (to be later filled in by record()).
        }

        /**
         * Sets a metrics typed key
         * @param key
         * @param value
         * @param <T>
         * @return
         */
        @NonNull
        public <T> Item set(@NonNull Key<T> key, @Nullable T value) {
            if (value instanceof Integer) {
                putInt(key.getName(), (int) value);
            } else if (value instanceof Long) {
                putLong(key.getName(), (long) value);
            } else if (value instanceof Double) {
                putDouble(key.getName(), (double) value);
            } else if (value instanceof String) {
                putString(key.getName(), (String) value);
            }
            // if value is null, etc. no error is raised.
            return this;
        }

        /**
         * Sets the property with key to an integer (32 bit) value.
         *
         * @param key
         * @param value
         * @return itself
         */
        public Item putInt(String key, int value) {
            final byte[] keyBytes = key.getBytes(MEDIAMETRICS_CHARSET);
            final char propSize = (char) reserveProperty(keyBytes, 4 /* payloadSize */);
            final int estimatedFinalPosition = mBuffer.position() + propSize;
            mBuffer.putChar(propSize)
                .put((byte) TYPE_INT32)
                .put(keyBytes).put((byte) 0) // key, zero terminated
                .putInt(value);
            ++mPropertyCount;
            if (mBuffer.position() != estimatedFinalPosition) {
                throw new IllegalStateException("Final position " + mBuffer.position()
                        + " != estimatedFinalPosition " + estimatedFinalPosition);
            }
            return this;
        }

        /**
         * Sets the property with key to a long (64 bit) value.
         *
         * @param key
         * @param value
         * @return itself
         */
        public Item putLong(String key, long value) {
            final byte[] keyBytes = key.getBytes(MEDIAMETRICS_CHARSET);
            final char propSize = (char) reserveProperty(keyBytes, 8 /* payloadSize */);
            final int estimatedFinalPosition = mBuffer.position() + propSize;
            mBuffer.putChar(propSize)
                .put((byte) TYPE_INT64)
                .put(keyBytes).put((byte) 0) // key, zero terminated
                .putLong(value);
            ++mPropertyCount;
            if (mBuffer.position() != estimatedFinalPosition) {
                throw new IllegalStateException("Final position " + mBuffer.position()
                    + " != estimatedFinalPosition " + estimatedFinalPosition);
            }
            return this;
        }

        /**
         * Sets the property with key to a double value.
         *
         * @param key
         * @param value
         * @return itself
         */
        public Item putDouble(String key, double value) {
            final byte[] keyBytes = key.getBytes(MEDIAMETRICS_CHARSET);
            final char propSize = (char) reserveProperty(keyBytes, 8 /* payloadSize */);
            final int estimatedFinalPosition = mBuffer.position() + propSize;
            mBuffer.putChar(propSize)
                .put((byte) TYPE_DOUBLE)
                .put(keyBytes).put((byte) 0) // key, zero terminated
                .putDouble(value);
            ++mPropertyCount;
            if (mBuffer.position() != estimatedFinalPosition) {
                throw new IllegalStateException("Final position " + mBuffer.position()
                    + " != estimatedFinalPosition " + estimatedFinalPosition);
            }
            return this;
        }

        /**
         * Sets the property with key to a String value.
         *
         * @param key
         * @param value
         * @return itself
         */
        public Item putString(String key, String value) {
            final byte[] keyBytes = key.getBytes(MEDIAMETRICS_CHARSET);
            final byte[] valueBytes = value.getBytes(MEDIAMETRICS_CHARSET);
            final char propSize = (char) reserveProperty(keyBytes, valueBytes.length + 1);
            final int estimatedFinalPosition = mBuffer.position() + propSize;
            mBuffer.putChar(propSize)
                .put((byte) TYPE_CSTRING)
                .put(keyBytes).put((byte) 0) // key, zero terminated
                .put(valueBytes).put((byte) 0); // value, zero term.
            ++mPropertyCount;
            if (mBuffer.position() != estimatedFinalPosition) {
                throw new IllegalStateException("Final position " + mBuffer.position()
                    + " != estimatedFinalPosition " + estimatedFinalPosition);
            }
            return this;
        }

        /**
         * Sets the pid to the provided value.
         *
         * @param pid which can be -1 if the service is to fill it in from the calling info.
         * @return itself
         */
        public Item setPid(int pid) {
            mBuffer.putInt(mPidOffset, pid); // pid location in byte string.
            return this;
        }

        /**
         * Sets the uid to the provided value.
         *
         * The UID represents the client associated with the property. This must be the UID
         * of the application if it comes from the application client.
         *
         * Trusted services are allowed to set the uid for a client-related item.
         *
         * @param uid which can be -1 if the service is to fill it in from calling info.
         * @return itself
         */
        public Item setUid(int uid) {
            mBuffer.putInt(mUidOffset, uid); // uid location in byte string.
            return this;
        }

        /**
         * Sets the timestamp to the provided value.
         *
         * The time is referenced by the Boottime obtained by SystemClock.elapsedRealtimeNanos().
         * This should be associated with the occurrence of the event.  It is recommended that
         * the event be registered immediately when it occurs, and no later than 500ms
         * (and certainly not in the future).
         *
         * @param timeNs which can be 0 if the service is to fill it in at the time of call.
         * @return itself
         */
        public Item setTimestamp(long timeNs) {
            mBuffer.putLong(mTimeNsOffset, timeNs); // time location in byte string.
            return this;
        }

        /**
         * Clears the properties and resets the time to 0.
         *
         * No other values are changed.
         *
         * @return itself
         */
        public Item clear() {
            mBuffer.position(mPropertyStartOffset);
            mBuffer.limit(mBuffer.capacity());
            mBuffer.putLong(mTimeNsOffset, 0); // reset time.
            mPropertyCount = 0;
            return this;
        }

        /**
         * Sends the item to the MediaMetrics service.
         *
         * The item properties are unchanged, hence record() may be called more than once
         * to send the same item twice. Also, record() may be called without any properties.
         *
         * @return true if successful.
         */
        public boolean record() {
            updateHeader();
            return native_submit_bytebuffer(mBuffer, mBuffer.limit()) >= 0;
        }

        /**
         * Converts the Item to a Bundle.
         *
         * This is primarily used as a test API for CTS.
         *
         * @return a Bundle with the keys set according to data in the Item's buffer.
         */
        public Bundle toBundle() {
            updateHeader();

            final ByteBuffer buffer = mBuffer.duplicate();
            buffer.order(ByteOrder.nativeOrder()) // restore order property
                .flip();                          // convert from write buffer to read buffer

            return toBundle(buffer);
        }

        // The following constants are used for tests to extract
        // the content of the Bundle for CTS testing.
        public static final String BUNDLE_TOTAL_SIZE = "_totalSize";
        public static final String BUNDLE_HEADER_SIZE = "_headerSize";
        public static final String BUNDLE_VERSION = "_version";
        public static final String BUNDLE_KEY_SIZE = "_keySize";
        public static final String BUNDLE_KEY = "_key";
        public static final String BUNDLE_PID = "_pid";
        public static final String BUNDLE_UID = "_uid";
        public static final String BUNDLE_TIMESTAMP = "_timestamp";
        public static final String BUNDLE_PROPERTY_COUNT = "_propertyCount";

        /**
         * Converts a buffer contents to a bundle
         *
         * This is primarily used as a test API for CTS.
         *
         * @param buffer contains the byte data serialized according to the byte string version.
         * @return a Bundle with the keys set according to data in the buffer.
         */
        public static Bundle toBundle(ByteBuffer buffer) {
            final Bundle bundle = new Bundle();

            final int totalSize = buffer.getInt();
            final int headerSize = buffer.getInt();
            final char version = buffer.getChar();
            final char keySize = buffer.getChar(); // includes zero termination, i.e. keyLength + 1

            if (totalSize < 0 || headerSize < 0) {
                throw new IllegalArgumentException("Item size cannot be > " + Integer.MAX_VALUE);
            }
            final String key;
            if (keySize > 0) {
                key = getStringFromBuffer(buffer, keySize);
            } else {
                throw new IllegalArgumentException("Illegal null key");
            }

            final int pid = buffer.getInt();
            final int uid = buffer.getInt();
            final long timestamp = buffer.getLong();

            // Verify header size (depending on version).
            final int headerRead = buffer.position();
            if (version == 0) {
                if (headerRead != headerSize) {
                    throw new IllegalArgumentException(
                            "Item key:" + key
                            + " headerRead:" + headerRead + " != headerSize:" + headerSize);
                }
            } else {
                // future versions should only increase header size
                // by adding to the end.
                if (headerRead > headerSize) {
                    throw new IllegalArgumentException(
                            "Item key:" + key
                            + " headerRead:" + headerRead + " > headerSize:" + headerSize);
                } else if (headerRead < headerSize) {
                    buffer.position(headerSize);
                }
            }

            // Body always starts with properties.
            final int propertyCount = buffer.getInt();
            if (propertyCount < 0) {
                throw new IllegalArgumentException(
                        "Cannot have more than " + Integer.MAX_VALUE + " properties");
            }
            bundle.putInt(BUNDLE_TOTAL_SIZE, totalSize);
            bundle.putInt(BUNDLE_HEADER_SIZE, headerSize);
            bundle.putChar(BUNDLE_VERSION, version);
            bundle.putChar(BUNDLE_KEY_SIZE, keySize);
            bundle.putString(BUNDLE_KEY, key);
            bundle.putInt(BUNDLE_PID, pid);
            bundle.putInt(BUNDLE_UID, uid);
            bundle.putLong(BUNDLE_TIMESTAMP, timestamp);
            bundle.putInt(BUNDLE_PROPERTY_COUNT, propertyCount);

            for (int i = 0; i < propertyCount; ++i) {
                final int initialBufferPosition = buffer.position();
                final char propSize = buffer.getChar();
                final byte type = buffer.get();

                // Log.d(TAG, "(" + i + ") propSize:" + ((int)propSize) + " type:" + type);
                final String propKey = getStringFromBuffer(buffer);
                switch (type) {
                    case TYPE_INT32:
                        bundle.putInt(propKey, buffer.getInt());
                        break;
                    case TYPE_INT64:
                        bundle.putLong(propKey, buffer.getLong());
                        break;
                    case TYPE_DOUBLE:
                        bundle.putDouble(propKey, buffer.getDouble());
                        break;
                    case TYPE_CSTRING:
                        bundle.putString(propKey, getStringFromBuffer(buffer));
                        break;
                    case TYPE_NONE:
                        break; // ignore on Java side
                    case TYPE_RATE:
                        buffer.getLong();  // consume the first int64_t of rate
                        buffer.getLong();  // consume the second int64_t of rate
                        break; // ignore on Java side
                    default:
                        // These are unsupported types for version 0
                        // We ignore them if the version is greater than 0.
                        if (version == 0) {
                            throw new IllegalArgumentException(
                                    "Property " + propKey + " has unsupported type " + type);
                        }
                        buffer.position(initialBufferPosition + propSize); // advance and skip
                        break;
                }
                final int deltaPosition = buffer.position() - initialBufferPosition;
                if (deltaPosition != propSize) {
                    throw new IllegalArgumentException("propSize:" + propSize
                        + " != deltaPosition:" + deltaPosition);
                }
            }

            final int finalPosition = buffer.position();
            if (finalPosition != totalSize) {
                throw new IllegalArgumentException("totalSize:" + totalSize
                    + " != finalPosition:" + finalPosition);
            }
            return bundle;
        }

        // Version 0 byte offsets for the header.
        private static final int FORMAT_VERSION = 0;
        private static final int TOTAL_SIZE_OFFSET = 0;
        private static final int HEADER_SIZE_OFFSET = 4;
        private static final int MINIMUM_PAYLOAD_SIZE = 4;
        private final int mPidOffset;            // computed in constructor
        private final int mUidOffset;            // computed in constructor
        private final int mTimeNsOffset;         // computed in constructor
        private final int mPropertyCountOffset;  // computed in constructor
        private final int mPropertyStartOffset;  // computed in constructor
        private final int mHeaderSize;           // computed in constructor

        private final String mKey;

        private ByteBuffer mBuffer;     // may be reallocated if capacity is insufficient.
        private int mPropertyCount = 0; // overflow not checked (mBuffer would overflow first).

        private int reserveProperty(byte[] keyBytes, int payloadSize) {
            final int keyLength = keyBytes.length;
            if (keyLength > Character.MAX_VALUE) {
                throw new IllegalStateException("property key too long "
                        + new String(keyBytes, MEDIAMETRICS_CHARSET));
            }
            if (payloadSize > Character.MAX_VALUE) {
                throw new IllegalStateException("payload too large " + payloadSize);
            }

            // See the byte string property format above.
            final int size = 2      /* length */
                    + 1             /* type */
                    + keyLength + 1 /* key length with zero termination */
                    + payloadSize;  /* payload size */

            if (size > Character.MAX_VALUE) {
                throw new IllegalStateException("Item property "
                        + new String(keyBytes, MEDIAMETRICS_CHARSET) + " is too large to send");
            }

            if (mBuffer.remaining() < size) {
                int newCapacity = mBuffer.position() + size;
                if (newCapacity > Integer.MAX_VALUE >> 1) {
                    throw new IllegalStateException(
                        "Item memory requirements too large: " + newCapacity);
                }
                newCapacity <<= 1;
                ByteBuffer buffer = ByteBuffer.allocateDirect(newCapacity);
                buffer.order(ByteOrder.nativeOrder());

                // Copy data from old buffer to new buffer.
                mBuffer.flip();
                buffer.put(mBuffer);

                // set buffer to new buffer
                mBuffer = buffer;
            }
            return size;
        }

        // Used for test
        private static String getStringFromBuffer(ByteBuffer buffer) {
            return getStringFromBuffer(buffer, Integer.MAX_VALUE);
        }

        // Used for test
        private static String getStringFromBuffer(ByteBuffer buffer, int size) {
            int i = buffer.position();
            int limit = buffer.limit();
            if (size < Integer.MAX_VALUE - i && i + size < limit) {
                limit = i + size;
            }
            for (; i < limit; ++i) {
                if (buffer.get(i) == 0) {
                    final int newPosition = i + 1;
                    if (size != Integer.MAX_VALUE && newPosition - buffer.position() != size) {
                        throw new IllegalArgumentException("chars consumed at " + i + ": "
                            + (newPosition - buffer.position()) + " != size: " + size);
                    }
                    final String found;
                    if (buffer.hasArray()) {
                        found = new String(
                            buffer.array(), buffer.position() + buffer.arrayOffset(),
                            i - buffer.position(), MEDIAMETRICS_CHARSET);
                        buffer.position(newPosition);
                    } else {
                        final byte[] array = new byte[i - buffer.position()];
                        buffer.get(array);
                        found = new String(array, MEDIAMETRICS_CHARSET);
                        buffer.get(); // remove 0.
                    }
                    return found;
                }
            }
            throw new IllegalArgumentException(
                    "No zero termination found in string position: "
                    + buffer.position() + " end: " + i);
        }

        /**
         * May be called multiple times - just makes the header consistent with the current
         * properties written.
         */
        private void updateHeader() {
            // Buffer sized properly in constructor.
            mBuffer.putInt(TOTAL_SIZE_OFFSET, mBuffer.position())      // set total length
                .putInt(mPropertyCountOffset, (char) mPropertyCount); // set number of properties
        }
    }

    private static native int native_submit_bytebuffer(@NonNull ByteBuffer buffer, int length);
}
