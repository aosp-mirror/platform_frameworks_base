/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.unit_tests;

import android.os.storage.IMountService.Stub;

import android.net.Uri;
import android.os.FileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.Suppress;
import android.util.DisplayMetrics;
import android.util.Log;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.storage.IMountService;
import android.os.storage.StorageResultCode;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StatFs;
import android.provider.Settings;
import junit.framework.Assert;

public class AsecTests extends AndroidTestCase {
    private static final boolean localLOGV = true;
    public static final String TAG="AsecTests";

    void failStr(String errMsg) {
        Log.w(TAG, "errMsg="+errMsg);
    }
    void failStr(Exception e) {
        Log.w(TAG, "e.getMessage="+e.getMessage());
        Log.w(TAG, "e="+e);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        if (localLOGV) Log.i(TAG, "Cleaning out old test containers");
        cleanupContainers();
    }

    private void cleanupContainers() throws RemoteException {
        IMountService ms = getMs();
        String[] containers = ms.getSecureContainerList();

        for (int i = 0; i < containers.length; i++) {
            if (containers[i].startsWith("com.android.unittests.AsecTests.")) {
                ms.destroySecureContainer(containers[i]);
            }
        }
    }

    private IMountService getMs() {
        IBinder service = ServiceManager.getService("mount");
        if (service != null) {
            return IMountService.Stub.asInterface(service);
        } else {
            Log.e(TAG, "Can't get mount service");
        }
        return null;
    }

    private boolean isMediaMounted() {
        try {
        String mPath = Environment.getExternalStorageDirectory().toString();
        String state = getMs().getVolumeState(mPath);
        return Environment.MEDIA_MOUNTED.equals(state);
        } catch (RemoteException e) {
            failStr(e);
            return false;
        }
    }

    public void testCreateContainer() {
        Assert.assertTrue(isMediaMounted());
        IMountService ms = getMs();
        try {
            int rc = ms.createSecureContainer("com.android.unittests.AsecTests.testCreateContainer", 4, "fat", "none", 1000);
            Assert.assertEquals(StorageResultCode.OperationSucceeded, rc);
        } catch (Exception e) {
            failStr(e);
        }
    }

    public void testDestroyContainer() {
        Assert.assertTrue(isMediaMounted());
        IMountService ms = getMs();
        try {
            int rc = ms.createSecureContainer("com.android.unittests.AsecTests.testDestroyContainer", 4, "fat", "none", 1000);
            Assert.assertEquals(StorageResultCode.OperationSucceeded, rc);
            rc = ms.destroySecureContainer("com.android.unittests.AsecTests.testDestroyContainer");
            Assert.assertEquals(StorageResultCode.OperationSucceeded, rc);
        } catch (Exception e) {
            failStr(e);
        }
    }

    public void testMountContainer() {
        Assert.assertTrue(isMediaMounted());
        IMountService ms = getMs();
        try {
            int rc = ms.createSecureContainer(
                    "com.android.unittests.AsecTests.testMountContainer", 4, "fat", "none", 1000);
            Assert.assertEquals(StorageResultCode.OperationSucceeded, rc);

            rc = ms.unmountSecureContainer("com.android.unittests.AsecTests.testMountContainer");
            Assert.assertEquals(StorageResultCode.OperationSucceeded, rc);

            rc = ms.mountSecureContainer("com.android.unittests.AsecTests.testMountContainer", "none", 1000);
            Assert.assertEquals(StorageResultCode.OperationSucceeded, rc);
        } catch (Exception e) {
            failStr(e);
        }
    }

    public void testMountBadKey() {
        Assert.assertTrue(isMediaMounted());
        IMountService ms = getMs();
        try {
            int rc = ms.createSecureContainer(
                    "com.android.unittests.AsecTests.testMountBadKey", 4, "fat",
                            "00000000000000000000000000000000", 1000);
            Assert.assertEquals(StorageResultCode.OperationSucceeded, rc);

            rc = ms.unmountSecureContainer("com.android.unittests.AsecTests.testMountBadKey");
            Assert.assertEquals(StorageResultCode.OperationSucceeded, rc);

            rc = ms.mountSecureContainer(
                    "com.android.unittests.AsecTests.testMountBadKey",
                            "00000000000000000000000000000001", 1001);
            Assert.assertEquals(StorageResultCode.OperationFailedInternalError, rc);

            rc = ms.mountSecureContainer(
                    "com.android.unittests.AsecTests.testMountBadKey", "none", 1001);
            Assert.assertEquals(StorageResultCode.OperationFailedInternalError, rc);
        } catch (Exception e) {
            failStr(e);
        }
    }
}
