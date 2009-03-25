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

package android.media;


import java.io.FileDescriptor;
import java.lang.ref.WeakReference;
import java.lang.CloneNotSupportedException;

import android.content.res.AssetFileDescriptor;
import android.os.Looper;
import android.os.Handler;
import android.os.Message;
import android.util.AndroidRuntimeException;
import android.util.Log;

/**
 * JetPlayer provides access to JET content playback and control.
 * <p>
 * Use <code>JetPlayer.getJetPlayer()</code> to get an instance of this class.
 * 
 */
public class JetPlayer
{    
    //--------------------------------------------
    // Constants
    //------------------------
    /**
     * The maximum number of simultaneous tracks. Use __link #getMaxTracks()} to
     * access this value.
     */
    private static int MAXTRACKS = 32;
        
    // to keep in sync with the JetPlayer class constants
    // defined in frameworks/base/include/media/JetPlayer.h
    private static final int JET_EVENT                   = 1;
    private static final int JET_USERID_UPDATE           = 2;
    private static final int JET_NUMQUEUEDSEGMENT_UPDATE = 3;
    private static final int JET_PAUSE_UPDATE            = 4;
    
    // to keep in sync with external/sonivox/arm-wt-22k/lib_src/jet_data.h
    // Encoding of event information on 32 bits
    private static final int JET_EVENT_VAL_MASK    = 0x0000007f; // mask for value
    private static final int JET_EVENT_CTRL_MASK   = 0x00003f80; // mask for controller
    private static final int JET_EVENT_CHAN_MASK   = 0x0003c000; // mask for channel
    private static final int JET_EVENT_TRACK_MASK  = 0x00fc0000; // mask for track number
    private static final int JET_EVENT_SEG_MASK    = 0xff000000; // mask for segment ID
    private static final int JET_EVENT_CTRL_SHIFT  = 7;  // shift to get controller number to bit 0
    private static final int JET_EVENT_CHAN_SHIFT  = 14; // shift to get MIDI channel to bit 0
    private static final int JET_EVENT_TRACK_SHIFT = 18; // shift to get track ID to bit 0
    private static final int JET_EVENT_SEG_SHIFT   = 24; // shift to get segment ID to bit 0
    
    // to keep in sync with values used in external/sonivox/arm-wt-22k/Android.mk
    // Jet rendering audio parameters
    private static final int JET_OUTPUT_RATE = 22050; // _SAMPLE_RATE_22050 in Android.mk
    private static final int JET_OUTPUT_CHANNEL_CONFIG =
            AudioFormat.CHANNEL_CONFIGURATION_STEREO; // NUM_OUTPUT_CHANNELS=2 in Android.mk

    
    //--------------------------------------------
    // Member variables
    //------------------------
    /**
     * Handler for jet events and status updates coming from the native code
     */
    private NativeEventHandler mEventHandler = null;
    
    /**
     * Looper associated with the thread that creates the AudioTrack instance
     */
    private Looper mInitializationLooper = null;
    
    /**
     * Lock to protect the event listener updates against event notifications
     */
    private final Object mEventListenerLock = new Object();
    
    private OnJetEventListener mJetEventListener = null;
    
    private static JetPlayer singletonRef;
    
    
    //--------------------------------
    // Used exclusively by native code
    //--------------------
    /** 
     * Accessed by native methods: provides access to C++ JetPlayer object 
     */
    @SuppressWarnings("unused")
    private int mNativePlayerInJavaObj;

    
    //--------------------------------------------
    // Constructor, finalize
    //------------------------
    public static JetPlayer getJetPlayer() {
        if (singletonRef == null) {
            singletonRef = new JetPlayer();
        }
        return singletonRef;
    }
    
    
    public Object clone() throws CloneNotSupportedException {
        // JetPlayer is a singleton class,
        // so you can't clone a JetPlayer instance
        throw new CloneNotSupportedException();    
    }
    

