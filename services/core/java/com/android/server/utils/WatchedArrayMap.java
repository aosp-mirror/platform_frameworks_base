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

package com.android.server.utils;

import android.annotation.NonNull;
import android.annotation.Nullable;

import android.util.ArrayMap;
import android.util.Log;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * WatchedArrayMap is an {@link android.util.ArrayMap} that can report changes to itself.  If its
 * values are {@link Watchable} then the WatchedArrayMap will also report changes to the values.
 * A {@link Watchable} is notified only once, no matter how many times it is stored in the array.
 */
public class WatchedArrayMap<K, V> extends WatchableImpl implements Map<K, V> {

    // The storage
    private final ArrayMap<K, V> mStorage;

    // If true, the array is watching its children
    private boolean mWatching = false;

    // The local observer
    private final Watcher mObserver = new Watcher() {
            @Override
            public void onChange(@Nullable Watchable what) {
                WatchedArrayMap.this.dispatchChange(what);
            }
        };

    /**
     * A convenience function called when the elements are added to or removed from the storage.
     * The watchable is always {@link this}.
     */
    private void onChanged() {
        dispatchChange(this);
    }

    /**
     * A convenience function.  Register the object if it is {@link Watchable} and if the
     * array is currently watching.  Note that the watching flag must be true if this
     * function is to succeed.  Also note that if this is called with the same object
     * twice, <this> is only registered once.
     */
    private void registerChild(Object o) {
        if (mWatching && o instanceof Watchable) {
            ((Watchable) o).registerObserver(mObserver);
        }
    }

    /**
     * A convenience function.  Unregister the object if it is {@link Watchable} and if the
     * array is currently watching.  This unconditionally removes the object from the
     * registered list.
     */
    private void unregisterChild(Object o) {
        if (mWatching && o instanceof Watchable) {
            ((Watchable) o).unregisterObserver(mObserver);
        }
    }

    /**
     * A convenience function.  Unregister the object if it is {@link Watchable}, if the
     * array is currently watching, and if there are no other instances of this object in
     * the storage.  Note that the watching flag must be true if this function is to
     * succeed.  The object must already have been removed from the storage before this
     * method is called.
     */
    private void unregisterChildIf(Object o) {
        if (mWatching && o instanceof Watchable) {
            if (!mStorage.containsValue(o)) {
                ((Watchable) o).unregisterObserver(mObserver);
            }
        }
    }

    /**
     * Register a {@link Watcher} with the array.  If this is the first Watcher than any
     * array values that are {@link Watchable} are registered to the array itself.
     */
    @Override
    public void registerObserver(@NonNull Watcher observer) {
        super.registerObserver(observer);
        if (registeredObserverCount() == 1) {
            // The watching flag must be set true before any children are registered.
            mWatching = true;
            final int end = mStorage.size();
            for (int i = 0; i < end; i++) {
                registerChild(mStorage.valueAt(i));
            }
        }
    }

    /**
     * Unregister a {@link Watcher} from the array.  If this is the last Watcher than any
     * array values that are {@link Watchable} are unregistered to the array itself.
     */
    @Override
    public void unregisterObserver(@NonNull Watcher observer) {
        super.unregisterObserver(observer);
        if (registeredObserverCount() == 0) {
            final int end = mStorage.size();
            for (int i = 0; i < end; i++) {
                unregisterChild(mStorage.valueAt(i));
            }
            // The watching flag must be true while children are unregistered.
            mWatching = false;
        }
    }

    /**
     * Create a new empty {@link WatchedArrayMap}.  The default capacity of an array map
     * is 0, and will grow once items are added to it.
     */
    public WatchedArrayMap() {
        this(0, false);
    }

    /**
     * Create a new {@link WatchedArrayMap} with a given initial capacity.
     */
    public WatchedArrayMap(int capacity) {
        this(capacity, false);
    }

    /** {@hide} */
    public WatchedArrayMap(int capacity, boolean identityHashCode) {
        mStorage = new ArrayMap<K, V>(capacity, identityHashCode);
    }

    /**
     * Create a new {@link WatchedArrayMap} with the mappings from the given {@link Map}.
     */
    public WatchedArrayMap(@Nullable Map<? extends K, ? extends V> map) {
        mStorage = new ArrayMap<K, V>();
        if (map != null) {
            putAll(map);
        }
    }

