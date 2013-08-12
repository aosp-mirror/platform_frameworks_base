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

import android.app.ActivityThread;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.media.IAudioService;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.RSIllegalArgumentException;
import android.renderscript.Type;
import android.util.Log;
import android.text.TextUtils;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The Camera class is used to set image capture settings, start/stop preview,
 * snap pictures, and retrieve frames for encoding for video.  This class is a
 * client for the Camera service, which manages the actual camera hardware.
 *
 * <p>To access the device camera, you must declare the
 * {@link android.Manifest.permission#CAMERA} permission in your Android
 * Manifest. Also be sure to include the
 * <a href="{@docRoot}guide/topics/manifest/uses-feature-element.html">&lt;uses-feature></a>
 * manifest element to declare camera features used by your application.
 * For example, if you use the camera and auto-focus feature, your Manifest
 * should include the following:</p>
 * <pre> &lt;uses-permission android:name="android.permission.CAMERA" />
 * &lt;uses-feature android:name="android.hardware.camera" />
 * &lt;uses-feature android:name="android.hardware.camera.autofocus" /></pre>
 *
 * <p>To take pictures with this class, use the following steps:</p>
 *
 * <ol>
 * <li>Obtain an instance of Camera from {@link #open(int)}.
 *
 * <li>Get existing (default) settings with {@link #getParameters()}.
 *
 * <li>If necessary, modify the returned {@link Camera.Parameters} object and call
 * {@link #setParameters(Camera.Parameters)}.
 *
 * <li>If desired, call {@link #setDisplayOrientation(int)}.
 *
 * <li><b>Important</b>: Pass a fully initialized {@link SurfaceHolder} to
 * {@link #setPreviewDisplay(SurfaceHolder)}.  Without a surface, the camera
 * will be unable to start the preview.
 *
 * <li><b>Important</b>: Call {@link #startPreview()} to start updating the
 * preview surface.  Preview must be started before you can take a picture.
 *
 * <li>When you want, call {@link #takePicture(Camera.ShutterCallback,
 * Camera.PictureCallback, Camera.PictureCallback, Camera.PictureCallback)} to
 * capture a photo.  Wait for the callbacks to provide the actual image data.
 *
 * <li>After taking a picture, preview display will have stopped.  To take more
 * photos, call {@link #startPreview()} again first.
 *
 * <li>Call {@link #stopPreview()} to stop updating the preview surface.
 *
 * <li><b>Important:</b> Call {@link #release()} to release the camera for
 * use by other applications.  Applications should release the camera
 * immediately in {@link android.app.Activity#onPause()} (and re-{@link #open()}
 * it in {@link android.app.Activity#onResume()}).
 * </ol>
 *
 * <p>To quickly switch to video recording mode, use these steps:</p>
 *
 * <ol>
 * <li>Obtain and initialize a Camera and start preview as described above.
 *
 * <li>Call {@link #unlock()} to allow the media process to access the camera.
 *
 * <li>Pass the camera to {@link android.media.MediaRecorder#setCamera(Camera)}.
 * See {@link android.media.MediaRecorder} information about video recording.
 *
 * <li>When finished recording, call {@link #reconnect()} to re-acquire
 * and re-lock the camera.
 *
 * <li>If desired, restart preview and take more photos or videos.
 *
 * <li>Call {@link #stopPreview()} and {@link #release()} as described above.
 * </ol>
 *
 * <p>This class is not thread-safe, and is meant for use from one event thread.
 * Most long-running operations (preview, focus, photo capture, etc) happen
 * asynchronously and invoke callbacks as necessary.  Callbacks will be invoked
 * on the event thread {@link #open(int)} was called from.  This class's methods
 * must never be called from multiple threads at once.</p>
 *
 * <p class="caution"><strong>Caution:</strong> Different Android-powered devices
 * may have different hardware specifications, such as megapixel ratings and
 * auto-focus capabilities. In order for your application to be compatible with
 * more devices, you should not make assumptions about the device camera
 * specifications.</p>
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about using cameras, read the
 * <a href="{@docRoot}guide/topics/media/camera.html">Camera</a> developer guide.</p>
 * </div>
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
    private static final int CAMERA_MSG_RAW_IMAGE_NOTIFY = 0x200;
    private static final int CAMERA_MSG_PREVIEW_METADATA = 0x400;
    private static final int CAMERA_MSG_FOCUS_MOVE       = 0x800;
    /* ### QC ADD-ONS: START */
    private static final int CAMERA_MSG_STATS_DATA       = 0x1000;
    private static final int CAMERA_MSG_META_DATA        = 0x2000;
    /* ### QC ADD-ONS: END */

    private int mNativeContext; // accessed by native methods
    private EventHandler mEventHandler;
    private ShutterCallback mShutterCallback;
    private PictureCallback mRawImageCallback;
    private PictureCallback mJpegCallback;
    private PreviewCallback mPreviewCallback;
    private boolean mUsingPreviewAllocation;
    private PictureCallback mPostviewCallback;
    private AutoFocusCallback mAutoFocusCallback;
    private AutoFocusMoveCallback mAutoFocusMoveCallback;
    private OnZoomChangeListener mZoomListener;
    private FaceDetectionListener mFaceListener;
    private ErrorCallback mErrorCallback;
    private boolean mOneShot;
    private boolean mWithBuffer;
    private boolean mFaceDetectionRunning = false;
    private Object mAutoFocusCallbackLock = new Object();
    /* ### QC ADD-ONS: START */
    private CameraDataCallback mCameraDataCallback;
    private CameraMetaDataCallback mCameraMetaDataCallback;
    /* ### QC ADD-ONS: END */

    /**
     * Broadcast Action:  A new picture is taken by the camera, and the entry of
     * the picture has been added to the media store.
     * {@link android.content.Intent#getData} is URI of the picture.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_NEW_PICTURE = "android.hardware.action.NEW_PICTURE";

    /**
     * Broadcast Action:  A new video is recorded by the camera, and the entry
     * of the video has been added to the media store.
     * {@link android.content.Intent#getData} is URI of the video.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_NEW_VIDEO = "android.hardware.action.NEW_VIDEO";

    /**
     * Hardware face detection. It does not use much CPU.
     */
    private static final int CAMERA_FACE_DETECTION_HW = 0;

    /**
     * Software face detection. It uses some CPU.
     */
    private static final int CAMERA_FACE_DETECTION_SW = 1;

    /**
     * Returns the number of physical cameras available on this device.
     */
    public native static int getNumberOfCameras();

    /**
     * Returns the information about a particular camera.
     * If {@link #getNumberOfCameras()} returns N, the valid id is 0 to N-1.
     */
    public static void getCameraInfo(int cameraId, CameraInfo cameraInfo) {
        _getCameraInfo(cameraId, cameraInfo);
        IBinder b = ServiceManager.getService(Context.AUDIO_SERVICE);
        IAudioService audioService = IAudioService.Stub.asInterface(b);
        try {
            if (audioService.isCameraSoundForced()) {
                // Only set this when sound is forced; otherwise let native code
                // decide.
                cameraInfo.canDisableShutterSound = false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Audio service is unavailable for queries");
        }
    }
    private native static void _getCameraInfo(int cameraId, CameraInfo cameraInfo);

    /**
     * Information about a camera
     */
    public static class CameraInfo {
        /**
         * The facing of the camera is opposite to that of the screen.
         */
        public static final int CAMERA_FACING_BACK = 0;

        /**
         * The facing of the camera is the same as that of the screen.
         */
        public static final int CAMERA_FACING_FRONT = 1;

         /* ### QC ADD-ONS: START TBD*/
        /** @hide
         *  camera is in ZSL mode.
         */
        public static final int CAMERA_SUPPORT_MODE_ZSL = 2;

        /** @hide
         * camera is in non-ZSL mode.
         */
        public static final int CAMERA_SUPPORT_MODE_NONZSL = 3;
         /* ### QC ADD-ONS: END */
        /**
         * The direction that the camera faces. It should be
         * CAMERA_FACING_BACK or CAMERA_FACING_FRONT.
         */
        public int facing;

        /**
         * <p>The orientation of the camera image. The value is the angle that the
         * camera image needs to be rotated clockwise so it shows correctly on
         * the display in its natural orientation. It should be 0, 90, 180, or 270.</p>
         *
         * <p>For example, suppose a device has a naturally tall screen. The
         * back-facing camera sensor is mounted in landscape. You are looking at
         * the screen. If the top side of the camera sensor is aligned with the
         * right edge of the screen in natural orientation, the value should be
         * 90. If the top side of a front-facing camera sensor is aligned with
         * the right of the screen, the value should be 270.</p>
         *
         * @see #setDisplayOrientation(int)
         * @see Parameters#setRotation(int)
         * @see Parameters#setPreviewSize(int, int)
         * @see Parameters#setPictureSize(int, int)
         * @see Parameters#setJpegThumbnailSize(int, int)
         */
        public int orientation;

        /**
         * <p>Whether the shutter sound can be disabled.</p>
         *
         * <p>On some devices, the camera shutter sound cannot be turned off
         * through {@link #enableShutterSound enableShutterSound}. This field
         * can be used to determine whether a call to disable the shutter sound
         * will succeed.</p>
         *
         * <p>If this field is set to true, then a call of
         * {@code enableShutterSound(false)} will be successful. If set to
         * false, then that call will fail, and the shutter sound will be played
         * when {@link Camera#takePicture takePicture} is called.</p>
         */
        public boolean canDisableShutterSound;
    };

    /**
     * Creates a new Camera object to access a particular hardware camera. If
     * the same camera is opened by other applications, this will throw a
     * RuntimeException.
     *
     * <p>You must call {@link #release()} when you are done using the camera,
     * otherwise it will remain locked and be unavailable to other applications.
     *
     * <p>Your application should only have one Camera object active at a time
     * for a particular hardware camera.
     *
     * <p>Callbacks from other methods are delivered to the event loop of the
     * thread which called open().  If this thread has no event loop, then
     * callbacks are delivered to the main application event loop.  If there
     * is no main application event loop, callbacks are not delivered.
     *
     * <p class="caution"><b>Caution:</b> On some devices, this method may
     * take a long time to complete.  It is best to call this method from a
     * worker thread (possibly using {@link android.os.AsyncTask}) to avoid
     * blocking the main application UI thread.
     *
     * @param cameraId the hardware camera to access, between 0 and
     *     {@link #getNumberOfCameras()}-1.
     * @return a new Camera object, connected, locked and ready for use.
     * @throws RuntimeException if opening the camera fails (for example, if the
     *     camera is in use by another process or device policy manager has
     *     disabled the camera).
     * @see android.app.admin.DevicePolicyManager#getCameraDisabled(android.content.ComponentName)
     */
    public static Camera open(int cameraId) {
        return new Camera(cameraId);
    }

    /**
     * Creates a new Camera object to access the first back-facing camera on the
     * device. If the device does not have a back-facing camera, this returns
     * null.
     * @see #open(int)
     */
    public static Camera open() {
        int numberOfCameras = getNumberOfCameras();
        CameraInfo cameraInfo = new CameraInfo();
        for (int i = 0; i < numberOfCameras; i++) {
            getCameraInfo(i, cameraInfo);
            if (cameraInfo.facing == CameraInfo.CAMERA_FACING_BACK) {
                return new Camera(i);
            }
        }
        return null;
    }

    Camera(int cameraId) {
        mShutterCallback = null;
        mRawImageCallback = null;
        mJpegCallback = null;
        mPreviewCallback = null;
        mPostviewCallback = null;
        mUsingPreviewAllocation = false;
        mZoomListener = null;
        /* ### QC ADD-ONS: START */
        mCameraDataCallback = null;
        mCameraMetaDataCallback = null;
        /* ### QC ADD-ONS: END */

        Looper looper;
        if ((looper = Looper.myLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else if ((looper = Looper.getMainLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else {
            mEventHandler = null;
        }

        String packageName = ActivityThread.currentPackageName();

        native_setup(new WeakReference<Camera>(this), cameraId, packageName);
    }

    /**
     * An empty Camera for testing purpose.
     */
    Camera() {
    }

    protected void finalize() {
        release();
    }

    private native final void native_setup(Object camera_this, int cameraId,
                                           String packageName);

    private native final void native_release();


    /**
     * Disconnects and releases the Camera object resources.
     *
     * <p>You must call this as soon as you're done with the Camera object.</p>
     */
    public final void release() {
        native_release();
        mFaceDetectionRunning = false;
    }

    /**
     * Unlocks the camera to allow another process to access it.
     * Normally, the camera is locked to the process with an active Camera
     * object until {@link #release()} is called.  To allow rapid handoff
     * between processes, you can call this method to release the camera
     * temporarily for another process to use; once the other process is done
     * you can call {@link #reconnect()} to reclaim the camera.
     *
     * <p>This must be done before calling
     * {@link android.media.MediaRecorder#setCamera(Camera)}. This cannot be
     * called after recording starts.
     *
     * <p>If you are not recording video, you probably do not need this method.
     *
     * @throws RuntimeException if the camera cannot be unlocked.
     */
    public native final void unlock();

    /**
     * Re-locks the camera to prevent other processes from accessing it.
     * Camera objects are locked by default unless {@link #unlock()} is
     * called.  Normally {@link #reconnect()} is used instead.
     *
     * <p>Since API level 14, camera is automatically locked for applications in
     * {@link android.media.MediaRecorder#start()}. Applications can use the
     * camera (ex: zoom) after recording starts. There is no need to call this
     * after recording starts or stops.
     *
     * <p>If you are not recording video, you probably do not need this method.
     *
     * @throws RuntimeException if the camera cannot be re-locked (for
     *     example, if the camera is still in use by another process).
     */
    public native final void lock();

    /**
     * Reconnects to the camera service after another process used it.
     * After {@link #unlock()} is called, another process may use the
     * camera; when the process is done, you must reconnect to the camera,
     * which will re-acquire the lock and allow you to continue using the
     * camera.
     *
     * <p>Since API level 14, camera is automatically locked for applications in
     * {@link android.media.MediaRecorder#start()}. Applications can use the
     * camera (ex: zoom) after recording starts. There is no need to call this
     * after recording starts or stops.
     *
     * <p>If you are not recording video, you probably do not need this method.
     *
     * @throws IOException if a connection cannot be re-established (for
     *     example, if the camera is still in use by another process).
     */
    public native final void reconnect() throws IOException;

    /**
     * Sets the {@link Surface} to be used for live preview.
     * Either a surface or surface texture is necessary for preview, and
     * preview is necessary to take pictures.  The same surface can be re-set
     * without harm.  Setting a preview surface will un-set any preview surface
     * texture that was set via {@link #setPreviewTexture}.
     *
     * <p>The {@link SurfaceHolder} must already contain a surface when this
     * method is called.  If you are using {@link android.view.SurfaceView},
     * you will need to register a {@link SurfaceHolder.Callback} with
     * {@link SurfaceHolder#addCallback(SurfaceHolder.Callback)} and wait for
     * {@link SurfaceHolder.Callback#surfaceCreated(SurfaceHolder)} before
     * calling setPreviewDisplay() or starting preview.
     *
     * <p>This method must be called before {@link #startPreview()}.  The
     * one exception is that if the preview surface is not set (or set to null)
     * before startPreview() is called, then this method may be called once
     * with a non-null parameter to set the preview surface.  (This allows
     * camera setup and surface creation to happen in parallel, saving time.)
     * The preview surface may not otherwise change while preview is running.
     *
     * @param holder containing the Surface on which to place the preview,
     *     or null to remove the preview surface
     * @throws IOException if the method fails (for example, if the surface
     *     is unavailable or unsuitable).
     */
    public final void setPreviewDisplay(SurfaceHolder holder) throws IOException {
        if (holder != null) {
            setPreviewDisplay(holder.getSurface());
        } else {
            setPreviewDisplay((Surface)null);
        }
    }

    private native final void setPreviewDisplay(Surface surface) throws IOException;

    /**
     * Sets the {@link SurfaceTexture} to be used for live preview.
     * Either a surface or surface texture is necessary for preview, and
     * preview is necessary to take pictures.  The same surface texture can be
     * re-set without harm.  Setting a preview surface texture will un-set any
     * preview surface that was set via {@link #setPreviewDisplay}.
     *
     * <p>This method must be called before {@link #startPreview()}.  The
     * one exception is that if the preview surface texture is not set (or set
     * to null) before startPreview() is called, then this method may be called
     * once with a non-null parameter to set the preview surface.  (This allows
     * camera setup and surface creation to happen in parallel, saving time.)
     * The preview surface texture may not otherwise change while preview is
     * running.
     *
     * <p>The timestamps provided by {@link SurfaceTexture#getTimestamp()} for a
     * SurfaceTexture set as the preview texture have an unspecified zero point,
     * and cannot be directly compared between different cameras or different
     * instances of the same camera, or across multiple runs of the same
     * program.
     *
     * <p>If you are using the preview data to create video or still images,
     * strongly consider using {@link android.media.MediaActionSound} to
     * properly indicate image capture or recording start/stop to the user.</p>
     *
     * @see android.media.MediaActionSound
     * @see android.graphics.SurfaceTexture
     * @see android.view.TextureView
     * @param surfaceTexture the {@link SurfaceTexture} to which the preview
     *     images are to be sent or null to remove the current preview surface
     *     texture
     * @throws IOException if the method fails (for example, if the surface
     *     texture is unavailable or unsuitable).
     */
    public native final void setPreviewTexture(SurfaceTexture surfaceTexture) throws IOException;

    /**
     * Callback interface used to deliver copies of preview frames as
     * they are displayed.
     *
     * @see #setPreviewCallback(Camera.PreviewCallback)
     * @see #setOneShotPreviewCallback(Camera.PreviewCallback)
     * @see #setPreviewCallbackWithBuffer(Camera.PreviewCallback)
     * @see #startPreview()
     */
    public interface PreviewCallback
    {
        /**
         * Called as preview frames are displayed.  This callback is invoked
         * on the event thread {@link #open(int)} was called from.
         *
         * <p>If using the {@link android.graphics.ImageFormat#YV12} format,
         * refer to the equations in {@link Camera.Parameters#setPreviewFormat}
         * for the arrangement of the pixel data in the preview callback
         * buffers.
         *
         * @param data the contents of the preview frame in the format defined
         *  by {@link android.graphics.ImageFormat}, which can be queried
         *  with {@link android.hardware.Camera.Parameters#getPreviewFormat()}.
         *  If {@link android.hardware.Camera.Parameters#setPreviewFormat(int)}
         *             is never called, the default will be the YCbCr_420_SP
         *             (NV21) format.
         * @param camera the Camera service object.
         */
        void onPreviewFrame(byte[] data, Camera camera);
    };

    /**
     * Starts capturing and drawing preview frames to the screen.
     * Preview will not actually start until a surface is supplied
     * with {@link #setPreviewDisplay(SurfaceHolder)} or
     * {@link #setPreviewTexture(SurfaceTexture)}.
     *
     * <p>If {@link #setPreviewCallback(Camera.PreviewCallback)},
     * {@link #setOneShotPreviewCallback(Camera.PreviewCallback)}, or
     * {@link #setPreviewCallbackWithBuffer(Camera.PreviewCallback)} were
     * called, {@link Camera.PreviewCallback#onPreviewFrame(byte[], Camera)}
     * will be called when preview data becomes available.
     */
    public native final void startPreview();

    /**
     * Stops capturing and drawing preview frames to the surface, and
     * resets the camera for a future call to {@link #startPreview()}.
     */
    public final void stopPreview() {
        _stopPreview();
        mFaceDetectionRunning = false;

        mShutterCallback = null;
        mRawImageCallback = null;
        mPostviewCallback = null;
        mJpegCallback = null;
        synchronized (mAutoFocusCallbackLock) {
            mAutoFocusCallback = null;
        }
        mAutoFocusMoveCallback = null;
    }

    private native final void _stopPreview();

    /**
     * Return current preview state.
     *
     * FIXME: Unhide before release
     * @hide
     */
    public native final boolean previewEnabled();

    /**
     * <p>Installs a callback to be invoked for every preview frame in addition
     * to displaying them on the screen.  The callback will be repeatedly called
     * for as long as preview is active.  This method can be called at any time,
     * even while preview is live.  Any other preview callbacks are
     * overridden.</p>
     *
     * <p>If you are using the preview data to create video or still images,
     * strongly consider using {@link android.media.MediaActionSound} to
     * properly indicate image capture or recording start/stop to the user.</p>
     *
     * @param cb a callback object that receives a copy of each preview frame,
     *     or null to stop receiving callbacks.
     * @see android.media.MediaActionSound
     */
    public final void setPreviewCallback(PreviewCallback cb) {
        mPreviewCallback = cb;
        mOneShot = false;
        mWithBuffer = false;
        if (cb != null) {
            mUsingPreviewAllocation = false;
        }
        // Always use one-shot mode. We fake camera preview mode by
        // doing one-shot preview continuously.
        setHasPreviewCallback(cb != null, false);
    }

    /**
     * <p>Installs a callback to be invoked for the next preview frame in
     * addition to displaying it on the screen.  After one invocation, the
     * callback is cleared. This method can be called any time, even when
     * preview is live.  Any other preview callbacks are overridden.</p>
     *
     * <p>If you are using the preview data to create video or still images,
     * strongly consider using {@link android.media.MediaActionSound} to
     * properly indicate image capture or recording start/stop to the user.</p>
     *
     * @param cb a callback object that receives a copy of the next preview frame,
     *     or null to stop receiving callbacks.
     * @see android.media.MediaActionSound
     */
    public final void setOneShotPreviewCallback(PreviewCallback cb) {
        mPreviewCallback = cb;
        mOneShot = true;
        mWithBuffer = false;
        if (cb != null) {
            mUsingPreviewAllocation = false;
        }
        setHasPreviewCallback(cb != null, false);
    }

    private native final void setHasPreviewCallback(boolean installed, boolean manualBuffer);

    /**
     * <p>Installs a callback to be invoked for every preview frame, using
     * buffers supplied with {@link #addCallbackBuffer(byte[])}, in addition to
     * displaying them on the screen.  The callback will be repeatedly called
     * for as long as preview is active and buffers are available.  Any other
     * preview callbacks are overridden.</p>
     *
     * <p>The purpose of this method is to improve preview efficiency and frame
     * rate by allowing preview frame memory reuse.  You must call
     * {@link #addCallbackBuffer(byte[])} at some point -- before or after
     * calling this method -- or no callbacks will received.</p>
     *
     * <p>The buffer queue will be cleared if this method is called with a null
     * callback, {@link #setPreviewCallback(Camera.PreviewCallback)} is called,
     * or {@link #setOneShotPreviewCallback(Camera.PreviewCallback)} is
     * called.</p>
     *
     * <p>If you are using the preview data to create video or still images,
     * strongly consider using {@link android.media.MediaActionSound} to
     * properly indicate image capture or recording start/stop to the user.</p>
     *
     * @param cb a callback object that receives a copy of the preview frame,
     *     or null to stop receiving callbacks and clear the buffer queue.
     * @see #addCallbackBuffer(byte[])
     * @see android.media.MediaActionSound
     */
    public final void setPreviewCallbackWithBuffer(PreviewCallback cb) {
        mPreviewCallback = cb;
        mOneShot = false;
        mWithBuffer = true;
        if (cb != null) {
            mUsingPreviewAllocation = false;
        }
        setHasPreviewCallback(cb != null, true);
    }

    /**
     * Adds a pre-allocated buffer to the preview callback buffer queue.
     * Applications can add one or more buffers to the queue. When a preview
     * frame arrives and there is still at least one available buffer, the
     * buffer will be used and removed from the queue. Then preview callback is
     * invoked with the buffer. If a frame arrives and there is no buffer left,
     * the frame is discarded. Applications should add buffers back when they
     * finish processing the data in them.
     *
     * <p>For formats besides YV12, the size of the buffer is determined by
     * multiplying the preview image width, height, and bytes per pixel. The
     * width and height can be read from
     * {@link Camera.Parameters#getPreviewSize()}. Bytes per pixel can be
     * computed from {@link android.graphics.ImageFormat#getBitsPerPixel(int)} /
     * 8, using the image format from
     * {@link Camera.Parameters#getPreviewFormat()}.
     *
     * <p>If using the {@link android.graphics.ImageFormat#YV12} format, the
     * size can be calculated using the equations listed in
     * {@link Camera.Parameters#setPreviewFormat}.
     *
     * <p>This method is only necessary when
     * {@link #setPreviewCallbackWithBuffer(PreviewCallback)} is used. When
     * {@link #setPreviewCallback(PreviewCallback)} or
     * {@link #setOneShotPreviewCallback(PreviewCallback)} are used, buffers
     * are automatically allocated. When a supplied buffer is too small to
     * hold the preview frame data, preview callback will return null and
     * the buffer will be removed from the buffer queue.
     *
     * @param callbackBuffer the buffer to add to the queue. The size of the
     *   buffer must match the values described above.
     * @see #setPreviewCallbackWithBuffer(PreviewCallback)
     */
    public final void addCallbackBuffer(byte[] callbackBuffer)
    {
        _addCallbackBuffer(callbackBuffer, CAMERA_MSG_PREVIEW_FRAME);
    }

    /**
     * Adds a pre-allocated buffer to the raw image callback buffer queue.
     * Applications can add one or more buffers to the queue. When a raw image
     * frame arrives and there is still at least one available buffer, the
     * buffer will be used to hold the raw image data and removed from the
     * queue. Then raw image callback is invoked with the buffer. If a raw
     * image frame arrives but there is no buffer left, the frame is
     * discarded. Applications should add buffers back when they finish
     * processing the data in them by calling this method again in order
     * to avoid running out of raw image callback buffers.
     *
     * <p>The size of the buffer is determined by multiplying the raw image
     * width, height, and bytes per pixel. The width and height can be
     * read from {@link Camera.Parameters#getPictureSize()}. Bytes per pixel
     * can be computed from
     * {@link android.graphics.ImageFormat#getBitsPerPixel(int)} / 8,
     * using the image format from {@link Camera.Parameters#getPreviewFormat()}.
     *
     * <p>This method is only necessary when the PictureCallbck for raw image
     * is used while calling {@link #takePicture(Camera.ShutterCallback,
     * Camera.PictureCallback, Camera.PictureCallback, Camera.PictureCallback)}.
     *
     * <p>Please note that by calling this method, the mode for
     * application-managed callback buffers is triggered. If this method has
     * never been called, null will be returned by the raw image callback since
     * there is no image callback buffer available. Furthermore, When a supplied
     * buffer is too small to hold the raw image data, raw image callback will
     * return null and the buffer will be removed from the buffer queue.
     *
     * @param callbackBuffer the buffer to add to the raw image callback buffer
     *     queue. The size should be width * height * (bits per pixel) / 8. An
     *     null callbackBuffer will be ignored and won't be added to the queue.
     *
     * @see #takePicture(Camera.ShutterCallback,
     * Camera.PictureCallback, Camera.PictureCallback, Camera.PictureCallback)}.
     *
     * {@hide}
     */
    public final void addRawImageCallbackBuffer(byte[] callbackBuffer)
    {
        addCallbackBuffer(callbackBuffer, CAMERA_MSG_RAW_IMAGE);
    }

    private final void addCallbackBuffer(byte[] callbackBuffer, int msgType)
    {
        // CAMERA_MSG_VIDEO_FRAME may be allowed in the future.
        if (msgType != CAMERA_MSG_PREVIEW_FRAME &&
            msgType != CAMERA_MSG_RAW_IMAGE) {
            throw new IllegalArgumentException(
                            "Unsupported message type: " + msgType);
        }

        _addCallbackBuffer(callbackBuffer, msgType);
    }

    private native final void _addCallbackBuffer(
                                byte[] callbackBuffer, int msgType);

    /**
     * <p>Create a {@link android.renderscript RenderScript}
     * {@link android.renderscript.Allocation Allocation} to use as a
     * destination of preview callback frames. Use
     * {@link #setPreviewCallbackAllocation setPreviewCallbackAllocation} to use
     * the created Allocation as a destination for camera preview frames.</p>
     *
     * <p>The Allocation will be created with a YUV type, and its contents must
     * be accessed within Renderscript with the {@code rsGetElementAtYuv_*}
     * accessor methods. Its size will be based on the current
     * {@link Parameters#getPreviewSize preview size} configured for this
     * camera.</p>
     *
     * @param rs the RenderScript context for this Allocation.
     * @param usage additional usage flags to set for the Allocation. The usage
     *   flag {@link android.renderscript.Allocation#USAGE_IO_INPUT} will always
     *   be set on the created Allocation, but additional flags may be provided
     *   here.
     * @return a new YUV-type Allocation with dimensions equal to the current
     *   preview size.
     * @throws RSIllegalArgumentException if the usage flags are not compatible
     *   with an YUV Allocation.
     * @see #setPreviewCallbackAllocation
     * @hide
     */
    public final Allocation createPreviewAllocation(RenderScript rs, int usage)
            throws RSIllegalArgumentException {
        Parameters p = getParameters();
        Size previewSize = p.getPreviewSize();
        Type.Builder yuvBuilder = new Type.Builder(rs,
                Element.createPixel(rs,
                        Element.DataType.UNSIGNED_8,
                        Element.DataKind.PIXEL_YUV));
        // Use YV12 for wide compatibility. Changing this requires also
        // adjusting camera service's format selection.
        yuvBuilder.setYuvFormat(ImageFormat.YV12);
        yuvBuilder.setX(previewSize.width);
        yuvBuilder.setY(previewSize.height);

        Allocation a = Allocation.createTyped(rs, yuvBuilder.create(),
                usage | Allocation.USAGE_IO_INPUT);

        return a;
    }

    /**
     * <p>Set an {@link android.renderscript.Allocation Allocation} as the
     * target of preview callback data. Use this method for efficient processing
     * of camera preview data with RenderScript. The Allocation must be created
     * with the {@link #createPreviewAllocation createPreviewAllocation }
     * method.</p>
     *
     * <p>Setting a preview allocation will disable any active preview callbacks
     * set by {@link #setPreviewCallback setPreviewCallback} or
     * {@link #setPreviewCallbackWithBuffer setPreviewCallbackWithBuffer}, and
     * vice versa. Using a preview allocation still requires an active standard
     * preview target to be set, either with
     * {@link #setPreviewTexture setPreviewTexture} or
     * {@link #setPreviewDisplay setPreviewDisplay}.</p>
     *
     * <p>To be notified when new frames are available to the Allocation, use
     * {@link android.renderscript.Allocation#setIoInputNotificationHandler Allocation.setIoInputNotificationHandler}. To
     * update the frame currently accessible from the Allocation to the latest
     * preview frame, call
     * {@link android.renderscript.Allocation#ioReceive Allocation.ioReceive}.</p>
     *
     * <p>To disable preview into the Allocation, call this method with a
     * {@code null} parameter.</p>
     *
     * <p>Once a preview allocation is set, the preview size set by
     * {@link Parameters#setPreviewSize setPreviewSize} cannot be changed. If
     * you wish to change the preview size, first remove the preview allocation
     * by calling {@code setPreviewCallbackAllocation(null)}, then change the
     * preview size, create a new preview Allocation with
     * {@link #createPreviewAllocation createPreviewAllocation}, and set it as
     * the new preview callback allocation target.</p>
     *
     * <p>If you are using the preview data to create video or still images,
     * strongly consider using {@link android.media.MediaActionSound} to
     * properly indicate image capture or recording start/stop to the user.</p>
     *
     * @param previewAllocation the allocation to use as destination for preview
     * @throws IOException if configuring the camera to use the Allocation for
     *   preview fails.
     * @throws IllegalArgumentException if the Allocation's dimensions or other
     *   parameters don't meet the requirements.
     * @see #createPreviewAllocation
     * @see #setPreviewCallback
     * @see #setPreviewCallbackWithBuffer
     * @hide
     */
    public final void setPreviewCallbackAllocation(Allocation previewAllocation)
            throws IOException {
        Surface previewSurface = null;
        if (previewAllocation != null) {
             Parameters p = getParameters();
             Size previewSize = p.getPreviewSize();
             if (previewSize.width != previewAllocation.getType().getX() ||
                     previewSize.height != previewAllocation.getType().getY()) {
                 throw new IllegalArgumentException(
                     "Allocation dimensions don't match preview dimensions: " +
                     "Allocation is " +
                     previewAllocation.getType().getX() +
                     ", " +
                     previewAllocation.getType().getY() +
                     ". Preview is " + previewSize.width + ", " +
                     previewSize.height);
             }
             if ((previewAllocation.getUsage() &
                             Allocation.USAGE_IO_INPUT) == 0) {
                 throw new IllegalArgumentException(
                     "Allocation usage does not include USAGE_IO_INPUT");
             }
             if (previewAllocation.getType().getElement().getDataKind() !=
                     Element.DataKind.PIXEL_YUV) {
                 throw new IllegalArgumentException(
                     "Allocation is not of a YUV type");
             }
             previewSurface = previewAllocation.getSurface();
             mUsingPreviewAllocation = true;
         } else {
             mUsingPreviewAllocation = false;
         }
         setPreviewCallbackSurface(previewSurface);
    }

    private native final void setPreviewCallbackSurface(Surface s);

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
                PreviewCallback pCb = mPreviewCallback;
                if (pCb != null) {
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
                    pCb.onPreviewFrame((byte[])msg.obj, mCamera);
                }
                return;

            case CAMERA_MSG_POSTVIEW_FRAME:
                if (mPostviewCallback != null) {
                    mPostviewCallback.onPictureTaken((byte[])msg.obj, mCamera);
                }
                return;

            case CAMERA_MSG_FOCUS:
                AutoFocusCallback cb = null;
                synchronized (mAutoFocusCallbackLock) {
                    cb = mAutoFocusCallback;
                }
                if (cb != null) {
                    boolean success = msg.arg1 == 0 ? false : true;
                    cb.onAutoFocus(success, mCamera);
                }
                return;

            case CAMERA_MSG_ZOOM:
                if (mZoomListener != null) {
                    mZoomListener.onZoomChange(msg.arg1, msg.arg2 != 0, mCamera);
                }
                return;

            case CAMERA_MSG_PREVIEW_METADATA:
                if (mFaceListener != null) {
                    mFaceListener.onFaceDetection((Face[])msg.obj, mCamera);
                }
                return;

            case CAMERA_MSG_ERROR :
                Log.e(TAG, "Error " + msg.arg1);
                if (mErrorCallback != null) {
                    mErrorCallback.onError(msg.arg1, mCamera);
                }
                return;

            case CAMERA_MSG_FOCUS_MOVE:
                if (mAutoFocusMoveCallback != null) {
                    mAutoFocusMoveCallback.onAutoFocusMoving(msg.arg1 == 0 ? false : true, mCamera);
                }
                return;
            /* ### QC ADD-ONS: START */
            case CAMERA_MSG_STATS_DATA:
                int statsdata[] = new int[257];
                for(int i =0; i<257; i++ ) {
                   statsdata[i] = byteToInt( (byte[])msg.obj, i*4);
                }
                if (mCameraDataCallback != null) {
                     mCameraDataCallback.onCameraData(statsdata, mCamera);
                }
                return;

            case CAMERA_MSG_META_DATA:
                if (mCameraMetaDataCallback != null) {
                    mCameraMetaDataCallback.onCameraMetaData((byte[])msg.obj, mCamera);
                }
                return;
            /* ### QC ADD-ONS: END */
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
     * Callback interface used to notify on completion of camera auto focus.
     *
     * <p>Devices that do not support auto-focus will receive a "fake"
     * callback to this interface. If your application needs auto-focus and
     * should not be installed on devices <em>without</em> auto-focus, you must
     * declare that your app uses the
     * {@code android.hardware.camera.autofocus} feature, in the
     * <a href="{@docRoot}guide/topics/manifest/uses-feature-element.html">&lt;uses-feature></a>
     * manifest element.</p>
     *
     * @see #autoFocus(AutoFocusCallback)
     */
    public interface AutoFocusCallback
    {
        /**
         * Called when the camera auto focus completes.  If the camera
         * does not support auto-focus and autoFocus is called,
         * onAutoFocus will be called immediately with a fake value of
         * <code>success</code> set to <code>true</code>.
         *
         * The auto-focus routine does not lock auto-exposure and auto-white
         * balance after it completes.
         *
         * @param success true if focus was successful, false if otherwise
         * @param camera  the Camera service object
         * @see android.hardware.Camera.Parameters#setAutoExposureLock(boolean)
         * @see android.hardware.Camera.Parameters#setAutoWhiteBalanceLock(boolean)
         */
        void onAutoFocus(boolean success, Camera camera);
    }

    /**
     * Starts camera auto-focus and registers a callback function to run when
     * the camera is focused.  This method is only valid when preview is active
     * (between {@link #startPreview()} and before {@link #stopPreview()}).
     *
     * <p>Callers should check
     * {@link android.hardware.Camera.Parameters#getFocusMode()} to determine if
     * this method should be called. If the camera does not support auto-focus,
     * it is a no-op and {@link AutoFocusCallback#onAutoFocus(boolean, Camera)}
     * callback will be called immediately.
     *
     * <p>If your application should not be installed
     * on devices without auto-focus, you must declare that your application
     * uses auto-focus with the
     * <a href="{@docRoot}guide/topics/manifest/uses-feature-element.html">&lt;uses-feature></a>
     * manifest element.</p>
     *
     * <p>If the current flash mode is not
     * {@link android.hardware.Camera.Parameters#FLASH_MODE_OFF}, flash may be
     * fired during auto-focus, depending on the driver and camera hardware.<p>
     *
     * <p>Auto-exposure lock {@link android.hardware.Camera.Parameters#getAutoExposureLock()}
     * and auto-white balance locks {@link android.hardware.Camera.Parameters#getAutoWhiteBalanceLock()}
     * do not change during and after autofocus. But auto-focus routine may stop
     * auto-exposure and auto-white balance transiently during focusing.
     *
     * <p>Stopping preview with {@link #stopPreview()}, or triggering still
     * image capture with {@link #takePicture(Camera.ShutterCallback,
     * Camera.PictureCallback, Camera.PictureCallback)}, will not change the
     * the focus position. Applications must call cancelAutoFocus to reset the
     * focus.</p>
     *
     * <p>If autofocus is successful, consider using
     * {@link android.media.MediaActionSound} to properly play back an autofocus
     * success sound to the user.</p>
     *
     * @param cb the callback to run
     * @see #cancelAutoFocus()
     * @see android.hardware.Camera.Parameters#setAutoExposureLock(boolean)
     * @see android.hardware.Camera.Parameters#setAutoWhiteBalanceLock(boolean)
     * @see android.media.MediaActionSound
     */
    public final void autoFocus(AutoFocusCallback cb)
    {
        synchronized (mAutoFocusCallbackLock) {
            mAutoFocusCallback = cb;
        }
        native_autoFocus();
    }
    private native final void native_autoFocus();

    /**
     * Cancels any auto-focus function in progress.
     * Whether or not auto-focus is currently in progress,
     * this function will return the focus position to the default.
     * If the camera does not support auto-focus, this is a no-op.
     *
     * @see #autoFocus(Camera.AutoFocusCallback)
     */
    public final void cancelAutoFocus()
    {
        synchronized (mAutoFocusCallbackLock) {
            mAutoFocusCallback = null;
        }
        native_cancelAutoFocus();
        // CAMERA_MSG_FOCUS should be removed here because the following
        // scenario can happen:
        // - An application uses the same thread for autoFocus, cancelAutoFocus
        //   and looper thread.
        // - The application calls autoFocus.
        // - HAL sends CAMERA_MSG_FOCUS, which enters the looper message queue.
        //   Before event handler's handleMessage() is invoked, the application
        //   calls cancelAutoFocus and autoFocus.
        // - The application gets the old CAMERA_MSG_FOCUS and thinks autofocus
        //   has been completed. But in fact it is not.
        //
        // As documented in the beginning of the file, apps should not use
        // multiple threads to call autoFocus and cancelAutoFocus at the same
        // time. It is HAL's responsibility not to send a CAMERA_MSG_FOCUS
        // message after native_cancelAutoFocus is called.
        mEventHandler.removeMessages(CAMERA_MSG_FOCUS);
    }
    private native final void native_cancelAutoFocus();

    /**
     * Callback interface used to notify on auto focus start and stop.
     *
     * <p>This is only supported in continuous autofocus modes -- {@link
     * Parameters#FOCUS_MODE_CONTINUOUS_VIDEO} and {@link
     * Parameters#FOCUS_MODE_CONTINUOUS_PICTURE}. Applications can show
     * autofocus animation based on this.</p>
     */
    public interface AutoFocusMoveCallback
    {
        /**
         * Called when the camera auto focus starts or stops.
         *
         * @param start true if focus starts to move, false if focus stops to move
         * @param camera the Camera service object
         */
        void onAutoFocusMoving(boolean start, Camera camera);
    }

    /**
     * Sets camera auto-focus move callback.
     *
     * @param cb the callback to run
     */
    public void setAutoFocusMoveCallback(AutoFocusMoveCallback cb) {
        mAutoFocusMoveCallback = cb;
        enableFocusMoveCallback((mAutoFocusMoveCallback != null) ? 1 : 0);
    }

    private native void enableFocusMoveCallback(int enable);

    /**
     * Send a raw command to the camera driver
     * @hide
     */
    public native void sendRawCommand(int arg1, int arg2, int arg3);

    /**
     * Callback interface used to signal the moment of actual image capture.
     *
     * @see #takePicture(ShutterCallback, PictureCallback, PictureCallback, PictureCallback)
     */
    public interface ShutterCallback
    {
        /**
         * Called as near as possible to the moment when a photo is captured
         * from the sensor.  This is a good opportunity to play a shutter sound
         * or give other feedback of camera operation.  This may be some time
         * after the photo was triggered, but some time before the actual data
         * is available.
         */
        void onShutter();
    }

    /**
     * Callback interface used to supply image data from a photo capture.
     *
     * @see #takePicture(ShutterCallback, PictureCallback, PictureCallback, PictureCallback)
     */
    public interface PictureCallback {
        /**
         * Called when image data is available after a picture is taken.
         * The format of the data depends on the context of the callback
         * and {@link Camera.Parameters} settings.
         *
         * @param data   a byte array of the picture data
         * @param camera the Camera service object
         */
        void onPictureTaken(byte[] data, Camera camera);
    };

    /**
     * Equivalent to takePicture(shutter, raw, null, jpeg).
     *
     * @see #takePicture(ShutterCallback, PictureCallback, PictureCallback, PictureCallback)
     */
    public final void takePicture(ShutterCallback shutter, PictureCallback raw,
            PictureCallback jpeg) {
        takePicture(shutter, raw, null, jpeg);
    }
    private native final void native_takePicture(int msgType);

    /**
     * Triggers an asynchronous image capture. The camera service will initiate
     * a series of callbacks to the application as the image capture progresses.
     * The shutter callback occurs after the image is captured. This can be used
     * to trigger a sound to let the user know that image has been captured. The
     * raw callback occurs when the raw image data is available (NOTE: the data
     * will be null if there is no raw image callback buffer available or the
     * raw image callback buffer is not large enough to hold the raw image).
     * The postview callback occurs when a scaled, fully processed postview
     * image is available (NOTE: not all hardware supports this). The jpeg
     * callback occurs when the compressed image is available. If the
     * application does not need a particular callback, a null can be passed
     * instead of a callback method.
     *
     * <p>This method is only valid when preview is active (after
     * {@link #startPreview()}).  Preview will be stopped after the image is
     * taken; callers must call {@link #startPreview()} again if they want to
     * re-start preview or take more pictures. This should not be called between
     * {@link android.media.MediaRecorder#start()} and
     * {@link android.media.MediaRecorder#stop()}.
     *
     * <p>After calling this method, you must not call {@link #startPreview()}
     * or take another picture until the JPEG callback has returned.
     *
     * @param shutter   the callback for image capture moment, or null
     * @param raw       the callback for raw (uncompressed) image data, or null
     * @param postview  callback with postview image data, may be null
     * @param jpeg      the callback for JPEG image data, or null
     */
    public final void takePicture(ShutterCallback shutter, PictureCallback raw,
            PictureCallback postview, PictureCallback jpeg) {
        mShutterCallback = shutter;
        mRawImageCallback = raw;
        mPostviewCallback = postview;
        mJpegCallback = jpeg;

        // If callback is not set, do not send me callbacks.
        int msgType = 0;
        if (mShutterCallback != null) {
            msgType |= CAMERA_MSG_SHUTTER;
        }
        if (mRawImageCallback != null) {
            msgType |= CAMERA_MSG_RAW_IMAGE;
        }
        if (mPostviewCallback != null) {
            msgType |= CAMERA_MSG_POSTVIEW_FRAME;
        }
        if (mJpegCallback != null) {
            msgType |= CAMERA_MSG_COMPRESSED_IMAGE;
        }

        native_takePicture(msgType);
        mFaceDetectionRunning = false;
    }

    /**
     * Zooms to the requested value smoothly. The driver will notify {@link
     * OnZoomChangeListener} of the zoom value and whether zoom is stopped at
     * the time. For example, suppose the current zoom is 0 and startSmoothZoom
     * is called with value 3. The
     * {@link Camera.OnZoomChangeListener#onZoomChange(int, boolean, Camera)}
     * method will be called three times with zoom values 1, 2, and 3.
     * Applications can call {@link #stopSmoothZoom} to stop the zoom earlier.
     * Applications should not call startSmoothZoom again or change the zoom
     * value before zoom stops. If the supplied zoom value equals to the current
     * zoom value, no zoom callback will be generated. This method is supported
     * if {@link android.hardware.Camera.Parameters#isSmoothZoomSupported}
     * returns true.
     *
     * @param value zoom value. The valid range is 0 to {@link
     *              android.hardware.Camera.Parameters#getMaxZoom}.
     * @throws IllegalArgumentException if the zoom value is invalid.
     * @throws RuntimeException if the method fails.
     * @see #setZoomChangeListener(OnZoomChangeListener)
     */
    public native final void startSmoothZoom(int value);

    /**
     * Stops the smooth zoom. Applications should wait for the {@link
     * OnZoomChangeListener} to know when the zoom is actually stopped. This
     * method is supported if {@link
     * android.hardware.Camera.Parameters#isSmoothZoomSupported} is true.
     *
     * @throws RuntimeException if the method fails.
     */
    public native final void stopSmoothZoom();

    /**
     * Set the clockwise rotation of preview display in degrees. This affects
     * the preview frames and the picture displayed after snapshot. This method
     * is useful for portrait mode applications. Note that preview display of
     * front-facing cameras is flipped horizontally before the rotation, that
     * is, the image is reflected along the central vertical axis of the camera
     * sensor. So the users can see themselves as looking into a mirror.
     *
     * <p>This does not affect the order of byte array passed in {@link
     * PreviewCallback#onPreviewFrame}, JPEG pictures, or recorded videos. This
     * method is not allowed to be called during preview.
     *
     * <p>If you want to make the camera image show in the same orientation as
     * the display, you can use the following code.
     * <pre>
     * public static void setCameraDisplayOrientation(Activity activity,
     *         int cameraId, android.hardware.Camera camera) {
     *     android.hardware.Camera.CameraInfo info =
     *             new android.hardware.Camera.CameraInfo();
     *     android.hardware.Camera.getCameraInfo(cameraId, info);
     *     int rotation = activity.getWindowManager().getDefaultDisplay()
     *             .getRotation();
     *     int degrees = 0;
     *     switch (rotation) {
     *         case Surface.ROTATION_0: degrees = 0; break;
     *         case Surface.ROTATION_90: degrees = 90; break;
     *         case Surface.ROTATION_180: degrees = 180; break;
     *         case Surface.ROTATION_270: degrees = 270; break;
     *     }
     *
     *     int result;
     *     if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
     *         result = (info.orientation + degrees) % 360;
     *         result = (360 - result) % 360;  // compensate the mirror
     *     } else {  // back-facing
     *         result = (info.orientation - degrees + 360) % 360;
     *     }
     *     camera.setDisplayOrientation(result);
     * }
     * </pre>
     *
     * <p>Starting from API level 14, this method can be called when preview is
     * active.
     *
     * @param degrees the angle that the picture will be rotated clockwise.
     *                Valid values are 0, 90, 180, and 270. The starting
     *                position is 0 (landscape).
     * @see #setPreviewDisplay(SurfaceHolder)
     */
    public native final void setDisplayOrientation(int degrees);

    /**
     * <p>Enable or disable the default shutter sound when taking a picture.</p>
     *
     * <p>By default, the camera plays the system-defined camera shutter sound
     * when {@link #takePicture} is called. Using this method, the shutter sound
     * can be disabled. It is strongly recommended that an alternative shutter
     * sound is played in the {@link ShutterCallback} when the system shutter
     * sound is disabled.</p>
     *
     * <p>Note that devices may not always allow disabling the camera shutter
     * sound. If the shutter sound state cannot be set to the desired value,
     * this method will return false. {@link CameraInfo#canDisableShutterSound}
     * can be used to determine whether the device will allow the shutter sound
     * to be disabled.</p>
     *
     * @param enabled whether the camera should play the system shutter sound
     *                when {@link #takePicture takePicture} is called.
     * @return {@code true} if the shutter sound state was successfully
     *         changed. {@code false} if the shutter sound state could not be
     *         changed. {@code true} is also returned if shutter sound playback
     *         is already set to the requested state.
     * @see #takePicture
     * @see CameraInfo#canDisableShutterSound
     * @see ShutterCallback
     */
    public final boolean enableShutterSound(boolean enabled) {
        if (!enabled) {
            IBinder b = ServiceManager.getService(Context.AUDIO_SERVICE);
            IAudioService audioService = IAudioService.Stub.asInterface(b);
            try {
                if (audioService.isCameraSoundForced()) return false;
            } catch (RemoteException e) {
                Log.e(TAG, "Audio service is unavailable for queries");
            }
        }
        return _enableShutterSound(enabled);
    }

    private native final boolean _enableShutterSound(boolean enabled);

    /**
     * Callback interface for zoom changes during a smooth zoom operation.
     *
     * @see #setZoomChangeListener(OnZoomChangeListener)
     * @see #startSmoothZoom(int)
     */
    public interface OnZoomChangeListener
    {
        /**
         * Called when the zoom value has changed during a smooth zoom.
         *
         * @param zoomValue the current zoom value. In smooth zoom mode, camera
         *                  calls this for every new zoom value.
         * @param stopped whether smooth zoom is stopped. If the value is true,
         *                this is the last zoom update for the application.
         * @param camera  the Camera service object
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

    /**
     * Callback interface for face detected in the preview frame.
     *
     */
    public interface FaceDetectionListener
    {
        /**
         * Notify the listener of the detected faces in the preview frame.
         *
         * @param faces The detected faces in a list
         * @param camera  The {@link Camera} service object
         */
        void onFaceDetection(Face[] faces, Camera camera);
    }

    /**
     * Registers a listener to be notified about the faces detected in the
     * preview frame.
     *
     * @param listener the listener to notify
     * @see #startFaceDetection()
     */
    public final void setFaceDetectionListener(FaceDetectionListener listener)
    {
        mFaceListener = listener;
    }

    /**
     * Starts the face detection. This should be called after preview is started.
     * The camera will notify {@link FaceDetectionListener} of the detected
     * faces in the preview frame. The detected faces may be the same as the
     * previous ones. Applications should call {@link #stopFaceDetection} to
     * stop the face detection. This method is supported if {@link
     * Parameters#getMaxNumDetectedFaces()} returns a number larger than 0.
     * If the face detection has started, apps should not call this again.
     *
     * <p>When the face detection is running, {@link Parameters#setWhiteBalance(String)},
     * {@link Parameters#setFocusAreas(List)}, and {@link Parameters#setMeteringAreas(List)}
     * have no effect. The camera uses the detected faces to do auto-white balance,
     * auto exposure, and autofocus.
     *
     * <p>If the apps call {@link #autoFocus(AutoFocusCallback)}, the camera
     * will stop sending face callbacks. The last face callback indicates the
     * areas used to do autofocus. After focus completes, face detection will
     * resume sending face callbacks. If the apps call {@link
     * #cancelAutoFocus()}, the face callbacks will also resume.</p>
     *
     * <p>After calling {@link #takePicture(Camera.ShutterCallback, Camera.PictureCallback,
     * Camera.PictureCallback)} or {@link #stopPreview()}, and then resuming
     * preview with {@link #startPreview()}, the apps should call this method
     * again to resume face detection.</p>
     *
     * @throws IllegalArgumentException if the face detection is unsupported.
     * @throws RuntimeException if the method fails or the face detection is
     *         already running.
     * @see FaceDetectionListener
     * @see #stopFaceDetection()
     * @see Parameters#getMaxNumDetectedFaces()
     */
    public final void startFaceDetection() {
        if (mFaceDetectionRunning) {
            throw new RuntimeException("Face detection is already running");
        }
        _startFaceDetection(CAMERA_FACE_DETECTION_HW);
        mFaceDetectionRunning = true;
    }

    /**
     * Stops the face detection.
     *
     * @see #startFaceDetection()
     */
    public final void stopFaceDetection() {
        _stopFaceDetection();
        mFaceDetectionRunning = false;
    }

    private native final void _startFaceDetection(int type);
    private native final void _stopFaceDetection();

    /**
     * Information about a face identified through camera face detection.
     *
     * <p>When face detection is used with a camera, the {@link FaceDetectionListener} returns a
     * list of face objects for use in focusing and metering.</p>
     *
     * @see FaceDetectionListener
     */
    public static class Face {
        /**
         * Create an empty face.
         */
        public Face() {
        }

        /**
         * Bounds of the face. (-1000, -1000) represents the top-left of the
         * camera field of view, and (1000, 1000) represents the bottom-right of
         * the field of view. For example, suppose the size of the viewfinder UI
         * is 800x480. The rect passed from the driver is (-1000, -1000, 0, 0).
         * The corresponding viewfinder rect should be (0, 0, 400, 240). It is
         * guaranteed left < right and top < bottom. The coordinates can be
         * smaller than -1000 or bigger than 1000. But at least one vertex will
         * be within (-1000, -1000) and (1000, 1000).
         *
         * <p>The direction is relative to the sensor orientation, that is, what
         * the sensor sees. The direction is not affected by the rotation or
         * mirroring of {@link #setDisplayOrientation(int)}. The face bounding
         * rectangle does not provide any information about face orientation.</p>
         *
         * <p>Here is the matrix to convert driver coordinates to View coordinates
         * in pixels.</p>
         * <pre>
         * Matrix matrix = new Matrix();
         * CameraInfo info = CameraHolder.instance().getCameraInfo()[cameraId];
         * // Need mirror for front camera.
         * boolean mirror = (info.facing == CameraInfo.CAMERA_FACING_FRONT);
         * matrix.setScale(mirror ? -1 : 1, 1);
         * // This is the value for android.hardware.Camera.setDisplayOrientation.
         * matrix.postRotate(displayOrientation);
         * // Camera driver coordinates range from (-1000, -1000) to (1000, 1000).
         * // UI coordinates range from (0, 0) to (width, height).
         * matrix.postScale(view.getWidth() / 2000f, view.getHeight() / 2000f);
         * matrix.postTranslate(view.getWidth() / 2f, view.getHeight() / 2f);
         * </pre>
         *
         * @see #startFaceDetection()
         */
        public Rect rect;

        /**
         * <p>The confidence level for the detection of the face. The range is 1 to
         * 100. 100 is the highest confidence.</p>
         *
         * <p>Depending on the device, even very low-confidence faces may be
         * listed, so applications should filter out faces with low confidence,
         * depending on the use case. For a typical point-and-shoot camera
         * application that wishes to display rectangles around detected faces,
         * filtering out faces with confidence less than 50 is recommended.</p>
         *
         * @see #startFaceDetection()
         */
        public int score;

        /**
         * An unique id per face while the face is visible to the tracker. If
         * the face leaves the field-of-view and comes back, it will get a new
         * id. This is an optional field, may not be supported on all devices.
         * If not supported, id will always be set to -1. The optional fields
         * are supported as a set. Either they are all valid, or none of them
         * are.
         */
        public int id = -1;

        /**
         * The coordinates of the center of the left eye. The coordinates are in
         * the same space as the ones for {@link #rect}. This is an optional
         * field, may not be supported on all devices. If not supported, the
         * value will always be set to null. The optional fields are supported
         * as a set. Either they are all valid, or none of them are.
         */
        public Point leftEye = null;

        /**
         * The coordinates of the center of the right eye. The coordinates are
         * in the same space as the ones for {@link #rect}.This is an optional
         * field, may not be supported on all devices. If not supported, the
         * value will always be set to null. The optional fields are supported
         * as a set. Either they are all valid, or none of them are.
         */
        public Point rightEye = null;

        /**
         * The coordinates of the center of the mouth.  The coordinates are in
         * the same space as the ones for {@link #rect}. This is an optional
         * field, may not be supported on all devices. If not supported, the
         * value will always be set to null. The optional fields are supported
         * as a set. Either they are all valid, or none of them are.
         */
        public Point mouth = null;
    }

    // Error codes match the enum in include/ui/Camera.h

    /**
     * Unspecified camera error.
     * @see Camera.ErrorCallback
     */
    public static final int CAMERA_ERROR_UNKNOWN = 1;

    /**
     * Media server died. In this case, the application must release the
     * Camera object and instantiate a new one.
     * @see Camera.ErrorCallback
     */
    public static final int CAMERA_ERROR_SERVER_DIED = 100;

    /**
     * Callback interface for camera error notification.
     *
     * @see #setErrorCallback(ErrorCallback)
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
     * @param cb The callback to run
     */
    public final void setErrorCallback(ErrorCallback cb)
    {
        mErrorCallback = cb;
    }

    private native final void native_setParameters(String params);
    private native final String native_getParameters();

    /**
     * Changes the settings for this Camera service.
     *
     * @param params the Parameters to use for this Camera service
     * @throws RuntimeException if any parameter is invalid or not supported.
     * @see #getParameters()
     */
    public void setParameters(Parameters params) {
        // If using preview allocations, don't allow preview size changes
        if (mUsingPreviewAllocation) {
            Size newPreviewSize = params.getPreviewSize();
            Size currentPreviewSize = getParameters().getPreviewSize();
            if (newPreviewSize.width != currentPreviewSize.width ||
                    newPreviewSize.height != currentPreviewSize.height) {
                throw new IllegalStateException("Cannot change preview size" +
                        " while a preview allocation is configured.");
            }
        }

        native_setParameters(params.flatten());
    }

    /**
     * Returns the current settings for this Camera service.
     * If modifications are made to the returned Parameters, they must be passed
     * to {@link #setParameters(Camera.Parameters)} to take effect.
     *
     * @see #setParameters(Camera.Parameters)
     */
    public Parameters getParameters() {
        Parameters p = new Parameters();
        String s = native_getParameters();
        p.unflatten(s);
        return p;
    }

    /**
     * Returns an empty {@link Parameters} for testing purpose.
     *
     * @return a Parameter object.
     *
     * @hide
     */
    public static Parameters getEmptyParameters() {
        Camera camera = new Camera();
        return camera.new Parameters();
    }

    /* ### QC ADD-ONS: START */
    private static int byteToInt(byte[] b, int offset) {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int shift = (4 - 1 - i) * 8;
            value += (b[(3-i) + offset] & 0x000000FF) << shift;
        }
        return value;
    }
    /** @hide
     * Handles the callback for when Camera Data is available.
     * data is read from the camera.
     */
    public interface CameraDataCallback {
        /**
         * Callback for when camera data is available.
         *
         * @param data   a int array of the camera data
         * @param camera the Camera service object
         */
        void onCameraData(int[] data, Camera camera);
    };

    /** @hide
     * Set camera histogram mode and registers a callback function to run.
     *  Only valid after startPreview() has been called.
     *
     * @param cb the callback to run
     */
    public final void setHistogramMode(CameraDataCallback cb)
    {
        mCameraDataCallback = cb;
        native_setHistogramMode(cb!=null);
    }
    private native final void native_setHistogramMode(boolean mode);

    /** @hide
     * Set camera histogram command to send data.
     *
     */
    public final void sendHistogramData()
    {
        native_sendHistogramData();
    }
    private native final void native_sendHistogramData();

    /** @hide
     * Handles the callback for when Camera Meta Data is available.
     * Meta data is read from the camera.
     */
    public interface CameraMetaDataCallback {
        /**
         * Callback for when camera meta data is available.
         *
         * @param data   a byte array of the camera meta data
         * @param camera the Camera service object
         */
        void onCameraMetaData(byte[] data, Camera camera);
    };

    /** @hide
     * Set camera meta data and registers a callback function to run.
     *  Only valid after startPreview() has been called.
     *
     * @param cb the callback to run
     */
    public final void setMetadataCb(CameraMetaDataCallback cb)
    {
        mCameraMetaDataCallback = cb;
        native_setMetadataCb(cb!=null);
    }
    private native final void native_setMetadataCb(boolean mode);

    /** @hide
     * Set camera face detection command to send meta data.
     */
    public final void sendMetaData()
    {
        native_sendMetaData();
    }
    private native final void native_sendMetaData();

     /** @hide
     * Handles the Touch Co-ordinate.
     */
     public class Coordinate {
        /**
         * Sets the x,y co-ordinates for a touch event
         *
         * @param x the x co-ordinate (pixels)
         * @param y the y co-ordinate (pixels)
         */
        public Coordinate(int x, int y) {
            xCoordinate = x;
            yCoordinate = y;
        }
        /**
         * Compares {@code obj} to this co-ordinate.
         *
         * @param obj the object to compare this co-ordinate with.
         * @return {@code true} if the xCoordinate and yCoordinate of {@code obj} is the
         *         same as those of this coordinate. {@code false} otherwise.
         */
        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Coordinate)) {
                return false;
            }
            Coordinate c = (Coordinate) obj;
            return xCoordinate == c.xCoordinate && yCoordinate == c.yCoordinate;
        }

        /** x co-ordinate for the touch event*/
        public int xCoordinate;

        /** y co-ordinate for the touch event */
        public int yCoordinate;
    };
    /* ### QC ADD-ONS: END */
    /**
     * Image size (width and height dimensions).
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
     * <p>The Area class is used for choosing specific metering and focus areas for
     * the camera to use when calculating auto-exposure, auto-white balance, and
     * auto-focus.</p>
     *
     * <p>To find out how many simultaneous areas a given camera supports, use
     * {@link Parameters#getMaxNumMeteringAreas()} and
     * {@link Parameters#getMaxNumFocusAreas()}. If metering or focusing area
     * selection is unsupported, these methods will return 0.</p>
     *
     * <p>Each Area consists of a rectangle specifying its bounds, and a weight
     * that determines its importance. The bounds are relative to the camera's
     * current field of view. The coordinates are mapped so that (-1000, -1000)
     * is always the top-left corner of the current field of view, and (1000,
     * 1000) is always the bottom-right corner of the current field of
     * view. Setting Areas with bounds outside that range is not allowed. Areas
     * with zero or negative width or height are not allowed.</p>
     *
     * <p>The weight must range from 1 to 1000, and represents a weight for
     * every pixel in the area. This means that a large metering area with
     * the same weight as a smaller area will have more effect in the
     * metering result.  Metering areas can overlap and the driver
     * will add the weights in the overlap region.</p>
     *
     * @see Parameters#setFocusAreas(List)
     * @see Parameters#getFocusAreas()
     * @see Parameters#getMaxNumFocusAreas()
     * @see Parameters#setMeteringAreas(List)
     * @see Parameters#getMeteringAreas()
     * @see Parameters#getMaxNumMeteringAreas()
     */
    public static class Area {
        /**
         * Create an area with specified rectangle and weight.
         *
         * @param rect the bounds of the area.
         * @param weight the weight of the area.
         */
        public Area(Rect rect, int weight) {
            this.rect = rect;
            this.weight = weight;
        }
        /**
         * Compares {@code obj} to this area.
         *
         * @param obj the object to compare this area with.
         * @return {@code true} if the rectangle and weight of {@code obj} is
         *         the same as those of this area. {@code false} otherwise.
         */
        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Area)) {
                return false;
            }
            Area a = (Area) obj;
            if (rect == null) {
                if (a.rect != null) return false;
            } else {
                if (!rect.equals(a.rect)) return false;
            }
            return weight == a.weight;
        }

        /**
         * Bounds of the area. (-1000, -1000) represents the top-left of the
         * camera field of view, and (1000, 1000) represents the bottom-right of
         * the field of view. Setting bounds outside that range is not
         * allowed. Bounds with zero or negative width or height are not
         * allowed.
         *
         * @see Parameters#getFocusAreas()
         * @see Parameters#getMeteringAreas()
         */
        public Rect rect;

        /**
         * Weight of the area. The weight must range from 1 to 1000, and
         * represents a weight for every pixel in the area. This means that a
         * large metering area with the same weight as a smaller area will have
         * more effect in the metering result.  Metering areas can overlap and
         * the driver will add the weights in the overlap region.
         *
         * @see Parameters#getFocusAreas()
         * @see Parameters#getMeteringAreas()
         */
        public int weight;
    }

    /**
     * Camera service settings.
     *
     * <p>To make camera parameters take effect, applications have to call
     * {@link Camera#setParameters(Camera.Parameters)}. For example, after
     * {@link Camera.Parameters#setWhiteBalance} is called, white balance is not
     * actually changed until {@link Camera#setParameters(Camera.Parameters)}
     * is called with the changed parameters object.
     *
     * <p>Different devices may have different camera capabilities, such as
     * picture size or flash modes. The application should query the camera
     * capabilities before setting parameters. For example, the application
     * should call {@link Camera.Parameters#getSupportedColorEffects()} before
     * calling {@link Camera.Parameters#setColorEffect(String)}. If the
     * camera does not support color effects,
     * {@link Camera.Parameters#getSupportedColorEffects()} will return null.
     */
    public class Parameters {
        // Parameter keys to communicate with the camera driver.
        private static final String KEY_PREVIEW_SIZE = "preview-size";
        private static final String KEY_PREVIEW_FORMAT = "preview-format";
        private static final String KEY_PREVIEW_FRAME_RATE = "preview-frame-rate";
        private static final String KEY_PREVIEW_FPS_RANGE = "preview-fps-range";
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
        private static final String KEY_ISO_MODE = "iso";
        private static final String KEY_FOCUS_AREAS = "focus-areas";
        private static final String KEY_MAX_NUM_FOCUS_AREAS = "max-num-focus-areas";
        private static final String KEY_FOCAL_LENGTH = "focal-length";
        private static final String KEY_HORIZONTAL_VIEW_ANGLE = "horizontal-view-angle";
        private static final String KEY_VERTICAL_VIEW_ANGLE = "vertical-view-angle";
        private static final String KEY_EXPOSURE_COMPENSATION = "exposure-compensation";
        private static final String KEY_MAX_EXPOSURE_COMPENSATION = "max-exposure-compensation";
        private static final String KEY_MIN_EXPOSURE_COMPENSATION = "min-exposure-compensation";
        private static final String KEY_EXPOSURE_COMPENSATION_STEP = "exposure-compensation-step";
        private static final String KEY_AUTO_EXPOSURE_LOCK = "auto-exposure-lock";
        private static final String KEY_AUTO_EXPOSURE_LOCK_SUPPORTED = "auto-exposure-lock-supported";
        private static final String KEY_AUTO_WHITEBALANCE_LOCK = "auto-whitebalance-lock";
        private static final String KEY_AUTO_WHITEBALANCE_LOCK_SUPPORTED = "auto-whitebalance-lock-supported";
        private static final String KEY_METERING_AREAS = "metering-areas";
        private static final String KEY_MAX_NUM_METERING_AREAS = "max-num-metering-areas";
        private static final String KEY_ZOOM = "zoom";
        private static final String KEY_MAX_ZOOM = "max-zoom";
        private static final String KEY_ZOOM_RATIOS = "zoom-ratios";
        private static final String KEY_ZOOM_SUPPORTED = "zoom-supported";
        private static final String KEY_SMOOTH_ZOOM_SUPPORTED = "smooth-zoom-supported";
        private static final String KEY_FOCUS_DISTANCES = "focus-distances";
        private static final String KEY_VIDEO_SIZE = "video-size";
        private static final String KEY_PREFERRED_PREVIEW_SIZE_FOR_VIDEO =
                                            "preferred-preview-size-for-video";
        private static final String KEY_MAX_NUM_DETECTED_FACES_HW = "max-num-detected-faces-hw";
        private static final String KEY_MAX_NUM_DETECTED_FACES_SW = "max-num-detected-faces-sw";
        private static final String KEY_RECORDING_HINT = "recording-hint";
        private static final String KEY_VIDEO_SNAPSHOT_SUPPORTED = "video-snapshot-supported";
        private static final String KEY_VIDEO_STABILIZATION = "video-stabilization";
        private static final String KEY_VIDEO_STABILIZATION_SUPPORTED = "video-stabilization-supported";
        private static final String KEY_POWER_MODE_SUPPORTED = "power-mode-supported";

        private static final String KEY_POWER_MODE = "power-mode";

        // Parameter key suffix for supported values.
        private static final String SUPPORTED_VALUES_SUFFIX = "-values";

        private static final String TRUE = "true";
        private static final String FALSE = "false";

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

        // Values for POWER MODE
        public static final String LOW_POWER = "Low_Power";
        public static final String NORMAL_POWER = "Normal_Power";

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

        //Values for ISO settings
        /** @hide */
        public static final String ISO_AUTO = "auto";
        /** @hide */
        public static final String ISO_HJR = "ISO_HJR";
        /** @hide */
        public static final String ISO_SPORTS = "ISO_SPORTS";
        /** @hide */
        public static final String ISO_NIGHT = "ISO_NIGHT";
        /** @hide */
        public static final String ISO_MOVIE = "ISO_MOVIE";
        /** @hide */
        public static final String ISO_100 = "ISO100";
        /** @hide */
        public static final String ISO_200 = "ISO200";
        /** @hide */
        public static final String ISO_400 = "ISO400";
        /** @hide */
        public static final String ISO_800 = "ISO800";
        /** @hide */
        public static final String ISO_1600 = "ISO1600";
        /** @hide */
        public static final String ISO_3200 = "ISO3200";
        /** @hide */
        public static final String ISO_6400 = "ISO6400";

        /** @hide
         * Scene mode is off.
         */
        public static final String SCENE_MODE_ASD = "asd";

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
        /** @hide
        * SCENE_MODE_BACKLIGHT
        **/
        public static final String SCENE_MODE_BACKLIGHT = "backlight";
        /** @hide
        * SCENE_MODE_FLOWERS
        **/
        public static final String SCENE_MODE_FLOWERS = "flowers";

        /**
         * Applications are looking for a barcode. Camera driver will be
         * optimized for barcode reading.
         */
        public static final String SCENE_MODE_BARCODE = "barcode";

        /**
         * Capture a scene using high dynamic range imaging techniques. The
         * camera will return an image that has an extended dynamic range
         * compared to a regular capture. Capturing such an image may take
         * longer than a regular capture.
         */
        public static final String SCENE_MODE_HDR = "hdr";

        /**
         * Auto-focus mode. Applications should call {@link
         * #autoFocus(AutoFocusCallback)} to start the focus in this mode.
         */
        public static final String FOCUS_MODE_AUTO = "auto";

        /**
         * Focus is set at infinity. Applications should not call
         * {@link #autoFocus(AutoFocusCallback)} in this mode.
         */
        public static final String FOCUS_MODE_INFINITY = "infinity";

        /**
         * Macro (close-up) focus mode. Applications should call
         * {@link #autoFocus(AutoFocusCallback)} to start the focus in this
         * mode.
         */
        public static final String FOCUS_MODE_MACRO = "macro";

        /**
         * Focus is fixed. The camera is always in this mode if the focus is not
         * adjustable. If the camera has auto-focus, this mode can fix the
         * focus, which is usually at hyperfocal distance. Applications should
         * not call {@link #autoFocus(AutoFocusCallback)} in this mode.
         */
        public static final String FOCUS_MODE_FIXED = "fixed";

        /** @hide
         * Normal focus mode. Applications should call
         * {@link #autoFocus(AutoFocusCallback)} to start the focus in this
         * mode.
         */
        public static final String FOCUS_MODE_NORMAL = "normal";

        /**
         * Extended depth of field (EDOF). Focusing is done digitally and
         * continuously. Applications should not call {@link
         * #autoFocus(AutoFocusCallback)} in this mode.
         */
        public static final String FOCUS_MODE_EDOF = "edof";

        /**
         * Continuous auto focus mode intended for video recording. The camera
         * continuously tries to focus. This is the best choice for video
         * recording because the focus changes smoothly . Applications still can
         * call {@link #takePicture(Camera.ShutterCallback,
         * Camera.PictureCallback, Camera.PictureCallback)} in this mode but the
         * subject may not be in focus. Auto focus starts when the parameter is
         * set.
         *
         * <p>Since API level 14, applications can call {@link
         * #autoFocus(AutoFocusCallback)} in this mode. The focus callback will
         * immediately return with a boolean that indicates whether the focus is
         * sharp or not. The focus position is locked after autoFocus call. If
         * applications want to resume the continuous focus, cancelAutoFocus
         * must be called. Restarting the preview will not resume the continuous
         * autofocus. To stop continuous focus, applications should change the
         * focus mode to other modes.
         *
         * @see #FOCUS_MODE_CONTINUOUS_PICTURE
         */
        public static final String FOCUS_MODE_CONTINUOUS_VIDEO = "continuous-video";

        /**
         * Continuous auto focus mode intended for taking pictures. The camera
         * continuously tries to focus. The speed of focus change is more
         * aggressive than {@link #FOCUS_MODE_CONTINUOUS_VIDEO}. Auto focus
         * starts when the parameter is set.
         *
         * <p>Applications can call {@link #autoFocus(AutoFocusCallback)} in
         * this mode. If the autofocus is in the middle of scanning, the focus
         * callback will return when it completes. If the autofocus is not
         * scanning, the focus callback will immediately return with a boolean
         * that indicates whether the focus is sharp or not. The apps can then
         * decide if they want to take a picture immediately or to change the
         * focus mode to auto, and run a full autofocus cycle. The focus
         * position is locked after autoFocus call. If applications want to
         * resume the continuous focus, cancelAutoFocus must be called.
         * Restarting the preview will not resume the continuous autofocus. To
         * stop continuous focus, applications should change the focus mode to
         * other modes.
         *
         * @see #FOCUS_MODE_CONTINUOUS_VIDEO
         */
        public static final String FOCUS_MODE_CONTINUOUS_PICTURE = "continuous-picture";

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
         * The array index of minimum preview fps for use with {@link
         * #getPreviewFpsRange(int[])} or {@link
         * #getSupportedPreviewFpsRange()}.
         */
        public static final int PREVIEW_FPS_MIN_INDEX = 0;

        /**
         * The array index of maximum preview fps for use with {@link
         * #getPreviewFpsRange(int[])} or {@link
         * #getSupportedPreviewFpsRange()}.
         */
        public static final int PREVIEW_FPS_MAX_INDEX = 1;

        // Formats for setPreviewFormat and setPictureFormat.
        private static final String PIXEL_FORMAT_YUV422SP = "yuv422sp";
        private static final String PIXEL_FORMAT_YUV420SP = "yuv420sp";
        private static final String PIXEL_FORMAT_YUV420SP_ADRENO = "yuv420sp-adreno";
        private static final String PIXEL_FORMAT_YUV422I = "yuv422i-yuyv";
        private static final String PIXEL_FORMAT_YUV420P = "yuv420p";
        private static final String PIXEL_FORMAT_RGB565 = "rgb565";
        private static final String PIXEL_FORMAT_JPEG = "jpeg";
        private static final String PIXEL_FORMAT_BAYER_RGGB = "bayer-rggb";
        private static final String PIXEL_FORMAT_RAW = "raw";
        private static final String PIXEL_FORMAT_YV12 = "yv12";
        private static final String PIXEL_FORMAT_NV12 = "nv12";

        private HashMap<String, String> mMap;

        private Parameters() {
            mMap = new HashMap<String, String>(64);
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
            StringBuilder flattened = new StringBuilder(128);
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

            TextUtils.StringSplitter splitter = new TextUtils.SimpleStringSplitter(';');
            splitter.setString(flattened);
            for (String kv : splitter) {
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
            if (key.indexOf('=') != -1 || key.indexOf(';') != -1 || key.indexOf(0) != -1) {
                Log.e(TAG, "Key \"" + key + "\" contains invalid character (= or ; or \\0)");
                return;
            }
            if (value.indexOf('=') != -1 || value.indexOf(';') != -1 || value.indexOf(0) != -1) {
                Log.e(TAG, "Value \"" + value + "\" contains invalid character (= or ; or \\0)");
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

        private void set(String key, List<Area> areas) {
            if (areas == null) {
                set(key, "(0,0,0,0,0)");
            } else {
                StringBuilder buffer = new StringBuilder();
                for (int i = 0; i < areas.size(); i++) {
                    Area area = areas.get(i);
                    Rect rect = area.rect;
                    buffer.append('(');
                    buffer.append(rect.left);
                    buffer.append(',');
                    buffer.append(rect.top);
                    buffer.append(',');
                    buffer.append(rect.right);
                    buffer.append(',');
                    buffer.append(rect.bottom);
                    buffer.append(',');
                    buffer.append(area.weight);
                    buffer.append(')');
                    if (i != areas.size() - 1) buffer.append(',');
                }
                set(key, buffer.toString());
            }
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
         * Sets the dimensions for preview pictures. If the preview has already
         * started, applications should stop the preview first before changing
         * preview size.
         *
         * The sides of width and height are based on camera orientation. That
         * is, the preview size is the size before it is rotated by display
         * orientation. So applications need to consider the display orientation
         * while setting preview size. For example, suppose the camera supports
         * both 480x320 and 320x480 preview sizes. The application wants a 3:2
         * preview ratio. If the display orientation is set to 0 or 180, preview
         * size should be set to 480x320. If the display orientation is set to
         * 90 or 270, preview size should be set to 320x480. The display
         * orientation should also be considered while setting picture size and
         * thumbnail size.
         *
         * @param width  the width of the pictures, in pixels
         * @param height the height of the pictures, in pixels
         * @see #setDisplayOrientation(int)
         * @see #getCameraInfo(int, CameraInfo)
         * @see #setPictureSize(int, int)
         * @see #setJpegThumbnailSize(int, int)
         */
        public void setPreviewSize(int width, int height) {
            String v = Integer.toString(width) + "x" + Integer.toString(height);
            set(KEY_PREVIEW_SIZE, v);
        }

        /**
         * Returns the dimensions setting for preview pictures.
         *
         * @return a Size object with the width and height setting
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
         * <p>Gets the supported video frame sizes that can be used by
         * MediaRecorder.</p>
         *
         * <p>If the returned list is not null, the returned list will contain at
         * least one Size and one of the sizes in the returned list must be
         * passed to MediaRecorder.setVideoSize() for camcorder application if
         * camera is used as the video source. In this case, the size of the
         * preview can be different from the resolution of the recorded video
         * during video recording.</p>
         *
         * @return a list of Size object if camera has separate preview and
         *         video output; otherwise, null is returned.
         * @see #getPreferredPreviewSizeForVideo()
         */
        public List<Size> getSupportedVideoSizes() {
            String str = get(KEY_VIDEO_SIZE + SUPPORTED_VALUES_SUFFIX);
            return splitSize(str);
        }

        /**
         * Returns the preferred or recommended preview size (width and height)
         * in pixels for video recording. Camcorder applications should
         * set the preview size to a value that is not larger than the
         * preferred preview size. In other words, the product of the width
         * and height of the preview size should not be larger than that of
         * the preferred preview size. In addition, we recommend to choose a
         * preview size that has the same aspect ratio as the resolution of
         * video to be recorded.
         *
         * @return the preferred preview size (width and height) in pixels for
         *         video recording if getSupportedVideoSizes() does not return
         *         null; otherwise, null is returned.
         * @see #getSupportedVideoSizes()
         */
        public Size getPreferredPreviewSizeForVideo() {
            String pair = get(KEY_PREFERRED_PREVIEW_SIZE_FOR_VIDEO);
            return strToSize(pair);
        }

        /**
         * <p>Sets the dimensions for EXIF thumbnail in Jpeg picture. If
         * applications set both width and height to 0, EXIF will not contain
         * thumbnail.</p>
         *
         * <p>Applications need to consider the display orientation. See {@link
         * #setPreviewSize(int,int)} for reference.</p>
         *
         * @param width  the width of the thumbnail, in pixels
         * @param height the height of the thumbnail, in pixels
         * @see #setPreviewSize(int,int)
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
         * @deprecated replaced by {@link #setPreviewFpsRange(int,int)}
         */
        @Deprecated
        public void setPreviewFrameRate(int fps) {
            set(KEY_PREVIEW_FRAME_RATE, fps);
        }

        /**
         * Returns the setting for the rate at which preview frames are
         * received. This is the target frame rate. The actual frame rate
         * depends on the driver.
         *
         * @return the frame rate setting (frames per second)
         * @deprecated replaced by {@link #getPreviewFpsRange(int[])}
         */
        @Deprecated
        public int getPreviewFrameRate() {
            return getInt(KEY_PREVIEW_FRAME_RATE);
        }

        /**
         * Gets the supported preview frame rates.
         *
         * @return a list of supported preview frame rates. null if preview
         *         frame rate setting is not supported.
         * @deprecated replaced by {@link #getSupportedPreviewFpsRange()}
         */
        @Deprecated
        public List<Integer> getSupportedPreviewFrameRates() {
            String str = get(KEY_PREVIEW_FRAME_RATE + SUPPORTED_VALUES_SUFFIX);
            return splitInt(str);
        }

        /**
         * Sets the minimum and maximum preview fps. This controls the rate of
         * preview frames received in {@link PreviewCallback}. The minimum and
         * maximum preview fps must be one of the elements from {@link
         * #getSupportedPreviewFpsRange}.
         *
         * @param min the minimum preview fps (scaled by 1000).
         * @param max the maximum preview fps (scaled by 1000).
         * @throws RuntimeException if fps range is invalid.
         * @see #setPreviewCallbackWithBuffer(Camera.PreviewCallback)
         * @see #getSupportedPreviewFpsRange()
         */
        public void setPreviewFpsRange(int min, int max) {
            set(KEY_PREVIEW_FPS_RANGE, "" + min + "," + max);
        }

        /**
         * Returns the current minimum and maximum preview fps. The values are
         * one of the elements returned by {@link #getSupportedPreviewFpsRange}.
         *
         * @return range the minimum and maximum preview fps (scaled by 1000).
         * @see #PREVIEW_FPS_MIN_INDEX
         * @see #PREVIEW_FPS_MAX_INDEX
         * @see #getSupportedPreviewFpsRange()
         */
        public void getPreviewFpsRange(int[] range) {
            if (range == null || range.length != 2) {
                throw new IllegalArgumentException(
                        "range must be an array with two elements.");
            }
            splitInt(get(KEY_PREVIEW_FPS_RANGE), range);
        }

        /**
         * Gets the supported preview fps (frame-per-second) ranges. Each range
         * contains a minimum fps and maximum fps. If minimum fps equals to
         * maximum fps, the camera outputs frames in fixed frame rate. If not,
         * the camera outputs frames in auto frame rate. The actual frame rate
         * fluctuates between the minimum and the maximum. The values are
         * multiplied by 1000 and represented in integers. For example, if frame
         * rate is 26.623 frames per second, the value is 26623.
         *
         * @return a list of supported preview fps ranges. This method returns a
         *         list with at least one element. Every element is an int array
         *         of two values - minimum fps and maximum fps. The list is
         *         sorted from small to large (first by maximum fps and then
         *         minimum fps).
         * @see #PREVIEW_FPS_MIN_INDEX
         * @see #PREVIEW_FPS_MAX_INDEX
         */
        public List<int[]> getSupportedPreviewFpsRange() {
            String str = get(KEY_PREVIEW_FPS_RANGE + SUPPORTED_VALUES_SUFFIX);
            return splitRange(str);
        }

        /**
         * Sets the image format for preview pictures.
         * <p>If this is never called, the default format will be
         * {@link android.graphics.ImageFormat#NV21}, which
         * uses the NV21 encoding format.</p>
         *
         * <p>Use {@link Parameters#getSupportedPreviewFormats} to get a list of
         * the available preview formats.
         *
         * <p>It is strongly recommended that either
         * {@link android.graphics.ImageFormat#NV21} or
         * {@link android.graphics.ImageFormat#YV12} is used, since
         * they are supported by all camera devices.</p>
         *
         * <p>For YV12, the image buffer that is received is not necessarily
         * tightly packed, as there may be padding at the end of each row of
         * pixel data, as described in
         * {@link android.graphics.ImageFormat#YV12}. For camera callback data,
         * it can be assumed that the stride of the Y and UV data is the
         * smallest possible that meets the alignment requirements. That is, if
         * the preview size is <var>width x height</var>, then the following
         * equations describe the buffer index for the beginning of row
         * <var>y</var> for the Y plane and row <var>c</var> for the U and V
         * planes:
         *
         * {@code
         * <pre>
         * yStride   = (int) ceil(width / 16.0) * 16;
         * uvStride  = (int) ceil( (yStride / 2) / 16.0) * 16;
         * ySize     = yStride * height;
         * uvSize    = uvStride * height / 2;
         * yRowIndex = yStride * y;
         * uRowIndex = ySize + uvSize + uvStride * c;
         * vRowIndex = ySize + uvStride * c;
         * size      = ySize + uvSize * 2;</pre>
         * }
         *
         * @param pixel_format the desired preview picture format, defined by
         *   one of the {@link android.graphics.ImageFormat} constants.  (E.g.,
         *   <var>ImageFormat.NV21</var> (default), or
         *   <var>ImageFormat.YV12</var>)
         *
         * @see android.graphics.ImageFormat
         * @see android.hardware.Camera.Parameters#getSupportedPreviewFormats
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
         * @see #setPreviewFormat
         */
        public int getPreviewFormat() {
            return pixelFormatForCameraFormat(get(KEY_PREVIEW_FORMAT));
        }

        /**
         * Gets the supported preview formats. {@link android.graphics.ImageFormat#NV21}
         * is always supported. {@link android.graphics.ImageFormat#YV12}
         * is always supported since API level 12.
         *
         * @return a list of supported preview formats. This method will always
         *         return a list with at least one element.
         * @see android.graphics.ImageFormat
         * @see #setPreviewFormat
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
         * <p>Sets the dimensions for pictures.</p>
         *
         * <p>Applications need to consider the display orientation. See {@link
         * #setPreviewSize(int,int)} for reference.</p>
         *
         * @param width  the width for pictures, in pixels
         * @param height the height for pictures, in pixels
         * @see #setPreviewSize(int,int)
         *
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
            case ImageFormat.YV12:      return PIXEL_FORMAT_YUV420P;
            case ImageFormat.RGB_565:   return PIXEL_FORMAT_RGB565;
            case ImageFormat.JPEG:      return PIXEL_FORMAT_JPEG;
            case ImageFormat.BAYER_RGGB: return PIXEL_FORMAT_BAYER_RGGB;
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

            if (format.equals(PIXEL_FORMAT_YUV420P))
                return ImageFormat.YV12;

            if (format.equals(PIXEL_FORMAT_RGB565))
                return ImageFormat.RGB_565;

            if (format.equals(PIXEL_FORMAT_JPEG))
                return ImageFormat.JPEG;

            return ImageFormat.UNKNOWN;
        }

        /**
         * Sets the clockwise rotation angle in degrees relative to the
         * orientation of the camera. This affects the pictures returned from
         * JPEG {@link PictureCallback}. The camera driver may set orientation
         * in the EXIF header without rotating the picture. Or the driver may
         * rotate the picture and the EXIF thumbnail. If the Jpeg picture is
         * rotated, the orientation in the EXIF header will be missing or 1 (row
         * #0 is top and column #0 is left side).
         *
         * <p>
         * If applications want to rotate the picture to match the orientation
         * of what users see, apps should use
         * {@link android.view.OrientationEventListener} and
         * {@link android.hardware.Camera.CameraInfo}. The value from
         * OrientationEventListener is relative to the natural orientation of
         * the device. CameraInfo.orientation is the angle between camera
         * orientation and natural device orientation. The sum of the two is the
         * rotation angle for back-facing camera. The difference of the two is
         * the rotation angle for front-facing camera. Note that the JPEG
         * pictures of front-facing cameras are not mirrored as in preview
         * display.
         *
         * <p>
         * For example, suppose the natural orientation of the device is
         * portrait. The device is rotated 270 degrees clockwise, so the device
         * orientation is 270. Suppose a back-facing camera sensor is mounted in
         * landscape and the top side of the camera sensor is aligned with the
         * right edge of the display in natural orientation. So the camera
         * orientation is 90. The rotation should be set to 0 (270 + 90).
         *
         * <p>The reference code is as follows.
         *
         * <pre>
         * public void onOrientationChanged(int orientation) {
         *     if (orientation == ORIENTATION_UNKNOWN) return;
         *     android.hardware.Camera.CameraInfo info =
         *            new android.hardware.Camera.CameraInfo();
         *     android.hardware.Camera.getCameraInfo(cameraId, info);
         *     orientation = (orientation + 45) / 90 * 90;
         *     int rotation = 0;
         *     if (info.facing == CameraInfo.CAMERA_FACING_FRONT) {
         *         rotation = (info.orientation - orientation + 360) % 360;
         *     } else {  // back-facing camera
         *         rotation = (info.orientation + orientation) % 360;
         *     }
         *     mParameters.setRotation(rotation);
         * }
         * </pre>
         *
         * @param rotation The rotation angle in degrees relative to the
         *                 orientation of the camera. Rotation can only be 0,
         *                 90, 180 or 270.
         * @throws IllegalArgumentException if rotation value is invalid.
         * @see android.view.OrientationEventListener
         * @see #getCameraInfo(int, CameraInfo)
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
            remove(KEY_QC_GPS_LATITUDE_REF);
            remove(KEY_GPS_LATITUDE);
            remove(KEY_QC_GPS_LONGITUDE_REF);
            remove(KEY_GPS_LONGITUDE);
            remove(KEY_QC_GPS_ALTITUDE_REF);
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
         * Sets the white balance. Changing the setting will release the
         * auto-white balance lock. It is recommended not to change white
         * balance and AWB lock at the same time.
         *
         * @param value new white balance.
         * @see #getWhiteBalance()
         * @see #setAutoWhiteBalanceLock(boolean)
         */
        public void setWhiteBalance(String value) {
            String oldValue = get(KEY_WHITE_BALANCE);
            if (same(value, oldValue)) return;
            set(KEY_WHITE_BALANCE, value);
            set(KEY_AUTO_WHITEBALANCE_LOCK, FALSE);
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
         * @see #SCENE_MODE_BARCODE
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
            if(getSupportedSceneModes() == null) return;
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
	    if(getSupportedFlashModes() == null) return;
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
         * Sets the Power mode.
         *
         * @param value Power mode.
         * @see #getPowerMode()
         */
        public void setPowerMode(String value) {
            set(KEY_POWER_MODE, value);
        }

        /**
         * Gets the current power mode setting.
         *
         * @return current power mode. null if power mode setting is not
         *         supported.
         * @see #POWER_MODE_LOW
         * @see #POWER_MODE_NORMAL
         */
        public String getPowerMode() {
            return get(KEY_POWER_MODE);
        }

        /**
         * Gets the current focus mode setting.
         *
         * @return current focus mode. This method will always return a non-null
         *         value. Applications should call {@link
         *         #autoFocus(AutoFocusCallback)} to start the focus if focus
         *         mode is FOCUS_MODE_AUTO or FOCUS_MODE_MACRO.
         * @see #FOCUS_MODE_AUTO
         * @see #FOCUS_MODE_INFINITY
         * @see #FOCUS_MODE_MACRO
         * @see #FOCUS_MODE_FIXED
         * @see #FOCUS_MODE_EDOF
         * @see #FOCUS_MODE_CONTINUOUS_VIDEO
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
         * <p>Sets the auto-exposure lock state. Applications should check
         * {@link #isAutoExposureLockSupported} before using this method.</p>
         *
         * <p>If set to true, the camera auto-exposure routine will immediately
         * pause until the lock is set to false. Exposure compensation settings
         * changes will still take effect while auto-exposure is locked.</p>
         *
         * <p>If auto-exposure is already locked, setting this to true again has
         * no effect (the driver will not recalculate exposure values).</p>
         *
         * <p>Stopping preview with {@link #stopPreview()}, or triggering still
         * image capture with {@link #takePicture(Camera.ShutterCallback,
         * Camera.PictureCallback, Camera.PictureCallback)}, will not change the
         * lock.</p>
         *
         * <p>Exposure compensation, auto-exposure lock, and auto-white balance
         * lock can be used to capture an exposure-bracketed burst of images,
         * for example.</p>
         *
         * <p>Auto-exposure state, including the lock state, will not be
         * maintained after camera {@link #release()} is called.  Locking
         * auto-exposure after {@link #open()} but before the first call to
         * {@link #startPreview()} will not allow the auto-exposure routine to
         * run at all, and may result in severely over- or under-exposed
         * images.</p>
         *
         * @param toggle new state of the auto-exposure lock. True means that
         *        auto-exposure is locked, false means that the auto-exposure
         *        routine is free to run normally.
         *
         * @see #getAutoExposureLock()
         */
        public void setAutoExposureLock(boolean toggle) {
            set(KEY_AUTO_EXPOSURE_LOCK, toggle ? TRUE : FALSE);
        }

        /**
         * Gets the state of the auto-exposure lock. Applications should check
         * {@link #isAutoExposureLockSupported} before using this method. See
         * {@link #setAutoExposureLock} for details about the lock.
         *
         * @return State of the auto-exposure lock. Returns true if
         *         auto-exposure is currently locked, and false otherwise.
         *
         * @see #setAutoExposureLock(boolean)
         *
         */
        public boolean getAutoExposureLock() {
            String str = get(KEY_AUTO_EXPOSURE_LOCK);
            return TRUE.equals(str);
        }

        /**
         * Returns true if auto-exposure locking is supported. Applications
         * should call this before trying to lock auto-exposure. See
         * {@link #setAutoExposureLock} for details about the lock.
         *
         * @return true if auto-exposure lock is supported.
         * @see #setAutoExposureLock(boolean)
         *
         */
        public boolean isAutoExposureLockSupported() {
            String str = get(KEY_AUTO_EXPOSURE_LOCK_SUPPORTED);
            return TRUE.equals(str);
        }

        /**
         * <p>Sets the auto-white balance lock state. Applications should check
         * {@link #isAutoWhiteBalanceLockSupported} before using this
         * method.</p>
         *
         * <p>If set to true, the camera auto-white balance routine will
         * immediately pause until the lock is set to false.</p>
         *
         * <p>If auto-white balance is already locked, setting this to true
         * again has no effect (the driver will not recalculate white balance
         * values).</p>
         *
         * <p>Stopping preview with {@link #stopPreview()}, or triggering still
         * image capture with {@link #takePicture(Camera.ShutterCallback,
         * Camera.PictureCallback, Camera.PictureCallback)}, will not change the
         * the lock.</p>
         *
         * <p> Changing the white balance mode with {@link #setWhiteBalance}
         * will release the auto-white balance lock if it is set.</p>
         *
         * <p>Exposure compensation, AE lock, and AWB lock can be used to
         * capture an exposure-bracketed burst of images, for example.
         * Auto-white balance state, including the lock state, will not be
         * maintained after camera {@link #release()} is called.  Locking
         * auto-white balance after {@link #open()} but before the first call to
         * {@link #startPreview()} will not allow the auto-white balance routine
         * to run at all, and may result in severely incorrect color in captured
         * images.</p>
         *
         * @param toggle new state of the auto-white balance lock. True means
         *        that auto-white balance is locked, false means that the
         *        auto-white balance routine is free to run normally.
         *
         * @see #getAutoWhiteBalanceLock()
         * @see #setWhiteBalance(String)
         */
        public void setAutoWhiteBalanceLock(boolean toggle) {
            set(KEY_AUTO_WHITEBALANCE_LOCK, toggle ? TRUE : FALSE);
        }

        /**
         * Gets the state of the auto-white balance lock. Applications should
         * check {@link #isAutoWhiteBalanceLockSupported} before using this
         * method. See {@link #setAutoWhiteBalanceLock} for details about the
         * lock.
         *
         * @return State of the auto-white balance lock. Returns true if
         *         auto-white balance is currently locked, and false
         *         otherwise.
         *
         * @see #setAutoWhiteBalanceLock(boolean)
         *
         */
        public boolean getAutoWhiteBalanceLock() {
            String str = get(KEY_AUTO_WHITEBALANCE_LOCK);
            return TRUE.equals(str);
        }

        /**
         * Returns true if auto-white balance locking is supported. Applications
         * should call this before trying to lock auto-white balance. See
         * {@link #setAutoWhiteBalanceLock} for details about the lock.
         *
         * @return true if auto-white balance lock is supported.
         * @see #setAutoWhiteBalanceLock(boolean)
         *
         */
        public boolean isAutoWhiteBalanceLockSupported() {
            String str = get(KEY_AUTO_WHITEBALANCE_LOCK_SUPPORTED);
            return TRUE.equals(str);
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
         * Gets the current ISO setting.
         *
         * @return one of ISO_XXX string constant. null if ISO
         *         setting is not supported.
         * @hide
         */
        public String getISOValue() {
            return get(KEY_ISO_MODE);
        }

        /**
         * Sets the ISO.
         *
         * @param iso ISO_XXX string constant.
         * @hide
         */
        public void setISOValue(String iso) {
            set(KEY_ISO_MODE, iso);
        }

         /**
         * Gets the supported ISO values.
         *
         * @return a List of ISO_MODE_XXX string constants. null if iso mode
         *         setting is not supported.
         * @hide
         */
        public List<String> getSupportedIsoValues() {
            String str = get(KEY_ISO_MODE + SUPPORTED_VALUES_SUFFIX);
            return split(str);
        }

        /**
         * <p>Gets the distances from the camera to where an object appears to be
         * in focus. The object is sharpest at the optimal focus distance. The
         * depth of field is the far focus distance minus near focus distance.</p>
         *
         * <p>Focus distances may change after calling {@link
         * #autoFocus(AutoFocusCallback)}, {@link #cancelAutoFocus}, or {@link
         * #startPreview()}. Applications can call {@link #getParameters()}
         * and this method anytime to get the latest focus distances. If the
         * focus mode is FOCUS_MODE_CONTINUOUS_VIDEO, focus distances may change
         * from time to time.</p>
         *
         * <p>This method is intended to estimate the distance between the camera
         * and the subject. After autofocus, the subject distance may be within
         * near and far focus distance. However, the precision depends on the
         * camera hardware, autofocus algorithm, the focus area, and the scene.
         * The error can be large and it should be only used as a reference.</p>
         *
         * <p>Far focus distance >= optimal focus distance >= near focus distance.
         * If the focus distance is infinity, the value will be
         * {@code Float.POSITIVE_INFINITY}.</p>
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
                        "output must be a float array with three elements.");
            }
            splitFloat(get(KEY_FOCUS_DISTANCES), output);
        }

        /**
         * Gets the maximum number of focus areas supported. This is the maximum
         * length of the list in {@link #setFocusAreas(List)} and
         * {@link #getFocusAreas()}.
         *
         * @return the maximum number of focus areas supported by the camera.
         * @see #getFocusAreas()
         */
        public int getMaxNumFocusAreas() {
            return getInt(KEY_MAX_NUM_FOCUS_AREAS, 0);
        }

        /**
         * <p>Gets the current focus areas. Camera driver uses the areas to decide
         * focus.</p>
         *
         * <p>Before using this API or {@link #setFocusAreas(List)}, apps should
         * call {@link #getMaxNumFocusAreas()} to know the maximum number of
         * focus areas first. If the value is 0, focus area is not supported.</p>
         *
         * <p>Each focus area is a rectangle with specified weight. The direction
         * is relative to the sensor orientation, that is, what the sensor sees.
         * The direction is not affected by the rotation or mirroring of
         * {@link #setDisplayOrientation(int)}. Coordinates of the rectangle
         * range from -1000 to 1000. (-1000, -1000) is the upper left point.
         * (1000, 1000) is the lower right point. The width and height of focus
         * areas cannot be 0 or negative.</p>
         *
         * <p>The weight must range from 1 to 1000. The weight should be
         * interpreted as a per-pixel weight - all pixels in the area have the
         * specified weight. This means a small area with the same weight as a
         * larger area will have less influence on the focusing than the larger
         * area. Focus areas can partially overlap and the driver will add the
         * weights in the overlap region.</p>
         *
         * <p>A special case of a {@code null} focus area list means the driver is
         * free to select focus targets as it wants. For example, the driver may
         * use more signals to select focus areas and change them
         * dynamically. Apps can set the focus area list to {@code null} if they
         * want the driver to completely control focusing.</p>
         *
         * <p>Focus areas are relative to the current field of view
         * ({@link #getZoom()}). No matter what the zoom level is, (-1000,-1000)
         * represents the top of the currently visible camera frame. The focus
         * area cannot be set to be outside the current field of view, even
         * when using zoom.</p>
         *
         * <p>Focus area only has effect if the current focus mode is
         * {@link #FOCUS_MODE_AUTO}, {@link #FOCUS_MODE_MACRO},
         * {@link #FOCUS_MODE_CONTINUOUS_VIDEO}, or
         * {@link #FOCUS_MODE_CONTINUOUS_PICTURE}.</p>
         *
         * @return a list of current focus areas
         */
        public List<Area> getFocusAreas() {
            return splitArea(get(KEY_FOCUS_AREAS));
        }

        /**
         * Sets focus areas. See {@link #getFocusAreas()} for documentation.
         *
         * @param focusAreas the focus areas
         * @see #getFocusAreas()
         */
        public void setFocusAreas(List<Area> focusAreas) {
            set(KEY_FOCUS_AREAS, focusAreas);
        }

        /**
         * Gets the maximum number of metering areas supported. This is the
         * maximum length of the list in {@link #setMeteringAreas(List)} and
         * {@link #getMeteringAreas()}.
         *
         * @return the maximum number of metering areas supported by the camera.
         * @see #getMeteringAreas()
         */
        public int getMaxNumMeteringAreas() {
            return getInt(KEY_MAX_NUM_METERING_AREAS, 0);
        }

        /**
         * <p>Gets the current metering areas. Camera driver uses these areas to
         * decide exposure.</p>
         *
         * <p>Before using this API or {@link #setMeteringAreas(List)}, apps should
         * call {@link #getMaxNumMeteringAreas()} to know the maximum number of
         * metering areas first. If the value is 0, metering area is not
         * supported.</p>
         *
         * <p>Each metering area is a rectangle with specified weight. The
         * direction is relative to the sensor orientation, that is, what the
         * sensor sees. The direction is not affected by the rotation or
         * mirroring of {@link #setDisplayOrientation(int)}. Coordinates of the
         * rectangle range from -1000 to 1000. (-1000, -1000) is the upper left
         * point. (1000, 1000) is the lower right point. The width and height of
         * metering areas cannot be 0 or negative.</p>
         *
         * <p>The weight must range from 1 to 1000, and represents a weight for
         * every pixel in the area. This means that a large metering area with
         * the same weight as a smaller area will have more effect in the
         * metering result.  Metering areas can partially overlap and the driver
         * will add the weights in the overlap region.</p>
         *
         * <p>A special case of a {@code null} metering area list means the driver
         * is free to meter as it chooses. For example, the driver may use more
         * signals to select metering areas and change them dynamically. Apps
         * can set the metering area list to {@code null} if they want the
         * driver to completely control metering.</p>
         *
         * <p>Metering areas are relative to the current field of view
         * ({@link #getZoom()}). No matter what the zoom level is, (-1000,-1000)
         * represents the top of the currently visible camera frame. The
         * metering area cannot be set to be outside the current field of view,
         * even when using zoom.</p>
         *
         * <p>No matter what metering areas are, the final exposure are compensated
         * by {@link #setExposureCompensation(int)}.</p>
         *
         * @return a list of current metering areas
         */
        public List<Area> getMeteringAreas() {
            return splitArea(get(KEY_METERING_AREAS));
        }

        /**
         * Sets metering areas. See {@link #getMeteringAreas()} for
         * documentation.
         *
         * @param meteringAreas the metering areas
         * @see #getMeteringAreas()
         */
        public void setMeteringAreas(List<Area> meteringAreas) {
            set(KEY_METERING_AREAS, meteringAreas);
        }

        /**
         * Gets the maximum number of detected faces supported. This is the
         * maximum length of the list returned from {@link FaceDetectionListener}.
         * If the return value is 0, face detection of the specified type is not
         * supported.
         *
         * @return the maximum number of detected face supported by the camera.
         * @see #startFaceDetection()
         */
        public int getMaxNumDetectedFaces() {
            return getInt(KEY_MAX_NUM_DETECTED_FACES_HW, 0);
        }

        /**
         * Sets recording mode hint. This tells the camera that the intent of
         * the application is to record videos {@link
         * android.media.MediaRecorder#start()}, not to take still pictures
         * {@link #takePicture(Camera.ShutterCallback, Camera.PictureCallback,
         * Camera.PictureCallback, Camera.PictureCallback)}. Using this hint can
         * allow MediaRecorder.start() to start faster or with fewer glitches on
         * output. This should be called before starting preview for the best
         * result, but can be changed while the preview is active. The default
         * value is false.
         *
         * The app can still call takePicture() when the hint is true or call
         * MediaRecorder.start() when the hint is false. But the performance may
         * be worse.
         *
         * @param hint true if the apps intend to record videos using
         *             {@link android.media.MediaRecorder}.
         */
        public void setRecordingHint(boolean hint) {
            set(KEY_RECORDING_HINT, hint ? TRUE : FALSE);
        }

        /**
         * <p>Returns true if video snapshot is supported. That is, applications
         * can call {@link #takePicture(Camera.ShutterCallback,
         * Camera.PictureCallback, Camera.PictureCallback,
         * Camera.PictureCallback)} during recording. Applications do not need
         * to call {@link #startPreview()} after taking a picture. The preview
         * will be still active. Other than that, taking a picture during
         * recording is identical to taking a picture normally. All settings and
         * methods related to takePicture work identically. Ex:
         * {@link #getPictureSize()}, {@link #getSupportedPictureSizes()},
         * {@link #setJpegQuality(int)}, {@link #setRotation(int)}, and etc. The
         * picture will have an EXIF header. {@link #FLASH_MODE_AUTO} and
         * {@link #FLASH_MODE_ON} also still work, but the video will record the
         * flash.</p>
         *
         * <p>Applications can set shutter callback as null to avoid the shutter
         * sound. It is also recommended to set raw picture and post view
         * callbacks to null to avoid the interrupt of preview display.</p>
         *
         * <p>Field-of-view of the recorded video may be different from that of the
         * captured pictures. The maximum size of a video snapshot may be
         * smaller than that for regular still captures. If the current picture
         * size is set higher than can be supported by video snapshot, the
         * picture will be captured at the maximum supported size instead.</p>
         *
         * @return true if video snapshot is supported.
         */
        public boolean isVideoSnapshotSupported() {
            String str = get(KEY_VIDEO_SNAPSHOT_SUPPORTED);
            return TRUE.equals(str);
        }

        /**
         * @return true if full size video snapshot is supported.
         */
        public boolean isPowerModeSupported() {
            String str = get(KEY_POWER_MODE_SUPPORTED);
            return TRUE.equals(str);
        }

        /**
         * <p>Enables and disables video stabilization. Use
         * {@link #isVideoStabilizationSupported} to determine if calling this
         * method is valid.</p>
         *
         * <p>Video stabilization reduces the shaking due to the motion of the
         * camera in both the preview stream and in recorded videos, including
         * data received from the preview callback. It does not reduce motion
         * blur in images captured with
         * {@link Camera#takePicture takePicture}.</p>
         *
         * <p>Video stabilization can be enabled and disabled while preview or
         * recording is active, but toggling it may cause a jump in the video
         * stream that may be undesirable in a recorded video.</p>
         *
         * @param toggle Set to true to enable video stabilization, and false to
         * disable video stabilization.
         * @see #isVideoStabilizationSupported()
         * @see #getVideoStabilization()
         */
        public void setVideoStabilization(boolean toggle) {
            set(KEY_VIDEO_STABILIZATION, toggle ? TRUE : FALSE);
        }

        /**
         * Get the current state of video stabilization. See
         * {@link #setVideoStabilization} for details of video stabilization.
         *
         * @return true if video stabilization is enabled
         * @see #isVideoStabilizationSupported()
         * @see #setVideoStabilization(boolean)
         */
        public boolean getVideoStabilization() {
            String str = get(KEY_VIDEO_STABILIZATION);
            return TRUE.equals(str);
        }

        /**
         * Returns true if video stabilization is supported. See
         * {@link #setVideoStabilization} for details of video stabilization.
         *
         * @return true if video stabilization is supported
         * @see #setVideoStabilization(boolean)
         * @see #getVideoStabilization()
         */
        public boolean isVideoStabilizationSupported() {
            String str = get(KEY_VIDEO_STABILIZATION_SUPPORTED);
            return TRUE.equals(str);
        }

        // Splits a comma delimited string to an ArrayList of String.
        // Return null if the passing string is null or the size is 0.
        private ArrayList<String> split(String str) {
            if (str == null) return null;

            TextUtils.StringSplitter splitter = new TextUtils.SimpleStringSplitter(',');
            splitter.setString(str);
            ArrayList<String> substrings = new ArrayList<String>();
            for (String s : splitter) {
                substrings.add(s);
            }
            return substrings;
        }

        // Splits a comma delimited string to an ArrayList of Integer.
        // Return null if the passing string is null or the size is 0.
        private ArrayList<Integer> splitInt(String str) {
            if (str == null) return null;

            TextUtils.StringSplitter splitter = new TextUtils.SimpleStringSplitter(',');
            splitter.setString(str);
            ArrayList<Integer> substrings = new ArrayList<Integer>();
            for (String s : splitter) {
                substrings.add(Integer.parseInt(s));
            }
            if (substrings.size() == 0) return null;
            return substrings;
        }

        private void splitInt(String str, int[] output) {
            if (str == null) return;

            TextUtils.StringSplitter splitter = new TextUtils.SimpleStringSplitter(',');
            splitter.setString(str);
            int index = 0;
            for (String s : splitter) {
                output[index++] = Integer.parseInt(s);
            }
        }

        // Splits a comma delimited string to an ArrayList of Float.
        private void splitFloat(String str, float[] output) {
            if (str == null) return;

            TextUtils.StringSplitter splitter = new TextUtils.SimpleStringSplitter(',');
            splitter.setString(str);
            int index = 0;
            for (String s : splitter) {
                output[index++] = Float.parseFloat(s);
            }
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

            TextUtils.StringSplitter splitter = new TextUtils.SimpleStringSplitter(',');
            splitter.setString(str);
            ArrayList<Size> sizeList = new ArrayList<Size>();
            for (String s : splitter) {
                Size size = strToSize(s);
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

        // Splits a comma delimited string to an ArrayList of int array.
        // Example string: "(10000,26623),(10000,30000)". Return null if the
        // passing string is null or the size is 0.
        private ArrayList<int[]> splitRange(String str) {
            if (str == null || str.charAt(0) != '('
                    || str.charAt(str.length() - 1) != ')') {
                Log.e(TAG, "Invalid range list string=" + str);
                return null;
            }

            ArrayList<int[]> rangeList = new ArrayList<int[]>();
            int endIndex, fromIndex = 1;
            do {
                int[] range = new int[2];
                endIndex = str.indexOf("),(", fromIndex);
                if (endIndex == -1) endIndex = str.length() - 1;
                splitInt(str.substring(fromIndex, endIndex), range);
                rangeList.add(range);
                fromIndex = endIndex + 3;
            } while (endIndex != str.length() - 1);

            if (rangeList.size() == 0) return null;
            return rangeList;
        }

        // Splits a comma delimited string to an ArrayList of Area objects.
        // Example string: "(-10,-10,0,0,300),(0,0,10,10,700)". Return null if
        // the passing string is null or the size is 0 or (0,0,0,0,0).
        private ArrayList<Area> splitArea(String str) {
            if (str == null || str.charAt(0) != '('
                    || str.charAt(str.length() - 1) != ')') {
                Log.e(TAG, "Invalid area string=" + str);
                return null;
            }

            ArrayList<Area> result = new ArrayList<Area>();
            int endIndex, fromIndex = 1;
            int[] array = new int[5];
            do {
                endIndex = str.indexOf("),(", fromIndex);
                if (endIndex == -1) endIndex = str.length() - 1;
                splitInt(str.substring(fromIndex, endIndex), array);
                Rect rect = new Rect(array[0], array[1], array[2], array[3]);
                result.add(new Area(rect, array[4]));
                fromIndex = endIndex + 3;
            } while (endIndex != str.length() - 1);

            if (result.size() == 0) return null;

            if (result.size() == 1) {
                Area area = result.get(0);
                Rect rect = area.rect;
                if (rect.left == 0 && rect.top == 0 && rect.right == 0
                        && rect.bottom == 0 && area.weight == 0) {
                    return null;
                }
            }

            return result;
        }

        private boolean same(String s1, String s2) {
            if (s1 == null && s2 == null) return true;
            if (s1 != null && s1.equals(s2)) return true;
            return false;
        }
        /* ### QC ADD-ONS: START */

        /* ### QC ADDED PARAMETER KEYS*/
        private static final String KEY_QC_HFR_SIZE = "hfr-size";
        private static final String KEY_QC_PREVIEW_FRAME_RATE_MODE = "preview-frame-rate-mode";
        private static final String KEY_QC_PREVIEW_FRAME_RATE_AUTO_MODE = "frame-rate-auto";
        private static final String KEY_QC_PREVIEW_FRAME_RATE_FIXED_MODE = "frame-rate-fixed";
        private static final String KEY_QC_GPS_LATITUDE_REF = "gps-latitude-ref";
        private static final String KEY_QC_GPS_LONGITUDE_REF = "gps-longitude-ref";
        private static final String KEY_QC_GPS_ALTITUDE_REF = "gps-altitude-ref";
        private static final String KEY_QC_GPS_STATUS = "gps-status";
        private static final String KEY_QC_EXIF_DATETIME = "exif-datetime";
        private static final String KEY_QC_TOUCH_AF_AEC = "touch-af-aec";
        private static final String KEY_QC_TOUCH_INDEX_AEC = "touch-index-aec";
        private static final String KEY_QC_TOUCH_INDEX_AF = "touch-index-af";
        private static final String KEY_QC_SCENE_DETECT = "scene-detect";
        private static final String KEY_QC_LENSSHADE = "lensshade";
        private static final String KEY_QC_HISTOGRAM = "histogram";
        private static final String KEY_QC_SKIN_TONE_ENHANCEMENT = "skinToneEnhancement";
        private static final String KEY_QC_AUTO_EXPOSURE = "auto-exposure";
        private static final String KEY_QC_SHARPNESS = "sharpness";
        private static final String KEY_QC_MAX_SHARPNESS = "max-sharpness";
        private static final String KEY_QC_CONTRAST = "contrast";
        private static final String KEY_QC_MAX_CONTRAST = "max-contrast";
        private static final String KEY_QC_SATURATION = "saturation";
        private static final String KEY_QC_MAX_SATURATION = "max-saturation";
        private static final String KEY_QC_DENOISE = "denoise";
        private static final String KEY_QC_CONTINUOUS_AF = "continuous-af";
        private static final String KEY_QC_SELECTABLE_ZONE_AF = "selectable-zone-af";
        private static final String KEY_QC_FACE_DETECTION = "face-detection";
        private static final String KEY_QC_MEMORY_COLOR_ENHANCEMENT = "mce";
        private static final String KEY_QC_REDEYE_REDUCTION = "redeye-reduction";
        private static final String KEY_QC_ZSL = "zsl";
        private static final String KEY_QC_CAMERA_MODE = "camera-mode";
        private static final String KEY_QC_VIDEO_HIGH_FRAME_RATE = "video-hfr";
        private static final String KEY_QC_VIDEO_HDR = "video-hdr";
        /** @hide
        * KEY_QC_AE_BRACKET_HDR
        **/
        public static final String KEY_QC_AE_BRACKET_HDR = "ae-bracket-hdr";

        /* ### QC ADDED PARAMETER VALUES*/

        // Values for touch af/aec settings.
        /** @hide
        * TOUCH_AF_AEC_OFF
        **/
        public static final String TOUCH_AF_AEC_OFF = "touch-off";
        /** @hide
        * TOUCH_AF_AEC_ON
        **/
        public static final String TOUCH_AF_AEC_ON = "touch-on";

        // Values for auto exposure settings.
        /** @hide
        * Auto exposure frame-avg
        **/
        public static final String AUTO_EXPOSURE_FRAME_AVG = "frame-average";
        /** @hide
        * Auto exposure center weighted
        **/
        public static final String AUTO_EXPOSURE_CENTER_WEIGHTED = "center-weighted";
        /** @hide
        * Auto exposure spot metering
        **/
        public static final String AUTO_EXPOSURE_SPOT_METERING = "spot-metering";

        //Values for Lens Shading
        /** @hide
        * LENSSHADE_ENABLE
        **/
        public static final String LENSSHADE_ENABLE = "enable";
        /** @hide
        * LENSSHADE_DISABLE
        **/
        public static final String LENSSHADE_DISABLE= "disable";

        //Values for Histogram
        /** @hide
        * Histogram enable
        **/
        public static final String HISTOGRAM_ENABLE = "enable";
        /** @hide
        * Histogram disable
        **/
        public static final String HISTOGRAM_DISABLE= "disable";

        //Values for Skin Tone Enhancement
        /** @hide
        * SKIN_TONE_ENHANCEMENT_ENABLE
        **/
        public static final String SKIN_TONE_ENHANCEMENT_ENABLE = "enable";
        /** @hide
        * SKIN_TONE_ENHANCEMENT_DISABLE
        **/
        public static final String SKIN_TONE_ENHANCEMENT_DISABLE= "disable";

        // Values for MCE settings.
        /** @hide
        * MCE_ENaBLE
        **/
        public static final String MCE_ENABLE = "enable";
        /** @hide
        * MCE_DISABLE
        **/
        public static final String MCE_DISABLE = "disable";

        // Values for ZSL settings.
        /** @hide
        * ZSL_ON
        **/
        public static final String ZSL_ON = "on";
        /** @hide
        * ZSL_OFF
        **/
        public static final String ZSL_OFF = "off";

        // Values for HDR Bracketing settings.

        /** @hide
        * AEC bracketing off
        **/
        public static final String AE_BRACKET_HDR_OFF = "Off";
        /** @hide
        * AEC bracketing hdr
        **/
        public static final String AE_BRACKET_HDR = "HDR";
        /** @hide
        * AEC bracketing aec-bracket
        **/
        public static final String AE_BRACKET = "AE-Bracket";

        // Values for HFR settings.
        /** @hide
        * VIDEO_HFR_OFF
        **/
        public static final String VIDEO_HFR_OFF = "off";
        /** @hide
        * VIDEO_HFR_2X
        **/
        public static final String VIDEO_HFR_2X = "60";
        /** @hide
        * VIDEO_HFR_3X
        **/
        public static final String VIDEO_HFR_3X = "90";
        /** @hide
        * VIDEO_HFR_4X
        **/
        public static final String VIDEO_HFR_4X = "120";

        // Values for auto scene detection settings.
        /** @hide
        * SCENE_DETECT_OFF
        **/
        public static final String SCENE_DETECT_OFF = "off";
        /** @hide
        * SCENE_DETECT_ON
        **/
        public static final String SCENE_DETECT_ON = "on";

        //Values for Continuous AF

        /** @hide
        * CAF off
        **/
        public static final String CONTINUOUS_AF_OFF = "caf-off";
        /** @hide
        * CAF on
        **/
        public static final String CONTINUOUS_AF_ON = "caf-on";
        /** @hide
        * Denoise off
        **/
        public static final String DENOISE_OFF = "denoise-off";
        /** @hide
        * Denoise on
        **/
        public static final String DENOISE_ON = "denoise-on";

        // Values for Redeye Reduction settings.
        /** @hide
        * REDEYE_REDUCTION_ENABLE
        **/
        public static final String REDEYE_REDUCTION_ENABLE = "enable";
        /** @hide
        * REDEYE_REDUCTION_DISABLE
        **/
        public static final String REDEYE_REDUCTION_DISABLE = "disable";

        // Values for selectable zone af settings.
        /** @hide
        * SELECTABLE_ZONE_AF_AUTO
        **/
        public static final String SELECTABLE_ZONE_AF_AUTO = "auto";
        /** @hide
        * SELECTABLE_ZONE_AF_SPOTMETERING
        **/
        public static final String SELECTABLE_ZONE_AF_SPOTMETERING = "spot-metering";
        /** @hide
        * SELECTABLE_ZONE_AF_CENTER_WEIGHTED
        **/
        public static final String SELECTABLE_ZONE_AF_CENTER_WEIGHTED = "center-weighted";
        /** @hide
        * SELECTABLE_ZONE_AF_FRAME_AVERAGE
        **/
        public static final String SELECTABLE_ZONE_AF_FRAME_AVERAGE = "frame-average";

        // Values for Face Detection settings.
        /** @hide
        * Face Detection off
        **/
        public static final String FACE_DETECTION_OFF = "off";
        /** @hide
        * Face Detction on
        **/
        public static final String FACE_DETECTION_ON = "on";

        /* ### QC ADDED PARAMETER APIS*/
         /** @hide
         * Gets the supported preview sizes in high frame rate recording mode.
         *
         * @return a list of Size object. This method will always return a list
         *         with at least one element.
         */
         public List<Size> getSupportedHfrSizes() {
            String str = get(KEY_QC_HFR_SIZE + SUPPORTED_VALUES_SUFFIX);
            return splitSize(str);
         }

         /** @hide
         * Gets the supported Touch AF/AEC setting.
         *
         * @return a List of TOUCH_AF_AEC_XXX string constants. null if TOUCH AF/AEC
         *         setting is not supported.
         *
         */
         public List<String> getSupportedTouchAfAec() {
            String str = get(KEY_QC_TOUCH_AF_AEC + SUPPORTED_VALUES_SUFFIX);
            return split(str);
         }

         /**
         * Gets the supported Touch AF/AEC setting.
         *
         * @return a List of TOUCH_AF_AEC_XXX string constants. null if TOUCH AF/AEC
         *         setting is not supported.
         *
         */

         /** @hide
         * Gets the supported frame rate modes.
         *
         * @return a List of FRAME_RATE_XXX_MODE string constant. null if this
         *         setting is not supported.
         */
         public List<String> getSupportedPreviewFrameRateModes() {
            String str = get(KEY_QC_PREVIEW_FRAME_RATE_MODE + SUPPORTED_VALUES_SUFFIX);
            return split(str);
         }

         /** @hide
         * Gets the supported auto scene detection modes.
         *
         * @return a List of SCENE_DETECT_XXX string constant. null if scene detection
         *         setting is not supported.
         *
         */
         public List<String> getSupportedSceneDetectModes() {
            String str = get(KEY_QC_SCENE_DETECT + SUPPORTED_VALUES_SUFFIX);
            return split(str);
         }

         /** @hide
         * Gets the supported Lensshade modes.
         *
         * @return a List of LENS_MODE_XXX string constants. null if lens mode
         *         setting is not supported.
         */
         public List<String> getSupportedLensShadeModes() {
            String str = get(KEY_QC_LENSSHADE + SUPPORTED_VALUES_SUFFIX);
            return split(str);
         }

         /** @hide
         * Gets the supported Histogram modes.
         *
         * @return a List of HISTOGRAM_XXX string constants. null if histogram mode
         *         setting is not supported.
         */
         public List<String> getSupportedHistogramModes() {
            String str = get(KEY_QC_HISTOGRAM + SUPPORTED_VALUES_SUFFIX);
            return split(str);
         }

         /** @hide
         * Gets the supported Skin Tone Enhancement modes.
         *
         * @return a List of SKIN_TONE_ENHANCEMENT_XXX string constants. null if skin tone enhancement
         *         setting is not supported.
         */
         public List<String> getSupportedSkinToneEnhancementModes() {
            String str = get(KEY_QC_SKIN_TONE_ENHANCEMENT + SUPPORTED_VALUES_SUFFIX);
            return split(str);
         }

          /** @hide
          * Gets the supported auto exposure setting.
          *
          * @return a List of AUTO_EXPOSURE_XXX string constants. null if auto exposure
          *         setting is not supported.
          */
          public List<String> getSupportedAutoexposure() {
             String str = get(KEY_QC_AUTO_EXPOSURE + SUPPORTED_VALUES_SUFFIX);
             return split(str);
          }

         /** @hide
         * Gets the supported MCE modes.
         *
         * @return a List of MCE_ENABLE/DISABLE string constants. null if MCE mode
         *         setting is not supported.
         */
         public List<String> getSupportedMemColorEnhanceModes() {
            String str = get(KEY_QC_MEMORY_COLOR_ENHANCEMENT + SUPPORTED_VALUES_SUFFIX);
            return split(str);
         }

         /** @hide
         * Gets the supported ZSL modes.
         *
         * @return a List of ZSL_OFF/OFF string constants. null if ZSL mode
         * setting is not supported.
         */
         public List<String> getSupportedZSLModes() {
            String str = get(KEY_QC_ZSL + SUPPORTED_VALUES_SUFFIX);
            return split(str);
         }

         /** @hide
         * Gets the supported Video HDR modes.
         *
         * @return a List of Video HDR_OFF/OFF string constants. null if
         * Video HDR mode setting is not supported.
         */
         public List<String> getSupportedVideoHDRModes() {
            String str = get(KEY_QC_VIDEO_HDR + SUPPORTED_VALUES_SUFFIX);
            return split(str);
         }

         /** @hide
         * Gets the supported HFR modes.
         *
         * @return a List of VIDEO_HFR_XXX string constants. null if hfr mode
         *         setting is not supported.
         */
         public List<String> getSupportedVideoHighFrameRateModes() {
            String str = get(KEY_QC_VIDEO_HIGH_FRAME_RATE + SUPPORTED_VALUES_SUFFIX);
            return split(str);
         }

         /** @hide
         * Gets the supported Continuous AF modes.
         *
         * @return a List of CONTINUOUS_AF_XXX string constant. null if continuous AF
         *         setting is not supported.
         *
         */
         public List<String> getSupportedContinuousAfModes() {
            String str = get(KEY_QC_CONTINUOUS_AF + SUPPORTED_VALUES_SUFFIX);
            return split(str);
         }

         /** @hide
         * Gets the supported DENOISE  modes.
         *
         * @return a List of DENOISE_XXX string constant. null if DENOISE
         *         setting is not supported.
         *
         */
         public List<String> getSupportedDenoiseModes() {
             String str = get(KEY_QC_DENOISE + SUPPORTED_VALUES_SUFFIX);
             return split(str);
         }

         /** @hide
         * Gets the supported selectable zone af setting.
         *
         * @return a List of SELECTABLE_ZONE_AF_XXX string constants. null if selectable zone af
         *         setting is not supported.
         */
         public List<String> getSupportedSelectableZoneAf() {
            String str = get(KEY_QC_SELECTABLE_ZONE_AF + SUPPORTED_VALUES_SUFFIX);
            return split(str);
         }

         /** @hide
         * Gets the supported face detection modes.
         *
         * @return a List of FACE_DETECTION_XXX string constant. null if face detection
         *         setting is not supported.
         *
         */
         public List<String> getSupportedFaceDetectionModes() {
            String str = get(KEY_QC_FACE_DETECTION + SUPPORTED_VALUES_SUFFIX);
            return split(str);
         }

        /** @hide
         * Gets the supported redeye reduction modes.
         *
         * @return a List of REDEYE_REDUCTION_XXX string constant. null if redeye reduction
         *         setting is not supported.
         *
         */
        public List<String> getSupportedRedeyeReductionModes() {
            String str = get(KEY_QC_REDEYE_REDUCTION + SUPPORTED_VALUES_SUFFIX);
            return split(str);
        }

         /** @hide
         * Sets GPS altitude reference. This will be stored in JPEG EXIF header.
         * @param altRef reference GPS altitude in meters.
         */
         public void setGpsAltitudeRef(double altRef) {
            set(KEY_QC_GPS_ALTITUDE_REF, Double.toString(altRef));
         }

         /** @hide
         * Sets GPS Status. This will be stored in JPEG EXIF header.
         *
         * @param status GPS status (UTC in seconds since January 1,
         *                  1970).
         */
         public void setGpsStatus(double status) {
            set(KEY_QC_GPS_STATUS, Double.toString(status));
         }

         /** @hide
         * Sets the touch co-ordinate for Touch AEC.
         *
         * @param x  the x co-ordinate of the touch event
         * @param y the y co-ordinate of the touch event
         *
         */
         public void setTouchIndexAec(int x, int y) {
            String v = Integer.toString(x) + "x" + Integer.toString(y);
            set(KEY_QC_TOUCH_INDEX_AEC, v);
         }

         /** @hide
         * Returns the touch co-ordinates of the touch event.
         *
         * @return a Index object with the x and y co-ordinated
         *          for the touch event
         *
         */
         public Coordinate getTouchIndexAec() {
            String pair = get(KEY_QC_TOUCH_INDEX_AEC);
            return strToCoordinate(pair);
         }

         /** @hide
         * Sets the touch co-ordinate for Touch AF.
         *
         * @param x  the x co-ordinate of the touch event
         * @param y the y co-ordinate of the touch event
         *
         */
         public void setTouchIndexAf(int x, int y) {
            String v = Integer.toString(x) + "x" + Integer.toString(y);
            set(KEY_QC_TOUCH_INDEX_AF, v);
         }

         /** @hide
         * Returns the touch co-ordinates of the touch event.
         *
         * @return a Index object with the x and y co-ordinated
         *          for the touch event
         *
         */
         public Coordinate getTouchIndexAf() {
            String pair = get(KEY_QC_TOUCH_INDEX_AF);
            return strToCoordinate(pair);
         }
         /** @hide
         * Set Sharpness Level
         *
         * @param sharpness level
         */
         public void setSharpness(int sharpness){
            if((sharpness < 0) || (sharpness > getMaxSharpness()) )
                throw new IllegalArgumentException(
                        "Invalid Sharpness " + sharpness);

            set(KEY_QC_SHARPNESS, String.valueOf(sharpness));
         }

         /** @hide
         * Set Contrast Level
         *
         * @param contrast level
         */
         public void setContrast(int contrast){
            if((contrast < 0 ) || (contrast > getMaxContrast()))
                throw new IllegalArgumentException(
                        "Invalid Contrast " + contrast);

            set(KEY_QC_CONTRAST, String.valueOf(contrast));
         }

         /** @hide
         * Set Saturation Level
         *
         * @param saturation level
         */
         public void setSaturation(int saturation){
            if((saturation < 0 ) || (saturation > getMaxSaturation()))
                throw new IllegalArgumentException(
                        "Invalid Saturation " + saturation);

            set(KEY_QC_SATURATION, String.valueOf(saturation));
         }

         /** @hide
         * Get Sharpness level
         *
         * @return sharpness level
         */
         public int getSharpness(){
            return getInt(KEY_QC_SHARPNESS);
         }

         /** @hide
         * Get Max Sharpness Level
         *
         * @return max sharpness level
         */
         public int getMaxSharpness(){
            return getInt(KEY_QC_MAX_SHARPNESS);
         }

         /** @hide
         * Get Contrast level
         *
         * @return contrast level
         */
         public int getContrast(){
            return getInt(KEY_QC_CONTRAST);
         }

         /** @hide
         * Get Max Contrast Level
         *
         * @return max contrast level
         */
         public int getMaxContrast(){
            return getInt(KEY_QC_MAX_CONTRAST);
         }

         /** @hide
         * Get Saturation level
         *
         * @return saturation level
         */
         public int getSaturation(){
            return getInt(KEY_QC_SATURATION);
         }

         /** @hide
         * Get Max Saturation Level
         *
         * @return max contrast level
         */
         public int getMaxSaturation(){
            return getInt(KEY_QC_MAX_SATURATION);
         }

         /** @hide
         * Sets GPS latitude reference coordinate. This will be stored in JPEG EXIF
         * header.
         * @param latRef GPS latitude reference coordinate.
         */
         public void setGpsLatitudeRef(String latRef) {
            set(KEY_QC_GPS_LATITUDE_REF, latRef);
         }

         /** @hide
         * Sets GPS longitude reference coordinate. This will be stored in JPEG EXIF
         * header.
         * @param lonRef GPS longitude reference coordinate.
         */
         public void setGpsLongitudeRef(String lonRef) {
            set(KEY_QC_GPS_LONGITUDE_REF, lonRef);
         }

         /** @hide
         * Sets system timestamp. This will be stored in JPEG EXIF header.
         *
         * @param dateTime current timestamp (UTC in seconds since January 1,
         *                  1970).
         */
         public void setExifDateTime(String dateTime) {
            set(KEY_QC_EXIF_DATETIME, dateTime);
         }

         /** @hide
         * Gets the current Touch AF/AEC setting.
         *
         * @return one of TOUCH_AF_AEC_XXX string constant. null if Touch AF/AEC
         *         setting is not supported.
         *
         */
         public String getTouchAfAec() {
            return get(KEY_QC_TOUCH_AF_AEC);
         }

         /** @hide
         * Sets the current TOUCH AF/AEC setting.
         *
         * @param value TOUCH_AF_AEC_XXX string constants.
         *
         */
         public void setTouchAfAec(String value) {
            set(KEY_QC_TOUCH_AF_AEC, value);
         }

         /** @hide
         * Gets the current redeye reduction setting.
         *
         * @return one of REDEYE_REDUCTION_XXX string constant. null if redeye reduction
         *         setting is not supported.
         *
         */
         public String getRedeyeReductionMode() {
            return get(KEY_QC_REDEYE_REDUCTION);
         }

         /** @hide
         * Sets the redeye reduction. Other parameters may be changed after changing
         * redeye reduction. After setting redeye reduction,
         * applications should call getParameters to know if some parameters are
         * changed.
         *
         * @param value REDEYE_REDUCTION_XXX string constants.
         *
         */
         public void setRedeyeReductionMode(String value) {
            set(KEY_QC_REDEYE_REDUCTION, value);
         }

         /** @hide
         * Gets the frame rate mode setting.
         *
         * @return one of FRAME_RATE_XXX_MODE string constant. null if this
         *         setting is not supported.
         */
         public String getPreviewFrameRateMode() {
            return get(KEY_QC_PREVIEW_FRAME_RATE_MODE);
         }

         /** @hide
         * Sets the frame rate mode.
         *
         * @param value FRAME_RATE_XXX_MODE string constants.
         */
         public void setPreviewFrameRateMode(String value) {
            set(KEY_QC_PREVIEW_FRAME_RATE_MODE, value);
         }

         /** @hide
         * Gets the current auto scene detection setting.
         *
         * @return one of SCENE_DETECT_XXX string constant. null if auto scene detection
         *         setting is not supported.
         *
         */
         public String getSceneDetectMode() {
            return get(KEY_QC_SCENE_DETECT);
         }

         /** @hide
         * Sets the auto scene detect. Other parameters may be changed after changing
         * scene detect. After setting auto scene detection,
         * applications should call getParameters to know if some parameters are
         * changed.
         *
         * @param value SCENE_DETECT_XXX string constants.
         *
         */
         public void setSceneDetectMode(String value) {
            set(KEY_QC_SCENE_DETECT, value);
         }

         /** @hide
         * Gets the current hdr bracketing mode setting.
         *
         * @return current hdr bracketing mode.
         * @see #KEY_AE_BRACKET_OFF
         * @see #KEY_AE_BRACKET_HDR
         * @see #KEY_AE_BRACKET_BRACKATING
         */
         public String getAEBracket() {
            return get(KEY_QC_AE_BRACKET_HDR);
         }

         /** @hide
         * Set HDR-Bracketing Level
         *
         * @param value HDR-Bracketing
         */
         public void setAEBracket(String value){
            set(KEY_QC_AE_BRACKET_HDR, value);
         }

         /** @hide
         * Gets the current LensShade Mode.
         *
         * @return LensShade Mode
         */
         public String getLensShade() {
            return get(KEY_QC_LENSSHADE);
         }

         /** @hide
         * Sets the current LensShade Mode.
         *
         * @return LensShade Mode
         */
         public void setLensShade(String lensshade) {
            set(KEY_QC_LENSSHADE, lensshade);
         }

         /** @hide
         * Gets the current auto exposure setting.
         *
         * @return one of AUTO_EXPOSURE_XXX string constant. null if auto exposure
         *         setting is not supported.
         */
         public String getAutoExposure() {
            return get(KEY_QC_AUTO_EXPOSURE);
         }

         /** @hide
         * Sets the current auto exposure setting.
         *
         * @param value AUTO_EXPOSURE_XXX string constants.
         */
         public void setAutoExposure(String value) {
            set(KEY_QC_AUTO_EXPOSURE, value);
         }

         /** @hide
         * Gets the current MCE Mode.
         *
         * @return MCE value
         */
         public String getMemColorEnhance() {
            return get(KEY_QC_MEMORY_COLOR_ENHANCEMENT);
         }

         /** @hide
         * Sets the current MCE Mode.
         *
         * @return MCE Mode
         */
         public void setMemColorEnhance(String mce) {
            set(KEY_QC_MEMORY_COLOR_ENHANCEMENT, mce);
         }

         /** @hide
         * Gets the current ZSL Mode.
         *
         * @return ZSL mode value
         */
         public String getZSLMode() {
            return get(KEY_QC_ZSL);
         }

         /** @hide
         * Sets the current ZSL Mode. ZSL mode is set as a 0th bit in KEY_CAMERA_MODE.
         *
         * @return null
         */
         public void setZSLMode(String zsl) {
            set(KEY_QC_ZSL, zsl);
         }

         /** @hide
         * Gets the current Camera Mode Flag. Camera mode includes a
         * flag(byte) which indicates different camera modes.
         * For now support for ZSL added at bit0
         *
         * @return Camera Mode.
         */
         public String getCameraMode() {
           return get(KEY_QC_CAMERA_MODE);
         }

         /** @hide
         * Sets the current Camera Mode.
         *
         * @return null
         */
         public void setCameraMode(int cameraMode) {
           set(KEY_QC_CAMERA_MODE, cameraMode);
         }

         /** @hide
         * Gets the current HFR Mode.
         *
         * @return VIDEO_HFR_XXX string constants
         */
         public String getVideoHighFrameRate() {
            return get(KEY_QC_VIDEO_HIGH_FRAME_RATE);
         }

         /** @hide
         * Sets the current HFR Mode.
         *
         * @param hfr VIDEO_HFR_XXX string constants
         */
         public void setVideoHighFrameRate(String hfr) {
            set(KEY_QC_VIDEO_HIGH_FRAME_RATE, hfr);
         }

         /** @hide
         * Gets the current Video HDR Mode.
         *
         * @return Video HDR mode value
         */
         public String getVideoHDRMode() {
            return get(KEY_QC_VIDEO_HDR);
         }

         /** @hide
         * Sets the current Video HDR Mode.
         *
         * @return null
         */
         public void setVideoHDRMode(String videohdr) {
            set(KEY_QC_VIDEO_HDR, videohdr);
         }

         /** @hide
         * Gets the current DENOISE  setting.
         *
         * @return one of DENOISE_XXX string constant. null if Denoise
         *         setting is not supported.
         *
         */
         public String getDenoise() {
             return get(KEY_QC_DENOISE);
         }

         /** @hide
         * Gets the current Continuous AF setting.
         *
         * @return one of CONTINUOUS_AF_XXX string constant. null if continuous AF
         *         setting is not supported.
         *
         */
         public String getContinuousAf() {
            return get(KEY_QC_CONTINUOUS_AF);
         }

         /** @hide
         * Sets the current Denoise  mode.
         * @param value DENOISE_XXX string constants.
         *
         */

         public void setDenoise(String value) {
             set(KEY_QC_DENOISE, value);
         }

         /** @hide
         * Sets the current Continuous AF mode.
         * @param value CONTINUOUS_AF_XXX string constants.
         *
         */
         public void setContinuousAf(String value) {
            set(KEY_QC_CONTINUOUS_AF, value);
         }

         /** @hide
         * Gets the current selectable zone af setting.
         *
         * @return one of SELECTABLE_ZONE_AF_XXX string constant. null if selectable zone af
         *         setting is not supported.
         */
         public String getSelectableZoneAf() {
            return get(KEY_QC_SELECTABLE_ZONE_AF);
         }

         /** @hide
         * Sets the current selectable zone af setting.
         *
         * @param value SELECTABLE_ZONE_AF_XXX string constants.
         */
         public void setSelectableZoneAf(String value) {
            set(KEY_QC_SELECTABLE_ZONE_AF, value);
         }

         /** @hide
         * Gets the current face detection setting.
         *
         * @return one of FACE_DETECTION_XXX string constant. null if face detection
         *         setting is not supported.
         *
         */
         public String getFaceDetectionMode() {
            return get(KEY_QC_FACE_DETECTION);
         }

         /** @hide
         * Sets the auto scene detect. Other settings like Touch AF/AEC might be
         * changed after setting face detection.
         *
         * @param value FACE_DETECTION_XXX string constants.
         *
         */
         public void setFaceDetectionMode(String value) {
            set(KEY_QC_FACE_DETECTION, value);
         }

         // Splits a comma delimited string to an ArrayList of Coordinate.
         // Return null if the passing string is null or the Coordinate is 0.
         private ArrayList<Coordinate> splitCoordinate(String str) {
            if (str == null) return null;
            TextUtils.StringSplitter splitter = new TextUtils.SimpleStringSplitter(',');
            splitter.setString(str);
            ArrayList<Coordinate> coordinateList = new ArrayList<Coordinate>();
            for (String s : splitter) {
                Coordinate coordinate = strToCoordinate(s);
                if (coordinate != null) coordinateList.add(coordinate);
            }
            if (coordinateList.size() == 0) return null;
            return coordinateList;
         }

         // Parses a string (ex: "500x500") to Coordinate object.
         // Return null if the passing string is null.
         private Coordinate strToCoordinate(String str) {
            if (str == null) return null;

            int pos = str.indexOf('x');
            if (pos != -1) {
                String x = str.substring(0, pos);
                String y = str.substring(pos + 1);
                return new Coordinate(Integer.parseInt(x),
                                Integer.parseInt(y));
            }
            Log.e(TAG, "Invalid Coordinate parameter string=" + str);
            return null;
         }
         /* ### QC ADD-ONS: END */
    };
}
