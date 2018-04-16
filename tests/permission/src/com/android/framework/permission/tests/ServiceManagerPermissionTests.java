/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.framework.permission.tests;

import com.android.internal.os.BinderInternal;

import android.app.AppOpsManager;
import android.os.Binder;
import android.os.IPermissionController;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManagerNative;
import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

/**
 * TODO: Remove this. This is only a placeholder, need to implement this.
 */
public class ServiceManagerPermissionTests extends TestCase {
    @SmallTest
    public void testAddService() {
        try {
            // The security in the service manager is that you can't replace
            // a service that is already published.
            Binder binder = new Binder();
            ServiceManager.addService("activity", binder);
            fail("ServiceManager.addService did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        }
    }

    @SmallTest
    public void testSetPermissionController() {
        try {
            IPermissionController pc = new IPermissionController.Stub() {
                @Override
                public boolean checkPermission(java.lang.String permission, int pid, int uid) {
                    return true;
                }

                @Override
                public int noteOp(String op, int uid, String packageName) {
                    return AppOpsManager.MODE_ALLOWED;
                }

                @Override
                public String[] getPackagesForUid(int uid) {
                    return new String[0];
                }

                @Override
                public boolean isRuntimePermission(String permission) {
                    return false;
                }

                @Override
                public int getPackageUid(String packageName, int flags) {
                    return -1;
                }
            };
            ServiceManagerNative.asInterface(BinderInternal.getContextObject())
                    .setPermissionController(pc);
            fail("IServiceManager.setPermissionController did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
    }
}
