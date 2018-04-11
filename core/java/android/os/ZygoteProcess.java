/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.Zygote;
import com.android.internal.util.Preconditions;

import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/*package*/ class ZygoteStartFailedEx extends Exception {
    ZygoteStartFailedEx(String s) {
        super(s);
    }

    ZygoteStartFailedEx(Throwable cause) {
        super(cause);
    }

    ZygoteStartFailedEx(String s, Throwable cause) {
        super(s, cause);
    }
}

/**
 * Maintains communication state with the zygote processes. This class is responsible
 * for the sockets opened to the zygotes and for starting processes on behalf of the
 * {@link android.os.Process} class.
 *
 * {@hide}
 */
public class ZygoteProcess {
    private static final String LOG_TAG = "ZygoteProcess";

    /**
     * The name of the socket used to communicate with the primary zygote.
     */
    private final LocalSocketAddress mSocket;

    /**
     * The name of the secondary (alternate ABI) zygote socket.
     */
    private final LocalSocketAddress mSecondarySocket;

    public ZygoteProcess(String primarySocket, String secondarySocket) {
        this(new LocalSocketAddress(primarySocket, LocalSocketAddress.Namespace.RESERVED),
                new LocalSocketAddress(secondarySocket, LocalSocketAddress.Namespace.RESERVED));
    }

    public ZygoteProcess(LocalSocketAddress primarySocket, LocalSocketAddress secondarySocket) {
        mSocket = primarySocket;
        mSecondarySocket = secondarySocket;
    }

    public LocalSocketAddress getPrimarySocketAddress() {
        return mSocket;
    }

    /**
     * State for communicating with the zygote process.
     */
    public static class ZygoteState {
        final LocalSocket socket;
        final DataInputStream inputStream;
        final BufferedWriter writer;
        final List<String> abiList;

        boolean mClosed;

        private ZygoteState(LocalSocket socket, DataInputStream inputStream,
                BufferedWriter writer, List<String> abiList) {
            this.socket = socket;
            this.inputStream = inputStream;
            this.writer = writer;
            this.abiList = abiList;
        }

        public static ZygoteState connect(LocalSocketAddress address) throws IOException {
            DataInputStream zygoteInputStream = null;
            BufferedWriter zygoteWriter = null;
            final LocalSocket zygoteSocket = new LocalSocket();

            try {
                zygoteSocket.connect(address);

                zygoteInputStream = new DataInputStream(zygoteSocket.getInputStream());

                zygoteWriter = new BufferedWriter(new OutputStreamWriter(
                        zygoteSocket.getOutputStream()), 256);
            } catch (IOException ex) {
                try {
                    zygoteSocket.close();
                } catch (IOException ignore) {
                }

                throw ex;
            }

            String abiListString = getAbiList(zygoteWriter, zygoteInputStream);
            Log.i("Zygote", "Process: zygote socket " + address.getNamespace() + "/"
                    + address.getName() + " opened, supported ABIS: " + abiListString);

            return new ZygoteState(zygoteSocket, zygoteInputStream, zygoteWriter,
                    Arrays.asList(abiListString.split(",")));
        }

        boolean matches(String abi) {
            return abiList.contains(abi);
        }

        public void close() {
            try {
                socket.close();
            } catch (IOException ex) {
                Log.e(LOG_TAG,"I/O exception on routine close", ex);
            }

            mClosed = true;
        }

        boolean isClosed() {
            return mClosed;
        }
    }

    /**
     * Lock object to protect access to the two ZygoteStates below. This lock must be
     * acquired while communicating over the ZygoteState's socket, to prevent
     * interleaved access.
     */
    private final Object mLock = new Object();

    /**
     * List of exemptions to the API blacklist. These are prefix matches on the runtime format
     * symbol signature. Any matching symbol is treated by the runtime as being on the light grey
     * list.
     */
    private List<String> mApiBlacklistExemptions = Collections.emptyList();

    /**
     * Proportion of hidden API accesses that should be logged to the event log; 0 - 0x10000.
     */
    private int mHiddenApiAccessLogSampleRate;

    /**
     * The state of the connection to the primary zygote.
     */
    private ZygoteState primaryZygoteState;

    /**
     * The state of the connection to the secondary zygote.
     */
    private ZygoteState secondaryZygoteState;

