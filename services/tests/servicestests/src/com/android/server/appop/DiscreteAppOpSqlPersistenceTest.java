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
import static android.app.AppOpsManager.ATTRIBUTION_FLAG_TRUSTED;
import static android.app.AppOpsManager.UID_STATE_FOREGROUND;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Process;
import android.util.ArraySet;
import android.util.Log;
import android.util.LongSparseArray;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.appop.DiscreteOpsSqlRegistry.DiscreteOp;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class DiscreteAppOpSqlPersistenceTest {
    private static final String DATABASE_NAME = "test_app_ops.db";
    private DiscreteOpsSqlRegistry mDiscreteRegistry;
    private final Context mContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Before
    public void setUp() {
        mDiscreteRegistry = new DiscreteOpsSqlRegistry(mContext,
                mContext.getDatabasePath(DATABASE_NAME));
        mDiscreteRegistry.systemReady();
    }

    @After
    public void cleanUp() {
        mContext.deleteDatabase(DATABASE_NAME);
    }

    @Test
    public void discreteOpEventIsRecorded() {
        DiscreteOp opEvent = new DiscreteOpBuilder(mContext).build();
        mDiscreteRegistry.recordDiscreteAccess(opEvent);
        List<DiscreteOp> discreteOps = mDiscreteRegistry.getCachedDiscreteOps();
        assertThat(discreteOps.size()).isEqualTo(1);
        assertThat(discreteOps).contains(opEvent);
    }

    @Test
    public void discreteOpEventIsPersistedToDisk() {
        DiscreteOp opEvent = new DiscreteOpBuilder(mContext).build();
        mDiscreteRegistry.recordDiscreteAccess(opEvent);
        flushDiscreteOpsToDatabase();
        assertThat(mDiscreteRegistry.getCachedDiscreteOps()).isEmpty();
        List<DiscreteOp> discreteOps = mDiscreteRegistry.getAllDiscreteOps();
        assertThat(discreteOps.size()).isEqualTo(1);
        assertThat(discreteOps).contains(opEvent);
    }

    @Test
    public void discreteOpEventInSameMinuteIsNotRecorded() {
        long oneMinuteMillis = Duration.ofMinutes(1).toMillis();
        // round timestamp at minute level and add 5 seconds
        long accessTime = System.currentTimeMillis() / oneMinuteMillis * oneMinuteMillis + 5000;
        DiscreteOp opEvent = new DiscreteOpBuilder(mContext).setAccessTime(accessTime).build();
        mDiscreteRegistry.recordDiscreteAccess(opEvent);
        // create duplicate event in same minute, with added 30 seconds
        DiscreteOp opEvent2 =
                new DiscreteOpBuilder(mContext).setAccessTime(accessTime + 30000).build();
        mDiscreteRegistry.recordDiscreteAccess(opEvent2);
        List<DiscreteOp> discreteOps = mDiscreteRegistry.getAllDiscreteOps();

        assertThat(discreteOps.size()).isEqualTo(1);
        assertThat(discreteOps).contains(opEvent);
    }

    @Test
    public void multipleDiscreteOpEventAreRecorded() {
        DiscreteOp opEvent = new DiscreteOpBuilder(mContext).build();
        DiscreteOp opEvent2 = new DiscreteOpBuilder(mContext).setPackageName(
                "test.package").build();
        mDiscreteRegistry.recordDiscreteAccess(opEvent);
        mDiscreteRegistry.recordDiscreteAccess(opEvent2);

        List<DiscreteOp> discreteOps = mDiscreteRegistry.getAllDiscreteOps();
        assertThat(discreteOps).contains(opEvent);
        assertThat(discreteOps).contains(opEvent2);
        assertThat(discreteOps.size()).isEqualTo(2);
    }

    @Test
    public void clearDiscreteOps() {
        DiscreteOp opEvent = new DiscreteOpBuilder(mContext).build();
        mDiscreteRegistry.recordDiscreteAccess(opEvent);
        flushDiscreteOpsToDatabase();
        DiscreteOp opEvent2 = new DiscreteOpBuilder(mContext).setUid(12345).setPackageName(
                "abc").build();
        mDiscreteRegistry.recordDiscreteAccess(opEvent2);
        mDiscreteRegistry.clearHistory();
        assertThat(mDiscreteRegistry.getAllDiscreteOps()).isEmpty();
    }

    @Test
    public void clearDiscreteOpsForPackage() {
        DiscreteOp opEvent = new DiscreteOpBuilder(mContext).build();
        mDiscreteRegistry.recordDiscreteAccess(opEvent);
        flushDiscreteOpsToDatabase();
        mDiscreteRegistry.recordDiscreteAccess(new DiscreteOpBuilder(mContext).build());
        mDiscreteRegistry.clearHistory(Process.myUid(), mContext.getPackageName());

        assertThat(mDiscreteRegistry.getAllDiscreteOps()).isEmpty();
    }

    @Test
    public void offsetDiscreteOps() {
        DiscreteOp opEvent = new DiscreteOpBuilder(mContext).build();
        long event2AccessTime = System.currentTimeMillis() - 300000;
        DiscreteOp opEvent2 = new DiscreteOpBuilder(mContext).setAccessTime(
                event2AccessTime).build();
        mDiscreteRegistry.recordDiscreteAccess(opEvent);
        flushDiscreteOpsToDatabase();
        mDiscreteRegistry.recordDiscreteAccess(opEvent2);
        long offset = Duration.ofMinutes(2).toMillis();

        mDiscreteRegistry.offsetHistory(offset);

        // adjust input for assertion
        DiscreteOp e1 = new DiscreteOpBuilder(opEvent)
                .setAccessTime(opEvent.getAccessTime() - offset).build();
        DiscreteOp e2 = new DiscreteOpBuilder(opEvent2)
                .setAccessTime(event2AccessTime - offset).build();

        List<DiscreteOp> results = mDiscreteRegistry.getAllDiscreteOps();
        assertThat(results.size()).isEqualTo(2);
        assertThat(results).contains(e1);
        assertThat(results).contains(e2);
    }

    @Test
    public void completeAttributionChain() {
        long chainId = 100;
        DiscreteOp event1 = new DiscreteOpBuilder(mContext)
                .setChainId(chainId)
                .setAttributionFlags(ATTRIBUTION_FLAG_RECEIVER | ATTRIBUTION_FLAG_TRUSTED)
                .build();
        DiscreteOp event2 = new DiscreteOpBuilder(mContext)
                .setChainId(chainId)
                .setAttributionFlags(ATTRIBUTION_FLAG_ACCESSOR | ATTRIBUTION_FLAG_TRUSTED)
                .build();
        List<DiscreteOp> events = new ArrayList<>();
        events.add(event1);
        events.add(event2);

        LongSparseArray<DiscreteOpsSqlRegistry.AttributionChain> chains =
                mDiscreteRegistry.createAttributionChains(events, new ArraySet<>());

        assertThat(chains.size()).isGreaterThan(0);
        DiscreteOpsSqlRegistry.AttributionChain chain = chains.get(chainId);
        assertThat(chain).isNotNull();
        assertThat(chain.isComplete()).isTrue();
        assertThat(chain.getStart()).isEqualTo(event1);
        assertThat(chain.getLastVisible()).isEqualTo(event2);
    }

    @Test
    public void addToHistoricalOps() {
        long beginTimeMillis = System.currentTimeMillis();
        DiscreteOp event1 = new DiscreteOpBuilder(mContext)
                .build();
        DiscreteOp event2 = new DiscreteOpBuilder(mContext)
                .setUid(123457)
                .build();
        mDiscreteRegistry.recordDiscreteAccess(event1);
        flushDiscreteOpsToDatabase();
        mDiscreteRegistry.recordDiscreteAccess(event2);

        long endTimeMillis = System.currentTimeMillis() + 500;
        AppOpsManager.HistoricalOps results = new AppOpsManager.HistoricalOps(beginTimeMillis,
                endTimeMillis);

        mDiscreteRegistry.addFilteredDiscreteOpsToHistoricalOps(results, beginTimeMillis,
                endTimeMillis, 0, 0, null, null, null, 0, new ArraySet<>());
        Log.i("Manjeet", "TEST read " + results);
        assertWithMessage("results shouldn't be empty").that(results.isEmpty()).isFalse();
    }

    @Test
    public void dump() {
        DiscreteOp event1 = new DiscreteOpBuilder(mContext)
                .setAccessTime(1732221340628L)
                .setUid(12345)
                .build();
        DiscreteOp event2 = new DiscreteOpBuilder(mContext)
                .setAccessTime(1732227340628L)
                .setUid(123457)
                .build();
        mDiscreteRegistry.recordDiscreteAccess(event1);
        flushDiscreteOpsToDatabase();
        mDiscreteRegistry.recordDiscreteAccess(event2);
    }

    /** This clears in-memory cache and push records into the database. */
    private void flushDiscreteOpsToDatabase() {
        mDiscreteRegistry.writeAndClearOldAccessHistory();
    }

    /**
     * Creates default op event for CAMERA app op with current time as access time
     * and 1 minute duration
     */
    private static class DiscreteOpBuilder {
        private int mUid;
        private String mPackageName;
        private String mAttributionTag;
        private String mDeviceId;
        private int mOpCode;
        private int mOpFlags;
        private int mAttributionFlags;
        private int mUidState;
        private long mChainId;
        private long mAccessTime;
        private long mDuration;

        DiscreteOpBuilder(Context context) {
            mUid = Process.myUid();
            mPackageName = context.getPackageName();
            mAttributionTag = null;
            mDeviceId = String.valueOf(context.getDeviceId());
            mOpCode = AppOpsManager.OP_CAMERA;
            mOpFlags = AppOpsManager.OP_FLAG_SELF;
            mAttributionFlags = ATTRIBUTION_FLAG_ACCESSOR;
            mUidState = UID_STATE_FOREGROUND;
            mChainId = AppOpsManager.ATTRIBUTION_CHAIN_ID_NONE;
            mAccessTime = System.currentTimeMillis();
            mDuration = Duration.ofMinutes(1).toMillis();
        }

        DiscreteOpBuilder(DiscreteOp discreteOp) {
            this.mUid = discreteOp.getUid();
            this.mPackageName = discreteOp.getPackageName();
            this.mAttributionTag = discreteOp.getAttributionTag();
            this.mDeviceId = discreteOp.getDeviceId();
            this.mOpCode = discreteOp.getOpCode();
            this.mOpFlags = discreteOp.getOpFlags();
            this.mAttributionFlags = discreteOp.getAttributionFlags();
            this.mUidState = discreteOp.getUidState();
            this.mChainId = discreteOp.getChainId();
            this.mAccessTime = discreteOp.getAccessTime();
            this.mDuration = discreteOp.getDuration();
        }

        public DiscreteOpBuilder setUid(int uid) {
            this.mUid = uid;
            return this;
        }

        public DiscreteOpBuilder setPackageName(String packageName) {
            this.mPackageName = packageName;
            return this;
        }

        public DiscreteOpBuilder setAttributionFlags(int attributionFlags) {
            this.mAttributionFlags = attributionFlags;
            return this;
        }

        public DiscreteOpBuilder setChainId(long chainId) {
            this.mChainId = chainId;
            return this;
        }

        public DiscreteOpBuilder setAccessTime(long accessTime) {
            this.mAccessTime = accessTime;
            return this;
        }

        public DiscreteOp build() {
            return new DiscreteOp(mUid, mPackageName, mAttributionTag, mDeviceId, mOpCode, mOpFlags,
                    mAttributionFlags, mUidState, mChainId, mAccessTime, mDuration);
        }
    }
}
