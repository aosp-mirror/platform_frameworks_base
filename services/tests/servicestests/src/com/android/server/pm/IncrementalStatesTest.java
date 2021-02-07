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

package com.android.server.pm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.PackageManager;
import android.os.ConditionVariable;
import android.os.incremental.IStorageHealthListener;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for {@link IncrementalStates}.
 * Run with: atest -c FrameworksServicesTests:com.android.server.pm.IncrementalStatesTest
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
@MediumTest
public class IncrementalStatesTest {
    private IncrementalStates mIncrementalStates;
    private ConditionVariable mUnstartableCalled = new ConditionVariable();
    private ConditionVariable mFullyLoadedCalled = new ConditionVariable();
    private AtomicInteger mUnstartableReason = new AtomicInteger(0);
    private static final int WAIT_TIMEOUT_MILLIS = 1000; /* 1 second */
    private IncrementalStates.Callback mCallback = new IncrementalStates.Callback() {
        @Override
        public void onPackageUnstartable(int reason) {
            mUnstartableCalled.open();
            mUnstartableReason.set(reason);
        }

        @Override
        public void onPackageStartable() {
        }

        @Override
        public void onPackageFullyLoaded() {
            mFullyLoadedCalled.open();
        }
    };

    /**
     * Setup the tests as if the package has just been committed.
     * By default the package is now startable and is loading.
     */
    @Before
    public void setUp() {
        mIncrementalStates = new IncrementalStates();
        assertFalse(mIncrementalStates.getIncrementalStatesInfo().isStartable());
        mIncrementalStates.setCallback(mCallback);
        mIncrementalStates.onCommit(true);
        // Test that package is now startable and loading
        assertTrue(mIncrementalStates.getIncrementalStatesInfo().isStartable());
        assertTrue(mIncrementalStates.getIncrementalStatesInfo().isLoading());
        mUnstartableCalled.close();
        mFullyLoadedCalled.close();
    }

    /**
     * Test that the package is still startable when Incremental Storage is unhealthy.
     */
    @Test
    public void testStartableTransition_IncrementalStorageUnhealthy() {
        mIncrementalStates.onStorageHealthStatusChanged(
                IStorageHealthListener.HEALTH_STATUS_UNHEALTHY);
        // Test that package is still startable
        assertFalse(mUnstartableCalled.block(WAIT_TIMEOUT_MILLIS));
        assertTrue(mIncrementalStates.getIncrementalStatesInfo().isStartable());
        assertEquals(PackageManager.UNSTARTABLE_REASON_UNKNOWN, mUnstartableReason.get());
    }

    /**
     * Test that the package is still startable when Incremental Storage has pending reads.
     */
    @Test
    public void testStartableTransition_IncrementalStorageReadsPending()
            throws InterruptedException {
        mIncrementalStates.onStorageHealthStatusChanged(
                IStorageHealthListener.HEALTH_STATUS_READS_PENDING);
        // Test that package is still startable
        assertFalse(mUnstartableCalled.block(WAIT_TIMEOUT_MILLIS));
        assertTrue(mIncrementalStates.getIncrementalStatesInfo().isStartable());
    }

    /**
     * Test that the package is still startable when health status indicate storage issues.
     */
    @Test
    public void testStartableTransition_IncrementalStorageBlocked() {
        mIncrementalStates.onStorageHealthStatusChanged(
                IStorageHealthListener.HEALTH_STATUS_UNHEALTHY_STORAGE);
        // Test that package is still startable
        assertFalse(mUnstartableCalled.block(WAIT_TIMEOUT_MILLIS));
        assertTrue(mIncrementalStates.getIncrementalStatesInfo().isStartable());
        assertEquals(PackageManager.UNSTARTABLE_REASON_UNKNOWN,
                mUnstartableReason.get());
    }

    /**
     * Test that the package is still startable when health status indicates transport issues.
     */
    @Test
    public void testStartableTransition_DataLoaderIntegrityError() {
        mIncrementalStates.onStorageHealthStatusChanged(
                IStorageHealthListener.HEALTH_STATUS_UNHEALTHY_TRANSPORT);
        // Test that package is still startable
        assertFalse(mUnstartableCalled.block(WAIT_TIMEOUT_MILLIS));
        assertTrue(mIncrementalStates.getIncrementalStatesInfo().isStartable());
        assertEquals(PackageManager.UNSTARTABLE_REASON_UNKNOWN,
                mUnstartableReason.get());
    }

    /**
     * Test that when loading progress is 1, the package becomes fully loaded, and the change of
     * Incremental Storage health status does not affect the startable state.
     */
    @Test
    public void testStartableTransition_HealthStatusChangeWhenFullyLoaded()
            throws InterruptedException {
        mIncrementalStates.setProgress(1.0f);
        // Test that package is now fully loaded
        assertTrue(mFullyLoadedCalled.block(WAIT_TIMEOUT_MILLIS));
        assertFalse(mIncrementalStates.getIncrementalStatesInfo().isLoading());
        mIncrementalStates.onStorageHealthStatusChanged(
                IStorageHealthListener.HEALTH_STATUS_UNHEALTHY);
        // Test that package is still startable
        assertFalse(mUnstartableCalled.block(WAIT_TIMEOUT_MILLIS));
        assertTrue(mIncrementalStates.getIncrementalStatesInfo().isStartable());
    }

    /**
     * Test startability transitions if app crashes or anrs
     */
    @Test
    public void testStartableTransition_AppCrashOrAnr() {
        mIncrementalStates.onCrashOrAnr();
        assertTrue(mIncrementalStates.getIncrementalStatesInfo().isStartable());
    }
}
