/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.os;

import static android.system.OsConstants.F_SETFD;
import static android.system.OsConstants.O_CLOEXEC;
import static android.system.OsConstants.POLLIN;
import static android.system.OsConstants.STDERR_FILENO;
import static android.system.OsConstants.STDIN_FILENO;
import static android.system.OsConstants.STDOUT_FILENO;
import static com.android.internal.os.ZygoteConnectionConstants.CONNECTION_TIMEOUT_MILLIS;
import static com.android.internal.os.ZygoteConnectionConstants.MAX_ZYGOTE_ARGC;
import static com.android.internal.os.ZygoteConnectionConstants.WRAPPED_PID_TIMEOUT_MILLIS;

import android.net.Credentials;
import android.net.LocalSocket;
import android.os.FactoryTest;
import android.os.Process;
import android.os.SystemProperties;
import android.os.Trace;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructPollfd;
import android.util.Log;
import dalvik.system.VMRuntime;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import libcore.io.IoUtils;

/**
 * A connection that can make spawn requests.
 */
class ZygoteConnection {
    private static final String TAG = "Zygote";

    /** a prototype instance for a future List.toArray() */
    private static final int[][] intArray2d = new int[0][0];

    /**
     * The command socket.
     *
     * mSocket is retained in the child process in "peer wait" mode, so
     * that it closes when the child process terminates. In other cases,
     * it is closed in the peer.
     */
    private final LocalSocket mSocket;
    private final DataOutputStream mSocketOutStream;
    private final BufferedReader mSocketReader;
    private final Credentials peer;
    private final String abiList;
    private boolean isEof;

    /**
     * Constructs instance from connected socket.
     *
     * @param socket non-null; connected socket
     * @param abiList non-null; a list of ABIs this zygote supports.
     * @throws IOException
     */
    ZygoteConnection(LocalSocket socket, String abiList) throws IOException {
        mSocket = socket;
        this.abiList = abiList;

        mSocketOutStream
                = new DataOutputStream(socket.getOutputStream());

        mSocketReader = new BufferedReader(
                new InputStreamReader(socket.getInputStream()), 256);

        mSocket.setSoTimeout(CONNECTION_TIMEOUT_MILLIS);

        try {
            peer = mSocket.getPeerCredentials();
        } catch (IOException ex) {
            Log.e(TAG, "Cannot read peer credentials", ex);
            throw ex;
        }

        isEof = false;
    }

    /**
     * Returns the file descriptor of the associated socket.
     *
     * @return null-ok; file descriptor
     */
    FileDescriptor getFileDesciptor() {
        return mSocket.getFileDescriptor();
    }