    /**
     * Start a new process.
     *
     * <p>If processes are enabled, a new process is created and the
     * static main() function of a <var>processClass</var> is executed there.
     * The process will continue running after this function returns.
     *
     * <p>If processes are not enabled, a new thread in the caller's
     * process is created and main() of <var>processclass</var> called there.
     *
     * <p>The niceName parameter, if not an empty string, is a custom name to
     * give to the process instead of using processClass.  This allows you to
     * make easily identifyable processes even if you are using the same base
     * <var>processClass</var> to start them.
     *
     * When invokeWith is not null, the process will be started as a fresh app
     * and not a zygote fork. Note that this is only allowed for uid 0 or when
     * runtimeFlags contains DEBUG_ENABLE_DEBUGGER.
     *
     * @param processClass The class to use as the process's main entry
     *                     point.
     * @param niceName A more readable name to use for the process.
     * @param uid The user-id under which the process will run.
     * @param gid The group-id under which the process will run.
     * @param gids Additional group-ids associated with the process.
     * @param runtimeFlags Additional flags.
     * @param targetSdkVersion The target SDK version for the app.
     * @param seInfo null-ok SELinux information for the new process.
     * @param abi non-null the ABI this app should be started with.
     * @param instructionSet null-ok the instruction set to use.
     * @param appDataDir null-ok the data directory of the app.
     * @param invokeWith null-ok the command to invoke with.
     * @param zygoteArgs Additional arguments to supply to the zygote process.
     *
     * @return An object that describes the result of the attempt to start the process.
     * @throws RuntimeException on fatal start failure
     */
    public final Process.ProcessStartResult start(final String processClass,
                                                  final String niceName,
                                                  int uid, int gid, int[] gids,
                                                  int runtimeFlags, int mountExternal,
                                                  int targetSdkVersion,
                                                  String seInfo,
                                                  String abi,
                                                  String instructionSet,
                                                  String appDataDir,
                                                  String invokeWith,
                                                  String[] zygoteArgs) {
        try {
            return startViaZygote(processClass, niceName, uid, gid, gids,
                    runtimeFlags, mountExternal, targetSdkVersion, seInfo,
                    abi, instructionSet, appDataDir, invokeWith, false /* startChildZygote */,
                    zygoteArgs);
        } catch (ZygoteStartFailedEx ex) {
            Log.e(LOG_TAG,
                    "Starting VM process through Zygote failed");
            throw new RuntimeException(
                    "Starting VM process through Zygote failed", ex);
        }
    }

    /** retry interval for opening a zygote socket */
    static final int ZYGOTE_RETRY_MILLIS = 500;

    /**
     * Queries the zygote for the list of ABIS it supports.
     *
     * @throws ZygoteStartFailedEx if the query failed.
     */
    @GuardedBy("mLock")
    private static String getAbiList(BufferedWriter writer, DataInputStream inputStream)
            throws IOException {
        // Each query starts with the argument count (1 in this case)
        writer.write("1");
        // ... followed by a new-line.
        writer.newLine();
        // ... followed by our only argument.
        writer.write("--query-abi-list");
        writer.newLine();
        writer.flush();

        // The response is a length prefixed stream of ASCII bytes.
        int numBytes = inputStream.readInt();
        byte[] bytes = new byte[numBytes];
        inputStream.readFully(bytes);

        return new String(bytes, StandardCharsets.US_ASCII);
    }

    /**
     * Sends an argument list to the zygote process, which starts a new child
     * and returns the child's pid. Please note: the present implementation
     * replaces newlines in the argument list with spaces.
     *
     * @throws ZygoteStartFailedEx if process start failed for any reason
     */
    @GuardedBy("mLock")
    private static Process.ProcessStartResult zygoteSendArgsAndGetResult(
            ZygoteState zygoteState, ArrayList<String> args)
            throws ZygoteStartFailedEx {
        try {
            // Throw early if any of the arguments are malformed. This means we can
            // avoid writing a partial response to the zygote.
            int sz = args.size();
            for (int i = 0; i < sz; i++) {
                if (args.get(i).indexOf('\n') >= 0) {
                    throw new ZygoteStartFailedEx("embedded newlines not allowed");
                }
            }

            /**
             * See com.android.internal.os.SystemZygoteInit.readArgumentList()
             * Presently the wire format to the zygote process is:
             * a) a count of arguments (argc, in essence)
             * b) a number of newline-separated argument strings equal to count
             *
             * After the zygote process reads these it will write the pid of
             * the child or -1 on failure, followed by boolean to
             * indicate whether a wrapper process was used.
             */
            final BufferedWriter writer = zygoteState.writer;
            final DataInputStream inputStream = zygoteState.inputStream;

            writer.write(Integer.toString(args.size()));
            writer.newLine();

            for (int i = 0; i < sz; i++) {
                String arg = args.get(i);
                writer.write(arg);
                writer.newLine();
            }

            writer.flush();

            // Should there be a timeout on this?
            Process.ProcessStartResult result = new Process.ProcessStartResult();

            // Always read the entire result from the input stream to avoid leaving
            // bytes in the stream for future process starts to accidentally stumble
            // upon.
            result.pid = inputStream.readInt();
            result.usingWrapper = inputStream.readBoolean();

            if (result.pid < 0) {
                throw new ZygoteStartFailedEx("fork() failed");
            }
            return result;
        } catch (IOException ex) {
            zygoteState.close();
            throw new ZygoteStartFailedEx(ex);
        }
    }

