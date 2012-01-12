/*
 * Copyright (C) 2012 The Android Open Source Project
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

import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.NoSuchElementException;
import static libcore.io.OsConstants.*;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.CommonTimeUtils;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;

/**
 * Used for accessing the android common time service's common clock and receiving notifications
 * about common time synchronization status changes.
 * @hide
 */
public class CommonClock {
    /**
     * Sentinel value returned by {@link #getTime()} and {@link #getEstimatedError()} when the
     * common time service is not able to determine the current common time due to a lack of
     * synchronization.
     */
    public static final long TIME_NOT_SYNCED = -1;

    /**
     * Sentinel value returned by {@link #getTimelineId()} when the common time service is not
     * currently synced to any timeline.
     */
    public static final long INVALID_TIMELINE_ID = 0;

    /**
     * Sentinel value returned by {@link #getEstimatedError()} when the common time service is not
     * currently synced to any timeline.
     */
    public static final int ERROR_ESTIMATE_UNKNOWN = 0x7FFFFFFF;

    /**
     * Value used by {@link #getState()} to indicate that there was an internal error while
     * attempting to determine the state of the common time service.
     */
    public static final int STATE_INVALID = -1;

    /**
     * Value used by {@link #getState()} to indicate that the common time service is in its initial
     * state and attempting to find the current timeline master, if any.  The service will
     * transition to either {@link #STATE_CLIENT} if it finds an active master, or to
     * {@link #STATE_MASTER} if no active master is found and this client becomes the master of a
     * new timeline.
     */
    public static final int STATE_INITIAL = 0;

    /**
     * Value used by {@link #getState()} to indicate that the common time service is in its client
     * state and is synchronizing its time to a different timeline master on the network.
     */
    public static final int STATE_CLIENT = 1;

    /**
     * Value used by {@link #getState()} to indicate that the common time service is in its master
     * state and is serving as the timeline master for other common time service clients on the
     * network.
     */
    public static final int STATE_MASTER = 2;

    /**
     * Value used by {@link #getState()} to indicate that the common time service is in its Ronin
     * state.  Common time service instances in the client state enter the Ronin state after their
     * timeline master becomes unreachable on the network.  Common time services who enter the Ronin
     * state will begin a new master election for the timeline they were recently clients of.  As
     * clients detect they are not the winner and drop out of the election, they will transition to
     * the {@link #STATE_WAIT_FOR_ELECTION} state.  When there is only one client remaining in the
     * election, it will assume ownership of the timeline and transition to the
     * {@link #STATE_MASTER} state.  During the election, all clients will allow their timeline to
     * drift without applying correction.
     */
    public static final int STATE_RONIN = 3;

    /**
     * Value used by {@link #getState()} to indicate that the common time service is waiting for a
     * master election to conclude and for the new master to announce itself before transitioning to
     * the {@link #STATE_CLIENT} state.  If no new master announces itself within the timeout
     * threshold, the time service will transition back to the {@link #STATE_RONIN} state in order
     * to restart the election.
     */
    public static final int STATE_WAIT_FOR_ELECTION = 4;

    /**
     * Name of the underlying native binder service
     */
    public static final String SERVICE_NAME = "common_time.clock";

    /**
     * Class constructor.
     * @throws android.os.RemoteException
     */
    public CommonClock()
    throws RemoteException {
        mRemote = ServiceManager.getService(SERVICE_NAME);
        if (null == mRemote)
            throw new RemoteException();

        mInterfaceDesc = mRemote.getInterfaceDescriptor();
        mUtils = new CommonTimeUtils(mRemote, mInterfaceDesc);
        mRemote.linkToDeath(mDeathHandler, 0);
        registerTimelineChangeListener();
    }

    /**
     * Handy class factory method.
     */
    static public CommonClock create() {
        CommonClock retVal;

        try {
            retVal = new CommonClock();
        }
        catch (RemoteException e) {
            retVal = null;
        }

        return retVal;
    }

    /**
     * Release all native resources held by this {@link android.os.CommonClock} instance.  Once
     * resources have been released, the {@link android.os.CommonClock} instance is disconnected from
     * the native service and will throw a {@link android.os.RemoteException} if any of its
     * methods are called.  Clients should always call release on their client instances before
     * releasing their last Java reference to the instance.  Failure to do this will cause
     * non-deterministic native resource reclamation and may cause the common time service to remain
     * active on the network for longer than it should.
     */
    public void release() {
        unregisterTimelineChangeListener();
        if (null != mRemote) {
            try {
                mRemote.unlinkToDeath(mDeathHandler, 0);
            }
            catch (NoSuchElementException e) { }
            mRemote = null;
        }
        mUtils = null;
    }

