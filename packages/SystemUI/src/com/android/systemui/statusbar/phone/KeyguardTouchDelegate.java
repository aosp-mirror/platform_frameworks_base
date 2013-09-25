/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.MotionEvent;

import com.android.internal.policy.IKeyguardExitCallback;
import com.android.internal.policy.IKeyguardShowCallback;
import com.android.internal.policy.IKeyguardService;


/**
 * Facilitates event communication between navigation bar and keyguard.  Currently used to
 * control WidgetPager in keyguard to expose the camera widget.
 *
 */
public class KeyguardTouchDelegate {
    // TODO: propagate changes to these to {@link KeyguardServiceDelegate}
    static final String KEYGUARD_PACKAGE = "com.android.keyguard";
    static final String KEYGUARD_CLASS = "com.android.keyguard.KeyguardService";

    IKeyguardService mService;

    protected static final boolean DEBUG = false;
    protected static final String TAG = "KeyguardTouchDelegate";

    private final ServiceConnection mKeyguardConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.v(TAG, "Connected to keyguard");
            mService = IKeyguardService.Stub.asInterface(service);

        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.v(TAG, "Disconnected from keyguard");
            mService = null;
        }

    };

    public KeyguardTouchDelegate(Context context) {
        Intent intent = new Intent();
        intent.setClassName(KEYGUARD_PACKAGE, KEYGUARD_CLASS);
        if (!context.bindServiceAsUser(intent, mKeyguardConnection,
                Context.BIND_AUTO_CREATE, UserHandle.OWNER)) {
            if (DEBUG) Log.v(TAG, "*** Keyguard: can't bind to " + KEYGUARD_CLASS);
        } else {
            if (DEBUG) Log.v(TAG, "*** Keyguard started");
        }
    }

    public boolean isSecure() {
        boolean secure = false;
        if (mService != null) {
            try {
                secure = mService.isSecure();
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException calling keyguard.isSecure()!", e);
            }
        } else {
            Log.w(TAG, "isSecure(): NO SERVICE!");
        }
        return secure;
    }

    public boolean dispatch(MotionEvent event) {
        if (mService != null) {
            try {
                mService.dispatch(event);
            } catch (RemoteException e) {
                // What to do?
                Log.e(TAG, "RemoteException sending event to keyguard!", e);
                return false;
            }
            return true;
        } else {
            Log.w(TAG, "dispatch(event): NO SERVICE!");
        }
        return false;
    }

    public void showAssistant() {
        if (mService != null) {
            try {
                mService.showAssistant();
            } catch (RemoteException e) {
                // What to do?
                Log.e(TAG, "RemoteException launching assistant!", e);
            }
        } else {
            Log.w(TAG, "dispatch(event): NO SERVICE!");
        }
    }

    public void launchCamera() {
        if (mService != null) {
            try {
                mService.launchCamera();
            } catch (RemoteException e) {
                // What to do?
                Log.e(TAG, "RemoteException launching camera!", e);
            }
        } else {
            Log.w(TAG, "dispatch(event): NO SERVICE!");
        }
    }

}
