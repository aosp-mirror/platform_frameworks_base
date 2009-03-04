/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
/**
 * @author Alexey A. Petrenko
 * @version $Revision$
 */

package java.awt;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * The RenderingHints class represents preferences for the rendering algorithms.
 * The preferences are arbitrary and can be specified by Map objects or by
 * key-value pairs.
 * 
 * @since Android 1.0
 */
public class RenderingHints implements Map<Object, Object>, Cloneable {

    /**
     * The Constant KEY_ALPHA_INTERPOLATION - alpha interpolation rendering hint
     * key.
     */
    public static final Key KEY_ALPHA_INTERPOLATION = new KeyImpl(1);

    /**
     * The Constant VALUE_ALPHA_INTERPOLATION_DEFAULT - alpha interpolation
     * rendering hint value.
     */
    public static final Object VALUE_ALPHA_INTERPOLATION_DEFAULT = new KeyValue(
            KEY_ALPHA_INTERPOLATION);

    /**
     * The Constant VALUE_ALPHA_INTERPOLATION_SPEED - alpha interpolation
     * rendering hint value.
     */
    public static final Object VALUE_ALPHA_INTERPOLATION_SPEED = new KeyValue(
            KEY_ALPHA_INTERPOLATION);

    /**
     * The Constant VALUE_ALPHA_INTERPOLATION_QUALITY - alpha interpolation
     * rendering hint value.
     */
    public static final Object VALUE_ALPHA_INTERPOLATION_QUALITY = new KeyValue(
            KEY_ALPHA_INTERPOLATION);

    /**
     * The Constant KEY_ANTIALIASING - antialiasing rendering hint key.
     */
    public static final Key KEY_ANTIALIASING = new KeyImpl(2);

    /**
     * The Constant VALUE_ANTIALIAS_DEFAULT - antialiasing rendering hint value.
     */
    public static final Object VALUE_ANTIALIAS_DEFAULT = new KeyValue(KEY_ANTIALIASING);

    /**
     * The Constant VALUE_ANTIALIAS_ON - antialiasing rendering hint value.
     */
    public static final Object VALUE_ANTIALIAS_ON = new KeyValue(KEY_ANTIALIASING);

    /**
     * The Constant VALUE_ANTIALIAS_OFF - antialiasing rendering hint value.
     */
    public static final Object VALUE_ANTIALIAS_OFF = new KeyValue(KEY_ANTIALIASING);

    /**
     * The Constant KEY_COLOR_RENDERING - color rendering hint key.
     */
    public static final Key KEY_COLOR_RENDERING = new KeyImpl(3);

    /**
     * The Constant VALUE_COLOR_RENDER_DEFAULT - color rendering hint value.
     */
    public static final Object VALUE_COLOR_RENDER_DEFAULT = new KeyValue(KEY_COLOR_RENDERING);

    /**
     * The Constant VALUE_COLOR_RENDER_SPEED - color rendering hint value.
     */
    public static final Object VALUE_COLOR_RENDER_SPEED = new KeyValue(KEY_COLOR_RENDERING);

    /**
     * The Constant VALUE_COLOR_RENDER_QUALITY - color rendering hint value.
     */
    public static final Object VALUE_COLOR_RENDER_QUALITY = new KeyValue(KEY_COLOR_RENDERING);

    /**
     * The Constant KEY_DITHERING - dithering rendering hint key.
     */
    public static final Key KEY_DITHERING = new KeyImpl(4);

    /**
     * The Constant VALUE_DITHER_DEFAULT - dithering rendering hint value.
     */
    public static final Object VALUE_DITHER_DEFAULT = new KeyValue(KEY_DITHERING);

    /**
     * The Constant VALUE_DITHER_DISABLE - dithering rendering hint value.
     */
    public static final Object VALUE_DITHER_DISABLE = new KeyValue(KEY_DITHERING);

    /**
     * The Constant VALUE_DITHER_DISABLE - dithering rendering hint value.
     */
    public static final Object VALUE_DITHER_ENABLE = new KeyValue(KEY_DITHERING);

    /**
     * The Constant KEY_FRACTIONALMETRICS - fractional metrics rendering hint
     * key.
     */
    public static final Key KEY_FRACTIONALMETRICS = new KeyImpl(5);

