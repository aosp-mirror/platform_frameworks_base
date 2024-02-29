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

import static com.android.internal.os.ZygoteConnectionConstants.CONNECTION_TIMEOUT_MILLIS;
import static com.android.internal.os.ZygoteConnectionConstants.WRAPPED_PID_TIMEOUT_MILLIS;

import android.compat.annotation.UnsupportedAppUsage;
import android.content.pm.ApplicationInfo;
import android.net.Credentials;
import android.net.LocalSocket;
import android.os.Parcel;
import android.os.Process;
import android.os.Trace;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructPollfd;
import android.util.Log;

import dalvik.system.VMRuntime;
import dalvik.system.ZygoteHooks;

import libcore.io.IoUtils;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

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
    @UnsupportedAppUsage
    private final LocalSocket mSocket;
    @UnsupportedAppUsage
    private final DataOutputStream mSocketOutStream;
    @UnsupportedAppUsage
    private final Credentials peer;
    private final String abiList;
    private boolean isEof;

    /**
     * Constructs instance from connected socket.
     *
     * @param socket non-null; connected socket
     * @param abiList non-null; a list of ABIs this zygote supports.
     * @throws IOException If obtaining the peer credentials fails
     */
    ZygoteConnection(LocalSocket socket, String abiList) throws IOException {
        mSocket = socket;
        this.abiList = abiList;

        mSocketOutStream = new DataOutputStream(socket.getOutputStream());

        mSocket.setSoTimeout(CONNECTION_TIMEOUT_MILLIS);

        try {
            peer = mSocket.getPeerCredentials();
        } catch (IOException ex) {
            Log.e(TAG, "Cannot read peer credentials", ex);
            throw ex;
        }

        if (peer.getUid() != Process.SYSTEM_UID) {
            throw new ZygoteSecurityException("Only system UID is allowed to connect to Zygote.");
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
     * Reads a command from the command socket. If a child is successfully forked, a
     * {@code Runnable} that calls the childs main method (or equivalent) is returned in the child
     * process. {@code null} is always returned in the parent process (the zygote).
     * If multipleOK is set, we may keep processing additional fork commands before returning.
     *
     * If the client closes the socket, an {@code EOF} condition is set, which callers can test
     * for by calling {@code ZygoteConnection.isClosedByPeer}.
     */
    Runnable processCommand(ZygoteServer zygoteServer, boolean multipleOK) {
        ZygoteArguments parsedArgs;

        try (ZygoteCommandBuffer argBuffer = new ZygoteCommandBuffer(mSocket)) {
            while (true) {
                try {
                    parsedArgs = ZygoteArguments.getInstance(argBuffer);
                    // Keep argBuffer around, since we need it to fork.
                } catch (IOException ex) {
                    throw new IllegalStateException("IOException on command socket", ex);
                }
                if (parsedArgs == null) {
                    isEof = true;
                    return null;
                }

                int pid;
                FileDescriptor childPipeFd = null;
                FileDescriptor serverPipeFd = null;

                if (parsedArgs.mBootCompleted) {
                    handleBootCompleted();
                    return null;
                }

                if (parsedArgs.mAbiListQuery) {
                    handleAbiListQuery();
                    return null;
                }

                if (parsedArgs.mPidQuery) {
                    handlePidQuery();
                    return null;
                }

                if (parsedArgs.mUsapPoolStatusSpecified
                        || parsedArgs.mApiDenylistExemptions != null
                        || parsedArgs.mHiddenApiAccessLogSampleRate != -1
                        || parsedArgs.mHiddenApiAccessStatslogSampleRate != -1) {
                    // Handle these once we've released argBuffer, to avoid opening a second one.
                    break;
                }

                if (parsedArgs.mPreloadDefault) {
                    handlePreload();
                    return null;
                }

                if (parsedArgs.mPreloadPackage != null) {
                    handlePreloadPackage(parsedArgs.mPreloadPackage,
                            parsedArgs.mPreloadPackageLibs,
                            parsedArgs.mPreloadPackageLibFileName,
                            parsedArgs.mPreloadPackageCacheKey);
                    return null;
                }

                if (canPreloadApp() && parsedArgs.mPreloadApp != null) {
                    byte[] rawParcelData = Base64.getDecoder().decode(parsedArgs.mPreloadApp);
                    Parcel appInfoParcel = Parcel.obtain();
                    appInfoParcel.unmarshall(rawParcelData, 0, rawParcelData.length);
                    appInfoParcel.setDataPosition(0);
                    ApplicationInfo appInfo =
                            ApplicationInfo.CREATOR.createFromParcel(appInfoParcel);
                    appInfoParcel.recycle();
                    if (appInfo != null) {
                        handlePreloadApp(appInfo);
                    } else {
                        throw new IllegalArgumentException("Failed to deserialize --preload-app");
                    }
                    return null;
                }

                if (parsedArgs.mPermittedCapabilities != 0
                        || parsedArgs.mEffectiveCapabilities != 0) {
                    throw new ZygoteSecurityException("Client may not specify capabilities: "
                            + "permitted=0x" + Long.toHexString(parsedArgs.mPermittedCapabilities)
                            + ", effective=0x"
                            + Long.toHexString(parsedArgs.mEffectiveCapabilities));
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
                        throw new IllegalStateException("Unable to set up pipe for invoke-with",
                                errnoEx);
                    }
                }

                /*
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

                FileDescriptor zygoteFd = zygoteServer.getZygoteSocketFileDescriptor();

                if (zygoteFd != null) {
                    fdsToClose[1] = zygoteFd.getInt$();
                }

                if (parsedArgs.mInvokeWith != null || parsedArgs.mStartChildZygote
                        || !multipleOK || peer.getUid() != Process.SYSTEM_UID) {
                    // Continue using old code for now. TODO: Handle these cases in the other path.
                    pid = Zygote.forkAndSpecialize(parsedArgs.mUid, parsedArgs.mGid,
                            parsedArgs.mGids, parsedArgs.mRuntimeFlags, rlimits,
                            parsedArgs.mMountExternal, parsedArgs.mSeInfo, parsedArgs.mNiceName,
                            fdsToClose, fdsToIgnore, parsedArgs.mStartChildZygote,
                            parsedArgs.mInstructionSet, parsedArgs.mAppDataDir,
                            parsedArgs.mIsTopApp, parsedArgs.mPkgDataInfoList,
                            parsedArgs.mAllowlistedDataInfoList, parsedArgs.mBindMountAppDataDirs,
                            parsedArgs.mBindMountAppStorageDirs);

                    try {
                        if (pid == 0) {
                            // in child
                            zygoteServer.setForkChild();

                            zygoteServer.closeServerSocket();
                            IoUtils.closeQuietly(serverPipeFd);
                            serverPipeFd = null;

                            return handleChildProc(parsedArgs, childPipeFd,
                                    parsedArgs.mStartChildZygote);
                        } else {
                            // In the parent. A pid < 0 indicates a failure and will be handled in
                            // handleParentProc.
                            IoUtils.closeQuietly(childPipeFd);
                            childPipeFd = null;
                            handleParentProc(pid, serverPipeFd);
                            return null;
                        }
                    } finally {
                        IoUtils.closeQuietly(childPipeFd);
                        IoUtils.closeQuietly(serverPipeFd);
                    }
                } else {
                    ZygoteHooks.preFork();
                    Runnable result = Zygote.forkSimpleApps(argBuffer,
                            zygoteServer.getZygoteSocketFileDescriptor(),
                            peer.getUid(), Zygote.minChildUid(peer), parsedArgs.mNiceName);
                    if (result == null) {
                        // parent; we finished some number of forks. Result is Boolean.
                        // We already did the equivalent of handleParentProc().
                        ZygoteHooks.postForkCommon();
                        // argBuffer contains a command not understood by forksimpleApps.
                        continue;
                    } else {
                        // child; result is a Runnable.
                        zygoteServer.setForkChild();
                        Zygote.setAppProcessName(parsedArgs, TAG);  // ??? Necessary?
                        return result;
                    }
                }
            }
        }
        // Handle anything that may need a ZygoteCommandBuffer after we've released ours.
        if (parsedArgs.mUsapPoolStatusSpecified) {
            return handleUsapPoolStatusChange(zygoteServer, parsedArgs.mUsapPoolEnabled);
        }
        if (parsedArgs.mApiDenylistExemptions != null) {
            return handleApiDenylistExemptions(zygoteServer,
                    parsedArgs.mApiDenylistExemptions);
        }
        if (parsedArgs.mHiddenApiAccessLogSampleRate != -1
                || parsedArgs.mHiddenApiAccessStatslogSampleRate != -1) {
            return handleHiddenApiAccessLogSampleRate(zygoteServer,
                    parsedArgs.mHiddenApiAccessLogSampleRate,
                    parsedArgs.mHiddenApiAccessStatslogSampleRate);
        }
        throw new AssertionError("Shouldn't get here");
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

    private void handleBootCompleted() {
        try {
            mSocketOutStream.writeInt(0);
        } catch (IOException ioe) {
            throw new IllegalStateException("Error writing to command socket", ioe);
        }

        VMRuntime.bootCompleted();
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

    private Runnable stateChangeWithUsapPoolReset(ZygoteServer zygoteServer,
            Runnable stateChangeCode) {
        try {
            if (zygoteServer.isUsapPoolEnabled()) {
                Log.i(TAG, "Emptying USAP Pool due to state change.");
                Zygote.emptyUsapPool();
            }

            stateChangeCode.run();

            if (zygoteServer.isUsapPoolEnabled()) {
                Runnable fpResult =
                        zygoteServer.fillUsapPool(
                                new int[]{mSocket.getFileDescriptor().getInt$()}, false);

                if (fpResult != null) {
                    zygoteServer.setForkChild();
                    return fpResult;
                } else {
                    Log.i(TAG, "Finished refilling USAP Pool after state change.");
                }
            }

            mSocketOutStream.writeInt(0);

            return null;
        } catch (IOException ioe) {
            throw new IllegalStateException("Error writing to command socket", ioe);
        }
    }

    /**
     * Makes the necessary changes to implement a new API deny list exemption policy, and then
     * responds to the system server, letting it know that the task has been completed.
     *
     * This necessitates a change to the internal state of the Zygote.  As such, if the USAP
     * pool is enabled all existing USAPs have an incorrect API deny list exemption list.  To
     * properly handle this request the pool must be emptied and refilled.  This process can return
     * a Runnable object that must be returned to ZygoteServer.runSelectLoop to be invoked.
     *
     * @param zygoteServer  The server object that received the request
     * @param exemptions  The new exemption list.
     * @return A Runnable object representing a new app in any USAPs spawned from here; the
     *         zygote process will always receive a null value from this function.
     */
    private Runnable handleApiDenylistExemptions(ZygoteServer zygoteServer, String[] exemptions) {
        return stateChangeWithUsapPoolReset(zygoteServer,
                () -> ZygoteInit.setApiDenylistExemptions(exemptions));
    }

    private Runnable handleUsapPoolStatusChange(ZygoteServer zygoteServer, boolean newStatus) {
        try {
            Runnable fpResult = zygoteServer.setUsapPoolStatus(newStatus, mSocket);

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

    /**
     * Changes the API access log sample rate for the Zygote and processes spawned from it.
     *
     * This necessitates a change to the internal state of the Zygote.  As such, if the USAP
     * pool is enabled all existing USAPs have an incorrect API access log sample rate.  To
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
        return stateChangeWithUsapPoolReset(zygoteServer, () -> {
            int maxSamplingRate = Math.max(samplingRate, statsdSamplingRate);
            ZygoteInit.setHiddenApiAccessLogSampleRate(maxSamplingRate);
            StatsdHiddenApiUsageLogger.setHiddenApiAccessLogSampleRates(
                    samplingRate, statsdSamplingRate);
            ZygoteInit.setHiddenApiUsageLogger(StatsdHiddenApiUsageLogger.getInstance());
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
    @UnsupportedAppUsage
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
     * @param pipeFd null-ok; pipe for communication back to Zygote.
     * @param isZygote whether this new child process is itself a new Zygote.
     */
    private Runnable handleChildProc(ZygoteArguments parsedArgs,
            FileDescriptor pipeFd, boolean isZygote) {
        /*
         * By the time we get here, the native code has closed the two actual Zygote
         * socket connections, and substituted /dev/null in their place.  The LocalSocket
         * objects still need to be closed properly.
         */

        closeSocket();

        Zygote.setAppProcessName(parsedArgs, TAG);

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
                        parsedArgs.mDisabledCompatChanges,
                        parsedArgs.mRemainingArgs, null /* classLoader */);
            } else {
                return ZygoteInit.childZygoteInit(
                        parsedArgs.mRemainingArgs  /* classLoader */);
            }
        }
    }

    /**
     * Handles post-fork cleanup of parent proc
     *
     * @param pid != 0; pid of child if &gt; 0 or indication of failed fork
     * if &lt; 0;
     * @param pipeFd null-ok; pipe for communication with child.
     */
    private void handleParentProc(int pid, FileDescriptor pipeFd) {
        if (pid > 0) {
            setChildPgid(pid);
        }

        boolean usingWrapper = false;
        if (pipeFd != null && pid > 0) {
            int innerPid = -1;
            try {
                // Do a busy loop here. We can't guarantee that a failure (and thus an exception
                // bail) happens in a timely manner.
                final int BYTES_REQUIRED = 4;  // Bytes in an int.

                StructPollfd[] fds = new StructPollfd[] {
                        new StructPollfd()
                };

                byte[] data = new byte[BYTES_REQUIRED];

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
                    int elapsedTimeMs =
                            (int) TimeUnit.MILLISECONDS.convert(
                                    endTime - startTime,
                                    TimeUnit.NANOSECONDS);
                    remainingSleepTime = WRAPPED_PID_TIMEOUT_MILLIS - elapsedTimeMs;

                    if (res > 0) {
                        if ((fds[0].revents & POLLIN) != 0) {
                            // Only read one byte, so as not to block. Really needed?
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
