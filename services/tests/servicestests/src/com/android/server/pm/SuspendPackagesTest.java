/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.BaseBundle;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.servicestests.apps.suspendtestapp.SuspendTestReceiver;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class SuspendPackagesTest {
    private static final String TEST_APP_PACKAGE_NAME = SuspendTestReceiver.PACKAGE_NAME;
    private static final String[] PACKAGES_TO_SUSPEND = new String[]{TEST_APP_PACKAGE_NAME};

    private Context mContext;
    private PackageManager mPackageManager;
    private Handler mReceiverHandler;
    private ComponentName mTestReceiverComponent;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        mPackageManager = mContext.getPackageManager();
        mPackageManager.setPackagesSuspended(PACKAGES_TO_SUSPEND, false, null, null, null);
        mReceiverHandler = new Handler(Looper.getMainLooper());
        mTestReceiverComponent = new ComponentName(TEST_APP_PACKAGE_NAME,
                SuspendTestReceiver.class.getCanonicalName());
    }

    private Bundle requestAppAction(String action) throws InterruptedException {
        final AtomicReference<Bundle> result = new AtomicReference<>();
        final CountDownLatch receiverLatch = new CountDownLatch(1);

        final Intent broadcastIntent = new Intent(action)
                .setComponent(mTestReceiverComponent)
                .setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        mContext.sendOrderedBroadcast(broadcastIntent, null, new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                result.set(getResultExtras(true));
                receiverLatch.countDown();
            }
        }, mReceiverHandler, 0, null, null);

        assertTrue("Test receiver timed out ", receiverLatch.await(5, TimeUnit.SECONDS));
        return result.get();
    }

    private PersistableBundle getExtras(String keyPrefix, long lval, String sval, double dval) {
        final PersistableBundle extras = new PersistableBundle(3);
        extras.putLong(keyPrefix + ".LONG_VALUE", lval);
        extras.putDouble(keyPrefix + ".DOUBLE_VALUE", dval);
        extras.putString(keyPrefix + ".STRING_VALUE", sval);
        return extras;
    }

    private void suspendTestPackage(PersistableBundle appExtras, PersistableBundle launcherExtras) {
        final String[] unchangedPackages = mPackageManager.setPackagesSuspended(
                PACKAGES_TO_SUSPEND, true, appExtras, launcherExtras, null);
        assertTrue("setPackagesSuspended returned non-empty list", unchangedPackages.length == 0);
    }

    @Test
    public void testIsPackageSuspended() {
        suspendTestPackage(null, null);
        assertTrue("isPackageSuspended is false",
                mPackageManager.isPackageSuspended(TEST_APP_PACKAGE_NAME));
    }

    @Test
    public void testSuspendedStateFromApp() throws Exception {
        Bundle resultFromApp = requestAppAction(SuspendTestReceiver.ACTION_GET_SUSPENDED_STATE);
        assertFalse(resultFromApp.getBoolean(SuspendTestReceiver.EXTRA_SUSPENDED, true));
        assertNull(resultFromApp.getParcelable(SuspendTestReceiver.EXTRA_SUSPENDED_APP_EXTRAS));

        final PersistableBundle appExtras = getExtras("appExtras", 20, "20", 0.2);
        suspendTestPackage(appExtras, null);

        resultFromApp = requestAppAction(SuspendTestReceiver.ACTION_GET_SUSPENDED_STATE);
        assertTrue("resultFromApp:suspended is false",
                resultFromApp.getBoolean(SuspendTestReceiver.EXTRA_SUSPENDED));
        final PersistableBundle receivedAppExtras =
                resultFromApp.getParcelable(SuspendTestReceiver.EXTRA_SUSPENDED_APP_EXTRAS);
        receivedAppExtras.get(""); // hack to unparcel the bundles
        appExtras.get("");
        assertTrue("Received app extras " + receivedAppExtras + " different to the ones supplied",
                BaseBundle.kindofEquals(appExtras, receivedAppExtras));
    }
}
