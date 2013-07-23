
package com.android.mediaframeworktest.integration;

import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.hardware.ICameraService;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Log;

public class CameraBinderTestUtils {
    private final ICameraService mCameraService;
    private int mGuessedNumCameras;

    static final String CAMERA_SERVICE_BINDER_NAME = "media.camera";

    protected static final int USE_CALLING_UID = -1;
    protected static final int BAD_VALUE = -22;
    protected static final int INVALID_OPERATION = -38;
    protected static final int ALREADY_EXISTS = -17;
    public static final int NO_ERROR = 0;
    private final Context mContext;

    public CameraBinderTestUtils(Context context) {

        mContext = context;

        guessNumCameras();

        IBinder cameraServiceBinder = ServiceManager
                .getService(CameraBinderTestUtils.CAMERA_SERVICE_BINDER_NAME);
        assertNotNull("Camera service IBinder should not be null", cameraServiceBinder);

        this.mCameraService = ICameraService.Stub.asInterface(cameraServiceBinder);
        assertNotNull("Camera service should not be null", getCameraService());
    }

    private void guessNumCameras() {

        /**
         * Why do we need this? This way we have no dependency on getNumCameras
         * actually working. On most systems there are only 0, 1, or 2 cameras,
         * and this covers that 'usual case'. On other systems there might be 3+
         * cameras, but this will at least check the first 2.
         */
        this.mGuessedNumCameras = 0;

        // Front facing camera
        if (CameraBinderTestUtils.isFeatureAvailable(mContext,
                PackageManager.FEATURE_CAMERA_FRONT)) {
            this.mGuessedNumCameras = getGuessedNumCameras() + 1;
        }

        // Back facing camera
        if (CameraBinderTestUtils.isFeatureAvailable(mContext,
                PackageManager.FEATURE_CAMERA)) {
            this.mGuessedNumCameras = getGuessedNumCameras() + 1;
        }

        // Any facing camera
        if (getGuessedNumCameras() == 0
                && CameraBinderTestUtils.isFeatureAvailable(mContext,
                        PackageManager.FEATURE_CAMERA_ANY)) {
            this.mGuessedNumCameras = getGuessedNumCameras() + 1;
        }

        Log.v(CameraBinderTest.TAG, "Guessing there are at least " + getGuessedNumCameras()
                + " cameras");
    }

    final static public boolean isFeatureAvailable(Context context, String feature) {
        final PackageManager packageManager = context.getPackageManager();
        final FeatureInfo[] featuresList = packageManager.getSystemAvailableFeatures();
        for (FeatureInfo f : featuresList) {
            if (f.name != null && f.name.equals(feature)) {
                return true;
            }
        }

        return false;
    }

    ICameraService getCameraService() {
        return mCameraService;
    }

    int getGuessedNumCameras() {
        return mGuessedNumCameras;
    }
}
