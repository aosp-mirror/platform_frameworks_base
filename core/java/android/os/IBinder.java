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
import android.compat.annotation.UnsupportedAppUsage;

import java.io.FileDescriptor;

/**
 * Base interface for a remotable object, the core part of a lightweight
 * remote procedure call mechanism designed for high performance when
 * performing in-process and cross-process calls.  This
 * interface describes the abstract protocol for interacting with a
 * remotable object.  Do not implement this interface directly, instead
 * extend from {@link Binder}.
 * 
 * <p>The key IBinder API is {@link #transact transact()} matched by
 * {@link Binder#onTransact Binder.onTransact()}.  These
 * methods allow you to send a call to an IBinder object and receive a
 * call coming in to a Binder object, respectively.  This transaction API
 * is synchronous, such that a call to {@link #transact transact()} does not
 * return until the target has returned from
 * {@link Binder#onTransact Binder.onTransact()}; this is the
 * expected behavior when calling an object that exists in the local
 * process, and the underlying inter-process communication (IPC) mechanism
 * ensures that these same semantics apply when going across processes.
 * 
 * <p>The data sent through transact() is a {@link Parcel}, a generic buffer
 * of data that also maintains some meta-data about its contents.  The meta
 * data is used to manage IBinder object references in the buffer, so that those
 * references can be maintained as the buffer moves across processes.  This
 * mechanism ensures that when an IBinder is written into a Parcel and sent to
 * another process, if that other process sends a reference to that same IBinder
 * back to the original process, then the original process will receive the
 * same IBinder object back.  These semantics allow IBinder/Binder objects to
 * be used as a unique identity (to serve as a token or for other purposes)
 * that can be managed across processes.
 * 
 * <p>The system maintains a pool of transaction threads in each process that
 * it runs in.  These threads are used to dispatch all
 * IPCs coming in from other processes.  For example, when an IPC is made from
 * process A to process B, the calling thread in A blocks in transact() as
 * it sends the transaction to process B.  The next available pool thread in
 * B receives the incoming transaction, calls Binder.onTransact() on the target
 * object, and replies with the result Parcel.  Upon receiving its result, the
 * thread in process A returns to allow its execution to continue.  In effect,
 * other processes appear to use as additional threads that you did not create
 * executing in your own process.
 * 
 * <p>The Binder system also supports recursion across processes.  For example
 * if process A performs a transaction to process B, and process B while
 * handling that transaction calls transact() on an IBinder that is implemented
 * in A, then the thread in A that is currently waiting for the original
 * transaction to finish will take care of calling Binder.onTransact() on the
 * object being called by B.  This ensures that the recursion semantics when
 * calling remote binder object are the same as when calling local objects.
 * 
 * <p>When working with remote objects, you often want to find out when they
 * are no longer valid.  There are three ways this can be determined:
 * <ul>
 * <li> The {@link #transact transact()} method will throw a
 * {@link RemoteException} exception if you try to call it on an IBinder
 * whose process no longer exists.
 * <li> The {@link #pingBinder()} method can be called, and will return false
 * if the remote process no longer exists.
 * <li> The {@link #linkToDeath linkToDeath()} method can be used to register
 * a {@link DeathRecipient} with the IBinder, which will be called when its
 * containing process goes away.
 * </ul>
 * 
 * @see Binder
 */
public interface IBinder {
    /**
     * The first transaction code available for user commands.
     */
    int FIRST_CALL_TRANSACTION  = 0x00000001;
    /**
     * The last transaction code available for user commands.
     */
    int LAST_CALL_TRANSACTION   = 0x00ffffff;
    
    /**
     * IBinder protocol transaction code: pingBinder().
     */
    int PING_TRANSACTION        = ('_'<<24)|('P'<<16)|('N'<<8)|'G';
    
    /**
     * IBinder protocol transaction code: dump internal state.
     */
    int DUMP_TRANSACTION        = ('_'<<24)|('D'<<16)|('M'<<8)|'P';
    
    /**
     * IBinder protocol transaction code: execute a shell command.
     * @hide
     */
    int SHELL_COMMAND_TRANSACTION = ('_'<<24)|('C'<<16)|('M'<<8)|'D';

    /**
     * IBinder protocol transaction code: interrogate the recipient side
     * of the transaction for its canonical interface descriptor.
     */
    int INTERFACE_TRANSACTION   = ('_'<<24)|('N'<<16)|('T'<<8)|'F';

