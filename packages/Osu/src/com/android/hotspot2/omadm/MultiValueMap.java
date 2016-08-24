package com.android.hotspot2.omadm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MultiValueMap<T> {
    private final Map<String, ArrayList<T>> mMap = new LinkedHashMap<>();

    public void put(String key, T value) {
        key = key.toLowerCase();
        ArrayList<T> values = mMap.get(key);
        if (values == null) {
            values = new ArrayList<>();
            mMap.put(key, values);
        }
        values.add(value);
    }

    public T get(String key) {
        key = key.toLowerCase();
        List<T> values = mMap.get(key);
        if (values == null) {
            return null;
        } else if (values.size() == 1) {
            return values.get(0);
        } else {
            throw new IllegalArgumentException("Cannot do get on multi-value");
        }
    }

    public T replace(String key, T oldValue, T newValue) {
        key = key.toLowerCase();
        List<T> values = mMap.get(key);
        if (values == null) {
            return null;
        }

        for (int n = 0; n < values.size(); n++) {
            T value = values.get(n);
            if (value == oldValue) {
                values.set(n, newValue);
                return value;
            }
        }
        return null;
    }

    public T remove(String key, T value) {
        key = key.toLowerCase();
        List<T> values = mMap.get(key);
        if (values == null) {
            return null;
        }

        T result = null;
        Iterator<T> valueIterator = values.iterator();
        while (valueIterator.hasNext()) {
            if (valueIterator.next() == value) {
                valueIterator.remove();
                result = value;
                break;
            }
        }
        if (values.isEmpty()) {
            mMap.remove(key);
        }
        return result;
    }

    public T remove(T value) {
        T result = null;
        Iterator<Map.Entry<String, ArrayList<T>>> iterator = mMap.entrySet().iterator();
        while (iterator.hasNext()) {
            ArrayList<T> values = iterator.next().getValue();
            Iterator<T> valueIterator = values.iterator();
            while (valueIterator.hasNext()) {
                if (valueIterator.next() == value) {
                    valueIterator.remove();
                    result = value;
                    break;
                }
            }
            if (result != null) {
                if (values.isEmpty()) {
                    iterator.remove();
                }
                break;
            }
        }
        return result;
    }

    public Collection<T> values() {
        List<T> allValues = new ArrayList<>(mMap.size());
        for (List<T> values : mMap.values()) {
            for (T value : values) {
                allValues.add(value);
            }
        }
        return allValues;
    }

    public T getSingletonValue() {
        if (mMap.size() != 1) {
            throw new IllegalArgumentException("Map is not a single entry map");
        }
        List<T> values = mMap.values().iterator().next();
        if (values.size() != 1) {
            throw new IllegalArgumentException("Map is not a single entry map");
        }
        return values.iterator().next();
    }
}
