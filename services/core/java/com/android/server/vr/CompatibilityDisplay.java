
package com.android.server.vr;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.service.vr.IVrStateCallbacks;
import android.service.vr.IVrManager;
import android.util.Log;

import com.android.server.vr.VrManagerService;

/**
 * Creates a 2D Virtual Display while VR Mode is enabled. This display will be used to run and
 * render 2D app within a VR experience. For example, bringing up the 2D calculator app in VR.
 */
class CompatibilityDisplay {
    private final static String TAG = "CompatDisplay";
    private final static boolean DEBUG = false;

    // TODO: Go over these values and figure out what is best
    private final static int HEIGHT = 960;
    private final static int WIDTH = 720;
    private final static int DPI = 320;

    private final DisplayManager mDisplayManager;
    private final IVrManager mVrManager;

    // TODO: Lock initially created when VrStateCallback was connected through Binder. This may not
    // be necessary with the direct access to VrManager.
    private final Object vdLock = new Object();

    private final IVrStateCallbacks mVrStateCallbacks = new IVrStateCallbacks.Stub() {
        @Override
        public void onVrStateChanged(boolean enabled) {
            if (enabled != mIsVrModeEnabled) {
                mIsVrModeEnabled = enabled;
                if (enabled) {
                    // TODO: Consider not creating the display until ActivityManager needs one on
                    // which to display a 2D application.
                    startVirtualDisplay();
                } else {
                    stopVirtualDisplay();
                }
            }
        }
    };

    private VirtualDisplay mVirtualDisplay;
    private boolean mIsVrModeEnabled;

    public CompatibilityDisplay(DisplayManager displayManager, IVrManager vrManager) {
        mDisplayManager = displayManager;
        mVrManager = vrManager;
    }

    /**
     * Initializes the compabilitiy display by listening to VR mode changes.
     */
    public void init() {
        startVrModeListener();
    }

    private void startVrModeListener() {
        if (mVrManager != null) {
            try {
                mVrManager.registerListener(mVrStateCallbacks);
            } catch (RemoteException e) {
                Log.e(TAG, "Could not register VR State listener.", e);
            }
        }
    }

    private void startVirtualDisplay() {
        if (DEBUG) {
            Log.d(TAG, "Starting VD, DM:" + mDisplayManager);
        }

        if (mDisplayManager == null) {
            Log.w(TAG, "Cannot create virtual display because mDisplayManager == null");
            return;
        }

        synchronized (vdLock) {
            if (mVirtualDisplay != null) {
                Log.e(TAG, "Starting the virtual display when one already exists", new Exception());
                return;
            }

            mVirtualDisplay = mDisplayManager.createVirtualDisplay("VR 2D Display", WIDTH, HEIGHT,
                    DPI,
                    null /* Surface */, 0 /* flags */);
        }

        if (DEBUG) {
            Log.d(TAG, "VD created: " + mVirtualDisplay);
        }
    }

    private void stopVirtualDisplay() {
        if (DEBUG) {
            Log.i(TAG, "Santos, stopping VD");
        }

        synchronized (vdLock) {
            if (mVirtualDisplay != null) {
                mVirtualDisplay.release();
                mVirtualDisplay = null;
            }
        }
    }
}
