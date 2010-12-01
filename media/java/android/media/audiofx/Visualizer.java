/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.media.audiofx;

import android.util.Log;
import java.lang.ref.WeakReference;
import java.io.IOException;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

/**
 * The Visualizer class enables application to retrieve part of the currently playing audio for
 * visualization purpose. It is not an audio recording interface and only returns partial and low
 * quality audio content. However, to protect privacy of certain audio data (e.g voice mail) the use
 * of the visualizer requires the permission android.permission.RECORD_AUDIO.
 * <p>The audio session ID passed to the constructor indicates which audio content should be
 * visualized:<br>
 * <ul>
 *   <li>If the session is 0, the audio output mix is visualized</li>
 *   <li>If the session is not 0, the audio from a particular {@link android.media.MediaPlayer} or
 *   {@link android.media.AudioTrack}
 *   using this audio session is visualized </li>
 * </ul>
 * <p>Two types of representation of audio content can be captured: <br>
 * <ul>
 *   <li>Waveform data: consecutive 8-bit (unsigned) mono samples by using the
 *   {@link #getWaveForm(byte[])} method</li>
 *   <li>Frequency data: 8-bit magnitude FFT by using the {@link #getFft(byte[])} method</li>
 * </ul>
 * <p>The length of the capture can be retrieved or specified by calling respectively
 * {@link #getCaptureSize()} and {@link #setCaptureSize(int)} methods. The capture size must be a
 * power of 2 in the range returned by {@link #getCaptureSizeRange()}.
 * <p>In addition to the polling capture mode described above with {@link #getWaveForm(byte[])} and
 *  {@link #getFft(byte[])} methods, a callback mode is also available by installing a listener by
 *  use of the {@link #setDataCaptureListener(OnDataCaptureListener, int, boolean, boolean)} method.
 *  The rate at which the listener capture method is called as well as the type of data returned is
 *  specified.
 * <p>Before capturing data, the Visualizer must be enabled by calling the
 * {@link #setEnabled(boolean)} method.
 * When data capture is not needed any more, the Visualizer should be disabled.
 * <p>It is good practice to call the {@link #release()} method when the Visualizer is not used
 * anymore to free up native resources associated to the Visualizer instance.
 * <p>Creating a Visualizer on the output mix (audio session 0) requires permission
 * {@link android.Manifest.permission#MODIFY_AUDIO_SETTINGS}
 */

public class Visualizer {

    static {
        System.loadLibrary("audioeffect_jni");
        native_init();
    }

    private final static String TAG = "Visualizer-JAVA";

    /**
     * State of a Visualizer object that was not successfully initialized upon creation
     */
    public static final int STATE_UNINITIALIZED = 0;
    /**
     * State of a Visualizer object that is ready to be used.
     */
    public static final int STATE_INITIALIZED   = 1;
    /**
     * State of a Visualizer object that is active.
     */
    public static final int STATE_ENABLED   = 2;

    // to keep in sync with frameworks/base/media/jni/audioeffect/android_media_Visualizer.cpp
    private static final int NATIVE_EVENT_PCM_CAPTURE = 0;
    private static final int NATIVE_EVENT_FFT_CAPTURE = 1;

    // Error codes:
    /**
     * Successful operation.
     */
    public  static final int SUCCESS              = 0;
    /**
     * Unspecified error.
     */
    public  static final int ERROR                = -1;
    /**
     * Internal opreation status. Not returned by any method.
     */
    public  static final int ALREADY_EXISTS       = -2;
    /**
     * Operation failed due to bad object initialization.
     */
    public  static final int ERROR_NO_INIT              = -3;
    /**
     * Operation failed due to bad parameter value.
     */
    public  static final int ERROR_BAD_VALUE            = -4;
    /**
     * Operation failed because it was requested in wrong state.
     */
    public  static final int ERROR_INVALID_OPERATION    = -5;
    /**
     * Operation failed due to lack of memory.
     */
    public  static final int ERROR_NO_MEMORY            = -6;
    /**
     * Operation failed due to dead remote object.
     */
    public  static final int ERROR_DEAD_OBJECT          = -7;

    //--------------------------------------------------------------------------
    // Member variables
    //--------------------
    /**
     * Indicates the state of the Visualizer instance
     */
    private int mState = STATE_UNINITIALIZED;
    /**
     * Lock to synchronize access to mState
     */
    private final Object mStateLock = new Object();
    /**
     * System wide unique Identifier of the visualizer engine used by this Visualizer instance
     */
    private int mId;

