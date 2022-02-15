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
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.util.Log;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BinderInternal;

import libcore.util.NativeAllocationRegistry;

import java.io.FileDescriptor;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Java proxy for a native IBinder object.
 * Allocated and constructed by the native javaObjectforIBinder function. Never allocated
 * directly from Java code.
 *
 * @hide
 */
public final class BinderProxy implements IBinder {
    // See android_util_Binder.cpp for the native half of this.

    // Assume the process-wide default value when created
    volatile boolean mWarnOnBlocking = Binder.sWarnOnBlocking;

    private static volatile Binder.ProxyTransactListener sTransactListener = null;

    private static class BinderProxyMapSizeException extends AssertionError {
        BinderProxyMapSizeException(String s) {
            super(s);
        }
    };

    /**
     * @see {@link Binder#setProxyTransactListener(listener)}.
     */
    public static void setTransactListener(@Nullable Binder.ProxyTransactListener listener) {
        sTransactListener = listener;
    }

    /*
     * Map from longs to BinderProxy, retaining only a WeakReference to the BinderProxies.
     * We roll our own only because we need to lazily remove WeakReferences during accesses
     * to avoid accumulating junk WeakReference objects. WeakHashMap isn't easily usable
     * because we want weak values, not keys.
     * Our hash table is never resized, but the number of entries is unlimited;
     * performance degrades as occupancy increases significantly past MAIN_INDEX_SIZE.
     * Not thread-safe. Client ensures there's a single access at a time.
     */
    private static final class ProxyMap {
        private static final int LOG_MAIN_INDEX_SIZE = 8;
        private static final int MAIN_INDEX_SIZE = 1 <<  LOG_MAIN_INDEX_SIZE;
        private static final int MAIN_INDEX_MASK = MAIN_INDEX_SIZE - 1;
        /**
         * Debuggable builds will throw an BinderProxyMapSizeException if the number of
         * map entries exceeds:
         */
        private static final int CRASH_AT_SIZE = 25_000;

        /**
         * We next warn when we exceed this bucket size.
         */
        private int mWarnBucketSize = 20;

        /**
         * Increment mWarnBucketSize by WARN_INCREMENT each time we warn.
         */
        private static final int WARN_INCREMENT = 10;

        /**
         * Hash function tailored to native pointers.
         * Returns a value < MAIN_INDEX_SIZE.
         */
        private static int hash(long arg) {
            return ((int) ((arg >> 2) ^ (arg >> (2 + LOG_MAIN_INDEX_SIZE)))) & MAIN_INDEX_MASK;
        }

        /**
         * Return the total number of pairs in the map.
         */
        private int size() {
            int size = 0;
            for (ArrayList<WeakReference<BinderProxy>> a : mMainIndexValues) {
                if (a != null) {
                    size += a.size();
                }
            }
            return size;
        }

        /**
         * Return the total number of pairs in the map containing values that have
         * not been cleared. More expensive than the above size function.
         */
        private int unclearedSize() {
            int size = 0;
            for (ArrayList<WeakReference<BinderProxy>> a : mMainIndexValues) {
                if (a != null) {
                    for (WeakReference<BinderProxy> ref : a) {
                        if (!ref.refersTo(null)) {
                            ++size;
                        }
                    }
                }
            }
            return size;
        }

        /**
         * Remove ith entry from the hash bucket indicated by hash.
         */
        private void remove(int hash, int index) {
            Long[] keyArray = mMainIndexKeys[hash];
            ArrayList<WeakReference<BinderProxy>> valueArray = mMainIndexValues[hash];
            int size = valueArray.size();  // KeyArray may have extra elements.
            // Move last entry into empty slot, and truncate at end.
            if (index != size - 1) {
                keyArray[index] = keyArray[size - 1];
                valueArray.set(index, valueArray.get(size - 1));
            }
            valueArray.remove(size - 1);
            // Just leave key array entry; it's unused. We only trust the valueArray size.
        }

