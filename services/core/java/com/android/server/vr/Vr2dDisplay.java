package com.android.server.vr;

import static android.view.Display.INVALID_DISPLAY;

import android.app.ActivityManagerInternal;
import android.app.Vr2dDisplayProperties;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
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
class Vr2dDisplay {
    private final static String TAG = "Vr2dDisplay";
    private final static boolean DEBUG = false;

    // TODO: Go over these values and figure out what is best
    private int mVirtualDisplayHeight;
    private int mVirtualDisplayWidth;
    private int mVirtualDisplayDpi;
    private final static int STOP_VIRTUAL_DISPLAY_DELAY_MILLIS = 2000;
    private final static String UNIQUE_DISPLAY_ID = "277f1a09-b88d-4d1e-8716-796f114d080b";
    private final static String DISPLAY_NAME = "VR 2D Display";

    private final static String DEBUG_ACTION_SET_MODE =
            "com.android.server.vr.Vr2dDisplay.SET_MODE";
    private final static String DEBUG_EXTRA_MODE_ON =
            "com.android.server.vr.Vr2dDisplay.EXTRA_MODE_ON";
    private final static String DEBUG_ACTION_SET_SURFACE =
            "com.android.server.vr.Vr2dDisplay.SET_SURFACE";
    private final static String DEBUG_EXTRA_SURFACE =
            "com.android.server.vr.Vr2dDisplay.EXTRA_SURFACE";

    /**
     * The default width of the VR virtual display
     */
    public static final int DEFAULT_VR_DISPLAY_WIDTH = 1400;

    /**
     * The default height of the VR virtual display
     */
    public static final int DEFAULT_VR_DISPLAY_HEIGHT = 1800;

    /**
     * The default height of the VR virtual dpi.
     */
    public static final int DEFAULT_VR_DISPLAY_DPI = 320;

    /**
     * The minimum height, width and dpi of VR virtual display.
     */
    public static final int MIN_VR_DISPLAY_WIDTH = 1;
    public static final int MIN_VR_DISPLAY_HEIGHT = 1;
    public static final int MIN_VR_DISPLAY_DPI = 1;

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

    public Vr2dDisplay(DisplayManager displayManager,
           ActivityManagerInternal activityManagerInternal, IVrManager vrManager) {
        mDisplayManager = displayManager;
        mActivityManagerInternal = activityManagerInternal;
        mVrManager = vrManager;
        mVirtualDisplayWidth = DEFAULT_VR_DISPLAY_WIDTH;
        mVirtualDisplayHeight = DEFAULT_VR_DISPLAY_HEIGHT;
        mVirtualDisplayDpi = DEFAULT_VR_DISPLAY_DPI;
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
        if (DEBUG) {
            Log.i(TAG, "isVrMode: " + mIsVrModeEnabled + ", override: " + mIsVrModeOverrideEnabled);
        }

        if (mIsVrModeEnabled || mIsVrModeOverrideEnabled) {
            // TODO: Consider not creating the display until ActivityManager needs one on
            // which to display a 2D application.
            startVirtualDisplay();
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
        if (DEBUG) {
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
     * Sets the resolution and DPI of the Vr2d virtual display used to display
     * 2D applications in VR mode.
     *
     * <p>Requires {@link android.Manifest.permission#ACCESS_VR_MANAGER} permission.</p>
     *
     * @param compatDisplayProperties Properties of the virtual display for 2D applications
     * in VR mode.
     */
    public void setVirtualDisplayProperties(Vr2dDisplayProperties compatDisplayProperties) {
        synchronized(mVdLock) {
            if (DEBUG) {
                Log.i(TAG, "VD setVirtualDisplayProperties: res = "
                        + compatDisplayProperties.getWidth() + "X"
                        + compatDisplayProperties.getHeight() + ", dpi = "
                        + compatDisplayProperties.getDpi());
            }

            if (compatDisplayProperties.getWidth() < MIN_VR_DISPLAY_WIDTH ||
                compatDisplayProperties.getHeight() < MIN_VR_DISPLAY_HEIGHT ||
                compatDisplayProperties.getDpi() < MIN_VR_DISPLAY_DPI) {
                throw new IllegalArgumentException (
                        "Illegal argument: height, width, dpi cannot be negative. res = "
                        + compatDisplayProperties.getWidth() + "X"
                        + compatDisplayProperties.getHeight()
                        + ", dpi = " + compatDisplayProperties.getDpi());
            }

            mVirtualDisplayWidth = compatDisplayProperties.getWidth();
            mVirtualDisplayHeight = compatDisplayProperties.getHeight();
            mVirtualDisplayDpi = compatDisplayProperties.getDpi();

            if (mVirtualDisplay != null) {
                mVirtualDisplay.resize(mVirtualDisplayWidth, mVirtualDisplayHeight,
                    mVirtualDisplayDpi);
                ImageReader oldImageReader = mImageReader;
                mImageReader = null;
                startImageReader();
                oldImageReader.close();
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
                    Log.i(TAG, "VD id: " + virtualDisplayId);
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

            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH;
            mVirtualDisplay = mDisplayManager.createVirtualDisplay(null /* projection */,
                    DISPLAY_NAME, mVirtualDisplayWidth, mVirtualDisplayHeight, mVirtualDisplayDpi,
                    null /* surface */, flags, null /* callback */, null /* handler */,
                    UNIQUE_DISPLAY_ID);

            if (mVirtualDisplay != null) {
                mActivityManagerInternal.setVr2dDisplayId(
                    mVirtualDisplay.getDisplay().getDisplayId());
                // Now create the ImageReader to supply a Surface to the new virtual display.
                startImageReader();
            } else {
                Log.w(TAG, "Virtual display id is null after createVirtualDisplay");
                mActivityManagerInternal.setVr2dDisplayId(INVALID_DISPLAY);
                return;
            }
        }

        Log.i(TAG, "VD created: " + mVirtualDisplay);
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
                            mActivityManagerInternal.setVr2dDisplayId(INVALID_DISPLAY);
                            setSurfaceLocked(null); // clean up and release the surface first.
                            if (mVirtualDisplay != null) {
                                mVirtualDisplay.release();
                                mVirtualDisplay = null;
                            }
                            stopImageReader();
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
            mImageReader = ImageReader.newInstance(mVirtualDisplayWidth, mVirtualDisplayHeight,
                PixelFormat.RGBA_8888, 2 /* maxImages */);
            Log.i(TAG, "VD startImageReader: res = " + mVirtualDisplayWidth + "X" +
                    mVirtualDisplayHeight + ", dpi = " + mVirtualDisplayDpi);
        }
        synchronized (mVdLock) {
            setSurfaceLocked(mImageReader.getSurface());
        }
    }

    /**
     * Cleans up the ImageReader.
     */
    private void stopImageReader() {
        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
    }
}
