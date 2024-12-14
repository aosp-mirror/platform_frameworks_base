/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.hardware.camera2.extension;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.impl.CaptureCallback;
import android.util.Log;
import android.util.Pair;
import android.util.Size;

import com.android.internal.camera.flags.Flags;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Advanced contract for implementing Extensions. ImageCapture/Preview
 * Extensions are both implemented on this interface.
 *
 * <p>This advanced contract empowers implementations to gain access to
 * more Camera2 capability. This includes: (1) Add custom surfaces with
 * specific formats like {@link android.graphics.ImageFormat#YUV_420_888},
 * {@link android.graphics.ImageFormat#RAW10}, {@link android.graphics.ImageFormat#RAW_DEPTH10}.
 * (2) Access to the capture request callbacks as well as all the images retrieved of
 * various image formats. (3)
 * Able to triggers single or repeating request with the capabilities to
 * specify target surfaces, template id and parameters.
 *
 * @hide
 */
@SystemApi
public abstract class AdvancedExtender {
    private HashMap<String, Long> mMetadataVendorIdMap = new HashMap<>();
    private final CameraManager mCameraManager;

    private CameraUsageTracker mCameraUsageTracker;
    private static final String TAG = "AdvancedExtender";

    /**
     * Initialize a camera extension advanced extender instance.
     *
     * @param cameraManager the system camera manager
     */
    public AdvancedExtender(@NonNull CameraManager cameraManager) {
        mCameraManager = cameraManager;
        try {
            String [] cameraIds = mCameraManager.getCameraIdListNoLazy();
            if (cameraIds != null) {
                for (String cameraId : cameraIds) {
                    CameraCharacteristics chars = mCameraManager.getCameraCharacteristics(cameraId);
                    Object thisClass = CameraCharacteristics.Key.class;
                    Class<CameraCharacteristics.Key<?>> keyClass =
                            (Class<CameraCharacteristics.Key<?>>)thisClass;
                    ArrayList<CameraCharacteristics.Key<?>> vendorKeys =
                            chars.getNativeMetadata().getAllVendorKeys(keyClass);
                    if ((vendorKeys != null) && !vendorKeys.isEmpty()) {
                        mMetadataVendorIdMap.put(cameraId, vendorKeys.get(0).getVendorId());
                    }
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to query camera characteristics!");
        }
    }

    void setCameraUsageTracker(CameraUsageTracker tracker) {
        mCameraUsageTracker = tracker;
    }

    /**
     * Returns the camera metadata vendor id, that can be used to
     * configure and enable vendor tag support for a particular
     * camera metadata buffer.
     *
     * @param cameraId           The camera2 id string of the camera.
     * @return the camera metadata vendor Id associated with the given camera
     */
    public long getMetadataVendorId(@NonNull String cameraId) {
        long vendorId = mMetadataVendorIdMap.containsKey(cameraId) ?
                mMetadataVendorIdMap.get(cameraId) : Long.MAX_VALUE;
        return vendorId;
    }

    /**
     * Indicates whether the extension is supported on the device.
     *
     * @param cameraId           The camera2 id string of the camera.
     * @param charsMap           A map consisting of the camera ids and
     *                           the {@link android.hardware.camera2.CameraCharacteristics}s. For
     *                           every camera, the map contains at least
     *                           the CameraCharacteristics for the camera
     *                           id.
     *                           If the camera is logical camera, it will
     *                           also contain associated
     *                           physical camera ids and their
     *                           CameraCharacteristics.
     * @return true if the extension is supported, otherwise false
     */
    public abstract boolean isExtensionAvailable(@NonNull String cameraId,
            @NonNull CharacteristicsMap charsMap);

    /**
     * Initializes the extender to be used with the specified camera.
     *
     * <p>This should be called before any other method on the extender.
     * The exception is {@link #isExtensionAvailable}.
     *
     * @param cameraId           The camera2 id string of the camera.
     * @param map                A map consisting of the camera ids and
     *                           the {@link android.hardware.camera2.CameraCharacteristics}s. For
     *                           every camera, the map contains at least
     *                           the CameraCharacteristics for the camera
     *                           id.
     *                           If the camera is logical camera, it will
     *                           also contain associated
     *                           physical camera ids and their
     *                           CameraCharacteristics.
     */
    public abstract void initialize(@NonNull String cameraId, @NonNull CharacteristicsMap map);

    /**
     * Returns supported output format/size map for preview. The format
     * could be {@link android.graphics.ImageFormat#PRIVATE} or
     * {@link android.graphics.ImageFormat#YUV_420_888}. Implementations must support
     * {@link android.graphics.ImageFormat#PRIVATE} format at least.
     * An example of how the map is parsed can be found in
     * {@link #initializeParcelable(Map)}
     *
     * <p>The preview surface format in the CameraCaptureSession may not
     * be identical to the supported preview output format returned here.
     * @param cameraId           The camera2 id string of the camera.
     */
    @NonNull
    public abstract Map<Integer, List<Size>> getSupportedPreviewOutputResolutions(
            @NonNull String cameraId);

    /**
     * Returns supported output format/size map for image capture. OEM is
     * required to support both {@link android.graphics.ImageFormat#JPEG} and
     * {@link android.graphics.ImageFormat#YUV_420_888} format output.
     * An example of how the map is parsed can be found in
     * {@link #initializeParcelable(Map)}
     *
     * <p>The surface created with this supported
     * format/size could be either added in CameraCaptureSession with HAL
     * processing OR it  configures intermediate surfaces(
     * {@link android.graphics.ImageFormat#YUV_420_888}/
     * {@link android.graphics.ImageFormat#RAW10}..) and
     * writes the output to the output surface.
     * @param cameraId           The camera2 id string of the camera.
     */
    @NonNull
    public abstract Map<Integer, List<Size>> getSupportedCaptureOutputResolutions(
            @NonNull String cameraId);

    /**
     * Returns a processor for activating extension sessions. It
     * implements all the interactions required for starting an extension
     * and cleanup.
     */
    @NonNull
    public abstract SessionProcessor getSessionProcessor();

    /**
     * Returns a list of orthogonal capture request keys.
     *
     * <p>Any keys included in the list will be configurable by clients of
     * the extension and will affect the extension functionality.</p>
     *
     * <p>Please note that the keys {@link CaptureRequest#JPEG_QUALITY}
     * and {@link CaptureRequest#JPEG_ORIENTATION} are always supported
     * regardless of being added to the list or not. To support common
     * camera operations like zoom, tap-to-focus, flash and
     * exposure compensation, we recommend supporting the following keys
     * if  possible.
     * <pre>
     *  zoom:  {@link CaptureRequest#CONTROL_ZOOM_RATIO}
     *         {@link CaptureRequest#SCALER_CROP_REGION}
     *  tap-to-focus:
     *         {@link CaptureRequest#CONTROL_AF_MODE}
     *         {@link CaptureRequest#CONTROL_AF_TRIGGER}
     *         {@link CaptureRequest#CONTROL_AF_REGIONS}
     *         {@link CaptureRequest#CONTROL_AE_REGIONS}
     *         {@link CaptureRequest#CONTROL_AWB_REGIONS}
     *  flash:
     *         {@link CaptureRequest#CONTROL_AE_MODE}
     *         {@link CaptureRequest#CONTROL_AE_PRECAPTURE_TRIGGER}
     *         {@link CaptureRequest#FLASH_MODE}
     *  exposure compensation:
     *         {@link CaptureRequest#CONTROL_AE_EXPOSURE_COMPENSATION}
     * </pre>
     *
     * @param cameraId           The camera2 id string of the camera.
     *
     * @return The list of supported orthogonal capture keys, or empty
     * list if no capture settings are not supported.
     */
    @NonNull
    public abstract List<CaptureRequest.Key> getAvailableCaptureRequestKeys(
            @NonNull String cameraId);

    /**
     * Returns a list of supported capture result keys.
     *
     * <p>Any keys included in this list must be available as part of the
     * registered {@link CaptureCallback#onCaptureCompleted} callback.</p>
     *
     * <p>At the very minimum, it is expected that the result key list is
     * a superset of the capture request keys.</p>
     *
     * @param cameraId           The camera2 id string of the camera.
     * @return The list of supported capture result keys, or
     * empty list if capture results are not supported.
     */
    @NonNull
    public abstract List<CaptureResult.Key> getAvailableCaptureResultKeys(
            @NonNull String cameraId);

    /**
     * Returns a list of {@link CameraCharacteristics} key/value pairs for apps to use when
     * querying the Extensions specific {@link CameraCharacteristics}.
     *
     * <p>To ensure the correct {@link CameraCharacteristics} are used when an extension is
     * enabled, an application should prioritize the value returned from the list if the
     * {@link CameraCharacteristics} key is present. If the key doesn't exist in the returned list,
     * then the application should query the value using
     * {@link CameraCharacteristics#get(CameraCharacteristics.Key)}.
     *
     * <p>For example, an extension may limit the zoom ratio range. In this case, an OEM can return
     * a new zoom ratio range for the key {@link CameraCharacteristics#CONTROL_ZOOM_RATIO_RANGE}.
     *
     * <p> Currently, the only synthetic keys supported for override are
     * {@link CameraCharacteristics#REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES} and
     * {@link CameraCharacteristics#REQUEST_AVAILABLE_COLOR_SPACE_PROFILES}. To enable them, an OEM
     * should override the respective native keys
     * {@link CameraCharacteristics#REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES_MAP} and
     *  {@link CameraCharacteristics#REQUEST_AVAILABLE_COLOR_SPACE_PROFILES_MAP}.
     */
    @NonNull
    public abstract List<Pair<CameraCharacteristics.Key, Object>>
            getAvailableCharacteristicsKeyValues();

    private final class AdvancedExtenderImpl extends IAdvancedExtenderImpl.Stub {
        @Override
        public boolean isExtensionAvailable(String cameraId,
                Map<String, CameraMetadataNative> charsMapNative) {
            return AdvancedExtender.this.isExtensionAvailable(cameraId,
                    new CharacteristicsMap(charsMapNative));
        }

        @Override
        public void init(String cameraId, Map<String, CameraMetadataNative> charsMapNative) {
            AdvancedExtender.this.initialize(cameraId, new CharacteristicsMap(charsMapNative));
        }

        @Override
        public List<SizeList> getSupportedPostviewResolutions(
                android.hardware.camera2.extension.Size captureSize) {
            // Feature is currently unsupported
            return null;
        }

        @Override
        public List<SizeList> getSupportedPreviewOutputResolutions(String cameraId) {
                return initializeParcelable(
                        AdvancedExtender.this.getSupportedPreviewOutputResolutions(cameraId));
        }

        @Override
        public List<SizeList> getSupportedCaptureOutputResolutions(String cameraId) {
            return initializeParcelable(
                    AdvancedExtender.this.getSupportedCaptureOutputResolutions(cameraId));
        }

        @Override
        public LatencyRange getEstimatedCaptureLatencyRange(String cameraId,
                android.hardware.camera2.extension.Size outputSize, int format) {
            // Feature is currently unsupported
            return null;
        }

        @Override
        public ISessionProcessorImpl getSessionProcessor() {
            SessionProcessor processor =AdvancedExtender.this.getSessionProcessor();
            processor.setCameraUsageTracker(mCameraUsageTracker);
            return processor.getSessionProcessorBinder();
        }

        @Override
        public CameraMetadataNative getAvailableCaptureRequestKeys(String cameraId) {
            List<CaptureRequest.Key> supportedCaptureKeys =
                    AdvancedExtender.this.getAvailableCaptureRequestKeys(cameraId);

            if (!supportedCaptureKeys.isEmpty()) {
                CameraMetadataNative ret = new CameraMetadataNative();
                long vendorId = getMetadataVendorId(cameraId);
                ret.setVendorId(vendorId);
                int requestKeyTags[] = new int[supportedCaptureKeys.size()];
                int i = 0;
                for (CaptureRequest.Key key : supportedCaptureKeys) {
                    requestKeyTags[i++] = CameraMetadataNative.getTag(key.getName(), vendorId);
                }
                ret.set(CameraCharacteristics.REQUEST_AVAILABLE_REQUEST_KEYS, requestKeyTags);

                return ret;
            }

            return null;
        }

        @Override
        public CameraMetadataNative getAvailableCaptureResultKeys(String cameraId) {
            List<CaptureResult.Key> supportedResultKeys =
                    AdvancedExtender.this.getAvailableCaptureResultKeys(cameraId);

            if (!supportedResultKeys.isEmpty()) {
                CameraMetadataNative ret = new CameraMetadataNative();
                long vendorId = getMetadataVendorId(cameraId);
                ret.setVendorId(vendorId);
                int resultKeyTags [] = new int[supportedResultKeys.size()];
                int i = 0;
                for (CaptureResult.Key key : supportedResultKeys) {
                    resultKeyTags[i++] = CameraMetadataNative.getTag(key.getName(), vendorId);
                }
                ret.set(CameraCharacteristics.REQUEST_AVAILABLE_RESULT_KEYS, resultKeyTags);

                return ret;
            }

            return null;
        }

        @Override
        public boolean isCaptureProcessProgressAvailable() {
            // Feature is currently unsupported
            return false;
        }

        @Override
        public boolean isPostviewAvailable() {
            // Feature is currently unsupported
            return false;
        }

        @Override
        public CameraMetadataNative getAvailableCharacteristicsKeyValues(String cameraId) {
            List<Pair<CameraCharacteristics.Key, Object>> entries =
                    AdvancedExtender.this.getAvailableCharacteristicsKeyValues();

            if ((entries != null) && !entries.isEmpty()) {
                CameraMetadataNative ret = new CameraMetadataNative();
                long vendorId = mMetadataVendorIdMap.containsKey(cameraId)
                        ? mMetadataVendorIdMap.get(cameraId) : Long.MAX_VALUE;
                ret.setVendorId(vendorId);
                int[] characteristicsKeyTags = new int[entries.size()];
                int i = 0;
                for (Pair<CameraCharacteristics.Key, Object> entry : entries) {
                    int tag = CameraMetadataNative.getTag(entry.first.getName(), vendorId);
                    characteristicsKeyTags[i++] = tag;
                    ret.set(entry.first, entry.second);
                }
                ret.set(CameraCharacteristics.REQUEST_AVAILABLE_CHARACTERISTICS_KEYS,
                        characteristicsKeyTags);

                return ret;
            }

            return null;
        }
    }

    @NonNull IAdvancedExtenderImpl getAdvancedExtenderBinder() {
        return new AdvancedExtenderImpl();
    }

    private static List<SizeList> initializeParcelable(
            Map<Integer, List<android.util.Size>> sizes) {
        if (sizes == null) {
            return null;
        }
        ArrayList<SizeList> ret = new ArrayList<>(sizes.size());
        for (Map.Entry<Integer, List<android.util.Size>> entry : sizes.entrySet()) {
            SizeList sizeList = new SizeList();
            sizeList.format = entry.getKey();
            sizeList.sizes = new ArrayList<>();
            for (android.util.Size size : entry.getValue()) {
                android.hardware.camera2.extension.Size sz =
                        new android.hardware.camera2.extension.Size();
                sz.width = size.getWidth();
                sz.height = size.getHeight();
                sizeList.sizes.add(sz);
            }
            ret.add(sizeList);
        }

        return ret;
    }
}