        /**
         * Look up the supplied key. If we have a non-cleared entry for it, return it.
         */
        BinderProxy get(long key) {
            int myHash = hash(key);
            Long[] keyArray = mMainIndexKeys[myHash];
            if (keyArray == null) {
                return null;
            }
            ArrayList<WeakReference<BinderProxy>> valueArray = mMainIndexValues[myHash];
            int bucketSize = valueArray.size();
            for (int i = 0; i < bucketSize; ++i) {
                long foundKey = keyArray[i];
                if (key == foundKey) {
                    WeakReference<BinderProxy> wr = valueArray.get(i);
                    BinderProxy bp = wr.get();
                    if (bp != null) {
                        return bp;
                    } else {
                        remove(myHash, i);
                        return null;
                    }
                }
            }
            return null;
        }

        private int mRandom;  // A counter used to generate a "random" index. World's 2nd worst RNG.

        /**
         * Add the key-value pair to the map.
         * Requires that the indicated key is not already in the map.
         */
        void set(long key, @NonNull BinderProxy value) {
            int myHash = hash(key);
            ArrayList<WeakReference<BinderProxy>> valueArray = mMainIndexValues[myHash];
            if (valueArray == null) {
                valueArray = mMainIndexValues[myHash] = new ArrayList<>();
                mMainIndexKeys[myHash] = new Long[1];
            }
            int size = valueArray.size();
            WeakReference<BinderProxy> newWr = new WeakReference<>(value);
            // First look for a cleared reference.
            // This ensures that ArrayList size is bounded by the maximum occupancy of
            // that bucket.
            for (int i = 0; i < size; ++i) {
                if (valueArray.get(i).refersTo(null)) {
                    valueArray.set(i, newWr);
                    Long[] keyArray = mMainIndexKeys[myHash];
                    keyArray[i] = key;
                    if (i < size - 1) {
                        // "Randomly" check one of the remaining entries in [i+1, size), so that
                        // needlessly long buckets are eventually pruned.
                        int rnd = Math.floorMod(++mRandom, size - (i + 1));
                        if (valueArray.get(i + 1 + rnd).refersTo(null)) {
                            remove(myHash, i + 1 + rnd);
                        }
                    }
                    return;
                }
            }
            valueArray.add(size, newWr);
            Long[] keyArray = mMainIndexKeys[myHash];
            if (keyArray.length == size) {
                // size >= 1, since we initially allocated one element
                Long[] newArray = new Long[size + size / 2 + 2];
                System.arraycopy(keyArray, 0, newArray, 0, size);
                newArray[size] = key;
                mMainIndexKeys[myHash] = newArray;
            } else {
                keyArray[size] = key;
            }
            if (size >= mWarnBucketSize) {
                final int totalSize = size();
                Log.v(Binder.TAG, "BinderProxy map growth! bucket size = " + size
                        + " total = " + totalSize);
                mWarnBucketSize += WARN_INCREMENT;
                if (Build.IS_DEBUGGABLE && totalSize >= CRASH_AT_SIZE) {
                    // Use the number of uncleared entries to determine whether we should
                    // really report a histogram and crash. We don't want to fundamentally
                    // change behavior for a debuggable process, so we GC only if we are
                    // about to crash.
                    final int totalUnclearedSize = unclearedSize();
                    if (totalUnclearedSize >= CRASH_AT_SIZE) {
                        dumpProxyInterfaceCounts();
                        dumpPerUidProxyCounts();
                        Runtime.getRuntime().gc();
                        throw new BinderProxyMapSizeException(
                                "Binder ProxyMap has too many entries: "
                                + totalSize + " (total), " + totalUnclearedSize + " (uncleared), "
                                + unclearedSize() + " (uncleared after GC). BinderProxy leak?");
                    } else if (totalSize > 3 * totalUnclearedSize / 2) {
                        Log.v(Binder.TAG, "BinderProxy map has many cleared entries: "
                                + (totalSize - totalUnclearedSize) + " of " + totalSize
                                + " are cleared");
                    }
                }
            }
        }

