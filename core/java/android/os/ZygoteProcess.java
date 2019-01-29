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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ApplicationInfo;
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
import java.util.Base64;
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

    /**
     * @hide for internal use only.
     */
    public static final String ZYGOTE_SOCKET_NAME = "zygote";

    /**
     * @hide for internal use only.
     */
    public static final String ZYGOTE_SECONDARY_SOCKET_NAME = "zygote_secondary";

    /**
     * @hide for internal use only.
     */
    public static final int ZYGOTE_CONNECT_TIMEOUT_MS = 20000;

    /**
     * @hide for internal use only.
     *
     * Use a relatively short delay, because for app zygote, this is in the critical path of
     * service launch.
     */
    public static final int ZYGOTE_CONNECT_RETRY_DELAY_MS = 50;

    /**
     * @hide for internal use only
     */
    public static final String BLASTULA_POOL_SOCKET_NAME = "blastula_pool";

    /**
     * @hide for internal use only
     */
    public static final String BLASTULA_POOL_SECONDARY_SOCKET_NAME = "blastula_pool_secondary";

    /**
     * @hide for internal use only
     */
    private static final String LOG_TAG = "ZygoteProcess";

    /**
     * The name of the socket used to communicate with the primary zygote.
     */
    private final LocalSocketAddress mZygoteSocketAddress;

    /**
     * The name of the secondary (alternate ABI) zygote socket.
     */
    private final LocalSocketAddress mZygoteSecondarySocketAddress;
    /**
     * The name of the socket used to communicate with the primary blastula pool.
     */
    private final LocalSocketAddress mBlastulaPoolSocketAddress;

    /**
     * The name of the socket used to communicate with the secondary (alternate ABI) blastula pool.
     */
    private final LocalSocketAddress mBlastulaPoolSecondarySocketAddress;

    public ZygoteProcess() {
        mZygoteSocketAddress =
                new LocalSocketAddress(ZYGOTE_SOCKET_NAME, LocalSocketAddress.Namespace.RESERVED);
        mZygoteSecondarySocketAddress =
                new LocalSocketAddress(ZYGOTE_SECONDARY_SOCKET_NAME,
                                       LocalSocketAddress.Namespace.RESERVED);

        mBlastulaPoolSocketAddress =
                new LocalSocketAddress(BLASTULA_POOL_SOCKET_NAME,
                                       LocalSocketAddress.Namespace.RESERVED);
        mBlastulaPoolSecondarySocketAddress =
                new LocalSocketAddress(BLASTULA_POOL_SECONDARY_SOCKET_NAME,
                                       LocalSocketAddress.Namespace.RESERVED);
    }

    public ZygoteProcess(LocalSocketAddress primarySocketAddress,
                         LocalSocketAddress secondarySocketAddress) {
        mZygoteSocketAddress = primarySocketAddress;
        mZygoteSecondarySocketAddress = secondarySocketAddress;

        mBlastulaPoolSocketAddress = null;
        mBlastulaPoolSecondarySocketAddress = null;
    }

    public LocalSocketAddress getPrimarySocketAddress() {
        return mZygoteSocketAddress;
    }

    /**
     * State for communicating with the zygote process.
     */
    public static class ZygoteState {
        final LocalSocketAddress mZygoteSocketAddress;
        final LocalSocketAddress mBlastulaSocketAddress;

        private final LocalSocket mZygoteSessionSocket;

        final DataInputStream mZygoteInputStream;
        final BufferedWriter mZygoteOutputWriter;

        private final List<String> mABIList;

        private boolean mClosed;

        private ZygoteState(LocalSocketAddress zygoteSocketAddress,
                            LocalSocketAddress blastulaSocketAddress,
                            LocalSocket zygoteSessionSocket,
                            DataInputStream zygoteInputStream,
                            BufferedWriter zygoteOutputWriter,
                            List<String> abiList) {
            this.mZygoteSocketAddress = zygoteSocketAddress;
            this.mBlastulaSocketAddress = blastulaSocketAddress;
            this.mZygoteSessionSocket = zygoteSessionSocket;
            this.mZygoteInputStream = zygoteInputStream;
            this.mZygoteOutputWriter = zygoteOutputWriter;
            this.mABIList = abiList;
        }

        /**
         * Create a new ZygoteState object by connecting to the given Zygote socket and saving the
         * given blastula socket address.
         *
         * @param zygoteSocketAddress  Zygote socket to connect to
         * @param blastulaSocketAddress  Blastula socket address to save for later
         * @return  A new ZygoteState object containing a session socket for the given Zygote socket
         * address
         * @throws IOException
         */
        public static ZygoteState connect(LocalSocketAddress zygoteSocketAddress,
                                          LocalSocketAddress blastulaSocketAddress)
                throws IOException {

            DataInputStream zygoteInputStream = null;
            BufferedWriter zygoteOutputWriter = null;
            final LocalSocket zygoteSessionSocket = new LocalSocket();

            try {
                zygoteSessionSocket.connect(zygoteSocketAddress);
                zygoteInputStream = new DataInputStream(zygoteSessionSocket.getInputStream());
                zygoteOutputWriter =
                        new BufferedWriter(
                                new OutputStreamWriter(zygoteSessionSocket.getOutputStream()),
                                Zygote.SOCKET_BUFFER_SIZE);
            } catch (IOException ex) {
                try {
                    zygoteSessionSocket.close();
                } catch (IOException ignore) { }

                throw ex;
            }

            return new ZygoteState(zygoteSocketAddress, blastulaSocketAddress,
                                   zygoteSessionSocket, zygoteInputStream, zygoteOutputWriter,
                                   getAbiList(zygoteOutputWriter, zygoteInputStream));
        }

        LocalSocket getBlastulaSessionSocket() throws IOException {
            final LocalSocket blastulaSessionSocket = new LocalSocket();
            blastulaSessionSocket.connect(this.mBlastulaSocketAddress);

            return blastulaSessionSocket;
        }

        boolean matches(String abi) {
            return mABIList.contains(abi);
        }

        public void close() {
            try {
                mZygoteSessionSocket.close();
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
     * @param packageName null-ok the name of the package this process belongs to.
     * @param packagesForUid null-ok all the packages with the same uid as this process.
     * @param visibleVols null-ok storage volumes that can be accessed by this process.
     * @param zygoteArgs Additional arguments to supply to the zygote process.
     *
     * @return An object that describes the result of the attempt to start the process.
     * @throws RuntimeException on fatal start failure
     */
    public final Process.ProcessStartResult start(@NonNull final String processClass,
                                                  final String niceName,
                                                  int uid, int gid, @Nullable int[] gids,
                                                  int runtimeFlags, int mountExternal,
                                                  int targetSdkVersion,
                                                  @Nullable String seInfo,
                                                  @NonNull String abi,
                                                  @Nullable String instructionSet,
                                                  @Nullable String appDataDir,
                                                  @Nullable String invokeWith,
                                                  @Nullable String packageName,
                                                  @Nullable String[] packagesForUid,
                                                  @Nullable String[] visibleVols,
                                                  boolean useBlastulaPool,
                                                  @Nullable String[] zygoteArgs) {
        try {
            return startViaZygote(processClass, niceName, uid, gid, gids,
                    runtimeFlags, mountExternal, targetSdkVersion, seInfo,
                    abi, instructionSet, appDataDir, invokeWith, /*startChildZygote=*/false,
                    packageName, packagesForUid, visibleVols, useBlastulaPool, zygoteArgs);
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
    private static List<String> getAbiList(BufferedWriter writer, DataInputStream inputStream)
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

        String rawList = new String(bytes, StandardCharsets.US_ASCII);

        return Arrays.asList(rawList.split(","));
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
            ZygoteState zygoteState, boolean useBlastulaPool, ArrayList<String> args)
            throws ZygoteStartFailedEx {
        // Throw early if any of the arguments are malformed. This means we can
        // avoid writing a partial response to the zygote.
        for (String arg : args) {
            if (arg.indexOf('\n') >= 0) {
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
        String msgStr = Integer.toString(args.size()) + "\n"
                        + String.join("\n", args) + "\n";

        // Should there be a timeout on this?
        Process.ProcessStartResult result = new Process.ProcessStartResult();

        // TODO (chriswailes): Move branch body into separate function.
        if (useBlastulaPool && Zygote.BLASTULA_POOL_ENABLED && isValidBlastulaCommand(args)) {
            LocalSocket blastulaSessionSocket = null;

            try {
                blastulaSessionSocket = zygoteState.getBlastulaSessionSocket();

                final BufferedWriter blastulaWriter =
                        new BufferedWriter(
                                new OutputStreamWriter(blastulaSessionSocket.getOutputStream()),
                                Zygote.SOCKET_BUFFER_SIZE);
                final DataInputStream blastulaReader =
                        new DataInputStream(blastulaSessionSocket.getInputStream());

                blastulaWriter.write(msgStr);
                blastulaWriter.flush();

                result.pid = blastulaReader.readInt();
                // Blastulas can't be used to spawn processes that need wrappers.
                result.usingWrapper = false;

                if (result.pid < 0) {
                    throw new ZygoteStartFailedEx("Blastula specialization failed");
                }

                return result;
            } catch (IOException ex) {
                // If there was an IOException using the blastula pool we will log the error and
                // attempt to start the process through the Zygote.
                Log.e(LOG_TAG, "IO Exception while communicating with blastula pool - "
                               + ex.toString());
            } finally {
                try {
                    blastulaSessionSocket.close();
                } catch (IOException ex) {
                    Log.e(LOG_TAG, "Failed to close blastula session socket: " + ex.getMessage());
                }
            }
        }

        try {
            final BufferedWriter zygoteWriter = zygoteState.mZygoteOutputWriter;
            final DataInputStream zygoteInputStream = zygoteState.mZygoteInputStream;

            zygoteWriter.write(msgStr);
            zygoteWriter.flush();

            // Always read the entire result from the input stream to avoid leaving
            // bytes in the stream for future process starts to accidentally stumble
            // upon.
            result.pid = zygoteInputStream.readInt();
            result.usingWrapper = zygoteInputStream.readBoolean();
        } catch (IOException ex) {
            zygoteState.close();
            Log.e(LOG_TAG, "IO Exception while communicating with Zygote - "
                    + ex.toString());
            throw new ZygoteStartFailedEx(ex);
        }

        if (result.pid < 0) {
            throw new ZygoteStartFailedEx("fork() failed");
        }

        return result;
    }

    /**
     * Flags that may not be passed to a blastula.
     */
    private static final String[] INVALID_BLASTULA_FLAGS = {
        "--query-abi-list",
        "--get-pid",
        "--preload-default",
        "--preload-package",
        "--preload-app",
        "--start-child-zygote",
        "--set-api-blacklist-exemptions",
        "--hidden-api-log-sampling-rate",
        "--invoke-with"
    };

    /**
     * Tests a command list to see if it is valid to send to a blastula.
     * @param args  Zygote/Blastula command arguments
     * @return  True if the command can be passed to a blastula; false otherwise
     */
    private static boolean isValidBlastulaCommand(ArrayList<String> args) {
        for (String flag : args) {
            for (String badFlag : INVALID_BLASTULA_FLAGS) {
                if (flag.startsWith(badFlag)) {
                    return false;
                }
            }
        }

        return true;
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
     * @param packageName null-ok the name of the package this process belongs to.
     * @param packagesForUid null-ok all the packages with the same uid as this process.
     * @param visibleVols null-ok storage volumes that can be accessed by this process.
     * @param extraArgs Additional arguments to supply to the zygote process.
     * @return An object that describes the result of the attempt to start the process.
     * @throws ZygoteStartFailedEx if process start failed for any reason
     */
    private Process.ProcessStartResult startViaZygote(@NonNull final String processClass,
                                                      @Nullable final String niceName,
                                                      final int uid, final int gid,
                                                      @Nullable final int[] gids,
                                                      int runtimeFlags, int mountExternal,
                                                      int targetSdkVersion,
                                                      @Nullable String seInfo,
                                                      @NonNull String abi,
                                                      @Nullable String instructionSet,
                                                      @Nullable String appDataDir,
                                                      @Nullable String invokeWith,
                                                      boolean startChildZygote,
                                                      @Nullable String packageName,
                                                      @Nullable String[] packagesForUid,
                                                      @Nullable String[] visibleVols,
                                                      boolean useBlastulaPool,
                                                      @Nullable String[] extraArgs)
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
        } else if (mountExternal == Zygote.MOUNT_EXTERNAL_FULL) {
            argsForZygote.add("--mount-external-full");
        } else if (mountExternal == Zygote.MOUNT_EXTERNAL_INSTALLER) {
            argsForZygote.add("--mount-external-installer");
        } else if (mountExternal == Zygote.MOUNT_EXTERNAL_LEGACY) {
            argsForZygote.add("--mount-external-legacy");
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

        if (packageName != null) {
            argsForZygote.add("--package-name=" + packageName);
        }

        if (packagesForUid != null && packagesForUid.length > 0) {
            final StringBuilder sb = new StringBuilder();
            sb.append("--packages-for-uid=");

            for (int i = 0; i < packagesForUid.length; ++i) {
                if (i != 0) {
                    sb.append(',');
                }
                sb.append(packagesForUid[i]);
            }
            argsForZygote.add(sb.toString());
        }

        if (visibleVols != null && visibleVols.length > 0) {
            final StringBuilder sb = new StringBuilder();
            sb.append("--visible-vols=");

            for (int i = 0; i < visibleVols.length; ++i) {
                if (i != 0) {
                    sb.append(',');
                }
                sb.append(visibleVols[i]);
            }
            argsForZygote.add(sb.toString());
        }

        argsForZygote.add(processClass);

        if (extraArgs != null) {
            for (String arg : extraArgs) {
                argsForZygote.add(arg);
            }
        }

        synchronized(mLock) {
            return zygoteSendArgsAndGetResult(openZygoteSocketIfNeeded(abi),
                                              useBlastulaPool,
                                              argsForZygote);
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
     * Attempt to retrieve the PID of the zygote serving the given abi.
     */
    public int getZygotePid(String abi) {
        try {
            synchronized (mLock) {
                ZygoteState state = openZygoteSocketIfNeeded(abi);

                // Each query starts with the argument count (1 in this case)
                state.mZygoteOutputWriter.write("1");
                // ... followed by a new-line.
                state.mZygoteOutputWriter.newLine();
                // ... followed by our only argument.
                state.mZygoteOutputWriter.write("--get-pid");
                state.mZygoteOutputWriter.newLine();
                state.mZygoteOutputWriter.flush();

                // The response is a length prefixed stream of ASCII bytes.
                int numBytes = state.mZygoteInputStream.readInt();
                byte[] bytes = new byte[numBytes];
                state.mZygoteInputStream.readFully(bytes);

                return Integer.parseInt(new String(bytes, StandardCharsets.US_ASCII));
            }
        } catch (Exception ex) {
            throw new RuntimeException("Failure retrieving pid", ex);
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
    public boolean setApiBlacklistExemptions(List<String> exemptions) {
        synchronized (mLock) {
            mApiBlacklistExemptions = exemptions;
            boolean ok = maybeSetApiBlacklistExemptions(primaryZygoteState, true);
            if (ok) {
                ok = maybeSetApiBlacklistExemptions(secondaryZygoteState, true);
            }
            return ok;
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
    private boolean maybeSetApiBlacklistExemptions(ZygoteState state, boolean sendIfEmpty) {
        if (state == null || state.isClosed()) {
            Slog.e(LOG_TAG, "Can't set API blacklist exemptions: no zygote connection");
            return false;
        }
        if (!sendIfEmpty && mApiBlacklistExemptions.isEmpty()) {
            return true;
        }
        try {
            state.mZygoteOutputWriter.write(Integer.toString(mApiBlacklistExemptions.size() + 1));
            state.mZygoteOutputWriter.newLine();
            state.mZygoteOutputWriter.write("--set-api-blacklist-exemptions");
            state.mZygoteOutputWriter.newLine();
            for (int i = 0; i < mApiBlacklistExemptions.size(); ++i) {
                state.mZygoteOutputWriter.write(mApiBlacklistExemptions.get(i));
                state.mZygoteOutputWriter.newLine();
            }
            state.mZygoteOutputWriter.flush();
            int status = state.mZygoteInputStream.readInt();
            if (status != 0) {
                Slog.e(LOG_TAG, "Failed to set API blacklist exemptions; status " + status);
            }
            return true;
        } catch (IOException ioe) {
            Slog.e(LOG_TAG, "Failed to set API blacklist exemptions", ioe);
            mApiBlacklistExemptions = Collections.emptyList();
            return false;
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
            state.mZygoteOutputWriter.write(Integer.toString(1));
            state.mZygoteOutputWriter.newLine();
            state.mZygoteOutputWriter.write("--hidden-api-log-sampling-rate="
                    + Integer.toString(mHiddenApiAccessLogSampleRate));
            state.mZygoteOutputWriter.newLine();
            state.mZygoteOutputWriter.flush();
            int status = state.mZygoteInputStream.readInt();
            if (status != 0) {
                Slog.e(LOG_TAG, "Failed to set hidden API log sampling rate; status " + status);
            }
        } catch (IOException ioe) {
            Slog.e(LOG_TAG, "Failed to set hidden API log sampling rate", ioe);
        }
    }

    /**
     * Tries to open a session socket to a Zygote process with a compatible ABI if one is not
     * already open. If a compatible session socket is already open that session socket is returned.
     * This function may block and may have to try connecting to multiple Zygotes to find the
     * appropriate one.  Requires that mLock be held.
     */
    @GuardedBy("mLock")
    private ZygoteState openZygoteSocketIfNeeded(String abi)
            throws ZygoteStartFailedEx {

        Preconditions.checkState(Thread.holdsLock(mLock), "ZygoteProcess lock not held");

        if (primaryZygoteState == null || primaryZygoteState.isClosed()) {
            try {
                primaryZygoteState =
                    ZygoteState.connect(mZygoteSocketAddress, mBlastulaPoolSocketAddress);
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
                secondaryZygoteState =
                    ZygoteState.connect(mZygoteSecondarySocketAddress,
                                        mBlastulaPoolSecondarySocketAddress);
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
     * Instructs the zygote to pre-load the application code for the given Application.
     * Only the app zygote supports this function.
     * TODO preloadPackageForAbi() can probably be removed and the callers an use this instead.
     */
    public boolean preloadApp(ApplicationInfo appInfo, String abi) throws ZygoteStartFailedEx,
                                                                          IOException {
        synchronized (mLock) {
            ZygoteState state = openZygoteSocketIfNeeded(abi);
            state.mZygoteOutputWriter.write("2");
            state.mZygoteOutputWriter.newLine();

            state.mZygoteOutputWriter.write("--preload-app");
            state.mZygoteOutputWriter.newLine();

            // Zygote args needs to be strings, so in order to pass ApplicationInfo,
            // write it to a Parcel, and base64 the raw Parcel bytes to the other side.
            Parcel parcel = Parcel.obtain();
            appInfo.writeToParcel(parcel, 0 /* flags */);
            String encodedParcelData = Base64.getEncoder().encodeToString(parcel.marshall());
            parcel.recycle();
            state.mZygoteOutputWriter.write(encodedParcelData);
            state.mZygoteOutputWriter.newLine();

            state.mZygoteOutputWriter.flush();

            return (state.mZygoteInputStream.readInt() == 0);
        }
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
            state.mZygoteOutputWriter.write("5");
            state.mZygoteOutputWriter.newLine();

            state.mZygoteOutputWriter.write("--preload-package");
            state.mZygoteOutputWriter.newLine();

            state.mZygoteOutputWriter.write(packagePath);
            state.mZygoteOutputWriter.newLine();

            state.mZygoteOutputWriter.write(libsPath);
            state.mZygoteOutputWriter.newLine();

            state.mZygoteOutputWriter.write(libFileName);
            state.mZygoteOutputWriter.newLine();

            state.mZygoteOutputWriter.write(cacheKey);
            state.mZygoteOutputWriter.newLine();

            state.mZygoteOutputWriter.flush();

            return (state.mZygoteInputStream.readInt() == 0);
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
            state.mZygoteOutputWriter.write("1");
            state.mZygoteOutputWriter.newLine();
            state.mZygoteOutputWriter.write("--preload-default");
            state.mZygoteOutputWriter.newLine();
            state.mZygoteOutputWriter.flush();

            return (state.mZygoteInputStream.readInt() == 0);
        }
    }

    /**
     * Try connecting to the Zygote over and over again until we hit a time-out.
     * @param socketName The name of the socket to connect to.
     */
    public static void waitForConnectionToZygote(String zygoteSocketName) {
        final LocalSocketAddress zygoteSocketAddress =
                new LocalSocketAddress(zygoteSocketName, LocalSocketAddress.Namespace.RESERVED);
        waitForConnectionToZygote(zygoteSocketAddress);
    }

    /**
     * Try connecting to the Zygote over and over again until we hit a time-out.
     * @param address The name of the socket to connect to.
     */
    public static void waitForConnectionToZygote(LocalSocketAddress zygoteSocketAddress) {
        int numRetries = ZYGOTE_CONNECT_TIMEOUT_MS / ZYGOTE_CONNECT_RETRY_DELAY_MS;
        for (int n = numRetries; n >= 0; n--) {
            try {
                final ZygoteState zs =
                        ZygoteState.connect(zygoteSocketAddress, null);
                zs.close();
                return;
            } catch (IOException ioe) {
                Log.w(LOG_TAG,
                        "Got error connecting to zygote, retrying. msg= " + ioe.getMessage());
            }

            try {
                Thread.sleep(ZYGOTE_CONNECT_RETRY_DELAY_MS);
            } catch (InterruptedException ie) {
            }
        }
        Slog.wtf(LOG_TAG, "Failed to connect to Zygote through socket "
                + zygoteSocketAddress.getName());
    }

    /**
     * Starts a new zygote process as a child of this zygote. This is used to create
     * secondary zygotes that inherit data from the zygote that this object
     * communicates with. This returns a new ZygoteProcess representing a connection
     * to the newly created zygote. Throws an exception if the zygote cannot be started.
     *
     * @param processClass The class to use as the child zygote's main entry
     *                     point.
     * @param niceName A more readable name to use for the process.
     * @param uid The user-id under which the child zygote will run.
     * @param gid The group-id under which the child zygote will run.
     * @param gids Additional group-ids associated with the child zygote process.
     * @param runtimeFlags Additional flags.
     * @param seInfo null-ok SELinux information for the child zygote process.
     * @param abi non-null the ABI of the child zygote
     * @param acceptedAbiList ABIs this child zygote will accept connections for; this
     *                        may be different from <code>abi</code> in case the children
     *                        spawned from this Zygote only communicate using ABI-safe methods.
     * @param instructionSet null-ok the instruction set to use.
     * @param uidRangeStart The first UID in the range the child zygote may setuid()/setgid() to
     * @param uidRangeEnd The last UID in the range the child zygote may setuid()/setgid() to
     */
    public ChildZygoteProcess startChildZygote(final String processClass,
                                               final String niceName,
                                               int uid, int gid, int[] gids,
                                               int runtimeFlags,
                                               String seInfo,
                                               String abi,
                                               String acceptedAbiList,
                                               String instructionSet,
                                               int uidRangeStart,
                                               int uidRangeEnd) {
        // Create an unguessable address in the global abstract namespace.
        final LocalSocketAddress serverAddress = new LocalSocketAddress(
                processClass + "/" + UUID.randomUUID().toString());

        final String[] extraArgs = {Zygote.CHILD_ZYGOTE_SOCKET_NAME_ARG + serverAddress.getName(),
                                    Zygote.CHILD_ZYGOTE_ABI_LIST_ARG + acceptedAbiList,
                                    Zygote.CHILD_ZYGOTE_UID_RANGE_START + uidRangeStart,
                                    Zygote.CHILD_ZYGOTE_UID_RANGE_END + uidRangeEnd};

        Process.ProcessStartResult result;
        try {
            result = startViaZygote(processClass, niceName, uid, gid,
                    gids, runtimeFlags, 0 /* mountExternal */, 0 /* targetSdkVersion */, seInfo,
                    abi, instructionSet, null /* appDataDir */, null /* invokeWith */,
                    true /* startChildZygote */, null /* packageName */,
                    null /* packagesForUid */, null /* visibleVolumes */,
                    false /* useBlastulaPool */, extraArgs);
        } catch (ZygoteStartFailedEx ex) {
            throw new RuntimeException("Starting child-zygote through Zygote failed", ex);
        }

        return new ChildZygoteProcess(serverAddress, result.pid);
    }
}
