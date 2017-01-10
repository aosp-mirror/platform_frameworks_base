package com.android.internal.logging;

import android.util.EventLog;
import android.util.SparseArray;
import android.view.View;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;


/**
 * Helper class to assemble more complex logs.
 *
 * @hide
 */

public class LogBuilder {

    private SparseArray<Object> entries = new SparseArray();

    public LogBuilder(int mainCategory) {
        setCategory(mainCategory);
    }

    public LogBuilder setView(View view) {
        entries.put(MetricsEvent.RESERVED_FOR_LOGBUILDER_VIEW, view.getId());
        return this;
    }

    public LogBuilder setCategory(int category) {
        entries.put(MetricsEvent.RESERVED_FOR_LOGBUILDER_CATEGORY, category);
        return this;
    }

    public LogBuilder setType(int type) {
        entries.put(MetricsEvent.RESERVED_FOR_LOGBUILDER_TYPE, type);
        return this;
    }

    /**
     * @param tag From your MetricsEvent enum.
     * @param value One of Integer, Long, Float, String
     * @return
     */
    public LogBuilder addTaggedData(int tag, Object value) {
        if (!(value instanceof Integer ||
            value instanceof String ||
            value instanceof Long ||
            value instanceof Float)) {
            throw new IllegalArgumentException(
                    "Value must be loggable type - int, long, float, String");
        }
        entries.put(tag, value);
        return this;
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
        return out;
    }
}

