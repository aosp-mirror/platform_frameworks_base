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
import static com.android.internal.os.ZygoteConnectionConstants.WRAPPED_PID_TIMEOUT_MILLIS;

import android.content.pm.ApplicationInfo;
import android.metrics.LogMaker;
import android.net.Credentials;
import android.net.LocalSocket;
import android.os.Parcel;
import android.os.Process;
import android.os.Trace;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructPollfd;
import android.util.Log;
import android.util.StatsLog;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import dalvik.system.VMRuntime;

import libcore.io.IoUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A connection that can make spawn requests.
 */
class ZygoteConnection {
    private static final String TAG = "Zygote";

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
    FileDescriptor getFileDescriptor() {
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
        ZygoteArguments parsedArgs = null;
        FileDescriptor[] descriptors;

        try {
            args = Zygote.readArgumentList(mSocketReader);

            // TODO (chriswailes): Remove this and add an assert.
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

        parsedArgs = new ZygoteArguments(args);

        if (parsedArgs.mAbiListQuery) {
            handleAbiListQuery();
            return null;
        }

        if (parsedArgs.mPidQuery) {
            handlePidQuery();
            return null;
        }

        if (parsedArgs.mBlastulaPoolStatusSpecified) {
            return handleBlastulaPoolStatusChange(zygoteServer, parsedArgs.mBlastulaPoolEnabled);
        }

        if (parsedArgs.mPreloadDefault) {
            handlePreload();
            return null;
        }

        if (parsedArgs.mPreloadPackage != null) {
            handlePreloadPackage(parsedArgs.mPreloadPackage, parsedArgs.mPreloadPackageLibs,
                    parsedArgs.mPreloadPackageLibFileName, parsedArgs.mPreloadPackageCacheKey);
            return null;
        }

        if (canPreloadApp() && parsedArgs.mPreloadApp != null) {
            byte[] rawParcelData = Base64.getDecoder().decode(parsedArgs.mPreloadApp);
            Parcel appInfoParcel = Parcel.obtain();
            appInfoParcel.unmarshall(rawParcelData, 0, rawParcelData.length);
            appInfoParcel.setDataPosition(0);
            ApplicationInfo appInfo = ApplicationInfo.CREATOR.createFromParcel(appInfoParcel);
            appInfoParcel.recycle();
            if (appInfo != null) {
                handlePreloadApp(appInfo);
            } else {
                throw new IllegalArgumentException("Failed to deserialize --preload-app");
            }
            return null;
        }

        if (parsedArgs.mApiBlacklistExemptions != null) {
            return handleApiBlacklistExemptions(zygoteServer, parsedArgs.mApiBlacklistExemptions);
        }

        if (parsedArgs.mHiddenApiAccessLogSampleRate != -1
                || parsedArgs.mHiddenApiAccessStatslogSampleRate != -1) {
            return handleHiddenApiAccessLogSampleRate(zygoteServer,
                    parsedArgs.mHiddenApiAccessLogSampleRate,
                    parsedArgs.mHiddenApiAccessStatslogSampleRate);
        }

        if (parsedArgs.mPermittedCapabilities != 0 || parsedArgs.mEffectiveCapabilities != 0) {
            throw new ZygoteSecurityException("Client may not specify capabilities: "
                    + "permitted=0x" + Long.toHexString(parsedArgs.mPermittedCapabilities)
                    + ", effective=0x" + Long.toHexString(parsedArgs.mEffectiveCapabilities));
        }

        Zygote.applyUidSecurityPolicy(parsedArgs, peer);
        Zygote.applyInvokeWithSecurityPolicy(parsedArgs, peer);

        Zygote.applyDebuggerSystemProperty(parsedArgs);
        Zygote.applyInvokeWithSystemProperty(parsedArgs);

        int[][] rlimits = null;

        if (parsedArgs.mRLimits != null) {
            rlimits = parsedArgs.mRLimits.toArray(Zygote.INT_ARRAY_2D);
        }

        int[] fdsToIgnore = null;

        if (parsedArgs.mInvokeWith != null) {
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

        fd = zygoteServer.getZygoteSocketFileDescriptor();

        if (fd != null) {
            fdsToClose[1] = fd.getInt$();
        }

        fd = null;

        pid = Zygote.forkAndSpecialize(parsedArgs.mUid, parsedArgs.mGid, parsedArgs.mGids,
                parsedArgs.mRuntimeFlags, rlimits, parsedArgs.mMountExternal, parsedArgs.mSeInfo,
                parsedArgs.mNiceName, fdsToClose, fdsToIgnore, parsedArgs.mStartChildZygote,
                parsedArgs.mInstructionSet, parsedArgs.mAppDataDir, parsedArgs.mPackageName,
                parsedArgs.mPackagesForUid, parsedArgs.mSandboxId);

        try {
            if (pid == 0) {
                // in child
                zygoteServer.setForkChild();

                zygoteServer.closeServerSocket();
                IoUtils.closeQuietly(serverPipeFd);
                serverPipeFd = null;

                return handleChildProc(parsedArgs, descriptors, childPipeFd,
                        parsedArgs.mStartChildZygote);
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

    private void handlePidQuery() {
        try {
            String pidString = String.valueOf(Process.myPid());
            final byte[] pidStringBytes = pidString.getBytes(StandardCharsets.US_ASCII);
            mSocketOutStream.writeInt(pidStringBytes.length);
            mSocketOutStream.write(pidStringBytes);
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

    private Runnable stateChangeWithBlastulaPoolReset(ZygoteServer zygoteServer,
            Runnable stateChangeCode) {
        try {
            if (zygoteServer.isBlastulaPoolEnabled()) {
                Zygote.emptyBlastulaPool();
            }

            stateChangeCode.run();

            if (zygoteServer.isBlastulaPoolEnabled()) {
                Runnable fpResult =
                        zygoteServer.fillBlastulaPool(
                                new int[]{mSocket.getFileDescriptor().getInt$()});

                if (fpResult != null) {
                    zygoteServer.setForkChild();
                    return fpResult;
                }
            }

            mSocketOutStream.writeInt(0);

            return null;
        } catch (IOException ioe) {
            throw new IllegalStateException("Error writing to command socket", ioe);
        }
    }

    /**
     * Makes the necessary changes to implement a new API blacklist exemption policy, and then
     * responds to the system server, letting it know that the task has been completed.
     *
     * This necessitates a change to the internal state of the Zygote.  As such, if the blastula
     * pool is enabled all existing blastulas have an incorrect API blacklist exemption list.  To
     * properly handle this request the pool must be emptied and refilled.  This process can return
     * a Runnable object that must be returned to ZygoteServer.runSelectLoop to be invoked.
     *
     * @param zygoteServer  The server object that received the request
     * @param exemptions  The new exemption list.
     * @return A Runnable object representing a new app in any blastulas spawned from here; the
     *         zygote process will always receive a null value from this function.
     */
    private Runnable handleApiBlacklistExemptions(ZygoteServer zygoteServer, String[] exemptions) {
        return stateChangeWithBlastulaPoolReset(zygoteServer,
                () -> ZygoteInit.setApiBlacklistExemptions(exemptions));
    }

    private Runnable handleBlastulaPoolStatusChange(ZygoteServer zygoteServer, boolean newStatus) {
        try {
            Runnable fpResult = zygoteServer.setBlastulaPoolStatus(newStatus, mSocket);

            if (fpResult == null) {
                mSocketOutStream.writeInt(0);
            } else {
                zygoteServer.setForkChild();
            }

            return fpResult;
        } catch (IOException ioe) {
            throw new IllegalStateException("Error writing to command socket", ioe);
        }
    }

    private static class HiddenApiUsageLogger implements VMRuntime.HiddenApiUsageLogger {

        private final MetricsLogger mMetricsLogger = new MetricsLogger();
        private static HiddenApiUsageLogger sInstance = new HiddenApiUsageLogger();
        private int mHiddenApiAccessLogSampleRate = 0;
        private int mHiddenApiAccessStatslogSampleRate = 0;

        public static void setHiddenApiAccessLogSampleRates(int sampleRate, int newSampleRate) {
            sInstance.mHiddenApiAccessLogSampleRate = sampleRate;
            sInstance.mHiddenApiAccessStatslogSampleRate = newSampleRate;
        }

        public static HiddenApiUsageLogger getInstance() {
            return HiddenApiUsageLogger.sInstance;
        }

        public void hiddenApiUsed(int sampledValue, String packageName, String signature,
                int accessMethod, boolean accessDenied) {
            if (sampledValue < mHiddenApiAccessLogSampleRate) {
                logUsage(packageName, signature, accessMethod, accessDenied);
            }
            if (sampledValue < mHiddenApiAccessStatslogSampleRate) {
                newLogUsage(signature, accessMethod, accessDenied);
            }
        }

        private void logUsage(String packageName, String signature, int accessMethod,
                                  boolean accessDenied) {
            int accessMethodMetric = HiddenApiUsageLogger.ACCESS_METHOD_NONE;
            switch(accessMethod) {
                case HiddenApiUsageLogger.ACCESS_METHOD_NONE:
                    accessMethodMetric = MetricsEvent.ACCESS_METHOD_NONE;
                    break;
                case HiddenApiUsageLogger.ACCESS_METHOD_REFLECTION:
                    accessMethodMetric = MetricsEvent.ACCESS_METHOD_REFLECTION;
                    break;
                case HiddenApiUsageLogger.ACCESS_METHOD_JNI:
                    accessMethodMetric = MetricsEvent.ACCESS_METHOD_JNI;
                    break;
                case HiddenApiUsageLogger.ACCESS_METHOD_LINKING:
                    accessMethodMetric = MetricsEvent.ACCESS_METHOD_LINKING;
                    break;
            }
            LogMaker logMaker = new LogMaker(MetricsEvent.ACTION_HIDDEN_API_ACCESSED)
                    .setPackageName(packageName)
                    .addTaggedData(MetricsEvent.FIELD_HIDDEN_API_SIGNATURE, signature)
                    .addTaggedData(MetricsEvent.FIELD_HIDDEN_API_ACCESS_METHOD,
                        accessMethodMetric);
            if (accessDenied) {
                logMaker.addTaggedData(MetricsEvent.FIELD_HIDDEN_API_ACCESS_DENIED, 1);
            }
            mMetricsLogger.write(logMaker);
        }

        private void newLogUsage(String signature, int accessMethod, boolean accessDenied) {
            int accessMethodProto = StatsLog.HIDDEN_API_USED__ACCESS_METHOD__NONE;
            switch(accessMethod) {
                case HiddenApiUsageLogger.ACCESS_METHOD_NONE:
                    accessMethodProto = StatsLog.HIDDEN_API_USED__ACCESS_METHOD__NONE;
                    break;
                case HiddenApiUsageLogger.ACCESS_METHOD_REFLECTION:
                    accessMethodProto = StatsLog.HIDDEN_API_USED__ACCESS_METHOD__REFLECTION;
                    break;
                case HiddenApiUsageLogger.ACCESS_METHOD_JNI:
                    accessMethodProto = StatsLog.HIDDEN_API_USED__ACCESS_METHOD__JNI;
                    break;
                case HiddenApiUsageLogger.ACCESS_METHOD_LINKING:
                    accessMethodProto = StatsLog.HIDDEN_API_USED__ACCESS_METHOD__LINKING;
                    break;
            }
            int uid = Process.myUid();
            StatsLog.write(StatsLog.HIDDEN_API_USED, uid, signature,
                                   accessMethodProto, accessDenied);
        }
    }

    /**
     * Changes the API access log sample rate for the Zygote and processes spawned from it.
     *
     * This necessitates a change to the internal state of the Zygote.  As such, if the blastula
     * pool is enabled all existing blastulas have an incorrect API access log sample rate.  To
     * properly handle this request the pool must be emptied and refilled.  This process can return
     * a Runnable object that must be returned to ZygoteServer.runSelectLoop to be invoked.
     *
     * @param zygoteServer  The server object that received the request
     * @param samplingRate  The new sample rate for regular logging
     * @param statsdSamplingRate  The new sample rate for statslog logging
     * @return A Runnable object representing a new app in any blastulas spawned from here; the
     *         zygote process will always receive a null value from this function.
     */
    private Runnable handleHiddenApiAccessLogSampleRate(ZygoteServer zygoteServer,
            int samplingRate, int statsdSamplingRate) {
        return stateChangeWithBlastulaPoolReset(zygoteServer, () -> {
            int maxSamplingRate = Math.max(samplingRate, statsdSamplingRate);
            ZygoteInit.setHiddenApiAccessLogSampleRate(maxSamplingRate);
            HiddenApiUsageLogger.setHiddenApiAccessLogSampleRates(samplingRate,
                    statsdSamplingRate);
            ZygoteInit.setHiddenApiUsageLogger(HiddenApiUsageLogger.getInstance());
        });
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

    protected void handlePreloadPackage(String packagePath, String libsPath, String libFileName,
            String cacheKey) {
        throw new RuntimeException("Zygote does not support package preloading");
    }

    protected boolean canPreloadApp() {
        return false;
    }

    protected void handlePreloadApp(ApplicationInfo aInfo) {
        throw new RuntimeException("Zygote does not support app preloading");
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
     * Handles post-fork setup of child proc, closing sockets as appropriate,
     * reopen stdio as appropriate, and ultimately throwing MethodAndArgsCaller
     * if successful or returning if failed.
     *
     * @param parsedArgs non-null; zygote args
     * @param descriptors null-ok; new file descriptors for stdio if available.
     * @param pipeFd null-ok; pipe for communication back to Zygote.
     * @param isZygote whether this new child process is itself a new Zygote.
     */
    private Runnable handleChildProc(ZygoteArguments parsedArgs, FileDescriptor[] descriptors,
            FileDescriptor pipeFd, boolean isZygote) {
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

        if (parsedArgs.mNiceName != null) {
            Process.setArgV0(parsedArgs.mNiceName);
        }

        // End of the postFork event.
        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        if (parsedArgs.mInvokeWith != null) {
            WrapperInit.execApplication(parsedArgs.mInvokeWith,
                    parsedArgs.mNiceName, parsedArgs.mTargetSdkVersion,
                    VMRuntime.getCurrentInstructionSet(),
                    pipeFd, parsedArgs.mRemainingArgs);

            // Should not get here.
            throw new IllegalStateException("WrapperInit.execApplication unexpectedly returned");
        } else {
            if (!isZygote) {
                return ZygoteInit.zygoteInit(parsedArgs.mTargetSdkVersion,
                        parsedArgs.mRemainingArgs, null /* classLoader */);
            } else {
                return ZygoteInit.childZygoteInit(parsedArgs.mTargetSdkVersion,
                        parsedArgs.mRemainingArgs, null /* classLoader */);
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
