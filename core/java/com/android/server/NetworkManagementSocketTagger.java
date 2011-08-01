/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server;

import android.os.SystemProperties;
import android.util.Log;
import dalvik.system.SocketTagger;
import libcore.io.IoUtils;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.SocketException;
import java.nio.charset.Charsets;

/**
 * Assigns tags to sockets for traffic stats.
 */
public final class NetworkManagementSocketTagger extends SocketTagger {
    private static final String TAG = "NetworkManagementSocketTagger";
    private static final boolean LOGD = false;

    /**
     * {@link SystemProperties} key that indicates if {@code qtaguid} bandwidth
     * controls have been enabled.
     */
    // TODO: remove when always enabled, or once socket tagging silently fails.
    public static final String PROP_QTAGUID_ENABLED = "net.qtaguid_enabled";

    private static ThreadLocal<SocketTags> threadSocketTags = new ThreadLocal<SocketTags>() {
        @Override
        protected SocketTags initialValue() {
            return new SocketTags();
        }
    };

    public static void install() {
        SocketTagger.set(new NetworkManagementSocketTagger());
    }

    public static void setThreadSocketStatsTag(int tag) {
        threadSocketTags.get().statsTag = tag;
    }

    public static int getThreadSocketStatsTag() {
        return threadSocketTags.get().statsTag;
    }

    public static void setThreadSocketStatsUid(int uid) {
        threadSocketTags.get().statsUid = uid;
    }

    @Override
    public void tag(FileDescriptor fd) throws SocketException {
        final SocketTags options = threadSocketTags.get();
        if (LOGD) {
            Log.d(TAG, "tagSocket(" + fd.getInt$() + ") with statsTag=0x"
                    + Integer.toHexString(options.statsTag) + ", statsUid=" + options.statsUid);
        }
        try {
            // TODO: skip tagging when options would be no-op
            tagSocketFd(fd, options.statsTag, options.statsUid);
        } catch (IOException e) {
            throw new SocketException("Problem tagging socket", e);
        }
    }

    private void tagSocketFd(FileDescriptor fd, int tag, int uid) throws IOException {
        final int fdNum = fd.getInt$();
        if (fdNum == -1 || (tag == -1 && uid == -1)) return;

        String cmd = "t " + fdNum;
        if (tag == -1) {
            // Case where just the uid needs adjusting. But probably the caller
            // will want to track his own name here, just in case.
            cmd += " 0";
        } else {
            cmd += " " + tagToKernel(tag);
        }
        if (uid != -1) {
            cmd += " " + uid;
        }
        internalModuleCtrl(cmd);
    }

    @Override
    public void untag(FileDescriptor fd) throws SocketException {
        if (LOGD) {
            Log.i(TAG, "untagSocket(" + fd.getInt$() + ")");
        }
        try {
            unTagSocketFd(fd);
        } catch (IOException e) {
            throw new SocketException("Problem untagging socket", e);
        }
    }

    private void unTagSocketFd(FileDescriptor fd) throws IOException {
        int fdNum = fd.getInt$();
        if (fdNum == -1) return;
        String cmd = "u " + fdNum;
        internalModuleCtrl(cmd);
    }

    public static class SocketTags {
        public int statsTag = -1;
        public int statsUid = -1;
    }

    /**
     * Sends commands to the kernel netfilter module.
     *
     * @param cmd command string for the qtaguid netfilter module. May not be null.
     *   <p>Supports:
     *     <ul><li>tag a socket:<br>
     *        <code>t <i>sock_fd</i> <i>acct_tag</i> [<i>uid_in_case_caller_is_acting_on_behalf_of</i>]</code><br>
     *     <code>*_tag</code> defaults to default_policy_tag_from_uid(uid_of_caller)<br>
     *     <code>acct_tag</code> is either 0 or greater that 2^32.<br>
     *     <code>uid_*</code> is only settable by privileged UIDs (DownloadManager,...)
     *     </li>
     *     <li>untag a socket, preserving counters:<br>
     *       <code>u <i>sock_fd</i></code>
     *     </li></ul>
     *   <p>Notes:<br>
     *   <ul><li><i>sock_fd</i> is withing the callers process space.</li>
     *   <li><i>*_tag</i> are 64bit values</li></ul>
     *
     */
    private void internalModuleCtrl(String cmd) throws IOException {
        if (!SystemProperties.getBoolean(PROP_QTAGUID_ENABLED, false)) return;

        // TODO: migrate to native library for tagging commands
        FileOutputStream procOut = null;
        try {
            procOut = new FileOutputStream("/proc/net/xt_qtaguid/ctrl");
            procOut.write(cmd.getBytes(Charsets.US_ASCII));
        } finally {
            IoUtils.closeQuietly(procOut);
        }
    }

    /**
     * Convert {@link Integer} tag to {@code /proc/} format. Assumes unsigned
     * base-10 format like {@code 2147483647}. Currently strips signed bit to
     * avoid using {@link BigInteger}.
     */
    public static String tagToKernel(int tag) {
        // TODO: eventually write in hex, since that's what proc exports
        // TODO: migrate to direct integer instead of odd shifting
        return Long.toString((((long) tag) << 32) & 0x7FFFFFFF00000000L);
    }

    /**
     * Convert {@code /proc/} tag format to {@link Integer}. Assumes incoming
     * format like {@code 0x7fffffff00000000}.
     */
    public static int kernelToTag(String string) {
        // TODO: migrate to direct integer instead of odd shifting
        return (int) (Long.decode(string) >> 32);
    }
}
