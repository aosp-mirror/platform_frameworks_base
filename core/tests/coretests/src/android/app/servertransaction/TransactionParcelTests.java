/*
 * Copyright 2017 The Android Open Source Project
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

package android.app.servertransaction;

import static android.app.servertransaction.TestUtils.config;
import static android.app.servertransaction.TestUtils.mergedConfig;
import static android.app.servertransaction.TestUtils.referrerIntentList;
import static android.app.servertransaction.TestUtils.resultInfoList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.IApplicationThread;
import android.app.IInstrumentationWatcher;
import android.app.IUiAutomationConnection;
import android.app.ProfilerInfo;
import android.content.AutofillOptions;
import android.content.ComponentName;
import android.content.ContentCaptureOptions;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ParceledListSlice;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.app.IVoiceInteractor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Map;

/**
 * Test parcelling and unparcelling of transactions and transaction items.
 *
 * <p>Build/Install/Run:
 *  atest FrameworksCoreTests:TransactionParcelTests
 *
 * <p>This test class is a part of Window Manager Service tests and specified in
 * {@link com.android.server.wm.test.filters.FrameworksTestsFilter}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class TransactionParcelTests {

    private Parcel mParcel;

    @Before
    public void setUp() throws Exception {
        mParcel = Parcel.obtain();
    }

    @Test
    public void testConfigurationChange() {
        // Write to parcel
        ConfigurationChangeItem item = ConfigurationChangeItem.obtain(config());
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        ConfigurationChangeItem result = ConfigurationChangeItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testActivityConfigChange() {
        // Write to parcel
        ActivityConfigurationChangeItem item = ActivityConfigurationChangeItem.obtain(config());
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        ActivityConfigurationChangeItem result =
                ActivityConfigurationChangeItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testMoveToDisplay() {
        // Write to parcel
        MoveToDisplayItem item = MoveToDisplayItem.obtain(4 /* targetDisplayId */, config());
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        MoveToDisplayItem result = MoveToDisplayItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testNewIntent() {
        // Write to parcel
        NewIntentItem item = NewIntentItem.obtain(referrerIntentList(), false);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        NewIntentItem result = NewIntentItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testActivityResult() {
        // Write to parcel
        ActivityResultItem item = ActivityResultItem.obtain(resultInfoList());
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        ActivityResultItem result = ActivityResultItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testPipModeChange() {
        // Write to parcel
        PipModeChangeItem item = PipModeChangeItem.obtain(true /* isInPipMode */, config());
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        PipModeChangeItem result = PipModeChangeItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testMultiWindowModeChange() {
        // Write to parcel
        MultiWindowModeChangeItem item = MultiWindowModeChangeItem.obtain(
                true /* isInMultiWindowMode */, config());
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        MultiWindowModeChangeItem result =
                MultiWindowModeChangeItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testWindowVisibilityChange() {
        // Write to parcel
        WindowVisibilityItem item = WindowVisibilityItem.obtain(true /* showWindow */);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        WindowVisibilityItem result = WindowVisibilityItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));

        // Check different value
        item = WindowVisibilityItem.obtain(false);

        mParcel = Parcel.obtain();
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        result = WindowVisibilityItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testDestroy() {
        DestroyActivityItem item = DestroyActivityItem.obtain(true /* finished */,
                135 /* configChanges */);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        DestroyActivityItem result = DestroyActivityItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testLaunch() {
        // Write to parcel
        Intent intent = new Intent("action");
        int ident = 57;
        ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.flags = 42;
        activityInfo.maxAspectRatio = 2.4f;
        activityInfo.launchToken = "token";
        activityInfo.applicationInfo = new ApplicationInfo();
        activityInfo.packageName = "packageName";
        activityInfo.name = "name";
        Configuration overrideConfig = new Configuration();
        overrideConfig.assetsSeq = 5;
        CompatibilityInfo compat = CompatibilityInfo.DEFAULT_COMPATIBILITY_INFO;
        String referrer = "referrer";
        int procState = 4;
        Bundle bundle = new Bundle();
        bundle.putString("key", "value");
        PersistableBundle persistableBundle = new PersistableBundle();
        persistableBundle.putInt("k", 4);

        LaunchActivityItem item = LaunchActivityItem.obtain(intent, ident, activityInfo,
                config(), overrideConfig, compat, referrer, null /* voiceInteractor */,
                procState, bundle, persistableBundle, resultInfoList(), referrerIntentList(),
                true /* isForward */, null /* profilerInfo */, new Binder());
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        LaunchActivityItem result = LaunchActivityItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testRelaunch() {
        // Write to parcel
        Configuration overrideConfig = new Configuration();
        overrideConfig.assetsSeq = 5;
        ActivityRelaunchItem item = ActivityRelaunchItem.obtain(resultInfoList(),
                referrerIntentList(), 35, mergedConfig(), true);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        ActivityRelaunchItem result = ActivityRelaunchItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testPause() {
        // Write to parcel
        PauseActivityItem item = PauseActivityItem.obtain(true /* finished */,
                true /* userLeaving */, 135 /* configChanges */, true /* dontReport */);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        PauseActivityItem result = PauseActivityItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testResume() {
        // Write to parcel
        ResumeActivityItem item = ResumeActivityItem.obtain(27 /* procState */,
                true /* isForward */);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        ResumeActivityItem result = ResumeActivityItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testStop() {
        // Write to parcel
        StopActivityItem item = StopActivityItem.obtain(true /* showWindow */,
                14 /* configChanges */);
        writeAndPrepareForReading(item);

        // Read from parcel and assert
        StopActivityItem result = StopActivityItem.CREATOR.createFromParcel(mParcel);

        assertEquals(item.hashCode(), result.hashCode());
        assertTrue(item.equals(result));
    }

    @Test
    public void testClientTransaction() {
        // Write to parcel
        WindowVisibilityItem callback1 = WindowVisibilityItem.obtain(true);
        ActivityConfigurationChangeItem callback2 = ActivityConfigurationChangeItem.obtain(
                config());

        StopActivityItem lifecycleRequest = StopActivityItem.obtain(true /* showWindow */,
                78 /* configChanges */);

        IApplicationThread appThread = new StubAppThread();
        Binder activityToken = new Binder();

        ClientTransaction transaction = ClientTransaction.obtain(appThread, activityToken);
        transaction.addCallback(callback1);
        transaction.addCallback(callback2);
        transaction.setLifecycleStateRequest(lifecycleRequest);

        writeAndPrepareForReading(transaction);

        // Read from parcel and assert
        ClientTransaction result = ClientTransaction.CREATOR.createFromParcel(mParcel);

        assertEquals(transaction.hashCode(), result.hashCode());
        assertTrue(transaction.equals(result));
    }

    @Test
    public void testClientTransactionCallbacksOnly() {
        // Write to parcel
        WindowVisibilityItem callback1 = WindowVisibilityItem.obtain(true);
        ActivityConfigurationChangeItem callback2 = ActivityConfigurationChangeItem.obtain(
                config());

        IApplicationThread appThread = new StubAppThread();
        Binder activityToken = new Binder();

        ClientTransaction transaction = ClientTransaction.obtain(appThread, activityToken);
        transaction.addCallback(callback1);
        transaction.addCallback(callback2);

        writeAndPrepareForReading(transaction);

        // Read from parcel and assert
        ClientTransaction result = ClientTransaction.CREATOR.createFromParcel(mParcel);

        assertEquals(transaction.hashCode(), result.hashCode());
        assertTrue(transaction.equals(result));
    }

    @Test
    public void testClientTransactionLifecycleOnly() {
        // Write to parcel
        StopActivityItem lifecycleRequest = StopActivityItem.obtain(true /* showWindow */,
                78 /* configChanges */);

        IApplicationThread appThread = new StubAppThread();
        Binder activityToken = new Binder();

        ClientTransaction transaction = ClientTransaction.obtain(appThread, activityToken);
        transaction.setLifecycleStateRequest(lifecycleRequest);

        writeAndPrepareForReading(transaction);

        // Read from parcel and assert
        ClientTransaction result = ClientTransaction.CREATOR.createFromParcel(mParcel);

        assertEquals(transaction.hashCode(), result.hashCode());
        assertTrue(transaction.equals(result));
    }

    /** Write to {@link #mParcel} and reset its position to prepare for reading from the start. */
    private void writeAndPrepareForReading(Parcelable parcelable) {
        parcelable.writeToParcel(mParcel, 0 /* flags */);
        mParcel.setDataPosition(0);
    }

    /** Stub implementation of IApplicationThread that can be presented as {@link Binder}. */
    class StubAppThread extends android.app.IApplicationThread.Stub  {

        @Override
        public void scheduleTransaction(ClientTransaction transaction) throws RemoteException {
        }

        @Override
        public void scheduleReceiver(Intent intent, ActivityInfo activityInfo,
                CompatibilityInfo compatibilityInfo, int i, String s, Bundle bundle, boolean b,
                int i1, int i2) throws RemoteException {
        }

        @Override
        public void scheduleCreateService(IBinder iBinder, ServiceInfo serviceInfo,
                CompatibilityInfo compatibilityInfo, int i) throws RemoteException {
        }

        @Override
        public void scheduleStopService(IBinder iBinder) throws RemoteException {
        }

        @Override
        public void bindApplication(String s, ApplicationInfo applicationInfo,
                List<ProviderInfo> list, ComponentName componentName, ProfilerInfo profilerInfo,
                Bundle bundle, IInstrumentationWatcher iInstrumentationWatcher,
                IUiAutomationConnection iUiAutomationConnection, int i, boolean b, boolean b1,
                boolean b2, boolean b3, Configuration configuration,
                CompatibilityInfo compatibilityInfo, Map map, Bundle bundle1, String s1,
                AutofillOptions ao, ContentCaptureOptions co) throws RemoteException {
        }

        @Override
        public void scheduleExit() throws RemoteException {
        }

        @Override
        public void scheduleServiceArgs(IBinder iBinder, ParceledListSlice parceledListSlice)
                throws RemoteException {
        }

        @Override
        public void updateTimeZone() throws RemoteException {
        }

        @Override
        public void processInBackground() throws RemoteException {
        }

        @Override
        public void scheduleBindService(IBinder iBinder, Intent intent, boolean b, int i)
                throws RemoteException {
        }

        @Override
        public void scheduleUnbindService(IBinder iBinder, Intent intent) throws RemoteException {
        }

        @Override
        public void dumpService(ParcelFileDescriptor parcelFileDescriptor, IBinder iBinder,
                String[] strings) throws RemoteException {
        }

        @Override
        public void scheduleRegisteredReceiver(IIntentReceiver iIntentReceiver, Intent intent,
                int i, String s, Bundle bundle, boolean b, boolean b1, int i1, int i2)
                throws RemoteException {
        }

        @Override
        public void scheduleLowMemory() throws RemoteException {
        }

        @Override
        public void scheduleSleeping(IBinder iBinder, boolean b) throws RemoteException {
        }

        @Override
        public void profilerControl(boolean b, ProfilerInfo profilerInfo, int i)
                throws RemoteException {
        }

        @Override
        public void setSchedulingGroup(int i) throws RemoteException {
        }

        @Override
        public void scheduleCreateBackupAgent(ApplicationInfo applicationInfo,
                CompatibilityInfo compatibilityInfo, int i, int userId) throws RemoteException {
        }

        @Override
        public void scheduleDestroyBackupAgent(ApplicationInfo applicationInfo,
                CompatibilityInfo compatibilityInfo, int userId) throws RemoteException {
        }

        @Override
        public void scheduleOnNewActivityOptions(IBinder iBinder, Bundle bundle)
                throws RemoteException {
        }

        @Override
        public void scheduleSuicide() throws RemoteException {
        }

        @Override
        public void dispatchPackageBroadcast(int i, String[] strings) throws RemoteException {
        }

        @Override
        public void scheduleCrash(String s) throws RemoteException {
        }

        @Override
        public void dumpActivity(ParcelFileDescriptor parcelFileDescriptor, IBinder iBinder,
                String s, String[] strings) throws RemoteException {
        }

        @Override
        public void clearDnsCache() throws RemoteException {
        }

        @Override
        public void updateHttpProxy() throws RemoteException {
        }

        @Override
        public void setCoreSettings(Bundle bundle) throws RemoteException {
        }

        @Override
        public void updatePackageCompatibilityInfo(String s, CompatibilityInfo compatibilityInfo)
                throws RemoteException {
        }

        @Override
        public void scheduleTrimMemory(int i) throws RemoteException {
        }

        @Override
        public void dumpMemInfo(ParcelFileDescriptor parcelFileDescriptor,
                Debug.MemoryInfo memoryInfo, boolean b, boolean b1, boolean b2, boolean b3,
                boolean b4, String[] strings) throws RemoteException {
        }

        @Override
        public void dumpMemInfoProto(ParcelFileDescriptor parcelFileDescriptor,
                Debug.MemoryInfo memoryInfo, boolean b, boolean b1, boolean b2,
                boolean b3, String[] strings) throws RemoteException {
        }

        @Override
        public void dumpGfxInfo(ParcelFileDescriptor parcelFileDescriptor, String[] strings)
                throws RemoteException {
        }

        @Override
        public void dumpProvider(ParcelFileDescriptor parcelFileDescriptor, IBinder iBinder,
                String[] strings) throws RemoteException {
        }

        @Override
        public void dumpDbInfo(ParcelFileDescriptor parcelFileDescriptor, String[] strings)
                throws RemoteException {
        }

        @Override
        public void unstableProviderDied(IBinder iBinder) throws RemoteException {
        }

        @Override
        public void requestAssistContextExtras(IBinder iBinder, IBinder iBinder1, int i, int i1,
                int i2) throws RemoteException {
        }

        @Override
        public void scheduleTranslucentConversionComplete(IBinder iBinder, boolean b)
                throws RemoteException {
        }

        @Override
        public void setProcessState(int i) throws RemoteException {
        }

        @Override
        public void scheduleInstallProvider(ProviderInfo providerInfo) throws RemoteException {
        }

        @Override
        public void updateTimePrefs(int i) throws RemoteException {
        }

        @Override
        public void scheduleEnterAnimationComplete(IBinder iBinder) throws RemoteException {
        }

        @Override
        public void notifyCleartextNetwork(byte[] bytes) throws RemoteException {
        }

        @Override
        public void startBinderTracking() throws RemoteException {
        }

        @Override
        public void stopBinderTrackingAndDump(ParcelFileDescriptor parcelFileDescriptor)
                throws RemoteException {
        }

        @Override
        public void scheduleLocalVoiceInteractionStarted(IBinder iBinder,
                IVoiceInteractor iVoiceInteractor) throws RemoteException {
        }

        @Override
        public void handleTrustStorageUpdate() throws RemoteException {
        }

        @Override
        public void attachAgent(String s) throws RemoteException {
        }

        @Override
        public void scheduleApplicationInfoChanged(ApplicationInfo applicationInfo)
                throws RemoteException {
        }

        @Override
        public void setNetworkBlockSeq(long l) throws RemoteException {
        }

        @Override
        public void dumpHeap(boolean managed, boolean mallocInfo, boolean runGc, String path,
                ParcelFileDescriptor fd, RemoteCallback finishCallback) {
        }

        @Override
        public final void runIsolatedEntryPoint(String entryPoint, String[] entryPointArgs) {
        }

        @Override
        public void requestDirectActions(IBinder activityToken, IVoiceInteractor interactor,
                RemoteCallback cancellationCallback, RemoteCallback resultCallback) {
        }

        @Override
        public void performDirectAction(IBinder activityToken, String actionId, Bundle arguments,
                RemoteCallback cancellationCallback, RemoteCallback resultCallback) {
        }
    }
}
