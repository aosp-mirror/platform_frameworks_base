/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.internal.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks callbacks for the event. This class supports reentrant modification
 * of the callbacks during notification without adversely disrupting notifications.
 * A common pattern for callbacks is to receive a notification and then remove
 * themselves. This class handles this behavior with constant memory under
 * most circumstances.
 *
 * <p>A subclass of {@link CallbackRegistry.NotifierCallback} must be passed to
 * the constructor to define how notifications should be called. That implementation
 * does the actual notification on the listener.</p>
 *
 * <p>This class supports only callbacks with at most two parameters.
 * Typically, these are the notification originator and a parameter, but these may
 * be used as required. If more than two parameters are required or primitive types
 * must be used, <code>A</code> should be some kind of containing structure that
 * the subclass may reuse between notifications.</p>
 *
 * @param <C> The callback type.
 * @param <T> The notification sender type. Typically this is the containing class.
 * @param <A> Opaque argument used to pass additional data beyond an int.
 */
public class CallbackRegistry<C, T, A> implements Cloneable {
    private static final String TAG = "CallbackRegistry";

    /** An ordered collection of listeners waiting to be notified. */
    private List<C> mCallbacks = new ArrayList<C>();

    /**
     * A bit flag for the first 64 listeners that are removed during notification.
     * The lowest significant bit corresponds to the 0th index into mCallbacks.
     * For a small number of callbacks, no additional array of objects needs to
     * be allocated.
     */
    private long mFirst64Removed = 0x0;

    /**
     * Bit flags for the remaining callbacks that are removed during notification.
     * When there are more than 64 callbacks and one is marked for removal, a dynamic
     * array of bits are allocated for the callbacks.
     */
    private long[] mRemainderRemoved;

    /**
     * The reentrancy level of the notification. When we notify a callback, it may cause
     * further notifications. The reentrancy level must be tracked to let us clean up
     * the callback state when all notifications have been processed.
     */
    private int mNotificationLevel;

    /** The notification mechanism for notifying an event. */
    private final NotifierCallback<C, T, A> mNotifier;

    /**
     * Creates an EventRegistry that notifies the event with notifier.
     * @param notifier The class to use to notify events.
     */
    public CallbackRegistry(NotifierCallback<C, T, A> notifier) {
        mNotifier = notifier;
    }

    /**
     * Notify all callbacks.
     *
     * @param sender The originator. This is an opaque parameter passed to
     *      {@link CallbackRegistry.NotifierCallback#onNotifyCallback(Object, Object, int, A)}
     * @param arg An opaque parameter passed to
     *      {@link CallbackRegistry.NotifierCallback#onNotifyCallback(Object, Object, int, A)}
     * @param arg2 An opaque parameter passed to
     *      {@link CallbackRegistry.NotifierCallback#onNotifyCallback(Object, Object, int, A)}
     */
    public synchronized void notifyCallbacks(T sender, int arg, A arg2) {
        mNotificationLevel++;
        notifyRecurseLocked(sender, arg, arg2);
        mNotificationLevel--;
        if (mNotificationLevel == 0) {
            if (mRemainderRemoved != null) {
                for (int i = mRemainderRemoved.length - 1; i >= 0; i--) {
                    final long removedBits = mRemainderRemoved[i];
                    if (removedBits != 0) {
                        removeRemovedCallbacks((i + 1) * Long.SIZE, removedBits);
                        mRemainderRemoved[i] = 0;
                    }
                }
            }
            if (mFirst64Removed != 0) {
                removeRemovedCallbacks(0, mFirst64Removed);
                mFirst64Removed = 0;
            }
        }
    }

    /**
     * Notify up to the first Long.SIZE callbacks that don't have a bit set in <code>removed</code>.
     *
     * @param sender The originator. This is an opaque parameter passed to
     *      {@link CallbackRegistry.NotifierCallback#onNotifyCallback(Object, Object, int, A)}
     * @param arg An opaque parameter passed to
     *      {@link CallbackRegistry.NotifierCallback#onNotifyCallback(Object, Object, int, A)}
     * @param arg2 An opaque parameter passed to
     *      {@link CallbackRegistry.NotifierCallback#onNotifyCallback(Object, Object, int, A)}
     */
    private void notifyFirst64Locked(T sender, int arg, A arg2) {
        final int maxNotified = Math.min(Long.SIZE, mCallbacks.size());
        notifyCallbacksLocked(sender, arg, arg2, 0, maxNotified, mFirst64Removed);
    }

