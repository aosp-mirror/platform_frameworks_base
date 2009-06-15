package com.android.framework.permission.tests;

import com.android.internal.os.BinderInternal;

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
                public boolean checkPermission(java.lang.String permission, int pid, int uid) {
                    return true;
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