    /**
     * Starts a new process via the zygote mechanism.
     *
     * @param processClass Class name whose static main() to run
     * @param niceName 'nice' process name to appear in ps
     * @param uid a POSIX uid that the new process should setuid() to
     * @param gid a POSIX gid that the new process shuold setgid() to
     * @param gids null-ok; a list of supplementary group IDs that the
     * new process should setgroup() to.
     * @param runtimeFlags Additional flags for the runtime.
     * @param targetSdkVersion The target SDK version for the app.
     * @param seInfo null-ok SELinux information for the new process.
     * @param abi the ABI the process should use.
     * @param instructionSet null-ok the instruction set to use.
     * @param appDataDir null-ok the data directory of the app.
     * @param startChildZygote Start a sub-zygote. This creates a new zygote process
     * that has its state cloned from this zygote process.
     * @param extraArgs Additional arguments to supply to the zygote process.
     * @return An object that describes the result of the attempt to start the process.
     * @throws ZygoteStartFailedEx if process start failed for any reason
     */
    private Process.ProcessStartResult startViaZygote(final String processClass,
                                                      final String niceName,
                                                      final int uid, final int gid,
                                                      final int[] gids,
                                                      int runtimeFlags, int mountExternal,
                                                      int targetSdkVersion,
                                                      String seInfo,
                                                      String abi,
                                                      String instructionSet,
                                                      String appDataDir,
                                                      String invokeWith,
                                                      boolean startChildZygote,
                                                      String[] extraArgs)
                                                      throws ZygoteStartFailedEx {
        ArrayList<String> argsForZygote = new ArrayList<String>();

        // --runtime-args, --setuid=, --setgid=,
        // and --setgroups= must go first
        argsForZygote.add("--runtime-args");
        argsForZygote.add("--setuid=" + uid);
        argsForZygote.add("--setgid=" + gid);
        argsForZygote.add("--runtime-flags=" + runtimeFlags);
        if (mountExternal == Zygote.MOUNT_EXTERNAL_DEFAULT) {
            argsForZygote.add("--mount-external-default");
        } else if (mountExternal == Zygote.MOUNT_EXTERNAL_READ) {
            argsForZygote.add("--mount-external-read");
        } else if (mountExternal == Zygote.MOUNT_EXTERNAL_WRITE) {
            argsForZygote.add("--mount-external-write");
        }
        argsForZygote.add("--target-sdk-version=" + targetSdkVersion);

        // --setgroups is a comma-separated list
        if (gids != null && gids.length > 0) {
            StringBuilder sb = new StringBuilder();
            sb.append("--setgroups=");

            int sz = gids.length;
            for (int i = 0; i < sz; i++) {
                if (i != 0) {
                    sb.append(',');
                }
                sb.append(gids[i]);
            }

            argsForZygote.add(sb.toString());
        }

        if (niceName != null) {
            argsForZygote.add("--nice-name=" + niceName);
        }

        if (seInfo != null) {
            argsForZygote.add("--seinfo=" + seInfo);
        }

        if (instructionSet != null) {
            argsForZygote.add("--instruction-set=" + instructionSet);
        }

        if (appDataDir != null) {
            argsForZygote.add("--app-data-dir=" + appDataDir);
        }

        if (invokeWith != null) {
            argsForZygote.add("--invoke-with");
            argsForZygote.add(invokeWith);
        }

        if (startChildZygote) {
            argsForZygote.add("--start-child-zygote");
        }

        argsForZygote.add(processClass);

        if (extraArgs != null) {
            for (String arg : extraArgs) {
                argsForZygote.add(arg);
            }
        }

        synchronized(mLock) {
            return zygoteSendArgsAndGetResult(openZygoteSocketIfNeeded(abi), argsForZygote);
        }
    }

    /**
     * Closes the connections to the zygote, if they exist.
     */
    public void close() {
        if (primaryZygoteState != null) {
            primaryZygoteState.close();
        }
        if (secondaryZygoteState != null) {
            secondaryZygoteState.close();
        }
    }