    /**
     * Reads one start command from the command socket. If successful, a child is forked and a
     * {@code Runnable} that calls the childs main method (or equivalent) is returned in the child
     * process. {@code null} is always returned in the parent process (the zygote).
     *
     * If the client closes the socket, an {@code EOF} condition is set, which callers can test
     * for by calling {@code ZygoteConnection.isClosedByPeer}.
     */
    Runnable processOneCommand(ZygoteServer zygoteServer) {
        String args[];
        Arguments parsedArgs = null;
        FileDescriptor[] descriptors;

        try {
            args = readArgumentList();
            descriptors = mSocket.getAncillaryFileDescriptors();
        } catch (IOException ex) {
            throw new IllegalStateException("IOException on command socket", ex);
        }

        // readArgumentList returns null only when it has reached EOF with no available
        // data to read. This will only happen when the remote socket has disconnected.
        if (args == null) {
            isEof = true;
            return null;
        }

        int pid = -1;
        FileDescriptor childPipeFd = null;
        FileDescriptor serverPipeFd = null;

        parsedArgs = new Arguments(args);

        if (parsedArgs.abiListQuery) {
            handleAbiListQuery();
            return null;
        }

        if (parsedArgs.preloadDefault) {
            handlePreload();
            return null;
        }

        if (parsedArgs.preloadPackage != null) {
            handlePreloadPackage(parsedArgs.preloadPackage, parsedArgs.preloadPackageLibs,
                    parsedArgs.preloadPackageCacheKey);
            return null;
        }

        if (parsedArgs.permittedCapabilities != 0 || parsedArgs.effectiveCapabilities != 0) {
            throw new ZygoteSecurityException("Client may not specify capabilities: " +
                    "permitted=0x" + Long.toHexString(parsedArgs.permittedCapabilities) +
                    ", effective=0x" + Long.toHexString(parsedArgs.effectiveCapabilities));
        }

        applyUidSecurityPolicy(parsedArgs, peer);
        applyInvokeWithSecurityPolicy(parsedArgs, peer);

        applyDebuggerSystemProperty(parsedArgs);
        applyInvokeWithSystemProperty(parsedArgs);

        int[][] rlimits = null;

        if (parsedArgs.rlimits != null) {
            rlimits = parsedArgs.rlimits.toArray(intArray2d);
        }

        int[] fdsToIgnore = null;

        if (parsedArgs.invokeWith != null) {
            try {
                FileDescriptor[] pipeFds = Os.pipe2(O_CLOEXEC);
                childPipeFd = pipeFds[1];
                serverPipeFd = pipeFds[0];
                Os.fcntlInt(childPipeFd, F_SETFD, 0);
                fdsToIgnore = new int[]{childPipeFd.getInt$(), serverPipeFd.getInt$()};
            } catch (ErrnoException errnoEx) {
                throw new IllegalStateException("Unable to set up pipe for invoke-with", errnoEx);
            }
        }

        /**
         * In order to avoid leaking descriptors to the Zygote child,
         * the native code must close the two Zygote socket descriptors
         * in the child process before it switches from Zygote-root to
         * the UID and privileges of the application being launched.
         *
         * In order to avoid "bad file descriptor" errors when the
         * two LocalSocket objects are closed, the Posix file
         * descriptors are released via a dup2() call which closes
         * the socket and substitutes an open descriptor to /dev/null.
         */

        int [] fdsToClose = { -1, -1 };

        FileDescriptor fd = mSocket.getFileDescriptor();

        if (fd != null) {
            fdsToClose[0] = fd.getInt$();
        }

        fd = zygoteServer.getServerSocketFileDescriptor();

        if (fd != null) {
            fdsToClose[1] = fd.getInt$();
        }

        fd = null;

        pid = Zygote.forkAndSpecialize(parsedArgs.uid, parsedArgs.gid, parsedArgs.gids,
                parsedArgs.debugFlags, rlimits, parsedArgs.mountExternal, parsedArgs.seInfo,
                parsedArgs.niceName, fdsToClose, fdsToIgnore, parsedArgs.instructionSet,
                parsedArgs.appDataDir);

        try {
            if (pid == 0) {
                // in child
                zygoteServer.setForkChild();

                zygoteServer.closeServerSocket();
                IoUtils.closeQuietly(serverPipeFd);
                serverPipeFd = null;

                return handleChildProc(parsedArgs, descriptors, childPipeFd);
            } else {
                // In the parent. A pid < 0 indicates a failure and will be handled in
                // handleParentProc.
                IoUtils.closeQuietly(childPipeFd);
                childPipeFd = null;
                handleParentProc(pid, descriptors, serverPipeFd);
                return null;
            }
        } finally {
            IoUtils.closeQuietly(childPipeFd);
            IoUtils.closeQuietly(serverPipeFd);
        }
    }

    private void handleAbiListQuery() {
        try {
            final byte[] abiListBytes = abiList.getBytes(StandardCharsets.US_ASCII);
            mSocketOutStream.writeInt(abiListBytes.length);
            mSocketOutStream.write(abiListBytes);
        } catch (IOException ioe) {
            throw new IllegalStateException("Error writing to command socket", ioe);
        }
    }

    /**
     * Preloads resources if the zygote is in lazily preload mode. Writes the result of the
     * preload operation; {@code 0} when a preload was initiated due to this request and {@code 1}
     * if no preload was initiated. The latter implies that the zygote is not configured to load
     * resources lazy or that the zygote has already handled a previous request to handlePreload.
     */
    private void handlePreload() {
        try {
            if (isPreloadComplete()) {
                mSocketOutStream.writeInt(1);
            } else {
                preload();
                mSocketOutStream.writeInt(0);
            }
        } catch (IOException ioe) {
            throw new IllegalStateException("Error writing to command socket", ioe);
        }
    }

    protected void preload() {
        ZygoteInit.lazyPreload();
    }

    protected boolean isPreloadComplete() {
        return ZygoteInit.isPreloadComplete();
    }

    protected DataOutputStream getSocketOutputStream() {
        return mSocketOutStream;
    }

