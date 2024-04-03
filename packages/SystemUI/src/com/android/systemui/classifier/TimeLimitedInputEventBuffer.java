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

package com.android.systemui.classifier;

import android.view.InputEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

/**
 * Maintains an ordered list of the last N milliseconds of InputEvents.
 *
 * This class is simply a convenience class designed to look like a simple list, but that
 * automatically discards old InputEvents. It functions much like a queue - first in first out -
 * but does not have a fixed size like a circular buffer.
 */
public class TimeLimitedInputEventBuffer<T extends InputEvent> implements List<T> {

    private final List<T> mInputEvents;
    private final long mMaxAgeMs;

    public TimeLimitedInputEventBuffer(long maxAgeMs) {
        super();
        mMaxAgeMs = maxAgeMs;
        mInputEvents = new ArrayList<>();
    }

    private void ejectOldEvents() {
        if (mInputEvents.isEmpty()) {
            return;
        }
        Iterator<T> iter = listIterator();
        long mostRecentMs = mInputEvents.get(mInputEvents.size() - 1).getEventTime();
        while (iter.hasNext()) {
            T ev = iter.next();
            if (mostRecentMs - ev.getEventTime() > mMaxAgeMs) {
                iter.remove();
                ev.recycle();
            }
        }
    }

    @Override
    public void add(int index, T element) {
        throw new UnsupportedOperationException();
    }

    @Override
    public T remove(int index) {
        return mInputEvents.remove(index);
    }

    @Override
    public int indexOf(Object o) {
        return mInputEvents.indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
        return mInputEvents.lastIndexOf(o);
    }

    @Override
    public int size() {
        return mInputEvents.size();
    }

    @Override
    public boolean isEmpty() {
        return mInputEvents.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return mInputEvents.contains(o);
    }

    @Override
    public Iterator<T> iterator() {
        return mInputEvents.iterator();
    }

    @Override
    public Object[] toArray() {
        return mInputEvents.toArray();
    }

    @Override
    public <T2> T2[] toArray(T2[] a) {
        return mInputEvents.toArray(a);
    }

    @Override
    public boolean add(T element) {
        boolean result = mInputEvents.add(element);
        ejectOldEvents();
        return result;
    }

    @Override
    public boolean remove(Object o) {
        return mInputEvents.remove(o);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return mInputEvents.containsAll(c);
    }

    @Override
    public boolean addAll(Collection<? extends T> collection) {
        boolean result = mInputEvents.addAll(collection);
        ejectOldEvents();
        return result;
    }

    @Override
    public boolean addAll(int index, Collection<? extends T> elements) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return mInputEvents.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return mInputEvents.retainAll(c);
    }

    @Override
    public void clear() {
        mInputEvents.clear();
    }

    @Override
    public boolean equals(Object o) {
        return mInputEvents.equals(o);
    }

    @Override
    public int hashCode() {
        return mInputEvents.hashCode();
    }

    @Override
    public T get(int index) {
        return mInputEvents.get(index);
    }

    @Override
    public T set(int index, T element) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ListIterator<T> listIterator() {
        return new Iter(0);
    }

    @Override
    public ListIterator<T> listIterator(int index) {
        return new Iter(index);
    }

    @Override
    public List<T> subList(int fromIndex, int toIndex) {
        return mInputEvents.subList(fromIndex, toIndex);
    }

    class Iter implements ListIterator<T> {

        private final ListIterator<T> mIterator;

        Iter(int index) {
            this.mIterator = mInputEvents.listIterator(index);
        }

        @Override
        public boolean hasNext() {
            return mIterator.hasNext();
        }

        @Override
        public T next() {
            return mIterator.next();
        }

        @Override
        public boolean hasPrevious() {
            return mIterator.hasPrevious();
        }

        @Override
        public T previous() {
            return mIterator.previous();
        }

        @Override
        public int nextIndex() {
            return mIterator.nextIndex();
        }

        @Override
        public int previousIndex() {
            return mIterator.previousIndex();
        }

        @Override
        public void remove() {
            mIterator.remove();
        }

        @Override
        public void set(T inputEvent) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void add(T element) {
            throw new UnsupportedOperationException();
        }
    }
}