    /**
     * Return the underlying storage.  This breaks the wrapper but is necessary when
     * passing the array to distant methods.
     */
    public ArrayMap untrackedMap() {
        return mStorage;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        // The storage cannot be simply cleared.  Each element in the storage must be
        // unregistered.  Deregistration is only needed if the array is actually
        // watching.
        if (mWatching) {
            final int end = mStorage.size();
            for (int i = 0; i < end; i++) {
                unregisterChild(mStorage.valueAt(i));
            }
        }
        mStorage.clear();
        onChanged();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsKey(Object key) {
        return mStorage.containsKey(key);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsValue(Object value) {
        return mStorage.containsValue(value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        return Collections.unmodifiableSet(mStorage.entrySet());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object o) {
        if (o instanceof WatchedArrayMap) {
            WatchedArrayMap w = (WatchedArrayMap) o;
            return mStorage.equals(w.mStorage);
        } else {
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V get(Object key) {
        return mStorage.get(key);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return mStorage.hashCode();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEmpty() {
        return mStorage.isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Set<K> keySet() {
        return Collections.unmodifiableSet(mStorage.keySet());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V put(K key, V value) {
        final V result = mStorage.put(key, value);
        registerChild(value);
        onChanged();
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void putAll(@NonNull Map<? extends K, ? extends V> map) {
        for (Map.Entry<? extends K, ? extends V> element : map.entrySet()) {
            put(element.getKey(), element.getValue());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public V remove(@NonNull Object key) {
        final V result = mStorage.remove(key);
        unregisterChildIf(result);
        onChanged();
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int size() {
        return mStorage.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Collection<V> values() {
        return Collections.unmodifiableCollection(mStorage.values());
    }

    // Methods supported by ArrayMap that are not part of Map

    /**
     * Return the key at the given index in the array.
     *
     * <p>For indices outside of the range <code>0...size()-1</code>, an
     * {@link ArrayIndexOutOfBoundsException} is thrown.</p>
     *
     * @param index The desired index, must be between 0 and {@link #size()}-1.
     * @return Returns the key stored at the given index.
     */
    public K keyAt(int index) {
        return mStorage.keyAt(index);
    }

    /**
     * Return the value at the given index in the array.
     *
     * <p>For indices outside of the range <code>0...size()-1</code>, an
     * {@link ArrayIndexOutOfBoundsException} is thrown.</p>
     *
     * @param index The desired index, must be between 0 and {@link #size()}-1.
     * @return Returns the value stored at the given index.
     */
    public V valueAt(int index) {
        return mStorage.valueAt(index);
    }

     /**
     * Remove an existing key from the array map.
     * @param key The key of the mapping to remove.
     * @return Returns the value that was stored under the key, or null if there
     * was no such key.
     */
    public int indexOfKey(K key) {
        return mStorage.indexOfKey(key);
    }

    /**
     * Returns an index for which {@link #valueAt} would return the
     * specified value, or a negative number if no keys map to the
     * specified value.
     * Beware that this is a linear search, unlike lookups by key,
     * and that multiple keys can map to the same value and this will
     * find only one of them.
     */
    public int indexOfValue(V value) {
        return mStorage.indexOfValue(value);
    }

    /**
     * Set the value at a given index in the array.
     *
     * <p>For indices outside of the range <code>0...size()-1</code>, an
     * {@link ArrayIndexOutOfBoundsException} is thrown.</p>
     *
     * @param index The desired index, must be between 0 and {@link #size()}-1.
     * @param value The new value to store at this index.
     * @return Returns the previous value at the given index.
     */
    public V setValueAt(int index, V value) {
        final V result = mStorage.setValueAt(index, value);
        if (value != result) {
            unregisterChildIf(result);
            registerChild(value);
            onChanged();
        }
        return result;
    }

    /**
     * Remove the key/value mapping at the given index.
     *
     * <p>For indices outside of the range <code>0...size()-1</code>, an
     * {@link ArrayIndexOutOfBoundsException} is thrown.</p>
     *
     * @param index The desired index, must be between 0 and {@link #size()}-1.
     * @return Returns the value that was stored at this index.
     */
    public V removeAt(int index) {
        final V result = mStorage.removeAt(index);
        unregisterChildIf(result);
        onChanged();
        return result;
    }
}
