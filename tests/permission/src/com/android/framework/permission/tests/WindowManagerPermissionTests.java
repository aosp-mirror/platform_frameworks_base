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
import android.view.KeyEvent;
import android.view.MotionEvent;

import junit.framework.TestCase;

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
            mWm.pauseKeyDispatching(null);
            fail("IWindowManager.pauseKeyDispatching did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
        
        try {
            mWm.resumeKeyDispatching(null);
            fail("IWindowManager.resumeKeyDispatching did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
        
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
            mWm.addWindowToken(null, 0);
            fail("IWindowManager.addWindowToken did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
        
        try {
            mWm.removeWindowToken(null);
            fail("IWindowManager.removeWindowToken did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
        
        try {
            mWm.addAppToken(0, 0, null, 0, 0, false, false);
            fail("IWindowManager.addAppToken did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
        
        try {
            mWm.setAppGroupId(null, 0);
            fail("IWindowManager.setAppGroupId did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
        
        try {
            mWm.updateOrientationFromAppTokens(new Configuration(), null);
            fail("IWindowManager.updateOrientationFromAppTokens did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
        
        try {
            mWm.setAppOrientation(null, 0);
            mWm.addWindowToken(null, 0);
            fail("IWindowManager.setAppOrientation did not throw SecurityException as"
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
        
        try {
            mWm.setAppStartingWindow(null, "foo", 0, null, null, 0, 0, 0, null, false);
            fail("IWindowManager.setAppStartingWindow did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
        
        try {
            mWm.setAppWillBeHidden(null);
            fail("IWindowManager.setAppWillBeHidden did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
        
        try {
            mWm.setAppVisibility(null, false);
            fail("IWindowManager.setAppVisibility did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
        
        try {
            mWm.startAppFreezingScreen(null, 0);
            fail("IWindowManager.startAppFreezingScreen did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
        
        try {
            mWm.stopAppFreezingScreen(null, false);
            fail("IWindowManager.stopAppFreezingScreen did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
        
        try {
            mWm.removeAppToken(null);
            fail("IWindowManager.removeAppToken did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
        
        try {
            mWm.moveAppToken(0, null);
            fail("IWindowManager.moveAppToken did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
        
        try {
            mWm.moveAppTokensToTop(null);
            fail("IWindowManager.moveAppTokensToTop did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
        
        try {
            mWm.moveAppTokensToBottom(null);
            fail("IWindowManager.moveAppTokensToBottom did not throw SecurityException as"
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