    protected void handlePreloadPackage(String packagePath, String libsPath, String cacheKey) {
        throw new RuntimeException("Zyogte does not support package preloading");
    }

    /**
     * Closes socket associated with this connection.
     */
    void closeSocket() {
        try {
            mSocket.close();
        } catch (IOException ex) {
            Log.e(TAG, "Exception while closing command "
                    + "socket in parent", ex);
        }
    }

    boolean isClosedByPeer() {
        return isEof;
    }

    /**
     * Handles argument parsing for args related to the zygote spawner.
     *
     * Current recognized args:
     * <ul>
     *   <li> --setuid=<i>uid of child process, defaults to 0</i>
     *   <li> --setgid=<i>gid of child process, defaults to 0</i>
     *   <li> --setgroups=<i>comma-separated list of supplimentary gid's</i>
     *   <li> --capabilities=<i>a pair of comma-separated integer strings
     * indicating Linux capabilities(2) set for child. The first string
     * represents the <code>permitted</code> set, and the second the
     * <code>effective</code> set. Precede each with 0 or
     * 0x for octal or hexidecimal value. If unspecified, both default to 0.
     * This parameter is only applied if the uid of the new process will
     * be non-0. </i>
     *   <li> --rlimit=r,c,m<i>tuple of values for setrlimit() call.
     *    <code>r</code> is the resource, <code>c</code> and <code>m</code>
     *    are the settings for current and max value.</i>
     *   <li> --instruction-set=<i>instruction-set-string</i> which instruction set to use/emulate.
     *   <li> --nice-name=<i>nice name to appear in ps</i>
     *   <li> --runtime-args indicates that the remaining arg list should
     * be handed off to com.android.internal.os.RuntimeInit, rather than
     * processed directly.
     * Android runtime startup (eg, Binder initialization) is also eschewed.
     *   <li> [--] &lt;args for RuntimeInit &gt;
     * </ul>
     */
    static class Arguments {
        /** from --setuid */
        int uid = 0;
        boolean uidSpecified;

        /** from --setgid */
        int gid = 0;
        boolean gidSpecified;

        /** from --setgroups */
        int[] gids;

        /**
         * From --enable-jdwp, --enable-checkjni, --enable-assert,
         * --enable-safemode, --generate-debug-info, --enable-jni-logging,
         * --java-debuggable, and --native-debuggable.
         */
        int debugFlags;

        /** From --mount-external */
        int mountExternal = Zygote.MOUNT_EXTERNAL_NONE;

        /** from --target-sdk-version. */
        int targetSdkVersion;
        boolean targetSdkVersionSpecified;

        /** from --nice-name */
        String niceName;

        /** from --capabilities */
        boolean capabilitiesSpecified;
        long permittedCapabilities;
        long effectiveCapabilities;

        /** from --seinfo */
        boolean seInfoSpecified;
        String seInfo;

        /** from all --rlimit=r,c,m */
        ArrayList<int[]> rlimits;

        /** from --invoke-with */
        String invokeWith;

        /**
         * Any args after and including the first non-option arg
         * (or after a '--')
         */
        String remainingArgs[];

        /**
         * Whether the current arguments constitute an ABI list query.
         */
        boolean abiListQuery;

        /**
         * The instruction set to use, or null when not important.
         */
        String instructionSet;

        /**
         * The app data directory. May be null, e.g., for the system server. Note that this might
         * not be reliable in the case of process-sharing apps.
         */
        String appDataDir;

        /**
         * Whether to preload a package, with the package path in the remainingArgs.
         */
        String preloadPackage;
        String preloadPackageLibs;
        String preloadPackageCacheKey;

        /**
         * Whether this is a request to start preloading the default resources and classes.
         * This argument only makes sense when the zygote is in lazy preload mode (i.e, when
         * it's started with --enable-lazy-preload).
         */
        boolean preloadDefault;

        /**
         * Constructs instance and parses args
         * @param args zygote command-line args
         * @throws IllegalArgumentException
         */
        Arguments(String args[]) throws IllegalArgumentException {
            parseArgs(args);
        }