    /**
     * Gets the common clock's current time.
     *
     * @return a signed 64-bit value representing the current common time in microseconds, or the
     * special value {@link #TIME_NOT_SYNCED} if the common time service is currently not
     * synchronized.
     * @throws android.os.RemoteException
     */
    public long getTime()
    throws RemoteException {
        throwOnDeadServer();
        return mUtils.transactGetLong(METHOD_GET_COMMON_TIME, TIME_NOT_SYNCED);
    }

    /**
     * Gets the current estimation of common clock's synchronization accuracy from the common time
     * service.
     *
     * @return a signed 32-bit value representing the common time service's estimation of
     * synchronization accuracy in microseconds, or the special value
     * {@link #ERROR_ESTIMATE_UNKNOWN} if the common time service is currently not synchronized.
     * Negative values indicate that the local server estimates that the nominal common time is
     * behind the local server's time (in other words, the local clock is running fast) Positive
     * values indicate that the local server estimates that the nominal common time is ahead of the
     * local server's time (in other words, the local clock is running slow)
     * @throws android.os.RemoteException
     */
    public int getEstimatedError()
    throws RemoteException {
        throwOnDeadServer();
        return mUtils.transactGetInt(METHOD_GET_ESTIMATED_ERROR, ERROR_ESTIMATE_UNKNOWN);
    }

    /**
     * Gets the ID of the timeline the common time service is currently synchronizing its clock to.
     *
     * @return a long representing the unique ID of the timeline the common time service is
     * currently synchronizing with, or {@link #INVALID_TIMELINE_ID} if the common time service is
     * currently not synchronized.
     * @throws android.os.RemoteException
     */
    public long getTimelineId()
    throws RemoteException {
        throwOnDeadServer();
        return mUtils.transactGetLong(METHOD_GET_TIMELINE_ID, INVALID_TIMELINE_ID);
    }

    /**
     * Gets the current state of this clock's common time service in the the master election
     * algorithm.
     *
     * @return a integer indicating the current state of the this clock's common time service in the
     * master election algorithm or {@link #STATE_INVALID} if there is an internal error.
     * @throws android.os.RemoteException
     */
    public int getState()
    throws RemoteException {
        throwOnDeadServer();
        return mUtils.transactGetInt(METHOD_GET_STATE, STATE_INVALID);
    }

    /**
     * Gets the IP address and UDP port of the current timeline master.
     *
     * @return an InetSocketAddress containing the IP address and UDP port of the current timeline
     * master, or null if there is no current master.
     * @throws android.os.RemoteException
     */
    public InetSocketAddress getMasterAddr()
    throws RemoteException {
        throwOnDeadServer();
        return mUtils.transactGetSockaddr(METHOD_GET_MASTER_ADDRESS);
    }

    /**
     * The OnTimelineChangedListener interface defines a method called by the
     * {@link android.os.CommonClock} instance to indicate that the time synchronization service has
     * either synchronized with a new timeline, or is no longer a member of any timeline.  The
     * client application can implement this interface and register the listener with the
     * {@link #setTimelineChangedListener(OnTimelineChangedListener)} method.
     */
    public interface OnTimelineChangedListener  {
        /**
         * Method called when the time service's timeline has changed.
         *
         * @param newTimelineId a long which uniquely identifies the timeline the time
         * synchronization service is now a member of, or {@link #INVALID_TIMELINE_ID} if the the
         * service is not synchronized to any timeline.
         */
        void onTimelineChanged(long newTimelineId);
    }

    /**
     * Registers an OnTimelineChangedListener interface.
     * <p>Call this method with a null listener to stop receiving server death notifications.
     */
    public void setTimelineChangedListener(OnTimelineChangedListener listener) {
        synchronized (mListenerLock) {
            mTimelineChangedListener = listener;
        }
    }

    /**
     * The OnServerDiedListener interface defines a method called by the
     * {@link android.os.CommonClock} instance to indicate that the connection to the native media
     * server has been broken and that the {@link android.os.CommonClock} instance will need to be
     * released and re-created.  The client application can implement this interface and register
     * the listener with the {@link #setServerDiedListener(OnServerDiedListener)} method.
     */
    public interface OnServerDiedListener  {
        /**
         * Method called when the native media server has died.  <p>If the native common time
         * service encounters a fatal error and needs to restart, the binder connection from the
         * {@link android.os.CommonClock} instance to the common time service will be broken.  To
         * restore functionality, clients should {@link #release()} their old visualizer and create
         * a new instance.
         */
        void onServerDied();
    }

