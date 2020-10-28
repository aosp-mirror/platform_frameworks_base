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


import android.compat.annotation.UnsupportedAppUsage;
import android.content.res.AssetFileDescriptor;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.AndroidRuntimeException;
import android.util.Log;

import java.io.FileDescriptor;
import java.lang.ref.WeakReference;

/**
 * JetPlayer provides access to JET content playback and control.
 * 
 * <p>Please refer to the JET Creator User Manual for a presentation of the JET interactive
 * music concept and how to use the JetCreator tool to create content to be player by JetPlayer.
 * 
 * <p>Use of the JetPlayer class is based around the playback of a number of JET segments
 * sequentially added to a playback FIFO queue. The rendering of the MIDI content stored in each
 * segment can be dynamically affected by two mechanisms:
 * <ul>
 * <li>tracks in a segment can be muted or unmuted at any moment, individually or through
 *    a mask (to change the mute state of multiple tracks at once)</li>
 * <li>parts of tracks in a segment can be played at predefined points in the segment, in order
 *    to maintain synchronization with the other tracks in the segment. This is achieved through
 *    the notion of "clips", which can be triggered at any time, but that will play only at the
 *    right time, as authored in the corresponding JET file.</li>
 * </ul>
 * As a result of the rendering and playback of the JET segments, the user of the JetPlayer instance
 * can receive notifications from the JET engine relative to:
 * <ul>
 * <li>the playback state,</li>
 * <li>the number of segments left to play in the queue,</li>
 * <li>application controller events (CC80-83) to mark points in the MIDI segments.</li>
 * </ul>
 * Use {@link #getJetPlayer()} to construct a JetPlayer instance. JetPlayer is a singleton class.
 * </p>
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about how to use JetPlayer, read the
 * <a href="{@docRoot}guide/topics/media/jetplayer.html">JetPlayer</a> developer guide.</p></div>
 */
public class JetPlayer
{    
    //--------------------------------------------
    // Constants
    //------------------------
    /**
     * The maximum number of simultaneous tracks. Use {@link #getMaxTracks()} to
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
            AudioFormat.CHANNEL_OUT_STEREO; // NUM_OUTPUT_CHANNELS=2 in Android.mk

    
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
    
    static {
        System.loadLibrary("media_jni");
    }
    
    //--------------------------------
    // Used exclusively by native code
    //--------------------
    /** 
     * Accessed by native methods: provides access to C++ JetPlayer object 
     */
    @SuppressWarnings("unused")
    @UnsupportedAppUsage
    private long mNativePlayerInJavaObj;

    
    //--------------------------------------------
    // Constructor, finalize
    //------------------------
    /**
     * Factory method for the JetPlayer class.
     * @return the singleton JetPlayer instance
     */
    public static JetPlayer getJetPlayer() {
        if (singletonRef == null) {
            singletonRef = new JetPlayer();
        }
        return singletonRef;
    }
    