        /**
         * Parses the commandline arguments intended for the Zygote spawner
         * (such as "--setuid=" and "--setgid=") and creates an array
         * containing the remaining args.
         *
         * Per security review bug #1112214, duplicate args are disallowed in
         * critical cases to make injection harder.
         */
        private void parseArgs(String args[])
                throws IllegalArgumentException {
            int curArg = 0;

            boolean seenRuntimeArgs = false;

            for ( /* curArg */ ; curArg < args.length; curArg++) {
                String arg = args[curArg];

                if (arg.equals("--")) {
                    curArg++;
                    break;
                } else if (arg.startsWith("--setuid=")) {
                    if (uidSpecified) {
                        throw new IllegalArgumentException(
                                "Duplicate arg specified");
                    }
                    uidSpecified = true;
                    uid = Integer.parseInt(
                            arg.substring(arg.indexOf('=') + 1));
                } else if (arg.startsWith("--setgid=")) {
                    if (gidSpecified) {
                        throw new IllegalArgumentException(
                                "Duplicate arg specified");
                    }
                    gidSpecified = true;
                    gid = Integer.parseInt(
                            arg.substring(arg.indexOf('=') + 1));
                } else if (arg.startsWith("--target-sdk-version=")) {
                    if (targetSdkVersionSpecified) {
                        throw new IllegalArgumentException(
                                "Duplicate target-sdk-version specified");
                    }
                    targetSdkVersionSpecified = true;
                    targetSdkVersion = Integer.parseInt(
                            arg.substring(arg.indexOf('=') + 1));
                } else if (arg.equals("--enable-jdwp")) {
                    debugFlags |= Zygote.DEBUG_ENABLE_JDWP;
                } else if (arg.equals("--enable-safemode")) {
                    debugFlags |= Zygote.DEBUG_ENABLE_SAFEMODE;
                } else if (arg.equals("--enable-checkjni")) {
                    debugFlags |= Zygote.DEBUG_ENABLE_CHECKJNI;
                } else if (arg.equals("--generate-debug-info")) {
                    debugFlags |= Zygote.DEBUG_GENERATE_DEBUG_INFO;
                } else if (arg.equals("--always-jit")) {
                    debugFlags |= Zygote.DEBUG_ALWAYS_JIT;
                } else if (arg.equals("--native-debuggable")) {
                    debugFlags |= Zygote.DEBUG_NATIVE_DEBUGGABLE;
                } else if (arg.equals("--java-debuggable")) {
                    debugFlags |= Zygote.DEBUG_JAVA_DEBUGGABLE;
                } else if (arg.equals("--enable-jni-logging")) {
                    debugFlags |= Zygote.DEBUG_ENABLE_JNI_LOGGING;
                } else if (arg.equals("--enable-assert")) {
                    debugFlags |= Zygote.DEBUG_ENABLE_ASSERT;
                } else if (arg.equals("--runtime-args")) {
                    seenRuntimeArgs = true;
                } else if (arg.startsWith("--seinfo=")) {
                    if (seInfoSpecified) {
                        throw new IllegalArgumentException(
                                "Duplicate arg specified");
                    }
                    seInfoSpecified = true;
                    seInfo = arg.substring(arg.indexOf('=') + 1);
                } else if (arg.startsWith("--capabilities=")) {
                    if (capabilitiesSpecified) {
                        throw new IllegalArgumentException(
                                "Duplicate arg specified");
                    }
                    capabilitiesSpecified = true;
                    String capString = arg.substring(arg.indexOf('=')+1);

                    String[] capStrings = capString.split(",", 2);

                    if (capStrings.length == 1) {
                        effectiveCapabilities = Long.decode(capStrings[0]);
                        permittedCapabilities = effectiveCapabilities;
                    } else {
                        permittedCapabilities = Long.decode(capStrings[0]);
                        effectiveCapabilities = Long.decode(capStrings[1]);
                    }
                } else if (arg.startsWith("--rlimit=")) {
                    // Duplicate --rlimit arguments are specifically allowed.
                    String[] limitStrings
                            = arg.substring(arg.indexOf('=')+1).split(",");

                    if (limitStrings.length != 3) {
                        throw new IllegalArgumentException(
                                "--rlimit= should have 3 comma-delimited ints");
                    }
                    int[] rlimitTuple = new int[limitStrings.length];

                    for(int i=0; i < limitStrings.length; i++) {
                        rlimitTuple[i] = Integer.parseInt(limitStrings[i]);
                    }

                    if (rlimits == null) {
                        rlimits = new ArrayList();
                    }

                    rlimits.add(rlimitTuple);
                } else if (arg.startsWith("--setgroups=")) {
                    if (gids != null) {
                        throw new IllegalArgumentException(
                                "Duplicate arg specified");
                    }

                    String[] params
                            = arg.substring(arg.indexOf('=') + 1).split(",");

                    gids = new int[params.length];

                    for (int i = params.length - 1; i >= 0 ; i--) {
                        gids[i] = Integer.parseInt(params[i]);
                    }
                } else if (arg.equals("--invoke-with")) {
                    if (invokeWith != null) {
                        throw new IllegalArgumentException(
                                "Duplicate arg specified");
                    }
                    try {
                        invokeWith = args[++curArg];
                    } catch (IndexOutOfBoundsException ex) {
                        throw new IllegalArgumentException(
                                "--invoke-with requires argument");
                    }
                } else if (arg.startsWith("--nice-name=")) {
                    if (niceName != null) {
                        throw new IllegalArgumentException(
                                "Duplicate arg specified");
                    }
                    niceName = arg.substring(arg.indexOf('=') + 1);
                } else if (arg.equals("--mount-external-default")) {
                    mountExternal = Zygote.MOUNT_EXTERNAL_DEFAULT;
                } else if (arg.equals("--mount-external-read")) {
                    mountExternal = Zygote.MOUNT_EXTERNAL_READ;
                } else if (arg.equals("--mount-external-write")) {
                    mountExternal = Zygote.MOUNT_EXTERNAL_WRITE;
                } else if (arg.equals("--query-abi-list")) {
                    abiListQuery = true;
                } else if (arg.startsWith("--instruction-set=")) {
                    instructionSet = arg.substring(arg.indexOf('=') + 1);
                } else if (arg.startsWith("--app-data-dir=")) {
                    appDataDir = arg.substring(arg.indexOf('=') + 1);
                } else if (arg.equals("--preload-package")) {
                    preloadPackage = args[++curArg];
                    preloadPackageLibs = args[++curArg];
                    preloadPackageCacheKey = args[++curArg];
                } else if (arg.equals("--preload-default")) {
                    preloadDefault = true;
                } else {
                    break;
                }
            }

            if (abiListQuery) {
                if (args.length - curArg > 0) {
                    throw new IllegalArgumentException("Unexpected arguments after --query-abi-list.");
                }
            } else if (preloadPackage != null) {
                if (args.length - curArg > 0) {
                    throw new IllegalArgumentException(
                            "Unexpected arguments after --preload-package.");
                }
            } else if (!preloadDefault) {
                if (!seenRuntimeArgs) {
                    throw new IllegalArgumentException("Unexpected argument : " + args[curArg]);
                }

                remainingArgs = new String[args.length - curArg];
                System.arraycopy(args, curArg, remainingArgs, 0, remainingArgs.length);
            }
        }
    }

