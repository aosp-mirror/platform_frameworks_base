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
import android.util.SparseArray;

import java.util.ArrayList;

/**
 * A watched variant of SparseArray.  If a {@link Watchable} is stored in the array, the
 * array registers with the {@link Watchable}.  The array registers only once with each
 * {@link Watchable} no matter how many times the {@link Watchable} is stored in the
 * array.
 * @param <E> The element type, stored in the array.
 */
public class WatchedSparseArray<E> extends WatchableImpl
        implements Snappable {

    // The storage
    private final SparseArray<E> mStorage;

    // If true, the array is watching its children
    private volatile boolean mWatching = false;

    // The local observer
    private final Watcher mObserver = new Watcher() {
            @Override
            public void onChange(@Nullable Watchable o) {
                WatchedSparseArray.this.dispatchChange(o);
            }
        };

    /**
     * A private convenience function that notifies registered listeners that an element
     * has been added to or removed from the array.  The what parameter is the array itself.
     */
    private void onChanged() {
        dispatchChange(this);
    }

    /**
     * A convenience function.  Register the object if it is {@link Watchable} and if the
     * array is currently watching.  Note that the watching flag must be true if this
     * function is to succeed.
     */
    private void registerChild(Object o) {
        if (mWatching && o instanceof Watchable) {
            ((Watchable) o).registerObserver(mObserver);
        }
    }

    /**
     * A convenience function.  Unregister the object if it is {@link Watchable} and if
     * the array is currently watching.  Note that the watching flag must be true if this
     * function is to succeed.
     */
    private void unregisterChild(Object o) {
        if (mWatching && o instanceof Watchable) {
            ((Watchable) o).unregisterObserver(mObserver);
        }
    }

    /**
     * A convenience function.  Unregister the object if it is {@link Watchable}, if the array is
     * currently watching, and if the storage does not contain the object.  Note that the watching
     * flag must be true if this function is to succeed.  This must be called after an object has
     * been removed from the storage.
     */
    private void unregisterChildIf(Object o) {
        if (mWatching && o instanceof Watchable) {
            if (mStorage.indexOfValue((E) o) == -1) {
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
     * Creates a new WatchedSparseArray containing no mappings.
     */
    public WatchedSparseArray() {
        mStorage = new SparseArray();
    }

    /**
     * Creates a new WatchedSparseArray containing no mappings that
     * will not require any additional memory allocation to store the
     * specified number of mappings.  If you supply an initial capacity of
     * 0, the sparse array will be initialized with a light-weight
     * representation not requiring any additional array allocations.
     */
    public WatchedSparseArray(int initialCapacity) {
        mStorage = new SparseArray(initialCapacity);
    }

    /**
     * Create a {@link WatchedSparseArray} from a {@link SparseArray}
     */
    public WatchedSparseArray(@NonNull SparseArray<E> c) {
        mStorage = c.clone();
    }

    /**
     * The copy constructor does not copy the watcher data.
     */
    public WatchedSparseArray(@NonNull WatchedSparseArray<E> r) {
        mStorage = r.mStorage.clone();
    }

    /**
     * Make <this> a copy of src.  Any data in <this> is discarded.
     */
    public void copyFrom(@NonNull SparseArray<E> src) {
        clear();
        final int end = src.size();
        for (int i = 0; i < end; i++) {
            put(src.keyAt(i), src.valueAt(i));
        }
    }

    /**
     * Make dst a copy of <this>.  Any previous data in dst is discarded.
     */
    public void copyTo(@NonNull SparseArray<E> dst) {
        dst.clear();
        final int end = size();
        for (int i = 0; i < end; i++) {
            dst.put(keyAt(i), valueAt(i));
        }
    }

    /**
     * Return the underlying storage.  This breaks the wrapper but is necessary when
     * passing the array to distant methods.
     */
    public SparseArray<E> untrackedStorage() {
        return mStorage;
    }

    /**
     * Returns true if the key exists in the array. This is equivalent to
     * {@link #indexOfKey(int)} >= 0.
     *
     * @param key Potential key in the mapping
     * @return true if the key is defined in the mapping
     */
    public boolean contains(int key) {
        return mStorage.contains(key);
    }

    /**
     * Gets the Object mapped from the specified key, or <code>null</code>
     * if no such mapping has been made.
     */
    public E get(int key) {
        return mStorage.get(key);
    }

    /**
     * Gets the Object mapped from the specified key, or the specified Object
     * if no such mapping has been made.
     */
    public E get(int key, E valueIfKeyNotFound) {
        return mStorage.get(key, valueIfKeyNotFound);
    }

    /**
     * Removes the mapping from the specified key, if there was any.
     */
    public void delete(int key) {
        final E child = mStorage.get(key);
        mStorage.delete(key);
        unregisterChildIf(child);
        onChanged();
    }

    /**
     * @hide
     * Removes the mapping from the specified key, if there was any, returning the old value.
     */
    public E removeReturnOld(int key) {
        final E result = mStorage.removeReturnOld(key);
        unregisterChildIf(result);
        return result;
    }

    /**
     * Alias for {@link #delete(int)}.
     */
    public void remove(int key) {
        delete(key);
    }

    /**
     * Removes the mapping at the specified index.
     *
     * <p>For indices outside of the range <code>0...size()-1</code>, an
     * {@link ArrayIndexOutOfBoundsException} is thrown.</p>
     */
    public void removeAt(int index) {
        final E child = mStorage.valueAt(index);
        mStorage.removeAt(index);
        unregisterChildIf(child);
        onChanged();
    }

    /**
     * Remove a range of mappings as a batch.
     *
     * @param index Index to begin at
     * @param size Number of mappings to remove
     *
     * <p>For indices outside of the range <code>0...size()-1</code>,
     * the behavior is undefined.</p>
     */
    public void removeAtRange(int index, int size) {
        final ArrayList<E> children = new ArrayList<>();
        try {
            for (int i = 0; i < size; i++) {
                children.add(mStorage.valueAt(i + index));
            }
        } catch (Exception e) {
            // Ignore any exception and proceed with removal.
        }
        try {
            mStorage.removeAtRange(index, size);
        } finally {
            // Even on exception, make sure to deregister children that have been
            // removed.
            for (int i = 0; i < size; i++) {
                unregisterChildIf(children.get(i));
            }
        }
        onChanged();
    }

    /**
     * Adds a mapping from the specified key to the specified value,
     * replacing the previous mapping from the specified key if there
     * was one.
     */
    public void put(int key, E value) {
        final E old = mStorage.get(key);
        mStorage.put(key, value);
        unregisterChildIf(old);
        registerChild(value);
        onChanged();
    }

    /**
     * Returns the number of key-value mappings that this SparseArray
     * currently stores.
     */
    public int size() {
        return mStorage.size();
    }

    /**
     * Given an index in the range <code>0...size()-1</code>, returns
     * the key from the <code>index</code>th key-value mapping that this
     * SparseArray stores.
     *
     * <p>The keys corresponding to indices in ascending order are guaranteed to
     * be in ascending order, e.g., <code>keyAt(0)</code> will return the
     * smallest key and <code>keyAt(size()-1)</code> will return the largest
     * key.</p>
     *
     * <p>For indices outside of the range <code>0...size()-1</code>,
     * the behavior is undefined for apps targeting {@link android.os.Build.VERSION_CODES#P} and
     * earlier, and an {@link ArrayIndexOutOfBoundsException} is thrown for apps targeting
     * {@link android.os.Build.VERSION_CODES#Q} and later.</p>
     */
    public int keyAt(int index) {
        return mStorage.keyAt(index);
    }

    /**
     * Given an index in the range <code>0...size()-1</code>, returns
     * the value from the <code>index</code>th key-value mapping that this
     * SparseArray stores.
     *
     * <p>The values corresponding to indices in ascending order are guaranteed
     * to be associated with keys in ascending order, e.g.,
     * <code>valueAt(0)</code> will return the value associated with the
     * smallest key and <code>valueAt(size()-1)</code> will return the value
     * associated with the largest key.</p>
     *
     * <p>For indices outside of the range <code>0...size()-1</code>,
     * the behavior is undefined for apps targeting {@link android.os.Build.VERSION_CODES#P} and
     * earlier, and an {@link ArrayIndexOutOfBoundsException} is thrown for apps targeting
     * {@link android.os.Build.VERSION_CODES#Q} and later.</p>
     */
    public E valueAt(int index) {
        return mStorage.valueAt(index);
    }

    /**
     * Given an index in the range <code>0...size()-1</code>, sets a new
     * value for the <code>index</code>th key-value mapping that this
     * SparseArray stores.
     *
     * <p>For indices outside of the range <code>0...size()-1</code>, the behavior is undefined for
     * apps targeting {@link android.os.Build.VERSION_CODES#P} and earlier, and an
     * {@link ArrayIndexOutOfBoundsException} is thrown for apps targeting
     * {@link android.os.Build.VERSION_CODES#Q} and later.</p>
     */
    public void setValueAt(int index, E value) {
        final E old = mStorage.valueAt(index);
        mStorage.setValueAt(index, value);
        if (value != old) {
            unregisterChildIf(old);
            registerChild(value);
            onChanged();
        }
    }

    /**
     * Returns the index for which {@link #keyAt} would return the
     * specified key, or a negative number if the specified
     * key is not mapped.
     */
    public int indexOfKey(int key) {
        return mStorage.indexOfKey(key);
    }

    /**
     * Returns an index for which {@link #valueAt} would return the
     * specified value, or a negative number if no keys map to the
     * specified value.
     * <p>Beware that this is a linear search, unlike lookups by key,
     * and that multiple keys can map to the same value and this will
     * find only one of them.
     * <p>Note also that unlike most collections' {@code indexOf} methods,
     * this method compares values using {@code ==} rather than {@code equals}.
     */
    public int indexOfValue(E value) {
        return mStorage.indexOfValue(value);
    }

    /**
     * Returns an index for which {@link #valueAt} would return the
     * specified value, or a negative number if no keys map to the
     * specified value.
     * <p>Beware that this is a linear search, unlike lookups by key,
     * and that multiple keys can map to the same value and this will
     * find only one of them.
     * <p>Note also that this method uses {@code equals} unlike {@code indexOfValue}.
     * @hide
     */
    public int indexOfValueByValue(E value) {
        return mStorage.indexOfValueByValue(value);
    }

    /**
     * Removes all key-value mappings from this SparseArray.
     */
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
     * Puts a key/value pair into the array, optimizing for the case where
     * the key is greater than all existing keys in the array.
     */
    public void append(int key, E value) {
        mStorage.append(key, value);
        registerChild(value);
        onChanged();
    }

    @Override
    public int hashCode() {
        return mStorage.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o instanceof WatchedSparseArray) {
            WatchedSparseArray w = (WatchedSparseArray) o;
            return mStorage.equals(w.mStorage);
        } else {
            return false;
        }
    }

    /**
     * <p>This implementation composes a string by iterating over its mappings. If
     * this map contains itself as a value, the string "(this Map)"
     * will appear in its place.
     */
    @Override
    public String toString() {
        return mStorage.toString();
    }

    /**
     * Create a copy of the array.  If the element is a subclass of Snapper then the copy
     * contains snapshots of the elements.  Otherwise the copy contains references to the
     * elements.  The returned snapshot is immutable.
     * @return A new array whose elements are the elements of <this>.
     */
    public WatchedSparseArray<E> snapshot() {
        WatchedSparseArray<E> l = new WatchedSparseArray<>(size());
        snapshot(l, this);
        return l;
    }

    /**
     * Make <this> a snapshot of the argument.  Note that <this> is immutable when the
     * method returns.  <this> must be empty when the function is called.
     * @param r The source array, which is copied into <this>
     */
    public void snapshot(@NonNull WatchedSparseArray<E> r) {
        snapshot(this, r);
    }

    /**
     * Make the destination a copy of the source.  If the element is a subclass of Snapper then the
     * copy contains snapshots of the elements.  Otherwise the copy contains references to the
     * elements.  The destination must be initially empty.  Upon return, the destination is
     * immutable.
     * @param dst The destination array.  It must be empty.
     * @param src The source array.  It is not modified.
     */
    public static <E> void snapshot(@NonNull WatchedSparseArray<E> dst,
            @NonNull WatchedSparseArray<E> src) {
        if (dst.size() != 0) {
            throw new IllegalArgumentException("snapshot destination is not empty");
        }
        final int end = src.size();
        for (int i = 0; i < end; i++) {
            final E val = Snapshots.maybeSnapshot(src.valueAt(i));
            final int key = src.keyAt(i);
            dst.mStorage.put(key, val);
        }
        dst.seal();
    }

}
