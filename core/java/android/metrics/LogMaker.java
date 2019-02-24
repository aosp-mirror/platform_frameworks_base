/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.metrics;

import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.content.ComponentName;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;



/**
 * Helper class to assemble more complex logs.
 *
 * @hide
 */
@SystemApi
@TestApi
public class LogMaker {
    private static final String TAG = "LogBuilder";

    /**
     * Min required eventlog line length.
     * See: android/util/cts/EventLogTest.java
     * Size checks enforced here are intended only as sanity checks;
     * your logs may be truncated earlier. Please log responsibly.
     *
     * @hide
     */
    @VisibleForTesting
    public static final int MAX_SERIALIZED_SIZE = 4000;

    private SparseArray<Object> entries = new SparseArray();

    /** @param category for the new LogMaker. */
    public LogMaker(int category) {
        setCategory(category);
    }

    /* Deserialize from the eventlog */
    public LogMaker(Object[] items) {
        if (items != null) {
            deserialize(items);
        } else {
            setCategory(MetricsEvent.VIEW_UNKNOWN);
        }
    }

    /** @param category to replace the existing setting. */
    public LogMaker setCategory(int category) {
        entries.put(MetricsEvent.RESERVED_FOR_LOGBUILDER_CATEGORY, category);
        return this;
    }

    /** Set the category to unknown. */
    public LogMaker clearCategory() {
        entries.remove(MetricsEvent.RESERVED_FOR_LOGBUILDER_CATEGORY);
        return this;
    }

    /** @param type to replace the existing setting. */
    public LogMaker setType(int type) {
        entries.put(MetricsEvent.RESERVED_FOR_LOGBUILDER_TYPE, type);
        return this;
    }

    /** Set the type to unknown. */
    public LogMaker clearType() {
        entries.remove(MetricsEvent.RESERVED_FOR_LOGBUILDER_TYPE);
        return this;
    }

    /** @param subtype to replace the existing setting. */
    public LogMaker setSubtype(int subtype) {
        entries.put(MetricsEvent.RESERVED_FOR_LOGBUILDER_SUBTYPE, subtype);
        return this;
    }

    /** Set the subtype to 0. */
    public LogMaker clearSubtype() {
        entries.remove(MetricsEvent.RESERVED_FOR_LOGBUILDER_SUBTYPE);
        return this;
    }

    /**
     * Set event latency.
     *
     * @hide // TODO Expose in the future?  Too late for O.
     */
    public LogMaker setLatency(long milliseconds) {
        entries.put(MetricsEvent.RESERVED_FOR_LOGBUILDER_LATENCY_MILLIS, milliseconds);
        return this;
    }

    /**
     * This will be set by the system when the log is persisted.
     * Client-supplied values will be ignored.
     *
     * @param timestamp to replace the existing settings.
     * @hide
     */
    public LogMaker setTimestamp(long timestamp) {
        entries.put(MetricsEvent.RESERVED_FOR_LOGBUILDER_TIMESTAMP, timestamp);
        return this;
    }

    /** Remove the timestamp property.
     * @hide
     */
    public LogMaker clearTimestamp() {
        entries.remove(MetricsEvent.RESERVED_FOR_LOGBUILDER_TIMESTAMP);
        return this;
    }

    /** @param packageName to replace the existing setting. */
    public LogMaker setPackageName(String packageName) {
        entries.put(MetricsEvent.RESERVED_FOR_LOGBUILDER_PACKAGENAME, packageName);
        return this;
    }

    /**
     * @param component to replace the existing setting.
     * @hide
     */
    public LogMaker setComponentName(ComponentName component) {
        entries.put(MetricsEvent.RESERVED_FOR_LOGBUILDER_PACKAGENAME, component.getPackageName());
        entries.put(MetricsEvent.FIELD_CLASS_NAME, component.getClassName());
        return this;
    }

    /** Remove the package name property. */
    public LogMaker clearPackageName() {
        entries.remove(MetricsEvent.RESERVED_FOR_LOGBUILDER_PACKAGENAME);
        return this;
    }

    /**
     * This will be set by the system when the log is persisted.
     * Client-supplied values will be ignored.
     *
     * @param pid to replace the existing setting.
     * @hide
     */
    public LogMaker setProcessId(int pid) {
        entries.put(MetricsEvent.RESERVED_FOR_LOGBUILDER_PID, pid);
        return this;
    }