        private InterfaceCount[] getSortedInterfaceCounts(int maxToReturn) {
            if (maxToReturn < 0) {
                throw new IllegalArgumentException("negative interface count");
            }

            Map<String, Integer> counts = new HashMap<>();
            final ArrayList<WeakReference<BinderProxy>> proxiesToQuery =
                    new ArrayList<WeakReference<BinderProxy>>();
            synchronized (sProxyMap) {
                for (ArrayList<WeakReference<BinderProxy>> a : mMainIndexValues) {
                    if (a != null) {
                        proxiesToQuery.addAll(a);
                    }
                }
            }
            // For gathering this debug output, we're making synchronous binder calls
            // out of system_server to all processes hosting binder objects it holds a reference to;
            // since some of those processes might be frozen, we don't want to block here
            // forever. Disable the freezer.
            try {
                ActivityManager.getService().enableAppFreezer(false);
            } catch (RemoteException e) {
                Log.e(Binder.TAG, "RemoteException while disabling app freezer");
            }

            // We run the dump on a separate thread, because there are known cases where
            // a process overrides getInterfaceDescriptor() and somehow blocks on it, causing
            // the calling thread (usually AMS) to hit the watchdog.
            // Do the dumping on a separate thread instead, and give up after a while.
            ExecutorService executorService = Executors.newSingleThreadExecutor();
            executorService.submit(() -> {
                for (WeakReference<BinderProxy> weakRef : proxiesToQuery) {
                    BinderProxy bp = weakRef.get();
                    String key;
                    if (bp == null) {
                        key = "<cleared weak-ref>";
                    } else {
                        try {
                            key = bp.getInterfaceDescriptor();
                            if ((key == null || key.isEmpty()) && !bp.isBinderAlive()) {
                                key = "<proxy to dead node>";
                            }
                        } catch (Throwable t) {
                            key = "<exception during getDescriptor>";
                        }
                    }
                    Integer i = counts.get(key);
                    if (i == null) {
                        counts.put(key, 1);
                    } else {
                        counts.put(key, i + 1);
                    }
                }
            });

            try {
                executorService.shutdown();
                boolean dumpDone = executorService.awaitTermination(20, TimeUnit.SECONDS);
                if (!dumpDone) {
                    Log.e(Binder.TAG, "Failed to complete binder proxy dump,"
                            + " dumping what we have so far.");
                }
            } catch (InterruptedException e) {
                // Ignore
            }
            try {
                ActivityManager.getService().enableAppFreezer(true);
            } catch (RemoteException e) {
                Log.e(Binder.TAG, "RemoteException while re-enabling app freezer");
            }
            Map.Entry<String, Integer>[] sorted = counts.entrySet().toArray(
                    new Map.Entry[counts.size()]);

            Arrays.sort(sorted, (Map.Entry<String, Integer> a, Map.Entry<String, Integer> b)
                    -> b.getValue().compareTo(a.getValue()));

            int returnCount = Math.min(maxToReturn, sorted.length);
            InterfaceCount[] ifaceCounts = new InterfaceCount[returnCount];
            for (int i = 0; i < returnCount; i++) {
                ifaceCounts[i] = new InterfaceCount(sorted[i].getKey(), sorted[i].getValue());
            }
            return ifaceCounts;
        }

        static final int MAX_NUM_INTERFACES_TO_DUMP = 10;

        /**
         * Dump a histogram to the logcat. Used to diagnose abnormally large proxy maps.
         */
        private void dumpProxyInterfaceCounts() {
            final InterfaceCount[] sorted = getSortedInterfaceCounts(MAX_NUM_INTERFACES_TO_DUMP);

            Log.v(Binder.TAG, "BinderProxy descriptor histogram "
                    + "(top " + Integer.toString(MAX_NUM_INTERFACES_TO_DUMP) + "):");
            for (int i = 0; i < sorted.length; i++) {
                Log.v(Binder.TAG, " #" + (i + 1) + ": " + sorted[i]);
            }
        }