    private JetPlayer() {

        // remember which looper is associated with the JetPlayer instanciation
        if ((mInitializationLooper = Looper.myLooper()) == null) {
            mInitializationLooper = Looper.getMainLooper();
        }
        
        int buffSizeInBytes = AudioTrack.getMinBufferSize(JET_OUTPUT_RATE,
                JET_OUTPUT_CHANNEL_CONFIG, AudioFormat.ENCODING_PCM_16BIT);
        
        if ((buffSizeInBytes != AudioTrack.ERROR) 
                && (buffSizeInBytes != AudioTrack.ERROR_BAD_VALUE)) {
                            
            native_setup(new WeakReference<JetPlayer>(this),
                    JetPlayer.getMaxTracks(),
                    // bytes to frame conversion: sample format is ENCODING_PCM_16BIT, 2 channels
                    // 1200 == minimum buffer size in frames on generation 1 hardware
                    Math.max(1200, buffSizeInBytes / 4));
        }
    }
    
    
    protected void finalize() { 
        native_finalize(); 
    }
    
    
    public void release() {
        native_release();
    }
    
    
    //--------------------------------------------
    // Getters
    //------------------------
    /**
     * Returns the maximum number of simultaneous MIDI tracks supported by the Jet player
     */
    public static int getMaxTracks() {
        return JetPlayer.MAXTRACKS;
    }
    
    
    //--------------------------------------------
    // Jet functionality
    //------------------------
    public boolean loadJetFile(String path) {
        return native_loadJetFromFile(path);
    }
    
    
    public boolean loadJetFile(AssetFileDescriptor afd) {
        long len = afd.getLength();
        if (len < 0) {
            throw new AndroidRuntimeException("no length for fd");
        }
        return native_loadJetFromFileD(
                afd.getFileDescriptor(), afd.getStartOffset(), len);
    }
    
    
    public boolean closeJetFile() {
        return native_closeJetFile();
    }
    
    
    public boolean play() {
        return native_playJet();
    }
    
    
    public boolean pause() {
        return native_pauseJet();
    }
    
    
    public boolean queueJetSegment(int segmentNum, int libNum, int repeatCount,
        int transpose, int muteFlags, byte userID) {
        return native_queueJetSegment(segmentNum, libNum, repeatCount, 
                transpose, muteFlags, userID);
    }
    
    
    public boolean queueJetSegmentMuteArray(int segmentNum, int libNum, int repeatCount,
            int transpose, boolean[] muteArray, byte userID) {
        if (muteArray.length != JetPlayer.getMaxTracks()) {
            return false;
        }
        return native_queueJetSegmentMuteArray(segmentNum, libNum, repeatCount,
                transpose, muteArray, userID);
    }
    
    
    public boolean setMuteFlags(int muteFlags, boolean sync) {
        return native_setMuteFlags(muteFlags, sync);
    }
    
    
    public boolean setMuteArray(boolean[] muteArray, boolean sync) {
        if(muteArray.length != JetPlayer.getMaxTracks())
            return false;
        return native_setMuteArray(muteArray, sync);
    }
    
    
    public boolean setMuteFlag(int trackId, boolean muteFlag, boolean sync) {
        return native_setMuteFlag(trackId, muteFlag, sync);
    }
    
    
    public boolean triggerClip(int clipId) {
        return native_triggerClip(clipId);
    }
    
    
    public boolean clearQueue() {
        return native_clearQueue();
    }
    
     
    //---------------------------------------------------------
    // Internal class to handle events posted from native code
    //------------------------
    private class NativeEventHandler extends Handler
    {
        private JetPlayer mJet;

        public NativeEventHandler(JetPlayer jet, Looper looper) {
            super(looper);
            mJet = jet;
        }

        @Override
        public void handleMessage(Message msg) {
            OnJetEventListener listener = null;
            synchronized (mEventListenerLock) {
                listener = mJet.mJetEventListener;
            }
            switch(msg.what) {
            case JET_EVENT:
                if (listener != null) {
                    // call the appropriate listener after decoding the event parameters
                    // encoded in msg.arg1
                    mJetEventListener.onJetEvent(
                            mJet,
                            (short)((msg.arg1 & JET_EVENT_SEG_MASK)   >> JET_EVENT_SEG_SHIFT),
                            (byte) ((msg.arg1 & JET_EVENT_TRACK_MASK) >> JET_EVENT_TRACK_SHIFT),
                            // JETCreator channel numbers start at 1, but the index starts at 0
                            // in the .jet files
                            (byte)(((msg.arg1 & JET_EVENT_CHAN_MASK)  >> JET_EVENT_CHAN_SHIFT) + 1),
                            (byte) ((msg.arg1 & JET_EVENT_CTRL_MASK)  >> JET_EVENT_CTRL_SHIFT),
                            (byte)  (msg.arg1 & JET_EVENT_VAL_MASK) );
                }
                return;
            case JET_USERID_UPDATE:
                if (listener != null) {
                    listener.onJetUserIdUpdate(mJet, msg.arg1, msg.arg2);
                }
                return;
            case JET_NUMQUEUEDSEGMENT_UPDATE:
                if (listener != null) {
                    listener.onJetNumQueuedSegmentUpdate(mJet, msg.arg1);
                }
                return;
            case JET_PAUSE_UPDATE:
                if (listener != null)
                    listener.onJetPauseUpdate(mJet, msg.arg1);
                return;

            default:
                loge("Unknown message type " + msg.what);
                return;
            }
        }
    }
    
    
    //--------------------------------------------
    // Jet event listener
    //------------------------
    public void setEventListener(OnJetEventListener listener) {
        setEventListener(listener, null);
    }
    
