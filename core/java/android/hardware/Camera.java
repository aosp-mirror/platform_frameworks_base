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
import java.util.HashMap;
import java.util.StringTokenizer;
import java.io.IOException;

import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.graphics.PixelFormat;
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
    
    // These match the enums in frameworks/base/include/ui/Camera.h
    private static final int CAMERA_MSG_ERROR = 0;
    private static final int CAMERA_MSG_SHUTTER = 1;
    private static final int CAMERA_MSG_FOCUS = 2;
    private static final int CAMERA_MSG_ZOOM = 3;
    private static final int CAMERA_MSG_PREVIEW_FRAME = 4;
    private static final int CAMERA_MSG_VIDEO_FRAME = 5;
    private static final int CAMERA_MSG_POSTVIEW_FRAME = 6;
    private static final int CAMERA_MSG_RAW_IMAGE = 7;
    private static final int CAMERA_MSG_COMPRESSED_IMAGE = 8;

    private int mNativeContext; // accessed by native methods
    private EventHandler mEventHandler;
    private ShutterCallback mShutterCallback;
    private PictureCallback mRawImageCallback;
    private PictureCallback mJpegCallback;
    private PreviewCallback mPreviewCallback;
    private AutoFocusCallback mAutoFocusCallback;
    private ErrorCallback mErrorCallback;
    private boolean mOneShot;
    
    /**
     * Returns a new Camera object.
     */
    public static Camera open() { 
        return new Camera(); 
    }

    Camera() {
        mShutterCallback = null;
        mRawImageCallback = null;
        mJpegCallback = null;
        mPreviewCallback = null;

        Looper looper;
        if ((looper = Looper.myLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else if ((looper = Looper.getMainLooper()) != null) {
            mEventHandler = new EventHandler(this, looper);
        } else {
            mEventHandler = null;
        }

        native_setup(new WeakReference<Camera>(this));
    }
    
    protected void finalize() { 
        native_release(); 
    }
    
    private native final void native_setup(Object camera_this);
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
     * can be connected to another proces.
     *
     * @throws IOException if the method fails.
     *
     * FIXME: Unhide after approval
     * @hide
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
     * Returns 0 if lock was successful.
     *
     * FIXME: Unhide after approval
     * @hide
     */
    public native final int lock();
    
    /**
     * Unlock the camera to allow aother process to access it. To save
     * setup/teardown time, a client of Camera can pass an initialized Camera
     * object to another process. This method is used to unlock the Camera
     * object before handing off the Camera object to the other process.

     * Returns 0 if unlock was successful.
     *
     * FIXME: Unhide after approval
     * @hide
     */
    public native final int unlock();
    
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
         *  by {@link android.graphics.PixelFormat}, which can be queried 
         *  with {@link android.hardware.Camera.Parameters#getPreviewFormat()}.
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
        setHasPreviewCallback(cb != null, false);
    }

    /**
     * Installs a callback to retrieve a single preview frame, after which the
     * callback is cleared.
     *
     * @param cb A callback object that receives a copy of the preview frame.
     */
    public final void setOneShotPreviewCallback(PreviewCallback cb) {
        if (cb != null) {
            mPreviewCallback = cb;
            mOneShot = true;
            setHasPreviewCallback(true, true);
        }
    }

    private native final void setHasPreviewCallback(boolean installed, boolean oneshot);

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
                if (mRawImageCallback != null)
                    mRawImageCallback.onPictureTaken((byte[])msg.obj, mCamera);
                return;

            case CAMERA_MSG_COMPRESSED_IMAGE:
                if (mJpegCallback != null)
                    mJpegCallback.onPictureTaken((byte[])msg.obj, mCamera);
                return;
            
            case CAMERA_MSG_PREVIEW_FRAME:
                if (mPreviewCallback != null) {
                    mPreviewCallback.onPreviewFrame((byte[])msg.obj, mCamera);
                    if (mOneShot) {
                        mPreviewCallback = null;
                    }
                }
                return;

            case CAMERA_MSG_FOCUS:
                if (mAutoFocusCallback != null)
                    mAutoFocusCallback.onAutoFocus(msg.arg1 == 0 ? false : true, mCamera);
                return;

            case CAMERA_MSG_ERROR :
                Log.e(TAG, "Error " + msg.arg1);
                if (mErrorCallback != null)
                    mErrorCallback.onError(msg.arg1, mCamera);
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
         * Callback for the camera auto focus.
         * 
         * @param success true if focus was successful, false if otherwise
         * @param camera  the Camera service object
         */
        void onAutoFocus(boolean success, Camera camera);
    };

    /**
     * Starts auto-focus function and registers a callback function to
     * run when camera is focused. Only valid after startPreview() has
     * been called.
     * <p>Devices that do not support auto-focus will trigger a "fake" 
     * callback to the 
     * {@link android.hardware.Camera.AutoFocusCallback}. 
     * If your application should not be installed
     * on devices without auto-focus, you must declare that your application 
     * uses auto-focus with the 
     * <a href="{@docRoot}guide/topics/manifest/uses-feature-element.html">&lt;uses-feature></a>
     * manifest element.</p>
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
     * Triggers an asynchronous image capture. The camera service
     * will initiate a series of callbacks to the application as the
     * image capture progresses. The shutter callback occurs after
     * the image is captured. This can be used to trigger a sound
     * to let the user know that image has been captured. The raw
     * callback occurs when the raw image data is available. The jpeg
     * callback occurs when the compressed image is available. If the
     * application does not need a particular callback, a null can be
     * passed instead of a callback method.
     * 
     * @param shutter   callback after the image is captured, may be null
     * @param raw       callback with raw image data, may be null
     * @param jpeg      callback with jpeg image data, may be null
     */
    public final void takePicture(ShutterCallback shutter, PictureCallback raw,
            PictureCallback jpeg) {
        mShutterCallback = shutter;
        mRawImageCallback = raw;
        mJpegCallback = jpeg;
        native_takePicture();
    }
    private native final void native_takePicture();

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
        /** width of the picture */
        public int width;
        /** height of the picture */
        public int height;
    };

    /**
     * Handles the parameters for pictures created by a Camera service.
     */
    public class Parameters {
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
            set("preview-size", v);
        }

        /**
         * Returns the dimensions setting for preview pictures.
         * 
         * @return a Size object with the height and width setting 
         *          for the preview picture
         */
        public Size getPreviewSize() {
            String pair = get("preview-size");
            if (pair == null)
                return null;
            String[] dims = pair.split("x");
            if (dims.length != 2)
                return null;

            return new Size(Integer.parseInt(dims[0]),
                            Integer.parseInt(dims[1]));

        }

        /**
         * Sets the dimensions for EXIF thumbnails.
         * 
         * @param width  the width of the thumbnail, in pixels
         * @param height the height of the thumbnail, in pixels
         *
         * FIXME: unhide before release
         * @hide
         */
        public void setThumbnailSize(int width, int height) {
            set("jpeg-thumbnail-width", width);
            set("jpeg-thumbnail-height", height);
        }

        /**
         * Returns the dimensions for EXIF thumbnail
         * 
         * @return a Size object with the height and width setting 
         *          for the EXIF thumbnails
         *
         * FIXME: unhide before release
         * @hide
         */
        public Size getThumbnailSize() {
            return new Size(getInt("jpeg-thumbnail-width"),
                            getInt("jpeg-thumbnail-height"));
        }

        /**
         * Sets the quality of the EXIF thumbnail
         * 
         * @param quality the JPEG quality of the EXIT thumbnail
         *
         * FIXME: unhide before release
         * @hide
         */
        public void setThumbnailQuality(int quality) {
            set("jpeg-thumbnail-quality", quality);
        }

        /**
         * Returns the quality setting for the EXIF thumbnail
         * 
         * @return the JPEG quality setting of the EXIF thumbnail
         *
         * FIXME: unhide before release
         * @hide
         */
        public int getThumbnailQuality() {
            return getInt("jpeg-thumbnail-quality");
        }

        /**
         * Sets the rate at which preview frames are received.
         * 
         * @param fps the frame rate (frames per second)
         */
        public void setPreviewFrameRate(int fps) {
            set("preview-frame-rate", fps);
        }

        /**
         * Returns the setting for the rate at which preview frames
         * are received.
         * 
         * @return the frame rate setting (frames per second)
         */
        public int getPreviewFrameRate() {
            return getInt("preview-frame-rate");
        }

        /**
         * Sets the image format for preview pictures. 
         * <p>If the image format is not set with this method, then the
         * preview format will default to 
         * {@link android.graphics.PixelFormat#YCbCr_420_SP}, which
         * uses the NV21 encoding format.</p>
         * 
         * @param pixel_format the desired preview picture format, defined
         *   by one of the {@link android.graphics.PixelFormat} constants.
         *   (E.g., <var>PixelFormat.YCbCr_420_SP</var> (default),
         *                      <var>PixelFormat.RGB_565</var>, or
         *                      <var>PixelFormat.JPEG</var>)
         * @see android.graphics.PixelFormat
         */
        public void setPreviewFormat(int pixel_format) {
            String s = cameraFormatForPixelFormat(pixel_format);
            if (s == null) {
                throw new IllegalArgumentException();
            }

            set("preview-format", s);
        }

        /**
         * Returns the image format for preview pictures.
         * 
         * @return the {@link android.graphics.PixelFormat} int representing 
         *         the preview picture format.
         */
        public int getPreviewFormat() {
            return pixelFormatForCameraFormat(get("preview-format"));
        }

        /**
         * Sets the dimensions for pictures.
         * 
         * @param width  the width for pictures, in pixels
         * @param height the height for pictures, in pixels
         */
        public void setPictureSize(int width, int height) {
            String v = Integer.toString(width) + "x" + Integer.toString(height);
            set("picture-size", v);
        }

        /**
         * Returns the dimension setting for pictures.
         * 
         * @return a Size object with the height and width setting 
         *          for pictures
         */
        public Size getPictureSize() {
            String pair = get("picture-size");
            if (pair == null)
                return null;
            String[] dims = pair.split("x");
            if (dims.length != 2)
                return null;

            return new Size(Integer.parseInt(dims[0]),
                            Integer.parseInt(dims[1]));

        }

        /**
         * Sets the image format for pictures.
         * 
         * @param pixel_format the desired picture format 
         *                     (<var>PixelFormat.YCbCr_420_SP</var>,
         *                      <var>PixelFormat.RGB_565</var>, or
         *                      <var>PixelFormat.JPEG</var>)
         * @see android.graphics.PixelFormat
         */
        public void setPictureFormat(int pixel_format) {
            String s = cameraFormatForPixelFormat(pixel_format);
            if (s == null) {
                throw new IllegalArgumentException();
            }

            set("picture-format", s);
        }

        /**
         * Returns the image format for pictures.
         * 
         * @return the PixelFormat int representing the picture format
         */
        public int getPictureFormat() {
            return pixelFormatForCameraFormat(get("picture-format"));
        }

        private String cameraFormatForPixelFormat(int pixel_format) {
            switch(pixel_format) {
            case PixelFormat.YCbCr_422_SP: return "yuv422sp";
            case PixelFormat.YCbCr_420_SP: return "yuv420sp";
            case PixelFormat.RGB_565:      return "rgb565";
            case PixelFormat.JPEG:         return "jpeg";
            default:                       return null;
            }
        }

        private int pixelFormatForCameraFormat(String format) {
            if (format == null)
                return PixelFormat.UNKNOWN;

            if (format.equals("yuv422sp"))
                return PixelFormat.YCbCr_422_SP;

            if (format.equals("yuv420sp"))
                return PixelFormat.YCbCr_420_SP;

            if (format.equals("rgb565"))
                return PixelFormat.RGB_565;

            if (format.equals("jpeg"))
                return PixelFormat.JPEG;

            return PixelFormat.UNKNOWN;
        }

    };
}


