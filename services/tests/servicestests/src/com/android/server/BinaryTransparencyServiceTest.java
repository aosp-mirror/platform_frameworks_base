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

package com.android.server;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import android.app.job.JobScheduler;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SystemProperties;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileDescriptor;
import java.util.HashMap;
import java.util.Map;

@RunWith(AndroidJUnit4.class)
public class BinaryTransparencyServiceTest {
    private Context mContext;
    private BinaryTransparencyService mBinaryTransparencyService;
    private BinaryTransparencyService.BinaryTransparencyServiceImpl mTestInterface;

    @Before
    public void setUp() {
        mContext = spy(ApplicationProvider.getApplicationContext());
        mBinaryTransparencyService = new BinaryTransparencyService(mContext);
        mTestInterface = mBinaryTransparencyService.new BinaryTransparencyServiceImpl();
    }

    private void prepSignedInfo() {
        // simulate what happens on boot completed phase
        // but we avoid calling JobScheduler.schedule by returning a null.
        doReturn(null).when(mContext).getSystemService(JobScheduler.class);
        mBinaryTransparencyService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
    }

    private void prepApexInfo() throws RemoteException {
        // simulates what happens to apex info after computations are done.
        String[] args = {"get", "apex_info"};
        mTestInterface.onShellCommand(FileDescriptor.in, FileDescriptor.out, FileDescriptor.err,
                args, null, new ResultReceiver(null));
    }

    @Test
    public void getSignedImageInfo_preInitialize_returnsUninitializedString() {
        String result = mTestInterface.getSignedImageInfo();
        Assert.assertNotNull("VBMeta digest value should not be null", result);
        Assert.assertEquals(BinaryTransparencyService.VBMETA_DIGEST_UNINITIALIZED, result);
    }

    @Test
    public void getSignedImageInfo_postInitialize_returnsNonErrorStrings() {
        prepSignedInfo();
        String result = mTestInterface.getSignedImageInfo();
        Assert.assertNotNull("Initialized VBMeta digest string should not be null", result);
        Assert.assertNotEquals("VBMeta digest value is uninitialized",
                BinaryTransparencyService.VBMETA_DIGEST_UNINITIALIZED, result);
        Assert.assertNotEquals("VBMeta value should not be unavailable",
                BinaryTransparencyService.VBMETA_DIGEST_UNAVAILABLE, result);
    }

    @Test
    public void getSignedImageInfo_postInitialize_returnsCorrectValue() {
        prepSignedInfo();
        String result = mTestInterface.getSignedImageInfo();
        Assert.assertEquals(
                SystemProperties.get(BinaryTransparencyService.SYSPROP_NAME_VBETA_DIGEST,
                        BinaryTransparencyService.VBMETA_DIGEST_UNAVAILABLE), result);
    }

    @Test
    public void getApexInfo_postInitialize_returnsValidEntries() throws RemoteException {
        prepApexInfo();
        Map result = mTestInterface.getApexInfo();
        Assert.assertNotNull("Apex info map should not be null", result);
        Assert.assertFalse("Apex info map should not be empty", result.isEmpty());
    }

    @Test
    public void getApexInfo_postInitialize_returnsActualApexs()
            throws RemoteException, PackageManager.NameNotFoundException {
        prepApexInfo();
        Map result = mTestInterface.getApexInfo();

        PackageManager pm = mContext.getPackageManager();
        Assert.assertNotNull(pm);
        HashMap<PackageInfo, String> castedResult = (HashMap<PackageInfo, String>) result;
        for (PackageInfo packageInfo : castedResult.keySet()) {
            Assert.assertTrue(packageInfo.packageName + "is not an APEX!", packageInfo.isApex);
        }
    }

}
