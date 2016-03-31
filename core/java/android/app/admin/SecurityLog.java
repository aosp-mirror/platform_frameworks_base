/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.app.admin;

import android.annotation.IntDef;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemProperties;
import android.util.EventLog.Event;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collection;

public class SecurityLog {

    private static final String PROPERTY_LOGGING_ENABLED = "persist.logd.security";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({TAG_ADB_SHELL_INTERACTIVE, TAG_ADB_SHELL_CMD, TAG_SYNC_RECV_FILE, TAG_SYNC_SEND_FILE,
        TAG_APP_PROCESS_START, TAG_KEYGUARD_DISMISSED, TAG_KEYGUARD_DISMISS_AUTH_ATTEMPT,
        TAG_KEYGUARD_SECURED})
    public @interface SECURITY_LOG_TAG {}

    /**
     * Indicate that an ADB interactive shell was opened via "adb shell".
     * There is no extra payload in the log event.
     */
    public static final int TAG_ADB_SHELL_INTERACTIVE =
            SecurityLogTags.SECURITY_ADB_SHELL_INTERACTIVE;
    /**
     * Indicate that an shell command was issued over ADB via "adb shell command"
     * The log entry contains a string data of the shell command, accessible via
     * {@link SecurityEvent#getData()}
     */
    public static final int TAG_ADB_SHELL_CMD = SecurityLogTags.SECURITY_ADB_SHELL_COMMAND;
    /**
     * Indicate that a file was pulled from the device via the adb daemon, for example via
     * "adb pull". The log entry contains a string data of the path of the pulled file,
     * accessible via {@link SecurityEvent#getData()}
     */
    public static final int TAG_SYNC_RECV_FILE = SecurityLogTags.SECURITY_ADB_SYNC_RECV;
    /**
     * Indicate that a file was pushed to the device via the adb daemon, for example via
     * "adb push". The log entry contains a string data of the destination path of the
     * pushed file, accessible via {@link SecurityEvent#getData()}
     */
    public static final int TAG_SYNC_SEND_FILE = SecurityLogTags.SECURITY_ADB_SYNC_SEND;
    /**
     * Indicate that an app process was started. The log entry contains the following
     * information about the process encapsulated in an {@link Object} array, accessible via
     * {@link SecurityEvent#getData()}:
     * process name (String), exact start time (long), app Uid (integer), app Pid (integer),
     * seinfo tag (String), SHA-256 hash of the base APK in hexadecimal (String)
     */
    public static final int TAG_APP_PROCESS_START = SecurityLogTags.SECURITY_APP_PROCESS_START;
    /**
     * Indicate that keyguard is being dismissed.
     * There is no extra payload in the log event.
     */
    public static final int TAG_KEYGUARD_DISMISSED =
            SecurityLogTags.SECURITY_KEYGUARD_DISMISSED;
    /**
     * Indicate that there has been an authentication attempt to dismiss the keyguard. The log entry
     * contains the following information about the attempt encapsulated in an {@link Object} array,
     * accessible via {@link SecurityEvent#getData()}:
     * attempt result (integer, 1 for successful, 0 for unsuccessful), strength of auth method
     * (integer, 1 if strong auth method was used, 0 otherwise)
     */
    public static final int TAG_KEYGUARD_DISMISS_AUTH_ATTEMPT =
            SecurityLogTags.SECURITY_KEYGUARD_DISMISS_AUTH_ATTEMPT;
    /**
     * Indicate that the device has been locked, either by user or by timeout.
     * There is no extra payload in the log event.
     */
    public static final int TAG_KEYGUARD_SECURED = SecurityLogTags.SECURITY_KEYGUARD_SECURED;

    /**
     * Returns if security logging is enabled. Log producers should only write new logs if this is
     * true. Under the hood this is the logical AND of whether device owner exists and whether
     * it enables logging by setting the system property {@link #PROPERTY_LOGGING_ENABLED}.
     * @hide
     */
    public static native boolean isLoggingEnabled();

    /**
     * @hide
     */
    public static void setLoggingEnabledProperty(boolean enabled) {
        SystemProperties.set(PROPERTY_LOGGING_ENABLED, enabled ? "true" : "false");
    }

    /**
     * @hide
     */
    public static boolean getLoggingEnabledProperty() {
        return SystemProperties.getBoolean(PROPERTY_LOGGING_ENABLED, false);
    }

    /**
     * A class representing a security event log entry.
     */
    public static final class SecurityEvent implements Parcelable {
        private Event mEvent;

        /** @hide */
        /*package*/ SecurityEvent(byte[] data) {
            mEvent = Event.fromBytes(data);
        }

        /**
         * Returns the timestamp in nano seconds when this event was logged.
         */
        public long getTimeNanos() {
            return mEvent.getTimeNanos();
        }

        /**
         * Returns the tag of this log entry, which specifies entry's semantics.
         * Could be one of {@link SecurityLog#TAG_SYNC_RECV_FILE},
         * {@link SecurityLog#TAG_SYNC_SEND_FILE}, {@link SecurityLog#TAG_ADB_SHELL_CMD},
         * {@link SecurityLog#TAG_ADB_SHELL_INTERACTIVE}, {@link SecurityLog#TAG_APP_PROCESS_START},
         * {@link SecurityLog#TAG_KEYGUARD_DISMISSED}, {@link SecurityLog#TAG_KEYGUARD_SECURED},
         * {@link SecurityLog#TAG_KEYGUARD_DISMISS_AUTH_ATTEMPT}.
         */
        public @SECURITY_LOG_TAG int getTag() {
            return mEvent.getTag();
        }

        /**
         * Returns the payload contained in this log. Each call to this method will
         * retrieve the next payload item. If no more payload exists, it returns {@code null}.
         */
        public Object getData() {
            return mEvent.getData();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeByteArray(mEvent.getBytes());
        }

        public static final Parcelable.Creator<SecurityEvent> CREATOR =
                new Parcelable.Creator<SecurityEvent>() {
            @Override
            public SecurityEvent createFromParcel(Parcel source) {
                return new SecurityEvent(source.createByteArray());
            }

            @Override
            public SecurityEvent[] newArray(int size) {
                return new SecurityEvent[size];
            }
        };
    }
    /**
     * Retrieve all security logs and return immediately.
     * @hide
     */
    public static native void readEvents(Collection<SecurityEvent> output) throws IOException;

    /**
     * Retrieve all security logs since the given timestamp in nanoseconds and return immediately.
     * @hide
     */
    public static native void readEventsSince(long timestamp, Collection<SecurityEvent> output)
            throws IOException;

    /**
     * Retrieve all security logs before the last reboot. May return corrupted data due to
     * unreliable pstore.
     * @hide
     */
    public static native void readPreviousEvents(Collection<SecurityEvent> output)
            throws IOException;

    /**
     * Retrieve all security logs whose timestamp (in nanosceonds) is equal to or greater than the
     * given timestamp. This method will block until either the last log earlier than the given
     * timestamp is about to be pruned, or after a 2-hour timeout has passed.
     * @hide
     */
    public static native void readEventsOnWrapping(long timestamp, Collection<SecurityEvent> output)
            throws IOException;

    /**
     * Write a log entry to the underlying storage, with a string payload.
     * @hide
     */
    public static native int writeEvent(int tag, String str);

    /**
     * Write a log entry to the underlying storage, with several payloads.
     * Supported types of payload are: integer, long, float, string plus array of supported types.
     * @hide
     */
    public static native int writeEvent(int tag, Object... payloads);
}