    /**
     * Cloning a JetPlayer instance is not supported. Calling clone() will generate an exception.
     */
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
                    // bytes to frame conversion:
                    // 1200 == minimum buffer size in frames on generation 1 hardware
                    Math.max(1200, buffSizeInBytes /
                            (AudioFormat.getBytesPerSample(AudioFormat.ENCODING_PCM_16BIT) *
                            2 /*channels*/)));
        }
    }
    
    
    protected void finalize() { 
        native_finalize(); 
    }
    
    
    /**
     * Stops the current JET playback, and releases all associated native resources.
     * The object can no longer be used and the reference should be set to null
     * after a call to release().
     */
    public void release() {
        native_release();
        singletonRef = null;
    }
    
    
    //--------------------------------------------
    // Getters
    //------------------------
    /**
     * Returns the maximum number of simultaneous MIDI tracks supported by JetPlayer
     */
    public static int getMaxTracks() {
        return JetPlayer.MAXTRACKS;
    }
    
    
    //--------------------------------------------
    // Jet functionality
    //------------------------
    /**
     * Loads a .jet file from a given path.
     * @param path the path to the .jet file, for instance "/sdcard/mygame/music.jet".
     * @return true if loading the .jet file was successful, false if loading failed.
     */
    public boolean loadJetFile(String path) {
        return native_loadJetFromFile(path);
    }
    
    
    /**
     * Loads a .jet file from an asset file descriptor.
     * @param afd the asset file descriptor.
     * @return true if loading the .jet file was successful, false if loading failed.
     */
    public boolean loadJetFile(AssetFileDescriptor afd) {
        long len = afd.getLength();
        if (len < 0) {
            throw new AndroidRuntimeException("no length for fd");
        }
        return native_loadJetFromFileD(
                afd.getFileDescriptor(), afd.getStartOffset(), len);
    }
    
    /**
     * Closes the resource containing the JET content.
     * @return true if successfully closed, false otherwise.
     */
    public boolean closeJetFile() {
        return native_closeJetFile();
    }
    
    
    /**
     * Starts playing the JET segment queue.
     * @return true if rendering and playback is successfully started, false otherwise.
     */
    public boolean play() {
        return native_playJet();
    }
    
    
    /**
     * Pauses the playback of the JET segment queue.
     * @return true if rendering and playback is successfully paused, false otherwise.
     */
    public boolean pause() {
        return native_pauseJet();
    }
    
    
    /**
     * Queues the specified segment in the JET queue.
     * @param segmentNum the identifier of the segment.
     * @param libNum the index of the sound bank associated with the segment. Use -1 to indicate
     *    that no sound bank (DLS file) is associated with this segment, in which case JET will use
     *    the General MIDI library.
     * @param repeatCount the number of times the segment will be repeated. 0 means the segment will
     *    only play once. -1 means the segment will repeat indefinitely.
     * @param transpose the amount of pitch transposition. Set to 0 for normal playback. 
     *    Range is -12 to +12.
     * @param muteFlags a bitmask to specify which MIDI tracks will be muted during playback. Bit 0
     *    affects track 0, bit 1 affects track 1 etc.
     * @param userID a value specified by the application that uniquely identifies the segment. 
     *    this value is received in the
     *    {@link OnJetEventListener#onJetUserIdUpdate(JetPlayer, int, int)} event listener method.
     *    Normally, the application will keep a byte value that is incremented each time a new
     *    segment is queued up. This can be used to look up any special characteristics of that
     *    track including trigger clips and mute flags.
     * @return true if the segment was successfully queued, false if the queue is full or if the
     *    parameters are invalid.
     */
    public boolean queueJetSegment(int segmentNum, int libNum, int repeatCount,
        int transpose, int muteFlags, byte userID) {
        return native_queueJetSegment(segmentNum, libNum, repeatCount, 
                transpose, muteFlags, userID);
    }
    
    
    /**
     * Queues the specified segment in the JET queue.
     * @param segmentNum the identifier of the segment.
     * @param libNum the index of the soundbank associated with the segment. Use -1 to indicate that
     *    no sound bank (DLS file) is associated with this segment, in which case JET will use
     *    the General MIDI library.
     * @param repeatCount the number of times the segment will be repeated. 0 means the segment will
     *    only play once. -1 means the segment will repeat indefinitely.
     * @param transpose the amount of pitch transposition. Set to 0 for normal playback. 
     *    Range is -12 to +12.
     * @param muteArray an array of booleans to specify which MIDI tracks will be muted during
     *    playback. The value at index 0 affects track 0, value at index 1 affects track 1 etc. 
     *    The length of the array must be {@link #getMaxTracks()} for the call to succeed.
     * @param userID a value specified by the application that uniquely identifies the segment. 
     *    this value is received in the
     *    {@link OnJetEventListener#onJetUserIdUpdate(JetPlayer, int, int)} event listener method.
     *    Normally, the application will keep a byte value that is incremented each time a new
     *    segment is queued up. This can be used to look up any special characteristics of that
     *    track including trigger clips and mute flags.
     * @return true if the segment was successfully queued, false if the queue is full or if the
     *    parameters are invalid.
     */
    public boolean queueJetSegmentMuteArray(int segmentNum, int libNum, int repeatCount,
            int transpose, boolean[] muteArray, byte userID) {
        if (muteArray.length != JetPlayer.getMaxTracks()) {
            return false;
        }
        return native_queueJetSegmentMuteArray(segmentNum, libNum, repeatCount,
                transpose, muteArray, userID);
    }
    
    
    /**
     * Modifies the mute flags.
     * @param muteFlags a bitmask to specify which MIDI tracks are muted. Bit 0 affects track 0,
     *    bit 1 affects track 1 etc.
     * @param sync if false, the new mute flags will be applied as soon as possible by the JET
     *    render and playback engine. If true, the mute flags will be updated at the start of the
     *    next segment. If the segment is repeated, the flags will take effect the next time 
     *    segment is repeated.
     * @return true if the mute flags were successfully updated, false otherwise.
     */
    public boolean setMuteFlags(int muteFlags, boolean sync) {
        return native_setMuteFlags(muteFlags, sync);
    }
    
    
    /**
     * Modifies the mute flags for the current active segment.
     * @param muteArray an array of booleans to specify which MIDI tracks are muted. The value at
     *    index 0 affects track 0, value at index 1 affects track 1 etc. 
     *    The length of the array must be {@link #getMaxTracks()} for the call to succeed.
     * @param sync if false, the new mute flags will be applied as soon as possible by the JET
     *    render and playback engine. If true, the mute flags will be updated at the start of the
     *    next segment. If the segment is repeated, the flags will take effect the next time 
     *    segment is repeated.
     * @return true if the mute flags were successfully updated, false otherwise.
     */
    public boolean setMuteArray(boolean[] muteArray, boolean sync) {
        if(muteArray.length != JetPlayer.getMaxTracks())
            return false;
        return native_setMuteArray(muteArray, sync);
    }
    
    
    /**
     * Mutes or unmutes a single track.
     * @param trackId the index of the track to mute.
     * @param muteFlag set to true to mute, false to unmute.
     * @param sync if false, the new mute flags will be applied as soon as possible by the JET
     *    render and playback engine. If true, the mute flag will be updated at the start of the
     *    next segment. If the segment is repeated, the flag will take effect the next time 
     *    segment is repeated.
     * @return true if the mute flag was successfully updated, false otherwise.
     */
    public boolean setMuteFlag(int trackId, boolean muteFlag, boolean sync) {
        return native_setMuteFlag(trackId, muteFlag, sync);
    }
    
    
    /**
     * Schedules the playback of a clip.
     * This will automatically update the mute flags in sync with the JET Clip Marker (controller 
     * 103). The parameter clipID must be in the range of 0-63. After the call to triggerClip, when
     * JET next encounters a controller event 103 with bits 0-5 of the value equal to clipID and 
     * bit 6 set to 1, it will automatically unmute the track containing the controller event.
     * When JET encounters the complementary controller event 103 with bits 0-5 of the value equal
     * to clipID and bit 6 set to 0, it will mute the track again.
     * @param clipId the identifier of the clip to trigger.
     * @return true if the clip was successfully triggered, false otherwise.
     */
    public boolean triggerClip(int clipId) {
        return native_triggerClip(clipId);
    }
    
    
    /**
     * Empties the segment queue, and clears all clips that are scheduled for playback.
     * @return true if the queue was successfully cleared, false otherwise.
     */
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
    /**
     * Sets the listener JetPlayer notifies when a JET event is generated by the rendering and
     * playback engine.
     * Notifications will be received in the same thread as the one in which the JetPlayer
     * instance was created.
     * @param listener
     */
    public void setEventListener(OnJetEventListener listener) {
        setEventListener(listener, null);
    }
    
    /**
     * Sets the listener JetPlayer notifies when a JET event is generated by the rendering and
     * playback engine.
     * Use this method to receive JET events in the Handler associated with another
     * thread than the one in which you created the JetPlayer instance.
     * @param listener
     * @param handler the Handler that will receive the event notification messages.
     */
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
         * Callback for when JET's currently playing segment's userID is updated.
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
         * @param paused indicates whether JET is paused (1) or not (0)
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
    @UnsupportedAppUsage
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
