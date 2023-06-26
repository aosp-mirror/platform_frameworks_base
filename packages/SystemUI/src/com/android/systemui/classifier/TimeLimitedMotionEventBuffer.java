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

import android.view.MotionEvent;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

/**
 * Maintains an ordered list of the last N milliseconds of MotionEvents.
 *
 * This class is simply a convenience class designed to look like a simple list, but that
 * automatically discards old MotionEvents. It functions much like a queue - first in first out -
 * but does not have a fixed size like a circular buffer.
 */
public class TimeLimitedMotionEventBuffer implements List<MotionEvent> {

    private final LinkedList<MotionEvent> mMotionEvents;
    private final long mMaxAgeMs;

    public TimeLimitedMotionEventBuffer(long maxAgeMs) {
        super();
        mMaxAgeMs = maxAgeMs;
        mMotionEvents = new LinkedList<>();
    }

    private void ejectOldEvents() {
        if (mMotionEvents.isEmpty()) {
            return;
        }
        Iterator<MotionEvent> iter = listIterator();
        long mostRecentMs = mMotionEvents.getLast().getEventTime();
        while (iter.hasNext()) {
            MotionEvent ev = iter.next();
            if (mostRecentMs - ev.getEventTime() > mMaxAgeMs) {
                iter.remove();
                ev.recycle();
            }
        }
    }

    @Override
    public void add(int index, MotionEvent element) {
        throw new UnsupportedOperationException();
    }

    @Override
    public MotionEvent remove(int index) {
        return mMotionEvents.remove(index);
    }

    @Override
    public int indexOf(Object o) {
        return mMotionEvents.indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
        return mMotionEvents.lastIndexOf(o);
    }

    @Override
    public int size() {
        return mMotionEvents.size();
    }

    @Override
    public boolean isEmpty() {
        return mMotionEvents.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return mMotionEvents.contains(o);
    }

    @Override
    public Iterator<MotionEvent> iterator() {
        return mMotionEvents.iterator();
    }

    @Override
    public Object[] toArray() {
        return mMotionEvents.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return mMotionEvents.toArray(a);
    }

    @Override
    public boolean add(MotionEvent element) {
        boolean result = mMotionEvents.add(element);
        ejectOldEvents();
        return result;
    }

    @Override
    public boolean remove(Object o) {
        return mMotionEvents.remove(o);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return mMotionEvents.containsAll(c);
    }

    @Override
    public boolean addAll(Collection<? extends MotionEvent> collection) {
        boolean result = mMotionEvents.addAll(collection);
        ejectOldEvents();
        return result;
    }

    @Override
    public boolean addAll(int index, Collection<? extends MotionEvent> elements) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return mMotionEvents.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return mMotionEvents.retainAll(c);
    }

    @Override
    public void clear() {
        mMotionEvents.clear();
    }

    @Override
    public boolean equals(Object o) {
        return mMotionEvents.equals(o);
    }

    @Override
    public int hashCode() {
        return mMotionEvents.hashCode();
    }

    @Override
    public MotionEvent get(int index) {
        return mMotionEvents.get(index);
    }

    @Override
    public MotionEvent set(int index, MotionEvent element) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ListIterator<MotionEvent> listIterator() {
        return new Iter(0);
    }

    @Override
    public ListIterator<MotionEvent> listIterator(int index) {
        return new Iter(index);
    }

    @Override
    public List<MotionEvent> subList(int fromIndex, int toIndex) {
        return mMotionEvents.subList(fromIndex, toIndex);
    }

    class Iter implements ListIterator<MotionEvent> {

        private final ListIterator<MotionEvent> mIterator;

        Iter(int index) {
            this.mIterator = mMotionEvents.listIterator(index);
        }

        @Override
        public boolean hasNext() {
            return mIterator.hasNext();
        }

        @Override
        public MotionEvent next() {
            return mIterator.next();
        }

        @Override
        public boolean hasPrevious() {
            return mIterator.hasPrevious();
        }

        @Override
        public MotionEvent previous() {
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
        public void set(MotionEvent motionEvent) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void add(MotionEvent element) {
            throw new UnsupportedOperationException();
        }
    }
}
