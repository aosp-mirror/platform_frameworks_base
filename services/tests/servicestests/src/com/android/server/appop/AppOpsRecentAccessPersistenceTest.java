/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.appop;

import static android.app.AppOpsManager.OP_FLAGS_ALL;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.companion.virtual.VirtualDeviceManager;
import android.content.Context;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.SparseArray;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.LocalServices;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;

@RunWith(AndroidJUnit4.class)
public class AppOpsRecentAccessPersistenceTest {
    private static final String TAG = AppOpsRecentAccessPersistenceTest.class.getSimpleName();
    private static final String TEST_XML = "AppOpsPersistenceTest/recent_accesses.xml";

    private final Context mContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private File mMockDataDirectory;
    private File mRecentAccessFile;
    private AppOpsService mAppOpsService;

    @Rule public final MockitoRule mockito = MockitoJUnit.rule();
    @Mock private AppOpsServiceTestingShim mAppOpCheckingService;

    @Before
    public void setUp() {
        when(mAppOpCheckingService.addAppOpsModeChangedListener(any())).thenReturn(true);
        LocalServices.addService(AppOpsCheckingServiceInterface.class, mAppOpCheckingService);

        mMockDataDirectory = mContext.getDir("mock_data", Context.MODE_PRIVATE);
        mRecentAccessFile = new File(mMockDataDirectory, "test_accesses.xml");

        HandlerThread handlerThread = new HandlerThread(TAG);
        handlerThread.start();
        Handler handler = new Handler(handlerThread.getLooper());
        mAppOpsService = new AppOpsService(mRecentAccessFile, mRecentAccessFile, handler, mContext);
    }

    @After
    public void cleanUp() {
        FileUtils.deleteContents(mMockDataDirectory);
    }

    @Test
    public void readAndWriteRecentAccesses() throws Exception {
        copyRecentAccessFromAsset(mContext, TEST_XML, mRecentAccessFile);
        SparseArray<AppOpsService.UidState> uidStates = new SparseArray<>();

        AtomicFile recentAccessFile = new AtomicFile(mRecentAccessFile);
        AppOpsRecentAccessPersistence persistence =
                new AppOpsRecentAccessPersistence(recentAccessFile, mAppOpsService);

        persistence.readRecentAccesses(uidStates);
        validateUidStates(uidStates);

        // Now we clear the xml file and write uidStates to it, then read again to verify data
        // written to the xml is correct.
        recentAccessFile.delete();
        persistence.writeRecentAccesses(uidStates);

        SparseArray<AppOpsService.UidState> newUidStates = new SparseArray<>();
        persistence.readRecentAccesses(newUidStates);
        validateUidStates(newUidStates);
    }

    // We compare data loaded into uidStates with original data in recent_accesses.xml
    private void validateUidStates(SparseArray<AppOpsService.UidState> uidStates) {
        assertThat(uidStates.size()).isEqualTo(1);

        AppOpsService.UidState uidState = uidStates.get(10001);
        assertThat(uidState.uid).isEqualTo(10001);

        ArrayMap<String, AppOpsService.Ops> packageOps = uidState.pkgOps;
        assertThat(packageOps.size()).isEqualTo(1);

        AppOpsService.Ops ops = packageOps.get("com.android.servicestests.apps.testapp");
        assertThat(ops.size()).isEqualTo(1);

        AppOpsService.Op op = ops.get(26);
        assertThat(op.mDeviceAttributedOps.size()).isEqualTo(2);

        // Test AppOp access for the default device
        AttributedOp attributedOp =
                op.mDeviceAttributedOps
                        .get(VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT)
                        .get("attribution.tag.test.1");
        assertThat(attributedOp.persistentDeviceId)
                .isEqualTo(VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT);
        assertThat(attributedOp.tag).isEqualTo("attribution.tag.test.1");

        AppOpsManager.AttributedOpEntry attributedOpEntry =
                attributedOp.createAttributedOpEntryLocked();

        assertThat(attributedOpEntry.getLastAccessTime(OP_FLAGS_ALL)).isEqualTo(1710799464518L);
        assertThat(attributedOpEntry.getLastDuration(OP_FLAGS_ALL)).isEqualTo(2963);

        // Test AppOp access for an external device
        AttributedOp attributedOpForDevice = op.mDeviceAttributedOps.get("companion:1").get(null);
        assertThat(attributedOpForDevice.persistentDeviceId).isEqualTo("companion:1");

        AppOpsManager.AttributedOpEntry attributedOpEntryForDevice =
                attributedOpForDevice.createAttributedOpEntryLocked();
        assertThat(attributedOpEntryForDevice.getLastAccessTime(OP_FLAGS_ALL))
                .isEqualTo(1712610342977L);
        assertThat(attributedOpEntryForDevice.getLastDuration(OP_FLAGS_ALL)).isEqualTo(7596);

        AppOpsManager.OpEventProxyInfo proxyInfo =
                attributedOpEntryForDevice.getLastProxyInfo(OP_FLAGS_ALL);
        assertThat(proxyInfo.getUid()).isEqualTo(10002);
        assertThat(proxyInfo.getPackageName()).isEqualTo("com.android.servicestests.apps.proxy");
        assertThat(proxyInfo.getAttributionTag())
                .isEqualTo("com.android.servicestests.apps.proxy.attrtag");
        assertThat(proxyInfo.getDeviceId()).isEqualTo("companion:2");
    }

    private static void copyRecentAccessFromAsset(Context context, String xmlAsset, File outFile)
            throws IOException {
        writeToFile(outFile, readAsset(context, xmlAsset));
    }

    private static String readAsset(Context context, String assetPath) throws IOException {
        final StringBuilder sb = new StringBuilder();
        try (BufferedReader br =
                new BufferedReader(
                        new InputStreamReader(
                                context.getResources().getAssets().open(assetPath)))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
                sb.append(System.lineSeparator());
            }
        }
        return sb.toString();
    }

    private static void writeToFile(File path, String content) throws IOException {
        path.getParentFile().mkdirs();

        try (FileWriter writer = new FileWriter(path)) {
            writer.write(content);
        }
    }
}