        /**
         * Dump per uid binder proxy counts to the logcat.
         */
        private void dumpPerUidProxyCounts() {
            SparseIntArray counts = BinderInternal.nGetBinderProxyPerUidCounts();
            if (counts.size() == 0) return;
            Log.d(Binder.TAG, "Per Uid Binder Proxy Counts:");
            for (int i = 0; i < counts.size(); i++) {
                final int uid = counts.keyAt(i);
                final int binderCount = counts.valueAt(i);
                Log.d(Binder.TAG, "UID : " + uid + "  count = " + binderCount);
            }
        }

        // Corresponding ArrayLists in the following two arrays always have the same size.
        // They contain no empty entries. However WeakReferences in the values ArrayLists
        // may have been cleared.

        // mMainIndexKeys[i][j] corresponds to mMainIndexValues[i].get(j) .
        // The values ArrayList has the proper size(), the corresponding keys array
        // is always at least the same size, but may be larger.
        // If either a particular keys array, or the corresponding values ArrayList
        // are null, then they both are.
        private final Long[][] mMainIndexKeys = new Long[MAIN_INDEX_SIZE][];
        private final ArrayList<WeakReference<BinderProxy>>[] mMainIndexValues =
                new ArrayList[MAIN_INDEX_SIZE];
    }

    @GuardedBy("sProxyMap")
    private static final ProxyMap sProxyMap = new ProxyMap();

    /**
     * Simple pair-value class to store number of binder proxy interfaces live in this process.
     */
    public static final class InterfaceCount {
        private final String mInterfaceName;
        private final int mCount;

        InterfaceCount(String interfaceName, int count) {
            mInterfaceName = interfaceName;
            mCount = count;
        }

        @Override
        public String toString() {
            return mInterfaceName + " x" + Integer.toString(mCount);
        }
    }

    /**
     * Get a sorted array with entries mapping proxy interface names to the number
     * of live proxies with those names.
     *
     * @param num maximum number of proxy interface counts to return. Use
     *            Integer.MAX_VALUE to retrieve all
     * @hide
     */
    public static InterfaceCount[] getSortedInterfaceCounts(int num) {
        return sProxyMap.getSortedInterfaceCounts(num);
    }

    /**
     * Returns the number of binder proxies held in this process.
     * @return number of binder proxies in this process
     */
    public static int getProxyCount() {
        synchronized (sProxyMap) {
            return sProxyMap.size();
        }
    }

    /**
     * Dump proxy debug information.
     *
     * @hide
     */
    public static void dumpProxyDebugInfo() {
        if (Build.IS_DEBUGGABLE) {
            sProxyMap.dumpProxyInterfaceCounts();
            sProxyMap.dumpPerUidProxyCounts();
        }
    }

    /**
     * Return a BinderProxy for IBinder.
     * If we previously returned a BinderProxy bp for the same iBinder, and bp is still
     * in use, then we return the same bp.
     *
     * @param nativeData C++ pointer to (possibly still empty) BinderProxyNativeData.
     * Takes ownership of nativeData iff <result>.mNativeData == nativeData, or if
     * we exit via an exception.  If neither applies, it's the callers responsibility to
     * recycle nativeData.
     * @param iBinder C++ pointer to IBinder. Does not take ownership of referenced object.
     */
    private static BinderProxy getInstance(long nativeData, long iBinder) {
        BinderProxy result;
        synchronized (sProxyMap) {
            try {
                result = sProxyMap.get(iBinder);
                if (result != null) {
                    return result;
                }
                result = new BinderProxy(nativeData);
            } catch (Throwable e) {
                // We're throwing an exception (probably OOME); don't drop nativeData.
                NativeAllocationRegistry.applyFreeFunction(NoImagePreloadHolder.sNativeFinalizer,
                        nativeData);
                throw e;
            }
            NoImagePreloadHolder.sRegistry.registerNativeAllocation(result, nativeData);
            // The registry now owns nativeData, even if registration threw an exception.
            sProxyMap.set(iBinder, result);
        }
        return result;
    }

