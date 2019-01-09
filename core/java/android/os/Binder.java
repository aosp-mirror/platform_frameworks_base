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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.util.ExceptionUtils;
import android.util.Log;
import android.util.Slog;

import com.android.internal.os.BinderInternal;
import com.android.internal.os.BinderInternal.CallSession;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.FunctionalUtils.ThrowingRunnable;
import com.android.internal.util.FunctionalUtils.ThrowingSupplier;

import dalvik.annotation.optimization.CriticalNative;

import libcore.io.IoUtils;
import libcore.util.NativeAllocationRegistry;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Modifier;

/**
 * Base class for a remotable object, the core part of a lightweight
 * remote procedure call mechanism defined by {@link IBinder}.
 * This class is an implementation of IBinder that provides
 * standard local implementation of such an object.
 *
 * <p>Most developers will not implement this class directly, instead using the
 * <a href="{@docRoot}guide/components/aidl.html">aidl</a> tool to describe the desired
 * interface, having it generate the appropriate Binder subclass.  You can,
 * however, derive directly from Binder to implement your own custom RPC
 * protocol or simply instantiate a raw Binder object directly to use as a
 * token that can be shared across processes.
 *
 * <p>This class is just a basic IPC primitive; it has no impact on an application's
 * lifecycle, and is valid only as long as the process that created it continues to run.
 * To use this correctly, you must be doing so within the context of a top-level
 * application component (a {@link android.app.Service}, {@link android.app.Activity},
 * or {@link android.content.ContentProvider}) that lets the system know your process
 * should remain running.</p>
 *
 * <p>You must keep in mind the situations in which your process
 * could go away, and thus require that you later re-create a new Binder and re-attach
 * it when the process starts again.  For example, if you are using this within an
 * {@link android.app.Activity}, your activity's process may be killed any time the
 * activity is not started; if the activity is later re-created you will need to
 * create a new Binder and hand it back to the correct place again; you need to be
 * aware that your process may be started for another reason (for example to receive
 * a broadcast) that will not involve re-creating the activity and thus run its code
 * to create a new Binder.</p>
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
    /** @hide */
    public static final boolean CHECK_PARCEL_SIZE = false;
    static final String TAG = "Binder";

    /** @hide */
    public static boolean LOG_RUNTIME_EXCEPTION = false; // DO NOT SUBMIT WITH TRUE

    /**
     * Value to represents that a calling work source is not set.
     *
     * This constatnt needs to be kept in sync with IPCThreadState::kUnsetWorkSource.
     *
     * @hide
     */
    public static final int UNSET_WORKSOURCE = -1;

    /**
     * Control whether dump() calls are allowed.
     */
    private static volatile String sDumpDisabled = null;

    /**
     * Global transaction tracker instance for this process.
     */
    private static volatile TransactionTracker sTransactionTracker = null;

    /**
     * Global observer for this process.
     */
    private static BinderInternal.Observer sObserver = null;

    /**
     * Guestimate of native memory associated with a Binder.
     */
    private static final int NATIVE_ALLOCATION_SIZE = 500;

    private static native long getNativeFinalizer();

    // Use a Holder to allow static initialization of Binder in the boot image, and
    // possibly to avoid some initialization ordering issues.
    private static class NoImagePreloadHolder {
        public static final NativeAllocationRegistry sRegistry = new NativeAllocationRegistry(
                Binder.class.getClassLoader(), getNativeFinalizer(), NATIVE_ALLOCATION_SIZE);
    }


    // Transaction tracking code.

    /**
     * Flag indicating whether we should be tracing transact calls.
     */
    private static volatile boolean sTracingEnabled = false;

    /**
     * Enable Binder IPC tracing.
     *
     * @hide
     */
    public static void enableTracing() {
        sTracingEnabled = true;
    }

    /**
     * Disable Binder IPC tracing.
     *
     * @hide
     */
    public static void disableTracing() {
        sTracingEnabled = false;
    }

    /**
     * Check if binder transaction tracing is enabled.
     *
     * @hide
     */
    public static boolean isTracingEnabled() {
        return sTracingEnabled;
    }

    /**
     * Get the binder transaction tracker for this process.
     *
     * @hide
     */
    public synchronized static TransactionTracker getTransactionTracker() {
        if (sTransactionTracker == null)
            sTransactionTracker = new TransactionTracker();
        return sTransactionTracker;
    }

    /**
     * Get the binder transaction observer for this process.
     *
     * @hide
     */
    public static void setObserver(@Nullable BinderInternal.Observer observer) {
        sObserver = observer;
    }

    /** {@hide} */
    static volatile boolean sWarnOnBlocking = false;

    /**
     * Warn if any blocking binder transactions are made out from this process.
     * This is typically only useful for the system process, to prevent it from
     * blocking on calls to external untrusted code. Instead, all outgoing calls
     * that require a result must be sent as {@link IBinder#FLAG_ONEWAY} calls
     * which deliver results through a callback interface.
     *
     * @hide
     */
    public static void setWarnOnBlocking(boolean warnOnBlocking) {
        sWarnOnBlocking = warnOnBlocking;
    }

    /**
     * Allow blocking calls on the given interface, overriding the requested
     * value of {@link #setWarnOnBlocking(boolean)}.
     * <p>
     * This should only be rarely called when you are <em>absolutely sure</em>
     * the remote interface is a built-in system component that can never be
     * upgraded. In particular, this <em>must never</em> be called for
     * interfaces hosted by package that could be upgraded or replaced,
     * otherwise you risk system instability if that remote interface wedges.
     *
     * @hide
     */
    public static IBinder allowBlocking(IBinder binder) {
        try {
            if (binder instanceof BinderProxy) {
                ((BinderProxy) binder).mWarnOnBlocking = false;
            } else if (binder != null && binder.getInterfaceDescriptor() != null
                    && binder.queryLocalInterface(binder.getInterfaceDescriptor()) == null) {
                Log.w(TAG, "Unable to allow blocking on interface " + binder);
            }
        } catch (RemoteException ignored) {
        }
        return binder;
    }

    /**
     * Reset the given interface back to the default blocking behavior,
     * reverting any changes made by {@link #allowBlocking(IBinder)}.
     *
     * @hide
     */
    public static IBinder defaultBlocking(IBinder binder) {
        if (binder instanceof BinderProxy) {
            ((BinderProxy) binder).mWarnOnBlocking = sWarnOnBlocking;
        }
        return binder;
    }

    /**
     * Inherit the current {@link #allowBlocking(IBinder)} value from one given
     * interface to another.
     *
     * @hide
     */
    public static void copyAllowBlocking(IBinder fromBinder, IBinder toBinder) {
        if (fromBinder instanceof BinderProxy && toBinder instanceof BinderProxy) {
            ((BinderProxy) toBinder).mWarnOnBlocking = ((BinderProxy) fromBinder).mWarnOnBlocking;
        }
    }

    /**
     * Raw native pointer to JavaBBinderHolder object. Owned by this Java object. Not null.
     */
    private final long mObject;

    private IInterface mOwner;
    private String mDescriptor;

    /**
     * Return the ID of the process that sent you the current transaction
     * that is being processed.  This pid can be used with higher-level
     * system services to determine its identity and check permissions.
     * If the current thread is not currently executing an incoming transaction,
     * then its own pid is returned.
     */
    @CriticalNative
    public static final native int getCallingPid();

    /**
     * Return the Linux uid assigned to the process that sent you the
     * current transaction that is being processed.  This uid can be used with
     * higher-level system services to determine its identity and check
     * permissions.  If the current thread is not currently executing an
     * incoming transaction, then its own uid is returned.
     */
    @CriticalNative
    public static final native int getCallingUid();

    /**
     * Returns {@code true} if the current thread is currently executing an
     * incoming transaction.
     *
     * @hide
     */
    @CriticalNative
    public static final native boolean isHandlingTransaction();

    /**
     * Return the Linux uid assigned to the process that sent the transaction
     * currently being processed.
     *
     * @throws IllegalStateException if the current thread is not currently
     *        executing an incoming transaction.
     */
    public static final int getCallingUidOrThrow() {
        if (!isHandlingTransaction()) {
            throw new IllegalStateException(
                  "Thread is not in a binder transcation");
        }
        return getCallingUid();
    }

    /**
     * Return the UserHandle assigned to the process that sent you the
     * current transaction that is being processed.  This is the user
     * of the caller.  It is distinct from {@link #getCallingUid()} in that a
     * particular user will have multiple distinct apps running under it each
     * with their own uid.  If the current thread is not currently executing an
     * incoming transaction, then its own UserHandle is returned.
     */
    public static final @NonNull UserHandle getCallingUserHandle() {
        return UserHandle.of(UserHandle.getUserId(getCallingUid()));
    }

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
    @CriticalNative
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
     * Convenience method for running the provided action enclosed in
     * {@link #clearCallingIdentity}/{@link #restoreCallingIdentity}
     *
     * Any exception thrown by the given action will be caught and rethrown after the call to
     * {@link #restoreCallingIdentity}
     *
     * @hide
     */
    public static final void withCleanCallingIdentity(@NonNull ThrowingRunnable action) {
        long callingIdentity = clearCallingIdentity();
        Throwable throwableToPropagate = null;
        try {
            action.runOrThrow();
        } catch (Throwable throwable) {
            throwableToPropagate = throwable;
        } finally {
            restoreCallingIdentity(callingIdentity);
            if (throwableToPropagate != null) {
                throw ExceptionUtils.propagate(throwableToPropagate);
            }
        }
    }

    /**
     * Convenience method for running the provided action enclosed in
     * {@link #clearCallingIdentity}/{@link #restoreCallingIdentity} returning the result
     *
     * Any exception thrown by the given action will be caught and rethrown after the call to
     * {@link #restoreCallingIdentity}
     *
     * @hide
     */
    public static final <T> T withCleanCallingIdentity(@NonNull ThrowingSupplier<T> action) {
        long callingIdentity = clearCallingIdentity();
        Throwable throwableToPropagate = null;
        try {
            return action.getOrThrow();
        } catch (Throwable throwable) {
            throwableToPropagate = throwable;
            return null; // overridden by throwing in finally block
        } finally {
            restoreCallingIdentity(callingIdentity);
            if (throwableToPropagate != null) {
                throw ExceptionUtils.propagate(throwableToPropagate);
            }
        }
    }

    /**
     * Sets the native thread-local StrictMode policy mask.
     *
     * <p>The StrictMode settings are kept in two places: a Java-level
     * threadlocal for libcore/Dalvik, and a native threadlocal (set
     * here) for propagation via Binder calls.  This is a little
     * unfortunate, but necessary to break otherwise more unfortunate
     * dependencies either of Dalvik on Android, or Android
     * native-only code on Dalvik.
     *
     * @see StrictMode
     * @hide
     */
    @CriticalNative
    public static final native void setThreadStrictModePolicy(int policyMask);

    /**
     * Gets the current native thread-local StrictMode policy mask.
     *
     * @see #setThreadStrictModePolicy
     * @hide
     */
    @CriticalNative
    public static final native int getThreadStrictModePolicy();

    /**
     * Sets the work source for this thread.
     *
     * <p>All the following binder calls on this thread will use the provided work source. If this
     * is called during an on-going binder transaction, all the following binder calls will use the
     * work source until the end of the transaction.
     *
     * <p>The concept of worksource is similar to {@link WorkSource}. However, for performance
     * reasons, we only support one UID. This UID represents the original user responsible for the
     * binder calls.
     *
     * <p>{@link Binder#restoreCallingWorkSource(long)} must always be called after setting the
     * worksource.
     *
     * <p>A typical use case would be
     * <pre>
     * long token = Binder.setCallingWorkSourceUid(uid);
     * try {
     *   // Call an API.
     * } finally {
     *   Binder.restoreCallingWorkSource(token);
     * }
     * </pre>
     *
     * <p>The work source will be propagated for future outgoing binder transactions
     * executed on this thread.
     *
     * @param workSource The original UID responsible for the binder call.
     * @return token to restore original work source.
     * @hide
     **/
    @CriticalNative
    @SystemApi
    public static final native long setCallingWorkSourceUid(int workSource);

    /**
     * Returns the work source set by the caller.
     *
     * Unlike {@link Binder#getCallingUid()}, this result of this method cannot be trusted. The
     * caller can set the value to whatever he wants. Only use this value if you trust the calling
     * uid.
     *
     * @return The original UID responsible for the binder transaction.
     * @hide
     */
    @CriticalNative
    @SystemApi
    public static final native int getCallingWorkSourceUid();

    /**
     * Clears the work source on this thread.
     *
     * <p>The work source will be propagated for future outgoing binder transactions
     * executed on this thread.
     *
     * <p>{@link Binder#restoreCallingWorkSource(long)} must always be called after clearing the
     * worksource.
     *
     * <p>A typical use case would be
     * <pre>
     * long token = Binder.clearCallingWorkSource();
     * try {
     *   // Call an API.
     * } finally {
     *   Binder.restoreCallingWorkSource(token);
     * }
     * </pre>
     *
     * @return token to restore original work source.
     * @hide
     **/
    @CriticalNative
    @SystemApi
    public static final native long clearCallingWorkSource();

    /**
     * Restores the work source on this thread using a token returned by
     * {@link #setCallingWorkSourceUid(int) or {@link clearCallingWorkSource()}.
     *
     * <p>A typical use case would be
     * <pre>
     * long token = Binder.setCallingWorkSourceUid(uid);
     * try {
     *   // Call an API.
     * } finally {
     *   Binder.restoreCallingWorkSource(token);
     * }
     * </pre>
     *
     * @hide
     **/
    @CriticalNative
    @SystemApi
    public static final native void restoreCallingWorkSource(long token);

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
    public static final void joinThreadPool() {
        BinderInternal.joinThreadPool();
    }

    /**
     * Returns true if the specified interface is a proxy.
     * @hide
     */
    public static final boolean isProxy(IInterface iface) {
        return iface.asBinder() != iface;
    }

    /**
     * Call blocks until the number of executing binder threads is less
     * than the maximum number of binder threads allowed for this process.
     * @hide
     */
    public static final native void blockUntilThreadAvailable();

    /**
     * Default constructor just initializes the object.
     *
     * If you're creating a Binder token (a Binder object without an attached interface),
     * you should use {@link #Binder(String)} instead.
     */
    public Binder() {
        this(null);
    }

    /**
     * Constructor for creating a raw Binder object (token) along with a descriptor.
     *
     * The descriptor of binder objects usually specifies the interface they are implementing.
     * In case of binder tokens, no interface is implemented, and the descriptor can be used
     * as a sort of tag to help identify the binder token. This will help identify remote
     * references to these objects more easily when debugging.
     *
     * @param descriptor Used to identify the creator of this token, for example the class name.
     * Instead of creating multiple tokens with the same descriptor, consider adding a suffix to
     * help identify them.
     */
    public Binder(@Nullable String descriptor)  {
        mObject = getNativeBBinderHolder();
        NoImagePreloadHolder.sRegistry.registerNativeAllocation(this, mObject);

        if (FIND_POTENTIAL_LEAKS) {
            final Class<? extends Binder> klass = getClass();
            if ((klass.isAnonymousClass() || klass.isMemberClass() || klass.isLocalClass()) &&
                    (klass.getModifiers() & Modifier.STATIC) == 0) {
                Log.w(TAG, "The following Binder class should be static or leaks might occur: " +
                    klass.getCanonicalName());
            }
        }
        mDescriptor = descriptor;
    }

    /**
     * Convenience method for associating a specific interface with the Binder.
     * After calling, queryLocalInterface() will be implemented for you
     * to return the given owner IInterface when the corresponding
     * descriptor is requested.
     */
    public void attachInterface(@Nullable IInterface owner, @Nullable String descriptor) {
        mOwner = owner;
        mDescriptor = descriptor;
    }

    /**
     * Default implementation returns an empty interface name.
     */
    public @Nullable String getInterfaceDescriptor() {
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
    public @Nullable IInterface queryLocalInterface(@NonNull String descriptor) {
        if (mDescriptor != null && mDescriptor.equals(descriptor)) {
            return mOwner;
        }
        return null;
    }

    /**
     * Control disabling of dump calls in this process.  This is used by the system
     * process watchdog to disable incoming dump calls while it has detecting the system
     * is hung and is reporting that back to the activity controller.  This is to
     * prevent the controller from getting hung up on bug reports at this point.
     * @hide
     *
     * @param msg The message to show instead of the dump; if null, dumps are
     * re-enabled.
     */
    public static void setDumpDisabled(String msg) {
        sDumpDisabled = msg;
    }

    /**
     * Listener to be notified about each proxy-side binder call.
     *
     * See {@link setProxyTransactListener}.
     * @hide
     */
    @SystemApi
    public interface ProxyTransactListener {
        /**
         * Called before onTransact.
         *
         * @return an object that will be passed back to #onTransactEnded (or null).
         */
        Object onTransactStarted(IBinder binder, int transactionCode);

        /**
         * Called after onTranact (even when an exception is thrown).
         *
         * @param session The object return by #onTransactStarted.
         */
        void onTransactEnded(@Nullable Object session);
    }

    /**
     * Propagates the work source to binder calls executed by the system server.
     *
     * <li>By default, this listener will propagate the worksource if the outgoing call happens on
     * the same thread as the incoming binder call.
     * <li>Custom attribution can be done by calling {@link ThreadLocalWorkSource#setUid(int)}.
     * @hide
     */
    public static class PropagateWorkSourceTransactListener implements ProxyTransactListener {
        @Override
        public Object onTransactStarted(IBinder binder, int transactionCode) {
           // Note that {@link Binder#getCallingUid()} is already set to the UID of the current
           // process when this method is called.
           //
           // We use ThreadLocalWorkSource instead. It also allows feature owners to set
           // {@link ThreadLocalWorkSource#set(int) manually to attribute resources to a UID.
            int uid = ThreadLocalWorkSource.getUid();
            if (uid != ThreadLocalWorkSource.UID_NONE) {
                return Binder.setCallingWorkSourceUid(uid);
            }
            return null;
        }

        @Override
        public void onTransactEnded(Object session) {
            if (session != null) {
                long token = (long) session;
                Binder.restoreCallingWorkSource(token);
            }
        }
    }

    /**
     * Sets a listener for the transact method on the proxy-side.
     *
     * <li>The listener is global. Only fast operations should be done to avoid thread
     * contentions.
     * <li>The listener implementation needs to handle synchronization if needed. The methods on the
     * listener can be called concurrently.
     * <li>Listener set will be used for new transactions. On-going transaction will still use the
     * previous listener (if already set).
     * <li>The listener is called on the critical path of the binder transaction so be careful about
     * performance.
     * <li>Never execute another binder transaction inside the listener.
     * @hide
     */
    @SystemApi
    public static void setProxyTransactListener(@Nullable ProxyTransactListener listener) {
        BinderProxy.setTransactListener(listener);
    }

    /**
     * Default implementation is a stub that returns false.  You will want
     * to override this to do the appropriate unmarshalling of transactions.
     *
     * <p>If you want to call this, call transact().
     *
     * <p>Implementations that are returning a result should generally use
     * {@link Parcel#writeNoException() Parcel.writeNoException} and
     * {@link Parcel#writeException(Exception) Parcel.writeException} to propagate
     * exceptions back to the caller.</p>
     *
     * @param code The action to perform.  This should
     * be a number between {@link #FIRST_CALL_TRANSACTION} and
     * {@link #LAST_CALL_TRANSACTION}.
     * @param data Marshalled data being received from the caller.
     * @param reply If the caller is expecting a result back, it should be marshalled
     * in to here.
     * @param flags Additional operation flags.  Either 0 for a normal
     * RPC, or {@link #FLAG_ONEWAY} for a one-way RPC.
     *
     * @return Return true on a successful call; returning false is generally used to
     * indicate that you did not understand the transaction code.
     */
    protected boolean onTransact(int code, @NonNull Parcel data, @Nullable Parcel reply,
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
                    IoUtils.closeQuietly(fd);
                }
            }
            // Write the StrictMode header.
            if (reply != null) {
                reply.writeNoException();
            } else {
                StrictMode.clearGatheredViolations();
            }
            return true;
        } else if (code == SHELL_COMMAND_TRANSACTION) {
            ParcelFileDescriptor in = data.readFileDescriptor();
            ParcelFileDescriptor out = data.readFileDescriptor();
            ParcelFileDescriptor err = data.readFileDescriptor();
            String[] args = data.readStringArray();
            ShellCallback shellCallback = ShellCallback.CREATOR.createFromParcel(data);
            ResultReceiver resultReceiver = ResultReceiver.CREATOR.createFromParcel(data);
            try {
                if (out != null) {
                    shellCommand(in != null ? in.getFileDescriptor() : null,
                            out.getFileDescriptor(),
                            err != null ? err.getFileDescriptor() : out.getFileDescriptor(),
                            args, shellCallback, resultReceiver);
                }
            } finally {
                IoUtils.closeQuietly(in);
                IoUtils.closeQuietly(out);
                IoUtils.closeQuietly(err);
                // Write the StrictMode header.
                if (reply != null) {
                    reply.writeNoException();
                } else {
                    StrictMode.clearGatheredViolations();
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Resolves a transaction code to a human readable name.
     *
     * <p>Default implementation is a stub that returns null.
     * <p>AIDL generated code will return the original method name.
     *
     * @param transactionCode The code to resolve.
     * @return A human readable name.
     * @hide
     */
    public @Nullable String getTransactionName(int transactionCode) {
        return null;
    }

    /**
     * Implemented to call the more convenient version
     * {@link #dump(FileDescriptor, PrintWriter, String[])}.
     */
    public void dump(@NonNull FileDescriptor fd, @Nullable String[] args) {
        FileOutputStream fout = new FileOutputStream(fd);
        PrintWriter pw = new FastPrintWriter(fout);
        try {
            doDump(fd, pw, args);
        } finally {
            pw.flush();
        }
    }

    void doDump(FileDescriptor fd, PrintWriter pw, String[] args) {
        final String disabled = sDumpDisabled;
        if (disabled == null) {
            try {
                dump(fd, pw, args);
            } catch (SecurityException e) {
                pw.println("Security exception: " + e.getMessage());
                throw e;
            } catch (Throwable e) {
                // Unlike usual calls, in this case if an exception gets thrown
                // back to us we want to print it back in to the dump data, since
                // that is where the caller expects all interesting information to
                // go.
                pw.println();
                pw.println("Exception occurred while dumping:");
                e.printStackTrace(pw);
            }
        } else {
            pw.println(sDumpDisabled);
        }
    }

    /**
     * Like {@link #dump(FileDescriptor, String[])}, but ensures the target
     * executes asynchronously.
     */
    public void dumpAsync(@NonNull final FileDescriptor fd, @Nullable final String[] args) {
        final FileOutputStream fout = new FileOutputStream(fd);
        final PrintWriter pw = new FastPrintWriter(fout);
        Thread thr = new Thread("Binder.dumpAsync") {
            public void run() {
                try {
                    dump(fd, pw, args);
                } finally {
                    pw.flush();
                }
            }
        };
        thr.start();
    }

    /**
     * Print the object's state into the given stream.
     *
     * @param fd The raw file descriptor that the dump is being sent to.
     * @param fout The file to which you should dump your state.  This will be
     * closed for you after you return.
     * @param args additional arguments to the dump request.
     */
    protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter fout,
            @Nullable String[] args) {
    }

    /**
     * @param in The raw file descriptor that an input data stream can be read from.
     * @param out The raw file descriptor that normal command messages should be written to.
     * @param err The raw file descriptor that command error messages should be written to.
     * @param args Command-line arguments.
     * @param callback Callback through which to interact with the invoking shell.
     * @param resultReceiver Called when the command has finished executing, with the result code.
     * @throws RemoteException
     * @hide
     */
    public void shellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
            @Nullable FileDescriptor err,
            @NonNull String[] args, @Nullable ShellCallback callback,
            @NonNull ResultReceiver resultReceiver) throws RemoteException {
        onShellCommand(in, out, err, args, callback, resultReceiver);
    }

    /**
     * Handle a call to {@link #shellCommand}.  The default implementation simply prints
     * an error message.  Override and replace with your own.
     * <p class="caution">Note: no permission checking is done before calling this method; you must
     * apply any security checks as appropriate for the command being executed.
     * Consider using {@link ShellCommand} to help in the implementation.</p>
     * @hide
     */
    public void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
            @Nullable FileDescriptor err,
            @NonNull String[] args, @Nullable ShellCallback callback,
            @NonNull ResultReceiver resultReceiver) throws RemoteException {
        FileOutputStream fout = new FileOutputStream(err != null ? err : out);
        PrintWriter pw = new FastPrintWriter(fout);
        pw.println("No shell command implementation.");
        pw.flush();
        resultReceiver.send(0, null);
    }

    /**
     * Default implementation rewinds the parcels and calls onTransact.  On
     * the remote side, transact calls into the binder to do the IPC.
     */
    public final boolean transact(int code, @NonNull Parcel data, @Nullable Parcel reply,
            int flags) throws RemoteException {
        if (false) Log.v("Binder", "Transact: " + code + " to " + this);

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
    public void linkToDeath(@NonNull DeathRecipient recipient, int flags) {
    }

    /**
     * Local implementation is a no-op.
     */
    public boolean unlinkToDeath(@NonNull DeathRecipient recipient, int flags) {
        return true;
    }

    static void checkParcel(IBinder obj, int code, Parcel parcel, String msg) {
        if (CHECK_PARCEL_SIZE && parcel.dataSize() >= 800*1024) {
            // Trying to send > 800k, this is way too much
            StringBuilder sb = new StringBuilder();
            sb.append(msg);
            sb.append(": on ");
            sb.append(obj);
            sb.append(" calling ");
            sb.append(code);
            sb.append(" size ");
            sb.append(parcel.dataSize());
            sb.append(" (data: ");
            parcel.setDataPosition(0);
            sb.append(parcel.readInt());
            sb.append(", ");
            sb.append(parcel.readInt());
            sb.append(", ");
            sb.append(parcel.readInt());
            sb.append(")");
            Slog.wtfStack(TAG, sb.toString());
        }
    }

    private static native long getNativeBBinderHolder();
    private static native long getFinalizer();

    /**
     * By default, we use the calling uid since we can always trust it.
     */
    private static volatile BinderInternal.WorkSourceProvider sWorkSourceProvider =
            Binder::getCallingUid;

    /**
     * Sets the work source provider.
     *
     * <li>The callback is global. Only fast operations should be done to avoid thread
     * contentions.
     * <li>The callback implementation needs to handle synchronization if needed. The methods on the
     * callback can be called concurrently.
     * <li>The callback is called on the critical path of the binder transaction so be careful about
     * performance.
     * <li>Never execute another binder transaction inside the callback.
     * @hide
     */
    public static void setWorkSourceProvider(BinderInternal.WorkSourceProvider workSourceProvider) {
        if (workSourceProvider == null) {
            throw new IllegalArgumentException("workSourceProvider cannot be null");
        }
        sWorkSourceProvider = workSourceProvider;
    }

    // Entry point from android_util_Binder.cpp's onTransact
    private boolean execTransact(int code, long dataObj, long replyObj,
            int flags) {
        final int workSourceUid = sWorkSourceProvider.resolveWorkSourceUid();
        final long origWorkSource = ThreadLocalWorkSource.setUid(workSourceUid);
        try {
            return execTransactInternal(code, dataObj, replyObj, flags, workSourceUid);
        } finally {
            ThreadLocalWorkSource.restore(origWorkSource);
        }
    }

    private boolean execTransactInternal(int code, long dataObj, long replyObj,
            int flags, int workSourceUid) {
        // Make sure the observer won't change while processing a transaction.
        final BinderInternal.Observer observer = sObserver;
        final CallSession callSession =
                observer != null ? observer.callStarted(this, code, workSourceUid) : null;
        Parcel data = Parcel.obtain(dataObj);
        Parcel reply = Parcel.obtain(replyObj);
        // theoretically, we should call transact, which will call onTransact,
        // but all that does is rewind it, and we just got these from an IPC,
        // so we'll just call it directly.
        boolean res;
        // Log any exceptions as warnings, don't silently suppress them.
        // If the call was FLAG_ONEWAY then these exceptions disappear into the ether.
        final boolean tracingEnabled = Binder.isTracingEnabled();
        try {
            if (tracingEnabled) {
                final String transactionName = getTransactionName(code);
                Trace.traceBegin(Trace.TRACE_TAG_ALWAYS, getClass().getName() + ":"
                        + (transactionName != null ? transactionName : code));
            }
            res = onTransact(code, data, reply, flags);
        } catch (RemoteException|RuntimeException e) {
            if (observer != null) {
                observer.callThrewException(callSession, e);
            }
            if (LOG_RUNTIME_EXCEPTION) {
                Log.w(TAG, "Caught a RuntimeException from the binder stub implementation.", e);
            }
            if ((flags & FLAG_ONEWAY) != 0) {
                if (e instanceof RemoteException) {
                    Log.w(TAG, "Binder call failed.", e);
                } else {
                    Log.w(TAG, "Caught a RuntimeException from the binder stub implementation.", e);
                }
            } else {
                reply.setDataPosition(0);
                reply.writeException(e);
            }
            res = true;
        } finally {
            if (tracingEnabled) {
                Trace.traceEnd(Trace.TRACE_TAG_ALWAYS);
            }
            if (observer != null) {
                observer.callEnded(callSession, data.dataSize(), reply.dataSize(), workSourceUid);
            }
        }
        checkParcel(this, code, reply, "Unreasonably large binder reply buffer");
        reply.recycle();
        data.recycle();

        // Just in case -- we are done with the IPC, so there should be no more strict
        // mode violations that have gathered for this thread.  Either they have been
        // parceled and are now in transport off to the caller, or we are returning back
        // to the main transaction loop to wait for another incoming transaction.  Either
        // way, strict mode begone!
        StrictMode.clearGatheredViolations();
        return res;
    }
}