    /**
     * Lock to protect listeners updates against event notifications
     */
    private final Object mListenerLock = new Object();
    /**
     * Handler for events coming from the native code
     */
    private NativeEventHandler mNativeEventHandler = null;
    /**
     *  PCM and FFT capture listener registered by client
     */
    private OnDataCaptureListener mCaptureListener = null;

    // accessed by native methods
    private int mNativeVisualizer;
    private int mJniData;

    //--------------------------------------------------------------------------
    // Constructor, Finalize
    //--------------------
    /**
     * Class constructor.
     * @param audioSession system wide unique audio session identifier. If audioSession
     *  is not 0, the visualizer will be attached to the MediaPlayer or AudioTrack in the
     *  same audio session. Otherwise, the Visualizer will apply to the output mix.
     *
     * @throws java.lang.UnsupportedOperationException
     * @throws java.lang.RuntimeException
     */

    public Visualizer(int audioSession)
    throws UnsupportedOperationException, RuntimeException {
        int[] id = new int[1];

        synchronized (mStateLock) {
            mState = STATE_UNINITIALIZED;
            // native initialization
            int result = native_setup(new WeakReference<Visualizer>(this), audioSession, id);
            if (result != SUCCESS && result != ALREADY_EXISTS) {
                Log.e(TAG, "Error code "+result+" when initializing Visualizer.");
                switch (result) {
                case ERROR_INVALID_OPERATION:
                    throw (new UnsupportedOperationException("Effect library not loaded"));
                default:
                    throw (new RuntimeException("Cannot initialize Visualizer engine, error: "
                            +result));
                }
            }
            mId = id[0];
            if (native_getEnabled()) {
                mState = STATE_ENABLED;
            } else {
                mState = STATE_INITIALIZED;
            }
        }
    }

    /**
     * Releases the native Visualizer resources. It is a good practice to release the
     * visualization engine when not in use.
     */
    public void release() {
        synchronized (mStateLock) {
            native_release();
            mState = STATE_UNINITIALIZED;
        }
    }

    @Override
    protected void finalize() {
        native_finalize();
    }

    /**
     * Enable or disable the visualization engine.
     * @param enabled requested enable state
     * @return {@link #SUCCESS} in case of success,
     * {@link #ERROR_INVALID_OPERATION} or {@link #ERROR_DEAD_OBJECT} in case of failure.
     * @throws IllegalStateException
     */
    public int setEnabled(boolean enabled)
    throws IllegalStateException {
        synchronized (mStateLock) {
            if (mState == STATE_UNINITIALIZED) {
                throw(new IllegalStateException("setEnabled() called in wrong state: "+mState));
            }
            int status = SUCCESS;
            if ((enabled && (mState == STATE_INITIALIZED)) ||
                    (!enabled && (mState == STATE_ENABLED))) {
                status = native_setEnabled(enabled);
                if (status == SUCCESS) {
                    mState = enabled ? STATE_ENABLED : STATE_INITIALIZED;
                }
            }
            return status;
        }
    }

    /**
     * Get current activation state of the visualizer.
     * @return true if the visualizer is active, false otherwise
     */
    public boolean getEnabled()
    {
        synchronized (mStateLock) {
            if (mState == STATE_UNINITIALIZED) {
                throw(new IllegalStateException("getEnabled() called in wrong state: "+mState));
            }
            return native_getEnabled();
        }
    }

    /**
     * Returns the capture size range.
     * @return the mininum capture size is returned in first array element and the maximum in second
     * array element.
     */
    public static native int[] getCaptureSizeRange();

    /**
     * Returns the maximum capture rate for the callback capture method. This is the maximum value
     * for the rate parameter of the
     * {@link #setDataCaptureListener(OnDataCaptureListener, int, boolean, boolean)} method.
     * @return the maximum capture rate expressed in milliHertz
     */
    public static native int getMaxCaptureRate();

    /**
     * Sets the capture size, i.e. the number of bytes returned by {@link #getWaveForm(byte[])} and
     * {@link #getFft(byte[])} methods. The capture size must be a power of 2 in the range returned
     * by {@link #getCaptureSizeRange()}.
     * This method must not be called when the Visualizer is enabled.
     * @param size requested capture size
     * @return {@link #SUCCESS} in case of success,
     * {@link #ERROR_BAD_VALUE} in case of failure.
     * @throws IllegalStateException
     */
    public int setCaptureSize(int size)
    throws IllegalStateException {
        synchronized (mStateLock) {
            if (mState != STATE_INITIALIZED) {
                throw(new IllegalStateException("setCaptureSize() called in wrong state: "+mState));
            }
            return native_setCaptureSize(size);
        }
    }

