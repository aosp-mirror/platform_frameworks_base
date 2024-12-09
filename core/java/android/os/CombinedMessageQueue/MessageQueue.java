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
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.app.ActivityThread;
import android.app.Instrumentation;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Process;
import android.os.UserHandle;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;
import android.ravenwood.annotation.RavenwoodRedirect;
import android.ravenwood.annotation.RavenwoodRedirectionClass;
import android.ravenwood.annotation.RavenwoodReplace;
import android.ravenwood.annotation.RavenwoodThrow;
import android.util.Log;
import android.util.Printer;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.ravenwood.RavenwoodEnvironment;

import dalvik.annotation.optimization.NeverCompile;

import java.io.FileDescriptor;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Low-level class holding the list of messages to be dispatched by a
 * {@link Looper}.  Messages are not added directly to a MessageQueue,
 * but rather through {@link Handler} objects associated with the Looper.
 *
 * <p>You can retrieve the MessageQueue for the current thread with
 * {@link Looper#myQueue() Looper.myQueue()}.
 */
@RavenwoodKeepWholeClass
@RavenwoodRedirectionClass("MessageQueue_ravenwood")
public final class MessageQueue {
    private static final String TAG_L = "LegacyMessageQueue";
    private static final String TAG_C = "ConcurrentMessageQueue";
    private static final boolean DEBUG = false;
    private static final boolean TRACE = false;

    // True if the message queue can be quit.
    @UnsupportedAppUsage
    private final boolean mQuitAllowed;

    @UnsupportedAppUsage
    @SuppressWarnings("unused")
    private long mPtr; // used by native code

    @UnsupportedAppUsage
    Message mMessages;
    private Message mLast;
    @UnsupportedAppUsage
    private final ArrayList<IdleHandler> mIdleHandlers = new ArrayList<IdleHandler>();
    private SparseArray<FileDescriptorRecord> mFileDescriptorRecords;
    private IdleHandler[] mPendingIdleHandlers;
    private boolean mQuitting;

    // Indicates whether next() is blocked waiting in pollOnce() with a non-zero timeout.
    private boolean mBlocked;

    // Tracks the number of async message. We use this in enqueueMessage() to avoid searching the
    // queue for async messages when inserting a message at the tail.
    private int mAsyncMessageCount;

    /**
     * Select between two implementations of message queue. The legacy implementation is used
     * by default as it provides maximum compatibility with applications and tests that
     * reach into MessageQueue via the mMessages field. The concurrent implemmentation is used for
     * system processes and provides a higher level of concurrency and higher enqueue throughput
     * than the legacy implementation.
     */
    private final boolean mUseConcurrent;

    /**
     * Caches process-level checks that determine `mUseConcurrent`.
     * This is to avoid redoing checks that shouldn't change during the process's lifetime.
     */
    private static Boolean sIsProcessAllowedToUseConcurrent = null;

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
        initIsProcessAllowedToUseConcurrent();
        mUseConcurrent = sIsProcessAllowedToUseConcurrent && !isInstrumenting();
        mQuitAllowed = quitAllowed;
        mPtr = nativeInit();
    }

    private static void initIsProcessAllowedToUseConcurrent() {
        if (sIsProcessAllowedToUseConcurrent != null) {
            return;
        }

        if (RavenwoodEnvironment.getInstance().isRunningOnRavenwood()) {
            sIsProcessAllowedToUseConcurrent = false;
            return;
        }

        final String processName = Process.myProcessName();
        if (processName == null) {
            // Assume that this is a host-side test and avoid concurrent mode for now.
            sIsProcessAllowedToUseConcurrent = false;
            return;
        }

        // Concurrent mode modifies behavior that is observable via reflection and is commonly
        // used by tests.
        // For now, we limit it to system processes to avoid breaking apps and their tests.
        sIsProcessAllowedToUseConcurrent = UserHandle.isCore(Process.myUid());

        if (sIsProcessAllowedToUseConcurrent) {
            // Some platform tests run in core UIDs.
            // Use this awful heuristic to detect them.
            if (processName.contains("test") || processName.contains("Test")) {
                sIsProcessAllowedToUseConcurrent = false;
            }
        } else {
            // Also explicitly allow SystemUI processes.
            // SystemUI doesn't run in a core UID, but we want to give it the performance boost,
            // and we know that it's safe to use the concurrent implementation in SystemUI.
            sIsProcessAllowedToUseConcurrent =
                    processName.equals("com.android.systemui")
                            || processName.startsWith("com.android.systemui:");
            // On Android distributions where SystemUI has a different process name,
            // the above condition may need to be adjusted accordingly.
        }

        // We can lift these restrictions in the future after we've made it possible for test
        // authors to test Looper and MessageQueue without resorting to reflection.

        // Holdback study.
        if (sIsProcessAllowedToUseConcurrent && Flags.messageQueueForceLegacy()) {
            sIsProcessAllowedToUseConcurrent = false;
        }
    }

    @RavenwoodReplace
    private static void throwIfNotTest() {
        final ActivityThread activityThread = ActivityThread.currentActivityThread();
        if (activityThread == null) {
            // Only tests can reach here.
            return;
        }
        final Instrumentation instrumentation = activityThread.getInstrumentation();
        if (instrumentation == null) {
            // Only tests can reach here.
            return;
        }
        if (instrumentation.isInstrumenting()) {
            return;
        }
        throw new IllegalStateException("Test-only API called not from a test!");
    }

    private static void throwIfNotTest$ravenwood() {
        return;
    }

    private static boolean isInstrumenting() {
        final ActivityThread activityThread = ActivityThread.currentActivityThread();
        if (activityThread == null) {
            return false;
        }
        final Instrumentation instrumentation = activityThread.getInstrumentation();
        return instrumentation != null && instrumentation.isInstrumenting();
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

    private static final class MatchDeliverableMessages extends MessageCompare {
        @Override
        public boolean compareMessage(MessageNode n, Handler h, int what, Object object, Runnable r,
                long when) {
            return n.mMessage.when <= when;
        }
    }
    private final MatchDeliverableMessages mMatchDeliverableMessages =
            new MatchDeliverableMessages();
    /**
     * Returns true if the looper has no pending messages which are due to be processed.
     *
     * <p>This method is safe to call from any thread.
     *
     * @return True if the looper is idle.
     */
    public boolean isIdle() {
        if (mUseConcurrent) {
            final long now = SystemClock.uptimeMillis();

            if (stackHasMessages(null, 0, null, null, now, mMatchDeliverableMessages, false)) {
                return false;
            }

            MessageNode msgNode = null;
            MessageNode asyncMsgNode = null;

            if (!mPriorityQueue.isEmpty()) {
                try {
                    msgNode = mPriorityQueue.first();
                } catch (NoSuchElementException e) { }
            }

            if (!mAsyncPriorityQueue.isEmpty()) {
                try {
                    asyncMsgNode = mAsyncPriorityQueue.first();
                } catch (NoSuchElementException e) { }
            }

            if ((msgNode != null && msgNode.getWhen() <= now)
                    || (asyncMsgNode != null && asyncMsgNode.getWhen() <= now)) {
                return false;
            }

            return true;
        } else {
            synchronized (this) {
                final long now = SystemClock.uptimeMillis();
                return mMessages == null || now < mMessages.when;
            }
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
        if (mUseConcurrent) {
            synchronized (mIdleHandlersLock) {
                mIdleHandlers.add(handler);
            }
        } else {
            synchronized (this) {
                mIdleHandlers.add(handler);
            }
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
        if (mUseConcurrent) {
            synchronized (mIdleHandlersLock) {
                mIdleHandlers.remove(handler);
            }
        } else {
            synchronized (this) {
                mIdleHandlers.remove(handler);
            }
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
        if (mUseConcurrent) {
            // If the loop is quitting then it must not be idling.
            // We can assume mPtr != 0 when sQuitting is false.
            return !((boolean) sQuitting.getVolatile(this)) && nativeIsPolling(mPtr);
        } else {
            synchronized (this) {
                return isPollingLocked();
            }
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
    @RavenwoodThrow(blockedBy = android.os.ParcelFileDescriptor.class)
    public void addOnFileDescriptorEventListener(@NonNull FileDescriptor fd,
            @OnFileDescriptorEventListener.Events int events,
            @NonNull OnFileDescriptorEventListener listener) {
        if (fd == null) {
            throw new IllegalArgumentException("fd must not be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }

        if (mUseConcurrent) {
            synchronized (mFileDescriptorRecordsLock) {
                updateOnFileDescriptorEventListenerLocked(fd, events, listener);
            }
        } else {
            synchronized (this) {
                updateOnFileDescriptorEventListenerLocked(fd, events, listener);
            }
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
    @RavenwoodThrow(blockedBy = android.os.ParcelFileDescriptor.class)
    public void removeOnFileDescriptorEventListener(@NonNull FileDescriptor fd) {
        if (fd == null) {
            throw new IllegalArgumentException("fd must not be null");
        }
        if (mUseConcurrent) {
            synchronized (mFileDescriptorRecordsLock) {
                updateOnFileDescriptorEventListenerLocked(fd, 0, null);
            }
        } else {
            synchronized (this) {
                updateOnFileDescriptorEventListenerLocked(fd, 0, null);
            }
        }
    }

    @RavenwoodThrow(blockedBy = android.os.ParcelFileDescriptor.class)
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
        if (mUseConcurrent) {
            synchronized (mFileDescriptorRecordsLock) {
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
        } else {
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
            if (mUseConcurrent) {
                synchronized (mFileDescriptorRecordsLock) {
                    int index = mFileDescriptorRecords.indexOfKey(fd);
                    if (index >= 0 && mFileDescriptorRecords.valueAt(index) == record
                            && record.mSeq == seq) {
                        record.mEvents = newWatchedEvents;
                        if (newWatchedEvents == 0) {
                            mFileDescriptorRecords.removeAt(index);
                        }
                    }
                }
            } else {
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
        }

        // Return the new set of events to watch for native code to take care of.
        return newWatchedEvents;
    }

    private static final AtomicLong mMessagesDelivered = new AtomicLong();

    /* This is only read/written from the Looper thread. For use with Concurrent MQ */
    private int mNextPollTimeoutMillis;
    private boolean mMessageDirectlyQueued;
    private Message nextMessage(boolean peek) {
        int i = 0;

        while (true) {
            if (DEBUG) {
                Log.d(TAG_C, "nextMessage loop #" + i);
                i++;
            }

            mDrainingLock.lock();
            mNextIsDrainingStack = true;
            mDrainingLock.unlock();

            /*
             * Set our state to active, drain any items from the stack into our priority queues
             */
            StackNode oldTop;
            oldTop = swapAndSetStackStateActive();
            drainStack(oldTop);

            mDrainingLock.lock();
            mNextIsDrainingStack = false;
            mDrainCompleted.signalAll();
            mDrainingLock.unlock();

            /*
             * The objective of this next block of code is to:
             *  - find a message to return (if any is ready)
             *  - find a next message we would like to return, after scheduling.
             *     - we make our scheduling decision based on this next message (if it exists).
             *
             * We have two queues to juggle and the presence of barriers throws an additional
             * wrench into our plans.
             *
             * The last wrinkle is that remove() may delete items from underneath us. If we hit
             * that case, we simply restart the loop.
             */

            /* Get the first node from each queue */
            Iterator<MessageNode> queueIter = mPriorityQueue.iterator();
            MessageNode msgNode = iterateNext(queueIter);
            Iterator<MessageNode> asyncQueueIter = mAsyncPriorityQueue.iterator();
            MessageNode asyncMsgNode = iterateNext(asyncQueueIter);

            if (DEBUG) {
                if (msgNode != null) {
                    Message msg = msgNode.mMessage;
                    Log.d(TAG_C, "Next found node what: " + msg.what + " when: " + msg.when
                            + " seq: " + msgNode.mInsertSeq + "barrier: "
                            + msgNode.isBarrier() + " now: " + SystemClock.uptimeMillis());
                }
                if (asyncMsgNode != null) {
                    Message msg = asyncMsgNode.mMessage;
                    Log.d(TAG_C, "Next found async node what: " + msg.what + " when: " + msg.when
                            + " seq: " + asyncMsgNode.mInsertSeq + "barrier: "
                            + asyncMsgNode.isBarrier() + " now: "
                            + SystemClock.uptimeMillis());
                }
            }

            /*
             * the node which we will return, null if none are ready
             */
            MessageNode found = null;
            /*
             * The node from which we will determine our next wakeup time.
             * Null indicates there is no next message ready. If we found a node,
             * we can leave this null as Looper will call us again after delivering
             * the message.
             */
            MessageNode next = null;

            long now = SystemClock.uptimeMillis();
            /*
             * If we have a barrier we should return the async node (if it exists and is ready)
             */
            if (msgNode != null && msgNode.isBarrier()) {
                if (asyncMsgNode != null && now >= asyncMsgNode.getWhen()) {
                    found = asyncMsgNode;
                } else {
                    next = asyncMsgNode;
                }
            } else { /* No barrier. */
                MessageNode earliest;
                /*
                 * If we have two messages, pick the earliest option from either queue.
                 * Otherwise grab whichever node is non-null. If both are null we'll fall through.
                 */
                earliest = pickEarliestNode(msgNode, asyncMsgNode);

                if (earliest != null) {
                    if (now >= earliest.getWhen()) {
                        found = earliest;
                    } else {
                        next = earliest;
                    }
                }
            }

            if (DEBUG) {
                if (found != null) {
                    Message msg = found.mMessage;
                    Log.d(TAG_C, " Will deliver node what: " + msg.what + " when: " + msg.when
                            + " seq: " + found.mInsertSeq + " barrier: " + found.isBarrier()
                            + " async: " + found.isAsync() + " now: "
                            + SystemClock.uptimeMillis());
                } else {
                    Log.d(TAG_C, "No node to deliver");
                }
                if (next != null) {
                    Message msg = next.mMessage;
                    Log.d(TAG_C, "Next node what: " + msg.what + " when: " + msg.when + " seq: "
                            + next.mInsertSeq + " barrier: " + next.isBarrier() + " async: "
                            + next.isAsync()
                            + " now: " + SystemClock.uptimeMillis());
                } else {
                    Log.d(TAG_C, "No next node");
                }
            }

            /*
             * If we have a found message, we will get called again so there's no need to set state.
             * In that case we can leave our state as ACTIVE.
             *
             * Otherwise we should determine how to park the thread.
             */
            StateNode nextOp = sStackStateActive;
            if (found == null) {
                if (next == null) {
                    /* No message to deliver, sleep indefinitely */
                    mNextPollTimeoutMillis = -1;
                    nextOp = sStackStateParked;
                    if (DEBUG) {
                        Log.d(TAG_C, "nextMessage next state is StackStateParked");
                    }
                } else {
                    /* Message not ready, or we found one to deliver already, set a timeout */
                    long nextMessageWhen = next.getWhen();
                    if (nextMessageWhen > now) {
                        mNextPollTimeoutMillis = (int) Math.min(nextMessageWhen - now,
                                Integer.MAX_VALUE);
                    } else {
                        mNextPollTimeoutMillis = 0;
                    }

                    mStackStateTimedPark.mWhenToWake = now + mNextPollTimeoutMillis;
                    nextOp = mStackStateTimedPark;
                    if (DEBUG) {
                        Log.d(TAG_C, "nextMessage next state is StackStateTimedParked timeout ms "
                                + mNextPollTimeoutMillis + " mWhenToWake: "
                                + mStackStateTimedPark.mWhenToWake + " now " + now);
                    }
                }
            }

            /*
             * Try to swap our state from Active back to Park or TimedPark. If we raced with
             * enqueue, loop back around to pick up any new items.
             */
            if (sState.compareAndSet(this, sStackStateActive, nextOp)) {
                mMessageCounts.clearCounts();
                if (found != null) {
                    if (!peek && !removeFromPriorityQueue(found)) {
                        /*
                         * RemoveMessages() might be able to pull messages out from under us
                         * However we can detect that here and just loop around if it happens.
                         */
                        continue;
                    }

                    if (TRACE) {
                        Trace.setCounter("MQ.Delivered", mMessagesDelivered.incrementAndGet());
                    }
                    return found.mMessage;
                }
                return null;
            }
        }
    }

    private Message nextConcurrent() {
        final long ptr = mPtr;
        if (ptr == 0) {
            return null;
        }

        mNextPollTimeoutMillis = 0;
        int pendingIdleHandlerCount = -1; // -1 only during first iteration
        while (true) {
            if (mNextPollTimeoutMillis != 0) {
                Binder.flushPendingCommands();
            }

            mMessageDirectlyQueued = false;
            nativePollOnce(ptr, mNextPollTimeoutMillis);

            Message msg = nextMessage(false);
            if (msg != null) {
                msg.markInUse();
                return msg;
            }

            if ((boolean) sQuitting.getVolatile(this)) {
                return null;
            }

            synchronized (mIdleHandlersLock) {
                // If first time idle, then get the number of idlers to run.
                // Idle handles only run if the queue is empty or if the first message
                // in the queue (possibly a barrier) is due to be handled in the future.
                if (pendingIdleHandlerCount < 0
                        && isIdle()) {
                    pendingIdleHandlerCount = mIdleHandlers.size();
                }
                if (pendingIdleHandlerCount <= 0) {
                    // No idle handlers to run.  Loop and wait some more.
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
                    Log.wtf(TAG_C, "IdleHandler threw exception", t);
                }

                if (!keep) {
                    synchronized (mIdleHandlersLock) {
                        mIdleHandlers.remove(idler);
                    }
                }
            }

            // Reset the idle handler count to 0 so we do not run them again.
            pendingIdleHandlerCount = 0;

            // While calling an idle handler, a new message could have been delivered
            // so go back and look again for a pending message without waiting.
            mNextPollTimeoutMillis = 0;
        }
    }

    @UnsupportedAppUsage
    Message next() {
        if (mUseConcurrent) {
            return nextConcurrent();
        }

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
                Message msg = mMessages;
                if (msg != null && msg.target == null) {
                    // Stalled by a barrier.  Find the next asynchronous message in the queue.
                    do {
                        prevMsg = msg;
                        msg = msg.next;
                    } while (msg != null && !msg.isAsynchronous());
                }
                if (msg != null) {
                    if (now < msg.when) {
                        // Next message is not ready.  Set a timeout to wake up when it is ready.
                        nextPollTimeoutMillis = (int) Math.min(msg.when - now, Integer.MAX_VALUE);
                    } else {
                        // Got a message.
                        mBlocked = false;
                        if (prevMsg != null) {
                            prevMsg.next = msg.next;
                            if (prevMsg.next == null) {
                                mLast = prevMsg;
                            }
                        } else {
                            mMessages = msg.next;
                            if (msg.next == null) {
                                mLast = null;
                            }
                        }
                        msg.next = null;
                        if (DEBUG) Log.v(TAG_L, "Returning message: " + msg);
                        msg.markInUse();
                        if (msg.isAsynchronous()) {
                            mAsyncMessageCount--;
                        }
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
                if (pendingIdleHandlerCount < 0
                        && (mMessages == null || now < mMessages.when)) {
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
                    Log.wtf(TAG_L, "IdleHandler threw exception", t);
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

        if (mUseConcurrent) {
            synchronized (mIdleHandlersLock) {
                if (sQuitting.compareAndSet(this, false, true)) {
                    if (safe) {
                        removeAllFutureMessages();
                    } else {
                        removeAllMessages();
                    }

                    // We can assume mPtr != 0 because sQuitting was previously false.
                    nativeWake(mPtr);
                }
            }
        } else {
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
        if (mUseConcurrent) {
            final int token = mNextBarrierTokenAtomic.getAndIncrement();

            // b/376573804: apps and tests may expect to be able to use reflection
            // to read this value. Make some effort to support this legacy use case.
            mNextBarrierToken = token + 1;

            final Message msg = Message.obtain();

            msg.markInUse();
            msg.arg1 = token;

            if (!enqueueMessageUnchecked(msg, when)) {
                Log.wtf(TAG_C, "Unexpected error while adding sync barrier!");
                return -1;
            }

            return token;
        }

        synchronized (this) {
            final int token = mNextBarrierToken++;
            final Message msg = Message.obtain();
            msg.markInUse();
            msg.when = when;
            msg.arg1 = token;

            if (Flags.messageQueueTailTracking() && mLast != null && mLast.when <= when) {
                /* Message goes to tail of list */
                mLast.next = msg;
                mLast = msg;
                msg.next = null;
                return token;
            }

            Message prev = null;
            Message p = mMessages;
            if (when != 0) {
                while (p != null && p.when <= when) {
                    prev = p;
                    p = p.next;
                }
            }

            if (p == null) {
                /* We reached the tail of the list, or list is empty. */
                mLast = msg;
            }

            if (prev != null) { // invariant: p == prev.next
                msg.next = p;
                prev.next = msg;
            } else {
                msg.next = p;
                mMessages = msg;
            }
            return token;
        }
    }

    private static final class MatchBarrierToken extends MessageCompare {
        int mBarrierToken;

        MatchBarrierToken(int token) {
            super();
            mBarrierToken = token;
        }

        @Override
        public boolean compareMessage(MessageNode n, Handler h, int what, Object object, Runnable r,
                long when) {
            final Message m = n.mMessage;
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
        // Remove a sync barrier token from the queue.
        // If the queue is no longer stalled by a barrier then wake it.
        if (mUseConcurrent) {
            boolean removed;
            MessageNode first;
            final MatchBarrierToken matchBarrierToken = new MatchBarrierToken(token);

            try {
                /* Retain the first element to see if we are currently stuck on a barrier. */
                first = mPriorityQueue.first();
            } catch (NoSuchElementException e) {
                /* The queue is empty */
                first = null;
            }

            removed = findOrRemoveMessages(null, 0, null, null, 0, matchBarrierToken, true);
            if (removed && first != null) {
                Message m = first.mMessage;
                if (m.target == null && m.arg1 == token) {
                    /* Wake up next() in case it was sleeping on this barrier. */
                    nativeWake(mPtr);
                }
            } else if (!removed) {
                throw new IllegalStateException("The specified message queue synchronization "
                        + " barrier token has not been posted or has already been removed.");
            }
            return;
        }

        synchronized (this) {
            Message prev = null;
            Message p = mMessages;
            while (p != null && (p.target != null || p.arg1 != token)) {
                prev = p;
                p = p.next;
            }
            if (p == null) {
                throw new IllegalStateException("The specified message queue synchronization "
                        + " barrier token has not been posted or has already been removed.");
            }
            final boolean needWake;
            if (prev != null) {
                prev.next = p.next;
                if (prev.next == null) {
                    mLast = prev;
                }
                needWake = false;
            } else {
                mMessages = p.next;
                if (mMessages == null) {
                    mLast = null;
                }
                needWake = mMessages == null || mMessages.target != null;
            }
            p.recycleUnchecked();

            // If the loop is quitting then it is already awake.
            // We can assume mPtr != 0 when mQuitting is false.
            if (needWake && !mQuitting) {
                nativeWake(mPtr);
            }
        }
    }

    boolean enqueueMessage(Message msg, long when) {
        if (msg.target == null) {
            throw new IllegalArgumentException("Message must have a target.");
        }

        if (mUseConcurrent) {
            if (msg.isInUse()) {
                throw new IllegalStateException(msg + " This message is already in use.");
            }

            return enqueueMessageUnchecked(msg, when);
        }

        synchronized (this) {
            if (msg.isInUse()) {
                throw new IllegalStateException(msg + " This message is already in use.");
            }

            if (mQuitting) {
                IllegalStateException e = new IllegalStateException(
                        msg.target + " sending message to a Handler on a dead thread");
                Log.w(TAG_L, e.getMessage(), e);
                msg.recycle();
                return false;
            }

            msg.markInUse();
            msg.when = when;
            Message p = mMessages;
            boolean needWake;
            if (p == null || when == 0 || when < p.when) {
                // New head, wake up the event queue if blocked.
                msg.next = p;
                mMessages = msg;
                needWake = mBlocked;
                if (p == null) {
                    mLast = mMessages;
                }
            } else {
                // Message is to be inserted at tail or middle of queue. Usually we don't have to
                // wake up the event queue unless there is a barrier at the head of the queue and
                // the message is the earliest asynchronous message in the queue.
                needWake = mBlocked && p.target == null && msg.isAsynchronous();

                // For readability, we split this portion of the function into two blocks based on
                // whether tail tracking is enabled. This has a minor implication for the case
                // where tail tracking is disabled. See the comment below.
                if (Flags.messageQueueTailTracking()) {
                    if (when >= mLast.when) {
                        needWake = needWake && mAsyncMessageCount == 0;
                        msg.next = null;
                        mLast.next = msg;
                        mLast = msg;
                    } else {
                        // Inserted within the middle of the queue.
                        Message prev;
                        for (;;) {
                            prev = p;
                            p = p.next;
                            if (p == null || when < p.when) {
                                break;
                            }
                            if (needWake && p.isAsynchronous()) {
                                needWake = false;
                            }
                        }
                        if (p == null) {
                            /* Inserting at tail of queue */
                            mLast = msg;
                        }
                        msg.next = p; // invariant: p == prev.next
                        prev.next = msg;
                    }
                } else {
                    Message prev;
                    for (;;) {
                        prev = p;
                        p = p.next;
                        if (p == null || when < p.when) {
                            break;
                        }
                        if (needWake && p.isAsynchronous()) {
                            needWake = false;
                        }
                    }
                    msg.next = p; // invariant: p == prev.next
                    prev.next = msg;

                    /*
                     * If this block is executing then we have a build without tail tracking -
                     * specifically: Flags.messageQueueTailTracking() == false. This is determined
                     * at build time so the flag won't change on us during runtime.
                     *
                     * Since we don't want to pepper the code with extra checks, we only check
                     * for tail tracking when we might use mLast. Otherwise, we continue to update
                     * mLast as the tail of the list.
                     *
                     * In this case however we are not maintaining mLast correctly. Since we never
                     * use it, this is fine. However, we run the risk of leaking a reference.
                     * So set mLast to null in this case to avoid any Message leaks. The other
                     * sites will never use the value so we are safe against null pointer derefs.
                     */
                    mLast = null;
                }
            }

            if (msg.isAsynchronous()) {
                mAsyncMessageCount++;
            }

            // We can assume mPtr != 0 because mQuitting is false.
            if (needWake) {
                nativeWake(mPtr);
            }
        }
        return true;
    }

    private Message legacyPeekOrPoll(boolean peek) {
        synchronized (this) {
            // Try to retrieve the next message.  Return if found.
            final long now = SystemClock.uptimeMillis();
            Message prevMsg = null;
            Message msg = mMessages;
            if (msg != null && msg.target == null) {
                // Stalled by a barrier.  Find the next asynchronous message in the queue.
                do {
                    prevMsg = msg;
                    msg = msg.next;
                } while (msg != null && !msg.isAsynchronous());
            }
            if (msg != null) {
                if (now >= msg.when) {
                    // Got a message.
                    mBlocked = false;
                    if (peek) {
                        return msg;
                    }
                    if (prevMsg != null) {
                        prevMsg.next = msg.next;
                        if (prevMsg.next == null) {
                            mLast = prevMsg;
                        }
                    } else {
                        mMessages = msg.next;
                        if (msg.next == null) {
                            mLast = null;
                        }
                    }
                    msg.next = null;
                    msg.markInUse();
                    if (msg.isAsynchronous()) {
                        mAsyncMessageCount--;
                    }
                    if (TRACE) {
                        Trace.setCounter("MQ.Delivered", mMessagesDelivered.incrementAndGet());
                    }
                    return msg;
                }
            }
        }
        return null;
    }

    /**
     * Get the timestamp of the next executable message in our priority queue.
     * Returns null if there are no messages ready for delivery.
     *
     * Caller must ensure that this doesn't race 'next' from the Looper thread.
     */
    @SuppressLint("VisiblySynchronized") // Legacy MessageQueue synchronizes on this
    Long peekWhenForTest() {
        throwIfNotTest();
        Message ret;
        if (mUseConcurrent) {
            ret = nextMessage(true);
        } else {
            ret = legacyPeekOrPoll(true);
        }
        return ret != null ? ret.when : null;
    }

    /**
     * Return the next executable message in our priority queue.
     * Returns null if there are no messages ready for delivery
     *
     * Caller must ensure that this doesn't race 'next' from the Looper thread.
     */
    @SuppressLint("VisiblySynchronized") // Legacy MessageQueue synchronizes on this
    @Nullable
    Message pollForTest() {
        throwIfNotTest();
        if (mUseConcurrent) {
            return nextMessage(false);
        } else {
            return legacyPeekOrPoll(false);
        }
    }

    /**
     * @return true if we are blocked on a sync barrier
     */
    boolean isBlockedOnSyncBarrier() {
        throwIfNotTest();
        if (mUseConcurrent) {
            Iterator<MessageNode> queueIter = mPriorityQueue.iterator();
            MessageNode queueNode = iterateNext(queueIter);

            if (queueNode.isBarrier()) {
                long now = SystemClock.uptimeMillis();

                /* Look for a deliverable async node. If one exists we are not blocked. */
                Iterator<MessageNode> asyncQueueIter = mAsyncPriorityQueue.iterator();
                MessageNode asyncNode = iterateNext(asyncQueueIter);
                if (asyncNode != null && now >= asyncNode.getWhen()) {
                    return false;
                }
                /*
                 * Look for a deliverable sync node. In this case, if one exists we are blocked
                 * since the barrier prevents delivery of the Message.
                 */
                while (queueNode.isBarrier()) {
                    queueNode = iterateNext(queueIter);
                }
                if (queueNode != null && now >= queueNode.getWhen()) {
                    return true;
                }

                return false;
            }
        } else {
            Message msg = mMessages;
            if (msg != null && msg.target == null) {
                Message iter = msg;
                /* Look for a deliverable async node */
                do {
                    iter = iter.next;
                } while (iter != null && !iter.isAsynchronous());

                long now = SystemClock.uptimeMillis();
                if (iter != null && now >= iter.when) {
                    return false;
                }
                /*
                 * Look for a deliverable sync node. In this case, if one exists we are blocked
                 * since the barrier prevents delivery of the Message.
                 */
                iter = msg;
                do {
                    iter = iter.next;
                } while (iter != null && (iter.target == null || iter.isAsynchronous()));

                if (iter != null && now >= iter.when) {
                    return true;
                }
                return false;
            }
        }
        /* No barrier was found. */
        return false;
    }

    private static final class MatchHandlerWhatAndObject extends MessageCompare {
        @Override
        public boolean compareMessage(MessageNode n, Handler h, int what, Object object, Runnable r,
                long when) {
            final Message m = n.mMessage;
            if (m.target == h && m.what == what && (object == null || m.obj == object)) {
                return true;
            }
            return false;
        }
    }
    private final MatchHandlerWhatAndObject mMatchHandlerWhatAndObject =
            new MatchHandlerWhatAndObject();
    boolean hasMessages(Handler h, int what, Object object) {
        if (h == null) {
            return false;
        }
        if (mUseConcurrent) {
            return findOrRemoveMessages(h, what, object, null, 0, mMatchHandlerWhatAndObject,
                    false);
        }
        synchronized (this) {
            Message p = mMessages;
            while (p != null) {
                if (p.target == h && p.what == what && (object == null || p.obj == object)) {
                    return true;
                }
                p = p.next;
            }
            return false;
        }
    }

    private static final class MatchHandlerWhatAndObjectEquals extends MessageCompare {
        @Override
        public boolean compareMessage(MessageNode n, Handler h, int what, Object object, Runnable r,
                long when) {
            final Message m = n.mMessage;
            if (m.target == h && m.what == what && (object == null || object.equals(m.obj))) {
                return true;
            }
            return false;
        }
    }
    private final MatchHandlerWhatAndObjectEquals mMatchHandlerWhatAndObjectEquals =
            new MatchHandlerWhatAndObjectEquals();
    boolean hasEqualMessages(Handler h, int what, Object object) {
        if (h == null) {
            return false;
        }
        if (mUseConcurrent) {
            return findOrRemoveMessages(h, what, object, null, 0, mMatchHandlerWhatAndObjectEquals,
                    false);

        }
        synchronized (this) {
            Message p = mMessages;
            while (p != null) {
                if (p.target == h && p.what == what && (object == null || object.equals(p.obj))) {
                    return true;
                }
                p = p.next;
            }
            return false;
        }
    }

    private static final class MatchHandlerRunnableAndObject extends MessageCompare {
        @Override
        public boolean compareMessage(MessageNode n, Handler h, int what, Object object, Runnable r,
                long when) {
            final Message m = n.mMessage;
            if (m.target == h && m.callback == r && (object == null || m.obj == object)) {
                return true;
            }
            return false;
        }
    }
    private final MatchHandlerRunnableAndObject mMatchHandlerRunnableAndObject =
            new MatchHandlerRunnableAndObject();
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    boolean hasMessages(Handler h, Runnable r, Object object) {
        if (h == null) {
            return false;
        }
        if (mUseConcurrent) {
            return findOrRemoveMessages(h, -1, object, r, 0, mMatchHandlerRunnableAndObject,
                    false);
        }

        synchronized (this) {
            Message p = mMessages;
            while (p != null) {
                if (p.target == h && p.callback == r && (object == null || p.obj == object)) {
                    return true;
                }
                p = p.next;
            }
            return false;
        }
    }

    private static final class MatchHandler extends MessageCompare {
        @Override
        public boolean compareMessage(MessageNode n, Handler h, int what, Object object, Runnable r,
                long when) {
            return n.mMessage.target == h;
        }
    }
    private final MatchHandler mMatchHandler = new MatchHandler();
    boolean hasMessages(Handler h) {
        if (h == null) {
            return false;
        }
        if (mUseConcurrent) {
            return findOrRemoveMessages(h, -1, null, null, 0, mMatchHandler, false);
        }
        synchronized (this) {
            Message p = mMessages;
            while (p != null) {
                if (p.target == h) {
                    return true;
                }
                p = p.next;
            }
            return false;
        }
    }

    void removeMessages(Handler h, int what, Object object) {
        if (h == null) {
            return;
        }
        if (mUseConcurrent) {
            findOrRemoveMessages(h, what, object, null, 0, mMatchHandlerWhatAndObject, true);
            return;
        }
        synchronized (this) {
            Message p = mMessages;

            // Remove all messages at front.
            while (p != null && p.target == h && p.what == what
                   && (object == null || p.obj == object)) {
                Message n = p.next;
                mMessages = n;
                if (p.isAsynchronous()) {
                    mAsyncMessageCount--;
                }
                p.recycleUnchecked();
                p = n;
            }

            if (p == null) {
                mLast = mMessages;
            }

            // Remove all messages after front.
            while (p != null) {
                Message n = p.next;
                if (n != null) {
                    if (n.target == h && n.what == what
                            && (object == null || n.obj == object)) {
                        Message nn = n.next;
                        if (n.isAsynchronous()) {
                            mAsyncMessageCount--;
                        }
                        n.recycleUnchecked();
                        p.next = nn;
                        if (p.next == null) {
                            mLast = p;
                        }
                        continue;
                    }
                }
                p = n;
            }
        }
    }

    void removeEqualMessages(Handler h, int what, Object object) {
        if (h == null) {
            return;
        }

        if (mUseConcurrent) {
            findOrRemoveMessages(h, what, object, null, 0, mMatchHandlerWhatAndObjectEquals, true);
            return;
        }

        synchronized (this) {
            Message p = mMessages;

            // Remove all messages at front.
            while (p != null && p.target == h && p.what == what
                   && (object == null || object.equals(p.obj))) {
                Message n = p.next;
                mMessages = n;
                if (p.isAsynchronous()) {
                    mAsyncMessageCount--;
                }
                p.recycleUnchecked();
                p = n;
            }

            if (p == null) {
                mLast = mMessages;
            }

            // Remove all messages after front.
            while (p != null) {
                Message n = p.next;
                if (n != null) {
                    if (n.target == h && n.what == what
                            && (object == null || object.equals(n.obj))) {
                        Message nn = n.next;
                        if (n.isAsynchronous()) {
                            mAsyncMessageCount--;
                        }
                        n.recycleUnchecked();
                        p.next = nn;
                        if (p.next == null) {
                            mLast = p;
                        }
                        continue;
                    }
                }
                p = n;
            }
        }
    }

    void removeMessages(Handler h, Runnable r, Object object) {
        if (h == null || r == null) {
            return;
        }

        if (mUseConcurrent) {
            findOrRemoveMessages(h, -1, object, r, 0, mMatchHandlerRunnableAndObject, true);
            return;
        }
        synchronized (this) {
            Message p = mMessages;

            // Remove all messages at front.
            while (p != null && p.target == h && p.callback == r
                   && (object == null || p.obj == object)) {
                Message n = p.next;
                mMessages = n;
                if (p.isAsynchronous()) {
                    mAsyncMessageCount--;
                }
                p.recycleUnchecked();
                p = n;
            }

            if (p == null) {
                mLast = mMessages;
            }

            // Remove all messages after front.
            while (p != null) {
                Message n = p.next;
                if (n != null) {
                    if (n.target == h && n.callback == r
                            && (object == null || n.obj == object)) {
                        Message nn = n.next;
                        if (n.isAsynchronous()) {
                            mAsyncMessageCount--;
                        }
                        n.recycleUnchecked();
                        p.next = nn;
                        if (p.next == null) {
                            mLast = p;
                        }
                        continue;
                    }
                }
                p = n;
            }
        }
    }

    private static final class MatchHandlerRunnableAndObjectEquals extends MessageCompare {
        @Override
        public boolean compareMessage(MessageNode n, Handler h, int what, Object object, Runnable r,
                long when) {
            final Message m = n.mMessage;
            if (m.target == h && m.callback == r && (object == null || object.equals(m.obj))) {
                return true;
            }
            return false;
        }
    }
    private final MatchHandlerRunnableAndObjectEquals mMatchHandlerRunnableAndObjectEquals =
            new MatchHandlerRunnableAndObjectEquals();
    void removeEqualMessages(Handler h, Runnable r, Object object) {
        if (h == null || r == null) {
            return;
        }

        if (mUseConcurrent) {
            findOrRemoveMessages(h, -1, object, r, 0, mMatchHandlerRunnableAndObjectEquals, true);
            return;
        }
        synchronized (this) {
            Message p = mMessages;

            // Remove all messages at front.
            while (p != null && p.target == h && p.callback == r
                   && (object == null || object.equals(p.obj))) {
                Message n = p.next;
                mMessages = n;
                if (p.isAsynchronous()) {
                    mAsyncMessageCount--;
                }
                p.recycleUnchecked();
                p = n;
            }

            if (p == null) {
                mLast = mMessages;
            }

            // Remove all messages after front.
            while (p != null) {
                Message n = p.next;
                if (n != null) {
                    if (n.target == h && n.callback == r
                            && (object == null || object.equals(n.obj))) {
                        Message nn = n.next;
                        if (n.isAsynchronous()) {
                            mAsyncMessageCount--;
                        }
                        n.recycleUnchecked();
                        p.next = nn;
                        if (p.next == null) {
                            mLast = p;
                        }
                        continue;
                    }
                }
                p = n;
            }
        }
    }

    private static final class MatchHandlerAndObject extends MessageCompare {
        @Override
        public boolean compareMessage(MessageNode n, Handler h, int what, Object object, Runnable r,
                long when) {
            final Message m = n.mMessage;
            if (m.target == h && (object == null || m.obj == object)) {
                return true;
            }
            return false;
        }
    }
    private final MatchHandlerAndObject mMatchHandlerAndObject = new MatchHandlerAndObject();
    void removeCallbacksAndMessages(Handler h, Object object) {
        if (h == null) {
            return;
        }

        if (mUseConcurrent) {
            findOrRemoveMessages(h, -1, object, null, 0, mMatchHandlerAndObject, true);
            return;
        }
        synchronized (this) {
            Message p = mMessages;

            // Remove all messages at front.
            while (p != null && p.target == h
                    && (object == null || p.obj == object)) {
                Message n = p.next;
                mMessages = n;
                if (p.isAsynchronous()) {
                    mAsyncMessageCount--;
                }
                p.recycleUnchecked();
                p = n;
            }

            if (p == null) {
                mLast = mMessages;
            }

            // Remove all messages after front.
            while (p != null) {
                Message n = p.next;
                if (n != null) {
                    if (n.target == h && (object == null || n.obj == object)) {
                        Message nn = n.next;
                        if (n.isAsynchronous()) {
                            mAsyncMessageCount--;
                        }
                        n.recycleUnchecked();
                        p.next = nn;
                        if (p.next == null) {
                            mLast = p;
                        }
                        continue;
                    }
                }
                p = n;
            }
        }
    }

    private static final class MatchHandlerAndObjectEquals extends MessageCompare {
        @Override
        public boolean compareMessage(MessageNode n, Handler h, int what, Object object, Runnable r,
                long when) {
            final Message m = n.mMessage;
            if (m.target == h && (object == null || object.equals(m.obj))) {
                return true;
            }
            return false;
        }
    }
    private final MatchHandlerAndObjectEquals mMatchHandlerAndObjectEquals =
            new MatchHandlerAndObjectEquals();
    void removeCallbacksAndEqualMessages(Handler h, Object object) {
        if (h == null) {
            return;
        }

        if (mUseConcurrent) {
            findOrRemoveMessages(h, -1, object, null, 0, mMatchHandlerAndObjectEquals, true);
            return;
        }
        synchronized (this) {
            Message p = mMessages;

            // Remove all messages at front.
            while (p != null && p.target == h
                    && (object == null || object.equals(p.obj))) {
                Message n = p.next;
                mMessages = n;
                if (p.isAsynchronous()) {
                    mAsyncMessageCount--;
                }
                p.recycleUnchecked();
                p = n;
            }

            if (p == null) {
                mLast = mMessages;
            }

            // Remove all messages after front.
            while (p != null) {
                Message n = p.next;
                if (n != null) {
                    if (n.target == h && (object == null || object.equals(n.obj))) {
                        Message nn = n.next;
                        if (n.isAsynchronous()) {
                            mAsyncMessageCount--;
                        }
                        n.recycleUnchecked();
                        p.next = nn;
                        if (p.next == null) {
                            mLast = p;
                        }
                        continue;
                    }
                }
                p = n;
            }
        }
    }

    private void removeAllMessagesLocked() {
        Message p = mMessages;
        while (p != null) {
            Message n = p.next;
            p.recycleUnchecked();
            p = n;
        }
        mMessages = null;
        mLast = null;
        mAsyncMessageCount = 0;
    }

    private void removeAllFutureMessagesLocked() {
        final long now = SystemClock.uptimeMillis();
        Message p = mMessages;
        if (p != null) {
            if (p.when > now) {
                removeAllMessagesLocked();
            } else {
                Message n;
                for (;;) {
                    n = p.next;
                    if (n == null) {
                        return;
                    }
                    if (n.when > now) {
                        break;
                    }
                    p = n;
                }
                p.next = null;
                mLast = p;

                do {
                    p = n;
                    n = p.next;
                    if (p.isAsynchronous()) {
                        mAsyncMessageCount--;
                    }
                    p.recycleUnchecked();
                } while (n != null);
            }
        }
    }

    private static final class MatchAllMessages extends MessageCompare {
        @Override
        public boolean compareMessage(MessageNode n, Handler h, int what, Object object, Runnable r,
                long when) {
            return true;
        }
    }
    private final MatchAllMessages mMatchAllMessages = new MatchAllMessages();
    private void removeAllMessages() {
        findOrRemoveMessages(null, -1, null, null, 0, mMatchAllMessages, true);
    }

    private static final class MatchAllFutureMessages extends MessageCompare {
        @Override
        public boolean compareMessage(MessageNode n, Handler h, int what, Object object, Runnable r,
                long when) {
            final Message m = n.mMessage;
                    if (m.when > when) {
                return true;
            }
            return false;
        }
    }
    private final MatchAllFutureMessages mMatchAllFutureMessages = new MatchAllFutureMessages();
    private void removeAllFutureMessages() {
        findOrRemoveMessages(null, -1, null, null, SystemClock.uptimeMillis(),
                mMatchAllFutureMessages, true);
    }

    @NeverCompile
    private void printPriorityQueueNodes() {
        Iterator<MessageNode> iterator = mPriorityQueue.iterator();

        Log.d(TAG_C, "* Dump priority queue");
        while (iterator.hasNext()) {
            MessageNode msgNode = iterator.next();
            Log.d(TAG_C, "** MessageNode what: " + msgNode.mMessage.what + " when "
                    + msgNode.mMessage.when + " seq: " + msgNode.mInsertSeq);
        }
    }

    @NeverCompile
    private int dumpPriorityQueue(ConcurrentSkipListSet<MessageNode> queue, Printer pw,
            String prefix, Handler h, int n) {
        int count = 0;
        long now = SystemClock.uptimeMillis();

        for (MessageNode msgNode : queue) {
            Message msg = msgNode.mMessage;
            if (h == null || h == msg.target) {
                pw.println(prefix + "Message " + (n + count) + ": " + msg.toString(now));
            }
            count++;
        }
        return count;
    }

    @NeverCompile
    void dump(Printer pw, String prefix, Handler h) {
        if (mUseConcurrent) {
            long now = SystemClock.uptimeMillis();
            int n = 0;

            pw.println(prefix + "(MessageQueue is using Concurrent implementation)");

            StackNode node = (StackNode) sState.getVolatile(this);
            while (node != null) {
                if (node.isMessageNode()) {
                    Message msg = ((MessageNode) node).mMessage;
                    if (h == null || h == msg.target) {
                        pw.println(prefix + "Message " + n + ": " + msg.toString(now));
                    }
                    node = ((MessageNode) node).mNext;
                } else {
                    pw.println(prefix + "State: " + node);
                    node = null;
                }
                n++;
            }

            pw.println(prefix + "PriorityQueue Messages: ");
            n += dumpPriorityQueue(mPriorityQueue, pw, prefix, h, n);
            pw.println(prefix + "AsyncPriorityQueue Messages: ");
            n += dumpPriorityQueue(mAsyncPriorityQueue, pw, prefix, h, n);

            pw.println(prefix + "(Total messages: " + n + ", polling=" + isPolling()
                    + ", quitting=" + (boolean) sQuitting.getVolatile(this) + ")");
            return;
        }

        synchronized (this) {
            pw.println(prefix + "(MessageQueue is using Legacy implementation)");
            long now = SystemClock.uptimeMillis();
            int n = 0;
            for (Message msg = mMessages; msg != null; msg = msg.next) {
                if (h == null || h == msg.target) {
                    pw.println(prefix + "Message " + n + ": " + msg.toString(now));
                }
                n++;
            }
            pw.println(prefix + "(Total messages: " + n + ", polling=" + isPollingLocked()
                    + ", quitting=" + mQuitting + ")");
        }
    }

    @NeverCompile
    private int dumpPriorityQueue(ConcurrentSkipListSet<MessageNode> queue,
            ProtoOutputStream proto) {
        int count = 0;

        for (MessageNode msgNode : queue) {
            Message msg = msgNode.mMessage;
            msg.dumpDebug(proto, MessageQueueProto.MESSAGES);
            count++;
        }
        return count;
    }

    @NeverCompile
    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        if (mUseConcurrent) {
            final long messageQueueToken = proto.start(fieldId);

            StackNode node = (StackNode) sState.getVolatile(this);
            while (node.isMessageNode()) {
                Message msg = ((MessageNode) node).mMessage;
                msg.dumpDebug(proto, MessageQueueProto.MESSAGES);
                node = ((MessageNode) node).mNext;
            }

            dumpPriorityQueue(mPriorityQueue, proto);
            dumpPriorityQueue(mAsyncPriorityQueue, proto);

            proto.write(MessageQueueProto.IS_POLLING_LOCKED, isPolling());
            proto.write(MessageQueueProto.IS_QUITTING, (boolean) sQuitting.getVolatile(this));
            proto.end(messageQueueToken);
            return;
        }

        final long messageQueueToken = proto.start(fieldId);
        synchronized (this) {
            for (Message msg = mMessages; msg != null; msg = msg.next) {
                msg.dumpDebug(proto, MessageQueueProto.MESSAGES);
            }
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

    /**
     * ConcurrentMessageQueue specific classes methods and variables
     */
    /* Helper to choose the correct queue to insert into. */
    private void insertIntoPriorityQueue(MessageNode msgNode) {
        if (msgNode.isAsync()) {
            mAsyncPriorityQueue.add(msgNode);
        } else {
            mPriorityQueue.add(msgNode);
        }
    }

    private boolean removeFromPriorityQueue(MessageNode msgNode) {
        if (msgNode.isAsync()) {
            return mAsyncPriorityQueue.remove(msgNode);
        } else {
            return mPriorityQueue.remove(msgNode);
        }
    }

    private MessageNode pickEarliestNode(MessageNode nodeA, MessageNode nodeB) {
        if (nodeA != null && nodeB != null) {
            if (nodeA.compareTo(nodeB) < 0) {
                return nodeA;
            }
            return nodeB;
        }

        return nodeA != null ? nodeA : nodeB;
    }

    private MessageNode iterateNext(Iterator<MessageNode> iter) {
        if (iter.hasNext()) {
            try {
                return iter.next();
            } catch (NoSuchElementException e) {
                /* The queue is empty - this can happen if we race with remove */
            }
        }
        return null;
    }

    /* Move any non-cancelled messages into the priority queue */
    private void drainStack(StackNode oldTop) {
        while (oldTop.isMessageNode()) {
            MessageNode oldTopMessageNode = (MessageNode) oldTop;
            if (oldTopMessageNode.removeFromStack()) {
                insertIntoPriorityQueue(oldTopMessageNode);
            }
            MessageNode inserted = oldTopMessageNode;
            oldTop = oldTopMessageNode.mNext;
            /*
             * removeMessages can walk this list while we are consuming it.
             * Set our next pointer to null *after* we add the message to our
             * priority queue. This way removeMessages() will always find the
             * message, either in our list or in the priority queue.
             */
            inserted.mNext = null;
        }
    }

    /* Set the stack state to Active, return a list of nodes to walk. */
    private StackNode swapAndSetStackStateActive() {
        while (true) {
            /* Set stack state to Active, get node list to walk later */
            StackNode current = (StackNode) sState.getVolatile(this);
            if (current == sStackStateActive
                    || sState.compareAndSet(this, current, sStackStateActive)) {
                return current;
            }
        }
    }
    private StateNode getStateNode(StackNode node) {
        if (node.isMessageNode()) {
            return ((MessageNode) node).mBottomOfStack;
        }
        return (StateNode) node;
    }

    private void waitForDrainCompleted() {
        mDrainingLock.lock();
        while (mNextIsDrainingStack) {
            mDrainCompleted.awaitUninterruptibly();
        }
        mDrainingLock.unlock();
    }

    @IntDef(value = {
        STACK_NODE_MESSAGE,
        STACK_NODE_ACTIVE,
        STACK_NODE_PARKED,
        STACK_NODE_TIMEDPARK})
    @Retention(RetentionPolicy.SOURCE)
    private @interface StackNodeType {}

    /*
     * Stack node types. STACK_NODE_MESSAGE indicates a node containing a message.
     * The other types indicate what state our Looper thread is in. The bottom of
     * the stack is always a single state node. Message nodes are added on top.
     */
    private static final int STACK_NODE_MESSAGE = 0;
    /*
     * Active state indicates that next() is processing messages
     */
    private static final int STACK_NODE_ACTIVE = 1;
    /*
     * Parked state indicates that the Looper thread is sleeping indefinitely (nothing to deliver)
     */
    private static final int STACK_NODE_PARKED = 2;
    /*
     * Timed Park state indicates that the Looper thread is sleeping, waiting for a message
     * deadline
     */
    private static final int STACK_NODE_TIMEDPARK = 3;

    /* Describes a node in the Treiber stack */
    static class StackNode {
        @StackNodeType
        private final int mType;

        StackNode(@StackNodeType int type) {
            mType = type;
        }

        @StackNodeType
        final int getNodeType() {
            return mType;
        }

        final boolean isMessageNode() {
            return mType == STACK_NODE_MESSAGE;
        }
    }

    static final class MessageNode extends StackNode implements Comparable<MessageNode> {
        private final Message mMessage;
        volatile StackNode mNext;
        StateNode mBottomOfStack;
        boolean mWokeUp;
        final long mInsertSeq;
        private static final VarHandle sRemovedFromStack;
        private volatile boolean mRemovedFromStackValue;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                sRemovedFromStack = l.findVarHandle(MessageQueue.MessageNode.class,
                        "mRemovedFromStackValue", boolean.class);
            } catch (Exception e) {
                Log.wtf(TAG_C, "VarHandle lookup failed with exception: " + e);
                throw new ExceptionInInitializerError(e);
            }
        }

        MessageNode(@NonNull Message message, long insertSeq) {
            super(STACK_NODE_MESSAGE);
            mMessage = message;
            mInsertSeq = insertSeq;
        }

        long getWhen() {
            return mMessage.when;
        }

        boolean isRemovedFromStack() {
            return mRemovedFromStackValue;
        }

        boolean removeFromStack() {
            return sRemovedFromStack.compareAndSet(this, false, true);
        }

        boolean isAsync() {
            return mMessage.isAsynchronous();
        }

        boolean isBarrier() {
            return mMessage.target == null;
        }

        @Override
        public int compareTo(@NonNull MessageNode messageNode) {
            Message other = messageNode.mMessage;

            int compared = Long.compare(mMessage.when, other.when);
            if (compared == 0) {
                compared = Long.compare(mInsertSeq, messageNode.mInsertSeq);
            }
            return compared;
        }
    }

    static class StateNode extends StackNode {
        StateNode(int type) {
            super(type);
        }
    }

    static final class TimedParkStateNode extends StateNode {
        long mWhenToWake;

        TimedParkStateNode() {
            super(STACK_NODE_TIMEDPARK);
        }
    }

    private static final StateNode sStackStateActive = new StateNode(STACK_NODE_ACTIVE);
    private static final StateNode sStackStateParked = new StateNode(STACK_NODE_PARKED);
    private final TimedParkStateNode mStackStateTimedPark = new TimedParkStateNode();

    /* This is the top of our treiber stack. */
    private static final VarHandle sState;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            sState = l.findVarHandle(MessageQueue.class, "mStateValue",
                    MessageQueue.StackNode.class);
        } catch (Exception e) {
            Log.wtf(TAG_C, "VarHandle lookup failed with exception: " + e);
            throw new ExceptionInInitializerError(e);
        }
    }

    private volatile StackNode mStateValue = sStackStateParked;
    private final ConcurrentSkipListSet<MessageNode> mPriorityQueue =
            new ConcurrentSkipListSet<MessageNode>();
    private final ConcurrentSkipListSet<MessageNode> mAsyncPriorityQueue =
            new ConcurrentSkipListSet<MessageNode>();

    /*
     * This helps us ensure that messages with the same timestamp are inserted in FIFO order.
     * Increments on each insert, starting at 0. MessageNode.compareTo() will compare sequences
     * when delivery timestamps are identical.
     */
    private static final VarHandle sNextInsertSeq;
    private volatile long mNextInsertSeqValue = 0;
    /*
     * The exception to the FIFO order rule is sendMessageAtFrontOfQueue().
     * Those messages must be in LIFO order.
     * Decrements on each front of queue insert.
     */
    private static final VarHandle sNextFrontInsertSeq;
    private volatile long mNextFrontInsertSeqValue = -1;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            sNextInsertSeq = l.findVarHandle(MessageQueue.class, "mNextInsertSeqValue",
                    long.class);
            sNextFrontInsertSeq = l.findVarHandle(MessageQueue.class, "mNextFrontInsertSeqValue",
                    long.class);
        } catch (Exception e) {
            Log.wtf(TAG_C, "VarHandle lookup failed with exception: " + e);
            throw new ExceptionInInitializerError(e);
        }

    }

    /*
     * Tracks the number of queued and cancelled messages in our stack.
     *
     * On item cancellation, determine whether to wake next() to flush tombstoned messages.
     * We track queued and cancelled counts as two ints packed into a single long.
     */
    private static final class MessageCounts {
        private static VarHandle sCounts;
        private volatile long mCountsValue = 0;
        static {
            try {
                MethodHandles.Lookup l = MethodHandles.lookup();
                sCounts = l.findVarHandle(MessageQueue.MessageCounts.class, "mCountsValue",
                        long.class);
            } catch (Exception e) {
                Log.wtf(TAG_C, "VarHandle lookup failed with exception: " + e);
                throw new ExceptionInInitializerError(e);
            }
        }

        /* We use a special value to indicate when next() has been woken for flush. */
        private static final long AWAKE = Long.MAX_VALUE;
        /*
         * Minimum number of messages in the stack which we need before we consider flushing
         * tombstoned items.
         */
        private static final int MESSAGE_FLUSH_THRESHOLD = 10;

        private static int numQueued(long val) {
            return (int) (val >>> Integer.SIZE);
        }

        private static int numCancelled(long val) {
            return (int) val;
        }

        private static long combineCounts(int queued, int cancelled) {
            return ((long) queued << Integer.SIZE) | (long) cancelled;
        }

        public void incrementQueued() {
            while (true) {
                long oldVal = mCountsValue;
                int queued = numQueued(oldVal);
                int cancelled = numCancelled(oldVal);
                /* Use Math.max() to avoid overflow of queued count */
                long newVal = combineCounts(Math.max(queued + 1, queued), cancelled);

                /* Don't overwrite 'AWAKE' state */
                if (oldVal == AWAKE || sCounts.compareAndSet(this, oldVal, newVal)) {
                    break;
                }
            }
        }

        public boolean incrementCancelled() {
            while (true) {
                long oldVal = mCountsValue;
                if (oldVal == AWAKE) {
                    return false;
                }
                int queued = numQueued(oldVal);
                int cancelled = numCancelled(oldVal);
                boolean needsPurge = queued > MESSAGE_FLUSH_THRESHOLD
                        && (queued >> 1) < cancelled;
                long newVal;
                if (needsPurge) {
                    newVal = AWAKE;
                } else {
                    newVal = combineCounts(queued,
                            Math.max(cancelled + 1, cancelled));
                }

                if (sCounts.compareAndSet(this, oldVal, newVal)) {
                    return needsPurge;
                }
            }
        }

        public void clearCounts() {
            mCountsValue = 0;
        }
    }

    private final MessageCounts mMessageCounts = new MessageCounts();

    private final Object mIdleHandlersLock = new Object();
    private final Object mFileDescriptorRecordsLock = new Object();

    private static final VarHandle sQuitting;
    private boolean mQuittingValue = false;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            sQuitting = l.findVarHandle(MessageQueue.class, "mQuittingValue", boolean.class);
        } catch (Exception e) {
            Log.wtf(TAG_C, "VarHandle lookup failed with exception: " + e);
            throw new ExceptionInInitializerError(e);
        }
    }

    // The next barrier token.
    // Barriers are indicated by messages with a null target whose arg1 field carries the token.
    private final AtomicInteger mNextBarrierTokenAtomic = new AtomicInteger(1);

    // Must retain this for compatibility reasons.
    @UnsupportedAppUsage
    private int mNextBarrierToken;

    /* Protects mNextIsDrainingStack */
    private final ReentrantLock mDrainingLock = new ReentrantLock();
    private boolean mNextIsDrainingStack = false;
    private final Condition mDrainCompleted = mDrainingLock.newCondition();

    private boolean enqueueMessageUnchecked(@NonNull Message msg, long when) {
        if ((boolean) sQuitting.getVolatile(this)) {
            IllegalStateException e = new IllegalStateException(
                    msg.target + " sending message to a Handler on a dead thread");
            Log.w(TAG_C, e.getMessage(), e);
            msg.recycleUnchecked();
            return false;
        }

        long seq = when != 0 ? ((long) sNextInsertSeq.getAndAdd(this, 1L) + 1L)
                : ((long) sNextFrontInsertSeq.getAndAdd(this, -1L) - 1L);
        /* TODO: Add a MessageNode member to Message so we can avoid this allocation */
        MessageNode node = new MessageNode(msg, seq);
        msg.when = when;
        msg.markInUse();

        if (DEBUG) {
            Log.d(TAG_C, "Insert message what: " + msg.what + " when: " + msg.when + " seq: "
                    + node.mInsertSeq + " barrier: " + node.isBarrier() + " async: "
                    + node.isAsync() + " now: " + SystemClock.uptimeMillis());
        }

        final Looper myLooper = Looper.myLooper();
        /* If we are running on the looper thread we can add directly to the priority queue */
        if (myLooper != null && myLooper.getQueue() == this) {
            node.removeFromStack();
            insertIntoPriorityQueue(node);
            /*
             * We still need to do this even though we are the current thread,
             * otherwise next() may sleep indefinitely.
             */
            if (!mMessageDirectlyQueued) {
                mMessageDirectlyQueued = true;
                nativeWake(mPtr);
            }
            return true;
        }

        while (true) {
            StackNode old = (StackNode) sState.getVolatile(this);
            boolean wakeNeeded;
            boolean inactive;

            node.mNext = old;
            switch (old.getNodeType()) {
                case STACK_NODE_ACTIVE:
                    /*
                     * The worker thread is currently active and will process any elements added to
                     * the stack before parking again.
                     */
                    node.mBottomOfStack = (StateNode) old;
                    inactive = false;
                    node.mWokeUp = true;
                    wakeNeeded = false;
                    break;

                case STACK_NODE_PARKED:
                    node.mBottomOfStack = (StateNode) old;
                    inactive = true;
                    node.mWokeUp = true;
                    wakeNeeded = true;
                    break;

                case STACK_NODE_TIMEDPARK:
                    node.mBottomOfStack = (StateNode) old;
                    inactive = true;
                    wakeNeeded = mStackStateTimedPark.mWhenToWake >= node.getWhen();
                    node.mWokeUp = wakeNeeded;
                    break;

                default:
                    MessageNode oldMessage = (MessageNode) old;

                    node.mBottomOfStack = oldMessage.mBottomOfStack;
                    int bottomType = node.mBottomOfStack.getNodeType();
                    inactive = bottomType >= STACK_NODE_PARKED;
                    wakeNeeded = (bottomType == STACK_NODE_TIMEDPARK
                            && mStackStateTimedPark.mWhenToWake >= node.getWhen()
                            && !oldMessage.mWokeUp);
                    node.mWokeUp = oldMessage.mWokeUp || wakeNeeded;
                    break;
            }
            if (sState.compareAndSet(this, old, node)) {
                if (inactive) {
                    if (wakeNeeded) {
                        nativeWake(mPtr);
                    } else {
                        mMessageCounts.incrementQueued();
                    }
                }
                return true;
            }
        }
    }

    /*
     * This class is used to find matches for hasMessages() and removeMessages()
     */
    private abstract static class MessageCompare {
        public abstract boolean compareMessage(MessageNode n, Handler h, int what, Object object,
                Runnable r, long when);
    }

    private boolean stackHasMessages(Handler h, int what, Object object, Runnable r, long when,
            MessageCompare compare, boolean removeMatches) {
        boolean found = false;
        StackNode top = (StackNode) sState.getVolatile(this);
        StateNode bottom = getStateNode(top);

        /*
         * If the top node is a state node, there are no reachable messages.
         * If it's anything other than Active, we can quit as we know that next() is not
         * consuming items.
         * If the top node is Active then we know that next() is currently consuming items.
         * In that case we should wait next() has drained the stack.
         */
        if (top == bottom) {
            if (bottom != sStackStateActive) {
                return false;
            }
            waitForDrainCompleted();
            return false;
        }

        /*
         * We have messages that we may tombstone. Walk the stack until we hit the bottom or we
         * hit a null pointer.
         * If we hit the bottom, we are done.
         * If we hit a null pointer, then the stack is being consumed by next() and we must cycle
         * until the stack has been drained.
         */
        MessageNode p = (MessageNode) top;

        while (true) {
            if (compare.compareMessage(p, h, what, object, r, when)) {
                found = true;
                if (DEBUG) {
                    Log.d(TAG_C, "stackHasMessages node matches");
                }
                if (removeMatches) {
                    if (p.removeFromStack()) {
                        p.mMessage.recycleUnchecked();
                        if (mMessageCounts.incrementCancelled()) {
                            nativeWake(mPtr);
                        }
                    }
                } else {
                    return true;
                }
            }

            StackNode n = p.mNext;
            if (n == null) {
                /* Next() is walking the stack, we must re-sample */
                if (DEBUG) {
                    Log.d(TAG_C, "stackHasMessages next() is walking the stack, we must re-sample");
                }
                waitForDrainCompleted();
                break;
            }
            if (!n.isMessageNode()) {
                /* We reached the end of the stack */
                return found;
            }
            p = (MessageNode) n;
        }

        return found;
    }

    private boolean priorityQueueHasMessage(ConcurrentSkipListSet<MessageNode> queue, Handler h,
            int what, Object object, Runnable r, long when, MessageCompare compare,
            boolean removeMatches) {
        Iterator<MessageNode> iterator = queue.iterator();
        boolean found = false;

        while (iterator.hasNext()) {
            MessageNode msg = iterator.next();

            if (compare.compareMessage(msg, h, what, object, r, when)) {
                if (removeMatches) {
                    found = true;
                    if (queue.remove(msg)) {
                        msg.mMessage.recycleUnchecked();
                    }
                } else {
                    return true;
                }
            }
        }
        return found;
    }

    private boolean findOrRemoveMessages(Handler h, int what, Object object, Runnable r, long when,
            MessageCompare compare, boolean removeMatches) {
        boolean foundInStack, foundInQueue;

        foundInStack = stackHasMessages(h, what, object, r, when, compare, removeMatches);
        foundInQueue = priorityQueueHasMessage(mPriorityQueue, h, what, object, r, when, compare,
                removeMatches);
        foundInQueue |= priorityQueueHasMessage(mAsyncPriorityQueue, h, what, object, r, when,
                compare, removeMatches);

        return foundInStack || foundInQueue;
    }

}