    /** Remove the process ID property.
     * @hide
     */
    public LogMaker clearProcessId() {
        entries.remove(MetricsEvent.RESERVED_FOR_LOGBUILDER_PID);
        return this;
    }

    /**
     * This will be set by the system when the log is persisted.
     * Client-supplied values will be ignored.
     *
     * @param uid to replace the existing setting.
     * @hide
     */
    public LogMaker setUid(int uid) {
        entries.put(MetricsEvent.RESERVED_FOR_LOGBUILDER_UID, uid);
        return this;
    }

    /**
     * Remove the UID property.
     * @hide
     */
    public LogMaker clearUid() {
        entries.remove(MetricsEvent.RESERVED_FOR_LOGBUILDER_UID);
        return this;
    }

    /**
     * The name of the counter or histogram.
     * Only useful for counter or histogram category objects.
     * @param name to replace the existing setting.
     * @hide
     */
    public LogMaker setCounterName(String name) {
        entries.put(MetricsEvent.RESERVED_FOR_LOGBUILDER_NAME, name);
        return this;
    }

    /**
     * The bucket label, expressed as an integer.
     * Only useful for histogram category objects.
     * @param bucket to replace the existing setting.
     * @hide
     */
    public LogMaker setCounterBucket(int bucket) {
        entries.put(MetricsEvent.RESERVED_FOR_LOGBUILDER_BUCKET, bucket);
        return this;
    }

    /**
     * The bucket label, expressed as a long integer.
     * Only useful for histogram category objects.
     * @param bucket to replace the existing setting.
     * @hide
     */
    public LogMaker setCounterBucket(long bucket) {
        entries.put(MetricsEvent.RESERVED_FOR_LOGBUILDER_BUCKET, bucket);
        return this;
    }

    /**
     * The value to increment the counter or bucket by.
     * Only useful for counter and histogram category objects.
     * @param value to replace the existing setting.
     * @hide
     */
    public LogMaker setCounterValue(int value) {
        entries.put(MetricsEvent.RESERVED_FOR_LOGBUILDER_VALUE, value);
        return this;
    }

    /**
     * @param tag From your MetricsEvent enum.
     * @param value One of Integer, Long, Float, or String; or null to clear the tag.
     * @return modified LogMaker
     */
    public LogMaker addTaggedData(int tag, Object value) {
        if (value == null) {
            return clearTaggedData(tag);
        }
        if (!isValidValue(value)) {
            throw new IllegalArgumentException(
                    "Value must be loggable type - int, long, float, String");
        }
        if (value.toString().getBytes().length > MAX_SERIALIZED_SIZE) {
            Log.i(TAG, "Log value too long, omitted: " + value.toString());
        } else {
            entries.put(tag, value);
        }
        return this;
    }

    /**
     * Remove a value from the LogMaker.
     *
     * @param tag From your MetricsEvent enum.
     * @return modified LogMaker
     */
    public LogMaker clearTaggedData(int tag) {
        entries.delete(tag);
        return this;
    }

    /**
     * @return true if this object may be added to a LogMaker as a value.
     */
    public boolean isValidValue(Object value) {
        return value instanceof Integer ||
            value instanceof String ||
            value instanceof Long ||
            value instanceof Float;
    }

    public Object getTaggedData(int tag) {
        return entries.get(tag);
    }

    /** @return the category of the log, or unknown. */
    public int getCategory() {
        Object obj = entries.get(MetricsEvent.RESERVED_FOR_LOGBUILDER_CATEGORY);
        if (obj instanceof Integer) {
            return (Integer) obj;
        } else {
            return MetricsEvent.VIEW_UNKNOWN;
        }
    }

    /** @return the type of the log, or unknwon. */
    public int getType() {
        Object obj = entries.get(MetricsEvent.RESERVED_FOR_LOGBUILDER_TYPE);
        if (obj instanceof Integer) {
            return (Integer) obj;
        } else {
            return MetricsEvent.TYPE_UNKNOWN;
        }
    }

