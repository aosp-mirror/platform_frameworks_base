/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.server.selinux;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import android.util.EventLog;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.os.Clock;
import com.android.internal.util.FrameworkStatsLog;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoSession;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Collectors;

@RunWith(AndroidJUnit4.class)
public class SelinuxAuditLogsCollectorTest {

    // Fake tag to use for testing
    private static final int ANSWER_TAG = 42;

    private final MockClock mClock = new MockClock();

    private final SelinuxAuditLogsCollector mSelinuxAutidLogsCollector =
            // Ignore rate limiting for tests
            new SelinuxAuditLogsCollector(
                    new RateLimiter(mClock, /* window= */ Duration.ofMillis(0)),
                    new QuotaLimiter(
                            mClock, /* windowSize= */ Duration.ofHours(1), /* maxPermits= */ 5));

    private MockitoSession mMockitoSession;

    @Before
    public void setUp() {
        // move the clock forward for the limiters.
        mClock.currentTimeMillis += Duration.ofHours(1).toMillis();
        // Ignore what was written in the event logs by previous tests.
        mSelinuxAutidLogsCollector.mLastWrite = Instant.now();

        mMockitoSession =
                mockitoSession().initMocks(this).mockStatic(FrameworkStatsLog.class).startMocking();
    }

    @After
    public void tearDown() {
        mMockitoSession.finishMocking();
    }

    @Test
    public void testWriteSdkSandboxAuditLogs() {
        writeTestLog("granted", "perm", "sdk_sandbox_audit", "ttype", "tclass");
        writeTestLog("denied", "perm1", "sdk_sandbox_audit", "ttype1", "tclass1");

        boolean done = mSelinuxAutidLogsCollector.collect(ANSWER_TAG);

        assertThat(done).isTrue();
        verify(
                () ->
                        FrameworkStatsLog.write(
                                FrameworkStatsLog.SELINUX_AUDIT_LOG,
                                true,
                                new String[] {"perm"},
                                "sdk_sandbox_audit",
                                null,
                                "ttype",
                                null,
                                "tclass",
                                null,
                                false));
        verify(
                () ->
                        FrameworkStatsLog.write(
                                FrameworkStatsLog.SELINUX_AUDIT_LOG,
                                false,
                                new String[] {"perm1"},
                                "sdk_sandbox_audit",
                                null,
                                "ttype1",
                                null,
                                "tclass1",
                                null,
                                false));
    }

    @Test
    public void testWriteSdkSandboxAuditLogs_multiplePerms() {
        writeTestLog("denied", "perm1 perm2", "sdk_sandbox_audit", "ttype", "tclass");
        writeTestLog("denied", "perm3 perm4", "sdk_sandbox_audit", "ttype", "tclass");

        boolean done = mSelinuxAutidLogsCollector.collect(ANSWER_TAG);

        assertThat(done).isTrue();
        verify(
                () ->
                        FrameworkStatsLog.write(
                                FrameworkStatsLog.SELINUX_AUDIT_LOG,
                                false,
                                new String[] {"perm1", "perm2"},
                                "sdk_sandbox_audit",
                                null,
                                "ttype",
                                null,
                                "tclass",
                                null,
                                false));
        verify(
                () ->
                        FrameworkStatsLog.write(
                                FrameworkStatsLog.SELINUX_AUDIT_LOG,
                                false,
                                new String[] {"perm3", "perm4"},
                                "sdk_sandbox_audit",
                                null,
                                "ttype",
                                null,
                                "tclass",
                                null,
                                false));
    }