    /**
     * Tries to establish a connection to the zygote that handles a given {@code abi}. Might block
     * and retry if the zygote is unresponsive. This method is a no-op if a connection is
     * already open.
     */
    public void establishZygoteConnectionForAbi(String abi) {
        try {
            synchronized(mLock) {
                openZygoteSocketIfNeeded(abi);
            }
        } catch (ZygoteStartFailedEx ex) {
            throw new RuntimeException("Unable to connect to zygote for abi: " + abi, ex);
        }
    }

    /**
     * Push hidden API blacklisting exemptions into the zygote process(es).
     *
     * <p>The list of exemptions will take affect for all new processes forked from the zygote after
     * this call.
     *
     * @param exemptions List of hidden API exemption prefixes. Any matching members are treated as
     *        whitelisted/public APIs (i.e. allowed, no logging of usage).
     */
    public void setApiBlacklistExemptions(List<String> exemptions) {
        synchronized (mLock) {
            mApiBlacklistExemptions = exemptions;
            maybeSetApiBlacklistExemptions(primaryZygoteState, true);
            maybeSetApiBlacklistExemptions(secondaryZygoteState, true);
        }
    }

    /**
     * Set the precentage of detected hidden API accesses that are logged to the event log.
     *
     * <p>This rate will take affect for all new processes forked from the zygote after this call.
     *
     * @param rate An integer between 0 and 0x10000 inclusive. 0 means no event logging.
     */
    public void setHiddenApiAccessLogSampleRate(int rate) {
        synchronized (mLock) {
            mHiddenApiAccessLogSampleRate = rate;
            maybeSetHiddenApiAccessLogSampleRate(primaryZygoteState);
            maybeSetHiddenApiAccessLogSampleRate(secondaryZygoteState);
        }
    }

    @GuardedBy("mLock")
    private void maybeSetApiBlacklistExemptions(ZygoteState state, boolean sendIfEmpty) {
        if (state == null || state.isClosed()) {
            return;
        }
        if (!sendIfEmpty && mApiBlacklistExemptions.isEmpty()) {
            return;
        }
        try {
            state.writer.write(Integer.toString(mApiBlacklistExemptions.size() + 1));
            state.writer.newLine();
            state.writer.write("--set-api-blacklist-exemptions");
            state.writer.newLine();
            for (int i = 0; i < mApiBlacklistExemptions.size(); ++i) {
                state.writer.write(mApiBlacklistExemptions.get(i));
                state.writer.newLine();
            }
            state.writer.flush();
            int status = state.inputStream.readInt();
            if (status != 0) {
                Slog.e(LOG_TAG, "Failed to set API blacklist exemptions; status " + status);
            }
        } catch (IOException ioe) {
            Slog.e(LOG_TAG, "Failed to set API blacklist exemptions", ioe);
        }
    }

    private void maybeSetHiddenApiAccessLogSampleRate(ZygoteState state) {
        if (state == null || state.isClosed()) {
            return;
        }
        if (mHiddenApiAccessLogSampleRate == -1) {
            return;
        }
        try {
            state.writer.write(Integer.toString(1));
            state.writer.newLine();
            state.writer.write("--hidden-api-log-sampling-rate="
                    + Integer.toString(mHiddenApiAccessLogSampleRate));
            state.writer.newLine();
            state.writer.flush();
            int status = state.inputStream.readInt();
            if (status != 0) {
                Slog.e(LOG_TAG, "Failed to set hidden API log sampling rate; status " + status);
            }
        } catch (IOException ioe) {
            Slog.e(LOG_TAG, "Failed to set hidden API log sampling rate", ioe);
        }
    }

    /**
     * Tries to open socket to Zygote process if not already open. If
     * already open, does nothing.  May block and retry.  Requires that mLock be held.
     */
    @GuardedBy("mLock")
    private ZygoteState openZygoteSocketIfNeeded(String abi) throws ZygoteStartFailedEx {
        Preconditions.checkState(Thread.holdsLock(mLock), "ZygoteProcess lock not held");

        if (primaryZygoteState == null || primaryZygoteState.isClosed()) {
            try {
                primaryZygoteState = ZygoteState.connect(mSocket);
            } catch (IOException ioe) {
                throw new ZygoteStartFailedEx("Error connecting to primary zygote", ioe);
            }
            maybeSetApiBlacklistExemptions(primaryZygoteState, false);
            maybeSetHiddenApiAccessLogSampleRate(primaryZygoteState);
        }
        if (primaryZygoteState.matches(abi)) {
            return primaryZygoteState;
        }

        // The primary zygote didn't match. Try the secondary.
        if (secondaryZygoteState == null || secondaryZygoteState.isClosed()) {
            try {
                secondaryZygoteState = ZygoteState.connect(mSecondarySocket);
            } catch (IOException ioe) {
                throw new ZygoteStartFailedEx("Error connecting to secondary zygote", ioe);
            }
            maybeSetApiBlacklistExemptions(secondaryZygoteState, false);
            maybeSetHiddenApiAccessLogSampleRate(secondaryZygoteState);
        }

        if (secondaryZygoteState.matches(abi)) {
            return secondaryZygoteState;
        }

        throw new ZygoteStartFailedEx("Unsupported zygote ABI: " + abi);
    }

