/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.os;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;
import android.ravenwood.annotation.RavenwoodRedirect;
import android.ravenwood.annotation.RavenwoodRedirectionClass;
import android.util.Log;
import android.util.Printer;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;

import java.io.FileDescriptor;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Low-level class holding the list of messages to be dispatched by a
 * {@link Looper}.  Messages are not added directly to a MessageQueue,
 * but rather through {@link Handler} objects associated with the Looper.
 *
 * <p>You can retrieve the MessageQueue for the current thread with
 * {@link Looper#myQueue() Looper.myQueue()}.
 */
@RavenwoodKeepWholeClass
@RavenwoodRedirectionClass("MessageQueue_host")
public final class MessageQueue {
    private static final String TAG = "LockedMessageQueue";
    private static final boolean DEBUG = false;
    private static final boolean TRACE = false;

    static final class MessageHeap {
        static final int MESSAGE_HEAP_INITIAL_SIZE = 16;

        Message[] mHeap = new Message[MESSAGE_HEAP_INITIAL_SIZE];
        int mNumElements = 0;

        static int parentNodeIdx(int i) {
            return (i - 1) >>> 1;
        }

        Message getParentNode(int i) {
            return mHeap[(i - 1) >>> 1];
        }

        static int rightNodeIdx(int i) {
            return 2 * i + 2;
        }

        Message getRightNode(int i) {
            return mHeap[2 * i + 2];
        }

        static int leftNodeIdx(int i) {
            return 2 * i + 1;
        }

        Message getLeftNode(int i) {
            return mHeap[2 * i + 1];
        }

        int size() {
            return mHeap.length;
        }

        int numElements() {
            return mNumElements;
        }

        boolean isEmpty() {
            return mNumElements == 0;
        }

        Message getMessageAt(int index) {
            return mHeap[index];
        }

        /*
        * Returns:
        *    0 if x==y.
        *    A value less than 0 if x<y.
        *    A value greater than 0 if x>y.
        */
        int compareMessage(Message x, Message y) {
            int compared = Long.compare(x.when, y.when);
            if (compared == 0) {
                compared = Long.compare(x.mInsertSeq, y.mInsertSeq);
            }
            return compared;
        }

        int compareMessageByIdx(int x, int y) {
            return compareMessage(mHeap[x], mHeap[y]);
        }

        void swap(int x, int y) {
            Message tmp = mHeap[x];
            mHeap[x] = mHeap[y];
            mHeap[y] = tmp;
        }

        void siftDown(int i) {
            int smallest = i;
            int r, l;

            while (true) {
                r = rightNodeIdx(i);
                l = leftNodeIdx(i);

                if (r < mNumElements && compareMessageByIdx(r, smallest) < 0) {
                    smallest = r;
                }

                if (l < mNumElements && compareMessageByIdx(l, smallest) < 0) {
                    smallest = l;
                }

                if (smallest != i) {
                    swap(i, smallest);
                    i = smallest;
                    continue;
                }
                break;
            }
        }

        boolean siftUp(int i) {
            boolean swapped = false;
            while (i != 0 && compareMessage(mHeap[i], getParentNode(i)) < 0) {
                int p = parentNodeIdx(i);

                swap(i, p);
                swapped = true;
                i = p;
            }

            return swapped;
        }

        void maybeGrowHeap() {
            if (mNumElements == mHeap.length) {
                /* Grow by 1.5x */
                int newSize = mHeap.length + (mHeap.length >>> 1);
                Message[] newHeap;
                if (DEBUG) {
                    Log.v(TAG, "maybeGrowHeap mNumElements " + mNumElements + " mHeap.length "
                            + mHeap.length + " newSize " + newSize);
                }

                newHeap = Arrays.copyOf(mHeap, newSize);
                mHeap = newHeap;
            }
        }

        void add(Message m) {
            int i;

            maybeGrowHeap();

            i = mNumElements;
            mNumElements++;
            mHeap[i] = m;

            siftUp(i);
        }

        void maybeShrinkHeap() {
            /* Shrink by 2x */
            int newSize = mHeap.length >>> 1;

            if (newSize >= MESSAGE_HEAP_INITIAL_SIZE
                    && mNumElements <= newSize) {
                Message[] newHeap;

                if (DEBUG) {
                    Log.v(TAG, "maybeShrinkHeap mNumElements " + mNumElements + " mHeap.length "
                            + mHeap.length + " newSize " + newSize);
                }

                newHeap = Arrays.copyOf(mHeap, newSize);
                mHeap = newHeap;
            }
        }

        Message poll() {
            if (mNumElements > 0) {
                Message ret = mHeap[0];
                mNumElements--;
                mHeap[0] = mHeap[mNumElements];
                mHeap[mNumElements] = null;

                siftDown(0);

                maybeShrinkHeap();
                return ret;
            }
            return null;
        }

        Message peek() {
            if (mNumElements > 0) {
                return mHeap[0];
            }
            return null;
        }

        private void remove(int i) throws IllegalArgumentException {
            if (i > mNumElements || mNumElements == 0) {
                throw new IllegalArgumentException("Index " + i + " out of bounds: "
                        + mNumElements);
            } else if (i == (mNumElements - 1)) {
                mHeap[i] = null;
                mNumElements--;
            } else {
                mNumElements--;
                mHeap[i] = mHeap[mNumElements];
                mHeap[mNumElements] = null;
                if (!siftUp(i)) {
                    siftDown(i);
                }
            }
            /* Don't shink here, let the caller do this once it has removed all matching items. */
        }

        void removeAll() {
            Message m;
            for (int i = 0; i < mNumElements; i++) {
                m = mHeap[i];
                mHeap[i] = null;
                m.recycleUnchecked();
            }
            mNumElements = 0;
            maybeShrinkHeap();
        }

        abstract static class MessageHeapCompare {
            public abstract boolean compareMessage(Message m, Handler h, int what, Object object,
                    Runnable r, long when);
        }

        boolean findOrRemoveMessages(Handler h, int what, Object object, Runnable r, long when,
                MessageHeapCompare compare, boolean removeMatches) {
            boolean found = false;
            /*
             * Walk the heap backwards so we don't have to re-visit an array element due to
             * sifting
             */
            for (int i = mNumElements - 1; i >= 0; i--) {
                if (compare.compareMessage(mHeap[i], h, what, object, r, when)) {
                    found = true;
                    if (removeMatches) {
                        Message m = mHeap[i];
                        try {
                            remove(i);
                        } catch (IllegalArgumentException e) {
                            Log.wtf(TAG, "Index out of bounds during remove " + e);
                        }
                        m.recycleUnchecked();
                        continue;
                    }
                    break;
                }
            }
            if (found && removeMatches) {
                maybeShrinkHeap();
            }
            return found;
        }

        /*
        * Keep this for manual debugging. It's easier to pepper the code with this function
        * than MessageQueue.dump()
        */
        void print() {
            Log.v(TAG, "heap num elem: " + mNumElements + " mHeap.length " + mHeap.length);
            for (int i = 0; i < mNumElements; i++) {
                Log.v(TAG, "[" + i + "]\t" + mHeap[i] + " seq: " + mHeap[i].mInsertSeq + " async: "
                        + mHeap[i].isAsynchronous());
            }
        }

        boolean verify(int root) {
            int r = rightNodeIdx(root);
            int l = leftNodeIdx(root);

            if (l >= mNumElements && r >= mNumElements) {
                return true;
            }

            if (l < mNumElements && compareMessageByIdx(l, root) < 0) {
                Log.wtf(TAG, "Verify failure: root idx/when: " + root + "/" + mHeap[root].when
                        + " left node idx/when: " + l + "/" + mHeap[l].when);
                return false;
            }

            if (r < mNumElements && compareMessageByIdx(r, root) < 0) {
                Log.wtf(TAG, "Verify failure: root idx/when: " + root + "/" + mHeap[root].when
                        + " right node idx/when: " + r + "/" + mHeap[r].when);
                return false;
            }

            if (!verify(r) || !verify(l)) {
                return false;
            }
            return true;
        }

        boolean checkDanglingReferences(String where) {
            /* First, let's make sure we didn't leave any dangling references */
            for (int i = mNumElements; i < mHeap.length; i++) {
                if (mHeap[i] != null) {
                    Log.wtf(TAG, "[" + where
                            + "] Verify failure: dangling reference found at index "
                            + i + ": " + mHeap[i] + " Async " + mHeap[i].isAsynchronous()
                            + " mNumElements " + mNumElements + " mHeap.length " + mHeap.length);
                    return false;
                }
            }
            return true;
        }

        boolean verify() {
            if (!checkDanglingReferences(TAG)) {
                return false;
            }
            return verify(0);
        }
    }

    // True if the message queue can be quit.
    @UnsupportedAppUsage
    private final boolean mQuitAllowed;

    @UnsupportedAppUsage
    @SuppressWarnings("unused")
    private long mPtr; // used by native code

    private final MessageHeap mPriorityQueue = new MessageHeap();
    private final MessageHeap mAsyncPriorityQueue = new MessageHeap();

    /*
     * This helps us ensure that messages with the same timestamp are inserted in FIFO order.
     * Increments on each insert, starting at 0. MessaeHeap.compareMessage() will compare sequences
     * when delivery timestamps are identical.
     */
    private long mNextInsertSeq;

    /*
     * The exception to the FIFO order rule is sendMessageAtFrontOfQueue().
     * Those messages must be in LIFO order.
     * Decrements on each front of queue insert.
     */
    private long mNextFrontInsertSeq = -1;

    @UnsupportedAppUsage
    private final ArrayList<IdleHandler> mIdleHandlers = new ArrayList<IdleHandler>();
    private SparseArray<FileDescriptorRecord> mFileDescriptorRecords;
    private IdleHandler[] mPendingIdleHandlers;
    private boolean mQuitting;

    // Indicates whether next() is blocked waiting in pollOnce() with a non-zero timeout.
    private boolean mBlocked;

    // The next barrier token.
    // Barriers are indicated by messages with a null target whose arg1 field carries the token.
    @UnsupportedAppUsage
    private int mNextBarrierToken;

    @RavenwoodRedirect
    private native static long nativeInit();
    @RavenwoodRedirect
    private native static void nativeDestroy(long ptr);
    @UnsupportedAppUsage
    @RavenwoodRedirect
    private native void nativePollOnce(long ptr, int timeoutMillis); /*non-static for callbacks*/
    @RavenwoodRedirect
    private native static void nativeWake(long ptr);
    @RavenwoodRedirect
    private native static boolean nativeIsPolling(long ptr);
    @RavenwoodRedirect
    private native static void nativeSetFileDescriptorEvents(long ptr, int fd, int events);

    MessageQueue(boolean quitAllowed) {
        mQuitAllowed = quitAllowed;
        mPtr = nativeInit();
    }

    @GuardedBy("this")
    private void removeRootFromPriorityQueue(Message msg) {
        Message tmp;
        if (msg.isAsynchronous()) {
            tmp = mAsyncPriorityQueue.poll();
        } else {
            tmp = mPriorityQueue.poll();
        }
        if (DEBUG && tmp != msg) {
            Log.wtf(TAG, "Unexpected message at head of heap. Wanted: " + msg + " msg.isAsync "
                    + msg.isAsynchronous() + " Found: " + tmp);

            mPriorityQueue.print();
            mAsyncPriorityQueue.print();
        }
    }

    @GuardedBy("this")
    private Message pickEarliestMessage(Message x, Message y) {
        if (x != null && y != null) {
            if (mPriorityQueue.compareMessage(x, y) < 0) {
                return x;
            }
            return y;
        }

        return x != null ? x : y;
    }

    @GuardedBy("this")
    private Message peekEarliestMessage() {
        Message x = mPriorityQueue.peek();
        Message y = mAsyncPriorityQueue.peek();

        return pickEarliestMessage(x, y);
    }

    @GuardedBy("this")
    private boolean priorityQueuesAreEmpty() {
        return mPriorityQueue.isEmpty() && mAsyncPriorityQueue.isEmpty();
    }

    @GuardedBy("this")
    private boolean priorityQueueHasBarrier() {
        Message m = mPriorityQueue.peek();

        if (m != null && m.target == null) {
            return true;
        }
        return false;
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            dispose();
        } finally {
            super.finalize();
        }
    }

    // Disposes of the underlying message queue.
    // Must only be called on the looper thread or the finalizer.
    private void dispose() {
        if (mPtr != 0) {
            nativeDestroy(mPtr);
            mPtr = 0;
        }
    }

    /**
     * Returns true if the looper has no pending messages which are due to be processed.
     *
     * <p>This method is safe to call from any thread.
     *
     * @return True if the looper is idle.
     */
    public boolean isIdle() {
        synchronized (this) {
            Message m = peekEarliestMessage();
            final long now = SystemClock.uptimeMillis();

            return (priorityQueuesAreEmpty() || now < m.when);
        }
    }

    /**
     * Add a new {@link IdleHandler} to this message queue.  This may be
     * removed automatically for you by returning false from
     * {@link IdleHandler#queueIdle IdleHandler.queueIdle()} when it is
     * invoked, or explicitly removing it with {@link #removeIdleHandler}.
     *
     * <p>This method is safe to call from any thread.
     *
     * @param handler The IdleHandler to be added.
     */
    public void addIdleHandler(@NonNull IdleHandler handler) {
        if (handler == null) {
            throw new NullPointerException("Can't add a null IdleHandler");
        }
        synchronized (this) {
            mIdleHandlers.add(handler);
        }
    }

    /**
     * Remove an {@link IdleHandler} from the queue that was previously added
     * with {@link #addIdleHandler}.  If the given object is not currently
     * in the idle list, nothing is done.
     *
     * <p>This method is safe to call from any thread.
     *
     * @param handler The IdleHandler to be removed.
     */
    public void removeIdleHandler(@NonNull IdleHandler handler) {
        synchronized (this) {
            mIdleHandlers.remove(handler);
        }
    }

    /**
     * Returns whether this looper's thread is currently polling for more work to do.
     * This is a good signal that the loop is still alive rather than being stuck
     * handling a callback.  Note that this method is intrinsically racy, since the
     * state of the loop can change before you get the result back.
     *
     * <p>This method is safe to call from any thread.
     *
     * @return True if the looper is currently polling for events.
     * @hide
     */
    public boolean isPolling() {
        synchronized (this) {
            return isPollingLocked();
        }
    }

    private boolean isPollingLocked() {
        // If the loop is quitting then it must not be idling.
        // We can assume mPtr != 0 when mQuitting is false.
        return !mQuitting && nativeIsPolling(mPtr);
    }

    /**
     * Adds a file descriptor listener to receive notification when file descriptor
     * related events occur.
     * <p>
     * If the file descriptor has already been registered, the specified events
     * and listener will replace any that were previously associated with it.
     * It is not possible to set more than one listener per file descriptor.
     * </p><p>
     * It is important to always unregister the listener when the file descriptor
     * is no longer of use.
     * </p>
     *
     * @param fd The file descriptor for which a listener will be registered.
     * @param events The set of events to receive: a combination of the
     * {@link OnFileDescriptorEventListener#EVENT_INPUT},
     * {@link OnFileDescriptorEventListener#EVENT_OUTPUT}, and
     * {@link OnFileDescriptorEventListener#EVENT_ERROR} event masks.  If the requested
     * set of events is zero, then the listener is unregistered.
     * @param listener The listener to invoke when file descriptor events occur.
     *
     * @see OnFileDescriptorEventListener
     * @see #removeOnFileDescriptorEventListener
     */
    @android.ravenwood.annotation.RavenwoodThrow(blockedBy = android.os.ParcelFileDescriptor.class)
    public void addOnFileDescriptorEventListener(@NonNull FileDescriptor fd,
            @OnFileDescriptorEventListener.Events int events,
            @NonNull OnFileDescriptorEventListener listener) {
        if (fd == null) {
            throw new IllegalArgumentException("fd must not be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        synchronized (this) {
            updateOnFileDescriptorEventListenerLocked(fd, events, listener);
        }
    }

    /**
     * Removes a file descriptor listener.
     * <p>
     * This method does nothing if no listener has been registered for the
     * specified file descriptor.
     * </p>
     *
     * @param fd The file descriptor whose listener will be unregistered.
     *
     * @see OnFileDescriptorEventListener
     * @see #addOnFileDescriptorEventListener
     */
    @android.ravenwood.annotation.RavenwoodThrow(blockedBy = android.os.ParcelFileDescriptor.class)
    public void removeOnFileDescriptorEventListener(@NonNull FileDescriptor fd) {
        if (fd == null) {
            throw new IllegalArgumentException("fd must not be null");
        }

        synchronized (this) {
            updateOnFileDescriptorEventListenerLocked(fd, 0, null);
        }
    }

    @android.ravenwood.annotation.RavenwoodThrow(blockedBy = android.os.ParcelFileDescriptor.class)
    private void updateOnFileDescriptorEventListenerLocked(FileDescriptor fd, int events,
            OnFileDescriptorEventListener listener) {
        final int fdNum = fd.getInt$();

        int index = -1;
        FileDescriptorRecord record = null;
        if (mFileDescriptorRecords != null) {
            index = mFileDescriptorRecords.indexOfKey(fdNum);
            if (index >= 0) {
                record = mFileDescriptorRecords.valueAt(index);
                if (record != null && record.mEvents == events) {
                    return;
                }
            }
        }

        if (events != 0) {
            events |= OnFileDescriptorEventListener.EVENT_ERROR;
            if (record == null) {
                if (mFileDescriptorRecords == null) {
                    mFileDescriptorRecords = new SparseArray<FileDescriptorRecord>();
                }
                record = new FileDescriptorRecord(fd, events, listener);
                mFileDescriptorRecords.put(fdNum, record);
            } else {
                record.mListener = listener;
                record.mEvents = events;
                record.mSeq += 1;
            }
            nativeSetFileDescriptorEvents(mPtr, fdNum, events);
        } else if (record != null) {
            record.mEvents = 0;
            mFileDescriptorRecords.removeAt(index);
            nativeSetFileDescriptorEvents(mPtr, fdNum, 0);
        }
    }

    // Called from native code.
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private int dispatchEvents(int fd, int events) {
        // Get the file descriptor record and any state that might change.
        final FileDescriptorRecord record;
        final int oldWatchedEvents;
        final OnFileDescriptorEventListener listener;
        final int seq;
        synchronized (this) {
            record = mFileDescriptorRecords.get(fd);
            if (record == null) {
                return 0; // spurious, no listener registered
            }

            oldWatchedEvents = record.mEvents;
            events &= oldWatchedEvents; // filter events based on current watched set
            if (events == 0) {
                return oldWatchedEvents; // spurious, watched events changed
            }

            listener = record.mListener;
            seq = record.mSeq;
        }

        // Invoke the listener outside of the lock.
        int newWatchedEvents = listener.onFileDescriptorEvents(
                record.mDescriptor, events);
        if (newWatchedEvents != 0) {
            newWatchedEvents |= OnFileDescriptorEventListener.EVENT_ERROR;
        }

        // Update the file descriptor record if the listener changed the set of
        // events to watch and the listener itself hasn't been updated since.
        if (newWatchedEvents != oldWatchedEvents) {
            synchronized (this) {
                int index = mFileDescriptorRecords.indexOfKey(fd);
                if (index >= 0 && mFileDescriptorRecords.valueAt(index) == record
                        && record.mSeq == seq) {
                    record.mEvents = newWatchedEvents;
                    if (newWatchedEvents == 0) {
                        mFileDescriptorRecords.removeAt(index);
                    }
                }
            }
        }

        // Return the new set of events to watch for native code to take care of.
        return newWatchedEvents;
    }

    private static final AtomicLong mMessagesDelivered = new AtomicLong();

    @UnsupportedAppUsage
    Message next() {
        // Return here if the message loop has already quit and been disposed.
        // This can happen if the application tries to restart a looper after quit
        // which is not supported.
        final long ptr = mPtr;
        if (ptr == 0) {
            return null;
        }

        int pendingIdleHandlerCount = -1; // -1 only during first iteration
        int nextPollTimeoutMillis = 0;
        for (;;) {
            if (nextPollTimeoutMillis != 0) {
                Binder.flushPendingCommands();
            }

            nativePollOnce(ptr, nextPollTimeoutMillis);

            synchronized (this) {
                // Try to retrieve the next message.  Return if found.
                final long now = SystemClock.uptimeMillis();
                Message prevMsg = null;
                Message msg = peekEarliestMessage();

                if (DEBUG && msg != null) {
                    Log.v(TAG, "Next found message " + msg + " isAsynchronous: "
                            + msg.isAsynchronous() + " target " + msg.target);
                }

                if (msg != null && !msg.isAsynchronous() && msg.target == null) {
                    // Stalled by a barrier.  Find the next asynchronous message in the queue.
                    msg = mAsyncPriorityQueue.peek();
                    if (DEBUG) {
                        Log.v(TAG, "Next message was barrier async msg: " + msg);
                    }
                }

                if (msg != null) {
                    if (now < msg.when) {
                        // Next message is not ready.  Set a timeout to wake up when it is ready.
                        nextPollTimeoutMillis = (int) Math.min(msg.when - now, Integer.MAX_VALUE);
                    } else {
                        mBlocked = false;
                        removeRootFromPriorityQueue(msg);
                        if (DEBUG) Log.v(TAG, "Returning message: " + msg);
                        msg.markInUse();
                        if (TRACE) {
                            Trace.setCounter("MQ.Delivered", mMessagesDelivered.incrementAndGet());
                        }
                        return msg;
                    }
                } else {
                    // No more messages.
                    nextPollTimeoutMillis = -1;
                }

                // Process the quit message now that all pending messages have been handled.
                if (mQuitting) {
                    dispose();
                    return null;
                }

                // If first time idle, then get the number of idlers to run.
                // Idle handles only run if the queue is empty or if the first message
                // in the queue (possibly a barrier) is due to be handled in the future.
                Message next = peekEarliestMessage();
                if (pendingIdleHandlerCount < 0
                        && (next == null || now < next.when)) {
                    pendingIdleHandlerCount = mIdleHandlers.size();
                }
                if (pendingIdleHandlerCount <= 0) {
                    // No idle handlers to run.  Loop and wait some more.
                    mBlocked = true;
                    continue;
                }

                if (mPendingIdleHandlers == null) {
                    mPendingIdleHandlers = new IdleHandler[Math.max(pendingIdleHandlerCount, 4)];
                }
                mPendingIdleHandlers = mIdleHandlers.toArray(mPendingIdleHandlers);
            }

            // Run the idle handlers.
            // We only ever reach this code block during the first iteration.
            for (int i = 0; i < pendingIdleHandlerCount; i++) {
                final IdleHandler idler = mPendingIdleHandlers[i];
                mPendingIdleHandlers[i] = null; // release the reference to the handler

                boolean keep = false;
                try {
                    keep = idler.queueIdle();
                } catch (Throwable t) {
                    Log.wtf(TAG, "IdleHandler threw exception", t);
                }

                if (!keep) {
                    synchronized (this) {
                        mIdleHandlers.remove(idler);
                    }
                }
            }

            // Reset the idle handler count to 0 so we do not run them again.
            pendingIdleHandlerCount = 0;

            // While calling an idle handler, a new message could have been delivered
            // so go back and look again for a pending message without waiting.
            nextPollTimeoutMillis = 0;
        }
    }

    void quit(boolean safe) {
        if (!mQuitAllowed) {
            throw new IllegalStateException("Main thread not allowed to quit.");
        }

        synchronized (this) {
            if (mQuitting) {
                return;
            }
            mQuitting = true;

            if (safe) {
                removeAllFutureMessagesLocked();
            } else {
                removeAllMessagesLocked();
            }

            // We can assume mPtr != 0 because mQuitting was previously false.
            nativeWake(mPtr);
        }
    }

    /**
     * Posts a synchronization barrier to the Looper's message queue.
     *
     * Message processing occurs as usual until the message queue encounters the
     * synchronization barrier that has been posted.  When the barrier is encountered,
     * later synchronous messages in the queue are stalled (prevented from being executed)
     * until the barrier is released by calling {@link #removeSyncBarrier} and specifying
     * the token that identifies the synchronization barrier.
     *
     * This method is used to immediately postpone execution of all subsequently posted
     * synchronous messages until a condition is met that releases the barrier.
     * Asynchronous messages (see {@link Message#isAsynchronous} are exempt from the barrier
     * and continue to be processed as usual.
     *
     * This call must be always matched by a call to {@link #removeSyncBarrier} with
     * the same token to ensure that the message queue resumes normal operation.
     * Otherwise the application will probably hang!
     *
     * @return A token that uniquely identifies the barrier.  This token must be
     * passed to {@link #removeSyncBarrier} to release the barrier.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @TestApi
    public int postSyncBarrier() {
        return postSyncBarrier(SystemClock.uptimeMillis());
    }

    private int postSyncBarrier(long when) {
        // Enqueue a new sync barrier token.
        // We don't need to wake the queue because the purpose of a barrier is to stall it.
        synchronized (this) {
            final int token = mNextBarrierToken++;
            final Message msg = Message.obtain();
            msg.arg1 = token;

            enqueueMessageUnchecked(msg, when);
            return token;
        }
    }

    private class MatchBarrierToken extends MessageHeap.MessageHeapCompare {
        int mBarrierToken;

        MatchBarrierToken(int token) {
            super();
            mBarrierToken = token;
        }

        @Override
        public boolean compareMessage(Message m, Handler h, int what, Object object, Runnable r,
                long when) {
            if (m.target == null && m.arg1 == mBarrierToken) {
                return true;
            }
            return false;
        }
    }

    /**
     * Removes a synchronization barrier.
     *
     * @param token The synchronization barrier token that was returned by
     * {@link #postSyncBarrier}.
     *
     * @throws IllegalStateException if the barrier was not found.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @TestApi
    public void removeSyncBarrier(int token) {
        final MatchBarrierToken matchBarrierToken = new MatchBarrierToken(token);

        // Remove a sync barrier token from the queue.
        // If the queue is no longer stalled by a barrier then wake it.
        synchronized (this) {
            boolean removed;
            Message first = mPriorityQueue.peek();

            removed = mPriorityQueue.findOrRemoveMessages(null, 0, null, null, 0,
                    matchBarrierToken, true);
            if (removed && first != null) {
                // If the loop is quitting then it is already awake.
                // We can assume mPtr != 0 when mQuitting is false.
                if (first.target == null && first.arg1 == token && !mQuitting) {
                    nativeWake(mPtr);
                }
            } else if (!removed) {
                throw new IllegalStateException("The specified message queue synchronization "
                        + " barrier token has not been posted or has already been removed.");
            }
        }
    }

    boolean enqueueMessage(Message msg, long when) {
        if (msg.target == null) {
            throw new IllegalArgumentException("Message must have a target.");
        }

        return enqueueMessageUnchecked(msg, when);
    }

    boolean enqueueMessageUnchecked(Message msg, long when) {
        synchronized (this) {
            if (mQuitting) {
                IllegalStateException e = new IllegalStateException(
                        msg.target + " sending message to a Handler on a dead thread");
                Log.w(TAG, e.getMessage(), e);
                msg.recycle();
                return false;
            }

            if (msg.isInUse()) {
                throw new IllegalStateException(msg + " This message is already in use.");
            }

            msg.markInUse();
            msg.when = when;
            msg.mInsertSeq = when != 0 ? mNextInsertSeq++ : mNextFrontInsertSeq--;
            if (DEBUG) Log.v(TAG, "Enqueue message: " + msg);
            boolean needWake;
            boolean isBarrier = msg.target == null;
            Message first = peekEarliestMessage();

            if (priorityQueuesAreEmpty() || when == 0 || when < first.when) {
                needWake = mBlocked && !isBarrier;
            } else {
                Message firstNonAsyncMessage =
                        first.isAsynchronous() ? mPriorityQueue.peek() : first;

                needWake = mBlocked && firstNonAsyncMessage != null
                        && firstNonAsyncMessage.target == null && msg.isAsynchronous();
            }

            if (msg.isAsynchronous()) {
                mAsyncPriorityQueue.add(msg);
            } else {
                mPriorityQueue.add(msg);
            }

            // We can assume mPtr != 0 because mQuitting is false.
            if (needWake) {
                nativeWake(mPtr);
            }
        }
        return true;
    }

    @GuardedBy("this")
    boolean findOrRemoveMessages(Handler h, int what, Object object, Runnable r, long when,
                MessageHeap.MessageHeapCompare compare, boolean removeMatches) {
        boolean found = mPriorityQueue.findOrRemoveMessages(h, what, object, r, when, compare,
                removeMatches);
        boolean foundAsync = mAsyncPriorityQueue.findOrRemoveMessages(h, what, object, r, when,
                compare, removeMatches);
        return found || foundAsync;
    }

    private static class MatchHandlerWhatAndObject extends MessageHeap.MessageHeapCompare {
        @Override
        public boolean compareMessage(Message m, Handler h, int what, Object object, Runnable r,
                long when) {
            if (m.target == h && m.what == what && (object == null || m.obj == object)) {
                return true;
            }
            return false;
        }
    }
    private static final MatchHandlerWhatAndObject sMatchHandlerWhatAndObject =
            new MatchHandlerWhatAndObject();

    boolean hasMessages(Handler h, int what, Object object) {
        if (h == null) {
            return false;
        }

        synchronized (this) {
            return findOrRemoveMessages(h, what, object, null, 0, sMatchHandlerWhatAndObject,
                    false);
        }
    }

    private static class MatchHandlerWhatAndObjectEquals extends MessageHeap.MessageHeapCompare {
        @Override
        public boolean compareMessage(Message m, Handler h, int what, Object object, Runnable r,
                long when) {
            if (m.target == h && m.what == what && (object == null || object.equals(m.obj))) {
                return true;
            }
            return false;
        }
    }
    private static final MatchHandlerWhatAndObjectEquals sMatchHandlerWhatAndObjectEquals =
            new MatchHandlerWhatAndObjectEquals();
    boolean hasEqualMessages(Handler h, int what, Object object) {
        if (h == null) {
            return false;
        }

        synchronized (this) {
            return findOrRemoveMessages(h, what, object, null, 0,
                    sMatchHandlerWhatAndObjectEquals, false);
        }
    }

    private static class MatchHandlerRunnableAndObject extends MessageHeap.MessageHeapCompare {
        @Override
        public boolean compareMessage(Message m, Handler h, int what, Object object, Runnable r,
                long when) {
            if (m.target == h && m.callback == r && (object == null || m.obj == object)) {
                return true;
            }
            return false;
        }
    }
    private static final MatchHandlerRunnableAndObject sMatchHandlerRunnableAndObject =
            new MatchHandlerRunnableAndObject();
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    boolean hasMessages(Handler h, Runnable r, Object object) {
        if (h == null) {
            return false;
        }

        synchronized (this) {
            return findOrRemoveMessages(h, -1, object, r, 0, sMatchHandlerRunnableAndObject,
                    false);
        }
    }

    private static class MatchHandler extends MessageHeap.MessageHeapCompare {
        @Override
        public boolean compareMessage(Message m, Handler h, int what, Object object, Runnable r,
                long when) {
            if (m.target == h) {
                return true;
            }
            return false;
        }
    }
    private static final MatchHandler sMatchHandler = new MatchHandler();
    boolean hasMessages(Handler h) {
        if (h == null) {
            return false;
        }

        synchronized (this) {
            return findOrRemoveMessages(h, -1, null, null, 0, sMatchHandler, false);
        }
    }

    void removeMessages(Handler h, int what, Object object) {
        if (h == null) {
            return;
        }

        synchronized (this) {
            findOrRemoveMessages(h, what, object, null, 0, sMatchHandlerWhatAndObject, true);
        }
    }

    void removeEqualMessages(Handler h, int what, Object object) {
        if (h == null) {
            return;
        }

        synchronized (this) {
            findOrRemoveMessages(h, what, object, null, 0, sMatchHandlerWhatAndObjectEquals, true);
        }
    }

    void removeMessages(Handler h, Runnable r, Object object) {
        if (h == null || r == null) {
            return;
        }

        synchronized (this) {
            findOrRemoveMessages(h, -1, object, r, 0, sMatchHandlerRunnableAndObject, true);
        }
    }

    private static class MatchHandlerRunnableAndObjectEquals
            extends MessageHeap.MessageHeapCompare {
        @Override
        public boolean compareMessage(Message m, Handler h, int what, Object object, Runnable r,
                long when) {
            if (m.target == h && m.callback == r && (object == null || object.equals(m.obj))) {
                return true;
            }
            return false;
        }
    }
    private static final MatchHandlerRunnableAndObjectEquals sMatchHandlerRunnableAndObjectEquals =
            new MatchHandlerRunnableAndObjectEquals();
    void removeEqualMessages(Handler h, Runnable r, Object object) {
        if (h == null || r == null) {
            return;
        }

        synchronized (this) {
            findOrRemoveMessages(h, -1, object, r, 0, sMatchHandlerRunnableAndObjectEquals, true);
        }
    }

    private static class MatchHandlerAndObject extends MessageHeap.MessageHeapCompare {
        @Override
        public boolean compareMessage(Message m, Handler h, int what, Object object, Runnable r,
                long when) {
            if (m.target == h && (object == null || m.obj == object)) {
                return true;
            }
            return false;
        }
    }
    private static final MatchHandlerAndObject sMatchHandlerAndObject = new MatchHandlerAndObject();
    void removeCallbacksAndMessages(Handler h, Object object) {
        if (h == null) {
            return;
        }

        synchronized (this) {
            findOrRemoveMessages(h, -1, object, null, 0, sMatchHandlerAndObject, true);
        }
    }

    private static class MatchHandlerAndObjectEquals extends MessageHeap.MessageHeapCompare {
        @Override
        public boolean compareMessage(Message m, Handler h, int what, Object object, Runnable r,
                long when) {
            if (m.target == h && (object == null || object.equals(m.obj))) {
                return true;
            }
            return false;
        }
    }
    private static final MatchHandlerAndObjectEquals sMatchHandlerAndObjectEquals =
            new MatchHandlerAndObjectEquals();
    void removeCallbacksAndEqualMessages(Handler h, Object object) {
        if (h == null) {
            return;
        }

        synchronized (this) {
            findOrRemoveMessages(h, -1, object, null, 0, sMatchHandlerAndObjectEquals, true);
        }
    }

    @GuardedBy("this")
    private void removeAllMessagesLocked() {
        mPriorityQueue.removeAll();
        mAsyncPriorityQueue.removeAll();
    }

    private static class MatchAllFutureMessages extends MessageHeap.MessageHeapCompare {
        @Override
        public boolean compareMessage(Message m, Handler h, int what, Object object, Runnable r,
                long when) {
            if (m.when > when) {
                return true;
            }
            return false;
        }
    }
    private static final MatchAllFutureMessages sMatchAllFutureMessages =
            new MatchAllFutureMessages();
    @GuardedBy("this")
    private void removeAllFutureMessagesLocked() {
        findOrRemoveMessages(null, -1, null, null, SystemClock.uptimeMillis(),
                sMatchAllFutureMessages, true);
    }

    int dumpPriorityQueue(Printer pw, String prefix, Handler h, MessageHeap priorityQueue) {
        int n = 0;
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < priorityQueue.numElements(); i++) {
            Message m = priorityQueue.getMessageAt(i);
            if (h == null && h == m.target) {
                pw.println(prefix + "Message " + n + ": " + m.toString(now));
                n++;
            }
        }
        return n;
    }

    void dumpPriorityQueue(ProtoOutputStream proto, MessageHeap priorityQueue) {
        for (int i = 0; i < priorityQueue.numElements(); i++) {
            Message m = priorityQueue.getMessageAt(i);
            m.dumpDebug(proto, MessageQueueProto.MESSAGES);
        }
    }

    void dump(Printer pw, String prefix, Handler h) {
        synchronized (this) {
            pw.println(prefix + "(MessageQueue is using Locked implementation)");
            long now = SystemClock.uptimeMillis();
            int n = dumpPriorityQueue(pw, prefix, h, mPriorityQueue);
            n += dumpPriorityQueue(pw, prefix, h, mAsyncPriorityQueue);
            pw.println(prefix + "(Total messages: " + n + ", polling=" + isPollingLocked()
                    + ", quitting=" + mQuitting + ")");
        }
    }

    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long messageQueueToken = proto.start(fieldId);
        synchronized (this) {
            dumpPriorityQueue(proto, mPriorityQueue);
            dumpPriorityQueue(proto, mAsyncPriorityQueue);
            proto.write(MessageQueueProto.IS_POLLING_LOCKED, isPollingLocked());
            proto.write(MessageQueueProto.IS_QUITTING, mQuitting);
        }
        proto.end(messageQueueToken);
    }

    /**
     * Callback interface for discovering when a thread is going to block
     * waiting for more messages.
     */
    public static interface IdleHandler {
        /**
         * Called when the message queue has run out of messages and will now
         * wait for more.  Return true to keep your idle handler active, false
         * to have it removed.  This may be called if there are still messages
         * pending in the queue, but they are all scheduled to be dispatched
         * after the current time.
         */
        boolean queueIdle();
    }

    /**
     * A listener which is invoked when file descriptor related events occur.
     */
    public interface OnFileDescriptorEventListener {
        /**
         * File descriptor event: Indicates that the file descriptor is ready for input
         * operations, such as reading.
         * <p>
         * The listener should read all available data from the file descriptor
         * then return <code>true</code> to keep the listener active or <code>false</code>
         * to remove the listener.
         * </p><p>
         * In the case of a socket, this event may be generated to indicate
         * that there is at least one incoming connection that the listener
         * should accept.
         * </p><p>
         * This event will only be generated if the {@link #EVENT_INPUT} event mask was
         * specified when the listener was added.
         * </p>
         */
        public static final int EVENT_INPUT = 1 << 0;

        /**
         * File descriptor event: Indicates that the file descriptor is ready for output
         * operations, such as writing.
         * <p>
         * The listener should write as much data as it needs.  If it could not
         * write everything at once, then it should return <code>true</code> to
         * keep the listener active.  Otherwise, it should return <code>false</code>
         * to remove the listener then re-register it later when it needs to write
         * something else.
         * </p><p>
         * This event will only be generated if the {@link #EVENT_OUTPUT} event mask was
         * specified when the listener was added.
         * </p>
         */
        public static final int EVENT_OUTPUT = 1 << 1;

        /**
         * File descriptor event: Indicates that the file descriptor encountered a
         * fatal error.
         * <p>
         * File descriptor errors can occur for various reasons.  One common error
         * is when the remote peer of a socket or pipe closes its end of the connection.
         * </p><p>
         * This event may be generated at any time regardless of whether the
         * {@link #EVENT_ERROR} event mask was specified when the listener was added.
         * </p>
         */
        public static final int EVENT_ERROR = 1 << 2;

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(flag = true, prefix = { "EVENT_" }, value = {
                EVENT_INPUT,
                EVENT_OUTPUT,
                EVENT_ERROR
        })
        public @interface Events {}

        /**
         * Called when a file descriptor receives events.
         *
         * @param fd The file descriptor.
         * @param events The set of events that occurred: a combination of the
         * {@link #EVENT_INPUT}, {@link #EVENT_OUTPUT}, and {@link #EVENT_ERROR} event masks.
         * @return The new set of events to watch, or 0 to unregister the listener.
         *
         * @see #EVENT_INPUT
         * @see #EVENT_OUTPUT
         * @see #EVENT_ERROR
         */
        @Events int onFileDescriptorEvents(@NonNull FileDescriptor fd, @Events int events);
    }

    private static final class FileDescriptorRecord {
        public final FileDescriptor mDescriptor;
        public int mEvents;
        public OnFileDescriptorEventListener mListener;
        public int mSeq;

        public FileDescriptorRecord(FileDescriptor descriptor,
                int events, OnFileDescriptorEventListener listener) {
            mDescriptor = descriptor;
            mEvents = events;
            mListener = listener;
        }
    }
}