    @Test
    public void testWriteSdkSandboxAuditLogs_withPaths() {
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass", "/good/path");
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass", "/very/long/path");
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass", "/short_path");
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass", "not_a_path");

        boolean done = mSelinuxAutidLogsCollector.collect(ANSWER_TAG);

        assertThat(done).isTrue();
        verify(
                () ->
                        FrameworkStatsLog.write(
                                FrameworkStatsLog.SELINUX_AUDIT_LOG,
                                false,
                                new String[] {"perm"},
                                "sdk_sandbox_audit",
                                null,
                                "ttype",
                                null,
                                "tclass",
                                "/good/path",
                                false));
        verify(
                () ->
                        FrameworkStatsLog.write(
                                FrameworkStatsLog.SELINUX_AUDIT_LOG,
                                false,
                                new String[] {"perm"},
                                "sdk_sandbox_audit",
                                null,
                                "ttype",
                                null,
                                "tclass",
                                "/very/long",
                                false));
        verify(
                () ->
                        FrameworkStatsLog.write(
                                FrameworkStatsLog.SELINUX_AUDIT_LOG,
                                false,
                                new String[] {"perm"},
                                "sdk_sandbox_audit",
                                null,
                                "ttype",
                                null,
                                "tclass",
                                "/short_path",
                                false));
        verify(
                () ->
                        FrameworkStatsLog.write(
                                FrameworkStatsLog.SELINUX_AUDIT_LOG,
                                false,
                                new String[] {"perm"},
                                "sdk_sandbox_audit",
                                null,
                                "ttype",
                                null,
                                "tclass",
                                null,
                                false));
    }

    @Test
    public void testWriteSdkSandboxAuditLogs_withCategories() {
        writeTestLog(
                "denied", "perm", "sdk_sandbox_audit", new int[] {123}, "ttype", null, "tclass");
        writeTestLog(
                "denied",
                "perm",
                "sdk_sandbox_audit",
                new int[] {123, 456},
                "ttype",
                null,
                "tclass");
        writeTestLog(
                "denied", "perm", "sdk_sandbox_audit", null, "ttype", new int[] {666}, "tclass");
        writeTestLog(
                "denied",
                "perm",
                "sdk_sandbox_audit",
                new int[] {123, 456},
                "ttype",
                new int[] {666, 777},
                "tclass");

        boolean done = mSelinuxAutidLogsCollector.collect(ANSWER_TAG);

        assertThat(done).isTrue();
        verify(
                () ->
                        FrameworkStatsLog.write(
                                FrameworkStatsLog.SELINUX_AUDIT_LOG,
                                false,
                                new String[] {"perm"},
                                "sdk_sandbox_audit",
                                new int[] {123},
                                "ttype",
                                null,
                                "tclass",
                                null,
                                false));
        verify(
                () ->
                        FrameworkStatsLog.write(
                                FrameworkStatsLog.SELINUX_AUDIT_LOG,
                                false,
                                new String[] {"perm"},
                                "sdk_sandbox_audit",
                                new int[] {123, 456},
                                "ttype",
                                null,
                                "tclass",
                                null,
                                false));
        verify(
                () ->
                        FrameworkStatsLog.write(
                                FrameworkStatsLog.SELINUX_AUDIT_LOG,
                                false,
                                new String[] {"perm"},
                                "sdk_sandbox_audit",
                                null,
                                "ttype",
                                new int[] {666},
                                "tclass",
                                null,
                                false));
        verify(
                () ->
                        FrameworkStatsLog.write(
                                FrameworkStatsLog.SELINUX_AUDIT_LOG,
                                false,
                                new String[] {"perm"},
                                "sdk_sandbox_audit",
                                new int[] {123, 456},
                                "ttype",
                                new int[] {666, 777},
                                "tclass",
                                null,
                                false));
    }

    @Test
    public void testWriteSdkSandboxAuditLogs_withPathAndCategories() {
        writeTestLog(
                "denied",
                "perm",
                "sdk_sandbox_audit",
                new int[] {123},
                "ttype",
                new int[] {666},
                "tclass",
                "/a/path");

        boolean done = mSelinuxAutidLogsCollector.collect(ANSWER_TAG);

        assertThat(done).isTrue();
        verify(
                () ->
                        FrameworkStatsLog.write(
                                FrameworkStatsLog.SELINUX_AUDIT_LOG,
                                false,
                                new String[] {"perm"},
                                "sdk_sandbox_audit",
                                new int[] {123},
                                "ttype",
                                new int[] {666},
                                "tclass",
                                "/a/path",
                                false));
    }

