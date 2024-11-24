/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.util.ArrayMap;
import android.util.Slog;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Takes care of the grunt work of maintaining a list of remote interfaces,
 * typically for the use of performing callbacks from a
 * {@link android.app.Service} to its clients.  In particular, this:
 *
 * <ul>
 * <li> Keeps track of a set of registered {@link IInterface} objects,
 * taking care to identify them through their underlying unique {@link IBinder}
 * (by calling {@link IInterface#asBinder IInterface.asBinder()}.
 * <li> Attaches a {@link IBinder.DeathRecipient IBinder.DeathRecipient} to
 * each registered interface, so that it can be cleaned out of the list if its
 * process goes away.
 * <li> Performs locking of the underlying list of interfaces to deal with
 * multithreaded incoming calls, and a thread-safe way to iterate over a
 * snapshot of the list without holding its lock.
 * </ul>
 *
 * <p>To use this class, simply create a single instance along with your
 * service, and call its {@link #register} and {@link #unregister} methods
 * as client register and unregister with your service.  To call back on to
 * the registered clients, use {@link #beginBroadcast},
 * {@link #getBroadcastItem}, and {@link #finishBroadcast}.
 *
 * <p>If a registered interface's process goes away, this class will take
 * care of automatically removing it from the list.  If you want to do
 * additional work in this situation, you can create a subclass that
 * implements the {@link #onCallbackDied} method.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class RemoteCallbackList<E extends IInterface> {
    private static final String TAG = "RemoteCallbackList";

    private static final int DEFAULT_MAX_QUEUE_SIZE = 1000;


    /**
     * @hide
     */
    @IntDef(prefix = {"FROZEN_CALLEE_POLICY_"}, value = {
            FROZEN_CALLEE_POLICY_UNSET,
            FROZEN_CALLEE_POLICY_ENQUEUE_ALL,
            FROZEN_CALLEE_POLICY_ENQUEUE_MOST_RECENT,
            FROZEN_CALLEE_POLICY_DROP,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface FrozenCalleePolicy {
    }

    /**
     * Callbacks are invoked immediately regardless of the frozen state of the target process.
     *
     * Not recommended. Only exists for backward-compatibility. This represents the behavior up to
     * SDK 35. Starting with SDK 36, clients should set a policy to govern callback invocations when
     * recipients are frozen.
     */
    @FlaggedApi(Flags.FLAG_BINDER_FROZEN_STATE_CHANGE_CALLBACK)
    public static final int FROZEN_CALLEE_POLICY_UNSET = 0;

    /**
     * When the callback recipient's process is frozen, callbacks are enqueued so they're invoked
     * after the recipient is unfrozen.
     *
     * This is commonly used when the recipient wants to receive all callbacks without losing any
     * history, e.g. the recipient maintains a running count of events that occurred.
     *
     * Queued callbacks are invoked in the order they were originally broadcasted.
     */
    @FlaggedApi(Flags.FLAG_BINDER_FROZEN_STATE_CHANGE_CALLBACK)
    public static final int FROZEN_CALLEE_POLICY_ENQUEUE_ALL = 1;

    /**
     * When the callback recipient's process is frozen, only the most recent callback is enqueued,
     * which is later invoked after the recipient is unfrozen.
     *
     * This can be used when only the most recent state matters, for instance when clients are
     * listening to screen brightness changes.
     */
    @FlaggedApi(Flags.FLAG_BINDER_FROZEN_STATE_CHANGE_CALLBACK)
    public static final int FROZEN_CALLEE_POLICY_ENQUEUE_MOST_RECENT = 2;

    /**
     * When the callback recipient's process is frozen, callbacks are suppressed as if they never
     * happened.
     *
     * This could be useful in the case where the recipient wishes to react to callbacks only when
     * they occur while the recipient is not frozen. For example, certain network events are only
     * worth responding to if the response can be immediate. Another example is recipients having
     * another way of getting the latest state once it's unfrozen. Therefore there is no need to
     * save callbacks that happened while the recipient was frozen.
     */
    @FlaggedApi(Flags.FLAG_BINDER_FROZEN_STATE_CHANGE_CALLBACK)
    public static final int FROZEN_CALLEE_POLICY_DROP = 3;

    @UnsupportedAppUsage
    /*package*/ ArrayMap<IBinder, Interface> mInterfaces = new ArrayMap<IBinder, Interface>();
    private Object[] mActiveBroadcast;
    private int mBroadcastCount = -1;
    private boolean mKilled = false;
    private StringBuilder mRecentCallers;

    private final @FrozenCalleePolicy int mFrozenCalleePolicy;
    private final int mMaxQueueSize;
    private final Executor mExecutor;

    private final class Interface implements IBinder.DeathRecipient,
            IBinder.FrozenStateChangeCallback {
        final IBinder mBinder;
        final E mInterface;
        final Object mCookie;
        final Queue<Consumer<E>> mCallbackQueue;
        int mCurrentState = IBinder.FrozenStateChangeCallback.STATE_UNFROZEN;

        Interface(E callbackInterface, Object cookie) {
            mBinder = callbackInterface.asBinder();
            mInterface = callbackInterface;
            mCookie = cookie;
            mCallbackQueue = mFrozenCalleePolicy == FROZEN_CALLEE_POLICY_ENQUEUE_ALL
                || mFrozenCalleePolicy == FROZEN_CALLEE_POLICY_ENQUEUE_MOST_RECENT
                ? new ConcurrentLinkedQueue<>() : null;
        }

        @Override
        public synchronized void onFrozenStateChanged(@NonNull IBinder who, int state) {
            if (state == STATE_UNFROZEN && mCallbackQueue != null) {
                while (!mCallbackQueue.isEmpty()) {
                    Consumer<E> callback = mCallbackQueue.poll();
                    callback.accept(mInterface);
                }
            }
            mCurrentState = state;
        }

        void addCallback(@NonNull Consumer<E> callback) {
            if (mFrozenCalleePolicy == FROZEN_CALLEE_POLICY_UNSET) {
                callback.accept(mInterface);
                return;
            }
            synchronized (this) {
                if (mCurrentState == STATE_UNFROZEN) {
                    callback.accept(mInterface);
                    return;
                }
                switch (mFrozenCalleePolicy) {
                    case FROZEN_CALLEE_POLICY_ENQUEUE_ALL:
                        if (mCallbackQueue.size() >= mMaxQueueSize) {
                            mCallbackQueue.poll();
                        }
                        mCallbackQueue.offer(callback);
                        break;
                    case FROZEN_CALLEE_POLICY_ENQUEUE_MOST_RECENT:
                        mCallbackQueue.clear();
                        mCallbackQueue.offer(callback);
                        break;
                    case FROZEN_CALLEE_POLICY_DROP:
                        // Do nothing. Just ignore the callback.
                        break;
                    case FROZEN_CALLEE_POLICY_UNSET:
                        // Do nothing. Should have returned at the start of the method.
                        break;
                }
            }
        }

        void maybeSubscribeToFrozenCallback() throws RemoteException {
            if (mFrozenCalleePolicy != FROZEN_CALLEE_POLICY_UNSET) {
                try {
                    mBinder.addFrozenStateChangeCallback(mExecutor, this);
                } catch (UnsupportedOperationException e) {
                    // The kernel does not support frozen notifications. In this case we want to
                    // silently fall back to FROZEN_CALLEE_POLICY_UNSET. This is done by simply
                    // ignoring the error and moving on. mCurrentState would always be
                    // STATE_UNFROZEN and all callbacks are invoked immediately.
                }
            }
        }

        void maybeUnsubscribeFromFrozenCallback() {
            if (mFrozenCalleePolicy != FROZEN_CALLEE_POLICY_UNSET) {
                try {
                    mBinder.removeFrozenStateChangeCallback(this);
                } catch (UnsupportedOperationException | IllegalArgumentException e) {
                    // The kernel does not support frozen notifications. Ignore the error and move
                    // on.
                }
            }
        }

        public void binderDied() {
            synchronized (mInterfaces) {
                mInterfaces.remove(mBinder);
                maybeUnsubscribeFromFrozenCallback();
            }
            onCallbackDied(mInterface, mCookie);
        }
    }

    /**
     * Builder for {@link RemoteCallbackList}.
     *
     * @param <E> The remote callback interface type.
     */
    @FlaggedApi(Flags.FLAG_BINDER_FROZEN_STATE_CHANGE_CALLBACK)
    public static final class Builder<E extends IInterface> {
        private @FrozenCalleePolicy int mFrozenCalleePolicy;
        private int mMaxQueueSize = DEFAULT_MAX_QUEUE_SIZE;
        private InterfaceDiedCallback mInterfaceDiedCallback;
        private Executor mExecutor;

        /**
         * Creates a Builder for {@link RemoteCallbackList}.
         *
         * @param frozenCalleePolicy When the callback recipient's process is frozen, this parameter
         * specifies when/whether callbacks are invoked. It's important to choose a strategy that's
         * right for the use case. Leaving the policy unset with {@link #FROZEN_CALLEE_POLICY_UNSET}
         * is not recommended as it allows callbacks to be invoked while the recipient is frozen.
         */
        public Builder(@FrozenCalleePolicy int frozenCalleePolicy) {
            mFrozenCalleePolicy = frozenCalleePolicy;
        }

        /**
         * Sets the max queue size.
         *
         * @param maxQueueSize The max size limit on the queue that stores callbacks added when the
         * recipient's process is frozen. Once the limit is reached, the oldest callback is dropped
         * to keep the size under the limit. Should only be called for
         * {@link #FROZEN_CALLEE_POLICY_ENQUEUE_ALL}.
         *
         * @return This builder.
         * @throws IllegalArgumentException if the maxQueueSize is not positive.
         * @throws UnsupportedOperationException if frozenCalleePolicy is not
         * {@link #FROZEN_CALLEE_POLICY_ENQUEUE_ALL}.
         */
        public @NonNull Builder setMaxQueueSize(int maxQueueSize) {
            if (maxQueueSize <= 0) {
                throw new IllegalArgumentException("maxQueueSize must be positive");
            }
            if (mFrozenCalleePolicy != FROZEN_CALLEE_POLICY_ENQUEUE_ALL) {
                throw new UnsupportedOperationException(
                        "setMaxQueueSize can only be called for FROZEN_CALLEE_POLICY_ENQUEUE_ALL");
            }
            mMaxQueueSize = maxQueueSize;
            return this;
        }

        /**
         * Sets the callback to be invoked when an interface dies.
         */
        public @NonNull Builder setInterfaceDiedCallback(
                @NonNull InterfaceDiedCallback<E> callback) {
            mInterfaceDiedCallback = callback;
            return this;
        }

        /**
         * Sets the executor to be used when invoking callbacks asynchronously.
         *
         * This is only used when callbacks need to be invoked asynchronously, e.g. when the process
         * hosting a callback becomes unfrozen. Callbacks that can be invoked immediately run on the
         * same thread that calls {@link #broadcast} synchronously.
         */
        public @NonNull Builder setExecutor(@NonNull @CallbackExecutor Executor executor) {
            mExecutor = executor;
            return this;
        }

        /**
         * For notifying when the process hosting a callback interface has died.
         *
         * @param <E> The remote callback interface type.
         */
        @FlaggedApi(Flags.FLAG_BINDER_FROZEN_STATE_CHANGE_CALLBACK)
        public interface InterfaceDiedCallback<E extends IInterface> {
            /**
             * Invoked when a callback interface has died.
             *
             * @param remoteCallbackList the list that the interface was registered with.
             * @param deadInterface the interface that has died.
             * @param cookie the cookie specified on interface registration.
             */
            void onInterfaceDied(@NonNull RemoteCallbackList<E> remoteCallbackList,
                    E deadInterface, @Nullable Object cookie);
        }

        /**
         * Builds and returns a {@link RemoteCallbackList}.
         *
         * @return The built {@link RemoteCallbackList} object.
         */
        public @NonNull RemoteCallbackList<E> build() {
            Executor executor = mExecutor;
            if (executor == null && mFrozenCalleePolicy != FROZEN_CALLEE_POLICY_UNSET) {
                // TODO Throw an exception here once the existing API caller is updated to provide
                // an executor.
                executor = new HandlerExecutor(Handler.getMain());
            }
            if (mInterfaceDiedCallback != null) {
                return new RemoteCallbackList<E>(mFrozenCalleePolicy, mMaxQueueSize, executor) {
                    @Override
                    public void onCallbackDied(E deadInterface, Object cookie) {
                        mInterfaceDiedCallback.onInterfaceDied(this, deadInterface, cookie);
                    }
                };
            }
            return new RemoteCallbackList<E>(mFrozenCalleePolicy, mMaxQueueSize, executor);
        }
    }

    /**
     * Returns the frozen callee policy.
     *
     * @return The frozen callee policy.
     */
    @FlaggedApi(Flags.FLAG_BINDER_FROZEN_STATE_CHANGE_CALLBACK)
    public @FrozenCalleePolicy int getFrozenCalleePolicy() {
        return mFrozenCalleePolicy;
    }

    /**
     * Returns the max queue size.
     *
     * @return The max queue size.
     */
    @FlaggedApi(Flags.FLAG_BINDER_FROZEN_STATE_CHANGE_CALLBACK)
    public int getMaxQueueSize() {
        return mMaxQueueSize;
    }

    /**
     * Returns the executor used when invoking callbacks asynchronously.
     *
     * @return The executor.
     */
    @FlaggedApi(Flags.FLAG_BINDER_FROZEN_STATE_CHANGE_CALLBACK)
    public @Nullable Executor getExecutor() {
        return mExecutor;
    }

    /**
     * Creates a RemoteCallbackList with {@link #FROZEN_CALLEE_POLICY_UNSET}. This is equivalent to
     * <pre>
     * new RemoteCallbackList.Build(RemoteCallbackList.FROZEN_CALLEE_POLICY_UNSET).build()
     * </pre>
     */
    public RemoteCallbackList() {
        this(FROZEN_CALLEE_POLICY_UNSET, DEFAULT_MAX_QUEUE_SIZE, null);
    }

    /**
     * Creates a RemoteCallbackList with the specified frozen callee policy.
     *
     * @param frozenCalleePolicy When the callback recipient's process is frozen, this parameter
     * specifies when/whether callbacks are invoked. It's important to choose a strategy that's
     * right for the use case. Leaving the policy unset with {@link #FROZEN_CALLEE_POLICY_UNSET}
     * is not recommended as it allows callbacks to be invoked while the recipient is frozen.
     *
     * @param maxQueueSize The max size limit on the queue that stores callbacks added when the
     * recipient's process is frozen. Once the limit is reached, the oldest callbacks would be
     * dropped to keep the size under limit. Ignored except for
     * {@link #FROZEN_CALLEE_POLICY_ENQUEUE_ALL}.
     *
     * @param executor The executor used when invoking callbacks asynchronously.
     */
    private RemoteCallbackList(@FrozenCalleePolicy int frozenCalleePolicy, int maxQueueSize,
            @CallbackExecutor Executor executor) {
        mFrozenCalleePolicy = frozenCalleePolicy;
        mMaxQueueSize = maxQueueSize;
        mExecutor = executor;
    }

    /**
     * Simple version of {@link RemoteCallbackList#register(E, Object)}
     * that does not take a cookie object.
     */
    public boolean register(E callbackInterface) {
        return register(callbackInterface, null);
    }

    /**
     * Add a new interface to the list.  This interface will remain in the list
     * until a corresponding call to {@link #unregister} or its hosting process
     * goes away.  If the interface was already registered (determined by
     * checking to see if the {@link IInterface#asBinder callbackInterface.asBinder()}
     * object is already in the list), then it will be replaced with the new interface.
     * Registrations are not counted; a single call to {@link #unregister}
     * will remove an interface after any number calls to register it.
     *
     * @param callbackInterface The callback interface to be added to the list.  Must
     * not be null -- passing null here will cause a NullPointerException.
     * Most services will want to check for null before calling this with
     * an object given from a client, so that clients can't crash the
     * service with bad data.
     *
     * @param cookie Optional additional data to be associated with this
     * interface.
     *
     * @return Returns true if the interface was successfully added to the list.
     * Returns false if it was not added, either because {@link #kill} had
     * previously been called or the interface's process has gone away.
     *
     * @see #unregister
     * @see #kill
     * @see #onCallbackDied
     */
    public boolean register(E callbackInterface, Object cookie) {
        synchronized (mInterfaces) {
            if (mKilled) {
                return false;
            }
            // Flag unusual case that could be caused by a leak. b/36778087
            logExcessiveInterfaces();
            IBinder binder = callbackInterface.asBinder();
            try {
                Interface i = new Interface(callbackInterface, cookie);
                unregister(callbackInterface);
                binder.linkToDeath(i, 0);
                i.maybeSubscribeToFrozenCallback();
                mInterfaces.put(binder, i);
                return true;
            } catch (RemoteException e) {
                return false;
            }
        }
    }

    /**
     * Remove from the list an interface that was previously added with
     * {@link #register}.  This uses the
     * {@link IInterface#asBinder callbackInterface.asBinder()} object to correctly
     * find the previous registration.
     * Registrations are not counted; a single unregister call will remove
     * an interface after any number calls to {@link #register} for it.
     *
     * @param callbackInterface The interface to be removed from the list.  Passing
     * null here will cause a NullPointerException, so you will generally want
     * to check for null before calling.
     *
     * @return Returns true if the interface was found and unregistered.  Returns
     * false if the given interface was not found on the list.
     *
     * @see #register
     */
    public boolean unregister(E callbackInterface) {
        synchronized (mInterfaces) {
            Interface i = mInterfaces.remove(callbackInterface.asBinder());
            if (i != null) {
                i.mInterface.asBinder().unlinkToDeath(i, 0);
                i.maybeUnsubscribeFromFrozenCallback();
                return true;
            }
            return false;
        }
    }

    /**
     * Disable this interface list.  All registered interfaces are unregistered,
     * and the list is disabled so that future calls to {@link #register} will
     * fail.  This should be used when a Service is stopping, to prevent clients
     * from registering interfaces after it is stopped.
     *
     * @see #register
     */
    public void kill() {
        synchronized (mInterfaces) {
            for (int cbi = mInterfaces.size() - 1; cbi >= 0; cbi--) {
                Interface i = mInterfaces.valueAt(cbi);
                i.mInterface.asBinder().unlinkToDeath(i, 0);
                i.maybeUnsubscribeFromFrozenCallback();
            }
            mInterfaces.clear();
            mKilled = true;
        }
    }

    /**
     * Old version of {@link #onCallbackDied(E, Object)} that
     * does not provide a cookie.
     */
    public void onCallbackDied(E callbackInterface) {
    }
    
    /**
     * Called when the process hosting an interface in the list has gone away.
     * The default implementation calls {@link #onCallbackDied(E)}
     * for backwards compatibility.
     * 
     * @param callbackInterface The interface whose process has died.  Note that, since
     * its process has died, you can not make any calls on to this interface.
     * You can, however, retrieve its IBinder and compare it with another
     * IBinder to see if it is the same object.
     * @param cookie The cookie object original provided to
     * {@link #register(E, Object)}.
     * 
     * @see #register
     */
    public void onCallbackDied(E callbackInterface, Object cookie) {
        onCallbackDied(callbackInterface);
    }

    /**
     * Use {@link #broadcast(Consumer)} instead to ensure proper handling of frozen processes.
     *
     * Prepare to start making calls to the currently registered interfaces.
     * This creates a copy of the interface list, which you can retrieve items
     * from using {@link #getBroadcastItem}.  Note that only one broadcast can
     * be active at a time, so you must be sure to always call this from the
     * same thread (usually by scheduling with {@link Handler}) or
     * do your own synchronization.  You must call {@link #finishBroadcast}
     * when done.
     *
     * <p>A typical loop delivering a broadcast looks like this:
     *
     * <pre>
     * int i = interfaces.beginBroadcast();
     * while (i &gt; 0) {
     *     i--;
     *     try {
     *         interfaces.getBroadcastItem(i).somethingHappened();
     *     } catch (RemoteException e) {
     *         // The RemoteCallbackList will take care of removing
     *         // the dead object for us.
     *     }
     * }
     * interfaces.finishBroadcast();</pre>
     *
     * Note that this method is only supported for {@link #FROZEN_CALLEE_POLICY_UNSET}. For other
     * policies use {@link #broadcast(Consumer)} instead.
     *
     * @return Returns the number of interfaces in the broadcast, to be used
     * with {@link #getBroadcastItem} to determine the range of indices you
     * can supply.
     *
     * @throws UnsupportedOperationException if an frozen callee policy is set.
     *
     * @see #getBroadcastItem
     * @see #finishBroadcast
     */
    public int beginBroadcast() {
        if (mFrozenCalleePolicy != FROZEN_CALLEE_POLICY_UNSET) {
            throw new UnsupportedOperationException();
        }
        return beginBroadcastInternal();
    }

    private int beginBroadcastInternal() {
        synchronized (mInterfaces) {
            if (mBroadcastCount > 0) {
                throw new IllegalStateException(
                        "beginBroadcast() called while already in a broadcast");
            }
            
            final int n = mBroadcastCount = mInterfaces.size();
            if (n <= 0) {
                return 0;
            }
            Object[] active = mActiveBroadcast;
            if (active == null || active.length < n) {
                mActiveBroadcast = active = new Object[n];
            }
            for (int i = 0; i < n; i++) {
                active[i] = mInterfaces.valueAt(i);
            }
            return n;
        }
    }

    /**
     * Retrieve an item in the active broadcast that was previously started
     * with {@link #beginBroadcast}.  This can <em>only</em> be called after
     * the broadcast is started, and its data is no longer valid after
     * calling {@link #finishBroadcast}.
     *
     * <p>Note that it is possible for the process of one of the returned
     * interfaces to go away before you call it, so you will need to catch
     * {@link RemoteException} when calling on to the returned object.
     * The interface list itself, however, will take care of unregistering
     * these objects once it detects that it is no longer valid, so you can
     * handle such an exception by simply ignoring it.
     *
     * @param index Which of the registered interfaces you would like to
     * retrieve.  Ranges from 0 to {@link #beginBroadcast}-1, inclusive.
     *
     * @return Returns the interface that you can call.  This will always be non-null.
     *
     * @see #beginBroadcast
     */
    public E getBroadcastItem(int index) {
        return ((Interface) mActiveBroadcast[index]).mInterface;
    }

    /**
     * Retrieve the cookie associated with the item
     * returned by {@link #getBroadcastItem(int)}.
     * 
     * @see #getBroadcastItem
     */
    public Object getBroadcastCookie(int index) {
        return ((Interface) mActiveBroadcast[index]).mCookie;
    }

    /**
     * Clean up the state of a broadcast previously initiated by calling
     * {@link #beginBroadcast}.  This must always be called when you are done
     * with a broadcast.
     *
     * @see #beginBroadcast
     */
    public void finishBroadcast() {
        synchronized (mInterfaces) {
            if (mBroadcastCount < 0) {
                throw new IllegalStateException(
                        "finishBroadcast() called outside of a broadcast");
            }

            Object[] active = mActiveBroadcast;
            if (active != null) {
                final int N = mBroadcastCount;
                for (int i=0; i<N; i++) {
                    active[i] = null;
                }
            }

            mBroadcastCount = -1;
        }
    }

    /**
     * Performs {@code callback} on each registered interface.
     *
     * This is equivalent to #beginBroadcast, followed by iterating over the items using
     * #getBroadcastItem and then @finishBroadcast, except that this method supports
     * frozen callee policies.
     */
    @FlaggedApi(Flags.FLAG_BINDER_FROZEN_STATE_CHANGE_CALLBACK)
    public void broadcast(@NonNull Consumer<E> callback) {
        int itemCount = beginBroadcastInternal();
        try {
            for (int i = 0; i < itemCount; i++) {
                ((Interface) mActiveBroadcast[i]).addCallback(callback);
            }
        } finally {
            finishBroadcast();
        }
    }

    /**
     * Performs {@code callback} for each cookie associated with an interface, calling
     * {@link #beginBroadcast()}/{@link #finishBroadcast()} before/after looping
     *
     * @hide
     */
    public <C> void broadcastForEachCookie(Consumer<C> callback) {
        int itemCount = beginBroadcast();
        try {
            for (int i = 0; i < itemCount; i++) {
                callback.accept((C) getBroadcastCookie(i));
            }
        } finally {
            finishBroadcast();
        }
    }

    /**
     * Performs {@code callback} on each interface and associated cookie, calling {@link
     * #beginBroadcast()}/{@link #finishBroadcast()} before/after looping.
     *
     * @hide
     */
    public <C> void broadcast(BiConsumer<E, C> callback) {
        int itemCount = beginBroadcast();
        try {
            for (int i = 0; i < itemCount; i++) {
                callback.accept(getBroadcastItem(i), (C) getBroadcastCookie(i));
            }
        } finally {
            finishBroadcast();
        }
    }

    /**
     * Returns the number of registered interfaces. Note that the number of registered
     * interfaces may differ from the value returned by {@link #beginBroadcast()} since
     * the former returns the number of interfaces registered at the time of the call
     * and the second the number of interfaces to which the broadcast will be delivered.
     * <p>
     * This function is useful to decide whether to schedule a broadcast if this
     * requires doing some work which otherwise would not be performed.
     * </p>
     *
     * @return The size.
     */
    public int getRegisteredCallbackCount() {
        synchronized (mInterfaces) {
            if (mKilled) {
                return 0;
            }
            return mInterfaces.size();
        }
    }

    /**
     * Return a currently registered interface.  Note that this is
     * <em>not</em> the same as {@link #getBroadcastItem} and should not be used
     * interchangeably with it.  This method returns the registered interface at the given
     * index, not the current broadcast state.  This means that it is not itself thread-safe:
     * any call to {@link #register} or {@link #unregister} will change these indices, so you
     * must do your own thread safety between these to protect from such changes.
     *
     * @param index Index of which interface registration to return, from 0 to
     * {@link #getRegisteredCallbackCount()} - 1.
     *
     * @return Returns whatever interface is associated with this index, or null if
     * {@link #kill()} has been called.
     */
    public E getRegisteredCallbackItem(int index) {
        synchronized (mInterfaces) {
            if (mKilled) {
                return null;
            }
            return mInterfaces.valueAt(index).mInterface;
        }
    }

    /**
     * Return any cookie associated with a currently registered interface.  Note that this is
     * <em>not</em> the same as {@link #getBroadcastCookie} and should not be used
     * interchangeably with it.  This method returns the current cookie registered at the given
     * index, not the current broadcast state.  This means that it is not itself thread-safe:
     * any call to {@link #register} or {@link #unregister} will change these indices, so you
     * must do your own thread safety between these to protect from such changes.
     *
     * @param index Index of which registration cookie to return, from 0 to
     * {@link #getRegisteredCallbackCount()} - 1.
     *
     * @return Returns whatever cookie object is associated with this index, or null if
     * {@link #kill()} has been called.
     */
    public Object getRegisteredCallbackCookie(int index) {
        synchronized (mInterfaces) {
            if (mKilled) {
                return null;
            }
            return mInterfaces.valueAt(index).mCookie;
        }
    }

    /** @hide */
    public void dump(PrintWriter pw, String prefix) {
        synchronized (mInterfaces) {
            pw.print(prefix); pw.print("callbacks: "); pw.println(mInterfaces.size());
            pw.print(prefix); pw.print("killed: "); pw.println(mKilled);
            pw.print(prefix); pw.print("broadcasts count: "); pw.println(mBroadcastCount);
        }
    }

    private void logExcessiveInterfaces() {
        final long size = mInterfaces.size();
        final long TOO_MANY = 3000;
        final long MAX_CHARS = 1000;
        if (size >= TOO_MANY) {
            if (size == TOO_MANY && mRecentCallers == null) {
                mRecentCallers = new StringBuilder();
            }
            if (mRecentCallers != null && mRecentCallers.length() < MAX_CHARS) {
                mRecentCallers.append(Debug.getCallers(5));
                mRecentCallers.append('\n');
                if (mRecentCallers.length() >= MAX_CHARS) {
                    Slog.wtf(TAG, "More than "
                            + TOO_MANY + " remote callbacks registered. Recent callers:\n"
                            + mRecentCallers.toString());
                    mRecentCallers = null;
                }
            }
        }
    }
}
