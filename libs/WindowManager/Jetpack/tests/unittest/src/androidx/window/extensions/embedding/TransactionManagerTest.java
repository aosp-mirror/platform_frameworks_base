/*
 * Copyright (C) 2022 The Android Open Source Project
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

package androidx.window.extensions.embedding;

import static android.window.TaskFragmentOrganizer.TASK_FRAGMENT_TRANSIT_CHANGE;
import static android.window.TaskFragmentOrganizer.TASK_FRAGMENT_TRANSIT_CLOSE;
import static android.window.TaskFragmentOrganizer.TASK_FRAGMENT_TRANSIT_OPEN;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verifyNoMoreInteractions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.clearInvocations;

import android.os.Binder;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.window.TaskFragmentOrganizer;
import android.window.WindowContainerTransaction;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.window.extensions.embedding.TransactionManager.TransactionRecord;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Test class for {@link TransactionManager}.
 *
 * Build/Install/Run:
 *  atest WMJetpackUnitTests:TransactionManagerTest
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class TransactionManagerTest {
    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    @Mock
    private TaskFragmentOrganizer mOrganizer;
    private TransactionManager mTransactionManager;

    @Before
    public void setup() {
        mTransactionManager = new TransactionManager(mOrganizer);
    }

    @Test
    public void testStartNewTransaction() {
        mTransactionManager.startNewTransaction();

        // Throw exception if #startNewTransaction is called twice without #apply() or #abort().
        assertThrows(IllegalStateException.class, mTransactionManager::startNewTransaction);

        // Allow to start new after #apply() the last transaction.
        TransactionRecord transactionRecord = mTransactionManager.startNewTransaction();
        transactionRecord.apply(false /* shouldApplyIndependently */);
        transactionRecord = mTransactionManager.startNewTransaction();

        // Allow to start new after #abort() the last transaction.
        transactionRecord.abort();
        mTransactionManager.startNewTransaction();
    }

    @Test
    public void testSetTransactionOriginType() {
        // Return TASK_FRAGMENT_TRANSIT_CHANGE if there is no trigger type set.
        TransactionRecord transactionRecord = mTransactionManager.startNewTransaction();

        assertEquals(TASK_FRAGMENT_TRANSIT_CHANGE,
                transactionRecord.getTransactionTransitionType());

        // Return the first set type.
        mTransactionManager.getCurrentTransactionRecord().abort();
        transactionRecord = mTransactionManager.startNewTransaction();
        transactionRecord.setOriginType(TASK_FRAGMENT_TRANSIT_OPEN);

        assertEquals(TASK_FRAGMENT_TRANSIT_OPEN, transactionRecord.getTransactionTransitionType());

        transactionRecord.setOriginType(TASK_FRAGMENT_TRANSIT_CLOSE);

        assertEquals(TASK_FRAGMENT_TRANSIT_OPEN, transactionRecord.getTransactionTransitionType());

        // Reset when #startNewTransaction().
        transactionRecord.abort();
        transactionRecord = mTransactionManager.startNewTransaction();

        assertEquals(TASK_FRAGMENT_TRANSIT_CHANGE,
                transactionRecord.getTransactionTransitionType());
    }

    @Test
    public void testGetCurrentTransactionRecord() {
        // Throw exception if #getTransaction is called without calling #startNewTransaction().
        assertThrows(IllegalStateException.class, mTransactionManager::getCurrentTransactionRecord);

        TransactionRecord transactionRecord = mTransactionManager.startNewTransaction();
        assertNotNull(transactionRecord);

        // Same WindowContainerTransaction should be returned.
        assertSame(transactionRecord, mTransactionManager.getCurrentTransactionRecord());

        // Reset after #abort().
        transactionRecord.abort();
        assertThrows(IllegalStateException.class, mTransactionManager::getCurrentTransactionRecord);

        // New WindowContainerTransaction after #startNewTransaction().
        mTransactionManager.startNewTransaction();
        assertNotEquals(transactionRecord, mTransactionManager.getCurrentTransactionRecord());

        // Reset after #apply().
        mTransactionManager.getCurrentTransactionRecord().apply(
                false /* shouldApplyIndependently */);
        assertThrows(IllegalStateException.class, mTransactionManager::getCurrentTransactionRecord);
    }

    @Test
    public void testApply() {
        // #applyTransaction(false)
        TransactionRecord transactionRecord = mTransactionManager.startNewTransaction();
        int transitionType = transactionRecord.getTransactionTransitionType();
        WindowContainerTransaction wct = transactionRecord.getTransaction();
        transactionRecord.apply(false /* shouldApplyIndependently */);

        verify(mOrganizer).applyTransaction(wct, transitionType,
                false /* shouldApplyIndependently */);

        // #applyTransaction(true)
        clearInvocations(mOrganizer);
        transactionRecord = mTransactionManager.startNewTransaction();
        transitionType = transactionRecord.getTransactionTransitionType();
        wct = transactionRecord.getTransaction();
        transactionRecord.apply(true /* shouldApplyIndependently */);

        verify(mOrganizer).applyTransaction(wct, transitionType,
                true /* shouldApplyIndependently */);

        // #onTransactionHandled(false)
        clearInvocations(mOrganizer);
        IBinder token = new Binder();
        transactionRecord = mTransactionManager.startNewTransaction(token);
        transitionType = transactionRecord.getTransactionTransitionType();
        wct = transactionRecord.getTransaction();
        transactionRecord.apply(false /* shouldApplyIndependently */);

        verify(mOrganizer).onTransactionHandled(token, wct, transitionType,
                false /* shouldApplyIndependently */);

        // #onTransactionHandled(true)
        clearInvocations(mOrganizer);
        token = new Binder();
        transactionRecord = mTransactionManager.startNewTransaction(token);
        transitionType = transactionRecord.getTransactionTransitionType();
        wct = transactionRecord.getTransaction();
        transactionRecord.apply(true /* shouldApplyIndependently */);

        verify(mOrganizer).onTransactionHandled(token, wct, transitionType,
                true /* shouldApplyIndependently */);

        // Throw exception if there is any more interaction.
        final TransactionRecord record = transactionRecord;
        assertThrows(IllegalStateException.class,
                () -> record.apply(false /* shouldApplyIndependently */));
        assertThrows(IllegalStateException.class,
                () -> record.apply(true /* shouldApplyIndependently */));
        assertThrows(IllegalStateException.class,
                record::abort);
    }

    @Test
    public void testAbort() {
        final TransactionRecord transactionRecord = mTransactionManager.startNewTransaction();
        transactionRecord.abort();

        // Throw exception if there is any more interaction.
        verifyNoMoreInteractions(mOrganizer);
        assertThrows(IllegalStateException.class,
                () -> transactionRecord.apply(false /* shouldApplyIndependently */));
        assertThrows(IllegalStateException.class,
                () -> transactionRecord.apply(true /* shouldApplyIndependently */));
        assertThrows(IllegalStateException.class,
                transactionRecord::abort);
    }
}