    /**
     * IBinder protocol transaction code: send a tweet to the target
     * object.  The data in the parcel is intended to be delivered to
     * a shared messaging service associated with the object; it can be
     * anything, as long as it is not more than 130 UTF-8 characters to
     * conservatively fit within common messaging services.  As part of
     * {@link Build.VERSION_CODES#HONEYCOMB_MR2}, all Binder objects are
     * expected to support this protocol for fully integrated tweeting
     * across the platform.  To support older code, the default implementation
     * logs the tweet to the main log as a simple emulation of broadcasting
     * it publicly over the Internet.
     * 
     * <p>Also, upon completing the dispatch, the object must make a cup
     * of tea, return it to the caller, and exclaim "jolly good message
     * old boy!".
     */
    int TWEET_TRANSACTION   = ('_'<<24)|('T'<<16)|('W'<<8)|'T';

    /**
     * IBinder protocol transaction code: tell an app asynchronously that the
     * caller likes it.  The app is responsible for incrementing and maintaining
     * its own like counter, and may display this value to the user to indicate the
     * quality of the app.  This is an optional command that applications do not
     * need to handle, so the default implementation is to do nothing.
     * 
     * <p>There is no response returned and nothing about the
     * system will be functionally affected by it, but it will improve the
     * app's self-esteem.
     */
    int LIKE_TRANSACTION   = ('_'<<24)|('L'<<16)|('I'<<8)|'K';

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    int SYSPROPS_TRANSACTION = ('_'<<24)|('S'<<16)|('P'<<8)|'R';

    /**
     * Flag to {@link #transact}: this is a one-way call, meaning that the
     * caller returns immediately, without waiting for a result from the
     * callee. Applies only if the caller and callee are in different
     * processes.
     *
     * <p>The system provides special ordering semantics for multiple oneway calls
     * being made to the same IBinder object: these calls will be dispatched in the
     * other process one at a time, with the same order as the original calls.  These
     * are still dispatched by the IPC thread pool, so may execute on different threads,
     * but the next one will not be dispatched until the previous one completes.  This
     * ordering is not guaranteed for calls on different IBinder objects or when mixing
     * oneway and non-oneway calls on the same IBinder object.</p>
     */
    int FLAG_ONEWAY             = 0x00000001;

    /**
     * Flag to {@link #transact}: request binder driver to clear transaction data.
     *
     * Be very careful when using this flag in Java, since Java objects read from a Java
     * Parcel may be non-trivial to clear.
     * @hide
     */
    int FLAG_CLEAR_BUF          = 0x00000020;

    /**
     * @hide
     */
    int FLAG_COLLECT_NOTED_APP_OPS = 0x00000002;

    /**
     * Limit that should be placed on IPC sizes to keep them safely under the
     * transaction buffer limit.
     * @hide
     */
    public static final int MAX_IPC_SIZE = 64 * 1024;

    /**
     * Limit that should be placed on IPC sizes, in bytes, to keep them safely under the transaction
     * buffer limit.
     */
    static int getSuggestedMaxIpcSizeBytes() {
        return MAX_IPC_SIZE;
    }

    /**
     * Get the canonical name of the interface supported by this binder.
     */
    public @Nullable String getInterfaceDescriptor() throws RemoteException;

    /**
     * Check to see if the object still exists.
     * 
     * @return Returns false if the
     * hosting process is gone, otherwise the result (always by default
     * true) returned by the pingBinder() implementation on the other
     * side.
     */
    public boolean pingBinder();

    /**
     * Check to see if the process that the binder is in is still alive.
     *
     * @return false if the process is not alive.  Note that if it returns
     * true, the process may have died while the call is returning.
     */
    public boolean isBinderAlive();
    
    /**
     * Attempt to retrieve a local implementation of an interface
     * for this Binder object.  If null is returned, you will need
     * to instantiate a proxy class to marshall calls through
     * the transact() method.
     */
    public @Nullable IInterface queryLocalInterface(@NonNull String descriptor);

    /**
     * Print the object's state into the given stream.
     * 
     * @param fd The raw file descriptor that the dump is being sent to.
     * @param args additional arguments to the dump request.
     */
    public void dump(@NonNull FileDescriptor fd, @Nullable String[] args) throws RemoteException;

