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

import android.net.NetworkStats;
import android.os.SystemProperties;
import android.util.Log;
import android.util.Slog;

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
        // TODO: skip tagging when options would be no-op
        tagSocketFd(fd, options.statsTag, options.statsUid);
    }

    private void tagSocketFd(FileDescriptor fd, int tag, int uid) {
        if (tag == -1 && uid == -1) return;

        if (SystemProperties.getBoolean(PROP_QTAGUID_ENABLED, false)) {
            final int errno = native_tagSocketFd(fd, tag, uid);
            if (errno < 0) {
                Log.i(TAG, "tagSocketFd(" + fd.getInt$() + ", "
                      + tag + ", " +
                      + uid + ") failed with errno" + errno);
            }
        }
    }

    @Override
    public void untag(FileDescriptor fd) throws SocketException {
        if (LOGD) {
            Log.i(TAG, "untagSocket(" + fd.getInt$() + ")");
        }
        unTagSocketFd(fd);
    }

    private void unTagSocketFd(FileDescriptor fd) {
        final SocketTags options = threadSocketTags.get();
        if (options.statsTag == -1 && options.statsUid == -1) return;

        if (SystemProperties.getBoolean(PROP_QTAGUID_ENABLED, false)) {
            final int errno = native_untagSocketFd(fd);
            if (errno < 0) {
                Log.w(TAG, "untagSocket(" + fd.getInt$() + ") failed with errno " + errno);
            }
        }
    }

    public static class SocketTags {
        public int statsTag = -1;
        public int statsUid = -1;
    }

    public static void setKernelCounterSet(int uid, int counterSet) {
        if (SystemProperties.getBoolean(PROP_QTAGUID_ENABLED, false)) {
            final int errno = native_setCounterSet(counterSet, uid);
            if (errno < 0) {
                Log.w(TAG, "setKernelCountSet(" + uid + ", " + counterSet + ") failed with errno "
                        + errno);
            }
        }
    }

    public static void resetKernelUidStats(int uid) {
        if (SystemProperties.getBoolean(PROP_QTAGUID_ENABLED, false)) {
            int errno = native_deleteTagData(0, uid);
            if (errno < 0) {
                Slog.w(TAG, "problem clearing counters for uid " + uid + " : errno " + errno);
            }
        }
    }

    /**
     * Convert {@code /proc/} tag format to {@link Integer}. Assumes incoming
     * format like {@code 0x7fffffff00000000}.
     */
    public static int kernelToTag(String string) {
        // TODO: migrate to direct integer instead of odd shifting
        return (int) (Long.decode(string) >> 32);
    }

    private static native int native_tagSocketFd(FileDescriptor fd, int tag, int uid);
    private static native int native_untagSocketFd(FileDescriptor fd);
    private static native int native_setCounterSet(int uid, int counterSetNum);
    private static native int native_deleteTagData(int tag, int uid);
}
