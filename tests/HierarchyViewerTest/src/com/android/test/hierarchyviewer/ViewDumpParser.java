package com.android.test.hierarchyviewer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ViewDumpParser {
    private Map<String, Short> mIds;
    private List<Map<Short,Object>> mViews;

    public void parse(byte[] data) {
        Decoder d = new Decoder(ByteBuffer.wrap(data));

        mViews = new ArrayList<>(100);
        while (d.hasRemaining()) {
            Object o = d.readObject();
            if (o instanceof Map) {
                //noinspection unchecked
                mViews.add((Map<Short, Object>) o);
            }
        }

        if (mViews.isEmpty()) {
            return;
        }

        // the last one is the property map
        Map<Short,Object> idMap = mViews.remove(mViews.size() - 1);
        mIds = reverse(idMap);
    }

    public String getFirstView() {
        if (mViews.isEmpty()) {
            return null;
        }

        Map<Short, Object> props = mViews.get(0);
        Object name = getProperty(props, "__name__");
        Object hash = getProperty(props, "__hash__");

        if (name instanceof String && hash instanceof Integer) {
            return String.format(Locale.US, "%s@%x", name, hash);
        } else {
            return null;
        }
    }

    private Object getProperty(Map<Short, Object> props, String key) {
        return props.get(mIds.get(key));
    }

    private static Map<String, Short> reverse(Map<Short, Object> m) {
        Map<String, Short> r = new HashMap<String, Short>(m.size());

        for (Map.Entry<Short, Object> e : m.entrySet()) {
            r.put((String)e.getValue(), e.getKey());
        }

        return r;
    }

    public List<Map<Short, Object>> getViews() {
        return mViews;
    }

    public Map<String, Short> getIds() {
        return mIds;
    }

}
