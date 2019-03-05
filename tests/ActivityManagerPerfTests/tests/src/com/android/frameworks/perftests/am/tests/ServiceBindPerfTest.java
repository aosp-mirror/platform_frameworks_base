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
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.perftests.am.util.Constants;
import com.android.frameworks.perftests.am.util.TargetPackageUtils;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class ServiceBindPerfTest extends BasePerfTest {
    /**
     * Create and return a ServiceConnection that will add the current time with type
     * Constants.TYPE_SERVICE_CONNECTED.
     */
    private ServiceConnection createServiceConnectionReportTime() {
        return new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                addReceivedTimeNs(Constants.TYPE_SERVICE_CONNECTED);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
            }
        };
    }

    /**
     * Try to bind to the service with the input parameters, throwing a RuntimeException with the
     * errorMessage on failure.
     */
    private void bindService(Intent intent, ServiceConnection serviceConnection, int flags) {
        final boolean success = mContext.bindService(intent, serviceConnection, flags);
        Assert.assertTrue("Could not bind to service", success);
    }

    /**
     * Benchmark time from Context.bindService() to Service.onBind() when target package is not
     * running.
     */
    @Test
    public void bindServiceNotRunning() {
        runPerfFunction(() -> {
            final Intent intent = createServiceIntent();
            final ServiceConnection serviceConnection = createServiceConnectionReportTime();

            final long startTimeNs = System.nanoTime();
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
            try {
                final long endTimeNs = getReceivedTimeNs(Constants.TYPE_SERVICE_CONNECTED);
                return endTimeNs - startTimeNs;
            } finally {
                TargetPackageUtils.unbindFromService(mContext, serviceConnection);
            }
        });
    }

    /**
     * Benchmark time from Context.bindService() to Service.onBind() when target package is running.
     */
    @Test
    public void bindServiceRunning() {
        runPerfFunction(() -> {
            startTargetPackage();

            final Intent intent = createServiceIntent();
            final ServiceConnection serviceConnection = createServiceConnectionReportTime();

            final long startTimeNs = System.nanoTime();
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
            try {
                final long endTimeNs = getReceivedTimeNs(Constants.TYPE_SERVICE_CONNECTED);
                return endTimeNs - startTimeNs;
            } finally {
                TargetPackageUtils.unbindFromService(mContext, serviceConnection);
            }
        });
    }

    /**
     * Benchmark time from Context.bindService() to Service.onBind() when service is already bound
     * to.
     */
    @Test
    public void bindServiceAlreadyBound() {
        runPerfFunction(() -> {
            startTargetPackage();

            final Intent intent = createServiceIntent();
            final ServiceConnection alreadyBoundServiceConnection =
                    TargetPackageUtils.bindAndWaitForConnectedService(mContext, intent);

            try {
                final ServiceConnection serviceConnection = createServiceConnectionReportTime();

                final long startTimeNs = System.nanoTime();
                try {
                    bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
                    final long endTimeNs = getReceivedTimeNs(Constants.TYPE_SERVICE_CONNECTED);
                    return endTimeNs - startTimeNs;
                } finally {
                    TargetPackageUtils.unbindFromService(mContext, serviceConnection);
                }
            } finally {
                TargetPackageUtils.unbindFromService(mContext, alreadyBoundServiceConnection);
            }
        });
    }

    /**
     * Benchmark time from Context.bindService() (without BIND_ALLOW_OOM_MANAGEMENT) to
     * Service.onBind() when service is already bound to with BIND_ALLOW_OOM_MANAGEMENT.
     */
    @Test
    public void bindServiceAllowOomManagement() {
        runPerfFunction(() -> {
            final Intent intentNoOom = createServiceIntent();
            final ServiceConnection serviceConnectionOom =
                    TargetPackageUtils.bindAndWaitForConnectedService(mContext, intentNoOom,
                            Context.BIND_ALLOW_OOM_MANAGEMENT);

            try {
                final ServiceConnection serviceConnectionNoOom =
                        createServiceConnectionReportTime();
                try {
                    final long startTimeNs = System.nanoTime();
                    bindService(intentNoOom, serviceConnectionNoOom, Context.BIND_AUTO_CREATE);
                    final long endTimeNs = getReceivedTimeNs(Constants.TYPE_SERVICE_CONNECTED);

                    return endTimeNs - startTimeNs;
                } finally {
                    TargetPackageUtils.unbindFromService(mContext, serviceConnectionNoOom);
                }
            } finally {
                TargetPackageUtils.unbindFromService(mContext, serviceConnectionOom);
            }
        });
    }
}
