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

import android.util.Config;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;

/**
 * Base class for a remotable object, the core part of a lightweight
 * remote procedure call mechanism defined by {@link IBinder}.
 * This class is an implementation of IBinder that provides
 * the standard support creating a local implementation of such an object.
 * 
 * <p>Most developers will not implement this class directly, instead using the
 * <a href="{@docRoot}guide/developing/tools/aidl.html">aidl</a> tool to describe the desired
 * interface, having it generate the appropriate Binder subclass.  You can,
 * however, derive directly from Binder to implement your own custom RPC
 * protocol or simply instantiate a raw Binder object directly to use as a
 * token that can be shared across processes.
 * 
 * @see IBinder
 */
public class Binder implements IBinder {
    /*
     * Set this flag to true to detect anonymous, local or member classes
     * that extend this Binder class and that are not static. These kind
     * of classes can potentially create leaks.
     */
    private static final boolean FIND_POTENTIAL_LEAKS = false;
    private static final String TAG = "Binder";

    private int mObject;
    private IInterface mOwner;
    private String mDescriptor;
    
    /**
     * Return the ID of the process that sent you the current transaction
     * that is being processed.  This pid can be used with higher-level
     * system services to determine its identity and check permissions.
     * If the current thread is not currently executing an incoming transaction,
     * then its own pid is returned.
     */
    public static final native int getCallingPid();
    
    /**
     * Return the ID of the user assigned to the process that sent you the
     * current transaction that is being processed.  This uid can be used with
     * higher-level system services to determine its identity and check
     * permissions.  If the current thread is not currently executing an
     * incoming transaction, then its own uid is returned.
     */
    public static final native int getCallingUid();
    
    /**
     * Reset the identity of the incoming IPC on the current thread.  This can
     * be useful if, while handling an incoming call, you will be calling
     * on interfaces of other objects that may be local to your process and
     * need to do permission checks on the calls coming into them (so they
     * will check the permission of your own local process, and not whatever
     * process originally called you).
     *
     * @return Returns an opaque token that can be used to restore the
     * original calling identity by passing it to
     * {@link #restoreCallingIdentity(long)}.
     *
     * @see #getCallingPid()
     * @see #getCallingUid()
     * @see #restoreCallingIdentity(long)
     */
    public static final native long clearCallingIdentity();

    /**
     * Restore the identity of the incoming IPC on the current thread
     * back to a previously identity that was returned by {@link
     * #clearCallingIdentity}.
     *
     * @param token The opaque token that was previously returned by
     * {@link #clearCallingIdentity}.
     *
     * @see #clearCallingIdentity
     */
    public static final native void restoreCallingIdentity(long token);
    
    /**
     * Flush any Binder commands pending in the current thread to the kernel
     * driver.  This can be
     * useful to call before performing an operation that may block for a long
     * time, to ensure that any pending object references have been released
     * in order to prevent the process from holding on to objects longer than
     * it needs to.
     */
    public static final native void flushPendingCommands();
    
    /**
     * Add the calling thread to the IPC thread pool.  This function does
     * not return until the current process is exiting.
     */
    public static final native void joinThreadPool();
    
    /**
     * Default constructor initializes the object.
     */
    public Binder() {
        init();

        if (FIND_POTENTIAL_LEAKS) {
            final Class<? extends Binder> klass = getClass();
            if ((klass.isAnonymousClass() || klass.isMemberClass() || klass.isLocalClass()) &&
                    (klass.getModifiers() & Modifier.STATIC) == 0) {
                Log.w(TAG, "The following Binder class should be static or leaks might occur: " +
                    klass.getCanonicalName());
            }
        }
    }
    
    /**
     * Convenience method for associating a specific interface with the Binder.
     * After calling, queryLocalInterface() will be implemented for you
     * to return the given owner IInterface when the corresponding
     * descriptor is requested.
     */
    public void attachInterface(IInterface owner, String descriptor) {
        mOwner = owner;
        mDescriptor = descriptor;
    }
    
    /**
     * Default implementation returns an empty interface name.
     */
    public String getInterfaceDescriptor() {
        return mDescriptor;
    }

    /**
     * Default implementation always returns true -- if you got here,
     * the object is alive.
     */
    public boolean pingBinder() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * Note that if you're calling on a local binder, this always returns true
     * because your process is alive if you're calling it.
     */
    public boolean isBinderAlive() {
        return true;
    }
    
    /**
     * Use information supplied to attachInterface() to return the
     * associated IInterface if it matches the requested
     * descriptor.
     */
    public IInterface queryLocalInterface(String descriptor) {
        if (mDescriptor.equals(descriptor)) {
            return mOwner;
        }
        return null;
    }
    