    /**
     * Notify all callbacks using a recursive algorithm to avoid allocating on the heap.
     * This part captures the callbacks beyond Long.SIZE that have no bits allocated for
     * removal before it recurses into {@link #notifyRemainderLocked(Object, int, A, int)}.
     * <p>
     * Recursion is used to avoid allocating temporary state on the heap. Each stack has one
     * long (64 callbacks) worth of information of which has been removed.
     *
     * @param sender The originator. This is an opaque parameter passed to
     *      {@link CallbackRegistry.NotifierCallback#onNotifyCallback(Object, Object, int, A)}
     * @param arg An opaque parameter passed to
     *      {@link CallbackRegistry.NotifierCallback#onNotifyCallback(Object, Object, int, A)}
     * @param arg2 An opaque parameter passed to
     *      {@link CallbackRegistry.NotifierCallback#onNotifyCallback(Object, Object, int, A)}
     */
    private void notifyRecurseLocked(T sender, int arg, A arg2) {
        final int callbackCount = mCallbacks.size();
        final int remainderIndex = mRemainderRemoved == null ? -1 : mRemainderRemoved.length - 1;

        // Now we've got all callbacks that have no mRemainderRemoved value, so notify the
        // others.
        notifyRemainderLocked(sender, arg, arg2, remainderIndex);

        // notifyRemainderLocked notifies all at maxIndex, so we'd normally start at maxIndex + 1
        // However, we must also keep track of those in mFirst64Removed, so we add 2 instead:
        final int startCallbackIndex = (remainderIndex + 2) * Long.SIZE;

        // The remaining have no bit set
        notifyCallbacksLocked(sender, arg, arg2, startCallbackIndex, callbackCount, 0);
    }

    /**
     * Notify callbacks that have mRemainderRemoved bits set for remainderIndex. If
     * remainderIndex is -1, the first 64 will be notified instead.
     *
     * @param sender The originator. This is an opaque parameter passed to
     *      {@link CallbackRegistry.NotifierCallback#onNotifyCallback(Object, Object, int, A)}
     * @param arg An opaque parameter passed to
     *      {@link CallbackRegistry.NotifierCallback#onNotifyCallback(Object, Object, int, A)}
     * @param arg2 An opaque parameter passed to
     *      {@link CallbackRegistry.NotifierCallback#onNotifyCallback(Object, Object, int, A)}
     * @param remainderIndex The index into mRemainderRemoved that should be notified.
     */
    private void notifyRemainderLocked(T sender, int arg, A arg2, int remainderIndex) {
        if (remainderIndex < 0) {
            notifyFirst64Locked(sender, arg, arg2);
        } else {
            final long bits = mRemainderRemoved[remainderIndex];
            final int startIndex = (remainderIndex + 1) * Long.SIZE;
            final int endIndex = Math.min(mCallbacks.size(), startIndex + Long.SIZE);
            notifyRemainderLocked(sender, arg, arg2, remainderIndex - 1);
            notifyCallbacksLocked(sender, arg, arg2, startIndex, endIndex, bits);
        }
    }

    /**
     * Notify callbacks from startIndex to endIndex, using bits as the bit status
     * for whether they have been removed or not. bits should be from mRemainderRemoved or
     * mFirst64Removed. bits set to 0 indicates that all callbacks from startIndex to
     * endIndex should be notified.
     *
     * @param sender The originator. This is an opaque parameter passed to
     *      {@link CallbackRegistry.NotifierCallback#onNotifyCallback(Object, Object, int, A)}
     * @param arg An opaque parameter passed to
     *      {@link CallbackRegistry.NotifierCallback#onNotifyCallback(Object, Object, int, A)}
     * @param arg2 An opaque parameter passed to
     *      {@link CallbackRegistry.NotifierCallback#onNotifyCallback(Object, Object, int, A)}
     * @param startIndex The index into the mCallbacks to start notifying.
     * @param endIndex One past the last index into mCallbacks to notify.
     * @param bits A bit field indicating which callbacks have been removed and shouldn't
     *             be notified.
     */
    private void notifyCallbacksLocked(T sender, int arg, A arg2, final int startIndex,
            final int endIndex, final long bits) {
        long bitMask = 1;
        for (int i = startIndex; i < endIndex; i++) {
            if ((bits & bitMask) == 0) {
                mNotifier.onNotifyCallback(mCallbacks.get(i), sender, arg, arg2);
            }
            bitMask <<= 1;
        }
    }

    /**
     * Add a callback to be notified. If the callback is already in the list, another won't
     * be added. This does not affect current notifications.
     * @param callback The callback to add.
     */
    public synchronized void add(C callback) {
        int index = mCallbacks.lastIndexOf(callback);
        if (index < 0 || isRemovedLocked(index)) {
            mCallbacks.add(callback);
        }
    }

    /**
     * Returns true if the callback at index has been marked for removal.
     *
     * @param index The index into mCallbacks to check.
     * @return true if the callback at index has been marked for removal.
     */
    private boolean isRemovedLocked(int index) {
        if (index < Long.SIZE) {
            // It is in the first 64 callbacks, just check the bit.
            final long bitMask = 1L << index;
            return (mFirst64Removed & bitMask) != 0;
        } else if (mRemainderRemoved == null) {
            // It is after the first 64 callbacks, but nothing else was marked for removal.
            return false;
        } else {
            final int maskIndex = (index / Long.SIZE) - 1;
            if (maskIndex >= mRemainderRemoved.length) {
                // There are some items in mRemainderRemoved, but nothing at the given index.
                return false;
            } else {
                // There is something marked for removal, so we have to check the bit.
                final long bits = mRemainderRemoved[maskIndex];
                final long bitMask = 1L << (index % Long.SIZE);
                return (bits & bitMask) != 0;
            }
        }
    }

