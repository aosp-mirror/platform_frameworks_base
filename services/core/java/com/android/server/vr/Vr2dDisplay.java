package com.android.server.vr;

import static android.view.Display.INVALID_DISPLAY;

import android.app.ActivityManagerInternal;
import android.app.Vr2dDisplayProperties;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.media.ImageReader;
import android.os.Handler;
import android.os.RemoteException;
import android.service.vr.IPersistentVrStateCallbacks;
import android.service.vr.IVrManager;
import android.util.Log;
import android.view.Surface;

import com.android.server.LocalServices;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerInternal;

/**
 * Creates a 2D Virtual Display while VR Mode is enabled. This display will be used to run and
 * render 2D app within a VR experience. For example, bringing up the 2D calculator app in VR.
 */
class Vr2dDisplay {
    private final static String TAG = "Vr2dDisplay";
    private final static boolean DEBUG = false;

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
    public static final int DEFAULT_VIRTUAL_DISPLAY_WIDTH = 1400;

    /**
     * The default height of the VR virtual display
     */
    public static final int DEFAULT_VIRTUAL_DISPLAY_HEIGHT = 1800;

    /**
     * The default height of the VR virtual dpi.
     */
    public static final int DEFAULT_VIRTUAL_DISPLAY_DPI = 320;

    /**
     * The minimum height, width and dpi of VR virtual display.
     */
    public static final int MIN_VR_DISPLAY_WIDTH = 1;
    public static final int MIN_VR_DISPLAY_HEIGHT = 1;
    public static final int MIN_VR_DISPLAY_DPI = 1;