    /**
     * Like {@link #dump(FileDescriptor, String[])} but always executes
     * asynchronously.  If the object is local, a new thread is created
     * to perform the dump.
     *
     * @param fd The raw file descriptor that the dump is being sent to.
     * @param args additional arguments to the dump request.
     */
    public void dumpAsync(@NonNull FileDescriptor fd, @Nullable String[] args)
            throws RemoteException;

    /**
     * Execute a shell command on this object.  This may be performed asynchrously from the caller;
     * the implementation must always call resultReceiver when finished.
     *
     * @param in The raw file descriptor that an input data stream can be read from.
     * @param out The raw file descriptor that normal command messages should be written to.
     * @param err The raw file descriptor that command error messages should be written to.
     * @param args Command-line arguments.
     * @param shellCallback Optional callback to the caller's shell to perform operations in it.
     * @param resultReceiver Called when the command has finished executing, with the result code.
     * @hide
     */
    public void shellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
            @Nullable FileDescriptor err,
            @NonNull String[] args, @Nullable ShellCallback shellCallback,
            @NonNull ResultReceiver resultReceiver) throws RemoteException;

    /**
     * Get the binder extension of this binder interface.
     * This allows one to customize an interface without having to modify the original interface.
     *
     * @return null if don't have binder extension
     * @throws RemoteException
     * @hide
     */
    public default @Nullable IBinder getExtension() throws RemoteException {
        throw new IllegalStateException("Method is not implemented");
    }

    /**
     * Perform a generic operation with the object.
     * 
     * @param code The action to perform.  This should
     * be a number between {@link #FIRST_CALL_TRANSACTION} and
     * {@link #LAST_CALL_TRANSACTION}.
     * @param data Marshalled data to send to the target.  Must not be null.
     * If you are not sending any data, you must create an empty Parcel
     * that is given here.
     * @param reply Marshalled data to be received from the target.  May be
     * null if you are not interested in the return value.
     * @param flags Additional operation flags.  Either 0 for a normal
     * RPC, or {@link #FLAG_ONEWAY} for a one-way RPC.
     *
     * @return Returns the result from {@link Binder#onTransact}.  A successful call
     * generally returns true; false generally means the transaction code was not
     * understood.  For a oneway call to a different process false should never be
     * returned.  If a oneway call is made to code in the same process (usually to
     * a C++ or Rust implementation), then there are no oneway semantics, and
     * false can still be returned.
     */
    public boolean transact(int code, @NonNull Parcel data, @Nullable Parcel reply, int flags)
        throws RemoteException;

    /**
     * Interface for receiving a callback when the process hosting an IBinder
     * has gone away.
     * 
     * @see #linkToDeath
     */
    public interface DeathRecipient {
        public void binderDied();

        /**
         * @hide
         */
        default void binderDied(IBinder who) {
            binderDied();
        }
    }

    /**
     * Register the recipient for a notification if this binder
     * goes away.  If this binder object unexpectedly goes away
     * (typically because its hosting process has been killed),
     * then the given {@link DeathRecipient}'s
     * {@link DeathRecipient#binderDied DeathRecipient.binderDied()} method
     * will be called.
     *
     * <p>This will automatically be unlinked when all references to the linked
     * binder proxy are dropped.</p>
     *
     * <p>You will only receive death notifications for remote binders,
     * as local binders by definition can't die without you dying as well.</p>
     *
     * @throws RemoteException if the target IBinder's
     * process has already died.
     *
     * @see #unlinkToDeath
     */
    public void linkToDeath(@NonNull DeathRecipient recipient, int flags)
            throws RemoteException;

    /**
     * Remove a previously registered death notification.
     * The recipient will no longer be called if this object
     * dies.
     * 
     * @return {@code true} if the <var>recipient</var> is successfully
     * unlinked, assuring you that its
     * {@link DeathRecipient#binderDied DeathRecipient.binderDied()} method
     * will not be called;  {@code false} if the target IBinder has already
     * died, meaning the method has been (or soon will be) called.
     * 
     * @throws java.util.NoSuchElementException if the given
     * <var>recipient</var> has not been registered with the IBinder, and
     * the IBinder is still alive.  Note that if the <var>recipient</var>
     * was never registered, but the IBinder has already died, then this
     * exception will <em>not</em> be thrown, and you will receive a false
     * return value instead.
     */
    public boolean unlinkToDeath(@NonNull DeathRecipient recipient, int flags);
}