    /**
     * The Constant VALUE_FRACTIONALMETRICS_DEFAULT - fractional metrics
     * rendering hint value.
     */
    public static final Object VALUE_FRACTIONALMETRICS_DEFAULT = new KeyValue(KEY_FRACTIONALMETRICS);

    /**
     * The Constant VALUE_FRACTIONALMETRICS_ON - fractional metrics rendering
     * hint value.
     */
    public static final Object VALUE_FRACTIONALMETRICS_ON = new KeyValue(KEY_FRACTIONALMETRICS);

    /**
     * The Constant VALUE_FRACTIONALMETRICS_OFF - fractional metrics rendering
     * hint value.
     */
    public static final Object VALUE_FRACTIONALMETRICS_OFF = new KeyValue(KEY_FRACTIONALMETRICS);

    /**
     * The Constant KEY_INTERPOLATION - interpolation rendering hint key.
     */
    public static final Key KEY_INTERPOLATION = new KeyImpl(6);

    /**
     * The Constant VALUE_INTERPOLATION_BICUBIC - interpolation rendering hint
     * value.
     */
    public static final Object VALUE_INTERPOLATION_BICUBIC = new KeyValue(KEY_INTERPOLATION);

    /**
     * The Constant VALUE_INTERPOLATION_BILINEAR - interpolation rendering hint
     * value.
     */
    public static final Object VALUE_INTERPOLATION_BILINEAR = new KeyValue(KEY_INTERPOLATION);

    /**
     * The Constant VALUE_INTERPOLATION_NEAREST_NEIGHBOR - interpolation
     * rendering hint value.
     */
    public static final Object VALUE_INTERPOLATION_NEAREST_NEIGHBOR = new KeyValue(
            KEY_INTERPOLATION);

    /**
     * The Constant KEY_RENDERING - rendering hint key.
     */
    public static final Key KEY_RENDERING = new KeyImpl(7);

    /**
     * The Constant VALUE_RENDER_DEFAULT - rendering hint value.
     */
    public static final Object VALUE_RENDER_DEFAULT = new KeyValue(KEY_RENDERING);

    /**
     * The Constant VALUE_RENDER_SPEED - rendering hint value.
     */
    public static final Object VALUE_RENDER_SPEED = new KeyValue(KEY_RENDERING);

    /**
     * The Constant VALUE_RENDER_QUALITY - rendering hint value.
     */
    public static final Object VALUE_RENDER_QUALITY = new KeyValue(KEY_RENDERING);

    /**
     * The Constant KEY_STROKE_CONTROL - stroke control hint key.
     */
    public static final Key KEY_STROKE_CONTROL = new KeyImpl(8);

    /**
     * The Constant VALUE_STROKE_DEFAULT - stroke hint value.
     */
    public static final Object VALUE_STROKE_DEFAULT = new KeyValue(KEY_STROKE_CONTROL);

    /**
     * The Constant VALUE_STROKE_NORMALIZE - stroke hint value.
     */
    public static final Object VALUE_STROKE_NORMALIZE = new KeyValue(KEY_STROKE_CONTROL);

    /**
     * The Constant VALUE_STROKE_PURE - stroke hint value.
     */
    public static final Object VALUE_STROKE_PURE = new KeyValue(KEY_STROKE_CONTROL);

    /**
     * The Constant KEY_TEXT_ANTIALIASING - text antialiasing hint key.
     */
    public static final Key KEY_TEXT_ANTIALIASING = new KeyImpl(9);

    /**
     * The Constant VALUE_TEXT_ANTIALIAS_DEFAULT - text antialiasing hint key.
     */
    public static final Object VALUE_TEXT_ANTIALIAS_DEFAULT = new KeyValue(KEY_TEXT_ANTIALIASING);

    /**
     * The Constant VALUE_TEXT_ANTIALIAS_ON - text antialiasing hint key.
     */
    public static final Object VALUE_TEXT_ANTIALIAS_ON = new KeyValue(KEY_TEXT_ANTIALIASING);

