/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.frameworks.perftests.am.tests;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.perftests.am.util.Constants;
import com.android.frameworks.perftests.am.util.TargetPackageUtils;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class ServiceStartPerfTest extends BasePerfTest {

    /**
     * Tries to start the service with the given intent, throwing a RuntimeException with the
     * errorMessage on failure.
     */
    private void startService(Intent intent) {
        final ComponentName componentName = mContext.startService(intent);
        Assert.assertNotNull("Could not start service", componentName);
    }

    /**
     * Benchmark time from Context.startService() to Service.onStartCommand() when target process is
     * not running.
     */
    @Test
    public void startServiceNotRunning() {
        runPerfFunction(() -> {
            final Intent intent = createServiceIntent();

            final long startTimeNs = System.nanoTime();

            startService(intent);

            final long endTimeNs = getReceivedTimeNs(Constants.TYPE_SERVICE_START);
            return endTimeNs - startTimeNs;
        });
    }

    /**
     * Benchmark time from Context.startService() to Service.onStartCommand() when target process is
     * running.
     */
    @Test
    public void startServiceProcessRunning() {
        runPerfFunction(() -> {
            startTargetPackage();

            final Intent intent = createServiceIntent();

            final long startTimeNs = System.nanoTime();
            startService(intent);
            final long endTimeNs = getReceivedTimeNs(Constants.TYPE_SERVICE_START);

            return endTimeNs - startTimeNs;
        });
    }

    /**
     * Benchmark time from Context.startService() to Service.onStartCommand() when service is
     * already bound to.
     */
    @Test
    public void startServiceAlreadyBound() {
        runPerfFunction(() -> {
            final ServiceConnection alreadyBoundServiceConnection =
                    TargetPackageUtils.bindAndWaitForConnectedService(mContext,
                            createServiceIntent());
            try {
                final Intent intent = createServiceIntent();

                final long startTimeNs = System.nanoTime();
                startService(intent);
                final long endTimeNs = getReceivedTimeNs(Constants.TYPE_SERVICE_START);

                return endTimeNs - startTimeNs;
            } finally {
                TargetPackageUtils.unbindFromService(mContext, alreadyBoundServiceConnection);
            }
        });
    }

    /**
     * Benchmark time from Context.startService() with FLAG_GRANT_READ_URI_PERMISSION to
     * Service.onStartCommand() when target service is already running.
     */
    @Test
    public void startServiceProcessRunningReadUriPermission() {
        runPerfFunction(() -> {
            final ServiceConnection alreadyBoundServiceConnection =
                    TargetPackageUtils.bindAndWaitForConnectedService(mContext,
                            createServiceIntent());
            try {
                final Intent intent = createServiceIntent();
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                final long startTimeNs = System.nanoTime();
                startService(intent);
                final long endTimeNs = getReceivedTimeNs(Constants.TYPE_SERVICE_START);

                return endTimeNs - startTimeNs;
            } finally {
                TargetPackageUtils.unbindFromService(mContext, alreadyBoundServiceConnection);
            }
        });
    }
}