    private BinderProxy(long nativeData) {
        mNativeData = nativeData;
    }

    /**
     * Guestimate of native memory associated with a BinderProxy.
     * This includes the underlying IBinder, associated DeathRecipientList, and KeyedVector
     * that points back to us. We guess high since it includes a GlobalRef, which
     * may be in short supply.
     */
    private static final int NATIVE_ALLOCATION_SIZE = 1000;

    // Use a Holder to allow static initialization of BinderProxy in the boot image, and
    // to avoid some initialization ordering issues.
    private static class NoImagePreloadHolder {
        public static final long sNativeFinalizer = getNativeFinalizer();
        public static final NativeAllocationRegistry sRegistry = new NativeAllocationRegistry(
                BinderProxy.class.getClassLoader(), sNativeFinalizer, NATIVE_ALLOCATION_SIZE);
    }

    /**
     * @return false if the hosting process is gone, otherwise whatever the remote returns
     */
    public native boolean pingBinder();

    /**
     * @return false if the hosting process is gone
     */
    public native boolean isBinderAlive();

    /**
     * Retrieve a local interface - always null in case of a proxy
     */
    public IInterface queryLocalInterface(String descriptor) {
        return null;
    }

    /** @hide */
    @Override
    public native @Nullable IBinder getExtension() throws RemoteException;

    /**
     * Perform a binder transaction on a proxy.
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
     * @return
     * @throws RemoteException
     */
    public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        Binder.checkParcel(this, code, data, "Unreasonably large binder buffer");

        boolean warnOnBlocking = mWarnOnBlocking; // Cache it to reduce volatile access.

        if (warnOnBlocking && ((flags & FLAG_ONEWAY) == 0)
                && Binder.sWarnOnBlockingOnCurrentThread.get()) {

            // For now, avoid spamming the log by disabling after we've logged
            // about this interface at least once
            mWarnOnBlocking = false;
            warnOnBlocking = false;

            if (Build.IS_USERDEBUG) {
                // Log this as a WTF on userdebug builds.
                Log.wtf(Binder.TAG,
                        "Outgoing transactions from this process must be FLAG_ONEWAY",
                        new Throwable());
            } else {
                Log.w(Binder.TAG,
                        "Outgoing transactions from this process must be FLAG_ONEWAY",
                        new Throwable());
            }
        }

        final boolean tracingEnabled = Binder.isStackTrackingEnabled();
        if (tracingEnabled) {
            final Throwable tr = new Throwable();
            Binder.getTransactionTracker().addTrace(tr);
            StackTraceElement stackTraceElement = tr.getStackTrace()[1];
            Trace.traceBegin(Trace.TRACE_TAG_ALWAYS,
                    stackTraceElement.getClassName() + "." + stackTraceElement.getMethodName());
        }

        // Make sure the listener won't change while processing a transaction.
        final Binder.ProxyTransactListener transactListener = sTransactListener;
        Object session = null;

        if (transactListener != null) {
            final int origWorkSourceUid = Binder.getCallingWorkSourceUid();
            session = transactListener.onTransactStarted(this, code, flags);

            // Allow the listener to update the work source uid. We need to update the request
            // header if the uid is updated.
            final int updatedWorkSourceUid = Binder.getCallingWorkSourceUid();
            if (origWorkSourceUid != updatedWorkSourceUid) {
                data.replaceCallingWorkSourceUid(updatedWorkSourceUid);
            }
        }

        final AppOpsManager.PausedNotedAppOpsCollection prevCollection =
                AppOpsManager.pauseNotedAppOpsCollection();

