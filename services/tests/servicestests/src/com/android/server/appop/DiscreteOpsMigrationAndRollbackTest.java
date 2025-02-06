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
import static android.app.AppOpsManager.UID_STATE_FOREGROUND;

import static com.google.common.truth.Truth.assertThat;

import android.app.AppOpsManager;
import android.companion.virtual.VirtualDeviceManager;
import android.content.Context;
import android.os.FileUtils;
import android.os.Process;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.time.Duration;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class DiscreteOpsMigrationAndRollbackTest {
    private final Context mContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private static final String DATABASE_NAME = "test_app_ops.db";
    private static final int RECORD_COUNT = 500;
    private final File mMockDataDirectory = mContext.getDir("mock_data", Context.MODE_PRIVATE);
    final Object mLock = new Object();

    @After
    @Before
    public void clean() {
        mContext.deleteDatabase(DATABASE_NAME);
        FileUtils.deleteContents(mMockDataDirectory);
    }

    @Test
    public void migrateFromXmlToSqlite() {
        // write records to xml registry
        DiscreteOpsXmlRegistry xmlRegistry = new DiscreteOpsXmlRegistry(mLock, mMockDataDirectory);
        xmlRegistry.systemReady();
        for (int i = 1; i <= RECORD_COUNT; i++) {
            DiscreteOpsSqlRegistry.DiscreteOp opEvent =
                    new DiscreteOpBuilder(mContext)
                            .setChainId(i)
                            .setUid(10000 + i) // make all records unique
                            .build();
            xmlRegistry.recordDiscreteAccess(opEvent.getUid(), opEvent.getPackageName(),
                    opEvent.getDeviceId(), opEvent.getOpCode(), opEvent.getAttributionTag(),
                    opEvent.getOpFlags(), opEvent.getUidState(), opEvent.getAccessTime(),
                    opEvent.getDuration(), opEvent.getAttributionFlags(),
                    (int) opEvent.getChainId(), DiscreteOpsRegistry.ACCESS_TYPE_NOTE_OP);
        }
        xmlRegistry.writeAndClearOldAccessHistory();
        assertThat(xmlRegistry.readLargestChainIdFromDiskLocked()).isEqualTo(RECORD_COUNT);
        assertThat(xmlRegistry.getAllDiscreteOps().mUids.size()).isEqualTo(RECORD_COUNT);

        // migration to sql registry
        DiscreteOpsSqlRegistry sqlRegistry = new DiscreteOpsSqlRegistry(mContext,
                mContext.getDatabasePath(DATABASE_NAME));
        sqlRegistry.systemReady();
        DiscreteOpsMigrationHelper.migrateDiscreteOpsToSqlite(xmlRegistry, sqlRegistry);
        List<DiscreteOpsSqlRegistry.DiscreteOp> sqlOps = sqlRegistry.getAllDiscreteOps();

        assertThat(xmlRegistry.getAllDiscreteOps().mUids).isEmpty();
        assertThat(sqlOps.size()).isEqualTo(RECORD_COUNT);
        assertThat(sqlRegistry.getLargestAttributionChainId()).isEqualTo(RECORD_COUNT);
    }

    @Test
    public void migrateFromSqliteToXml() {
        // write to sql registry
        DiscreteOpsSqlRegistry sqlRegistry = new DiscreteOpsSqlRegistry(mContext,
                mContext.getDatabasePath(DATABASE_NAME));
        sqlRegistry.systemReady();
        for (int i = 1; i <= RECORD_COUNT; i++) {
            DiscreteOpsSqlRegistry.DiscreteOp opEvent =
                    new DiscreteOpBuilder(mContext)
                            .setChainId(i)
                            .setUid(RECORD_COUNT + i) // make all records unique
                            .build();
            sqlRegistry.recordDiscreteAccess(opEvent.getUid(), opEvent.getPackageName(),
                    opEvent.getDeviceId(), opEvent.getOpCode(), opEvent.getAttributionTag(),
                    opEvent.getOpFlags(), opEvent.getUidState(), opEvent.getAccessTime(),
                    opEvent.getDuration(), opEvent.getAttributionFlags(),
                    (int) opEvent.getChainId(), DiscreteOpsRegistry.ACCESS_TYPE_NOTE_OP);
        }
        sqlRegistry.writeAndClearOldAccessHistory();
        assertThat(sqlRegistry.getAllDiscreteOps().size()).isEqualTo(RECORD_COUNT);
        assertThat(sqlRegistry.getLargestAttributionChainId()).isEqualTo(RECORD_COUNT);

        // migration to xml registry
        DiscreteOpsXmlRegistry xmlRegistry = new DiscreteOpsXmlRegistry(mLock, mMockDataDirectory);
        xmlRegistry.systemReady();
        DiscreteOpsMigrationHelper.migrateDiscreteOpsToXml(sqlRegistry, xmlRegistry);
        DiscreteOpsXmlRegistry.DiscreteOps xmlOps = xmlRegistry.getAllDiscreteOps();

        assertThat(sqlRegistry.getAllDiscreteOps()).isEmpty();
        assertThat(xmlOps.mLargestChainId).isEqualTo(RECORD_COUNT);
        assertThat(xmlOps.mUids.size()).isEqualTo(RECORD_COUNT);
    }

    private static class DiscreteOpBuilder {
        private int mUid;
        private String mPackageName;
        private String mAttributionTag;
        private String mDeviceId;
        private int mOpCode;
        private int mOpFlags;
        private int mAttributionFlags;
        private int mUidState;
        private int mChainId;
        private long mAccessTime;
        private long mDuration;

        DiscreteOpBuilder(Context context) {
            mUid = Process.myUid();
            mPackageName = context.getPackageName();
            mAttributionTag = null;
            mDeviceId = VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT;
            mOpCode = AppOpsManager.OP_CAMERA;
            mOpFlags = AppOpsManager.OP_FLAG_SELF;
            mAttributionFlags = ATTRIBUTION_FLAG_ACCESSOR;
            mUidState = UID_STATE_FOREGROUND;
            mChainId = AppOpsManager.ATTRIBUTION_CHAIN_ID_NONE;
            mAccessTime = System.currentTimeMillis();
            mDuration = Duration.ofMinutes(1).toMillis();
        }

        public DiscreteOpBuilder setUid(int uid) {
            this.mUid = uid;
            return this;
        }

        public DiscreteOpBuilder setChainId(int chainId) {
            this.mChainId = chainId;
            return this;
        }

        public DiscreteOpsSqlRegistry.DiscreteOp build() {
            return new DiscreteOpsSqlRegistry.DiscreteOp(mUid, mPackageName, mAttributionTag,
                    mDeviceId,
                    mOpCode, mOpFlags, mAttributionFlags, mUidState, mChainId, mAccessTime,
                    mDuration);
        }
    }
}