    /**
     * Returns current capture size.
     * @return the capture size in bytes.
     */
    public int getCaptureSize()
    throws IllegalStateException {
        synchronized (mStateLock) {
            if (mState == STATE_UNINITIALIZED) {
                throw(new IllegalStateException("getCaptureSize() called in wrong state: "+mState));
            }
            return native_getCaptureSize();
        }
    }

    /**
     * Returns the sampling rate of the captured audio.
     * @return the sampling rate in milliHertz.
     */
    public int getSamplingRate()
    throws IllegalStateException {
        synchronized (mStateLock) {
            if (mState == STATE_UNINITIALIZED) {
                throw(new IllegalStateException("getSamplingRate() called in wrong state: "+mState));
            }
            return native_getSamplingRate();
        }
    }

    /**
     * Returns a waveform capture of currently playing audio content. The capture consists in
     * a number of consecutive 8-bit (unsigned) mono PCM samples equal to the capture size returned
     * by {@link #getCaptureSize()}.
     * <p>This method must be called when the Visualizer is enabled.
     * @param waveform array of bytes where the waveform should be returned
     * @return {@link #SUCCESS} in case of success,
     * {@link #ERROR_NO_MEMORY}, {@link #ERROR_INVALID_OPERATION} or {@link #ERROR_DEAD_OBJECT}
     * in case of failure.
     * @throws IllegalStateException
     */
    public int getWaveForm(byte[] waveform)
    throws IllegalStateException {
        synchronized (mStateLock) {
            if (mState != STATE_ENABLED) {
                throw(new IllegalStateException("getWaveForm() called in wrong state: "+mState));
            }
            return native_getWaveForm(waveform);
        }
    }
    /**
     * Returns a frequency capture of currently playing audio content.
     * <p>This method must be called when the Visualizer is enabled.
     * <p>The capture is an 8-bit magnitude FFT, the frequency range covered being 0 (DC) to half of
     * the sampling rate returned by {@link #getSamplingRate()}. The capture returns the real and
     * imaginary parts of a number of frequency points equal to half of the capture size plus one.
     * <p>Note: only the real part is returned for the first point (DC) and the last point
     * (sampling frequency / 2).
     * <p>The layout in the returned byte array is as follows:
     * <ul>
     *   <li> n is the capture size returned by getCaptureSize()</li>
     *   <li> Rfk, Ifk are respectively  the real and imaginary parts of the kth frequency
     *   component</li>
     *   <li> If Fs is the sampling frequency retuned by getSamplingRate() the kth frequency is:
     *   (k*Fs)/(n/2) </li>
     * </ul>
     * <table border="0" cellspacing="0" cellpadding="0">
     * <tr><td>Index </p></td>
     *     <td>0 </p></td>
     *     <td>1 </p></td>
     *     <td>2 </p></td>
     *     <td>3 </p></td>
     *     <td>4 </p></td>
     *     <td>5 </p></td>
     *     <td>... </p></td>
     *     <td>n - 2 </p></td>
     *     <td>n - 1 </p></td></tr>
     * <tr><td>Data </p></td>
     *     <td>Rf0 </p></td>
     *     <td>Rf(n/2) </p></td>
     *     <td>Rf1 </p></td>
     *     <td>If1 </p></td>
     *     <td>Rf2 </p></td>
     *     <td>If2 </p></td>
     *     <td>... </p></td>
     *     <td>Rf(n-1)/2 </p></td>
     *     <td>If(n-1)/2 </p></td></tr>
     * </table>
     * @param fft array of bytes where the FFT should be returned
     * @return {@link #SUCCESS} in case of success,
     * {@link #ERROR_NO_MEMORY}, {@link #ERROR_INVALID_OPERATION} or {@link #ERROR_DEAD_OBJECT}
     * in case of failure.
     * @throws IllegalStateException
     */
    public int getFft(byte[] fft)
    throws IllegalStateException {
        synchronized (mStateLock) {
            if (mState != STATE_ENABLED) {
                throw(new IllegalStateException("getFft() called in wrong state: "+mState));
            }
            return native_getFft(fft);
        }
    }

    //---------------------------------------------------------
    // Interface definitions
    //--------------------
    /**
     * The OnDataCaptureListener interface defines methods called by the Visualizer to periodically
     * update the audio visualization capture.
     * The client application can implement this interface and register the listener with the
     * {@link #setDataCaptureListener(OnDataCaptureListener, int, boolean, boolean)} method.
     */
    public interface OnDataCaptureListener  {
        /**
         * Method called when a new waveform capture is available.
         * @param visualizer Visualizer object on which the listener is registered.
         * @param waveform array of bytes containing the waveform representation.
         * @param samplingRate sampling rate of the audio visualized.
         */
        void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate);

