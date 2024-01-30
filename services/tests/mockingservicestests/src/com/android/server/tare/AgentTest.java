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

package com.android.server.tare;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.server.tare.TareTestUtils.assertLedgersEqual;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;

import android.app.AlarmManager;
import android.content.Context;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

/** Tests various aspects of the Agent. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class AgentTest {
    private MockitoSession mMockingSession;
    @Mock
    private CompleteEconomicPolicy mEconomicPolicy;
    @Mock
    private Analyst mAnalyst;
    @Mock
    private Context mContext;
    @Mock
    private InternalResourceService mIrs;

    private Agent mAgent;
    private Scribe mScribe;

    private static class MockScribe extends Scribe {
        MockScribe(InternalResourceService irs, Analyst analyst) {
            super(irs, analyst);
        }

        @Override
        void postWrite() {
            // Do nothing
        }
    }

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .mockStatic(LocalServices.class)
                .startMocking();
        doReturn(mContext).when(mIrs).getContext();
        doReturn(mEconomicPolicy).when(mIrs).getCompleteEconomicPolicyLocked();
        doReturn(mIrs).when(mIrs).getLock();
        doReturn(mock(AlarmManager.class)).when(mContext).getSystemService(Context.ALARM_SERVICE);
        mScribe = new MockScribe(mIrs, mAnalyst);
        mAgent = new Agent(mIrs, mScribe, mAnalyst);
    }

    @After
    public void tearDown() {
        mAgent.tearDownLocked();

        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    @Test
    public void testAppRemoval() {
        final long consumptionLimit = 1_000_000L;
        final long remainingCakes = consumptionLimit / 2;
        mScribe.setConsumptionLimitLocked(consumptionLimit);
        mScribe.adjustRemainingConsumableCakesLocked(remainingCakes - consumptionLimit);
        assertEquals(remainingCakes, mScribe.getRemainingConsumableCakesLocked());

        final int userId = 0;
        final String pkgName = "com.test";
        final Ledger ledger = mScribe.getLedgerLocked(userId, pkgName);

        doReturn(consumptionLimit).when(mIrs).getConsumptionLimitLocked();
        doReturn(consumptionLimit).when(mEconomicPolicy)
                .getMaxSatiatedBalance(anyInt(), anyString());

        Ledger.Transaction transaction = new Ledger.Transaction(0, 0, 0, null, 5, 10);
        mAgent.recordTransactionLocked(userId, pkgName, ledger, transaction, false);
        assertEquals(5, ledger.getCurrentBalance());
        assertEquals(remainingCakes - 10, mScribe.getRemainingConsumableCakesLocked());

        mAgent.onPackageRemovedLocked(userId, pkgName);
        assertEquals(remainingCakes - 10, mScribe.getRemainingConsumableCakesLocked());
        assertLedgersEqual(new Ledger(), mScribe.getLedgerLocked(userId, pkgName));
    }

    @Test
    public void testRecordTransaction_UnderMax() {
        Ledger ledger = new Ledger();

        doReturn(1_000_000L).when(mIrs).getConsumptionLimitLocked();
        doReturn(1_000_000L).when(mEconomicPolicy).getMaxSatiatedBalance(anyInt(), anyString());

        Ledger.Transaction transaction = new Ledger.Transaction(0, 0, 0, null, 5, 0);
        mAgent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(5, ledger.getCurrentBalance());

        transaction = new Ledger.Transaction(0, 0, 0, null, 995, 0);
        mAgent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(1000, ledger.getCurrentBalance());

        transaction = new Ledger.Transaction(0, 0, 0, null, -500, 250);
        mAgent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(500, ledger.getCurrentBalance());

        transaction = new Ledger.Transaction(0, 0, 0, null, 999_500L, 500);
        mAgent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(1_000_000L, ledger.getCurrentBalance());

        transaction = new Ledger.Transaction(0, 0, 0, null, -1_000_001L, 1000);
        mAgent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(-1, ledger.getCurrentBalance());
    }

    @Test
    public void testRecordTransaction_MaxConsumptionLimit() {
        Ledger ledger = new Ledger();

        doReturn(1000L).when(mIrs).getConsumptionLimitLocked();
        doReturn(1_000_000L).when(mEconomicPolicy).getMaxSatiatedBalance(anyInt(), anyString());

        Ledger.Transaction transaction = new Ledger.Transaction(0, 0, 0, null, 5, 0);
        mAgent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(5, ledger.getCurrentBalance());

        transaction = new Ledger.Transaction(0, 0, 0, null, 995, 0);
        mAgent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(1000, ledger.getCurrentBalance());

        transaction = new Ledger.Transaction(0, 0, 0, null, -500, 250);
        mAgent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(500, ledger.getCurrentBalance());

        transaction = new Ledger.Transaction(0, 0, 0, null, 2000, 0);
        mAgent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(2500, ledger.getCurrentBalance());

        // ConsumptionLimit can change as the battery level changes. Ledger balances shouldn't be
        // affected.
        doReturn(900L).when(mIrs).getConsumptionLimitLocked();

        transaction = new Ledger.Transaction(0, 0, 0, null, 100, 0);
        mAgent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(2600, ledger.getCurrentBalance());

        transaction = new Ledger.Transaction(0, 0, 0, null, -50, 50);
        mAgent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(2550, ledger.getCurrentBalance());

        transaction = new Ledger.Transaction(0, 0, 0, null, -200, 100);
        mAgent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(2350, ledger.getCurrentBalance());

        doReturn(800L).when(mIrs).getConsumptionLimitLocked();

        transaction = new Ledger.Transaction(0, 0, 0, null, 100, 0);
        mAgent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(2450, ledger.getCurrentBalance());
    }

    @Test
    public void testRecordTransaction_MaxSatiatedBalance() {
        Ledger ledger = new Ledger();

        doReturn(1_000_000L).when(mIrs).getConsumptionLimitLocked();
        doReturn(1000L).when(mEconomicPolicy).getMaxSatiatedBalance(anyInt(), anyString());

        Ledger.Transaction transaction = new Ledger.Transaction(0, 0, 0, null, 5, 0);
        mAgent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(5, ledger.getCurrentBalance());

        transaction = new Ledger.Transaction(0, 0, 0, null, 995, 0);
        mAgent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(1000, ledger.getCurrentBalance());

        transaction = new Ledger.Transaction(0, 0, 0, null, -500, 250);
        mAgent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(500, ledger.getCurrentBalance());

        transaction = new Ledger.Transaction(0, 0, 0, null, 999_500L, 1000);
        mAgent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(1_000, ledger.getCurrentBalance());

        // Shouldn't change in normal operation, but adding test case in case it does.
        doReturn(900L).when(mEconomicPolicy).getMaxSatiatedBalance(anyInt(), anyString());

        transaction = new Ledger.Transaction(0, 0, 0, null, 500, 0);
        mAgent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(1_000, ledger.getCurrentBalance());

        transaction = new Ledger.Transaction(0, 0, 0, null, -1001, 500);
        mAgent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(-1, ledger.getCurrentBalance());
    }
}