    /** @return the subtype of the log, or 0. */
    public int getSubtype() {
        Object obj = entries.get(MetricsEvent.RESERVED_FOR_LOGBUILDER_SUBTYPE);
        if (obj instanceof Integer) {
            return (Integer) obj;
        } else {
            return 0;
        }
    }

    /** @return the timestamp of the log.or 0 */
    public long getTimestamp() {
        Object obj = entries.get(MetricsEvent.RESERVED_FOR_LOGBUILDER_TIMESTAMP);
        if (obj instanceof Long) {
            return (Long) obj;
        } else {
            return 0;
        }
    }

    /** @return the package name of the log, or null. */
    public String getPackageName() {
        Object obj = entries.get(MetricsEvent.RESERVED_FOR_LOGBUILDER_PACKAGENAME);
        if (obj instanceof String) {
            return (String) obj;
        } else {
            return null;
        }
    }

    /** @return the process ID of the log, or -1. */
    public int getProcessId() {
        Object obj = entries.get(MetricsEvent.RESERVED_FOR_LOGBUILDER_PID);
        if (obj instanceof Integer) {
            return (Integer) obj;
        } else {
            return -1;
        }
    }

    /** @return the UID of the log, or -1. */
    public int getUid() {
        Object obj = entries.get(MetricsEvent.RESERVED_FOR_LOGBUILDER_UID);
        if (obj instanceof Integer) {
            return (Integer) obj;
        } else {
            return -1;
        }
    }

    /** @return the name of the counter, or null. */
    public String getCounterName() {
        Object obj = entries.get(MetricsEvent.RESERVED_FOR_LOGBUILDER_NAME);
        if (obj instanceof String) {
            return (String) obj;
        } else {
            return null;
        }
    }

    /** @return the bucket label of the histogram\, or 0. */
    public long getCounterBucket() {
        Object obj = entries.get(MetricsEvent.RESERVED_FOR_LOGBUILDER_BUCKET);
        if (obj instanceof Number) {
            return ((Number) obj).longValue();
        } else {
            return 0L;
        }
    }

    /** @return true if the bucket label was specified as a long integer. */
    public boolean isLongCounterBucket() {
        Object obj = entries.get(MetricsEvent.RESERVED_FOR_LOGBUILDER_BUCKET);
        return obj instanceof Long;
    }

    /** @return the increment value of the counter, or 0. */
    public int getCounterValue() {
        Object obj = entries.get(MetricsEvent.RESERVED_FOR_LOGBUILDER_VALUE);
        if (obj instanceof Integer) {
            return (Integer) obj;
        } else {
            return 0;
        }
    }

    /**
     * @return a representation of the log suitable for EventLog.
     */
    public Object[] serialize() {
        Object[] out = new Object[entries.size() * 2];
        for (int i = 0; i < entries.size(); i++) {
            out[i * 2] = entries.keyAt(i);
            out[i * 2 + 1] = entries.valueAt(i);
        }
        int size = out.toString().getBytes().length;
        if (size > MAX_SERIALIZED_SIZE) {
            Log.i(TAG, "Log line too long, did not emit: " + size + " bytes.");
            throw new RuntimeException();
        }
        return out;
    }

    /**
     * Reconstitute an object from the output of {@link #serialize()}.
     */
    public void deserialize(Object[] items) {
        int i = 0;
        while (items != null && i < items.length) {
            Object key = items[i++];
            Object value = i < items.length ? items[i++] : null;
            if (key instanceof Integer) {
                entries.put((Integer) key, value);
            } else {
                Log.i(TAG, "Invalid key " + (key == null ? "null" : key.toString()));
            }
        }
    }

    /**
     * @param that the object to compare to.
     * @return true if values in that equal values in this, for tags that exist in this.
     */
    public boolean isSubsetOf(LogMaker that) {
        if (that == null) {
            return false;
        }
        for (int i = 0; i < entries.size(); i++) {
            int key = this.entries.keyAt(i);
            Object thisValue = this.entries.valueAt(i);
            Object thatValue = that.entries.get(key);
            if ((thisValue == null && thatValue != null) || !thisValue.equals(thatValue))
                return false;
        }
        return true;
    }

    /**
     * @return entries containing key value pairs.
     * @hide
     */
    public SparseArray<Object> getEntries() {
        return entries;
    }
}
