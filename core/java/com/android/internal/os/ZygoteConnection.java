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

import android.net.Credentials;
import android.net.LocalSocket;
import android.os.Process;
import android.os.SELinux;
import android.os.SystemProperties;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import dalvik.system.PathClassLoader;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import libcore.io.IoUtils;
import android.os.SystemClock;
import android.util.Slog;

/**
 * A connection that can make spawn requests.
 */
class ZygoteConnection {
    private static final String TAG = "Zygote";

    /** a prototype instance for a future List.toArray() */
    private static final int[][] intArray2d = new int[0][0];

    /**
     * {@link android.net.LocalSocket#setSoTimeout} value for connections.
     * Effectively, the amount of time a requestor has between the start of
     * the request and the completed request. The select-loop mode Zygote
     * doesn't have the logic to return to the select loop in the middle of
     * a request, so we need to time out here to avoid being denial-of-serviced.
     */
    private static final int CONNECTION_TIMEOUT_MILLIS = 1000;

    /** max number of arguments that a connection can specify */
    private static final int MAX_ZYGOTE_ARGC = 1024;

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
    private final String peerSecurityContext;
    private final String abiList;

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

        peerSecurityContext = SELinux.getPeerContext(mSocket.getFileDescriptor());
    }

    /**
     * Temporary hack: check time since start time and log if over a fixed threshold.
     *
     */
    private void checkTime(long startTime, String where) {
        long now = SystemClock.elapsedRealtime();
        if ((now-startTime) > 1000) {
            // If we are taking more than a second, log about it.
            Slog.w(TAG, "Slow operation: " + (now-startTime) + "ms so far, now at " + where);
        }
    }

    /**
     * Returns the file descriptor of the associated socket.
     *
     * @return null-ok; file descriptor
     */
    FileDescriptor getFileDescriptor() {
        return mSocket.getFileDescriptor();
    }

    /**
     * Reads one start command from the command socket. If successful,
     * a child is forked and a {@link ZygoteInit.MethodAndArgsCaller}
     * exception is thrown in that child while in the parent process,
     * the method returns normally. On failure, the child is not
     * spawned and messages are printed to the log and stderr. Returns
     * a boolean status value indicating whether an end-of-file on the command
     * socket has been encountered.
     *
     * @return false if command socket should continue to be read from, or
     * true if an end-of-file has been encountered.
     * @throws ZygoteInit.MethodAndArgsCaller trampoline to invoke main()
     * method in child process
     */
    boolean runOnce() throws ZygoteInit.MethodAndArgsCaller {

        String args[];
        Arguments parsedArgs = null;
        FileDescriptor[] descriptors;

        long startTime = SystemClock.elapsedRealtime();

        try {
            args = readArgumentList();
            descriptors = mSocket.getAncillaryFileDescriptors();
        } catch (IOException ex) {
            Log.w(TAG, "IOException on command socket " + ex.getMessage());
            closeSocket();
            return true;
        }

        checkTime(startTime, "zygoteConnection.runOnce: readArgumentList");
        if (args == null) {
            // EOF reached.
            closeSocket();
            return true;
        }

        /** the stderr of the most recent request, if avail */
        PrintStream newStderr = null;

        if (descriptors != null && descriptors.length >= 3) {
            newStderr = new PrintStream(
                    new FileOutputStream(descriptors[2]));
        }

        int pid = -1;
        FileDescriptor childPipeFd = null;
        FileDescriptor serverPipeFd = null;

        try {
            parsedArgs = new Arguments(args);

            if (parsedArgs.abiListQuery) {
                return handleAbiListQuery();
            }

            if (parsedArgs.permittedCapabilities != 0 || parsedArgs.effectiveCapabilities != 0) {
                throw new ZygoteSecurityException("Client may not specify capabilities: " +
                        "permitted=0x" + Long.toHexString(parsedArgs.permittedCapabilities) +
                        ", effective=0x" + Long.toHexString(parsedArgs.effectiveCapabilities));
            }


            applyUidSecurityPolicy(parsedArgs, peer, peerSecurityContext);
            applyRlimitSecurityPolicy(parsedArgs, peer, peerSecurityContext);
            applyInvokeWithSecurityPolicy(parsedArgs, peer, peerSecurityContext);
            applyseInfoSecurityPolicy(parsedArgs, peer, peerSecurityContext);

            checkTime(startTime, "zygoteConnection.runOnce: apply security policies");

            applyDebuggerSystemProperty(parsedArgs);
            applyInvokeWithSystemProperty(parsedArgs);

            checkTime(startTime, "zygoteConnection.runOnce: apply security policies");

            int[][] rlimits = null;

            if (parsedArgs.rlimits != null) {
                rlimits = parsedArgs.rlimits.toArray(intArray2d);
            }

            if (parsedArgs.runtimeInit && parsedArgs.invokeWith != null) {
                FileDescriptor[] pipeFds = Os.pipe();
                childPipeFd = pipeFds[1];
                serverPipeFd = pipeFds[0];
                ZygoteInit.setCloseOnExec(serverPipeFd, true);
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

            fd = ZygoteInit.getServerSocketFileDescriptor();

            if (fd != null) {
                fdsToClose[1] = fd.getInt$();
            }

            fd = null;

            checkTime(startTime, "zygoteConnection.runOnce: preForkAndSpecialize");
            pid = Zygote.forkAndSpecialize(parsedArgs.uid, parsedArgs.gid, parsedArgs.gids,
                    parsedArgs.debugFlags, rlimits, parsedArgs.mountExternal, parsedArgs.seInfo,
                    parsedArgs.niceName, fdsToClose, parsedArgs.instructionSet,
                    parsedArgs.appDataDir);
            checkTime(startTime, "zygoteConnection.runOnce: postForkAndSpecialize");
        } catch (IOException ex) {
            logAndPrintError(newStderr, "Exception creating pipe", ex);
        } catch (ErrnoException ex) {
            logAndPrintError(newStderr, "Exception creating pipe", ex);
        } catch (IllegalArgumentException ex) {
            logAndPrintError(newStderr, "Invalid zygote arguments", ex);
        } catch (ZygoteSecurityException ex) {
            logAndPrintError(newStderr,
                    "Zygote security policy prevents request: ", ex);
        }

        try {
            if (pid == 0) {
                // in child
                IoUtils.closeQuietly(serverPipeFd);
                serverPipeFd = null;
                handleChildProc(parsedArgs, descriptors, childPipeFd, newStderr);

                // should never get here, the child is expected to either
                // throw ZygoteInit.MethodAndArgsCaller or exec().
                return true;
            } else {
                // in parent...pid of < 0 means failure
                IoUtils.closeQuietly(childPipeFd);
                childPipeFd = null;
                return handleParentProc(pid, descriptors, serverPipeFd, parsedArgs);
            }
        } finally {
            IoUtils.closeQuietly(childPipeFd);
            IoUtils.closeQuietly(serverPipeFd);
        }
    }

    private boolean handleAbiListQuery() {
        try {
            final byte[] abiListBytes = abiList.getBytes(StandardCharsets.US_ASCII);
            mSocketOutStream.writeInt(abiListBytes.length);
            mSocketOutStream.write(abiListBytes);
            return false;
        } catch (IOException ioe) {
            Log.e(TAG, "Error writing to command socket", ioe);
            return true;
        }
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
     *   <li> --classpath=<i>colon-separated classpath</i> indicates
     * that the specified class (which must b first non-flag argument) should
     * be loaded from jar files in the specified classpath. Incompatible with
     * --runtime-init
     *   <li> --runtime-init indicates that the remaining arg list should
     * be handed off to com.android.internal.os.RuntimeInit, rather than
     * processed directly
     * Android runtime startup (eg, Binder initialization) is also eschewed.
     *   <li> --nice-name=<i>nice name to appear in ps</i>
     *   <li> If <code>--runtime-init</code> is present:
     *      [--] &lt;args for RuntimeInit &gt;
     *   <li> If <code>--runtime-init</code> is absent:
     *      [--] &lt;classname&gt; [args...]
     *   <li> --instruction-set=<i>instruction-set-string</i> which instruction set to use/emulate.
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
         * From --enable-debugger, --enable-checkjni, --enable-assert,
         * --enable-safemode, and --enable-jni-logging.
         */
        int debugFlags;

        /** From --mount-external */
        int mountExternal = Zygote.MOUNT_EXTERNAL_NONE;

        /** from --target-sdk-version. */
        int targetSdkVersion;
        boolean targetSdkVersionSpecified;

        /** from --classpath */
        String classpath;

        /** from --runtime-init */
        boolean runtimeInit;

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
                } else if (arg.equals("--enable-debugger")) {
                    debugFlags |= Zygote.DEBUG_ENABLE_DEBUGGER;
                } else if (arg.equals("--enable-safemode")) {
                    debugFlags |= Zygote.DEBUG_ENABLE_SAFEMODE;
                } else if (arg.equals("--enable-checkjni")) {
                    debugFlags |= Zygote.DEBUG_ENABLE_CHECKJNI;
                } else if (arg.equals("--enable-jni-logging")) {
                    debugFlags |= Zygote.DEBUG_ENABLE_JNI_LOGGING;
                } else if (arg.equals("--enable-assert")) {
                    debugFlags |= Zygote.DEBUG_ENABLE_ASSERT;
                } else if (arg.equals("--runtime-init")) {
                    runtimeInit = true;
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
                } else if (arg.equals("-classpath")) {
                    if (classpath != null) {
                        throw new IllegalArgumentException(
                                "Duplicate arg specified");
                    }
                    try {
                        classpath = args[++curArg];
                    } catch (IndexOutOfBoundsException ex) {
                        throw new IllegalArgumentException(
                                "-classpath requires argument");
                    }
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
                } else if (arg.equals("--mount-external-multiuser")) {
                    mountExternal = Zygote.MOUNT_EXTERNAL_MULTIUSER;
                } else if (arg.equals("--mount-external-multiuser-all")) {
                    mountExternal = Zygote.MOUNT_EXTERNAL_MULTIUSER_ALL;
                } else if (arg.equals("--query-abi-list")) {
                    abiListQuery = true;
                } else if (arg.startsWith("--instruction-set=")) {
                    instructionSet = arg.substring(arg.indexOf('=') + 1);
                } else if (arg.startsWith("--app-data-dir=")) {
                    appDataDir = arg.substring(arg.indexOf('=') + 1);
                } else {
                    break;
                }
            }

            if (runtimeInit && classpath != null) {
                throw new IllegalArgumentException(
                        "--runtime-init and -classpath are incompatible");
            }

            remainingArgs = new String[args.length - curArg];

            System.arraycopy(args, curArg, remainingArgs, 0,
                    remainingArgs.length);
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
     * Applies zygote security policy per bugs #875058 and #1082165.
     * Based on the credentials of the process issuing a zygote command:
     * <ol>
     * <li> uid 0 (root) may specify any uid, gid, and setgroups() list
     * <li> uid 1000 (Process.SYSTEM_UID) may specify any uid &gt; 1000 in normal
     * operation. It may also specify any gid and setgroups() list it chooses.
     * In factory test mode, it may specify any UID.
     * <li> Any other uid may not specify any uid, gid, or setgroups list. The
     * uid and gid will be inherited from the requesting process.
     * </ul>
     *
     * @param args non-null; zygote spawner arguments
     * @param peer non-null; peer credentials
     * @throws ZygoteSecurityException
     */
    private static void applyUidSecurityPolicy(Arguments args, Credentials peer,
            String peerSecurityContext)
            throws ZygoteSecurityException {

        int peerUid = peer.getUid();

        if (peerUid == 0) {
            // Root can do what it wants
        } else if (peerUid == Process.SYSTEM_UID ) {
            // System UID is restricted, except in factory test mode
            String factoryTest = SystemProperties.get("ro.factorytest");
            boolean uidRestricted;

            /* In normal operation, SYSTEM_UID can only specify a restricted
             * set of UIDs. In factory test mode, SYSTEM_UID may specify any uid.
             */
            uidRestricted
                 = !(factoryTest.equals("1") || factoryTest.equals("2"));

            if (uidRestricted
                    && args.uidSpecified && (args.uid < Process.SYSTEM_UID)) {
                throw new ZygoteSecurityException(
                        "System UID may not launch process with UID < "
                                + Process.SYSTEM_UID);
            }
        } else {
            // Everything else
            if (args.uidSpecified || args.gidSpecified
                || args.gids != null) {
                throw new ZygoteSecurityException(
                        "App UIDs may not specify uid's or gid's");
            }
        }

        if (args.uidSpecified || args.gidSpecified || args.gids != null) {
            boolean allowed = SELinux.checkSELinuxAccess(peerSecurityContext,
                                                         peerSecurityContext,
                                                         "zygote",
                                                         "specifyids");
            if (!allowed) {
                throw new ZygoteSecurityException(
                        "Peer may not specify uid's or gid's");
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
     * the debugger state is specified via the "--enable-debugger" flag
     * in the spawn request.
     *
     * @param args non-null; zygote spawner args
     */
    public static void applyDebuggerSystemProperty(Arguments args) {
        if ("1".equals(SystemProperties.get("ro.debuggable"))) {
            args.debugFlags |= Zygote.DEBUG_ENABLE_DEBUGGER;
        }
    }

    /**
     * Applies zygote security policy per bug #1042973. Based on the credentials
     * of the process issuing a zygote command:
     * <ol>
     * <li> peers of  uid 0 (root) and uid 1000 (Process.SYSTEM_UID)
     * may specify any rlimits.
     * <li> All other uids may not specify rlimits.
     * </ul>
     * @param args non-null; zygote spawner arguments
     * @param peer non-null; peer credentials
     * @throws ZygoteSecurityException
     */
    private static void applyRlimitSecurityPolicy(
            Arguments args, Credentials peer, String peerSecurityContext)
            throws ZygoteSecurityException {

        int peerUid = peer.getUid();

        if (!(peerUid == 0 || peerUid == Process.SYSTEM_UID)) {
            // All peers with UID other than root or SYSTEM_UID
            if (args.rlimits != null) {
                throw new ZygoteSecurityException(
                        "This UID may not specify rlimits.");
            }
        }

        if (args.rlimits != null) {
            boolean allowed = SELinux.checkSELinuxAccess(peerSecurityContext,
                                                         peerSecurityContext,
                                                         "zygote",
                                                         "specifyrlimits");
            if (!allowed) {
                throw new ZygoteSecurityException(
                        "Peer may not specify rlimits");
            }
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
    private static void applyInvokeWithSecurityPolicy(Arguments args, Credentials peer,
            String peerSecurityContext)
            throws ZygoteSecurityException {
        int peerUid = peer.getUid();

        if (args.invokeWith != null && peerUid != 0) {
            throw new ZygoteSecurityException("Peer is not permitted to specify "
                    + "an explicit invoke-with wrapper command");
        }

        if (args.invokeWith != null) {
            boolean allowed = SELinux.checkSELinuxAccess(peerSecurityContext,
                                                         peerSecurityContext,
                                                         "zygote",
                                                         "specifyinvokewith");
            if (!allowed) {
                throw new ZygoteSecurityException("Peer is not permitted to specify "
                    + "an explicit invoke-with wrapper command");
            }
        }
    }

    /**
     * Applies zygote security policy for SELinux information.
     *
     * @param args non-null; zygote spawner arguments
     * @param peer non-null; peer credentials
     * @throws ZygoteSecurityException
     */
    private static void applyseInfoSecurityPolicy(
            Arguments args, Credentials peer, String peerSecurityContext)
            throws ZygoteSecurityException {
        int peerUid = peer.getUid();

        if (args.seInfo == null) {
            // nothing to check
            return;
        }

        if (!(peerUid == 0 || peerUid == Process.SYSTEM_UID)) {
            // All peers with UID other than root or SYSTEM_UID
            throw new ZygoteSecurityException(
                    "This UID may not specify SELinux info.");
        }

        boolean allowed = SELinux.checkSELinuxAccess(peerSecurityContext,
                                                     peerSecurityContext,
                                                     "zygote",
                                                     "specifyseinfo");
        if (!allowed) {
            throw new ZygoteSecurityException(
                    "Peer may not specify SELinux info");
        }

        return;
    }

    /**
     * Applies invoke-with system properties to the zygote arguments.
     *
     * @param args non-null; zygote args
     */
    public static void applyInvokeWithSystemProperty(Arguments args) {
        if (args.invokeWith == null && args.niceName != null) {
            if (args.niceName != null) {
                String property = "wrap." + args.niceName;
                if (property.length() > 31) {
                    // Avoid creating an illegal property name when truncating.
                    if (property.charAt(30) != '.') {
                        property = property.substring(0, 31);
                    } else {
                        property = property.substring(0, 30);
                    }
                }
                args.invokeWith = SystemProperties.get(property);
                if (args.invokeWith != null && args.invokeWith.length() == 0) {
                    args.invokeWith = null;
                }
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
     * @param newStderr null-ok; stream to use for stderr until stdio
     * is reopened.
     *
     * @throws ZygoteInit.MethodAndArgsCaller on success to
     * trampoline to code that invokes static main.
     */
    private void handleChildProc(Arguments parsedArgs,
            FileDescriptor[] descriptors, FileDescriptor pipeFd, PrintStream newStderr)
            throws ZygoteInit.MethodAndArgsCaller {

        /**
         * By the time we get here, the native code has closed the two actual Zygote
         * socket connections, and substituted /dev/null in their place.  The LocalSocket
         * objects still need to be closed properly.
         */

        closeSocket();
        ZygoteInit.closeServerSocket();

        if (descriptors != null) {
            try {
                ZygoteInit.reopenStdio(descriptors[0],
                        descriptors[1], descriptors[2]);

                for (FileDescriptor fd: descriptors) {
                    IoUtils.closeQuietly(fd);
                }
                newStderr = System.err;
            } catch (IOException ex) {
                Log.e(TAG, "Error reopening stdio", ex);
            }
        }

        if (parsedArgs.niceName != null) {
            Process.setArgV0(parsedArgs.niceName);
        }

        if (parsedArgs.runtimeInit) {
            if (parsedArgs.invokeWith != null) {
                WrapperInit.execApplication(parsedArgs.invokeWith,
                        parsedArgs.niceName, parsedArgs.targetSdkVersion,
                        pipeFd, parsedArgs.remainingArgs);
            } else {
                RuntimeInit.zygoteInit(parsedArgs.targetSdkVersion,
                        parsedArgs.remainingArgs, null /* classLoader */);
            }
        } else {
            String className;
            try {
                className = parsedArgs.remainingArgs[0];
            } catch (ArrayIndexOutOfBoundsException ex) {
                logAndPrintError(newStderr,
                        "Missing required class name argument", null);
                return;
            }

            String[] mainArgs = new String[parsedArgs.remainingArgs.length - 1];
            System.arraycopy(parsedArgs.remainingArgs, 1,
                    mainArgs, 0, mainArgs.length);

            if (parsedArgs.invokeWith != null) {
                WrapperInit.execStandalone(parsedArgs.invokeWith,
                        parsedArgs.classpath, className, mainArgs);
            } else {
                ClassLoader cloader;
                if (parsedArgs.classpath != null) {
                    cloader = new PathClassLoader(parsedArgs.classpath,
                            ClassLoader.getSystemClassLoader());
                } else {
                    cloader = ClassLoader.getSystemClassLoader();
                }

                try {
                    ZygoteInit.invokeStaticMain(cloader, className, mainArgs);
                } catch (RuntimeException ex) {
                    logAndPrintError(newStderr, "Error starting.", ex);
                }
            }
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
     * @param parsedArgs non-null; zygote args
     * @return true for "exit command loop" and false for "continue command
     * loop"
     */
    private boolean handleParentProc(int pid,
            FileDescriptor[] descriptors, FileDescriptor pipeFd, Arguments parsedArgs) {

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
            DataInputStream is = new DataInputStream(new FileInputStream(pipeFd));
            int innerPid = -1;
            try {
                innerPid = is.readInt();
            } catch (IOException ex) {
                Log.w(TAG, "Error reading pid from wrapped process, child may have died", ex);
            } finally {
                try {
                    is.close();
                } catch (IOException ex) {
                }
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
            Log.e(TAG, "Error writing to command socket", ex);
            return true;
        }

        return false;
    }

    private void setChildPgid(int pid) {
        // Try to move the new child into the peer's process group.
        try {
            ZygoteInit.setpgid(pid, ZygoteInit.getpgid(peer.getPid()));
        } catch (IOException ex) {
            // This exception is expected in the case where
            // the peer is not in our session
            // TODO get rid of this log message in the case where
            // getsid(0) != getsid(peer.getPid())
            Log.i(TAG, "Zygote: setpgid failed. This is "
                + "normal if peer is not in our session");
        }
    }

    /**
     * Logs an error message and prints it to the specified stream, if
     * provided
     *
     * @param newStderr null-ok; a standard error stream
     * @param message non-null; error message
     * @param ex null-ok an exception
     */
    private static void logAndPrintError (PrintStream newStderr,
            String message, Throwable ex) {
        Log.e(TAG, message, ex);
        if (newStderr != null) {
            newStderr.println(message + (ex == null ? "" : ex));
        }
    }
}