    /**
     * Registers an OnServerDiedListener interface.
     * <p>Call this method with a null listener to stop receiving server death notifications.
     */
    public void setServerDiedListener(OnServerDiedListener listener) {
        synchronized (mListenerLock) {
            mServerDiedListener = listener;
        }
    }

    protected void finalize() throws Throwable { release(); }

    private void throwOnDeadServer() throws RemoteException {
        if ((null == mRemote) || (null == mUtils))
            throw new RemoteException();
    }

    private final Object mListenerLock = new Object();
    private OnTimelineChangedListener mTimelineChangedListener = null;
    private OnServerDiedListener mServerDiedListener = null;

    private IBinder mRemote = null;
    private String mInterfaceDesc = "";
    private CommonTimeUtils mUtils;

    private IBinder.DeathRecipient mDeathHandler = new IBinder.DeathRecipient() {
        public void binderDied() {
            synchronized (mListenerLock) {
                if (null != mServerDiedListener)
                    mServerDiedListener.onServerDied();
            }
        }
    };

    private class TimelineChangedListener extends Binder {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
        throws RemoteException {
            switch (code) {
                case METHOD_CBK_ON_TIMELINE_CHANGED:
                    data.enforceInterface(DESCRIPTOR);
                    long timelineId = data.readLong();
                    synchronized (mListenerLock) {
                        if (null != mTimelineChangedListener)
                            mTimelineChangedListener.onTimelineChanged(timelineId);
                    }
                    return true;
            }

            return super.onTransact(code, data, reply, flags);
        }

        private static final String DESCRIPTOR = "android.os.ICommonClockListener";
    };

    private TimelineChangedListener mCallbackTgt = null;

    private void registerTimelineChangeListener() throws RemoteException {
        if (null != mCallbackTgt)
            return;

        boolean success = false;
        android.os.Parcel data  = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        mCallbackTgt = new TimelineChangedListener();

        try {
            data.writeInterfaceToken(mInterfaceDesc);
            data.writeStrongBinder(mCallbackTgt);
            mRemote.transact(METHOD_REGISTER_LISTENER, data, reply, 0);
            success = (0 == reply.readInt());
        }
        catch (RemoteException e) {
            success = false;
        }
        finally {
            reply.recycle();
            data.recycle();
        }

        // Did we catch a remote exception or fail to register our callback target?  If so, our
        // object must already be dead (or be as good as dead).  Clear out all of our state so that
        // our other methods will properly indicate a dead object.
        if (!success) {
            mCallbackTgt = null;
            mRemote = null;
            mUtils = null;
        }
    }

    private void unregisterTimelineChangeListener() {
        if (null == mCallbackTgt)
            return;

        android.os.Parcel data  = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();

        try {
            data.writeInterfaceToken(mInterfaceDesc);
            data.writeStrongBinder(mCallbackTgt);
            mRemote.transact(METHOD_UNREGISTER_LISTENER, data, reply, 0);
        }
        catch (RemoteException e) { }
        finally {
            reply.recycle();
            data.recycle();
            mCallbackTgt = null;
        }
    }

    private static final int METHOD_IS_COMMON_TIME_VALID = IBinder.FIRST_CALL_TRANSACTION;
    private static final int METHOD_COMMON_TIME_TO_LOCAL_TIME = METHOD_IS_COMMON_TIME_VALID + 1;
    private static final int METHOD_LOCAL_TIME_TO_COMMON_TIME = METHOD_COMMON_TIME_TO_LOCAL_TIME + 1;
    private static final int METHOD_GET_COMMON_TIME = METHOD_LOCAL_TIME_TO_COMMON_TIME + 1;
    private static final int METHOD_GET_COMMON_FREQ = METHOD_GET_COMMON_TIME + 1;
    private static final int METHOD_GET_LOCAL_TIME = METHOD_GET_COMMON_FREQ + 1;
    private static final int METHOD_GET_LOCAL_FREQ = METHOD_GET_LOCAL_TIME + 1;
    private static final int METHOD_GET_ESTIMATED_ERROR = METHOD_GET_LOCAL_FREQ + 1;
    private static final int METHOD_GET_TIMELINE_ID = METHOD_GET_ESTIMATED_ERROR + 1;
    private static final int METHOD_GET_STATE = METHOD_GET_TIMELINE_ID + 1;
    private static final int METHOD_GET_MASTER_ADDRESS = METHOD_GET_STATE + 1;
    private static final int METHOD_REGISTER_LISTENER = METHOD_GET_MASTER_ADDRESS + 1;
    private static final int METHOD_UNREGISTER_LISTENER = METHOD_REGISTER_LISTENER + 1;

    private static final int METHOD_CBK_ON_TIMELINE_CHANGED = IBinder.FIRST_CALL_TRANSACTION;
}
