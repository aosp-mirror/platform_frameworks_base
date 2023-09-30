package android.companion.virtual.camera;

import android.companion.virtual.camera.VirtualCameraStreamConfig;

/**
 * Configuration for VirtualCamera to be passed to the server and HAL service.
 * @hide
 */
parcelable VirtualCameraHalConfig {
  String displayName;
  VirtualCameraStreamConfig[] streamConfigs;
}
