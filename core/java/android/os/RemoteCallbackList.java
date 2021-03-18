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

import android.compat.annotation.UnsupportedAppUsage;
import android.util.ArrayMap;
import android.util.Slog;

import java.io.PrintWriter;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Takes care of the grunt work of maintaining a list of remote interfaces,
 * typically for the use of performing callbacks from a
 * {@link android.app.Service} to its clients.  In particular, this:
 *
 * <ul>
 * <li> Keeps track of a set of registered {@link IInterface} callbacks,
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
 * <p>If a registered callback's process goes away, this class will take
 * care of automatically removing it from the list.  If you want to do
 * additional work in this situation, you can create a subclass that
 * implements the {@link #onCallbackDied} method.
 */
public class RemoteCallbackList<E extends IInterface> {
    private static final String TAG = "RemoteCallbackList";

    @UnsupportedAppUsage
    /*package*/ ArrayMap<IBinder, Callback> mCallbacks
            = new ArrayMap<IBinder, Callback>();
    private Object[] mActiveBroadcast;
    private int mBroadcastCount = -1;
    private boolean mKilled = false;
    private StringBuilder mRecentCallers;

    private final class Callback implements IBinder.DeathRecipient {
        final E mCallback;
        final Object mCookie;

        Callback(E callback, Object cookie) {
            mCallback = callback;
            mCookie = cookie;
        }

        public void binderDied() {
            synchronized (mCallbacks) {
                mCallbacks.remove(mCallback.asBinder());
            }
            onCallbackDied(mCallback, mCookie);
        }
    }

    /**
     * Simple version of {@link RemoteCallbackList#register(E, Object)}
     * that does not take a cookie object.
     */
    public boolean register(E callback) {
        return register(callback, null);
    }

    /**
     * Add a new callback to the list.  This callback will remain in the list
     * until a corresponding call to {@link #unregister} or its hosting process
     * goes away.  If the callback was already registered (determined by
     * checking to see if the {@link IInterface#asBinder callback.asBinder()}
     * object is already in the list), then it will be left as-is.
     * Registrations are not counted; a single call to {@link #unregister}
     * will remove a callback after any number calls to register it.
     *
     * @param callback The callback interface to be added to the list.  Must
     * not be null -- passing null here will cause a NullPointerException.
     * Most services will want to check for null before calling this with
     * an object given from a client, so that clients can't crash the
     * service with bad data.
     *
     * @param cookie Optional additional data to be associated with this
     * callback.
     * 
     * @return Returns true if the callback was successfully added to the list.
     * Returns false if it was not added, either because {@link #kill} had
     * previously been called or the callback's process has gone away.
     *
     * @see #unregister
     * @see #kill
     * @see #onCallbackDied
     */
    public boolean register(E callback, Object cookie) {
        synchronized (mCallbacks) {
            if (mKilled) {
                return false;
            }
            // Flag unusual case that could be caused by a leak. b/36778087
            logExcessiveCallbacks();
            IBinder binder = callback.asBinder();
            try {
                Callback cb = new Callback(callback, cookie);
                unregister(callback);
                binder.linkToDeath(cb, 0);
                mCallbacks.put(binder, cb);
                return true;
            } catch (RemoteException e) {
                return false;
            }
        }
    }

    /**
     * Remove from the list a callback that was previously added with
     * {@link #register}.  This uses the
     * {@link IInterface#asBinder callback.asBinder()} object to correctly
     * find the previous registration.
     * Registrations are not counted; a single unregister call will remove
     * a callback after any number calls to {@link #register} for it.
     *
     * @param callback The callback to be removed from the list.  Passing
     * null here will cause a NullPointerException, so you will generally want
     * to check for null before calling.
     *
     * @return Returns true if the callback was found and unregistered.  Returns
     * false if the given callback was not found on the list.
     *
     * @see #register
     */
    public boolean unregister(E callback) {
        synchronized (mCallbacks) {
            Callback cb = mCallbacks.remove(callback.asBinder());
            if (cb != null) {
                cb.mCallback.asBinder().unlinkToDeath(cb, 0);
                return true;
            }
            return false;
        }
    }

    /**
     * Disable this callback list.  All registered callbacks are unregistered,
     * and the list is disabled so that future calls to {@link #register} will
     * fail.  This should be used when a Service is stopping, to prevent clients
     * from registering callbacks after it is stopped.
     *
     * @see #register
     */
    public void kill() {
        synchronized (mCallbacks) {
            for (int cbi=mCallbacks.size()-1; cbi>=0; cbi--) {
                Callback cb = mCallbacks.valueAt(cbi);
                cb.mCallback.asBinder().unlinkToDeath(cb, 0);
            }
            mCallbacks.clear();
            mKilled = true;
        }
    }

    /**
     * Old version of {@link #onCallbackDied(E, Object)} that
     * does not provide a cookie.
     */
    public void onCallbackDied(E callback) {
    }
    
    /**
     * Called when the process hosting a callback in the list has gone away.
     * The default implementation calls {@link #onCallbackDied(E)}
     * for backwards compatibility.
     * 
     * @param callback The callback whose process has died.  Note that, since
     * its process has died, you can not make any calls on to this interface.
     * You can, however, retrieve its IBinder and compare it with another
     * IBinder to see if it is the same object.
     * @param cookie The cookie object original provided to
     * {@link #register(E, Object)}.
     * 
     * @see #register
     */
    public void onCallbackDied(E callback, Object cookie) {
        onCallbackDied(callback);
    }

    /**
     * Prepare to start making calls to the currently registered callbacks.
     * This creates a copy of the callback list, which you can retrieve items
     * from using {@link #getBroadcastItem}.  Note that only one broadcast can
     * be active at a time, so you must be sure to always call this from the
     * same thread (usually by scheduling with {@link Handler}) or
     * do your own synchronization.  You must call {@link #finishBroadcast}
     * when done.
     *
     * <p>A typical loop delivering a broadcast looks like this:
     *
     * <pre>
     * int i = callbacks.beginBroadcast();
     * while (i &gt; 0) {
     *     i--;
     *     try {
     *         callbacks.getBroadcastItem(i).somethingHappened();
     *     } catch (RemoteException e) {
     *         // The RemoteCallbackList will take care of removing
     *         // the dead object for us.
     *     }
     * }
     * callbacks.finishBroadcast();</pre>
     *
     * @return Returns the number of callbacks in the broadcast, to be used
     * with {@link #getBroadcastItem} to determine the range of indices you
     * can supply.
     *
     * @see #getBroadcastItem
     * @see #finishBroadcast
     */
    public int beginBroadcast() {
        synchronized (mCallbacks) {
            if (mBroadcastCount > 0) {
                throw new IllegalStateException(
                        "beginBroadcast() called while already in a broadcast");
            }
            
            final int N = mBroadcastCount = mCallbacks.size();
            if (N <= 0) {
                return 0;
            }
            Object[] active = mActiveBroadcast;
            if (active == null || active.length < N) {
                mActiveBroadcast = active = new Object[N];
            }
            for (int i=0; i<N; i++) {
                active[i] = mCallbacks.valueAt(i);
            }
            return N;
        }
    }

    /**
     * Retrieve an item in the active broadcast that was previously started
     * with {@link #beginBroadcast}.  This can <em>only</em> be called after
     * the broadcast is started, and its data is no longer valid after
     * calling {@link #finishBroadcast}.
     *
     * <p>Note that it is possible for the process of one of the returned
     * callbacks to go away before you call it, so you will need to catch
     * {@link RemoteException} when calling on to the returned object.
     * The callback list itself, however, will take care of unregistering
     * these objects once it detects that it is no longer valid, so you can
     * handle such an exception by simply ignoring it.
     *
     * @param index Which of the registered callbacks you would like to
     * retrieve.  Ranges from 0 to 1-{@link #beginBroadcast}.
     *
     * @return Returns the callback interface that you can call.  This will
     * always be non-null.
     *
     * @see #beginBroadcast
     */
    public E getBroadcastItem(int index) {
        return ((Callback)mActiveBroadcast[index]).mCallback;
    }
    
    /**
     * Retrieve the cookie associated with the item
     * returned by {@link #getBroadcastItem(int)}.
     * 
     * @see #getBroadcastItem
     */
    public Object getBroadcastCookie(int index) {
        return ((Callback)mActiveBroadcast[index]).mCookie;
    }

    /**
     * Clean up the state of a broadcast previously initiated by calling
     * {@link #beginBroadcast}.  This must always be called when you are done
     * with a broadcast.
     *
     * @see #beginBroadcast
     */
    public void finishBroadcast() {
        synchronized (mCallbacks) {
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
     * Performs {@code action} on each callback, calling
     * {@link #beginBroadcast()}/{@link #finishBroadcast()} before/after looping
     *
     * @hide
     */
    public void broadcast(Consumer<E> action) {
        int itemCount = beginBroadcast();
        try {
            for (int i = 0; i < itemCount; i++) {
                action.accept(getBroadcastItem(i));
            }
        } finally {
            finishBroadcast();
        }
    }

    /**
     * Performs {@code action} for each cookie associated with a callback, calling
     * {@link #beginBroadcast()}/{@link #finishBroadcast()} before/after looping
     *
     * @hide
     */
    public <C> void broadcastForEachCookie(Consumer<C> action) {
        int itemCount = beginBroadcast();
        try {
            for (int i = 0; i < itemCount; i++) {
                action.accept((C) getBroadcastCookie(i));
            }
        } finally {
            finishBroadcast();
        }
    }

    /**
     * Performs {@code action} on each callback and associated cookie, calling {@link
     * #beginBroadcast()}/{@link #finishBroadcast()} before/after looping.
     *
     * @hide
     */
    public <C> void broadcast(BiConsumer<E, C> action) {
        int itemCount = beginBroadcast();
        try {
            for (int i = 0; i < itemCount; i++) {
                action.accept(getBroadcastItem(i), (C) getBroadcastCookie(i));
            }
        } finally {
            finishBroadcast();
        }
    }

    /**
     * Returns the number of registered callbacks. Note that the number of registered
     * callbacks may differ from the value returned by {@link #beginBroadcast()} since
     * the former returns the number of callbacks registered at the time of the call
     * and the second the number of callback to which the broadcast will be delivered.
     * <p>
     * This function is useful to decide whether to schedule a broadcast if this
     * requires doing some work which otherwise would not be performed.
     * </p>
     *
     * @return The size.
     */
    public int getRegisteredCallbackCount() {
        synchronized (mCallbacks) {
            if (mKilled) {
                return 0;
            }
            return mCallbacks.size();
        }
    }

    /**
     * Return a currently registered callback.  Note that this is
     * <em>not</em> the same as {@link #getBroadcastItem} and should not be used
     * interchangeably with it.  This method returns the registered callback at the given
     * index, not the current broadcast state.  This means that it is not itself thread-safe:
     * any call to {@link #register} or {@link #unregister} will change these indices, so you
     * must do your own thread safety between these to protect from such changes.
     *
     * @param index Index of which callback registration to return, from 0 to
     * {@link #getRegisteredCallbackCount()} - 1.
     *
     * @return Returns whatever callback is associated with this index, or null if
     * {@link #kill()} has been called.
     */
    public E getRegisteredCallbackItem(int index) {
        synchronized (mCallbacks) {
            if (mKilled) {
                return null;
            }
            return mCallbacks.valueAt(index).mCallback;
        }
    }

    /**
     * Return any cookie associated with a currently registered callback.  Note that this is
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
        synchronized (mCallbacks) {
            if (mKilled) {
                return null;
            }
            return mCallbacks.valueAt(index).mCookie;
        }
    }

    /** @hide */
    public void dump(PrintWriter pw, String prefix) {
        synchronized (mCallbacks) {
            pw.print(prefix); pw.print("callbacks: "); pw.println(mCallbacks.size());
            pw.print(prefix); pw.print("killed: "); pw.println(mKilled);
            pw.print(prefix); pw.print("broadcasts count: "); pw.println(mBroadcastCount);
        }
    }

    private void logExcessiveCallbacks() {
        final long size = mCallbacks.size();
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
