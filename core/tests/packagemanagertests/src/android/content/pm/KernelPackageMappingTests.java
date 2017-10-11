/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.content.pm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.os.FileUtils;
import android.os.Process;
import android.os.ServiceManager;
import android.os.UserManager;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;

/**
 * This test needs to be run without any secondary users on the device,
 * and selinux needs to be disabled with "adb shell setenforce 0".
 */
@RunWith(AndroidJUnit4.class)
public class KernelPackageMappingTests {

    private static final String TAG = "KernelPackageMapping";
    private static final String SDCARDFS_PATH = "/config/sdcardfs";

    private UserInfo mSecondaryUser;

    private static File getKernelPackageDir(String packageName) {
        return new File(new File(SDCARDFS_PATH), packageName);
    }

    private static File getKernelPackageFile(String packageName, String filename) {
        return new File(getKernelPackageDir(packageName), filename);
    }

    private UserManager getUserManager() {
        UserManager um = (UserManager) InstrumentationRegistry.getContext().getSystemService(
                Context.USER_SERVICE);
        return um;
    }

    private IPackageManager getIPackageManager() {
        IPackageManager ipm = IPackageManager.Stub.asInterface(
                ServiceManager.getService("package"));
        return ipm;
    }

    private static String getContent(File file) {
        try {
            return FileUtils.readTextFile(file, 0, null).trim();
        } catch (IOException ioe) {
            Log.w(TAG, "Couldn't read file " + file.getAbsolutePath() + "\n" + ioe);
            return "<error>";
        }
    }

    @Test
    public void testInstalledPrimary() throws Exception {
        assertEquals("1000", getContent(getKernelPackageFile("com.android.settings", "appid")));
    }

    @Test
    public void testInstalledAll() throws Exception {
        assertEquals("", getContent(getKernelPackageFile("com.android.settings",
                "excluded_userids")));
    }

    @Test
    public void testNotInstalledSecondary() throws Exception {
        mSecondaryUser = getUserManager().createUser("Secondary", 0);
        assertEquals(Integer.toString(mSecondaryUser.id),
                getContent(
                        getKernelPackageFile("com.android.frameworks.coretests.packagemanager",
                                "excluded_userids")));
    }

    @After
    public void shutDown() throws Exception {
        if (mSecondaryUser != null) {
            getUserManager().removeUser(mSecondaryUser.id);
        }
    }
}