    /**
     * Reads an argument list from the command socket/
     * @return Argument list or null if EOF is reached
     * @throws IOException passed straight through
     */
    private String[] readArgumentList()
            throws IOException {

        /**
         * See android.os.Process.zygoteSendArgsAndGetPid()
         * Presently the wire format to the zygote process is:
         * a) a count of arguments (argc, in essence)
         * b) a number of newline-separated argument strings equal to count
         *
         * After the zygote process reads these it will write the pid of
         * the child or -1 on failure.
         */

        int argc;

        try {
            String s = mSocketReader.readLine();

            if (s == null) {
                // EOF reached.
                return null;
            }
            argc = Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            Log.e(TAG, "invalid Zygote wire format: non-int at argc");
            throw new IOException("invalid wire format");
        }

        // See bug 1092107: large argc can be used for a DOS attack
        if (argc > MAX_ZYGOTE_ARGC) {
            throw new IOException("max arg count exceeded");
        }

        String[] result = new String[argc];
        for (int i = 0; i < argc; i++) {
            result[i] = mSocketReader.readLine();
            if (result[i] == null) {
                // We got an unexpected EOF.
                throw new IOException("truncated request");
            }
        }

        return result;
    }

    /**
     * uid 1000 (Process.SYSTEM_UID) may specify any uid &gt; 1000 in normal
     * operation. It may also specify any gid and setgroups() list it chooses.
     * In factory test mode, it may specify any UID.
     *
     * @param args non-null; zygote spawner arguments
     * @param peer non-null; peer credentials
     * @throws ZygoteSecurityException
     */
    private static void applyUidSecurityPolicy(Arguments args, Credentials peer)
            throws ZygoteSecurityException {

        if (peer.getUid() == Process.SYSTEM_UID) {
            /* In normal operation, SYSTEM_UID can only specify a restricted
             * set of UIDs. In factory test mode, SYSTEM_UID may specify any uid.
             */
            boolean uidRestricted = FactoryTest.getMode() == FactoryTest.FACTORY_TEST_OFF;

            if (uidRestricted && args.uidSpecified && (args.uid < Process.SYSTEM_UID)) {
                throw new ZygoteSecurityException(
                        "System UID may not launch process with UID < "
                        + Process.SYSTEM_UID);
            }
        }

        // If not otherwise specified, uid and gid are inherited from peer
        if (!args.uidSpecified) {
            args.uid = peer.getUid();
            args.uidSpecified = true;
        }
        if (!args.gidSpecified) {
            args.gid = peer.getGid();
            args.gidSpecified = true;
        }
    }