    /**
     * The Constant VALUE_TEXT_ANTIALIAS_OFF - text antialiasing hint key.
     */
    public static final Object VALUE_TEXT_ANTIALIAS_OFF = new KeyValue(KEY_TEXT_ANTIALIASING);

    /** The map. */
    private HashMap<Object, Object> map = new HashMap<Object, Object>();

    /**
     * Instantiates a new rendering hints object from specified Map object with
     * defined key/value pairs or null for empty RenderingHints.
     * 
     * @param map
     *            the Map object with defined key/value pairs or null for empty
     *            RenderingHints.
     */
    public RenderingHints(Map<Key, ?> map) {
        super();
        if (map != null) {
            putAll(map);
        }
    }

    /**
     * Instantiates a new rendering hints object with the specified key/value
     * pair.
     * 
     * @param key
     *            the key of hint property.
     * @param value
     *            the value of hint property.
     */
    public RenderingHints(Key key, Object value) {
        super();
        put(key, value);
    }

    /**
     * Adds the properties represented by key/value pairs from the specified
     * RenderingHints object to current object.
     * 
     * @param hints
     *            the RenderingHints to be added.
     */
    public void add(RenderingHints hints) {
        map.putAll(hints.map);
    }

    /**
     * Puts the specified value to the specified key. Neither the key nor the
     * value can be null.
     * 
     * @param key
     *            the rendering hint key.
     * @param value
     *            the rendering hint value.
     * @return the previous rendering hint value assigned to the key or null.
     */
    public Object put(Object key, Object value) {
        if (!((Key)key).isCompatibleValue(value)) {
            throw new IllegalArgumentException();
        }

        return map.put(key, value);
    }

    /**
     * Removes the specified key and corresponding value from the RenderingHints
     * object.
     * 
     * @param key
     *            the specified hint key to be removed.
     * @return the object of previous rendering hint value which is assigned to
     *         the specified key, or null.
     */
    public Object remove(Object key) {
        return map.remove(key);
    }

    /**
     * Gets the value assigned to the specified key.
     * 
     * @param key
     *            the rendering hint key.
     * @return the object assigned to the specified key.
     */
    public Object get(Object key) {
        return map.get(key);
    }

    /**
     * Returns a set of rendering hints keys for current RenderingHints object.
     * 
     * @return the set of rendering hints keys.
     */
    public Set<Object> keySet() {
        return map.keySet();
    }

    /**
     * Returns a set of Map.Entry objects which contain current RenderingHint
     * key/value pairs.
     * 
     * @return the a set of mapped RenderingHint key/value pairs.
     */
    public Set<Map.Entry<Object, Object>> entrySet() {
        return map.entrySet();
    }

    /**
     * Puts all of the preferences from the specified Map into the current
     * RenderingHints object. These mappings replace all existing preferences.
     * 
     * @param m
     *            the specified Map of preferences.
     */
    public void putAll(Map<?, ?> m) {
        if (m instanceof RenderingHints) {
            map.putAll(((RenderingHints)m).map);
        } else {
            Set<?> entries = m.entrySet();

            if (entries != null) {
                Iterator<?> it = entries.iterator();
                while (it.hasNext()) {
                    Map.Entry<?, ?> entry = (Map.Entry<?, ?>)it.next();
                    Key key = (Key)entry.getKey();
                    Object val = entry.getValue();
                    put(key, val);
                }
            }
        }
    }

    /**
     * Returns a Collection of values contained in current RenderingHints
     * object.
     * 
     * @return the Collection of RenderingHints's values.
     */
    public Collection<Object> values() {
        return map.values();
    }

    /**
     * Checks whether or not current RenderingHints object contains at least one
     * the value which is equal to the specified Object.
     * 
     * @param value
     *            the specified Object.
     * @return true, if the specified object is assigned to at least one
     *         RenderingHint's key, false otherwise.
     */
    public boolean containsValue(Object value) {
        return map.containsValue(value);
    }

    /**
     * Checks whether or not current RenderingHints object contains the key
     * which is equal to the specified Object.
     * 
     * @param key
     *            the specified Object.
     * @return true, if the RenderingHints object contains the specified Object
     *         as a key, false otherwise.
     */
    public boolean containsKey(Object key) {
        if (key == null) {
            throw new NullPointerException();
        }

        return map.containsKey(key);
    }

