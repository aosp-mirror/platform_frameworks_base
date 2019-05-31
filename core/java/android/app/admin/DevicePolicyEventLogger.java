/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.Nullable;
import android.content.ComponentName;
import android.stats.devicepolicy.nano.StringList;
import android.util.StatsLog;

import com.android.framework.protobuf.nano.MessageNano;
import com.android.internal.util.Preconditions;

import java.util.Arrays;

/**
 * A wrapper for logging managed device events using {@link StatsLog}.
 * <p/>
 * This class allows chaining - each of its methods returns a reference to the current instance.
 * <p/>
 * Example usage:
 * <code><pre>
 * import android.stats.devicepolicy.DevicePolicyEnums;
 *
 * DevicePolicyEventLogger
 *     .createEvent(DevicePolicyEnums.USER_RESTRICTION_CHANGED)
 *     .setAdmin(who)
 *     .setString(key)
 *     .setBoolean(enabledFromThisOwner)
 *     .write();
 * </pre></code>
 *
 * @see StatsLog
 * @hide
 */
public class DevicePolicyEventLogger {
    private final int mEventId;
    private int mIntValue;
    private boolean mBooleanValue;
    private long mTimePeriodMs;
    private String[] mStringArrayValue;
    private String mAdminPackageName;

    private DevicePolicyEventLogger(int eventId) {
        mEventId = eventId;
    }

    /**
     * Creates a new {@link DevicePolicyEventLogger} instance for the specified
     * <code>eventId</code>.
     *
     * @param eventId one of {@link android.stats.devicepolicy.DevicePolicyEnums} as defined in
     * <code>core/proto/android/stats/devicepolicy/device_policy_enums.proto</code>
     */
    public static DevicePolicyEventLogger createEvent(int eventId) {
        return new DevicePolicyEventLogger(eventId);
    }

    /**
     * Returns the event id.
     */
    public int getEventId() {
        return mEventId;
    }

    /**
     * Sets a generic <code>int</code> value.
     */
    public DevicePolicyEventLogger setInt(int value) {
        mIntValue = value;
        return this;
    }

    /**
     * Returns the generic <code>int</code> value.
     */
    public int getInt() {
        return mIntValue;
    }

    /**
     * Sets a generic <code>boolean</code> value.
     */
    public DevicePolicyEventLogger setBoolean(boolean value) {
        mBooleanValue = value;
        return this;
    }

    /**
     * Returns the generic <code>boolean</code> value.
     */
    public boolean getBoolean() {
        return mBooleanValue;
    }

    /**
     * Sets a time period in milliseconds.
     */
    public DevicePolicyEventLogger setTimePeriod(long timePeriodMillis) {
        mTimePeriodMs = timePeriodMillis;
        return this;
    }

    /**
     * Returns the time period in milliseconds.
     */
    public long getTimePeriod() {
        return mTimePeriodMs;
    }

    /**
     * Sets generic <code>String</code> values.
     */
    public DevicePolicyEventLogger setStrings(String... values) {
        mStringArrayValue = values;
        return this;
    }

    /**
     * Sets generic <code>String</code> values.
     * <p/>
     * {@link #write()} logs the concatenation of <code>value</code> and <code>values</code>,
     * in that order.
     */
    public DevicePolicyEventLogger setStrings(String value, String[] values) {
        Preconditions.checkNotNull(values, "values parameter cannot be null");
        mStringArrayValue = new String[values.length + 1];
        mStringArrayValue[0] = value;
        System.arraycopy(values, 0, mStringArrayValue, 1, values.length);
        return this;
    }

    /**
     * Sets generic <code>String</code> values.
     * <p/>
     * {@link #write()} logs the concatenation of <code>value1</code>, <code>value2</code>
     * and <code>values</code>, in that order.
     */
    public DevicePolicyEventLogger setStrings(String value1, String value2, String[] values) {
        Preconditions.checkNotNull(values, "values parameter cannot be null");
        mStringArrayValue = new String[values.length + 2];
        mStringArrayValue[0] = value1;
        mStringArrayValue[1] = value2;
        System.arraycopy(values, 0, mStringArrayValue, 2, values.length);
        return this;
    }

    /**
     * Returns a copy of the generic <code>String[]</code> value.
     */
    public String[] getStringArray() {
        if (mStringArrayValue == null) {
            return null;
        }
        return Arrays.copyOf(mStringArrayValue, mStringArrayValue.length);
    }

    /**
     * Sets the package name of the admin application.
     */
    public DevicePolicyEventLogger setAdmin(@Nullable String packageName) {
        mAdminPackageName = packageName;
        return this;
    }

    /**
     * Sets the package name of the admin application from the {@link ComponentName}.
     */
    public DevicePolicyEventLogger setAdmin(@Nullable ComponentName componentName) {
        mAdminPackageName = (componentName != null ? componentName.getPackageName() : null);
        return this;
    }

    /**
     * Returns the package name of the admin application.
     */
    public String getAdminPackageName() {
        return mAdminPackageName;
    }

    /**
     * Writes the metric to {@link StatsLog}.
     */
    public void write() {
        byte[] bytes = stringArrayValueToBytes(mStringArrayValue);
        StatsLog.write(StatsLog.DEVICE_POLICY_EVENT, mEventId, mAdminPackageName, mIntValue,
                mBooleanValue, mTimePeriodMs, bytes);
    }

    /**
     * Converts the <code>String[] array</code> to <code>byte[]</code>.
     * <p/>
     * We can't log <code>String[]</code> using {@link StatsLog}. The convention is to assign
     * the array to a proto object and convert it to <code>byte[]</code>.
     */
    private static byte[] stringArrayValueToBytes(String[] array) {
        if (array == null) {
            return null;
        }
        StringList stringList = new StringList();
        stringList.stringValue = array;
        return MessageNano.toByteArray(stringList);
    }
}
