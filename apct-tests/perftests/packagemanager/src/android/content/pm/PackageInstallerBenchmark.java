/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.content.pm;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.os.HandlerThread;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.AdoptShellPermissionsRule;
import com.android.cts.install.lib.Install;
import com.android.cts.install.lib.InstallUtils;
import com.android.cts.install.lib.LocalIntentSender;
import com.android.cts.install.lib.TestApp;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class PackageInstallerBenchmark {
    private static final String TAG = "PackageInstallerBenchmark";

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    /**
     * This rule adopts the Shell process permissions, needed because INSTALL_PACKAGES
     * and DELETE_PACKAGES are privileged permission.
     */
    @Rule
    public AdoptShellPermissionsRule mAdoptShellPermissionsRule = new AdoptShellPermissionsRule(
            InstrumentationRegistry.getInstrumentation().getUiAutomation(),
            Manifest.permission.INSTALL_PACKAGES,
            Manifest.permission.DELETE_PACKAGES);

    private static class SessionCallback extends PackageInstaller.SessionCallback {
        private final List<Integer> mExpectedSessions;
        private final CountDownLatch mCountDownLatch;
        private final boolean mExpectedSuccess;

        SessionCallback(boolean expectedSuccess, List<Integer> expectedSessions,
                @NonNull CountDownLatch countDownLatch) {
            mExpectedSuccess = expectedSuccess;
            mCountDownLatch = countDownLatch;
            mExpectedSessions = expectedSessions;
        }

        @Override
        public void onCreated(int sessionId) { }

        @Override
        public void onBadgingChanged(int sessionId) { }

        @Override
        public void onActiveChanged(int sessionId, boolean active) { }

        @Override
        public void onProgressChanged(int sessionId, float progress) { }

        @Override
        public void onFinished(int sessionId, boolean success) {
            if (success == mExpectedSuccess && mExpectedSessions.contains(sessionId)) {
                mCountDownLatch.countDown();
            }
        }
    }

    private CountDownLatch mCountDownLatch;
    private SessionCallback mSessionCallback;
    private PackageInstaller mPackageInstaller;
    private Install mInstall;
    private HandlerThread mHandlerThread;
    private List<PackageInstaller.Session> mExpectedSessions;
    private List<Integer> mExpectedSessionIds;
    final LocalIntentSender mLocalIntentSender = new LocalIntentSender();
    private IntentSender mIntentSender;

    @Before
    public void setUp() throws IOException {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mPackageInstaller =  context.getPackageManager().getPackageInstaller();
        mHandlerThread = new HandlerThread("PackageInstallerBenchmark");
        mHandlerThread.start();

        mIntentSender = mLocalIntentSender.getIntentSender();
    }

    @After
    public void tearDown() throws InterruptedException {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.unregisterReceiver(mLocalIntentSender);

        uninstall(false /* stop at fail */, TestApp.A, TestApp.B, TestApp.C);
        mHandlerThread.quitSafely();
    }

    private List<PackageInstaller.Session> createSinglePackageSessions(
            BenchmarkState state, boolean expectedResult, TestApp...testApps)
            throws IOException, InterruptedException {
        state.pauseTiming();
        uninstall(false /* stop at fail */, testApps);

        mExpectedSessions = new ArrayList<>();
        mExpectedSessionIds = new ArrayList<>();
        for (TestApp testApp : testApps) {
            mInstall = Install.single(testApp);
            final int expectedSessionId = mInstall.createSession();
            PackageInstaller.Session session =
                    InstallUtils.openPackageInstallerSession(expectedSessionId);
            Log.d(TAG, "createNewSession: session expectedSessionId = " + expectedSessionId);
            mExpectedSessions.add(session);
            mExpectedSessionIds.add(expectedSessionId);
        }

        mCountDownLatch = new CountDownLatch(mExpectedSessions.size());
        mSessionCallback = new SessionCallback(expectedResult, mExpectedSessionIds,
                mCountDownLatch);
        mPackageInstaller.registerSessionCallback(mSessionCallback,
                mHandlerThread.getThreadHandler());
        state.resumeTiming();
        return mExpectedSessions;
    }

    private List<PackageInstaller.Session> createMultiplePackageSessions(BenchmarkState state,
            boolean expectedSuccess, List<TestApp[]> testAppsList)
            throws IOException, InterruptedException {
        state.pauseTiming();
        mExpectedSessions = new ArrayList<>();
        mExpectedSessionIds = new ArrayList<>();
        for (TestApp[] testApps : testAppsList) {
            uninstall(false /* stop at fail */, testApps);

            mInstall = Install.multi(testApps);
            final int expectedSessionId = mInstall.createSession();
            PackageInstaller.Session session =
                    InstallUtils.openPackageInstallerSession(expectedSessionId);
            mExpectedSessions.add(session);
            mExpectedSessionIds.add(expectedSessionId);
        }

        mCountDownLatch = new CountDownLatch(mExpectedSessions.size());
        mSessionCallback = new SessionCallback(expectedSuccess, mExpectedSessionIds,
                mCountDownLatch);
        mPackageInstaller.registerSessionCallback(mSessionCallback,
                mHandlerThread.getThreadHandler());
        state.resumeTiming();
        return mExpectedSessions;
    }

    private void uninstall(boolean stopAtFail, TestApp...testApps) throws InterruptedException {
        String[] packageNames = new String[testApps.length];
        for (int i = 0; i < testApps.length; i++) {
            packageNames[i] = testApps[i].getPackageName();
        }
        uninstall(stopAtFail, packageNames);
    }

    private void uninstall(boolean stopAtFail, String...packageNames) throws InterruptedException {
        LocalIntentSender localIntentSender = new LocalIntentSender();
        IntentSender intentSender = localIntentSender.getIntentSender();
        for (String packageName : packageNames) {
            try {
                mPackageInstaller.uninstall(packageName, intentSender);
            } catch (IllegalArgumentException e) {
                continue;
            }
            Intent intent = localIntentSender.getResult();
            if (stopAtFail) {
                InstallUtils.assertStatusSuccess(intent);
            }
        }

        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.unregisterReceiver(localIntentSender);
    }

    private void uninstallSession(BenchmarkState state, String...packageNames)
            throws InterruptedException {
        state.pauseTiming();
        uninstall(true /* stop at fail */, packageNames);
        mPackageInstaller.unregisterSessionCallback(mSessionCallback);
        state.resumeTiming();
    }

    @Test(timeout = 600_000L)
    public void commit_aSingleApkSession_untilFinishBenchmark() throws Exception {
        uninstall(false /* stop at fail */, TestApp.A);

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            List<PackageInstaller.Session> sessions =
                    createSinglePackageSessions(state, true, TestApp.A1);

            for (PackageInstaller.Session session : sessions) {
                session.commit(mIntentSender);
            }
            mCountDownLatch.await(1, TimeUnit.MINUTES);

            uninstallSession(state, TestApp.A);
        }
    }

    @Test(timeout = 600_000L)
    public void commit_threeSingleApkSessions_untilFinishBenchmark() throws Exception {
        uninstall(false /* stop at fail */, TestApp.A, TestApp.B, TestApp.C);

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            List<PackageInstaller.Session> sessions = createSinglePackageSessions(
                    state, true, TestApp.A1, TestApp.B1, TestApp.C1);

            for (PackageInstaller.Session session : sessions) {
                session.commit(mIntentSender);
            }
            mCountDownLatch.await(1, TimeUnit.MINUTES);

            uninstallSession(state, TestApp.A, TestApp.B, TestApp.C);
        }
    }

    @Test(timeout = 600_000L)
    public void commit_aMultiplePackagesSession_untilFinishBenchmark()
            throws IOException, InterruptedException {
        uninstall(false /* stop at fail */, TestApp.A, TestApp.B, TestApp.C);

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final List<TestApp[]> multiPackageApps = new ArrayList<>();
        multiPackageApps.add(new TestApp[] {TestApp.A1, TestApp.B1, TestApp.C1});

        while (state.keepRunning()) {
            List<PackageInstaller.Session> sessions = createMultiplePackageSessions(
                    state, true, multiPackageApps);

            for (PackageInstaller.Session session : sessions) {
                session.commit(mIntentSender);
            }
            mCountDownLatch.await(1, TimeUnit.MINUTES);

            uninstallSession(state, TestApp.A, TestApp.B, TestApp.C);
        }
    }

    @Test(timeout = 600_000L)
    public void commit_threeMultiplePackageSessions_untilFinishBenchmark()
            throws Exception {
        uninstall(false /* stop at fail */, TestApp.A, TestApp.B, TestApp.C);

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final List<TestApp[]> multiPackageApps = new ArrayList<>();
        multiPackageApps.add(new TestApp[] {TestApp.A1});
        multiPackageApps.add(new TestApp[] {TestApp.B1});
        multiPackageApps.add(new TestApp[] {TestApp.C1});

        while (state.keepRunning()) {
            List<PackageInstaller.Session> sessions = createMultiplePackageSessions(
                    state, true, multiPackageApps);

            for (PackageInstaller.Session session : sessions) {
                session.commit(mIntentSender);
            }
            mCountDownLatch.await(1, TimeUnit.MINUTES);

            uninstallSession(state, TestApp.A, TestApp.B, TestApp.C);
        }
    }

    @Test(timeout = 600_000L)
    public void commit_aMultipleApksSession_untilFinishBenchmark()
            throws IOException, InterruptedException {
        uninstall(false /* stop at fail */, TestApp.A);

        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            List<PackageInstaller.Session> sessions = createSinglePackageSessions(
                    state, true, TestApp.ASplit1);

            for (PackageInstaller.Session session : sessions) {
                session.commit(mIntentSender);
            }
            mCountDownLatch.await(1, TimeUnit.MINUTES);

            uninstallSession(state, TestApp.A);
        }
    }
}
