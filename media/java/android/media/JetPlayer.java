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


import java.lang.ref.WeakReference;
import java.lang.CloneNotSupportedException;

import android.os.Looper;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

/**
 * JetPlayer provides access to JET content playback and control.
 * <p>
 * Use <code>JetPlayer.getJetPlayer()</code> to get an instance of this class.
 * There can only be one instance of this class at any one time.
 * 
 * @hide
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
    protected static int MAXTRACKS = 32;
        
    // These constants are to be kept in sync with the ones in include/media/JetPlayer.h
    protected static final int JET_USERID_UPDATE           = 1;
    protected static final int JET_NUMQUEUEDSEGMENT_UPDATE = 2;
    protected static final int JET_PAUSE_UPDATE            = 3;

    
    //--------------------------------------------
    // Member variables
    //------------------------
    private EventHandler            mNativeEventHandler = null;
    
    /**
     * Lock to protect event listener updates against event notifications
     */
    protected final Object mStatusListenerLock = new Object();
    
    protected JetStatusUpdateListener mJetStatusUpdateListener = null;
    
    protected static JetPlayer singletonRef;
    
    
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
        if (singletonRef == null)
            singletonRef = new JetPlayer();
        return singletonRef;
    }
    
    
    public Object clone() throws CloneNotSupportedException {
        // JetPlayer is a singleton class,
        // so you can't clone a JetPlayer instance
        throw new CloneNotSupportedException();    
    }
    

    private JetPlayer() {
                
        native_setup(new WeakReference<JetPlayer>(this),
                JetPlayer.getMaxTracks(), 
                1200); //TODO parametrize this (?)
    }
    
    
    protected void finalize() { 
        native_finalize(); 
    }
    
    
    public void release() {
        native_release();
    }
    
    
    private void createNativeEventHandler() {
        Looper looper;
        if ((looper = Looper.myLooper()) != null) {
            mNativeEventHandler = new EventHandler(this, looper);
        } else if ((looper = Looper.getMainLooper()) != null) {
            mNativeEventHandler = new EventHandler(this, looper);
        } else {
            mNativeEventHandler = null;
        }
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
    public boolean openJetFile(String path) {
        return native_openJetFile(path);
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
    
    
     
    //---------------------------------------------------------
    // Internal class to handle events posted from native code
    //------------------------
    private class EventHandler extends Handler
    {
        private JetPlayer mJet;

        public EventHandler(JetPlayer jet, Looper looper) {
            super(looper);
            mJet = jet;
        }

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
            case JET_USERID_UPDATE:
                synchronized (mStatusListenerLock) {
                    if (mJetStatusUpdateListener != null) {
                        mJetStatusUpdateListener.onJetUserIdUpdate(msg.arg1, msg.arg2);
                    }
                }
                return;
            case JET_NUMQUEUEDSEGMENT_UPDATE:
                synchronized (mStatusListenerLock) {
                    if (mJetStatusUpdateListener != null) {
                        mJetStatusUpdateListener.onJetNumQueuedSegmentUpdate(msg.arg1);
                    }
                }
                return;
            case JET_PAUSE_UPDATE:
                synchronized (mStatusListenerLock) {
                    if (mJetStatusUpdateListener != null)
                        mJetStatusUpdateListener.onJetPauseUpdate(msg.arg1);
                }
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
    public void setStatusUpdateListener(JetStatusUpdateListener listener) {
        synchronized(mStatusListenerLock) {
            mJetStatusUpdateListener = listener;
        }
        
        if ((listener != null) && (mNativeEventHandler == null)) {
            createNativeEventHandler();
        }
    }
    
    /**
     * Handles the notification when the JET segment userID is updated.
     */
    public interface JetStatusUpdateListener {
        /**
         * Callback for when JET's currently playing segment userID is updated.
         * 
         * @param userId the ID of the currently playing segment
         * @param repeatCount the repetition count for the segment (0 means it plays once)
         */
        void onJetUserIdUpdate(int userId, int repeatCount);
        
        /**
         * Callback for when JET's number of queued segments is updated.
         * 
         * @param nbSegments the number of segments in the JET queue
         */
        void onJetNumQueuedSegmentUpdate(int nbSegments);
        
        /**
         * Callback for when JET pause state is updated.
         * 
         * @param paused indicates whether JET is paused or not
         */
        void onJetPauseUpdate(int paused);
    };
    
    
    //--------------------------------------------
    // Native methods
    //------------------------
    private native final boolean native_setup(Object Jet_this,
                int maxTracks, int trackBufferSize);
    private native final void    native_finalize();
    private native final void    native_release();
    private native final boolean native_openJetFile(String pathToJetFile);
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
    
    //---------------------------------------------------------
    // Called exclusively by native code
    //--------------------
    @SuppressWarnings("unused")
    private static void postEventFromNative(Object jetplayer_ref,
            int what, int arg1, int arg2) {
        
        JetPlayer jet = (JetPlayer)((WeakReference)jetplayer_ref).get();

        if( (jet!=null) && (jet.mNativeEventHandler!=null) ){
            Message m = jet.mNativeEventHandler.obtainMessage(what, arg1, arg2, null);
            jet.mNativeEventHandler.sendMessage(m);
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
