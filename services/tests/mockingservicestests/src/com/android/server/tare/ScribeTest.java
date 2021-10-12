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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.util.Log;
import android.util.SparseArrayMap;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.io.File;
import java.util.List;

/**
 * Tests for various Scribe behavior, including reading and writing correctly from file.
 *
 * atest FrameworksServicesTests:ScribeTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class ScribeTest {
    private static final String TAG = "ScribeTest";

    private static final int TEST_USER_ID = 27;
    private static final String TEST_PACKAGE = "com.android.test";

    private MockitoSession mMockingSession;
    private Scribe mScribeUnderTest;
    private File mTestFileDir;

    @Mock
    private InternalResourceService mIrs;
    @Mock
    private UserManagerInternal mUserManagerInternal;

    private Context getContext() {
        return InstrumentationRegistry.getContext();
    }

    @Before
    public void setUp() throws Exception {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .mockStatic(LocalServices.class)
                .startMocking();
        doReturn(mUserManagerInternal)
                .when(() -> LocalServices.getService(UserManagerInternal.class));
        when(mIrs.getLock()).thenReturn(new Object());
        when(mIrs.isEnabled()).thenReturn(true);
        when(mUserManagerInternal.getUserIds()).thenReturn(new int[]{TEST_USER_ID});
        mTestFileDir = new File(getContext().getFilesDir(), "scribe_test");
        //noinspection ResultOfMethodCallIgnored
        mTestFileDir.mkdirs();
        Log.d(TAG, "Saving data to '" + mTestFileDir + "'");
        mScribeUnderTest = new Scribe(mIrs, mTestFileDir);
    }

    @After
    public void tearDown() throws Exception {
        mScribeUnderTest.tearDownLocked();
        if (mTestFileDir.exists() && !mTestFileDir.delete()) {
            Log.w(TAG, "Failed to delete test file directory");
        }
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    @Test
    public void testWriteHighLevelStateToDisk() {
        long lastReclamationTime = System.currentTimeMillis();
        long narcsInCirculation = 2000L;

        Ledger ledger = mScribeUnderTest.getLedgerLocked(TEST_USER_ID, TEST_PACKAGE);
        ledger.recordTransaction(new Ledger.Transaction(0, 1000L, 1, null, 2000));
        // Negative ledger balance shouldn't affect the total circulation value.
        ledger = mScribeUnderTest.getLedgerLocked(TEST_USER_ID + 1, TEST_PACKAGE);
        ledger.recordTransaction(new Ledger.Transaction(0, 1000L, 1, null, -5000));
        mScribeUnderTest.setLastReclamationTimeLocked(lastReclamationTime);
        mScribeUnderTest.writeImmediatelyForTesting();

        mScribeUnderTest.loadFromDiskLocked();

        assertEquals(lastReclamationTime, mScribeUnderTest.getLastReclamationTimeLocked());
        assertEquals(narcsInCirculation, mScribeUnderTest.getNarcsInCirculationLocked());
    }

    @Test
    public void testWritingEmptyLedgerToDisk() {
        final Ledger ogLedger = mScribeUnderTest.getLedgerLocked(TEST_USER_ID, TEST_PACKAGE);
        mScribeUnderTest.writeImmediatelyForTesting();

        mScribeUnderTest.loadFromDiskLocked();
        assertLedgersEqual(ogLedger, mScribeUnderTest.getLedgerLocked(TEST_USER_ID, TEST_PACKAGE));
    }

    @Test
    public void testWritingPopulatedLedgerToDisk() {
        final Ledger ogLedger = mScribeUnderTest.getLedgerLocked(TEST_USER_ID, TEST_PACKAGE);
        ogLedger.recordTransaction(new Ledger.Transaction(0, 1000, 1, null, 51));
        ogLedger.recordTransaction(new Ledger.Transaction(1500, 2000, 2, "green", 52));
        ogLedger.recordTransaction(new Ledger.Transaction(2500, 3000, 3, "blue", 3));
        mScribeUnderTest.writeImmediatelyForTesting();

        mScribeUnderTest.loadFromDiskLocked();
        assertLedgersEqual(ogLedger, mScribeUnderTest.getLedgerLocked(TEST_USER_ID, TEST_PACKAGE));
    }

    @Test
    public void testWritingMultipleLedgersToDisk() {
        final SparseArrayMap<String, Ledger> ledgers = new SparseArrayMap<>();
        final int numUsers = 3;
        final int numLedgers = 5;
        final int[] userIds = new int[numUsers];
        when(mUserManagerInternal.getUserIds()).thenReturn(userIds);
        for (int u = 0; u < numUsers; ++u) {
            final int userId = TEST_USER_ID + u;
            userIds[u] = userId;
            for (int l = 0; l < numLedgers; ++l) {
                final String pkgName = TEST_PACKAGE + l;
                final Ledger ledger = mScribeUnderTest.getLedgerLocked(userId, pkgName);
                ledger.recordTransaction(new Ledger.Transaction(
                        0, 1000L * u + l, 1, null, 51L * u + l));
                ledger.recordTransaction(new Ledger.Transaction(
                        1500L * u + l, 2000L * u + l, 2 * u + l, "green" + u + l, 52L * u + l));
                ledger.recordTransaction(new Ledger.Transaction(
                        2500L * u + l, 3000L * u + l, 3 * u + l, "blue" + u + l, 3L * u + l));
                ledgers.add(userId, pkgName, ledger);
            }
        }
        mScribeUnderTest.writeImmediatelyForTesting();

        mScribeUnderTest.loadFromDiskLocked();
        ledgers.forEach((userId, pkgName, ledger)
                -> assertLedgersEqual(ledger, mScribeUnderTest.getLedgerLocked(userId, pkgName)));
    }

    @Test
    public void testDiscardLedgerFromDisk() {
        final Ledger ogLedger = mScribeUnderTest.getLedgerLocked(TEST_USER_ID, TEST_PACKAGE);
        ogLedger.recordTransaction(new Ledger.Transaction(0, 1000, 1, null, 51));
        ogLedger.recordTransaction(new Ledger.Transaction(1500, 2000, 2, "green", 52));
        ogLedger.recordTransaction(new Ledger.Transaction(2500, 3000, 3, "blue", 3));
        mScribeUnderTest.writeImmediatelyForTesting();

        mScribeUnderTest.loadFromDiskLocked();
        assertLedgersEqual(ogLedger, mScribeUnderTest.getLedgerLocked(TEST_USER_ID, TEST_PACKAGE));

        mScribeUnderTest.discardLedgerLocked(TEST_USER_ID, TEST_PACKAGE);
        mScribeUnderTest.writeImmediatelyForTesting();

        // Make sure there's no more saved ledger.
        mScribeUnderTest.loadFromDiskLocked();
        assertLedgersEqual(new Ledger(),
                mScribeUnderTest.getLedgerLocked(TEST_USER_ID, TEST_PACKAGE));
    }

    private void assertLedgersEqual(Ledger expected, Ledger actual) {
        if (expected == null) {
            assertNull(actual);
            return;
        }
        assertNotNull(actual);
        assertEquals(expected.getCurrentBalance(), actual.getCurrentBalance());
        List<Ledger.Transaction> expectedTransactions = expected.getTransactions();
        List<Ledger.Transaction> actualTransactions = actual.getTransactions();
        assertEquals(expectedTransactions.size(), actualTransactions.size());
        for (int i = 0; i < expectedTransactions.size(); ++i) {
            assertTransactionsEqual(expectedTransactions.get(i), actualTransactions.get(i));
        }
    }

    private void assertTransactionsEqual(Ledger.Transaction expected, Ledger.Transaction actual) {
        if (expected == null) {
            assertNull(actual);
            return;
        }
        assertNotNull(actual);
        assertEquals(expected.startTimeMs, actual.startTimeMs);
        assertEquals(expected.endTimeMs, actual.endTimeMs);
        assertEquals(expected.eventId, actual.eventId);
        assertEquals(expected.tag, actual.tag);
        assertEquals(expected.delta, actual.delta);
    }
}