    public void setEventListener(OnJetEventListener listener, Handler handler) {
        synchronized(mEventListenerLock) {
            
            mJetEventListener = listener;
            
            if (listener != null) {
                if (handler != null) {
                    mEventHandler = new NativeEventHandler(this, handler.getLooper());
                } else {
                    // no given handler, use the looper the AudioTrack was created in
                    mEventHandler = new NativeEventHandler(this, mInitializationLooper);
                }
            } else {
                mEventHandler = null;
            }
            
        }
    }
    
    
    /**
     * Handles the notification when the JET engine generates an event.
     */
    public interface OnJetEventListener {
        /**
         * Callback for when the JET engine generates a new event.
         * 
         * @param player the JET player the event is coming from
         * @param segment 8 bit unsigned value
         * @param track 6 bit unsigned value
         * @param channel 4 bit unsigned value
         * @param controller 7 bit unsigned value
         * @param value 7 bit unsigned value
         */
        void onJetEvent(JetPlayer player,
                short segment, byte track, byte channel, byte controller, byte value);
        /**
         * Callback for when JET's currently playing segment userID is updated.
         * 
         * @param player the JET player the status update is coming from
         * @param userId the ID of the currently playing segment
         * @param repeatCount the repetition count for the segment (0 means it plays once)
         */
        void onJetUserIdUpdate(JetPlayer player, int userId, int repeatCount);
        
        /**
         * Callback for when JET's number of queued segments is updated.
         * 
         * @param player the JET player the status update is coming from
         * @param nbSegments the number of segments in the JET queue
         */
        void onJetNumQueuedSegmentUpdate(JetPlayer player, int nbSegments);
        
        /**
         * Callback for when JET pause state is updated.
         * 
         * @param player the JET player the status update is coming from
         * @param paused indicates whether JET is paused or not
         */
        void onJetPauseUpdate(JetPlayer player, int paused);
    }
    
    
    //--------------------------------------------
    // Native methods
    //------------------------
    private native final boolean native_setup(Object Jet_this,
                int maxTracks, int trackBufferSize);
    private native final void    native_finalize();
    private native final void    native_release();
    private native final boolean native_loadJetFromFile(String pathToJetFile);
    private native final boolean native_loadJetFromFileD(FileDescriptor fd, long offset, long len);
    private native final boolean native_closeJetFile();
    private native final boolean native_playJet();
    private native final boolean native_pauseJet();
    private native final boolean native_queueJetSegment(int segmentNum, int libNum,
            int repeatCount, int transpose, int muteFlags, byte userID);
    private native final boolean native_queueJetSegmentMuteArray(int segmentNum, int libNum, 
            int repeatCount, int transpose, boolean[] muteArray, byte userID);
    private native final boolean native_setMuteFlags(int muteFlags, boolean sync);
    private native final boolean native_setMuteArray(boolean[]muteArray, boolean sync);
    private native final boolean native_setMuteFlag(int trackId, boolean muteFlag, boolean sync);
    private native final boolean native_triggerClip(int clipId);
    private native final boolean native_clearQueue();
    
    //---------------------------------------------------------
    // Called exclusively by native code
    //--------------------
    @SuppressWarnings("unused")
    private static void postEventFromNative(Object jetplayer_ref,
            int what, int arg1, int arg2) {
        //logd("Event posted from the native side: event="+ what + " args="+ arg1+" "+arg2);
        JetPlayer jet = (JetPlayer)((WeakReference)jetplayer_ref).get();

        if ((jet != null) && (jet.mEventHandler != null)) {
            Message m = 
                jet.mEventHandler.obtainMessage(what, arg1, arg2, null);
            jet.mEventHandler.sendMessage(m);
        }
        
    }
    
 
    //---------------------------------------------------------
    // Utils
    //--------------------
    private final static String TAG = "JetPlayer-J";
    
    private static void logd(String msg) {
        Log.d(TAG, "[ android.media.JetPlayer ] " + msg);
    }
    
    private static void loge(String msg) {
        Log.e(TAG, "[ android.media.JetPlayer ] " + msg);
    }
 
}