    /**
     * Removes callbacks from startIndex to startIndex + Long.SIZE, based
     * on the bits set in removed.
     * @param startIndex The index into the mCallbacks to start removing callbacks.
     * @param removed The bits indicating removal, where each bit is set for one callback
     *                to be removed.
     */
    private void removeRemovedCallbacks(int startIndex, long removed) {
        // The naive approach should be fine. There may be a better bit-twiddling approach.
        final int endIndex = startIndex + Long.SIZE;

        long bitMask = 1L << (Long.SIZE - 1);
        for (int i = endIndex - 1; i >= startIndex; i--) {
            if ((removed & bitMask) != 0) {
                mCallbacks.remove(i);
            }
            bitMask >>>= 1;
        }
    }

    /**
     * Remove a callback. This callback won't be notified after this call completes.
     * @param callback The callback to remove.
     */
    public synchronized void remove(C callback) {
        if (mNotificationLevel == 0) {
            mCallbacks.remove(callback);
        } else {
            int index = mCallbacks.lastIndexOf(callback);
            if (index >= 0) {
                setRemovalBitLocked(index);
            }
        }
    }

    private void setRemovalBitLocked(int index) {
        if (index < Long.SIZE) {
            // It is in the first 64 callbacks, just check the bit.
            final long bitMask = 1L << index;
            mFirst64Removed |= bitMask;
        } else {
            final int remainderIndex = (index / Long.SIZE) - 1;
            if (mRemainderRemoved == null) {
                mRemainderRemoved = new long[mCallbacks.size() / Long.SIZE];
            } else if (mRemainderRemoved.length < remainderIndex) {
                // need to make it bigger
                long[] newRemainders = new long[mCallbacks.size() / Long.SIZE];
                System.arraycopy(mRemainderRemoved, 0, newRemainders, 0, mRemainderRemoved.length);
                mRemainderRemoved = newRemainders;
            }
            final long bitMask = 1L << (index % Long.SIZE);
            mRemainderRemoved[remainderIndex] |= bitMask;
        }
    }

    /**
     * Makes a copy of the registered callbacks and returns it.
     *
     * @return a copy of the registered callbacks.
     */
    public synchronized ArrayList<C> copyListeners() {
        ArrayList<C> callbacks = new ArrayList<C>(mCallbacks.size());
        int numListeners = mCallbacks.size();
        for (int i = 0; i < numListeners; i++) {
            if (!isRemovedLocked(i)) {
                callbacks.add(mCallbacks.get(i));
            }
        }
        return callbacks;
    }

    /**
     * Returns true if there are no registered callbacks or false otherwise.
     *
     * @return true if there are no registered callbacks or false otherwise.
     */
    public synchronized boolean isEmpty() {
        if (mCallbacks.isEmpty()) {
            return true;
        } else if (mNotificationLevel == 0) {
            return false;
        } else {
            int numListeners = mCallbacks.size();
            for (int i = 0; i < numListeners; i++) {
                if (!isRemovedLocked(i)) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Removes all callbacks from the list.
     */
    public synchronized void clear() {
        if (mNotificationLevel == 0) {
            mCallbacks.clear();
        } else if (!mCallbacks.isEmpty()) {
            for (int i = mCallbacks.size() - 1; i >= 0; i--) {
                setRemovalBitLocked(i);
            }
        }
    }

    public synchronized CallbackRegistry<C, T, A> clone() {
        CallbackRegistry<C, T, A> clone = null;
        try {
            clone = (CallbackRegistry<C, T, A>) super.clone();
            clone.mFirst64Removed = 0;
            clone.mRemainderRemoved = null;
            clone.mNotificationLevel = 0;
            clone.mCallbacks = new ArrayList<C>();
            final int numListeners = mCallbacks.size();
            for (int i = 0; i < numListeners; i++) {
                if (!isRemovedLocked(i)) {
                    clone.mCallbacks.add(mCallbacks.get(i));
                }
            }
        } catch (CloneNotSupportedException e) {
            e.printStackTrace();
        }
        return clone;
    }

    /**
     * Class used to notify events from CallbackRegistry.
     *
     * @param <C> The callback type.
     * @param <T> The notification sender type. Typically this is the containing class.
     * @param <A> An opaque argument to pass to the notifier
     */
    public abstract static class NotifierCallback<C, T, A> {
        /**
         * Used to notify the callback.
         *
         * @param callback The callback to notify.
         * @param sender The opaque sender object.
         * @param arg The opaque notification parameter.
         * @param arg2 An opaque argument passed in
         *        {@link CallbackRegistry#notifyCallbacks}
         * @see CallbackRegistry#CallbackRegistry(CallbackRegistry.NotifierCallback)
         */
        public abstract void onNotifyCallback(C callback, T sender, int arg, A arg2);
    }
}
