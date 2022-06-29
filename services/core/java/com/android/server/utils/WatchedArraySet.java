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
import android.util.ArraySet;

import java.util.Collection;

/**
 * WatchedArraySet is an {@link android.util.ArraySet} that can report changes to itself.  If its
 * values are {@link Watchable} then the WatchedArraySet will also report changes to the values.
 * A {@link Watchable} is notified only once, no matter how many times it is stored in the array.
 * @param <E> The element type
 */
public class WatchedArraySet<E> extends WatchableImpl
        implements Snappable {

    // The storage
    private final ArraySet<E> mStorage;

    // If true, the array is watching its children
    private volatile boolean mWatching = false;

    // The local observer
    private final Watcher mObserver = new Watcher() {
            @Override
            public void onChange(@Nullable Watchable what) {
                WatchedArraySet.this.dispatchChange(what);
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
            if (!mStorage.contains(o)) {
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
     * Create a new empty {@link WatchedArraySet}.  The default capacity of an array map
     * is 0, and will grow once items are added to it.
     */
    public WatchedArraySet() {
        this(0, false);
    }

    /**
     * Create a new {@link WatchedArraySet} with a given initial capacity.
     */
    public WatchedArraySet(int capacity) {
        this(capacity, false);
    }

    /** {@hide} */
    public WatchedArraySet(int capacity, boolean identityHashCode) {
        mStorage = new ArraySet<E>(capacity, identityHashCode);
    }

    /**
     * Create a new {@link WatchedArraySet} with items from the given array
     */
    public WatchedArraySet(@Nullable E[] array) {
        mStorage = new ArraySet(array);
    }

    /**
     * Create a {@link WatchedArraySet} from an {@link ArraySet}
     */
    public WatchedArraySet(@NonNull ArraySet<E> c) {
        mStorage = new ArraySet<>(c);
    }

    /**
     * Create a {@link WatchedArraySet} from an {@link WatchedArraySet}
     */
    public WatchedArraySet(@NonNull WatchedArraySet<E> c) {
        mStorage = new ArraySet<>(c.mStorage);
    }

    /**
     * Make <this> a copy of src.  Any data in <this> is discarded.
     */
    public void copyFrom(@NonNull ArraySet<E> src) {
        clear();
        final int end = src.size();
        mStorage.ensureCapacity(end);
        for (int i = 0; i < end; i++) {
            add(src.valueAt(i));
        }
    }

    /**
     * Make dst a copy of <this>.  Any previous data in dst is discarded.
     */
    public void copyTo(@NonNull ArraySet<E> dst) {
        dst.clear();
        final int end = size();
        dst.ensureCapacity(end);
        for (int i = 0; i < end; i++) {
            dst.add(valueAt(i));
        }
    }

    /**
     * Return the underlying storage.  This breaks the wrapper but is necessary when
     * passing the array to distant methods.
     */
    public ArraySet<E> untrackedStorage() {
        return mStorage;
    }

    /**
     * Make the array map empty.  All storage is released.
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
     * Check whether a value exists in the set.
     *
     * @param key The value to search for.
     * @return Returns true if the value exists, else false.
     */
    public boolean contains(Object key) {
        return mStorage.contains(key);
    }

    /**
     * Returns the index of a value in the set.
     *
     * @param key The value to search for.
     * @return Returns the index of the value if it exists, else a negative integer.
     */
    public int indexOf(Object key) {
        return mStorage.indexOf(key);
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
    public E valueAt(int index) {
        return mStorage.valueAt(index);
    }

    /**
     * Return true if the array map contains no items.
     */
    public boolean isEmpty() {
        return mStorage.isEmpty();
    }

    /**
     * Adds the specified object to this set. The set is not modified if it
     * already contains the object.
     *
     * @param value the object to add.
     * @return {@code true} if this set is modified, {@code false} otherwise.
     */
    public boolean add(E value) {
        final boolean result = mStorage.add(value);
        registerChild(value);
        onChanged();
        return result;
    }

    /**
     * Special fast path for appending items to the end of the array without validation.
     * The array must already be large enough to contain the item.
     * @hide
     */
    public void append(E value) {
        mStorage.append(value);
        registerChild(value);
        onChanged();
    }

    /**
     * Perform a {@link #add(Object)} of all values in <var>array</var>
     * @param collection The collection whose contents are to be retrieved.
     */
    public void addAll(Collection<? extends E> collection) {
        mStorage.addAll(collection);
        onChanged();
    }

    /**
     * Perform a {@link #add(Object)} of all values in <var>array</var>
     * @param array The array whose contents are to be retrieved.
     */
    public void addAll(WatchedArraySet<? extends E> array) {
        final int end = array.size();
        for (int i = 0; i < end; i++) {
            add(array.valueAt(i));
        }
    }

    /**
     * Removes the specified object from this set.
     *
     * @param o the object to remove.
     * @return {@code true} if this set was modified, {@code false} otherwise.
     */
    public boolean remove(Object o) {
        if (mStorage.remove(o)) {
            unregisterChildIf(o);
            onChanged();
            return true;
        }
        return false;
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
    public E removeAt(int index) {
        final E result = mStorage.removeAt(index);
        unregisterChildIf(result);
        onChanged();
        return result;
    }

    /**
     * Perform a {@link #remove(Object)} of all values in <var>array</var>
     * @param array The array whose contents are to be removed.
     */
    public boolean removeAll(ArraySet<? extends E> array) {
        final int end = array.size();
        boolean any = false;
        for (int i = 0; i < end; i++) {
            any = remove(array.valueAt(i)) || any;
        }
        return any;
    }

    /**
     * Return the number of items in this array map.
     */
    public int size() {
        return mStorage.size();
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation returns false if the object is not a set, or
     * if the sets have different sizes.  Otherwise, for each value in this
     * set, it checks to make sure the value also exists in the other set.
     * If any value doesn't exist, the method returns false; otherwise, it
     * returns true.
     */
    @Override
    public boolean equals(@Nullable Object object) {
        if (object instanceof WatchedArraySet) {
            return mStorage.equals(((WatchedArraySet) object).mStorage);
        } else {
            return mStorage.equals(object);
        }
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
     *
     * <p>This implementation composes a string by iterating over its values. If
     * this set contains itself as a value, the string "(this Set)"
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
    public WatchedArraySet<E> snapshot() {
        WatchedArraySet<E> l = new WatchedArraySet<>();
        snapshot(l, this);
        return l;
    }

    /**
     * Make <this> a snapshot of the argument.  Note that <this> is immutable when the
     * method returns.  <this> must be empty when the function is called.
     * @param r The source array, which is copied into <this>
     */
    public void snapshot(@NonNull WatchedArraySet<E> r) {
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
    public static <E> void snapshot(@NonNull WatchedArraySet<E> dst,
            @NonNull WatchedArraySet<E> src) {
        if (dst.size() != 0) {
            throw new IllegalArgumentException("snapshot destination is not empty");
        }
        final int end = src.size();
        dst.mStorage.ensureCapacity(end);
        for (int i = 0; i < end; i++) {
            final E val = Snapshots.maybeSnapshot(src.valueAt(i));
            dst.mStorage.append(val);
        }
        dst.seal();
    }
}
