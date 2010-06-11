/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.hardware;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.StringTokenizer;
import java.io.IOException;

import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.graphics.ImageFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

/**
 * The Camera class is used to connect/disconnect with the camera service,
 * set capture settings, start/stop preview, snap a picture, and retrieve
 * frames for encoding for video.
 * <p>There is no default constructor for this class. Use {@link #open()} to
 * get a Camera object.</p>
 *
 * <p>In order to use the device camera, you must declare the
 * {@link android.Manifest.permission#CAMERA} permission in your Android
 * Manifest. Also be sure to include the
 * <a href="{@docRoot}guide/topics/manifest/uses-feature-element.html">&lt;uses-feature></a>
 * manifest element in order to declare camera features used by your application.
 * For example, if you use the camera and auto-focus feature, your Manifest
 * should include the following:</p>
 * <pre> &lt;uses-permission android:name="android.permission.CAMERA" />
 * &lt;uses-feature android:name="android.hardware.camera" />
 * &lt;uses-feature android:name="android.hardware.camera.autofocus" /></pre>
 *
 * <p class="caution"><strong>Caution:</strong> Different Android-powered devices
 * may have different hardware specifications, such as megapixel ratings and
 * auto-focus capabilities. In order for your application to be compatible with
 * more devices, you should not make assumptions about the device camera
 * specifications.</p>
 */
public class Camera {
    private static final String TAG = "Camera";

    // These match the enums in frameworks/base/include/camera/Camera.h
    private static final int CAMERA_MSG_ERROR            = 0x001;
    private static final int CAMERA_MSG_SHUTTER          = 0x002;
    private static final int CAMERA_MSG_FOCUS            = 0x004;
    private static final int CAMERA_MSG_ZOOM             = 0x008;
    private static final int CAMERA_MSG_PREVIEW_FRAME    = 0x010;
    private static final int CAMERA_MSG_VIDEO_FRAME      = 0x020;
    private static final int CAMERA_MSG_POSTVIEW_FRAME   = 0x040;
    private static final int CAMERA_MSG_RAW_IMAGE        = 0x080;
    private static final int CAMERA_MSG_COMPRESSED_IMAGE = 0x100;
    private static final int CAMERA_MSG_ALL_MSGS         = 0x1FF;

    private int mNativeContext; // accessed by native methods
    private EventHandler mEventHandler;
    private ShutterCallback mShutterCallback;
    private PictureCallback mRawImageCallback;
    private PictureCallback mJpegCallback;
    private PreviewCallback mPreviewCallback;
    private PictureCallback mPostviewCallback;
    private AutoFocusCallback mAutoFocusCallback;
    private OnZoomChangeListener mZoomListener;
    private ErrorCallback mErrorCallback;
    private boolean mOneShot;
    private boolean mWithBuffer;

    /**
     * Returns the number of Cameras available.
     * @hide
     */
    public native static int getNumberOfCameras();

    /**
     * Returns a new Camera object.
     * If {@link #getNumberOfCameras()} returns N, the valid is is 0 to N-1.
     * The id 0 is the default camera.
     * @hide
     */
    public static Camera open(int cameraId) {
        return new Camera(cameraId);
    }

    /**
     * Returns a new Camera object. This returns the default camera.
     */
    public static Camera open() {
        return new Camera(0);
    }

