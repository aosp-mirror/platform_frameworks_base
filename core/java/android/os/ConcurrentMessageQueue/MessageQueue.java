/*
 * Copyright (C) 2024 The Android Open Source Project
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
import android.os.Handler;
import android.os.Trace;
import android.util.Log;
import android.util.Printer;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;

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
@android.ravenwood.annotation.RavenwoodKeepWholeClass
@android.ravenwood.annotation.RavenwoodNativeSubstitutionClass(
        "com.android.platform.test.ravenwood.nativesubstitution.MessageQueue_host")
public final class MessageQueue {
    private static final String TAG = "ConcurrentMessageQueue";
    private static final boolean DEBUG = false;
    private static final boolean TRACE = false;

    // True if the message queue can be quit.
    private final boolean mQuitAllowed;

    @SuppressWarnings("unused")
    private long mPtr; // used by native code

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
                Log.wtf(TAG, "VarHandle lookup failed with exception: " + e);
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
            Log.wtf(TAG, "VarHandle lookup failed with exception: " + e);
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
     * Those messages must be in LIFO order - SIGH.
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
            Log.wtf(TAG, "VarHandle lookup failed with exception: " + e);
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
                Log.wtf(TAG, "VarHandle lookup failed with exception: " + e);
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
    @GuardedBy("mIdleHandlersLock")
    private final ArrayList<IdleHandler> mIdleHandlers = new ArrayList<IdleHandler>();
    private IdleHandler[] mPendingIdleHandlers;

    private final Object mFileDescriptorRecordsLock = new Object();
    @GuardedBy("mFileDescriptorRecordsLock")
    private SparseArray<FileDescriptorRecord> mFileDescriptorRecords;

    private static final VarHandle sQuitting;
    private boolean mQuittingValue = false;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            sQuitting = l.findVarHandle(MessageQueue.class, "mQuittingValue", boolean.class);
        } catch (Exception e) {
            Log.wtf(TAG, "VarHandle lookup failed with exception: " + e);
            throw new ExceptionInInitializerError(e);
        }
    }

    // The next barrier token.
    // Barriers are indicated by messages with a null target whose arg1 field carries the token.
    private final AtomicInteger mNextBarrierToken = new AtomicInteger(1);

    private static native long nativeInit();
    private static native void nativeDestroy(long ptr);
    private native void nativePollOnce(long ptr, int timeoutMillis); /*non-static for callbacks*/
    private static native void nativeWake(long ptr);
    private static native boolean nativeIsPolling(long ptr);
    private static native void nativeSetFileDescriptorEvents(long ptr, int fd, int events);

    MessageQueue(boolean quitAllowed) {
        mQuitAllowed = quitAllowed;
        mPtr = nativeInit();
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

        final long now = SystemClock.uptimeMillis();
        if ((msgNode != null && msgNode.getWhen() <= now)
                || (asyncMsgNode != null && asyncMsgNode.getWhen() <= now)) {
            return false;
        }

        return true;
    }

    /* Protects mNextIsDrainingStack */
    private final ReentrantLock mDrainingLock = new ReentrantLock();
    private boolean mNextIsDrainingStack = false;
    private final Condition mDrainCompleted = mDrainingLock.newCondition();

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
        synchronized (mIdleHandlersLock) {
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
        synchronized (mIdleHandlersLock) {
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
        // If the loop is quitting then it must not be idling.
        // We can assume mPtr != 0 when sQuitting is false.
        return !((boolean) sQuitting.getVolatile(this)) && nativeIsPolling(mPtr);
    }

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

    /* This is only read/written from the Looper thread */
    private int mNextPollTimeoutMillis;
    private static final AtomicLong mMessagesDelivered = new AtomicLong();

    private Message nextMessage() {
        int i = 0;

        while (true) {
            if (DEBUG) {
                Log.d(TAG, "nextMessage loop #" + i);
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
                    Log.d(TAG, "Next found node what: " + msg.what + " when: " + msg.when
                            + " seq: " + msgNode.mInsertSeq + "barrier: "
                            + msgNode.isBarrier() + " now: " + SystemClock.uptimeMillis());
                }
                if (asyncMsgNode != null) {
                    Message msg = asyncMsgNode.mMessage;
                    Log.d(TAG, "Next found async node what: " + msg.what + " when: " + msg.when
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
                    Log.d(TAG, "Will deliver node what: " + msg.what + " when: " + msg.when
                            + " seq: " + found.mInsertSeq + " barrier: " + found.isBarrier()
                            + " async: " + found.isAsync() + " now: "
                            + SystemClock.uptimeMillis());
                } else {
                    Log.d(TAG, "No node to deliver");
                }
                if (next != null) {
                    Message msg = next.mMessage;
                    Log.d(TAG, "Next node what: " + msg.what + " when: " + msg.when + " seq: "
                            + next.mInsertSeq + " barrier: " + next.isBarrier() + " async: "
                            + next.isAsync()
                            + " now: " + SystemClock.uptimeMillis());
                } else {
                    Log.d(TAG, "No next node");
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
                        Log.d(TAG, "nextMessage next state is StackStateParked");
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
                        Log.d(TAG, "nextMessage next state is StackStateTimedParked timeout ms "
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
                    if (!removeFromPriorityQueue(found)) {
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

    Message next() {
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

            nativePollOnce(ptr, mNextPollTimeoutMillis);

            Message msg = nextMessage();
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
                        && mNextPollTimeoutMillis != 0) {
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
                    Log.wtf(TAG, "IdleHandler threw exception", t);
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

    void quit(boolean safe) {
        if (!mQuitAllowed) {
            throw new IllegalStateException("Main thread not allowed to quit.");
        }
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
    }

    boolean enqueueMessage(@NonNull Message msg, long when) {
        if (msg.target == null) {
            throw new IllegalArgumentException("Message must have a target.");
        }

        if (msg.isInUse()) {
            throw new IllegalStateException(msg + " This message is already in use.");
        }

        return enqueueMessageUnchecked(msg, when);
    }

    private boolean enqueueMessageUnchecked(@NonNull Message msg, long when) {
        if ((boolean) sQuitting.getVolatile(this)) {
            IllegalStateException e = new IllegalStateException(
                    msg.target + " sending message to a Handler on a dead thread");
            Log.w(TAG, e.getMessage(), e);
            msg.recycleUnchecked();
            return false;
        }

        long seq = when != 0 ? ((long)sNextInsertSeq.getAndAdd(this, 1L) + 1L)
                : ((long)sNextFrontInsertSeq.getAndAdd(this, -1L) - 1L);
        /* TODO: Add a MessageNode member to Message so we can avoid this allocation */
        MessageNode node = new MessageNode(msg, seq);
        msg.when = when;
        msg.markInUse();

        if (DEBUG) {
            Log.d(TAG, "Insert message what: " + msg.what + " when: " + msg.when + " seq: "
                    + node.mInsertSeq + " barrier: " + node.isBarrier() + " async: "
                    + node.isAsync() + " now: " + SystemClock.uptimeMillis());
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
    @TestApi
    public int postSyncBarrier() {
        return postSyncBarrier(SystemClock.uptimeMillis());
    }

    private int postSyncBarrier(long when) {
        final int token = mNextBarrierToken.getAndIncrement();
        final Message msg = Message.obtain();

        msg.markInUse();
        msg.arg1 = token;

        if (!enqueueMessageUnchecked(msg, when)) {
            Log.wtf(TAG, "Unexpected error while adding sync barrier!");
            return -1;
        }

        return token;
    }

    private class MatchBarrierToken extends MessageCompare {
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
    @TestApi
    public void removeSyncBarrier(int token) {
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

    /*
     * This class is used to find matches for hasMessages() and removeMessages()
     */
    private abstract static class MessageCompare {
        public abstract boolean compareMessage(Message m, Handler h, int what, Object object,
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
            if (compare.compareMessage(p.mMessage, h, what, object, r, when)) {
                found = true;
                if (DEBUG) {
                    Log.w(TAG, "stackHasMessages node matches");
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
                    Log.d(TAG, "stackHasMessages next() is walking the stack, we must re-sample");
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

            if (compare.compareMessage(msg.mMessage, h, what, object, r, when)) {
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

    private static class MatchHandlerWhatAndObject extends MessageCompare {
        @Override
        public boolean compareMessage(Message m, Handler h, int what, Object object, Runnable r,
                long when) {
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

        return findOrRemoveMessages(h, what, object, null, 0, mMatchHandlerWhatAndObject, false);
    }

    private static class MatchHandlerWhatAndObjectEquals extends MessageCompare {
        @Override
        public boolean compareMessage(Message m, Handler h, int what, Object object, Runnable r,
                long when) {
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

        return findOrRemoveMessages(h, what, object, null, 0, mMatchHandlerWhatAndObjectEquals,
                false);
    }

    private static class MatchHandlerRunnableAndObject extends MessageCompare {
        @Override
        public boolean compareMessage(Message m, Handler h, int what, Object object, Runnable r,
                long when) {
            if (m.target == h && m.callback == r && (object == null || m.obj == object)) {
                return true;
            }
            return false;
        }
    }
    private final MatchHandlerRunnableAndObject mMatchHandlerRunnableAndObject =
            new MatchHandlerRunnableAndObject();

    boolean hasMessages(Handler h, Runnable r, Object object) {
        if (h == null) {
            return false;
        }

        return findOrRemoveMessages(h, -1, object, r, 0, mMatchHandlerRunnableAndObject, false);
    }

    private static class MatchHandler extends MessageCompare {
        @Override
        public boolean compareMessage(Message m, Handler h, int what, Object object, Runnable r,
                long when) {
            if (m.target == h) {
                return true;
            }
            return false;
        }
    }
    private final MatchHandler mMatchHandler = new MatchHandler();
    boolean hasMessages(Handler h) {
        if (h == null) {
            return false;
        }
        return findOrRemoveMessages(h, -1, null, null, 0, mMatchHandler, false);
    }

    void removeMessages(Handler h, int what, Object object) {
        if (h == null) {
            return;
        }
        findOrRemoveMessages(h, what, object, null, 0, mMatchHandlerWhatAndObject, true);
    }

    void removeEqualMessages(Handler h, int what, Object object) {
        if (h == null) {
            return;
        }
        findOrRemoveMessages(h, what, object, null, 0, mMatchHandlerWhatAndObjectEquals, true);
    }

    void removeMessages(Handler h, Runnable r, Object object) {
        if (h == null || r == null) {
            return;
        }
        findOrRemoveMessages(h, -1, object, r, 0, mMatchHandlerRunnableAndObject, true);
    }

    private static class MatchHandlerRunnableAndObjectEquals extends MessageCompare {
        @Override
        public boolean compareMessage(Message m, Handler h, int what, Object object, Runnable r,
                long when) {
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
        findOrRemoveMessages(h, -1, object, r, 0, mMatchHandlerRunnableAndObjectEquals, true);
    }

    private static class MatchHandlerAndObject extends MessageCompare {
        @Override
        public boolean compareMessage(Message m, Handler h, int what, Object object, Runnable r,
                long when) {
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
        findOrRemoveMessages(h, -1, object, null, 0, mMatchHandlerAndObject, true);
    }

    private static class MatchHandlerAndObjectEquals extends MessageCompare {
        @Override
        public boolean compareMessage(Message m, Handler h, int what, Object object, Runnable r,
                long when) {
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
        findOrRemoveMessages(h, -1, object, null, 0, mMatchHandlerAndObjectEquals, true);
    }

    private static class MatchAllMessages extends MessageCompare {
        @Override
        public boolean compareMessage(Message m, Handler h, int what, Object object, Runnable r,
                long when) {
            return true;
        }
    }
    private final MatchAllMessages mMatchAllMessages = new MatchAllMessages();
    private void removeAllMessages() {
        findOrRemoveMessages(null, -1, null, null, 0, mMatchAllMessages, true);
    }

    private static class MatchAllFutureMessages extends MessageCompare {
        @Override
        public boolean compareMessage(Message m, Handler h, int what, Object object, Runnable r,
                long when) {
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

    private void printPriorityQueueNodes() {
        Iterator<MessageNode> iterator = mPriorityQueue.iterator();

        Log.d(TAG, "* Dump priority queue");
        while (iterator.hasNext()) {
            MessageNode msgNode = iterator.next();
            Log.d(TAG, "** MessageNode what: " + msgNode.mMessage.what + " when "
                    + msgNode.mMessage.when + " seq: " + msgNode.mInsertSeq);
        }
    }

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

    void dump(Printer pw, String prefix, Handler h) {
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
    }

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

    void dumpDebug(ProtoOutputStream proto, long fieldId) {
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

        synchronized (mFileDescriptorRecordsLock) {
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

        synchronized (mFileDescriptorRecordsLock) {
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
    private int dispatchEvents(int fd, int events) {
        // Get the file descriptor record and any state that might change.
        final FileDescriptorRecord record;
        final int oldWatchedEvents;
        final OnFileDescriptorEventListener listener;
        final int seq;
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

        // Invoke the listener outside of the lock.
        int newWatchedEvents = listener.onFileDescriptorEvents(
                record.mDescriptor, events);
        if (newWatchedEvents != 0) {
            newWatchedEvents |= OnFileDescriptorEventListener.EVENT_ERROR;
        }

        // Update the file descriptor record if the listener changed the set of
        // events to watch and the listener itself hasn't been updated since.
        if (newWatchedEvents != oldWatchedEvents) {
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
        }

        // Return the new set of events to watch for native code to take care of.
        return newWatchedEvents;
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

    static final class FileDescriptorRecord {
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
