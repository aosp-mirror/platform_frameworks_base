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

import android.content.res.Configuration;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.IWindowManager;
import junit.framework.TestCase;

import static android.view.Display.DEFAULT_DISPLAY;

/**
 * TODO: Remove this. This is only a placeholder, need to implement this.
 */
public class WindowManagerPermissionTests extends TestCase {
    IWindowManager mWm;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mWm = IWindowManager.Stub.asInterface(
                ServiceManager.getService("window"));
    }

    @SmallTest
    public void testMANAGE_APP_TOKENS() {
        try {
            mWm.setEventDispatching(true);
            fail("IWindowManager.setEventDispatching did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }

        try {
            mWm.addWindowToken(null, 0, DEFAULT_DISPLAY);
            fail("IWindowManager.addWindowToken did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }

        try {
            mWm.removeWindowToken(null, DEFAULT_DISPLAY);
            fail("IWindowManager.removeWindowToken did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }

        try {
            mWm.updateOrientationFromAppTokens(new Configuration(),
                    null /* freezeThisOneIfNeeded */, DEFAULT_DISPLAY);
            fail("IWindowManager.updateOrientationFromAppTokens did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }

        try {
            mWm.setFocusedApp(null, false);
            fail("IWindowManager.setFocusedApp did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }

        try {
            mWm.prepareAppTransition(0, false);
            fail("IWindowManager.prepareAppTransition did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }

        try {
            mWm.executeAppTransition();
            fail("IWindowManager.executeAppTransition did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
    }

    @SmallTest
    public void testDISABLE_KEYGUARD() {
        Binder token = new Binder();
        try {
            mWm.disableKeyguard(token, "foo");
            fail("IWindowManager.disableKeyguard did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }

        try {
            mWm.reenableKeyguard(token);
            fail("IWindowManager.reenableKeyguard did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }

        try {
            mWm.exitKeyguardSecurely(null);
            fail("IWindowManager.exitKeyguardSecurely did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
    }

    @SmallTest
    public void testSET_ANIMATION_SCALE() {
        try {
            mWm.setAnimationScale(0, 1);
            fail("IWindowManager.setAnimationScale did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }

        try {
            mWm.setAnimationScales(new float[1]);
            fail("IWindowManager.setAnimationScales did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
    }

    @SmallTest
    public void testSET_ORIENTATION() {
        try {
            mWm.updateRotation(true, false);
            fail("IWindowManager.updateRotation did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }

        try {
            mWm.freezeRotation(-1);
            fail("IWindowManager.freezeRotation did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }

        try {
            mWm.thawRotation();
            fail("IWindowManager.thawRotation did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
    }
}