    /**
     * Default implementation is a stub that returns false.  You will want
     * to override this to do the appropriate unmarshalling of transactions.
     *
     * <p>If you want to call this, call transact().
     */
    protected boolean onTransact(int code, Parcel data, Parcel reply,
            int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            reply.writeString(getInterfaceDescriptor());
            return true;
        } else if (code == DUMP_TRANSACTION) {
            ParcelFileDescriptor fd = data.readFileDescriptor();
            String[] args = data.readStringArray();
            if (fd != null) {
                try {
                    dump(fd.getFileDescriptor(), args);
                } finally {
                    try {
                        fd.close();
                    } catch (IOException e) {
                    }
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Implemented to call the more convenient version
     * {@link #dump(FileDescriptor, PrintWriter, String[])}.
     */
    public void dump(FileDescriptor fd, String[] args) {
        FileOutputStream fout = new FileOutputStream(fd);
        PrintWriter pw = new PrintWriter(fout);
        try {
            dump(fd, pw, args);
        } finally {
            pw.flush();
        }
    }
    
    /**
     * Print the object's state into the given stream.
     * 
     * @param fd The raw file descriptor that the dump is being sent to.
     * @param fout The file to which you should dump your state.  This will be
     * closed for you after you return.
     * @param args additional arguments to the dump request.
     */
    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
    }

    /**
     * Default implementation rewinds the parcels and calls onTransact.  On
     * the remote side, transact calls into the binder to do the IPC.
     */
    public final boolean transact(int code, Parcel data, Parcel reply,
            int flags) throws RemoteException {
        if (Config.LOGV) Log.v("Binder", "Transact: " + code + " to " + this);
        if (data != null) {
            data.setDataPosition(0);
        }
        boolean r = onTransact(code, data, reply, flags);
        if (reply != null) {
            reply.setDataPosition(0);
        }
        return r;
    }
    
    /**
     * Local implementation is a no-op.
     */
    public void linkToDeath(DeathRecipient recipient, int flags) {
    }

    /**
     * Local implementation is a no-op.
     */
    public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
        return true;
    }
    
    protected void finalize() throws Throwable {
        try {
            destroy();
        } finally {
            super.finalize();
        }
    }
    
    private native final void init();
    private native final void destroy();
    private boolean execTransact(int code, int dataObj, int replyObj,
            int flags) {
        Parcel data = Parcel.obtain(dataObj);
        Parcel reply = Parcel.obtain(replyObj);
        // theoretically, we should call transact, which will call onTransact,
        // but all that does is rewind it, and we just got these from an IPC,
        // so we'll just call it directly.
        boolean res;
        try {
            res = onTransact(code, data, reply, flags);
        } catch (RemoteException e) {
            reply.writeException(e);
            res = true;
        } catch (RuntimeException e) {
            reply.writeException(e);
            res = true;
        } catch (OutOfMemoryError e) {
            RuntimeException re = new RuntimeException("Out of memory", e);
            reply.writeException(re);
            res = true;
        }
        reply.recycle();
        data.recycle();
        return res;
    }
}

final class BinderProxy implements IBinder {
    public native boolean pingBinder();
    public native boolean isBinderAlive();
    
    public IInterface queryLocalInterface(String descriptor) {
        return null;
    }
    
    public native String getInterfaceDescriptor() throws RemoteException;
    public native boolean transact(int code, Parcel data, Parcel reply,
            int flags) throws RemoteException;
    public native void linkToDeath(DeathRecipient recipient, int flags)
            throws RemoteException;
    public native boolean unlinkToDeath(DeathRecipient recipient, int flags);

    public void dump(FileDescriptor fd, String[] args) throws RemoteException {
        Parcel data = Parcel.obtain();
        data.writeFileDescriptor(fd);
        data.writeStringArray(args);
        try {
            transact(DUMP_TRANSACTION, data, null, 0);
        } finally {
            data.recycle();
        }
    }
    
    BinderProxy() {
        mSelf = new WeakReference(this);
    }
    
    @Override
    protected void finalize() throws Throwable {
        try {
            destroy();
        } finally {
            super.finalize();
        }
    }
    
    private native final void destroy();
    
    private static final void sendDeathNotice(DeathRecipient recipient) {
        if (Config.LOGV) Log.v("JavaBinder", "sendDeathNotice to " + recipient);
        try {
            recipient.binderDied();
        }
        catch (RuntimeException exc) {
            Log.w("BinderNative", "Uncaught exception from death notification",
                    exc);
        }
    }
    
    final private WeakReference mSelf;
    private int mObject;
}
