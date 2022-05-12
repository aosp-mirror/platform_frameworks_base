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

import java.util.ArrayList;
import java.util.Collection;

/**
 * WatchedArrayMap is an {@link android.util.ArrayMap} that can report changes to itself.  If its
 * values are {@link Watchable} then the WatchedArrayMap will also report changes to the values.
 * A {@link Watchable} is notified only once, no matter how many times it is stored in the array.
 * @param <E> The element type, stored in the array.
 */
public class WatchedArrayList<E> extends WatchableImpl
        implements Snappable {

    // The storage
    private final ArrayList<E> mStorage;

    // If true, the array is watching its children
    private volatile boolean mWatching = false;

    // The local observer
    private final Watcher mObserver = new Watcher() {
            @Override
            public void onChange(@Nullable Watchable what) {
                WatchedArrayList.this.dispatchChange(what);
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
                registerChild(mStorage.get(i));
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
                unregisterChild(mStorage.get(i));
            }
            // The watching flag must be true while children are unregistered.
            mWatching = false;
        }
    }

    /**
     * Create a new empty {@link WatchedArrayList}.  The default capacity of an array map
     * is 0, and will grow once items are added to it.
     */
    public WatchedArrayList() {
        this(0);
    }

    /**
     * Create a new {@link WatchedArrayList} with a given initial capacity.
     */
    public WatchedArrayList(int capacity) {
        mStorage = new ArrayList<E>(capacity);
    }

    /**
     * Create a new {@link WatchedArrayList} with the content of the collection.
     */
    public WatchedArrayList(@Nullable Collection<? extends E> c) {
        mStorage = new ArrayList<E>();
        if (c != null) {
            // There is no need to register children because the WatchedArrayList starts
            // life unobserved.
            mStorage.addAll(c);
        }
    }

    /**
     * Create a {@link WatchedArrayList} from an {@link ArrayList}
     */
    public WatchedArrayList(@NonNull ArrayList<E> c) {
        mStorage = new ArrayList<>(c);
    }

    /**
     * Create a {@link WatchedArrayList} from an {@link WatchedArrayList}
     */
    public WatchedArrayList(@NonNull WatchedArrayList<E> c) {
        mStorage = new ArrayList<>(c.mStorage);
    }

    /**
     * Make <this> a copy of src.  Any data in <this> is discarded.
     */
    public void copyFrom(@NonNull ArrayList<E> src) {
        clear();
        final int end = src.size();
        mStorage.ensureCapacity(end);
        for (int i = 0; i < end; i++) {
            add(src.get(i));
        }
    }

    /**
     * Make dst a copy of <this>.  Any previous data in dst is discarded.
     */
    public void copyTo(@NonNull ArrayList<E> dst) {
        dst.clear();
        final int end = size();
        dst.ensureCapacity(end);
        for (int i = 0; i < end; i++) {
            dst.add(get(i));
        }
    }

    /**
     * Return the underlying storage.  This breaks the wrapper but is necessary when
     * passing the array to distant methods.
     */
    public ArrayList<E> untrackedStorage() {
        return mStorage;
    }

    /**
     * Append the specified element to the end of the list
     */
    public boolean add(E value) {
        final boolean result = mStorage.add(value);
        registerChild(value);
        onChanged();
        return result;
    }

    /**
     * Insert the element into the list
     */
    public void add(int index, E value) {
        mStorage.add(index, value);
        registerChild(value);
        onChanged();
    }

    /**
     * Append the elements of the collection to the list.
     */
    public boolean addAll(Collection<? extends E> c) {
        if (c.size() > 0) {
            for (E e: c) {
                mStorage.add(e);
            }
            onChanged();
            return true;
        } else {
            return false;
        }
    }

    /**
     * Insert the elements of the collection into the list at the index.
     */
    public boolean addAll(int index, Collection<? extends E> c) {
        if (c.size() > 0) {
            for (E e: c) {
                mStorage.add(index++, e);
            }
            onChanged();
            return true;
        } else {
            return false;
        }
    }


    /**
     * Remove all elements from the list.
     */
    public void clear() {
        // The storage cannot be simply cleared.  Each element in the storage must be
        // unregistered.  Deregistration is only needed if the array is actually
        // watching.
        if (mWatching) {
            final int end = mStorage.size();
            for (int i = 0; i < end; i++) {
                unregisterChild(mStorage.get(i));
            }
        }
        mStorage.clear();
        onChanged();
    }

    /**
     * Return true if the object is in the array.
     */
    public boolean contains(Object o) {
        return mStorage.contains(o);
    }

    /**
     * Return true if all the objects in the given collection are in this array list.
     */
    public boolean containsAll(Collection<?> c) {
        return mStorage.containsAll(c);
    }

    /**
     * Ensure capacity.
     */
    public void ensureCapacity(int min) {
        mStorage.ensureCapacity(min);
    }

    /**
     * Retrieve the element at the specified index.
     */
    public E get(int index) {
        return mStorage.get(index);
    }

    /**
     * Return the index of the object.  -1 is returned if the object is not in the list.
     */
    public int indexOf(Object o) {
        return mStorage.indexOf(o);
    }

    /**
     * True if the list has no elements
     */
    public boolean isEmpty() {
        return mStorage.isEmpty();
    }

    /**
     * Return the index of the last occurrence of the object.
     */
    public int lastIndexOf(Object o) {
        return mStorage.lastIndexOf(o);
    }

    /**
     * Remove and return the element at the specified position.
     */
    public E remove(int index) {
        final E result = mStorage.remove(index);
        unregisterChildIf(result);
        onChanged();
        return result;
    }

    /**
     * Remove the first occurrence of the object in the list.  Return true if the object
     * was actually in the list and false otherwise.
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
     * Replace the object at the index.
     */
    public E set(int index, E value) {
        final E result = mStorage.set(index, value);
        if (value != result) {
            unregisterChildIf(result);
            registerChild(value);
            onChanged();
        }
        return result;
    }

    /**
     * Return the number of elements in the list.
     */
    public int size() {
        return mStorage.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(@Nullable Object o) {
        if (o instanceof WatchedArrayList) {
            WatchedArrayList w = (WatchedArrayList) o;
            return mStorage.equals(w.mStorage);
        } else {
            return false;
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
     * Create a copy of the array.  If the element is a subclass of Snapper then the copy
     * contains snapshots of the elements.  Otherwise the copy contains references to the
     * elements.  The returned snapshot is immutable.
     * @return A new array whose elements are the elements of <this>.
     */
    public WatchedArrayList<E> snapshot() {
        WatchedArrayList<E> l = new WatchedArrayList<>(size());
        snapshot(l, this);
        return l;
    }

    /**
     * Make <this> a snapshot of the argument.  Note that <this> is immutable when the
     * method returns.  <this> must be empty when the function is called.
     * @param r The source array, which is copied into <this>
     */
    public void snapshot(@NonNull WatchedArrayList<E> r) {
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
    public static <E> void snapshot(@NonNull WatchedArrayList<E> dst,
            @NonNull WatchedArrayList<E> src) {
        if (dst.size() != 0) {
            throw new IllegalArgumentException("snapshot destination is not empty");
        }
        final int end = src.size();
        dst.mStorage.ensureCapacity(end);
        for (int i = 0; i < end; i++) {
            final E val = Snapshots.maybeSnapshot(src.get(i));
            dst.mStorage.add(val);
        }
        dst.seal();
    }
}