    /**
     * Applies debugger system properties to the zygote arguments.
     *
     * If "ro.debuggable" is "1", all apps are debuggable. Otherwise,
     * the debugger state is specified via the "--enable-jdwp" flag
     * in the spawn request.
     *
     * @param args non-null; zygote spawner args
     */
    public static void applyDebuggerSystemProperty(Arguments args) {
        if (RoSystemProperties.DEBUGGABLE) {
            args.debugFlags |= Zygote.DEBUG_ENABLE_JDWP;
        }
    }

    /**
     * Applies zygote security policy.
     * Based on the credentials of the process issuing a zygote command:
     * <ol>
     * <li> uid 0 (root) may specify --invoke-with to launch Zygote with a
     * wrapper command.
     * <li> Any other uid may not specify any invoke-with argument.
     * </ul>
     *
     * @param args non-null; zygote spawner arguments
     * @param peer non-null; peer credentials
     * @throws ZygoteSecurityException
     */
    private static void applyInvokeWithSecurityPolicy(Arguments args, Credentials peer)
            throws ZygoteSecurityException {
        int peerUid = peer.getUid();

        if (args.invokeWith != null && peerUid != 0 &&
            (args.debugFlags & Zygote.DEBUG_ENABLE_JDWP) == 0) {
            throw new ZygoteSecurityException("Peer is permitted to specify an"
                    + "explicit invoke-with wrapper command only for debuggable"
                    + "applications.");
        }
    }

    /**
     * Applies invoke-with system properties to the zygote arguments.
     *
     * @param args non-null; zygote args
     */
    public static void applyInvokeWithSystemProperty(Arguments args) {
        if (args.invokeWith == null && args.niceName != null) {
            String property = "wrap." + args.niceName;
            args.invokeWith = SystemProperties.get(property);
            if (args.invokeWith != null && args.invokeWith.length() == 0) {
                args.invokeWith = null;
            }
        }
    }

    /**
     * Handles post-fork setup of child proc, closing sockets as appropriate,
     * reopen stdio as appropriate, and ultimately throwing MethodAndArgsCaller
     * if successful or returning if failed.
     *
     * @param parsedArgs non-null; zygote args
     * @param descriptors null-ok; new file descriptors for stdio if available.
     * @param pipeFd null-ok; pipe for communication back to Zygote.
     */
    private Runnable handleChildProc(Arguments parsedArgs, FileDescriptor[] descriptors,
            FileDescriptor pipeFd) {
        /**
         * By the time we get here, the native code has closed the two actual Zygote
         * socket connections, and substituted /dev/null in their place.  The LocalSocket
         * objects still need to be closed properly.
         */

        closeSocket();
        if (descriptors != null) {
            try {
                Os.dup2(descriptors[0], STDIN_FILENO);
                Os.dup2(descriptors[1], STDOUT_FILENO);
                Os.dup2(descriptors[2], STDERR_FILENO);

                for (FileDescriptor fd: descriptors) {
                    IoUtils.closeQuietly(fd);
                }
            } catch (ErrnoException ex) {
                Log.e(TAG, "Error reopening stdio", ex);
            }
        }

        if (parsedArgs.niceName != null) {
            Process.setArgV0(parsedArgs.niceName);
        }

        // End of the postFork event.
        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        if (parsedArgs.invokeWith != null) {
            WrapperInit.execApplication(parsedArgs.invokeWith,
                    parsedArgs.niceName, parsedArgs.targetSdkVersion,
                    VMRuntime.getCurrentInstructionSet(),
                    pipeFd, parsedArgs.remainingArgs);

            // Should not get here.
            throw new IllegalStateException("WrapperInit.execApplication unexpectedly returned");
        } else {
            return ZygoteInit.zygoteInit(parsedArgs.targetSdkVersion, parsedArgs.remainingArgs,
                    null /* classLoader */);
        }
    }