    /**
     * Checks whether or not the RenderingHints object contains any key/value
     * pairs.
     * 
     * @return true, if the RenderingHints object is empty, false otherwise.
     */
    public boolean isEmpty() {
        return map.isEmpty();
    }

    /**
     * Clears the RenderingHints of all key/value pairs.
     */
    public void clear() {
        map.clear();
    }

    /**
     * Returns the number of key/value pairs in the RenderingHints.
     * 
     * @return the number of key/value pairs.
     */
    public int size() {
        return map.size();
    }

    /**
     * Compares the RenderingHints object with the specified object.
     * 
     * @param o
     *            the specified Object to be compared.
     * @return true, if the Object is a Map whose key/value pairs match this
     *         RenderingHints' key/value pairs, false otherwise.
     */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Map)) {
            return false;
        }

        Map<?, ?> m = (Map<?, ?>)o;
        Set<?> keys = keySet();
        if (!keys.equals(m.keySet())) {
            return false;
        }

        Iterator<?> it = keys.iterator();
        while (it.hasNext()) {
            Key key = (Key)it.next();
            Object v1 = get(key);
            Object v2 = m.get(key);
            if (!(v1 == null ? v2 == null : v1.equals(v2))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the hash code for this RenderingHints object.
     * 
     * @return the hash code for this RenderingHints object.
     */
    @Override
    public int hashCode() {
        return map.hashCode();
    }

    /**
     * Returns the clone of the RenderingHints object with the same contents.
     * 
     * @return the clone of the RenderingHints instance.
     */
    @SuppressWarnings("unchecked")
    @Override
    public Object clone() {
        RenderingHints clone = new RenderingHints(null);
        clone.map = (HashMap<Object, Object>)this.map.clone();
        return clone;
    }

    /**
     * Returns the string representation of the RenderingHints object.
     * 
     * @return the String object which represents RenderingHints object.
     */
    @Override
    public String toString() {
        return "RenderingHints[" + map.toString() + "]"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The RenderingHints.Key class is abstract and defines a base type for all
     * RenderingHints keys.
     * 
     * @since Android 1.0
     */
    public abstract static class Key {

        /** The key. */
        private final int key;

        /**
         * Instantiates a new key with unique integer identifier. No two objects
         * of the same subclass with the same integer key can be instantiated.
         * 
         * @param key
         *            the unique key.
         */
        protected Key(int key) {
            this.key = key;
        }

        /**
         * Compares the Key object with the specified object.
         * 
         * @param o
         *            the specified Object to be compared.
         * @return true, if the Key is equal to the specified object, false
         *         otherwise.
         */
        @Override
        public final boolean equals(Object o) {
            return this == o;
        }

        /**
         * Returns the hash code for this Key object.
         * 
         * @return the hash code for this Key object.
         */
        @Override
        public final int hashCode() {
            return System.identityHashCode(this);
        }

        /**
         * Returns integer unique key with which this Key object has been
         * instantiated.
         * 
         * @return the integer unique key with which this Key object has been
         *         instantiated.
         */
        protected final int intKey() {
            return key;
        }

        /**
         * Checks whether or not specified value is compatible with the Key.
         * 
         * @param val
         *            the Object.
         * @return true, if the specified value is compatible with the Key,
         *         false otherwise.
         */
        public abstract boolean isCompatibleValue(Object val);
    }

    /**
     * Private implementation of Key class.
     */
    private static class KeyImpl extends Key {

        /**
         * Instantiates a new key implementation.
         * 
         * @param key
         *            the key.
         */
        protected KeyImpl(int key) {
            super(key);
        }

        @Override
        public boolean isCompatibleValue(Object val) {
            if (!(val instanceof KeyValue)) {
                return false;
            }

            return ((KeyValue)val).key == this;
        }
    }

    /**
     * Private class KeyValue is used as value for Key class instance.
     */
    private static class KeyValue {

        /**
         * The key.
         */
        private final Key key;

        /**
         * Instantiates a new key value.
         * 
         * @param key
         *            the key.
         */
        protected KeyValue(Key key) {
            this.key = key;
        }
    }
}