    Camera(int cameraId) {
        mShutterCallback = null;
        mRawImageCallback = null;
        mJpegCallback = null;
        mPreviewCallback = null;
        mPostviewCallback = null;
        mZoomListener = null;

        Looper looper;
        if ((looper = Looper.myLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else if ((looper = Looper.getMainLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else {
            mEventHandler = null;
        }

        native_setup(new WeakReference<Camera>(this), cameraId);
    }

    protected void finalize() {
        native_release();
    }

    private native final void native_setup(Object camera_this, int cameraId);
    private native final void native_release();


    /**
     * Disconnects and releases the Camera object resources.
     * <p>It is recommended that you call this as soon as you're done with the
     * Camera object.</p>
     */
    public final void release() {
        native_release();
    }

    /**
     * Reconnect to the camera after passing it to MediaRecorder. To save
     * setup/teardown time, a client of Camera can pass an initialized Camera
     * object to a MediaRecorder to use for video recording. Once the
     * MediaRecorder is done with the Camera, this method can be used to
     * re-establish a connection with the camera hardware. NOTE: The Camera
     * object must first be unlocked by the process that owns it before it
     * can be connected to another process.
     *
     * @throws IOException if the method fails.
     */
    public native final void reconnect() throws IOException;

    /**
     * Lock the camera to prevent other processes from accessing it. To save
     * setup/teardown time, a client of Camera can pass an initialized Camera
     * object to another process. This method is used to re-lock the Camera
     * object prevent other processes from accessing it. By default, the
     * Camera object is locked. Locking it again from the same process will
     * have no effect. Attempting to lock it from another process if it has
     * not been unlocked will fail.
     *
     * @throws RuntimeException if the method fails.
     */
    public native final void lock();

    /**
     * Unlock the camera to allow another process to access it. To save
     * setup/teardown time, a client of Camera can pass an initialized Camera
     * object to another process. This method is used to unlock the Camera
     * object before handing off the Camera object to the other process.
     *
     * @throws RuntimeException if the method fails.
     */
    public native final void unlock();

    /**
     * Sets the SurfaceHolder to be used for a picture preview. If the surface
     * changed since the last call, the screen will blank. Nothing happens
     * if the same surface is re-set.
     *
     * @param holder the SurfaceHolder upon which to place the picture preview
     * @throws IOException if the method fails.
     */
    public final void setPreviewDisplay(SurfaceHolder holder) throws IOException {
        if (holder != null) {
            setPreviewDisplay(holder.getSurface());
        } else {
            setPreviewDisplay((Surface)null);
        }
    }

    private native final void setPreviewDisplay(Surface surface);

    /**
     * Used to get a copy of each preview frame.
     */
    public interface PreviewCallback
    {
        /**
         * The callback that delivers the preview frames.
         *
         * @param data The contents of the preview frame in the format defined
         *  by {@link android.graphics.ImageFormat}, which can be queried
         *  with {@link android.hardware.Camera.Parameters#getPreviewFormat()}.
         *  If {@link android.hardware.Camera.Parameters#setPreviewFormat(int)}
         *             is never called, the default will be the YCbCr_420_SP
         *             (NV21) format.
         * @param camera The Camera service object.
         */
        void onPreviewFrame(byte[] data, Camera camera);
    };

    /**
     * Start drawing preview frames to the surface.
     */
    public native final void startPreview();

    /**
     * Stop drawing preview frames to the surface.
     */
    public native final void stopPreview();

    /**
     * Return current preview state.
     *
     * FIXME: Unhide before release
     * @hide
     */
    public native final boolean previewEnabled();

    /**
     * Can be called at any time to instruct the camera to use a callback for
     * each preview frame in addition to displaying it.
     *
     * @param cb A callback object that receives a copy of each preview frame.
     *           Pass null to stop receiving callbacks at any time.
     */
    public final void setPreviewCallback(PreviewCallback cb) {
        mPreviewCallback = cb;
        mOneShot = false;
        mWithBuffer = false;
        // Always use one-shot mode. We fake camera preview mode by
        // doing one-shot preview continuously.
        setHasPreviewCallback(cb != null, false);
    }

    /**
     * Installs a callback to retrieve a single preview frame, after which the
     * callback is cleared.
     *
     * @param cb A callback object that receives a copy of the preview frame.
     */
    public final void setOneShotPreviewCallback(PreviewCallback cb) {
        mPreviewCallback = cb;
        mOneShot = true;
        mWithBuffer = false;
        setHasPreviewCallback(cb != null, false);
    }

    private native final void setHasPreviewCallback(boolean installed, boolean manualBuffer);

    /**
     * Installs a callback which will get called as long as there are buffers in the
     * preview buffer queue, which minimizes dynamic allocation of preview buffers.
     *
     * Apps must call addCallbackBuffer to explicitly register the buffers to use, or no callbacks
     * will be received. addCallbackBuffer may be safely called before or after
     * a call to setPreviewCallbackWithBuffer with a non-null callback parameter.
     *
     * The buffer queue will be cleared upon any calls to setOneShotPreviewCallback,
     * setPreviewCallback, or to this method with a null callback parameter.
     *
     * @param cb A callback object that receives a copy of the preview frame.  A null value will clear the queue.
     */
    public final void setPreviewCallbackWithBuffer(PreviewCallback cb) {
        mPreviewCallback = cb;
        mOneShot = false;
        mWithBuffer = true;
        setHasPreviewCallback(cb != null, true);
    }

    /**
     * Adds a pre-allocated buffer to the preview callback buffer queue.
     * Applications can add one or more buffers to the queue. When a preview
     * frame arrives and there is still available buffer, buffer will be filled
     * and it is removed from the queue. Then preview callback is invoked with
     * the buffer. If a frame arrives and there is no buffer left, the frame is
     * discarded. Applications should add the buffers back when they finish the
     * processing.
     *
     * The image format of the callback buffer can be read from {@link
     * android.hardware.Camera.Parameters#getPreviewFormat()}. bitsPerPixel can
     * be read from {@link android.graphics.ImageFormat#getBitsPerPixel(int)}.
     * Preview width and height can be determined from getPreviewSize.
     *
     * Alternatively, a buffer from a previous callback may be passed in or used
     * to determine the size of new preview frame buffers.
     *
     * @param callbackBuffer The buffer to register. Size should be width * height * bitsPerPixel / 8.
     * @see #setPreviewCallbackWithBuffer(PreviewCallback)
     */
    public native final void addCallbackBuffer(byte[] callbackBuffer);

    private class EventHandler extends Handler
    {
        private Camera mCamera;

        public EventHandler(Camera c, Looper looper) {
            super(looper);
            mCamera = c;
        }

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
            case CAMERA_MSG_SHUTTER:
                if (mShutterCallback != null) {
                    mShutterCallback.onShutter();
                }
                return;

            case CAMERA_MSG_RAW_IMAGE:
                if (mRawImageCallback != null) {
                    mRawImageCallback.onPictureTaken((byte[])msg.obj, mCamera);
                }
                return;

            case CAMERA_MSG_COMPRESSED_IMAGE:
                if (mJpegCallback != null) {
                    mJpegCallback.onPictureTaken((byte[])msg.obj, mCamera);
                }
                return;

            case CAMERA_MSG_PREVIEW_FRAME:
                if (mPreviewCallback != null) {
                    PreviewCallback cb = mPreviewCallback;
                    if (mOneShot) {
                        // Clear the callback variable before the callback
                        // in case the app calls setPreviewCallback from
                        // the callback function
                        mPreviewCallback = null;
                    } else if (!mWithBuffer) {
                        // We're faking the camera preview mode to prevent
                        // the app from being flooded with preview frames.
                        // Set to oneshot mode again.
                        setHasPreviewCallback(true, false);
                    }
                    cb.onPreviewFrame((byte[])msg.obj, mCamera);
                }
                return;

            case CAMERA_MSG_POSTVIEW_FRAME:
                if (mPostviewCallback != null) {
                    mPostviewCallback.onPictureTaken((byte[])msg.obj, mCamera);
                }
                return;

            case CAMERA_MSG_FOCUS:
                if (mAutoFocusCallback != null) {
                    mAutoFocusCallback.onAutoFocus(msg.arg1 == 0 ? false : true, mCamera);
                }
                return;

            case CAMERA_MSG_ZOOM:
                if (mZoomListener != null) {
                    mZoomListener.onZoomChange(msg.arg1, msg.arg2 != 0, mCamera);
                }
                return;

            case CAMERA_MSG_ERROR :
                Log.e(TAG, "Error " + msg.arg1);
                if (mErrorCallback != null) {
                    mErrorCallback.onError(msg.arg1, mCamera);
                }
                return;

            default:
                Log.e(TAG, "Unknown message type " + msg.what);
                return;
            }
        }
    }

    private static void postEventFromNative(Object camera_ref,
                                            int what, int arg1, int arg2, Object obj)
    {
        Camera c = (Camera)((WeakReference)camera_ref).get();
        if (c == null)
            return;

        if (c.mEventHandler != null) {
            Message m = c.mEventHandler.obtainMessage(what, arg1, arg2, obj);
            c.mEventHandler.sendMessage(m);
        }
    }

    /**
     * Handles the callback for the camera auto focus.
     * <p>Devices that do not support auto-focus will receive a "fake"
     * callback to this interface. If your application needs auto-focus and
     * should not be installed on devices <em>without</em> auto-focus, you must
     * declare that your app uses the
     * {@code android.hardware.camera.autofocus} feature, in the
     * <a href="{@docRoot}guide/topics/manifest/uses-feature-element.html">&lt;uses-feature></a>
     * manifest element.</p>
     */
    public interface AutoFocusCallback
    {
        /**
         * Callback for the camera auto focus. If the camera does not support
         * auto-focus and autoFocus is called, onAutoFocus will be called
         * immediately with success.
         *
         * @param success true if focus was successful, false if otherwise
         * @param camera  the Camera service object
         */
        void onAutoFocus(boolean success, Camera camera);
    };

    /**
     * Starts auto-focus function and registers a callback function to run when
     * camera is focused. Only valid after startPreview() has been called.
     * Applications should call {@link
     * android.hardware.Camera.Parameters#getFocusMode()} to determine if this
     * method should be called. If the camera does not support auto-focus, it is
     * a no-op and {@link AutoFocusCallback#onAutoFocus(boolean, Camera)}
     * callback will be called immediately.
     * <p>If your application should not be installed
     * on devices without auto-focus, you must declare that your application
     * uses auto-focus with the
     * <a href="{@docRoot}guide/topics/manifest/uses-feature-element.html">&lt;uses-feature></a>
     * manifest element.</p>
     * <p>If the current flash mode is not
     * {@link android.hardware.Camera.Parameters#FLASH_MODE_OFF}, flash may be
     * fired during auto-focus depending on the driver.<p>
     *
     * @param cb the callback to run
     */
    public final void autoFocus(AutoFocusCallback cb)
    {
        mAutoFocusCallback = cb;
        native_autoFocus();
    }
    private native final void native_autoFocus();

    /**
     * Cancels auto-focus function. If the auto-focus is still in progress,
     * this function will cancel it. Whether the auto-focus is in progress
     * or not, this function will return the focus position to the default.
     * If the camera does not support auto-focus, this is a no-op.
     */
    public final void cancelAutoFocus()
    {
        mAutoFocusCallback = null;
        native_cancelAutoFocus();
    }
    private native final void native_cancelAutoFocus();

    /**
     * An interface which contains a callback for the shutter closing after taking a picture.
     */
    public interface ShutterCallback
    {
        /**
         * Can be used to play a shutter sound as soon as the image has been captured, but before
         * the data is available.
         */
        void onShutter();
    }

    /**
     * Handles the callback for when a picture is taken.
     */
    public interface PictureCallback {
        /**
         * Callback for when a picture is taken.
         *
         * @param data   a byte array of the picture data
         * @param camera the Camera service object
         */
        void onPictureTaken(byte[] data, Camera camera);
    };

    /**
     * Triggers an asynchronous image capture. The camera service will initiate
     * a series of callbacks to the application as the image capture progresses.
     * The shutter callback occurs after the image is captured. This can be used
     * to trigger a sound to let the user know that image has been captured. The
     * raw callback occurs when the raw image data is available (NOTE: the data
     * may be null if the hardware does not have enough memory to make a copy).
     * The jpeg callback occurs when the compressed image is available. If the
     * application does not need a particular callback, a null can be passed
     * instead of a callback method.
     *
     * This method will stop the preview. Applications should not call {@link
     * #stopPreview()} before this. After jpeg callback is received,
     * applications can call {@link #startPreview()} to restart the preview.
     *
     * @param shutter   callback after the image is captured, may be null
     * @param raw       callback with raw image data, may be null
     * @param jpeg      callback with jpeg image data, may be null
     */
    public final void takePicture(ShutterCallback shutter, PictureCallback raw,
            PictureCallback jpeg) {
        takePicture(shutter, raw, null, jpeg);
    }
    private native final void native_takePicture();

    /**
     * Triggers an asynchronous image capture. The camera service will initiate
     * a series of callbacks to the application as the image capture progresses.
     * The shutter callback occurs after the image is captured. This can be used
     * to trigger a sound to let the user know that image has been captured. The
     * raw callback occurs when the raw image data is available (NOTE: the data
     * may be null if the hardware does not have enough memory to make a copy).
     * The postview callback occurs when a scaled, fully processed postview
     * image is available (NOTE: not all hardware supports this). The jpeg
     * callback occurs when the compressed image is available. If the
     * application does not need a particular callback, a null can be passed
     * instead of a callback method.
     *
     * This method will stop the preview. Applications should not call {@link
     * #stopPreview()} before this. After jpeg callback is received,
     * applications can call {@link #startPreview()} to restart the preview.
     *
     * @param shutter   callback after the image is captured, may be null
     * @param raw       callback with raw image data, may be null
     * @param postview  callback with postview image data, may be null
     * @param jpeg      callback with jpeg image data, may be null
     */
    public final void takePicture(ShutterCallback shutter, PictureCallback raw,
            PictureCallback postview, PictureCallback jpeg) {
        mShutterCallback = shutter;
        mRawImageCallback = raw;
        mPostviewCallback = postview;
        mJpegCallback = jpeg;
        native_takePicture();
    }

    /**
     * Zooms to the requested value smoothly. Driver will notify {@link
     * OnZoomChangeListener} of the zoom value and whether zoom is stopped at
     * the time. For example, suppose the current zoom is 0 and startSmoothZoom
     * is called with value 3. Method onZoomChange will be called three times
     * with zoom value 1, 2, and 3. The applications can call {@link
     * #stopSmoothZoom} to stop the zoom earlier. The applications should not
     * call startSmoothZoom again or change the zoom value before zoom stops. If
     * the passing zoom value equals to the current zoom value, no zoom callback
     * will be generated. This method is supported if {@link
     * android.hardware.Camera.Parameters#isSmoothZoomSupported} is true.
     *
     * @param value zoom value. The valid range is 0 to {@link
     *              android.hardware.Camera.Parameters#getMaxZoom}.
     * @throws IllegalArgumentException if the zoom value is invalid.
     * @throws RuntimeException if the method fails.
     */
    public native final void startSmoothZoom(int value);

    /**
     * Stops the smooth zoom. The applications should wait for the {@link
     * OnZoomChangeListener} to know when the zoom is actually stopped. This
     * method is supported if {@link
     * android.hardware.Camera.Parameters#isSmoothZoomSupported} is true.
     *
     * @throws RuntimeException if the method fails.
     */
    public native final void stopSmoothZoom();

    /**
     * Set the display orientation. This affects the preview frames and the
     * picture displayed after snapshot. This method is useful for portrait
     * mode applications.
     *
     * This does not affect the order of byte array passed in
     * {@link PreviewCallback#onPreviewFrame}. This method is not allowed to
     * be called during preview.
     *
     * @param degrees the angle that the picture will be rotated clockwise.
     *                Valid values are 0, 90, 180, and 270. The starting
     *                position is 0 (landscape).
     */
    public native final void setDisplayOrientation(int degrees);

    /**
     * Interface for a callback to be invoked when zoom value changes.
     */
    public interface OnZoomChangeListener
    {
        /**
         * Called when the zoom value has changed.
         *
         * @param zoomValue the current zoom value. In smooth zoom mode, camera
         *                  calls this for every new zoom value.
         * @param stopped whether smooth zoom is stopped. If the value is true,
         *                this is the last zoom update for the application.
         *
         * @param camera  the Camera service object
         * @see #startSmoothZoom(int)
         */
        void onZoomChange(int zoomValue, boolean stopped, Camera camera);
    };

    /**
     * Registers a listener to be notified when the zoom value is updated by the
     * camera driver during smooth zoom.
     *
     * @param listener the listener to notify
     * @see #startSmoothZoom(int)
     */
    public final void setZoomChangeListener(OnZoomChangeListener listener)
    {
        mZoomListener = listener;
    }

    // These match the enum in include/ui/Camera.h
    /** Unspecified camerar error.  @see #ErrorCallback */
    public static final int CAMERA_ERROR_UNKNOWN = 1;
    /** Media server died. In this case, the application must release the
     * Camera object and instantiate a new one. @see #ErrorCallback */
    public static final int CAMERA_ERROR_SERVER_DIED = 100;

    /**
     * Handles the camera error callback.
     */
    public interface ErrorCallback
    {
        /**
         * Callback for camera errors.
         * @param error   error code:
         * <ul>
         * <li>{@link #CAMERA_ERROR_UNKNOWN}
         * <li>{@link #CAMERA_ERROR_SERVER_DIED}
         * </ul>
         * @param camera  the Camera service object
         */
        void onError(int error, Camera camera);
    };

    /**
     * Registers a callback to be invoked when an error occurs.
     * @param cb the callback to run
     */
    public final void setErrorCallback(ErrorCallback cb)
    {
        mErrorCallback = cb;
    }

    private native final void native_setParameters(String params);
    private native final String native_getParameters();

    /**
     * Sets the Parameters for pictures from this Camera service.
     *
     * @param params the Parameters to use for this Camera service
     */
    public void setParameters(Parameters params) {
        native_setParameters(params.flatten());
    }

    /**
     * Returns the picture Parameters for this Camera service.
     */
    public Parameters getParameters() {
        Parameters p = new Parameters();
        String s = native_getParameters();
        p.unflatten(s);
        return p;
    }

    /**
     * Handles the picture size (dimensions).
     */
    public class Size {
        /**
         * Sets the dimensions for pictures.
         *
         * @param w the photo width (pixels)
         * @param h the photo height (pixels)
         */
        public Size(int w, int h) {
            width = w;
            height = h;
        }
        /**
         * Compares {@code obj} to this size.
         *
         * @param obj the object to compare this size with.
         * @return {@code true} if the width and height of {@code obj} is the
         *         same as those of this size. {@code false} otherwise.
         */
        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Size)) {
                return false;
            }
            Size s = (Size) obj;
            return width == s.width && height == s.height;
        }
        @Override
        public int hashCode() {
            return width * 32713 + height;
        }
        /** width of the picture */
        public int width;
        /** height of the picture */
        public int height;
    };

    /**
     * Handles the parameters for pictures created by a Camera service.
     *
     * <p>To make camera parameters take effect, applications have to call
     * Camera.setParameters. For example, after setWhiteBalance is called, white
     * balance is not changed until Camera.setParameters() is called.
     *
     * <p>Different devices may have different camera capabilities, such as
     * picture size or flash modes. The application should query the camera
     * capabilities before setting parameters. For example, the application
     * should call getSupportedColorEffects before calling setEffect. If the
     * camera does not support color effects, getSupportedColorEffects will
     * return null.
     */
    public class Parameters {
        // Parameter keys to communicate with the camera driver.
        private static final String KEY_PREVIEW_SIZE = "preview-size";
        private static final String KEY_PREVIEW_FORMAT = "preview-format";
        private static final String KEY_PREVIEW_FRAME_RATE = "preview-frame-rate";
        private static final String KEY_PICTURE_SIZE = "picture-size";
        private static final String KEY_PICTURE_FORMAT = "picture-format";
        private static final String KEY_JPEG_THUMBNAIL_SIZE = "jpeg-thumbnail-size";
        private static final String KEY_JPEG_THUMBNAIL_WIDTH = "jpeg-thumbnail-width";
        private static final String KEY_JPEG_THUMBNAIL_HEIGHT = "jpeg-thumbnail-height";
        private static final String KEY_JPEG_THUMBNAIL_QUALITY = "jpeg-thumbnail-quality";
        private static final String KEY_JPEG_QUALITY = "jpeg-quality";
        private static final String KEY_ROTATION = "rotation";
        private static final String KEY_GPS_LATITUDE = "gps-latitude";
        private static final String KEY_GPS_LONGITUDE = "gps-longitude";
        private static final String KEY_GPS_ALTITUDE = "gps-altitude";
        private static final String KEY_GPS_TIMESTAMP = "gps-timestamp";
        private static final String KEY_GPS_PROCESSING_METHOD = "gps-processing-method";
        private static final String KEY_WHITE_BALANCE = "whitebalance";
        private static final String KEY_EFFECT = "effect";
        private static final String KEY_ANTIBANDING = "antibanding";
        private static final String KEY_SCENE_MODE = "scene-mode";
        private static final String KEY_FLASH_MODE = "flash-mode";
        private static final String KEY_FOCUS_MODE = "focus-mode";
        private static final String KEY_FOCAL_LENGTH = "focal-length";
        private static final String KEY_HORIZONTAL_VIEW_ANGLE = "horizontal-view-angle";
        private static final String KEY_VERTICAL_VIEW_ANGLE = "vertical-view-angle";
        private static final String KEY_EXPOSURE_COMPENSATION = "exposure-compensation";
        private static final String KEY_MAX_EXPOSURE_COMPENSATION = "max-exposure-compensation";
        private static final String KEY_MIN_EXPOSURE_COMPENSATION = "min-exposure-compensation";
        private static final String KEY_EXPOSURE_COMPENSATION_STEP = "exposure-compensation-step";
        private static final String KEY_ZOOM = "zoom";
        private static final String KEY_MAX_ZOOM = "max-zoom";
        private static final String KEY_ZOOM_RATIOS = "zoom-ratios";
        private static final String KEY_ZOOM_SUPPORTED = "zoom-supported";
        private static final String KEY_SMOOTH_ZOOM_SUPPORTED = "smooth-zoom-supported";
        private static final String KEY_FOCUS_DISTANCES = "focus-distances";
        private static final String KEY_METERING_MODE = "metering-mode";

        // Parameter key suffix for supported values.
        private static final String SUPPORTED_VALUES_SUFFIX = "-values";

        private static final String TRUE = "true";

        // Values for white balance settings.
        public static final String WHITE_BALANCE_AUTO = "auto";
        public static final String WHITE_BALANCE_INCANDESCENT = "incandescent";
        public static final String WHITE_BALANCE_FLUORESCENT = "fluorescent";
        public static final String WHITE_BALANCE_WARM_FLUORESCENT = "warm-fluorescent";
        public static final String WHITE_BALANCE_DAYLIGHT = "daylight";
        public static final String WHITE_BALANCE_CLOUDY_DAYLIGHT = "cloudy-daylight";
        public static final String WHITE_BALANCE_TWILIGHT = "twilight";
        public static final String WHITE_BALANCE_SHADE = "shade";

        // Values for color effect settings.
        public static final String EFFECT_NONE = "none";
        public static final String EFFECT_MONO = "mono";
        public static final String EFFECT_NEGATIVE = "negative";
        public static final String EFFECT_SOLARIZE = "solarize";
        public static final String EFFECT_SEPIA = "sepia";
        public static final String EFFECT_POSTERIZE = "posterize";
        public static final String EFFECT_WHITEBOARD = "whiteboard";
        public static final String EFFECT_BLACKBOARD = "blackboard";
        public static final String EFFECT_AQUA = "aqua";

        // Values for antibanding settings.
        public static final String ANTIBANDING_AUTO = "auto";
        public static final String ANTIBANDING_50HZ = "50hz";
        public static final String ANTIBANDING_60HZ = "60hz";
        public static final String ANTIBANDING_OFF = "off";

        // Values for flash mode settings.
        /**
         * Flash will not be fired.
         */
        public static final String FLASH_MODE_OFF = "off";

        /**
         * Flash will be fired automatically when required. The flash may be fired
         * during preview, auto-focus, or snapshot depending on the driver.
         */
        public static final String FLASH_MODE_AUTO = "auto";

        /**
         * Flash will always be fired during snapshot. The flash may also be
         * fired during preview or auto-focus depending on the driver.
         */
        public static final String FLASH_MODE_ON = "on";

        /**
         * Flash will be fired in red-eye reduction mode.
         */
        public static final String FLASH_MODE_RED_EYE = "red-eye";

        /**
         * Constant emission of light during preview, auto-focus and snapshot.
         * This can also be used for video recording.
         */
        public static final String FLASH_MODE_TORCH = "torch";

        /**
         * Scene mode is off.
         */
        public static final String SCENE_MODE_AUTO = "auto";

        /**
         * Take photos of fast moving objects. Same as {@link
         * #SCENE_MODE_SPORTS}.
         */
        public static final String SCENE_MODE_ACTION = "action";

        /**
         * Take people pictures.
         */
        public static final String SCENE_MODE_PORTRAIT = "portrait";

        /**
         * Take pictures on distant objects.
         */
        public static final String SCENE_MODE_LANDSCAPE = "landscape";

        /**
         * Take photos at night.
         */
        public static final String SCENE_MODE_NIGHT = "night";

        /**
         * Take people pictures at night.
         */
        public static final String SCENE_MODE_NIGHT_PORTRAIT = "night-portrait";

        /**
         * Take photos in a theater. Flash light is off.
         */
        public static final String SCENE_MODE_THEATRE = "theatre";

        /**
         * Take pictures on the beach.
         */
        public static final String SCENE_MODE_BEACH = "beach";

        /**
         * Take pictures on the snow.
         */
        public static final String SCENE_MODE_SNOW = "snow";

        /**
         * Take sunset photos.
         */
        public static final String SCENE_MODE_SUNSET = "sunset";

        /**
         * Avoid blurry pictures (for example, due to hand shake).
         */
        public static final String SCENE_MODE_STEADYPHOTO = "steadyphoto";

        /**
         * For shooting firework displays.
         */
        public static final String SCENE_MODE_FIREWORKS = "fireworks";

        /**
         * Take photos of fast moving objects. Same as {@link
         * #SCENE_MODE_ACTION}.
         */
        public static final String SCENE_MODE_SPORTS = "sports";

        /**
         * Take indoor low-light shot.
         */
        public static final String SCENE_MODE_PARTY = "party";

        /**
         * Capture the naturally warm color of scenes lit by candles.
         */
        public static final String SCENE_MODE_CANDLELIGHT = "candlelight";

        /**
         * Applications are looking for a barcode. Camera driver will be
         * optimized for barcode reading.
         */
        public static final String SCENE_MODE_BARCODE = "barcode";

        // Values for focus mode settings.
        /**
         * Auto-focus mode.
         */
        public static final String FOCUS_MODE_AUTO = "auto";

        /**
         * Focus is set at infinity. Applications should not call
         * {@link #autoFocus(AutoFocusCallback)} in this mode.
         */
        public static final String FOCUS_MODE_INFINITY = "infinity";
        public static final String FOCUS_MODE_MACRO = "macro";

        /**
         * Focus is fixed. The camera is always in this mode if the focus is not
         * adjustable. If the camera has auto-focus, this mode can fix the
         * focus, which is usually at hyperfocal distance. Applications should
         * not call {@link #autoFocus(AutoFocusCallback)} in this mode.
         */
        public static final String FOCUS_MODE_FIXED = "fixed";

        /**
         * Extended depth of field (EDOF). Focusing is done digitally and
         * continuously. Applications should not call {@link
         * #autoFocus(AutoFocusCallback)} in this mode.
         */
        public static final String FOCUS_MODE_EDOF = "edof";

        // Indices for focus distance array.
        /**
         * The array index of near focus distance for use with
         * {@link #getFocusDistances(float[])}.
         */
        public static final int FOCUS_DISTANCE_NEAR_INDEX = 0;

        /**
         * The array index of optimal focus distance for use with
         * {@link #getFocusDistances(float[])}.
         */
        public static final int FOCUS_DISTANCE_OPTIMAL_INDEX = 1;

        /**
         * The array index of far focus distance for use with
         * {@link #getFocusDistances(float[])}.
         */
        public static final int FOCUS_DISTANCE_FAR_INDEX = 2;

        /**
         * Continuous focus mode. The camera continuously tries to focus. This
         * is ideal for shooting video or shooting photo of moving object.
         * Continuous focus starts when {@link #autoFocus(AutoFocusCallback)} is
         * called. Continuous focus stops when {@link #cancelAutoFocus()} is
         * called. AutoFocusCallback will be only called once as soon as the
         * picture is in focus.
         */
        public static final String FOCUS_MODE_CONTINUOUS = "continuous";

        /**
         * The camera determines the exposure by giving more weight to the
         * central part of the scene.
         */
        public static final String METERING_MODE_CENTER_WEIGHTED = "center-weighted";

        /**
         * The camera determines the exposure by averaging the entire scene,
         * giving no weighting to any particular area.
         */
        public static final String METERING_MODE_FRAME_AVERAGE = "frame-average";

        /**
         * The camera determines the exposure by a very small area of the scene,
         * typically the center.
         */
        public static final String METERING_MODE_SPOT = "spot";

        // Formats for setPreviewFormat and setPictureFormat.
        private static final String PIXEL_FORMAT_YUV422SP = "yuv422sp";
        private static final String PIXEL_FORMAT_YUV420SP = "yuv420sp";
        private static final String PIXEL_FORMAT_YUV422I = "yuv422i-yuyv";
        private static final String PIXEL_FORMAT_RGB565 = "rgb565";
        private static final String PIXEL_FORMAT_JPEG = "jpeg";

        private HashMap<String, String> mMap;

        private Parameters() {
            mMap = new HashMap<String, String>();
        }

        /**
         * Writes the current Parameters to the log.
         * @hide
         * @deprecated
         */
        public void dump() {
            Log.e(TAG, "dump: size=" + mMap.size());
            for (String k : mMap.keySet()) {
                Log.e(TAG, "dump: " + k + "=" + mMap.get(k));
            }
        }

        /**
         * Creates a single string with all the parameters set in
         * this Parameters object.
         * <p>The {@link #unflatten(String)} method does the reverse.</p>
         *
         * @return a String with all values from this Parameters object, in
         *         semi-colon delimited key-value pairs
         */
        public String flatten() {
            StringBuilder flattened = new StringBuilder();
            for (String k : mMap.keySet()) {
                flattened.append(k);
                flattened.append("=");
                flattened.append(mMap.get(k));
                flattened.append(";");
            }
            // chop off the extra semicolon at the end
            flattened.deleteCharAt(flattened.length()-1);
            return flattened.toString();
        }

        /**
         * Takes a flattened string of parameters and adds each one to
         * this Parameters object.
         * <p>The {@link #flatten()} method does the reverse.</p>
         *
         * @param flattened a String of parameters (key-value paired) that
         *                  are semi-colon delimited
         */
        public void unflatten(String flattened) {
            mMap.clear();

            StringTokenizer tokenizer = new StringTokenizer(flattened, ";");
            while (tokenizer.hasMoreElements()) {
                String kv = tokenizer.nextToken();
                int pos = kv.indexOf('=');
                if (pos == -1) {
                    continue;
                }
                String k = kv.substring(0, pos);
                String v = kv.substring(pos + 1);
                mMap.put(k, v);
            }
        }

        public void remove(String key) {
            mMap.remove(key);
        }

        /**
         * Sets a String parameter.
         *
         * @param key   the key name for the parameter
         * @param value the String value of the parameter
         */
        public void set(String key, String value) {
            if (key.indexOf('=') != -1 || key.indexOf(';') != -1) {
                Log.e(TAG, "Key \"" + key + "\" contains invalid character (= or ;)");
                return;
            }
            if (value.indexOf('=') != -1 || value.indexOf(';') != -1) {
                Log.e(TAG, "Value \"" + value + "\" contains invalid character (= or ;)");
                return;
            }

            mMap.put(key, value);
        }

        /**
         * Sets an integer parameter.
         *
         * @param key   the key name for the parameter
         * @param value the int value of the parameter
         */
        public void set(String key, int value) {
            mMap.put(key, Integer.toString(value));
        }

        /**
         * Returns the value of a String parameter.
         *
         * @param key the key name for the parameter
         * @return the String value of the parameter
         */
        public String get(String key) {
            return mMap.get(key);
        }

        /**
         * Returns the value of an integer parameter.
         *
         * @param key the key name for the parameter
         * @return the int value of the parameter
         */
        public int getInt(String key) {
            return Integer.parseInt(mMap.get(key));
        }

        /**
         * Sets the dimensions for preview pictures.
         *
         * @param width  the width of the pictures, in pixels
         * @param height the height of the pictures, in pixels
         */
        public void setPreviewSize(int width, int height) {
            String v = Integer.toString(width) + "x" + Integer.toString(height);
            set(KEY_PREVIEW_SIZE, v);
        }

        /**
         * Returns the dimensions setting for preview pictures.
         *
         * @return a Size object with the height and width setting
         *          for the preview picture
         */
        public Size getPreviewSize() {
            String pair = get(KEY_PREVIEW_SIZE);
            return strToSize(pair);
        }

        /**
         * Gets the supported preview sizes.
         *
         * @return a list of Size object. This method will always return a list
         *         with at least one element.
         */
        public List<Size> getSupportedPreviewSizes() {
            String str = get(KEY_PREVIEW_SIZE + SUPPORTED_VALUES_SUFFIX);
            return splitSize(str);
        }

        /**
         * Sets the dimensions for EXIF thumbnail in Jpeg picture. If
         * applications set both width and height to 0, EXIF will not contain
         * thumbnail.
         *
         * @param width  the width of the thumbnail, in pixels
         * @param height the height of the thumbnail, in pixels
         */
        public void setJpegThumbnailSize(int width, int height) {
            set(KEY_JPEG_THUMBNAIL_WIDTH, width);
            set(KEY_JPEG_THUMBNAIL_HEIGHT, height);
        }

        /**
         * Returns the dimensions for EXIF thumbnail in Jpeg picture.
         *
         * @return a Size object with the height and width setting for the EXIF
         *         thumbnails
         */
        public Size getJpegThumbnailSize() {
            return new Size(getInt(KEY_JPEG_THUMBNAIL_WIDTH),
                            getInt(KEY_JPEG_THUMBNAIL_HEIGHT));
        }

        /**
         * Gets the supported jpeg thumbnail sizes.
         *
         * @return a list of Size object. This method will always return a list
         *         with at least two elements. Size 0,0 (no thumbnail) is always
         *         supported.
         */
        public List<Size> getSupportedJpegThumbnailSizes() {
            String str = get(KEY_JPEG_THUMBNAIL_SIZE + SUPPORTED_VALUES_SUFFIX);
            return splitSize(str);
        }

        /**
         * Sets the quality of the EXIF thumbnail in Jpeg picture.
         *
         * @param quality the JPEG quality of the EXIF thumbnail. The range is 1
         *                to 100, with 100 being the best.
         */
        public void setJpegThumbnailQuality(int quality) {
            set(KEY_JPEG_THUMBNAIL_QUALITY, quality);
        }

        /**
         * Returns the quality setting for the EXIF thumbnail in Jpeg picture.
         *
         * @return the JPEG quality setting of the EXIF thumbnail.
         */
        public int getJpegThumbnailQuality() {
            return getInt(KEY_JPEG_THUMBNAIL_QUALITY);
        }

        /**
         * Sets Jpeg quality of captured picture.
         *
         * @param quality the JPEG quality of captured picture. The range is 1
         *                to 100, with 100 being the best.
         */
        public void setJpegQuality(int quality) {
            set(KEY_JPEG_QUALITY, quality);
        }

        /**
         * Returns the quality setting for the JPEG picture.
         *
         * @return the JPEG picture quality setting.
         */
        public int getJpegQuality() {
            return getInt(KEY_JPEG_QUALITY);
        }

        /**
         * Sets the rate at which preview frames are received. This is the
         * target frame rate. The actual frame rate depends on the driver.
         *
         * @param fps the frame rate (frames per second)
         */
        public void setPreviewFrameRate(int fps) {
            set(KEY_PREVIEW_FRAME_RATE, fps);
        }

        /**
         * Returns the setting for the rate at which preview frames are
         * received. This is the target frame rate. The actual frame rate
         * depends on the driver.
         *
         * @return the frame rate setting (frames per second)
         */
        public int getPreviewFrameRate() {
            return getInt(KEY_PREVIEW_FRAME_RATE);
        }

        /**
         * Gets the supported preview frame rates.
         *
         * @return a list of supported preview frame rates. null if preview
         *         frame rate setting is not supported.
         */
        public List<Integer> getSupportedPreviewFrameRates() {
            String str = get(KEY_PREVIEW_FRAME_RATE + SUPPORTED_VALUES_SUFFIX);
            return splitInt(str);
        }

        /**
         * Sets the image format for preview pictures.
         * <p>If this is never called, the default format will be
         * {@link android.graphics.ImageFormat#NV21}, which
         * uses the NV21 encoding format.</p>
         *
         * @param pixel_format the desired preview picture format, defined
         *   by one of the {@link android.graphics.ImageFormat} constants.
         *   (E.g., <var>ImageFormat.NV21</var> (default),
         *                      <var>ImageFormat.RGB_565</var>, or
         *                      <var>ImageFormat.JPEG</var>)
         * @see android.graphics.ImageFormat
         */
        public void setPreviewFormat(int pixel_format) {
            String s = cameraFormatForPixelFormat(pixel_format);
            if (s == null) {
                throw new IllegalArgumentException(
                        "Invalid pixel_format=" + pixel_format);
            }

            set(KEY_PREVIEW_FORMAT, s);
        }

        /**
         * Returns the image format for preview frames got from
         * {@link PreviewCallback}.
         *
         * @return the preview format.
         * @see android.graphics.ImageFormat
         */
        public int getPreviewFormat() {
            return pixelFormatForCameraFormat(get(KEY_PREVIEW_FORMAT));
        }

        /**
         * Gets the supported preview formats.
         *
         * @return a list of supported preview formats. This method will always
         *         return a list with at least one element.
         * @see android.graphics.ImageFormat
         */
        public List<Integer> getSupportedPreviewFormats() {
            String str = get(KEY_PREVIEW_FORMAT + SUPPORTED_VALUES_SUFFIX);
            ArrayList<Integer> formats = new ArrayList<Integer>();
            for (String s : split(str)) {
                int f = pixelFormatForCameraFormat(s);
                if (f == ImageFormat.UNKNOWN) continue;
                formats.add(f);
            }
            return formats;
        }

        /**
         * Sets the dimensions for pictures.
         *
         * @param width  the width for pictures, in pixels
         * @param height the height for pictures, in pixels
         */
        public void setPictureSize(int width, int height) {
            String v = Integer.toString(width) + "x" + Integer.toString(height);
            set(KEY_PICTURE_SIZE, v);
        }

        /**
         * Returns the dimension setting for pictures.
         *
         * @return a Size object with the height and width setting
         *          for pictures
         */
        public Size getPictureSize() {
            String pair = get(KEY_PICTURE_SIZE);
            return strToSize(pair);
        }

        /**
         * Gets the supported picture sizes.
         *
         * @return a list of supported picture sizes. This method will always
         *         return a list with at least one element.
         */
        public List<Size> getSupportedPictureSizes() {
            String str = get(KEY_PICTURE_SIZE + SUPPORTED_VALUES_SUFFIX);
            return splitSize(str);
        }

        /**
         * Sets the image format for pictures.
         *
         * @param pixel_format the desired picture format
         *                     (<var>ImageFormat.NV21</var>,
         *                      <var>ImageFormat.RGB_565</var>, or
         *                      <var>ImageFormat.JPEG</var>)
         * @see android.graphics.ImageFormat
         */
        public void setPictureFormat(int pixel_format) {
            String s = cameraFormatForPixelFormat(pixel_format);
            if (s == null) {
                throw new IllegalArgumentException(
                        "Invalid pixel_format=" + pixel_format);
            }

            set(KEY_PICTURE_FORMAT, s);
        }

        /**
         * Returns the image format for pictures.
         *
         * @return the picture format
         * @see android.graphics.ImageFormat
         */
        public int getPictureFormat() {
            return pixelFormatForCameraFormat(get(KEY_PICTURE_FORMAT));
        }

        /**
         * Gets the supported picture formats.
         *
         * @return supported picture formats. This method will always return a
         *         list with at least one element.
         * @see android.graphics.ImageFormat
         */
        public List<Integer> getSupportedPictureFormats() {
            String str = get(KEY_PICTURE_FORMAT + SUPPORTED_VALUES_SUFFIX);
            ArrayList<Integer> formats = new ArrayList<Integer>();
            for (String s : split(str)) {
                int f = pixelFormatForCameraFormat(s);
                if (f == ImageFormat.UNKNOWN) continue;
                formats.add(f);
            }
            return formats;
        }

        private String cameraFormatForPixelFormat(int pixel_format) {
            switch(pixel_format) {
            case ImageFormat.NV16:      return PIXEL_FORMAT_YUV422SP;
            case ImageFormat.NV21:      return PIXEL_FORMAT_YUV420SP;
            case ImageFormat.YUY2:      return PIXEL_FORMAT_YUV422I;
            case ImageFormat.RGB_565:   return PIXEL_FORMAT_RGB565;
            case ImageFormat.JPEG:      return PIXEL_FORMAT_JPEG;
            default:                    return null;
            }
        }

        private int pixelFormatForCameraFormat(String format) {
            if (format == null)
                return ImageFormat.UNKNOWN;

            if (format.equals(PIXEL_FORMAT_YUV422SP))
                return ImageFormat.NV16;

            if (format.equals(PIXEL_FORMAT_YUV420SP))
                return ImageFormat.NV21;

            if (format.equals(PIXEL_FORMAT_YUV422I))
                return ImageFormat.YUY2;

            if (format.equals(PIXEL_FORMAT_RGB565))
                return ImageFormat.RGB_565;

            if (format.equals(PIXEL_FORMAT_JPEG))
                return ImageFormat.JPEG;

            return ImageFormat.UNKNOWN;
        }

        /**
         * Sets the orientation of the device in degrees. For example, suppose
         * the natural position of the device is landscape. If the user takes a
         * picture in landscape mode in 2048x1536 resolution, the rotation
         * should be set to 0. If the user rotates the phone 90 degrees
         * clockwise, the rotation should be set to 90. Applications can use
         * {@link android.view.OrientationEventListener} to set this parameter.
         *
         * The camera driver may set orientation in the EXIF header without
         * rotating the picture. Or the driver may rotate the picture and
         * the EXIF thumbnail. If the Jpeg picture is rotated, the orientation
         * in the EXIF header will be missing or 1 (row #0 is top and column #0
         * is left side).
         *
         * @param rotation The orientation of the device in degrees. Rotation
         *                 can only be 0, 90, 180 or 270.
         * @throws IllegalArgumentException if rotation value is invalid.
         * @see android.view.OrientationEventListener
         */
        public void setRotation(int rotation) {
            if (rotation == 0 || rotation == 90 || rotation == 180
                    || rotation == 270) {
                set(KEY_ROTATION, Integer.toString(rotation));
            } else {
                throw new IllegalArgumentException(
                        "Invalid rotation=" + rotation);
            }
        }

        /**
         * Sets GPS latitude coordinate. This will be stored in JPEG EXIF
         * header.
         *
         * @param latitude GPS latitude coordinate.
         */
        public void setGpsLatitude(double latitude) {
            set(KEY_GPS_LATITUDE, Double.toString(latitude));
        }

        /**
         * Sets GPS longitude coordinate. This will be stored in JPEG EXIF
         * header.
         *
         * @param longitude GPS longitude coordinate.
         */
        public void setGpsLongitude(double longitude) {
            set(KEY_GPS_LONGITUDE, Double.toString(longitude));
        }

        /**
         * Sets GPS altitude. This will be stored in JPEG EXIF header.
         *
         * @param altitude GPS altitude in meters.
         */
        public void setGpsAltitude(double altitude) {
            set(KEY_GPS_ALTITUDE, Double.toString(altitude));
        }

        /**
         * Sets GPS timestamp. This will be stored in JPEG EXIF header.
         *
         * @param timestamp GPS timestamp (UTC in seconds since January 1,
         *                  1970).
         */
        public void setGpsTimestamp(long timestamp) {
            set(KEY_GPS_TIMESTAMP, Long.toString(timestamp));
        }

        /**
         * Sets GPS processing method. It will store up to 32 characters
         * in JPEG EXIF header.
         *
         * @param processing_method The processing method to get this location.
         */
        public void setGpsProcessingMethod(String processing_method) {
            set(KEY_GPS_PROCESSING_METHOD, processing_method);
        }

        /**
         * Removes GPS latitude, longitude, altitude, and timestamp from the
         * parameters.
         */
        public void removeGpsData() {
            remove(KEY_GPS_LATITUDE);
            remove(KEY_GPS_LONGITUDE);
            remove(KEY_GPS_ALTITUDE);
            remove(KEY_GPS_TIMESTAMP);
            remove(KEY_GPS_PROCESSING_METHOD);
        }

        /**
         * Gets the current white balance setting.
         *
         * @return current white balance. null if white balance setting is not
         *         supported.
         * @see #WHITE_BALANCE_AUTO
         * @see #WHITE_BALANCE_INCANDESCENT
         * @see #WHITE_BALANCE_FLUORESCENT
         * @see #WHITE_BALANCE_WARM_FLUORESCENT
         * @see #WHITE_BALANCE_DAYLIGHT
         * @see #WHITE_BALANCE_CLOUDY_DAYLIGHT
         * @see #WHITE_BALANCE_TWILIGHT
         * @see #WHITE_BALANCE_SHADE
         *
         */
        public String getWhiteBalance() {
            return get(KEY_WHITE_BALANCE);
        }

        /**
         * Sets the white balance.
         *
         * @param value new white balance.
         * @see #getWhiteBalance()
         */
        public void setWhiteBalance(String value) {
            set(KEY_WHITE_BALANCE, value);
        }

        /**
         * Gets the supported white balance.
         *
         * @return a list of supported white balance. null if white balance
         *         setting is not supported.
         * @see #getWhiteBalance()
         */
        public List<String> getSupportedWhiteBalance() {
            String str = get(KEY_WHITE_BALANCE + SUPPORTED_VALUES_SUFFIX);
            return split(str);
        }

        /**
         * Gets the current color effect setting.
         *
         * @return current color effect. null if color effect
         *         setting is not supported.
         * @see #EFFECT_NONE
         * @see #EFFECT_MONO
         * @see #EFFECT_NEGATIVE
         * @see #EFFECT_SOLARIZE
         * @see #EFFECT_SEPIA
         * @see #EFFECT_POSTERIZE
         * @see #EFFECT_WHITEBOARD
         * @see #EFFECT_BLACKBOARD
         * @see #EFFECT_AQUA
         */
        public String getColorEffect() {
            return get(KEY_EFFECT);
        }

        /**
         * Sets the current color effect setting.
         *
         * @param value new color effect.
         * @see #getColorEffect()
         */
        public void setColorEffect(String value) {
            set(KEY_EFFECT, value);
        }

        /**
         * Gets the supported color effects.
         *
         * @return a list of supported color effects. null if color effect
         *         setting is not supported.
         * @see #getColorEffect()
         */
        public List<String> getSupportedColorEffects() {
            String str = get(KEY_EFFECT + SUPPORTED_VALUES_SUFFIX);
            return split(str);
        }


        /**
         * Gets the current antibanding setting.
         *
         * @return current antibanding. null if antibanding setting is not
         *         supported.
         * @see #ANTIBANDING_AUTO
         * @see #ANTIBANDING_50HZ
         * @see #ANTIBANDING_60HZ
         * @see #ANTIBANDING_OFF
         */
        public String getAntibanding() {
            return get(KEY_ANTIBANDING);
        }

        /**
         * Sets the antibanding.
         *
         * @param antibanding new antibanding value.
         * @see #getAntibanding()
         */
        public void setAntibanding(String antibanding) {
            set(KEY_ANTIBANDING, antibanding);
        }

        /**
         * Gets the supported antibanding values.
         *
         * @return a list of supported antibanding values. null if antibanding
         *         setting is not supported.
         * @see #getAntibanding()
         */
        public List<String> getSupportedAntibanding() {
            String str = get(KEY_ANTIBANDING + SUPPORTED_VALUES_SUFFIX);
            return split(str);
        }

        /**
         * Gets the current scene mode setting.
         *
         * @return one of SCENE_MODE_XXX string constant. null if scene mode
         *         setting is not supported.
         * @see #SCENE_MODE_AUTO
         * @see #SCENE_MODE_ACTION
         * @see #SCENE_MODE_PORTRAIT
         * @see #SCENE_MODE_LANDSCAPE
         * @see #SCENE_MODE_NIGHT
         * @see #SCENE_MODE_NIGHT_PORTRAIT
         * @see #SCENE_MODE_THEATRE
         * @see #SCENE_MODE_BEACH
         * @see #SCENE_MODE_SNOW
         * @see #SCENE_MODE_SUNSET
         * @see #SCENE_MODE_STEADYPHOTO
         * @see #SCENE_MODE_FIREWORKS
         * @see #SCENE_MODE_SPORTS
         * @see #SCENE_MODE_PARTY
         * @see #SCENE_MODE_CANDLELIGHT
         */
        public String getSceneMode() {
            return get(KEY_SCENE_MODE);
        }

        /**
         * Sets the scene mode. Changing scene mode may override other
         * parameters (such as flash mode, focus mode, white balance). For
         * example, suppose originally flash mode is on and supported flash
         * modes are on/off. In night scene mode, both flash mode and supported
         * flash mode may be changed to off. After setting scene mode,
         * applications should call getParameters to know if some parameters are
         * changed.
         *
         * @param value scene mode.
         * @see #getSceneMode()
         */
        public void setSceneMode(String value) {
            set(KEY_SCENE_MODE, value);
        }

        /**
         * Gets the supported scene modes.
         *
         * @return a list of supported scene modes. null if scene mode setting
         *         is not supported.
         * @see #getSceneMode()
         */
        public List<String> getSupportedSceneModes() {
            String str = get(KEY_SCENE_MODE + SUPPORTED_VALUES_SUFFIX);
            return split(str);
        }

        /**
         * Gets the current flash mode setting.
         *
         * @return current flash mode. null if flash mode setting is not
         *         supported.
         * @see #FLASH_MODE_OFF
         * @see #FLASH_MODE_AUTO
         * @see #FLASH_MODE_ON
         * @see #FLASH_MODE_RED_EYE
         * @see #FLASH_MODE_TORCH
         */
        public String getFlashMode() {
            return get(KEY_FLASH_MODE);
        }

        /**
         * Sets the flash mode.
         *
         * @param value flash mode.
         * @see #getFlashMode()
         */
        public void setFlashMode(String value) {
            set(KEY_FLASH_MODE, value);
        }

        /**
         * Gets the supported flash modes.
         *
         * @return a list of supported flash modes. null if flash mode setting
         *         is not supported.
         * @see #getFlashMode()
         */
        public List<String> getSupportedFlashModes() {
            String str = get(KEY_FLASH_MODE + SUPPORTED_VALUES_SUFFIX);
            return split(str);
        }

        /**
         * Gets the current focus mode setting.
         *
         * @return current focus mode. If the camera does not support
         *         auto-focus, this should return {@link #FOCUS_MODE_FIXED}. If
         *         the focus mode is not FOCUS_MODE_FIXED or {@link
         *         #FOCUS_MODE_INFINITY}, applications should call {@link
         *         #autoFocus(AutoFocusCallback)} to start the focus.
         * @see #FOCUS_MODE_AUTO
         * @see #FOCUS_MODE_INFINITY
         * @see #FOCUS_MODE_MACRO
         * @see #FOCUS_MODE_FIXED
         */
        public String getFocusMode() {
            return get(KEY_FOCUS_MODE);
        }

        /**
         * Sets the focus mode.
         *
         * @param value focus mode.
         * @see #getFocusMode()
         */
        public void setFocusMode(String value) {
            set(KEY_FOCUS_MODE, value);
        }

        /**
         * Gets the supported focus modes.
         *
         * @return a list of supported focus modes. This method will always
         *         return a list with at least one element.
         * @see #getFocusMode()
         */
        public List<String> getSupportedFocusModes() {
            String str = get(KEY_FOCUS_MODE + SUPPORTED_VALUES_SUFFIX);
            return split(str);
        }

        /**
         * Gets the focal length (in millimeter) of the camera.
         *
         * @return the focal length. This method will always return a valid
         *         value.
         */
        public float getFocalLength() {
            return Float.parseFloat(get(KEY_FOCAL_LENGTH));
        }

        /**
         * Gets the horizontal angle of view in degrees.
         *
         * @return horizontal angle of view. This method will always return a
         *         valid value.
         */
        public float getHorizontalViewAngle() {
            return Float.parseFloat(get(KEY_HORIZONTAL_VIEW_ANGLE));
        }

        /**
         * Gets the vertical angle of view in degrees.
         *
         * @return vertical angle of view. This method will always return a
         *         valid value.
         */
        public float getVerticalViewAngle() {
            return Float.parseFloat(get(KEY_VERTICAL_VIEW_ANGLE));
        }

        /**
         * Gets the current exposure compensation index.
         *
         * @return current exposure compensation index. The range is {@link
         *         #getMinExposureCompensation} to {@link
         *         #getMaxExposureCompensation}. 0 means exposure is not
         *         adjusted.
         */
        public int getExposureCompensation() {
            return getInt(KEY_EXPOSURE_COMPENSATION, 0);
        }

        /**
         * Sets the exposure compensation index.
         *
         * @param value exposure compensation index. The valid value range is
         *        from {@link #getMinExposureCompensation} (inclusive) to {@link
         *        #getMaxExposureCompensation} (inclusive). 0 means exposure is
         *        not adjusted. Application should call
         *        getMinExposureCompensation and getMaxExposureCompensation to
         *        know if exposure compensation is supported.
         */
        public void setExposureCompensation(int value) {
            set(KEY_EXPOSURE_COMPENSATION, value);
        }

        /**
         * Gets the maximum exposure compensation index.
         *
         * @return maximum exposure compensation index (>=0). If both this
         *         method and {@link #getMinExposureCompensation} return 0,
         *         exposure compensation is not supported.
         */
        public int getMaxExposureCompensation() {
            return getInt(KEY_MAX_EXPOSURE_COMPENSATION, 0);
        }

        /**
         * Gets the minimum exposure compensation index.
         *
         * @return minimum exposure compensation index (<=0). If both this
         *         method and {@link #getMaxExposureCompensation} return 0,
         *         exposure compensation is not supported.
         */
        public int getMinExposureCompensation() {
            return getInt(KEY_MIN_EXPOSURE_COMPENSATION, 0);
        }

        /**
         * Gets the exposure compensation step.
         *
         * @return exposure compensation step. Applications can get EV by
         *         multiplying the exposure compensation index and step. Ex: if
         *         exposure compensation index is -6 and step is 0.333333333, EV
         *         is -2.
         */
        public float getExposureCompensationStep() {
            return getFloat(KEY_EXPOSURE_COMPENSATION_STEP, 0);
        }

        /**
         * Gets current zoom value. This also works when smooth zoom is in
         * progress. Applications should check {@link #isZoomSupported} before
         * using this method.
         *
         * @return the current zoom value. The range is 0 to {@link
         *         #getMaxZoom}. 0 means the camera is not zoomed.
         */
        public int getZoom() {
            return getInt(KEY_ZOOM, 0);
        }

        /**
         * Sets current zoom value. If the camera is zoomed (value > 0), the
         * actual picture size may be smaller than picture size setting.
         * Applications can check the actual picture size after picture is
         * returned from {@link PictureCallback}. The preview size remains the
         * same in zoom. Applications should check {@link #isZoomSupported}
         * before using this method.
         *
         * @param value zoom value. The valid range is 0 to {@link #getMaxZoom}.
         */
        public void setZoom(int value) {
            set(KEY_ZOOM, value);
        }

        /**
         * Returns true if zoom is supported. Applications should call this
         * before using other zoom methods.
         *
         * @return true if zoom is supported.
         */
        public boolean isZoomSupported() {
            String str = get(KEY_ZOOM_SUPPORTED);
            return TRUE.equals(str);
        }

        /**
         * Gets the maximum zoom value allowed for snapshot. This is the maximum
         * value that applications can set to {@link #setZoom(int)}.
         * Applications should call {@link #isZoomSupported} before using this
         * method. This value may change in different preview size. Applications
         * should call this again after setting preview size.
         *
         * @return the maximum zoom value supported by the camera.
         */
        public int getMaxZoom() {
            return getInt(KEY_MAX_ZOOM, 0);
        }

        /**
         * Gets the zoom ratios of all zoom values. Applications should check
         * {@link #isZoomSupported} before using this method.
         *
         * @return the zoom ratios in 1/100 increments. Ex: a zoom of 3.2x is
         *         returned as 320. The number of elements is {@link
         *         #getMaxZoom} + 1. The list is sorted from small to large. The
         *         first element is always 100. The last element is the zoom
         *         ratio of the maximum zoom value.
         */
        public List<Integer> getZoomRatios() {
            return splitInt(get(KEY_ZOOM_RATIOS));
        }

        /**
         * Returns true if smooth zoom is supported. Applications should call
         * this before using other smooth zoom methods.
         *
         * @return true if smooth zoom is supported.
         */
        public boolean isSmoothZoomSupported() {
            String str = get(KEY_SMOOTH_ZOOM_SUPPORTED);
            return TRUE.equals(str);
        }

        /**
         * Gets the distances from the camera to where an object appears to be
         * in focus. The object is sharpest at the optimal focus distance. The
         * depth of field is the far focus distance minus near focus distance.
         *
         * Focus distances may change after calling {@link
         * #autoFocus(AutoFocusCallback)}, {@link #cancelAutoFocus}, or {@link
         * #startPreview()}. Applications can call {@link #getParameters()}
         * and this method anytime to get the latest focus distances. If the
         * focus mode is FOCUS_MODE_CONTINUOUS and autofocus has started, focus
         * distances may change from time to time.
         *
         * Far focus distance >= optimal focus distance >= near focus distance.
         * If the focus distance is infinity, the value will be
         * Float.POSITIVE_INFINITY.
         *
         * @param output focus distances in meters. output must be a float
         *        array with three elements. Near focus distance, optimal focus
         *        distance, and far focus distance will be filled in the array.
         * @see #FOCUS_DISTANCE_NEAR_INDEX
         * @see #FOCUS_DISTANCE_OPTIMAL_INDEX
         * @see #FOCUS_DISTANCE_FAR_INDEX
         */
        public void getFocusDistances(float[] output) {
            if (output == null || output.length != 3) {
                throw new IllegalArgumentException(
                        "output must be an float array with three elements.");
            }
            List<Float> distances = splitFloat(get(KEY_FOCUS_DISTANCES));
            output[0] = distances.get(0);
            output[1] = distances.get(1);
            output[2] = distances.get(2);
        }

        /**
         * Gets the supported metering modes.
         *
         * @return a list of supported metering modes. null if metering mode
         *         setting is not supported.
         * @see #getMeteringMode()
         */
        public List<String> getSupportedMeteringModes() {
            String str = get(KEY_METERING_MODE + SUPPORTED_VALUES_SUFFIX);
            return split(str);
        }

        /**
         * Gets the current metering mode, which affects how camera determines
         * exposure.
         *
         * @return current metering mode. If the camera does not support
         *         metering setting, this should return null.
         * @see #METERING_MODE_CENTER_WEIGHTED
         * @see #METERING_MODE_FRAME_AVERAGE
         * @see #METERING_MODE_SPOT
         */
        public String getMeteringMode() {
            return get(KEY_METERING_MODE);
        }

        /**
         * Sets the metering mode.
         *
         * @param value metering mode.
         * @see #getMeteringMode()
         */
        public void setMeteringMode(String value) {
            set(KEY_METERING_MODE, value);
        }

        // Splits a comma delimited string to an ArrayList of String.
        // Return null if the passing string is null or the size is 0.
        private ArrayList<String> split(String str) {
            if (str == null) return null;

            // Use StringTokenizer because it is faster than split.
            StringTokenizer tokenizer = new StringTokenizer(str, ",");
            ArrayList<String> substrings = new ArrayList<String>();
            while (tokenizer.hasMoreElements()) {
                substrings.add(tokenizer.nextToken());
            }
            return substrings;
        }

        // Splits a comma delimited string to an ArrayList of Integer.
        // Return null if the passing string is null or the size is 0.
        private ArrayList<Integer> splitInt(String str) {
            if (str == null) return null;

            StringTokenizer tokenizer = new StringTokenizer(str, ",");
            ArrayList<Integer> substrings = new ArrayList<Integer>();
            while (tokenizer.hasMoreElements()) {
                String token = tokenizer.nextToken();
                substrings.add(Integer.parseInt(token));
            }
            if (substrings.size() == 0) return null;
            return substrings;
        }

        // Splits a comma delimited string to an ArrayList of Float.
        // Return null if the passing string is null or the size is 0.
        private ArrayList<Float> splitFloat(String str) {
            if (str == null) return null;

            StringTokenizer tokenizer = new StringTokenizer(str, ",");
            ArrayList<Float> substrings = new ArrayList<Float>();
            while (tokenizer.hasMoreElements()) {
                String token = tokenizer.nextToken();
                substrings.add(Float.parseFloat(token));
            }
            if (substrings.size() == 0) return null;
            return substrings;
        }

        // Returns the value of a float parameter.
        private float getFloat(String key, float defaultValue) {
            try {
                return Float.parseFloat(mMap.get(key));
            } catch (NumberFormatException ex) {
                return defaultValue;
            }
        }

        // Returns the value of a integer parameter.
        private int getInt(String key, int defaultValue) {
            try {
                return Integer.parseInt(mMap.get(key));
            } catch (NumberFormatException ex) {
                return defaultValue;
            }
        }

        // Splits a comma delimited string to an ArrayList of Size.
        // Return null if the passing string is null or the size is 0.
        private ArrayList<Size> splitSize(String str) {
            if (str == null) return null;

            StringTokenizer tokenizer = new StringTokenizer(str, ",");
            ArrayList<Size> sizeList = new ArrayList<Size>();
            while (tokenizer.hasMoreElements()) {
                Size size = strToSize(tokenizer.nextToken());
                if (size != null) sizeList.add(size);
            }
            if (sizeList.size() == 0) return null;
            return sizeList;
        }

        // Parses a string (ex: "480x320") to Size object.
        // Return null if the passing string is null.
        private Size strToSize(String str) {
            if (str == null) return null;

            int pos = str.indexOf('x');
            if (pos != -1) {
                String width = str.substring(0, pos);
                String height = str.substring(pos + 1);
                return new Size(Integer.parseInt(width),
                                Integer.parseInt(height));
            }
            Log.e(TAG, "Invalid size parameter string=" + str);
            return null;
        }
    };
}
