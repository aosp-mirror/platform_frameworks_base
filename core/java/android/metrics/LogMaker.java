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

    public LogMaker(int mainCategory) {
        setCategory(mainCategory);
    }

    /* Deserialize from the eventlog */
    public LogMaker(Object[] items) {
      deserialize(items);
    }

    public LogMaker setCategory(int category) {
        entries.put(MetricsEvent.RESERVED_FOR_LOGBUILDER_CATEGORY, category);
        return this;
    }

    public LogMaker setType(int type) {
        entries.put(MetricsEvent.RESERVED_FOR_LOGBUILDER_TYPE, type);
        return this;
    }

    public LogMaker setSubtype(int subtype) {
        entries.put(MetricsEvent.RESERVED_FOR_LOGBUILDER_SUBTYPE, subtype);
        return this;
    }

    public LogMaker setTimestamp(long timestamp) {
        entries.put(MetricsEvent.RESERVED_FOR_LOGBUILDER_TIMESTAMP, timestamp);
        return this;
    }

    public LogMaker setPackageName(String packageName) {
        entries.put(MetricsEvent.RESERVED_FOR_LOGBUILDER_PACKAGENAME, packageName);
        return this;
    }

    public LogMaker setCounterName(String name) {
        entries.put(MetricsEvent.RESERVED_FOR_LOGBUILDER_NAME, name);
        return this;
    }

    public LogMaker setCounterBucket(int bucket) {
        entries.put(MetricsEvent.RESERVED_FOR_LOGBUILDER_BUCKET, bucket);
        return this;
    }

    public LogMaker setCounterBucket(long bucket) {
        entries.put(MetricsEvent.RESERVED_FOR_LOGBUILDER_BUCKET, bucket);
        return this;
    }

    public LogMaker setCounterValue(int value) {
        entries.put(MetricsEvent.RESERVED_FOR_LOGBUILDER_VALUE, value);
        return this;
    }

    /**
     * @param tag From your MetricsEvent enum.
     * @param value One of Integer, Long, Float, String
     * @return
     */
    public LogMaker addTaggedData(int tag, Object value) {
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

    public boolean isValidValue(Object value) {
        if (value == null) {
            Log.i("LogBuilder", "Logging a null value.");
            return true;
        }
        return value instanceof Integer ||
            value instanceof String ||
            value instanceof Long ||
            value instanceof Float;
    }

    public Object getTaggedData(int tag) {
        return entries.get(tag);
    }

    public int getCategory() {
        Object obj = entries.get(MetricsEvent.RESERVED_FOR_LOGBUILDER_CATEGORY);
        if (obj instanceof Integer) {
            return (Integer) obj;
        } else {
            return MetricsEvent.VIEW_UNKNOWN;
        }
    }

    public int getType() {
        Object obj = entries.get(MetricsEvent.RESERVED_FOR_LOGBUILDER_TYPE);
        if (obj instanceof Integer) {
            return (Integer) obj;
        } else {
            return MetricsEvent.TYPE_UNKNOWN;
        }
    }

    public int getSubtype() {
        Object obj = entries.get(MetricsEvent.RESERVED_FOR_LOGBUILDER_SUBTYPE);
        if (obj instanceof Integer) {
            return (Integer) obj;
        } else {
            return 0;
        }
    }

    public long getTimestamp() {
        Object obj = entries.get(MetricsEvent.RESERVED_FOR_LOGBUILDER_TIMESTAMP);
        if (obj instanceof Long) {
            return (Long) obj;
        } else {
            return 0;
        }
    }

    public String getPackageName() {
        Object obj = entries.get(MetricsEvent.RESERVED_FOR_LOGBUILDER_PACKAGENAME);
        if (obj instanceof String) {
            return (String) obj;
        } else {
            return null;
        }
    }

    public String getCounterName() {
        Object obj = entries.get(MetricsEvent.RESERVED_FOR_LOGBUILDER_NAME);
        if (obj instanceof String) {
            return (String) obj;
        } else {
            return null;
        }
    }

    public long getCounterBucket() {
        Object obj = entries.get(MetricsEvent.RESERVED_FOR_LOGBUILDER_BUCKET);
        if (obj instanceof Number) {
            return ((Number) obj).longValue();
        } else {
            return 0L;
        }
    }

    public boolean isLongCounterBucket() {
        Object obj = entries.get(MetricsEvent.RESERVED_FOR_LOGBUILDER_BUCKET);
        return obj instanceof Long;
    }

    public int getCounterValue() {
        Object obj = entries.get(MetricsEvent.RESERVED_FOR_LOGBUILDER_VALUE);
        if (obj instanceof Integer) {
            return (Integer) obj;
        } else {
            return 0;
        }
    }

    /**
     * Assemble logs into structure suitable for EventLog.
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

    public void deserialize(Object[] items) {
        int i = 0;
        while (i < items.length) {
            Object key = items[i++];
            Object value = i < items.length ? items[i++] : null;
            if (key instanceof Integer) {
                entries.put((Integer) key, value);
            } else {
                Log.i(TAG, "Invalid key " + key.toString());
            }
        }
    }
}