    @Test
    public void testWriteSdkSandboxAuditLogs_permissive() {
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass", true);
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass", false);

        boolean done = mSelinuxAutidLogsCollector.collect(ANSWER_TAG);

        assertThat(done).isTrue();
        verify(
                () ->
                        FrameworkStatsLog.write(
                                FrameworkStatsLog.SELINUX_AUDIT_LOG,
                                false,
                                new String[] {"perm"},
                                "sdk_sandbox_audit",
                                null,
                                "ttype",
                                null,
                                "tclass",
                                null,
                                false),
                times(2));
        verify(
                () ->
                        FrameworkStatsLog.write(
                                FrameworkStatsLog.SELINUX_AUDIT_LOG,
                                false,
                                new String[] {"perm"},
                                "sdk_sandbox_audit",
                                null,
                                "ttype",
                                null,
                                "tclass",
                                null,
                                true));
    }

    @Test
    public void testNotWriteAuditLogs_notSdkSandbox() {
        writeTestLog("denied", "perm", "stype", "ttype", "tclass");

        boolean done = mSelinuxAutidLogsCollector.collect(ANSWER_TAG);

        assertThat(done).isTrue();
        verify(
                () ->
                        FrameworkStatsLog.write(
                                anyInt(),
                                anyBoolean(),
                                any(),
                                anyString(),
                                any(),
                                anyString(),
                                any(),
                                anyString(),
                                any(),
                                anyBoolean()),
                never());
    }

    @Test
    public void testWriteSdkSandboxAuditLogs_upToQuota() {
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");
        // These are not pushed.
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");

        boolean done = mSelinuxAutidLogsCollector.collect(ANSWER_TAG);

        assertThat(done).isTrue();
        verify(
                () ->
                        FrameworkStatsLog.write(
                                anyInt(),
                                anyBoolean(),
                                any(),
                                anyString(),
                                any(),
                                anyString(),
                                any(),
                                anyString(),
                                any(),
                                anyBoolean()),
                times(5));
    }

    @Test
    public void testWriteSdkSandboxAuditLogs_resetQuota() {
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");

        boolean done = mSelinuxAutidLogsCollector.collect(ANSWER_TAG);
        assertThat(done).isTrue();
        verify(
                () ->
                        FrameworkStatsLog.write(
                                anyInt(),
                                anyBoolean(),
                                any(),
                                anyString(),
                                any(),
                                anyString(),
                                any(),
                                anyString(),
                                any(),
                                anyBoolean()),
                times(5));

        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");
        // move the clock forward to reset the quota limiter.
        mClock.currentTimeMillis += Duration.ofHours(1).toMillis();
        done = mSelinuxAutidLogsCollector.collect(ANSWER_TAG);
        assertThat(done).isTrue();
        verify(
                () ->
                        FrameworkStatsLog.write(
                                anyInt(),
                                anyBoolean(),
                                any(),
                                anyString(),
                                any(),
                                anyString(),
                                any(),
                                anyString(),
                                any(),
                                anyBoolean()),
                times(10));
    }

    @Test
    public void testNotWriteAuditLogs_stopRequested() {
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");
        // These are not pushed.
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");

        mSelinuxAutidLogsCollector.mStopRequested.set(true);
        boolean done = mSelinuxAutidLogsCollector.collect(ANSWER_TAG);
        assertThat(done).isFalse();
        verify(
                () ->
                        FrameworkStatsLog.write(
                                anyInt(),
                                anyBoolean(),
                                any(),
                                anyString(),
                                any(),
                                anyString(),
                                any(),
                                anyString(),
                                any(),
                                anyBoolean()),
                never());

        mSelinuxAutidLogsCollector.mStopRequested.set(false);
        done = mSelinuxAutidLogsCollector.collect(ANSWER_TAG);
        assertThat(done).isTrue();
        verify(
                () ->
                        FrameworkStatsLog.write(
                                anyInt(),
                                anyBoolean(),
                                any(),
                                anyString(),
                                any(),
                                anyString(),
                                any(),
                                anyString(),
                                any(),
                                anyBoolean()),
                times(5));
    }

