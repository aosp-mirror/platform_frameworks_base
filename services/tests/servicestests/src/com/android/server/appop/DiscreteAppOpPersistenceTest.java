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

import static android.app.AppOpsManager.ATTRIBUTION_FLAG_ACCESSOR;
import static android.app.AppOpsManager.ATTRIBUTION_FLAG_RECEIVER;
import static android.app.AppOpsManager.OP_FLAGS_ALL_TRUSTED;
import static android.app.AppOpsManager.OP_FLAG_SELF;
import static android.app.AppOpsManager.UID_STATE_FOREGROUND;
import static android.app.AppOpsManager.UID_STATE_FOREGROUND_SERVICE;

import static com.google.common.truth.Truth.assertThat;

import android.app.AppOpsManager;
import android.companion.virtual.VirtualDeviceManager;
import android.content.Context;
import android.os.FileUtils;
import android.os.Process;
import android.permission.flags.Flags;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class DiscreteAppOpPersistenceTest {
    private DiscreteRegistry mDiscreteRegistry;
    private final Object mLock = new Object();
    private File mMockDataDirectory;
    private final Context mContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    public void setUp() {
        mMockDataDirectory = mContext.getDir("mock_data", Context.MODE_PRIVATE);
        mDiscreteRegistry = new DiscreteRegistry(mLock, mMockDataDirectory);
        mDiscreteRegistry.systemReady();
    }

    @After
    public void cleanUp() {
        mDiscreteRegistry.writeAndClearAccessHistory();
        FileUtils.deleteContents(mMockDataDirectory);
    }

    @RequiresFlagsEnabled(Flags.FLAG_DEVICE_AWARE_APP_OP_NEW_SCHEMA_ENABLED)
    @Test
    public void defaultDevice_recordAccess_persistToDisk() {
        int uid = Process.myUid();
        String packageName = mContext.getOpPackageName();
        int op = AppOpsManager.OP_CAMERA;
        String deviceId = VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT;
        long accessTime = System.currentTimeMillis();
        long duration = 60000L;
        int uidState = UID_STATE_FOREGROUND;
        int opFlags = OP_FLAGS_ALL_TRUSTED;
        int attributionFlags = ATTRIBUTION_FLAG_ACCESSOR;
        int attributionChainId = AppOpsManager.ATTRIBUTION_CHAIN_ID_NONE;

        mDiscreteRegistry.recordDiscreteAccess(uid, packageName, deviceId, op, null, opFlags,
                uidState, accessTime, duration, attributionFlags, attributionChainId,
                DiscreteRegistry.ACCESS_TYPE_FINISH_OP);

        // Verify in-memory object is correct
        fetchDiscreteOpsAndValidate(uid, packageName, op, deviceId, null, accessTime,
                duration, uidState, opFlags, attributionFlags, attributionChainId);

        // Write to disk and clear the in-memory object
        mDiscreteRegistry.writeAndClearAccessHistory();

        // Verify the storage file is created and then verify its content is correct
        File[] files = FileUtils.listFilesOrEmpty(mMockDataDirectory);
        assertThat(files.length).isEqualTo(1);
        fetchDiscreteOpsAndValidate(uid, packageName, op, deviceId, null, accessTime,
                duration, uidState, opFlags, attributionFlags, attributionChainId);
    }

    @RequiresFlagsEnabled(Flags.FLAG_DEVICE_AWARE_APP_OP_NEW_SCHEMA_ENABLED)
    @Test
    public void externalDevice_recordAccess_persistToDisk() {
        int uid = Process.myUid();
        String packageName = mContext.getOpPackageName();
        int op = AppOpsManager.OP_CAMERA;
        String deviceId = "companion:1";
        long accessTime = System.currentTimeMillis();
        long duration = -1;
        int uidState = UID_STATE_FOREGROUND_SERVICE;
        int opFlags = OP_FLAG_SELF;
        int attributionFlags = ATTRIBUTION_FLAG_RECEIVER;
        int attributionChainId = 10;

        mDiscreteRegistry.recordDiscreteAccess(uid, packageName, deviceId, op, null, opFlags,
                uidState, accessTime, duration, attributionFlags, attributionChainId,
                DiscreteRegistry.ACCESS_TYPE_START_OP);

        fetchDiscreteOpsAndValidate(uid, packageName, op, deviceId, null, accessTime,
                duration, uidState, opFlags, attributionFlags, attributionChainId);

        mDiscreteRegistry.writeAndClearAccessHistory();

        File[] files = FileUtils.listFilesOrEmpty(mMockDataDirectory);
        assertThat(files.length).isEqualTo(1);
        fetchDiscreteOpsAndValidate(uid, packageName, op, deviceId, null, accessTime,
                duration, uidState, opFlags, attributionFlags, attributionChainId);
    }

    private void fetchDiscreteOpsAndValidate(int expectedUid, String expectedPackageName,
            int expectedOp, String expectedDeviceId, String expectedAttrTag,
            long expectedAccessTime, long expectedAccessDuration, int expectedUidState,
            int expectedOpFlags, int expectedAttrFlags, int expectedAttrChainId) {
        DiscreteRegistry.DiscreteOps discreteOps = mDiscreteRegistry.getAllDiscreteOps();

        assertThat(discreteOps.isEmpty()).isFalse();
        assertThat(discreteOps.mUids.size()).isEqualTo(1);

        DiscreteRegistry.DiscreteUidOps discreteUidOps = discreteOps.mUids.get(expectedUid);
        assertThat(discreteUidOps.mPackages.size()).isEqualTo(1);

        DiscreteRegistry.DiscretePackageOps discretePackageOps =
                discreteUidOps.mPackages.get(expectedPackageName);
        assertThat(discretePackageOps.mPackageOps.size()).isEqualTo(1);

        DiscreteRegistry.DiscreteOp discreteOp = discretePackageOps.mPackageOps.get(expectedOp);
        assertThat(discreteOp.mDeviceAttributedOps.size()).isEqualTo(1);

        DiscreteRegistry.DiscreteDeviceOp discreteDeviceOp =
                discreteOp.mDeviceAttributedOps.get(expectedDeviceId);
        assertThat(discreteDeviceOp.mAttributedOps.size()).isEqualTo(1);

        List<DiscreteRegistry.DiscreteOpEvent> discreteOpEvents =
                discreteDeviceOp.mAttributedOps.get(expectedAttrTag);
        assertThat(discreteOpEvents.size()).isEqualTo(1);

        DiscreteRegistry.DiscreteOpEvent discreteOpEvent = discreteOpEvents.get(0);
        assertThat(discreteOpEvent.mNoteTime).isEqualTo(expectedAccessTime);
        assertThat(discreteOpEvent.mNoteDuration).isEqualTo(expectedAccessDuration);
        assertThat(discreteOpEvent.mUidState).isEqualTo(expectedUidState);
        assertThat(discreteOpEvent.mOpFlag).isEqualTo(expectedOpFlags);
        assertThat(discreteOpEvent.mAttributionFlags).isEqualTo(expectedAttrFlags);
        assertThat(discreteOpEvent.mAttributionChainId).isEqualTo(expectedAttrChainId);
    }
}
