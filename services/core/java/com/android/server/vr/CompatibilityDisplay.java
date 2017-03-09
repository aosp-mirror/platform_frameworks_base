package com.android.server.vr;

import static android.view.Display.INVALID_DISPLAY;

import android.app.ActivityManagerInternal;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.ImageFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.service.vr.IPersistentVrStateCallbacks;
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
    private final static int STOP_VIRTUAL_DISPLAY_DELAY_MILLIS = 2000;

    private final static String DEBUG_ACTION_SET_MODE =
            "com.android.server.vr.CompatibilityDisplay.SET_MODE";
    private final static String DEBUG_EXTRA_MODE_ON =
            "com.android.server.vr.CompatibilityDisplay.EXTRA_MODE_ON";
    private final static String DEBUG_ACTION_SET_SURFACE =
            "com.android.server.vr.CompatibilityDisplay.SET_SURFACE";
    private final static String DEBUG_EXTRA_SURFACE =
            "com.android.server.vr.CompatibilityDisplay.EXTRA_SURFACE";

    private final ActivityManagerInternal mActivityManagerInternal;
    private final DisplayManager mDisplayManager;
    private final IVrManager mVrManager;
    private final Object mVdLock = new Object();
    private final Handler mHandler = new Handler();

    /**
     * Callback implementation to receive changes to VrMode.
     **/
    private final IPersistentVrStateCallbacks mVrStateCallbacks =
            new IPersistentVrStateCallbacks.Stub() {
        @Override
        public void onPersistentVrStateChanged(boolean enabled) {
            if (enabled != mIsVrModeEnabled) {
                mIsVrModeEnabled = enabled;
                updateVirtualDisplay();
            }
        }
    };

    private VirtualDisplay mVirtualDisplay;
    private Surface mSurface;
    private ImageReader mImageReader;
    private Runnable mStopVDRunnable;
    private boolean mIsVrModeOverrideEnabled;
    private boolean mIsVrModeEnabled;

    public CompatibilityDisplay(DisplayManager displayManager,
           ActivityManagerInternal activityManagerInternal, IVrManager vrManager) {
        mDisplayManager = displayManager;
        mActivityManagerInternal = activityManagerInternal;
        mVrManager = vrManager;
    }

    /**
     * Initializes the compabilitiy display by listening to VR mode changes.
     */
    public void init(Context context) {
        startVrModeListener();
        startDebugOnlyBroadcastReceiver(context);
    }

    /**
     * Creates and Destroys the virtual display depending on the current state of VrMode.
     */
    private void updateVirtualDisplay() {
        boolean createVirtualDisplay = "true".equals(SystemProperties.get("vr_virtualdisplay"));
        if (DEBUG) {
            Log.i(TAG, "isVrMode: " + mIsVrModeEnabled + ", createVD: " + createVirtualDisplay +
                    ", override: " + mIsVrModeOverrideEnabled);
        }

        if (mIsVrModeEnabled || (createVirtualDisplay && mIsVrModeOverrideEnabled)) {
            // TODO: Consider not creating the display until ActivityManager needs one on
            // which to display a 2D application.
            // TODO: STOPSHIP Remove createVirtualDisplay conditional before launching.
            if (createVirtualDisplay) {
                startVirtualDisplay();
                startImageReader();
            }
        } else {
            // Stop virtual display to test exit condition
            stopVirtualDisplay();
        }
    }

    /**
     * Creates a DEBUG-only BroadcastReceiver through which a test app can simulate VrMode and
     * set a custom Surface for the virtual display.  This allows testing of the virtual display
     * without going into full 3D.
     *
     * @param context The context.
     */
    private void startDebugOnlyBroadcastReceiver(Context context) {
        // STOPSHIP: remove vr_debug_vd_receiver test.
        boolean debugBroadcast = "true".equals(SystemProperties.get("vr_debug_vd_receiver"));
        if (DEBUG || debugBroadcast) {
            IntentFilter intentFilter = new IntentFilter(DEBUG_ACTION_SET_MODE);
            intentFilter.addAction(DEBUG_ACTION_SET_SURFACE);

            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    final String action = intent.getAction();
                    if (DEBUG_ACTION_SET_MODE.equals(action)) {
                        mIsVrModeOverrideEnabled =
                                intent.getBooleanExtra(DEBUG_EXTRA_MODE_ON, false);
                        updateVirtualDisplay();
                    } else if (DEBUG_ACTION_SET_SURFACE.equals(action)) {
                        if (mVirtualDisplay != null) {
                            if (intent.hasExtra(DEBUG_EXTRA_SURFACE)) {
                                setSurfaceLocked(intent.getParcelableExtra(DEBUG_EXTRA_SURFACE));
                            }
                        } else {
                            Log.w(TAG, "Cannot set the surface because the VD is null.");
                        }
                    }
                }
            }, intentFilter);
        }
    }

    /**
     * Starts listening to VrMode changes.
     */
    private void startVrModeListener() {
        if (mVrManager != null) {
            try {
                mVrManager.registerPersistentVrStateListener(mVrStateCallbacks);
            } catch (RemoteException e) {
                Log.e(TAG, "Could not register VR State listener.", e);
            }
        }
    }

    /**
     * Returns the virtual display ID if one currently exists, otherwise returns
     * {@link INVALID_DISPLAY_ID}.
     *
     * @return The virtual display ID.
     */
    public int getVirtualDisplayId() {
        synchronized(mVdLock) {
            if (mVirtualDisplay != null) {
                int virtualDisplayId = mVirtualDisplay.getDisplay().getDisplayId();
                if (DEBUG) {
                    Log.e(TAG, "VD id: " + virtualDisplayId);
                }
                return virtualDisplayId;
            }
        }
        return INVALID_DISPLAY;
    }

    /**
     * Starts the virtual display if one does not already exist.
     */
    private void startVirtualDisplay() {
        if (DEBUG) {
            Log.d(TAG, "Request to start VD, DM:" + mDisplayManager);
        }

        if (mDisplayManager == null) {
            Log.w(TAG, "Cannot create virtual display because mDisplayManager == null");
            return;
        }

        synchronized (mVdLock) {
            if (mVirtualDisplay != null) {
                Log.i(TAG, "VD already exists, ignoring request");
                return;
            }

            mVirtualDisplay = mDisplayManager.createVirtualDisplay("VR 2D Display", WIDTH, HEIGHT,
                    DPI, null /* Surface */, 0 /* flags */);

            if (mVirtualDisplay != null) {
                mActivityManagerInternal.setVrCompatibilityDisplayId(
                    mVirtualDisplay.getDisplay().getDisplayId());
            } else {
                Log.w(TAG, "Virtual display id is null after createVirtualDisplay");
                mActivityManagerInternal.setVrCompatibilityDisplayId(INVALID_DISPLAY);
                return;
            }
        }

        if (DEBUG) {
            Log.d(TAG, "VD created: " + mVirtualDisplay);
        }
    }

    /**
     * Stops the virtual display with a {@link #STOP_VIRTUAL_DISPLAY_DELAY_MILLIS} timeout.
     * The timeout prevents the virtual display from bouncing in cases where VrMode goes in and out
     * of being enabled. This can happen sometimes with our 2D test app.
     */
    private void stopVirtualDisplay() {
        if (mStopVDRunnable == null) {
           mStopVDRunnable = new Runnable() {
               @Override
               public void run() {
                    if (mIsVrModeEnabled) {
                        Log.i(TAG, "Virtual Display destruction stopped: VrMode is back on.");
                    } else {
                        Log.i(TAG, "Stopping Virtual Display");
                        synchronized (mVdLock) {
                            mActivityManagerInternal.setVrCompatibilityDisplayId(INVALID_DISPLAY);
                            setSurfaceLocked(null); // clean up and release the surface first.
                            if (mVirtualDisplay != null) {
                                mVirtualDisplay.release();
                                mVirtualDisplay = null;
                            }
                        }
                    }
               }
           };
        }

        mHandler.removeCallbacks(mStopVDRunnable);
        mHandler.postDelayed(mStopVDRunnable, STOP_VIRTUAL_DISPLAY_DELAY_MILLIS);
    }

    /**
     * Set the surface to use with the virtual display.
     *
     * Code should be locked by {@link #mVdLock} before invoked.
     *
     * @param surface The Surface to set.
     */
    private void setSurfaceLocked(Surface surface) {
        // Change the surface to either a valid surface or a null value.
        if (mSurface != surface && (surface == null || surface.isValid())) {
            Log.i(TAG, "Setting the new surface from " + mSurface + " to " + surface);
            if (mVirtualDisplay != null) {
                mVirtualDisplay.setSurface(surface);
            }
            if (mSurface != null) {
                mSurface.release();
            }
            mSurface = surface;
        }
    }

    /**
     * Starts an ImageReader as a do-nothing Surface.  The virtual display will not get fully
     * initialized within surface flinger unless it has a valid Surface associated with it. We use
     * the ImageReader as the default valid Surface.
     */
    private void startImageReader() {
        if (mImageReader == null) {
            mImageReader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.RAW_PRIVATE,
                2 /* maxImages */);
        }
        synchronized (mVdLock) {
            setSurfaceLocked(mImageReader.getSurface());
        }
    }
}
