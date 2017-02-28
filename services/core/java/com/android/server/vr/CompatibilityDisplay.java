
package com.android.server.vr;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import android.view.Surface;

import com.android.server.vr.VrManagerService;

/**
 * Creates a 2D Virtual Display while VR Mode is enabled. This display will be used to run and
 * render 2D app within a VR experience. For example, bringing up the 2D calculator app in VR.
 */
class CompatibilityDisplay {
    private final static String TAG = "CompatDisplay";
    private final static boolean DEBUG = false;

    // TODO: Go over these values and figure out what is best
    private final static int HEIGHT = 1800;
    private final static int WIDTH = 1400;
    private final static int DPI = 320;

    private final static String DEBUG_ACTION_SET_MODE =
            "com.android.server.vr.CompatibilityDisplay.SET_MODE";
    private final static String DEBUG_EXTRA_MODE_ON =
            "com.android.servier.vr.CompatibilityDisplay.EXTRA_MODE_ON";
    private final static String DEBUG_ACTION_SET_SURFACE =
            "com.android.server.vr.CompatibilityDisplay.SET_SURFACE";
    private final static String DEBUG_EXTRA_SURFACE =
            "com.android.server.vr.CompatibilityDisplay.EXTRA_SURFACE";

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
                updateVirtualDisplay();
            }
        }
    };

    private VirtualDisplay mVirtualDisplay;
    private Surface mSurface;
    private boolean mIsDebugOverrideEnabled;
    private boolean mIsVrModeEnabled;

    public CompatibilityDisplay(DisplayManager displayManager, IVrManager vrManager) {
        mDisplayManager = displayManager;
        mVrManager = vrManager;
    }

    /**
     * Initializes the compabilitiy display by listening to VR mode changes.
     */
    public void init(Context context) {
        startVrModeListener();
        startDebugOnlyBroadcastReceiver(context);
    }

    private void updateVirtualDisplay() {
        if (mIsVrModeEnabled || (DEBUG && mIsDebugOverrideEnabled)) {
            // TODO: Consider not creating the display until ActivityManager needs one on
            // which to display a 2D application.
            // TODO: STOPSHIP Remove DEBUG conditional before launching.
            if (DEBUG) {
                startVirtualDisplay();
            }
        } else {
            // TODO: Remove conditional when launching apps 2D doesn't force VrMode to stop.
            if (!DEBUG) {
                stopVirtualDisplay();
            }
        }
    }

    private void startDebugOnlyBroadcastReceiver(Context context) {
        if (DEBUG) {
            IntentFilter intentFilter = new IntentFilter(DEBUG_ACTION_SET_MODE);
            intentFilter.addAction(DEBUG_ACTION_SET_SURFACE);

            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    final String action = intent.getAction();
                    if (DEBUG_ACTION_SET_MODE.equals(action)) {
                        mIsDebugOverrideEnabled =
                                intent.getBooleanExtra(DEBUG_EXTRA_MODE_ON, false);
                        updateVirtualDisplay();
                    } else if (DEBUG_ACTION_SET_SURFACE.equals(action)) {
                        if (mVirtualDisplay != null) {
                            final Surface newSurface =
                                    intent.getParcelableExtra(DEBUG_EXTRA_SURFACE);

                            Log.i(TAG, "Setting the new surface from " + mSurface + " to " + newSurface);
                            if (newSurface != mSurface) {
                                mVirtualDisplay.setSurface(newSurface);
                                if (mSurface != null) {
                                    mSurface.release();
                                }
                                mSurface = newSurface;
                            }
                        } else {
                            Log.w(TAG, "Cannot set the surface because the VD is null.");
                        }
                    }
                }
            }, intentFilter);
        }
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
            Log.d(TAG, "Request to start VD, DM:" + mDisplayManager);
        }

        if (mDisplayManager == null) {
            Log.w(TAG, "Cannot create virtual display because mDisplayManager == null");
            return;
        }

        synchronized (vdLock) {
            if (mVirtualDisplay != null) {
                Log.i(TAG, "VD already exists, ignoring request");
                return;
            }

            mVirtualDisplay = mDisplayManager.createVirtualDisplay("VR 2D Display", WIDTH, HEIGHT,
                    DPI, null /* Surface */, 0 /* flags */);
            if (mSurface != null && mSurface.isValid()) {
              // TODO: Need to protect all setSurface calls with a lock.
              mVirtualDisplay.setSurface(mSurface);
            }
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