        /**
         * Method called when a new frequency capture is available.
         * @param visualizer Visualizer object on which the listener is registered.
         * @param fft array of bytes containing the frequency representation.
         * @param samplingRate sampling rate of the audio visualized.
         */
        void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate);
    }

    /**
     * Registers an OnDataCaptureListener interface and specifies the rate at which the capture
     * should be updated as well as the type of capture requested.
     * <p>Call this method with a null listener to stop receiving the capture updates.
     * @param listener OnDataCaptureListener registered
     * @param rate rate in milliHertz at which the capture should be updated
     * @param waveform true if a waveform capture is requested: the onWaveFormDataCapture()
     * method will be called on the OnDataCaptureListener interface.
     * @param fft true if a frequency capture is requested: the onFftDataCapture() method will be
     * called on the OnDataCaptureListener interface.
     * @return {@link #SUCCESS} in case of success,
     * {@link #ERROR_NO_INIT} or {@link #ERROR_BAD_VALUE} in case of failure.
     */
    public int setDataCaptureListener(OnDataCaptureListener listener,
            int rate, boolean waveform, boolean fft) {
        synchronized (mListenerLock) {
            mCaptureListener = listener;
        }
        if (listener == null) {
            // make sure capture callback is stopped in native code
            waveform = false;
            fft = false;
        }
        int status = native_setPeriodicCapture(rate, waveform, fft);
        if (status == SUCCESS) {
            if ((listener != null) && (mNativeEventHandler == null)) {
                Looper looper;
                if ((looper = Looper.myLooper()) != null) {
                    mNativeEventHandler = new NativeEventHandler(this, looper);
                } else if ((looper = Looper.getMainLooper()) != null) {
                    mNativeEventHandler = new NativeEventHandler(this, looper);
                } else {
                    mNativeEventHandler = null;
                    status = ERROR_NO_INIT;
                }
            }
        }
        return status;
    }

    /**
     * Helper class to handle the forwarding of native events to the appropriate listeners
     */
    private class NativeEventHandler extends Handler
    {
        private Visualizer mVisualizer;

        public NativeEventHandler(Visualizer v, Looper looper) {
            super(looper);
            mVisualizer = v;
        }

        @Override
        public void handleMessage(Message msg) {
            if (mVisualizer == null) {
                return;
            }
            OnDataCaptureListener l = null;
            synchronized (mListenerLock) {
                l = mVisualizer.mCaptureListener;
            }

            if (l != null) {
                byte[] data = (byte[])msg.obj;
                int samplingRate = msg.arg1;
                switch(msg.what) {
                case NATIVE_EVENT_PCM_CAPTURE:
                    l.onWaveFormDataCapture(mVisualizer, data, samplingRate);
                    break;
                case NATIVE_EVENT_FFT_CAPTURE:
                    l.onFftDataCapture(mVisualizer, data, samplingRate);
                    break;
                default:
                    Log.e(TAG,"Unknown native event: "+msg.what);
                    break;
                }
            }
        }
    }

    //---------------------------------------------------------
    // Interface definitions
    //--------------------

    private static native final void native_init();

    private native final int native_setup(Object audioeffect_this,
                                          int audioSession,
                                          int[] id);

    private native final void native_finalize();

    private native final void native_release();

    private native final int native_setEnabled(boolean enabled);

    private native final boolean native_getEnabled();

    private native final int native_setCaptureSize(int size);

    private native final int native_getCaptureSize();

    private native final int native_getSamplingRate();

    private native final int native_getWaveForm(byte[] waveform);

    private native final int native_getFft(byte[] fft);

    private native final int native_setPeriodicCapture(int rate, boolean waveForm, boolean fft);

    //---------------------------------------------------------
    // Java methods called from the native side
    //--------------------
    @SuppressWarnings("unused")
    private static void postEventFromNative(Object effect_ref,
            int what, int arg1, int arg2, Object obj) {
        Visualizer visu = (Visualizer)((WeakReference)effect_ref).get();
        if (visu == null) {
            return;
        }

        if (visu.mNativeEventHandler != null) {
            Message m = visu.mNativeEventHandler.obtainMessage(what, arg1, arg2, obj);
            visu.mNativeEventHandler.sendMessage(m);
        }

    }

}