    private final ActivityManagerInternal mActivityManagerInternal;
    private final WindowManagerInternal mWindowManagerInternal;
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
            if (enabled != mIsPersistentVrModeEnabled) {
                mIsPersistentVrModeEnabled = enabled;
                updateVirtualDisplay();
            }
        }
    };

    private VirtualDisplay mVirtualDisplay;
    private Surface mSurface;
    private ImageReader mImageReader;
    private Runnable mStopVDRunnable;
    private boolean mIsVrModeOverrideEnabled;  // debug override to set vr mode.
    private boolean mIsVirtualDisplayAllowed = true;  // Virtual-display feature toggle
    private boolean mIsPersistentVrModeEnabled;  // indicates we are in vr persistent mode.
    private boolean mBootsToVr = false;  // The device boots into VR (standalone VR device)

    public Vr2dDisplay(DisplayManager displayManager,
           ActivityManagerInternal activityManagerInternal,
           WindowManagerInternal windowManagerInternal, IVrManager vrManager) {
        mDisplayManager = displayManager;
        mActivityManagerInternal = activityManagerInternal;
        mWindowManagerInternal = windowManagerInternal;
        mVrManager = vrManager;
        mVirtualDisplayWidth = DEFAULT_VIRTUAL_DISPLAY_WIDTH;
        mVirtualDisplayHeight = DEFAULT_VIRTUAL_DISPLAY_HEIGHT;
        mVirtualDisplayDpi = DEFAULT_VIRTUAL_DISPLAY_DPI;
    }

    /**
     * Initializes the compabilitiy display by listening to VR mode changes.
     */
    public void init(Context context, boolean bootsToVr) {
        startVrModeListener();
        startDebugOnlyBroadcastReceiver(context);
        mBootsToVr = bootsToVr;
        if (mBootsToVr) {
          // If we are booting into VR, we need to start the virtual display immediately. This
          // ensures that the virtual display is up by the time Setup Wizard is started.
          updateVirtualDisplay();
        }
    }

    /**
     * Creates and Destroys the virtual display depending on the current state of VrMode.
     */
    private void updateVirtualDisplay() {
        if (DEBUG) {
            Log.i(TAG, "isVrMode: " + mIsPersistentVrModeEnabled + ", override: "
                    + mIsVrModeOverrideEnabled + ", isAllowed: " + mIsVirtualDisplayAllowed
                    + ", bootsToVr: " + mBootsToVr);
        }

        if (shouldRunVirtualDisplay()) {
            Log.i(TAG, "Attempting to start virtual display");
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
     * @param displayProperties Properties of the virtual display for 2D applications
     * in VR mode.
     */
    public void setVirtualDisplayProperties(Vr2dDisplayProperties displayProperties) {
        synchronized(mVdLock) {
            if (DEBUG) {
                Log.i(TAG, "VD setVirtualDisplayProperties: " +
                        displayProperties.toString());
            }

            int width = displayProperties.getWidth();
            int height = displayProperties.getHeight();
            int dpi = displayProperties.getDpi();
            boolean resized = false;

            if (width < MIN_VR_DISPLAY_WIDTH || height < MIN_VR_DISPLAY_HEIGHT ||
                    dpi < MIN_VR_DISPLAY_DPI) {
                Log.i(TAG, "Ignoring Width/Height/Dpi values of " + width + "," + height + ","
                        + dpi);
            } else {
                Log.i(TAG, "Setting width/height/dpi to " + width + "," + height + "," + dpi);
                mVirtualDisplayWidth = width;
                mVirtualDisplayHeight = height;
                mVirtualDisplayDpi = dpi;
                resized = true;
            }

            if ((displayProperties.getAddedFlags() &
                    Vr2dDisplayProperties.FLAG_VIRTUAL_DISPLAY_ENABLED)
                    == Vr2dDisplayProperties.FLAG_VIRTUAL_DISPLAY_ENABLED) {
                mIsVirtualDisplayAllowed = true;
            } else if ((displayProperties.getRemovedFlags() &
                    Vr2dDisplayProperties.FLAG_VIRTUAL_DISPLAY_ENABLED)
                    == Vr2dDisplayProperties.FLAG_VIRTUAL_DISPLAY_ENABLED) {
                mIsVirtualDisplayAllowed = false;
            }

            if (mVirtualDisplay != null && resized && mIsVirtualDisplayAllowed) {
                mVirtualDisplay.resize(mVirtualDisplayWidth, mVirtualDisplayHeight,
                    mVirtualDisplayDpi);
                ImageReader oldImageReader = mImageReader;
                mImageReader = null;
                startImageReader();
                oldImageReader.close();
            }

            // Start/Stop the virtual display in case the updates indicated that we should.
            updateVirtualDisplay();
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
            flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT;
            flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
            flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
            flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL;
            flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE;

            final VirtualDisplayConfig.Builder builder = new VirtualDisplayConfig.Builder(
                    DISPLAY_NAME, mVirtualDisplayWidth, mVirtualDisplayHeight, mVirtualDisplayDpi);
            builder.setUniqueId(UNIQUE_DISPLAY_ID);
            builder.setFlags(flags);
            mVirtualDisplay = mDisplayManager.createVirtualDisplay(null /* projection */,
                    builder.build(), null /* callback */, null /* handler */);

            if (mVirtualDisplay != null) {
                updateDisplayId(mVirtualDisplay.getDisplay().getDisplayId());
                // Now create the ImageReader to supply a Surface to the new virtual display.
                startImageReader();
            } else {
                Log.w(TAG, "Virtual display id is null after createVirtualDisplay");
                updateDisplayId(INVALID_DISPLAY);
                return;
            }
        }

        Log.i(TAG, "VD created: " + mVirtualDisplay);
    }

    private void updateDisplayId(int displayId) {
        LocalServices.getService(ActivityTaskManagerInternal.class).setVr2dDisplayId(displayId);
        mWindowManagerInternal.setVr2dDisplayId(displayId);
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
                    if (shouldRunVirtualDisplay()) {
                        Log.i(TAG, "Virtual Display destruction stopped: VrMode is back on.");
                    } else {
                        Log.i(TAG, "Stopping Virtual Display");
                        synchronized (mVdLock) {
                            updateDisplayId(INVALID_DISPLAY);
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

    private boolean shouldRunVirtualDisplay() {
        // Virtual Display should run whenever:
        // * Virtual Display is allowed/enabled AND
        // (1) BootsToVr is set indicating the device never leaves VR
        // (2) VR (persistent) mode is enabled
        // (3) VR mode is overridden to be enabled.
        return mIsVirtualDisplayAllowed &&
                (mBootsToVr || mIsPersistentVrModeEnabled || mIsVrModeOverrideEnabled);
    }
}