    /**
     * Handles post-fork cleanup of parent proc
     *
     * @param pid != 0; pid of child if &gt; 0 or indication of failed fork
     * if &lt; 0;
     * @param descriptors null-ok; file descriptors for child's new stdio if
     * specified.
     * @param pipeFd null-ok; pipe for communication with child.
     */
    private void handleParentProc(int pid, FileDescriptor[] descriptors, FileDescriptor pipeFd) {
        if (pid > 0) {
            setChildPgid(pid);
        }

        if (descriptors != null) {
            for (FileDescriptor fd: descriptors) {
                IoUtils.closeQuietly(fd);
            }
        }

        boolean usingWrapper = false;
        if (pipeFd != null && pid > 0) {
            int innerPid = -1;
            try {
                // Do a busy loop here. We can't guarantee that a failure (and thus an exception
                // bail) happens in a timely manner.
                final int BYTES_REQUIRED = 4;  // Bytes in an int.

                StructPollfd fds[] = new StructPollfd[] {
                        new StructPollfd()
                };

                byte data[] = new byte[BYTES_REQUIRED];

                int remainingSleepTime = WRAPPED_PID_TIMEOUT_MILLIS;
                int dataIndex = 0;
                long startTime = System.nanoTime();

                while (dataIndex < data.length && remainingSleepTime > 0) {
                    fds[0].fd = pipeFd;
                    fds[0].events = (short) POLLIN;
                    fds[0].revents = 0;
                    fds[0].userData = null;

                    int res = android.system.Os.poll(fds, remainingSleepTime);
                    long endTime = System.nanoTime();
                    int elapsedTimeMs = (int)((endTime - startTime) / 1000000l);
                    remainingSleepTime = WRAPPED_PID_TIMEOUT_MILLIS - elapsedTimeMs;

                    if (res > 0) {
                        if ((fds[0].revents & POLLIN) != 0) {
                            // Only read one byte, so as not to block.
                            int readBytes = android.system.Os.read(pipeFd, data, dataIndex, 1);
                            if (readBytes < 0) {
                                throw new RuntimeException("Some error");
                            }
                            dataIndex += readBytes;
                        } else {
                            // Error case. revents should contain one of the error bits.
                            break;
                        }
                    } else if (res == 0) {
                        Log.w(TAG, "Timed out waiting for child.");
                    }
                }

                if (dataIndex == data.length) {
                    DataInputStream is = new DataInputStream(new ByteArrayInputStream(data));
                    innerPid = is.readInt();
                }

                if (innerPid == -1) {
                    Log.w(TAG, "Error reading pid from wrapped process, child may have died");
                }
            } catch (Exception ex) {
                Log.w(TAG, "Error reading pid from wrapped process, child may have died", ex);
            }

            // Ensure that the pid reported by the wrapped process is either the
            // child process that we forked, or a descendant of it.
            if (innerPid > 0) {
                int parentPid = innerPid;
                while (parentPid > 0 && parentPid != pid) {
                    parentPid = Process.getParentPid(parentPid);
                }
                if (parentPid > 0) {
                    Log.i(TAG, "Wrapped process has pid " + innerPid);
                    pid = innerPid;
                    usingWrapper = true;
                } else {
                    Log.w(TAG, "Wrapped process reported a pid that is not a child of "
                            + "the process that we forked: childPid=" + pid
                            + " innerPid=" + innerPid);
                }
            }
        }

        try {
            mSocketOutStream.writeInt(pid);
            mSocketOutStream.writeBoolean(usingWrapper);
        } catch (IOException ex) {
            throw new IllegalStateException("Error writing to command socket", ex);
        }
    }

    private void setChildPgid(int pid) {
        // Try to move the new child into the peer's process group.
        try {
            Os.setpgid(pid, Os.getpgid(peer.getPid()));
        } catch (ErrnoException ex) {
            // This exception is expected in the case where
            // the peer is not in our session
            // TODO get rid of this log message in the case where
            // getsid(0) != getsid(peer.getPid())
            Log.i(TAG, "Zygote: setpgid failed. This is "
                + "normal if peer is not in our session");
        }
    }
}
