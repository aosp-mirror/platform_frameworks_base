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

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    private Context mContext;
    @Mock
    private InternalResourceService mIrs;

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .mockStatic(LocalServices.class)
                .startMocking();
        when(mIrs.getContext()).thenReturn(mContext);
        when(mIrs.getCompleteEconomicPolicyLocked()).thenReturn(mEconomicPolicy);
        when(mContext.getSystemService(Context.ALARM_SERVICE)).thenReturn(mock(AlarmManager.class));
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    @Test
    public void testRecordTransaction_UnderMax() {
        Agent agent = new Agent(mIrs);
        Ledger ledger = new Ledger();

        doReturn(1_000_000L).when(mIrs).getMaxCirculationLocked();
        doReturn(1_000_000L).when(mEconomicPolicy).getMaxSatiatedBalance();

        Ledger.Transaction transaction = new Ledger.Transaction(0, 0, 0, null, 5);
        agent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(5, ledger.getCurrentBalance());

        transaction = new Ledger.Transaction(0, 0, 0, null, 995);
        agent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(1000, ledger.getCurrentBalance());

        transaction = new Ledger.Transaction(0, 0, 0, null, -500);
        agent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(500, ledger.getCurrentBalance());

        transaction = new Ledger.Transaction(0, 0, 0, null, 999_500L);
        agent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(1_000_000L, ledger.getCurrentBalance());

        transaction = new Ledger.Transaction(0, 0, 0, null, -1_000_001L);
        agent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(-1, ledger.getCurrentBalance());
    }

    @Test
    public void testRecordTransaction_MaxCirculation() {
        Agent agent = new Agent(mIrs);
        Ledger ledger = new Ledger();

        doReturn(1000L).when(mIrs).getMaxCirculationLocked();
        doReturn(1000L).when(mEconomicPolicy).getMaxSatiatedBalance();

        Ledger.Transaction transaction = new Ledger.Transaction(0, 0, 0, null, 5);
        agent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(5, ledger.getCurrentBalance());

        transaction = new Ledger.Transaction(0, 0, 0, null, 995);
        agent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(1000, ledger.getCurrentBalance());

        transaction = new Ledger.Transaction(0, 0, 0, null, -500);
        agent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(500, ledger.getCurrentBalance());

        transaction = new Ledger.Transaction(0, 0, 0, null, 2000);
        agent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(1000, ledger.getCurrentBalance());

        // MaxCirculation can change as the battery level changes. Any already allocated ARCSs
        // shouldn't be removed by recordTransaction().
        doReturn(900L).when(mIrs).getMaxCirculationLocked();

        transaction = new Ledger.Transaction(0, 0, 0, null, 100);
        agent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(1000, ledger.getCurrentBalance());

        transaction = new Ledger.Transaction(0, 0, 0, null, -50);
        agent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(950, ledger.getCurrentBalance());

        transaction = new Ledger.Transaction(0, 0, 0, null, -200);
        agent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(750, ledger.getCurrentBalance());

        doReturn(800L).when(mIrs).getMaxCirculationLocked();

        transaction = new Ledger.Transaction(0, 0, 0, null, 100);
        agent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(800, ledger.getCurrentBalance());
    }

    @Test
    public void testRecordTransaction_MaxSatiatedBalance() {
        Agent agent = new Agent(mIrs);
        Ledger ledger = new Ledger();

        doReturn(1_000_000L).when(mIrs).getMaxCirculationLocked();
        doReturn(1000L).when(mEconomicPolicy).getMaxSatiatedBalance();

        Ledger.Transaction transaction = new Ledger.Transaction(0, 0, 0, null, 5);
        agent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(5, ledger.getCurrentBalance());

        transaction = new Ledger.Transaction(0, 0, 0, null, 995);
        agent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(1000, ledger.getCurrentBalance());

        transaction = new Ledger.Transaction(0, 0, 0, null, -500);
        agent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(500, ledger.getCurrentBalance());

        transaction = new Ledger.Transaction(0, 0, 0, null, 999_500L);
        agent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(1_000, ledger.getCurrentBalance());

        // Shouldn't change in normal operation, but adding test case in case it does.
        doReturn(900L).when(mEconomicPolicy).getMaxSatiatedBalance();

        transaction = new Ledger.Transaction(0, 0, 0, null, 500);
        agent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(1_000, ledger.getCurrentBalance());

        transaction = new Ledger.Transaction(0, 0, 0, null, -1001);
        agent.recordTransactionLocked(0, "com.test", ledger, transaction, false);
        assertEquals(-1, ledger.getCurrentBalance());
    }
}
