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
import android.util.Slog;
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

    private static KeyguardTouchDelegate sInstance;

    private volatile IKeyguardService mService;

    protected static final boolean DEBUG = false;
    protected static final String TAG = "KeyguardTouchDelegate";

    private final ServiceConnection mKeyguardConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Slog.v(TAG, "Connected to keyguard");
            mService = IKeyguardService.Stub.asInterface(service);

        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Slog.v(TAG, "Disconnected from keyguard");
            mService = null;
            sInstance = null; // force reconnection if this goes away
        }

    };

    private KeyguardTouchDelegate(Context context) {
        Intent intent = new Intent();
        intent.setClassName(KEYGUARD_PACKAGE, KEYGUARD_CLASS);
        if (!context.bindServiceAsUser(intent, mKeyguardConnection,
                Context.BIND_AUTO_CREATE, UserHandle.OWNER)) {
            if (DEBUG) Slog.v(TAG, "*** Keyguard: can't bind to " + KEYGUARD_CLASS);
        } else {
            if (DEBUG) Slog.v(TAG, "*** Keyguard started");
        }
    }

    public static KeyguardTouchDelegate getInstance(Context context) {
        KeyguardTouchDelegate instance = sInstance;
        if (instance == null) {
            instance = sInstance = new KeyguardTouchDelegate(context);
        }
        return instance;
    }

    public boolean isSecure() {
        final IKeyguardService service = mService;
        if (service != null) {
            try {
                return service.isSecure();
            } catch (RemoteException e) {
                Slog.e(TAG, "RemoteException calling keyguard.isSecure()!", e);
            }
        } else {
            Slog.w(TAG, "isSecure(): NO SERVICE!");
        }
        return false;
    }

    public boolean dispatch(MotionEvent event) {
        final IKeyguardService service = mService;
        if (service != null) {
            try {
                service.dispatch(event);
                return true;
            } catch (RemoteException e) {
                // What to do?
                Slog.e(TAG, "RemoteException sending event to keyguard!", e);
            }
        } else {
            Slog.w(TAG, "dispatch(event): NO SERVICE!");
        }
        return false;
    }

    public void dispatchButtonClick(int buttonId) {
        final IKeyguardService service = mService;
        if (service != null) {
            try {
                service.dispatchButtonClick(buttonId);
            } catch (RemoteException e) {
                // What to do?
                Slog.e(TAG, "RemoteException sending event to keyguard!", e);
            }
        } else {
            Slog.w(TAG, "dispatchButtonClick(buttonId): NO SERVICE!");
        }
    }

    public boolean isInputRestricted() {
        final IKeyguardService service = mService;
        if (service != null) {
            try {
                return service.isInputRestricted();
            } catch (RemoteException e) {
                Slog.w(TAG , "Remote Exception", e);
            }
        } else {
            Slog.w(TAG, "isInputRestricted(): NO SERVICE!");
        }
        return false;
    }

    public boolean isShowingAndNotHidden() {
        final IKeyguardService service = mService;
        if (service != null) {
            try {
                return service.isShowingAndNotHidden();
            } catch (RemoteException e) {
                Slog.w(TAG , "Remote Exception", e);
            }
        } else {
            Slog.w(TAG, "isShowingAndNotHidden(): NO SERVICE!");
        }
        return false;
    }

    public void showAssistant() {
        final IKeyguardService service = mService;
        if (service != null) {
            try {
                service.showAssistant();
            } catch (RemoteException e) {
                // What to do?
                Slog.e(TAG, "RemoteException launching assistant!", e);
            }
        } else {
            Slog.w(TAG, "showAssistant(event): NO SERVICE!");
        }
    }

    public void launchCamera() {
        final IKeyguardService service = mService;
        if (service != null) {
            try {
                service.launchCamera();
            } catch (RemoteException e) {
                // What to do?
                Slog.e(TAG, "RemoteException launching camera!", e);
            }
        } else {
            Slog.w(TAG, "launchCamera(): NO SERVICE!");
        }
    }

    public void dismiss() {
        final IKeyguardService service = mService;
        if (service != null) {
            try {
                service.dismiss();
            } catch (RemoteException e) {
                // What to do?
                Slog.e(TAG, "RemoteException dismissing keyguard!", e);
            }
        } else {
            Slog.w(TAG, "dismiss(): NO SERVICE!");
        }
    }

}
