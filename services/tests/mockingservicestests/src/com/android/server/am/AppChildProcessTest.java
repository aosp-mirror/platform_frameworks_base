/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.am;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManagerInternal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.am.ActivityManagerService.Injector;
import com.android.server.appop.AppOpsService;
import com.android.server.wm.ActivityTaskManagerService;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.quality.Strictness;

import java.io.File;

@Presubmit
public class AppChildProcessTest {
    private static final String TAG = AppChildProcessTest.class.getSimpleName();

    @Rule public ServiceThreadRule mServiceThreadRule = new ServiceThreadRule();
    @Mock private AppOpsService mAppOpsService;
    @Mock private PackageManagerInternal mPackageManagerInt;
    private StaticMockitoSession mMockitoSession;

    private Context mContext = getInstrumentation().getTargetContext();
    private TestInjector mInjector;
    private ActivityManagerService mAms;
    private ProcessList mProcessList;
    private PhantomProcessList mPhantomProcessList;
    private Handler mHandler;
    private HandlerThread mHandlerThread;

    @BeforeClass
    public static void setUpOnce() {
        System.setProperty("dexmaker.share_classloader", "true");
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mMockitoSession = mockitoSession()
            .spyStatic(Process.class)
            .strictness(Strictness.LENIENT)
            .startMocking();

        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        final ProcessList pList = new ProcessList();
        mProcessList = spy(pList);

        mInjector = new TestInjector(mContext);
        mAms = new ActivityManagerService(mInjector, mServiceThreadRule.getThread());
        mAms.mActivityTaskManager = new ActivityTaskManagerService(mContext);
        mAms.mActivityTaskManager.initialize(null, null, mContext.getMainLooper());
        mAms.mAtmInternal = spy(mAms.mActivityTaskManager.getAtmInternal());
        mAms.mPackageManagerInt = mPackageManagerInt;
        pList.mService = mAms;
        mPhantomProcessList = mAms.mPhantomProcessList;
        doReturn(new ComponentName("", "")).when(mPackageManagerInt).getSystemUiServiceComponent();
        doReturn(false).when(() -> Process.supportsPidFd());
        // Remove stale instance of PackageManagerInternal if there is any
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInt);
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        mMockitoSession.finishMocking();
        mHandlerThread.quit();
    }

    @Test
    public void testManageAppChildProcesses() throws Exception {
        final int initPid = 1;
        final int rootUid = 0;
        final int zygote64Pid = 100;
        final int zygote32Pid = 101;
        final int app1Pid = 200;
        final int app2Pid = 201;
        final int app1Uid = 10000;
        final int app2Uid = 10001;
        final int child1Pid = 300;
        final int child2Pid = 301;
        final int nativePid = 400;
        final String zygote64ProcessName = "zygote64";
        final String zygote32ProcessName = "zygote32";
        final String app1ProcessName = "test1";
        final String app2ProcessName = "test2";
        final String child1ProcessName = "test1_child1";
        final String child2ProcessName = "test1_child1_child2";
        final String nativeProcessName = "test_native";

        makeParent(zygote64Pid, initPid);
        makeParent(zygote32Pid, initPid);

        makeAppProcess(app1Pid, app1Uid, app1ProcessName, app1ProcessName);
        makeParent(app1Pid, zygote64Pid);
        makeAppProcess(app2Pid, app2Uid, app2ProcessName, app2ProcessName);
        makeParent(app2Pid, zygote64Pid);

        assertEquals(0, mPhantomProcessList.mPhantomProcesses.size());

        // Verify zygote itself isn't a phantom process
        assertEquals(null, mPhantomProcessList.getOrCreatePhantomProcessIfNeededLocked(
                zygote64ProcessName, rootUid, zygote64Pid));
        assertEquals(null, mPhantomProcessList.getOrCreatePhantomProcessIfNeededLocked(
                zygote32ProcessName, rootUid, zygote32Pid));
        // Verify none of the app isn't a phantom process
        assertEquals(null, mPhantomProcessList.getOrCreatePhantomProcessIfNeededLocked(
                app1ProcessName, app1Uid, app1Pid));
        assertEquals(null, mPhantomProcessList.getOrCreatePhantomProcessIfNeededLocked(
                app2ProcessName, app2Uid, app2Pid));

        // "Fork" an app child process
        makeParent(child1Pid, app1Pid);
        PhantomProcessRecord pr = mPhantomProcessList
                .getOrCreatePhantomProcessIfNeededLocked(child1ProcessName, app1Uid, child1Pid);
        assertTrue(pr != null);
        assertEquals(1, mPhantomProcessList.mPhantomProcesses.size());
        assertEquals(pr, mPhantomProcessList.mPhantomProcesses.valueAt(0));
        verifyPhantomProcessRecord(pr, child1ProcessName, app1Uid, child1Pid);

        // Create another native process from init
        makeParent(nativePid, initPid);
        assertEquals(null, mPhantomProcessList.getOrCreatePhantomProcessIfNeededLocked(
                nativeProcessName, rootUid, nativePid));
        assertEquals(1, mPhantomProcessList.mPhantomProcesses.size());
        assertEquals(pr, mPhantomProcessList.mPhantomProcesses.valueAt(0));

        // "Fork" another app child process
        makeParent(child2Pid, child1Pid);
        PhantomProcessRecord pr2 = mPhantomProcessList
                .getOrCreatePhantomProcessIfNeededLocked(child2ProcessName, app1Uid, child2Pid);
        assertTrue(pr2 != null);
        assertEquals(2, mPhantomProcessList.mPhantomProcesses.size());
        verifyPhantomProcessRecord(pr2, child2ProcessName, app1Uid, child2Pid);

        ArraySet<PhantomProcessRecord> set = new ArraySet<>();
        set.add(pr);
        set.add(pr2);
        for (int i = mPhantomProcessList.mPhantomProcesses.size() - 1; i >= 0; i--) {
            set.remove(mPhantomProcessList.mPhantomProcesses.valueAt(i));
        }
        assertEquals(0, set.size());
    }

    private void verifyPhantomProcessRecord(PhantomProcessRecord pr,
            String processName, int uid, int pid) {
        assertEquals(processName, pr.mProcessName);
        assertEquals(uid, pr.mUid);
        assertEquals(pid, pr.mPid);
    }

    private void makeAppProcess(int pid, int uid, String packageName, String processName) {
        ApplicationInfo ai = new ApplicationInfo();
        ai.packageName = packageName;
        ProcessRecord app = new ProcessRecord(mAms, ai, processName, uid);
        app.pid = pid;
        mAms.mPidsSelfLocked.doAddInternal(app);
    }

    private void makeParent(int pid, int ppid) {
        doReturn(ppid).when(() -> Process.getParentPid(eq(pid)));
    }

    private class TestInjector extends Injector {
        TestInjector(Context context) {
            super(context);
        }

        @Override
        public AppOpsService getAppOpsService(File file, Handler handler) {
            return mAppOpsService;
        }

        @Override
        public Handler getUiHandler(ActivityManagerService service) {
            return mHandler;
        }

        @Override
        public ProcessList getProcessList(ActivityManagerService service) {
            return mProcessList;
        }
    }

    static class ServiceThreadRule implements TestRule {
        private ServiceThread mThread;

        ServiceThread getThread() {
            return mThread;
        }

        @Override
        public Statement apply(Statement base, Description description) {
            return new Statement() {
                @Override
                public void evaluate() throws Throwable {
                    mThread = new ServiceThread("TestServiceThread",
                            Process.THREAD_PRIORITY_DEFAULT, true /* allowIo */);
                    mThread.start();
                    try {
                        base.evaluate();
                    } finally {
                        mThread.getThreadHandler().runWithScissors(mThread::quit, 0 /* timeout */);
                    }
                }
            };
        }
    }

}