        if ((flags & FLAG_ONEWAY) == 0 && AppOpsManager.isListeningForOpNoted()) {
            flags |= FLAG_COLLECT_NOTED_APP_OPS;
        }

        try {
            final boolean result = transactNative(code, data, reply, flags);

            if (reply != null && !warnOnBlocking) {
                reply.addFlags(Parcel.FLAG_IS_REPLY_FROM_BLOCKING_ALLOWED_OBJECT);
            }

            return result;
        } finally {
            AppOpsManager.resumeNotedAppOpsCollection(prevCollection);

            if (transactListener != null) {
                transactListener.onTransactEnded(session);
            }

            if (tracingEnabled) {
                Trace.traceEnd(Trace.TRACE_TAG_ALWAYS);
            }
        }
    }

    /* Returns the native free function */
    private static native long getNativeFinalizer();
    /**
     *  See {@link IBinder#getInterfaceDescriptor()}
     */
    public native String getInterfaceDescriptor() throws RemoteException;

    /**
     * Native implementation of transact() for proxies
     */
    public native boolean transactNative(int code, Parcel data, Parcel reply,
            int flags) throws RemoteException;
    /**
     * See {@link IBinder#linkToDeath(DeathRecipient, int)}
     */
    public native void linkToDeath(DeathRecipient recipient, int flags)
            throws RemoteException;
    /**
     * See {@link IBinder#unlinkToDeath}
     */
    public native boolean unlinkToDeath(DeathRecipient recipient, int flags);

    /**
     * Perform a dump on the remote object
     *
     * @param fd The raw file descriptor that the dump is being sent to.
     * @param args additional arguments to the dump request.
     * @throws RemoteException
     */
    public void dump(FileDescriptor fd, String[] args) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeFileDescriptor(fd);
        data.writeStringArray(args);
        try {
            transact(DUMP_TRANSACTION, data, reply, 0);
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * Perform an asynchronous dump on the remote object
     *
     * @param fd The raw file descriptor that the dump is being sent to.
     * @param args additional arguments to the dump request.
     * @throws RemoteException
     */
    public void dumpAsync(FileDescriptor fd, String[] args) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeFileDescriptor(fd);
        data.writeStringArray(args);
        try {
            transact(DUMP_TRANSACTION, data, reply, FLAG_ONEWAY);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * See {@link IBinder#shellCommand(FileDescriptor, FileDescriptor, FileDescriptor,
     * String[], ShellCallback, ResultReceiver)}
     *
     * @param in The raw file descriptor that an input data stream can be read from.
     * @param out The raw file descriptor that normal command messages should be written to.
     * @param err The raw file descriptor that command error messages should be written to.
     * @param args Command-line arguments.
     * @param callback Optional callback to the caller's shell to perform operations in it.
     * @param resultReceiver Called when the command has finished executing, with the result code.
     * @throws RemoteException
     */
    public void shellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback,
            ResultReceiver resultReceiver) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeFileDescriptor(in);
        data.writeFileDescriptor(out);
        data.writeFileDescriptor(err);
        data.writeStringArray(args);
        ShellCallback.writeToParcel(callback, data);
        resultReceiver.writeToParcel(data, 0);
        try {
            transact(SHELL_COMMAND_TRANSACTION, data, reply, 0);
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static void sendDeathNotice(DeathRecipient recipient, IBinder binderProxy) {
        if (false) {
            Log.v("JavaBinder", "sendDeathNotice to " + recipient + " for " + binderProxy);
        }
        try {
            recipient.binderDied(binderProxy);
        } catch (RuntimeException exc) {
            Log.w("BinderNative", "Uncaught exception from death notification",
                    exc);
        }
    }

    /**
     * C++ pointer to BinderProxyNativeData. That consists of strong pointers to the
     * native IBinder object, and a DeathRecipientList.
     */
    private final long mNativeData;
}