    @Test
    public void testAuditLogs_resumeJobDoesNotExceedLimit() {
        writeTestLog("denied", "perm", "sdk_sandbox_audit", "ttype", "tclass");
        mSelinuxAutidLogsCollector.mStopRequested.set(true);

        boolean done = mSelinuxAutidLogsCollector.collect(ANSWER_TAG);

        assertThat(done).isFalse();
        verify(
                () ->
                        FrameworkStatsLog.write(
                                anyInt(),
                                anyBoolean(),
                                any(),
                                anyString(),
                                any(),
                                anyString(),
                                any(),
                                anyString(),
                                any(),
                                anyBoolean()),
                never());
    }

    private static void writeTestLog(
            String granted, String permissions, String sType, String tType, String tClass) {
        EventLog.writeEvent(
                ANSWER_TAG,
                String.format(
                        "avc: %s { %s } scontext=u:r:%s:s0 tcontext=u:object_r:%s:s0 tclass=%s",
                        granted, permissions, sType, tType, tClass));
    }

    private static void writeTestLog(
            String granted,
            String permissions,
            String sType,
            String tType,
            String tClass,
            String path) {
        EventLog.writeEvent(
                ANSWER_TAG,
                String.format(
                        "avc: %s { %s } path=\"%s\" scontext=u:r:%s:s0 tcontext=u:object_r:%s:s0"
                                + " tclass=%s",
                        granted, permissions, path, sType, tType, tClass));
    }

    private static void writeTestLog(
            String granted,
            String permissions,
            String sType,
            int[] sCategories,
            String tType,
            int[] tCategories,
            String tClass) {
        EventLog.writeEvent(
                ANSWER_TAG,
                String.format(
                        "avc: %s { %s } scontext=u:r:%s:s0%s tcontext=u:object_r:%s:s0%s tclass=%s",
                        granted,
                        permissions,
                        sType,
                        toCategoriesString(sCategories),
                        tType,
                        toCategoriesString(tCategories),
                        tClass));
    }

    private static void writeTestLog(
            String granted,
            String permissions,
            String sType,
            int[] sCategories,
            String tType,
            int[] tCategories,
            String tClass,
            String path) {
        EventLog.writeEvent(
                ANSWER_TAG,
                String.format(
                        "avc: %s { %s } path=\"%s\" scontext=u:r:%s:s0%s"
                                + " tcontext=u:object_r:%s:s0%s tclass=%s",
                        granted,
                        permissions,
                        path,
                        sType,
                        toCategoriesString(sCategories),
                        tType,
                        toCategoriesString(tCategories),
                        tClass));
    }

    private static void writeTestLog(
            String granted,
            String permissions,
            String sType,
            String tType,
            String tClass,
            boolean permissive) {
        EventLog.writeEvent(
                ANSWER_TAG,
                String.format(
                        "avc: %s { %s } scontext=u:r:%s:s0 tcontext=u:object_r:%s:s0 tclass=%s"
                                + " permissive=%s",
                        granted, permissions, sType, tType, tClass, permissive ? "1" : "0"));
    }

    private static String toCategoriesString(int[] categories) {
        return (categories == null || categories.length == 0)
                ? ""
                : ":c"
                        + Arrays.stream(categories)
                                .mapToObj(String::valueOf)
                                .collect(Collectors.joining(",c"));
    }

    private static final class MockClock extends Clock {

        public long currentTimeMillis = 0;

        @Override
        public long currentTimeMillis() {
            return currentTimeMillis;
        }
    }
}