    /**
     * Instructs the zygote to pre-load the classes and native libraries at the given paths
     * for the specified abi. Not all zygotes support this function.
     */
    public boolean preloadPackageForAbi(String packagePath, String libsPath, String libFileName,
                                        String cacheKey, String abi) throws ZygoteStartFailedEx,
                                                                            IOException {
        synchronized(mLock) {
            ZygoteState state = openZygoteSocketIfNeeded(abi);
            state.writer.write("5");
            state.writer.newLine();

            state.writer.write("--preload-package");
            state.writer.newLine();

            state.writer.write(packagePath);
            state.writer.newLine();

            state.writer.write(libsPath);
            state.writer.newLine();

            state.writer.write(libFileName);
            state.writer.newLine();

            state.writer.write(cacheKey);
            state.writer.newLine();

            state.writer.flush();

            return (state.inputStream.readInt() == 0);
        }
    }

    /**
     * Instructs the zygote to preload the default set of classes and resources. Returns
     * {@code true} if a preload was performed as a result of this call, and {@code false}
     * otherwise. The latter usually means that the zygote eagerly preloaded at startup
     * or due to a previous call to {@code preloadDefault}. Note that this call is synchronous.
     */
    public boolean preloadDefault(String abi) throws ZygoteStartFailedEx, IOException {
        synchronized (mLock) {
            ZygoteState state = openZygoteSocketIfNeeded(abi);
            // Each query starts with the argument count (1 in this case)
            state.writer.write("1");
            state.writer.newLine();
            state.writer.write("--preload-default");
            state.writer.newLine();
            state.writer.flush();

            return (state.inputStream.readInt() == 0);
        }
    }

    /**
     * Try connecting to the Zygote over and over again until we hit a time-out.
     * @param socketName The name of the socket to connect to.
     */
    public static void waitForConnectionToZygote(String socketName) {
        final LocalSocketAddress address =
                new LocalSocketAddress(socketName, LocalSocketAddress.Namespace.RESERVED);
        waitForConnectionToZygote(address);
    }

    /**
     * Try connecting to the Zygote over and over again until we hit a time-out.
     * @param address The name of the socket to connect to.
     */
    public static void waitForConnectionToZygote(LocalSocketAddress address) {
        for (int n = 20; n >= 0; n--) {
            try {
                final ZygoteState zs = ZygoteState.connect(address);
                zs.close();
                return;
            } catch (IOException ioe) {
                Log.w(LOG_TAG,
                        "Got error connecting to zygote, retrying. msg= " + ioe.getMessage());
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
            }
        }
        Slog.wtf(LOG_TAG, "Failed to connect to Zygote through socket " + address.getName());
    }

    /**
     * Starts a new zygote process as a child of this zygote. This is used to create
     * secondary zygotes that inherit data from the zygote that this object
     * communicates with. This returns a new ZygoteProcess representing a connection
     * to the newly created zygote. Throws an exception if the zygote cannot be started.
     */
    public ChildZygoteProcess startChildZygote(final String processClass,
                                               final String niceName,
                                               int uid, int gid, int[] gids,
                                               int runtimeFlags,
                                               String seInfo,
                                               String abi,
                                               String instructionSet) {
        // Create an unguessable address in the global abstract namespace.
        final LocalSocketAddress serverAddress = new LocalSocketAddress(
                processClass + "/" + UUID.randomUUID().toString());

        final String[] extraArgs = {Zygote.CHILD_ZYGOTE_SOCKET_NAME_ARG + serverAddress.getName()};

        Process.ProcessStartResult result;
        try {
            result = startViaZygote(processClass, niceName, uid, gid,
                    gids, runtimeFlags, 0 /* mountExternal */, 0 /* targetSdkVersion */, seInfo,
                    abi, instructionSet, null /* appDataDir */, null /* invokeWith */,
                    true /* startChildZygote */, extraArgs);
        } catch (ZygoteStartFailedEx ex) {
            throw new RuntimeException("Starting child-zygote through Zygote failed", ex);
        }

        return new ChildZygoteProcess(serverAddress, result.pid);
    }
}
