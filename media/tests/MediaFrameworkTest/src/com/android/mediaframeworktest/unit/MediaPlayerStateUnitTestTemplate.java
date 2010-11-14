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

package com.android.mediaframeworktest.unit;

import android.util.Log;
import android.os.Looper;
import android.os.Handler;
import android.os.Message;
import android.media.MediaPlayer;
import android.test.AndroidTestCase;
import com.android.mediaframeworktest.MediaNames;

/**
 * A template class for running a method under test in all possible
 * states of a MediaPlayer object.
 * 
 * @see com.android.mediaframeworktest.unit.MediaPlayerSeekToStateUnitTest
 * for an example of using this class.
 * 
 * A typical concrete unit test class would implement the 
 * MediaPlayerMethodUnderTest interface and have a reference to an object of
 * this class. Then it calls runTestOnMethod() to actually perform the unit
 * tests.
 * 
 */
class MediaPlayerStateUnitTestTemplate extends AndroidTestCase {
    private static final String TEST_PATH = MediaNames.TEST_PATH_1;
    private static final String TAG = "MediaPlayerStateUnitTestTemplate";
    private static final int SEEK_TO_END  = 135110;  // Milliseconds.
    private static int WAIT_FOR_COMMAND_TO_COMPLETE = 1000;  // Milliseconds.
    
    private MediaPlayerStateErrors mStateErrors = new MediaPlayerStateErrors();
    private MediaPlayer mMediaPlayer = null;
    private boolean mInitialized = false;
    private boolean mOnCompletionHasBeenCalled = false;
    private MediaPlayerStateErrors.MediaPlayerState mMediaPlayerState = null;
    private Looper mLooper = null;
    private final Object lock = new Object();
    private MediaPlayerMethodUnderTest mMethodUnderTest = null;
    
    // An Handler object is absolutely necessary for receiving callback 
    // messages from MediaPlayer objects.
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            /*
            switch(msg.what) {
                case MediaPlayerStateErrors.MEDIA_PLAYER_ERROR:
                    Log.v(TAG, "handleMessage: received MEDIA_PLAYER_ERROR message");
                    break;
                default:
                    Log.v(TAG, "handleMessage: received unknown message");
                break;
            }
            */
        }
    };
    
    /**
     * Runs the given method under test in all possible states of a MediaPlayer
     * object.
     * 
     * @param testMethod the method under test.
     */
    public void runTestOnMethod(MediaPlayerMethodUnderTest testMethod) {
        mMethodUnderTest = testMethod;
        if (mMethodUnderTest != null) {  // Method under test has been set?
            initializeMessageLooper();
            synchronized(lock) {
                try {
                    lock.wait(WAIT_FOR_COMMAND_TO_COMPLETE);
                } catch(Exception e) {
                    Log.v(TAG, "runTestOnMethod: wait was interrupted.");
                }
            }
            assertTrue(mInitialized);  // mMediaPlayer has been initialized?
            checkMethodUnderTestInAllPossibleStates();
            terminateMessageLooper();   // Release message looper thread.
            assertTrue(mOnCompletionHasBeenCalled);
            mMethodUnderTest.checkStateErrors(mStateErrors);
            cleanUp();
        }
    }
    
    /*
     * Initializes the message looper so that the MediaPlayer object can 
     * receive the callback messages.
     */
    private void initializeMessageLooper() {
        new Thread() {
            @Override
            public void run() {
                // Set up a looper to be used by mMediaPlayer.
                Looper.prepare();

                // Save the looper so that we can terminate this thread 
                // after we are done with it.
                mLooper = Looper.myLooper();
                
                mMediaPlayer = new MediaPlayer();                
                mMediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    public boolean onError(MediaPlayer player, int what, int extra) {
                        Log.v(TAG, "onError has been called.");
                        synchronized(lock) {
                            Log.v(TAG, "notify lock.");
                            setStateError(mMediaPlayerState, true);
                            if (mMediaPlayerState != MediaPlayerStateErrors.MediaPlayerState.ERROR) {
                                notifyStateError();
                            }
                            lock.notify();
                        }
                        return true;
                    }
                });
                mMediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    public void onCompletion(MediaPlayer player) {
                        Log.v(TAG, "onCompletion has been called.");
                        synchronized(lock) {
                            if (mMediaPlayerState == MediaPlayerStateErrors.MediaPlayerState.PLAYBACK_COMPLETED) {
                                mOnCompletionHasBeenCalled = true;
                            }
                            lock.notify();
                        }
                    }
                });
                synchronized(lock) {
                    mInitialized = true;
                    lock.notify();
                }
                Looper.loop();  // Blocks forever until Looper.quit() is called.
                Log.v(TAG, "initializeMessageLooper: quit.");
            }
        }.start();
    }
    
    /*
     * Calls method under test in the given state of the MediaPlayer object.
     * 
     * @param state the MediaPlayer state in which the method under test is called.
     */
    private void callMediaPlayerMethodUnderTestInState(MediaPlayerStateErrors.MediaPlayerState state) {
        Log.v(TAG, "call " + mMethodUnderTest + ": started in state " + state);
        setMediaPlayerToState(state);
        mMethodUnderTest.invokeMethodUnderTest(mMediaPlayer);
        synchronized(lock) {
            try {
                lock.wait(WAIT_FOR_COMMAND_TO_COMPLETE);
           } catch(Exception e) {
               Log.v(TAG, "callMediaPlayerMethodUnderTestInState: wait is interrupted in state " + state);
           }
        }
        Log.v(TAG, "call " + mMethodUnderTest + ": ended in state " + state);
    }

    /*
     * The following setMediaPlayerToXXXStateXXX methods sets the MediaPlayer
     * object to the corresponding state, given the assumption that reset()
     * always resets the MediaPlayer object to Idle (after reset) state. 
     */
    private void setMediaPlayerToIdleStateAfterReset() {
        try {
            mMediaPlayer.reset();
            mMediaPlayer.setDataSource(TEST_PATH);
            mMediaPlayer.prepare();
            mMediaPlayer.reset();
        } catch(Exception e) {
            Log.v(TAG, "setMediaPlayerToIdleStateAfterReset: Exception " + e.getClass().getName() + " was thrown.");
            assertTrue(false);
        }
    }
    
    private void setMediaPlayerToInitializedState() {
        try {
            mMediaPlayer.reset();
            mMediaPlayer.setDataSource(TEST_PATH);
        } catch(Exception e) {
            Log.v(TAG, "setMediaPlayerToInitializedState: Exception " + e.getClass().getName() + " was thrown.");
            assertTrue(false);
        }
    }
    
    private void setMediaPlayerToPreparedState() {
        try {
            mMediaPlayer.reset();
            mMediaPlayer.setDataSource(TEST_PATH);
            mMediaPlayer.prepare();
        } catch(Exception e) {
            Log.v(TAG, "setMediaPlayerToPreparedState: Exception " + e.getClass().getName() + " was thrown.");
            assertTrue(false);
        }
    }
    
    private void setMediaPlayerToPreparedStateAfterStop() {
        try {
            mMediaPlayer.reset();
            mMediaPlayer.setDataSource(TEST_PATH);
            mMediaPlayer.prepare();
            mMediaPlayer.start();
            mMediaPlayer.stop();
            mMediaPlayer.prepare();
        } catch(Exception e) {
            Log.v(TAG, "setMediaPlayerToPreparedStateAfterStop: Exception " + e.getClass().getName() + " was thrown.");
            assertTrue(false);
        }
    }
    
    private void setMediaPlayerToStartedState() {
        try {
            mMediaPlayer.reset();
            mMediaPlayer.setDataSource(TEST_PATH);
            mMediaPlayer.prepare();
            mMediaPlayer.start();
        } catch(Exception e) {
            Log.v(TAG, "setMediaPlayerToStartedState: Exception " + e.getClass().getName() + " was thrown.");
            assertTrue(false);
        }
    }
    
    private void setMediaPlayerToStartedStateAfterPause() {
        try {
            mMediaPlayer.reset();
            mMediaPlayer.setDataSource(TEST_PATH);
            mMediaPlayer.prepare();
            mMediaPlayer.start();
            mMediaPlayer.pause();

            // pause() is an asynchronous call and returns immediately, but 
            // PV player engine may take quite a while to actually set the 
            // player state to Paused; if we call start() right after pause() 
            // without waiting, start() may fail.
            try {
                Thread.sleep(MediaNames.PAUSE_WAIT_TIME);
            } catch(Exception ie) {
                Log.v(TAG, "sleep was interrupted and terminated prematurely");
            }

            mMediaPlayer.start();
        } catch(Exception e) {
            Log.v(TAG, "setMediaPlayerToStartedStateAfterPause: Exception " + e.getClass().getName() + " was thrown.");
            assertTrue(false);
        }
    }
    
    private void setMediaPlayerToPausedState() {
        try {
            mMediaPlayer.reset();
            mMediaPlayer.setDataSource(TEST_PATH);
            mMediaPlayer.prepare();
            mMediaPlayer.start();
            mMediaPlayer.pause();
        } catch(Exception e) {
            Log.v(TAG, "setMediaPlayerToPausedState: Exception " + e.getClass().getName() + " was thrown.");
            assertTrue(false);
        }
    }
    
    private void setMediaPlayerToStoppedState() {
        try {
            mMediaPlayer.reset();
            mMediaPlayer.setDataSource(TEST_PATH);
            mMediaPlayer.prepare();
            mMediaPlayer.start();
            mMediaPlayer.stop();
        } catch(Exception e) {
            Log.v(TAG, "setMediaPlayerToStoppedState: Exception " + e.getClass().getName() + " was thrown.");
            assertTrue(false);
        }
    }
    
    private void setMediaPlayerToPlaybackCompletedState() {
        try {
            mMediaPlayer.reset();
            mMediaPlayer.setDataSource(TEST_PATH);
            mMediaPlayer.prepare();
            mMediaPlayer.seekTo(SEEK_TO_END);
            mMediaPlayer.start();
            synchronized(lock) {
                try {
                    lock.wait(WAIT_FOR_COMMAND_TO_COMPLETE);
                } catch(Exception e) {
                    Log.v(TAG, "setMediaPlayerToPlaybackCompletedState: wait was interrupted.");
                }
            }
        } catch(Exception e) {
            Log.v(TAG, "setMediaPlayerToPlaybackCompletedState: Exception " + e.getClass().getName() + " was thrown.");
            assertTrue(false);
        }
        Log.v(TAG, "setMediaPlayerToPlaybackCompletedState: done.");
    }
    
    /*
     * There are a lot of ways to force the MediaPlayer object to enter
     * the Error state. The impact (such as onError is called or not) highly 
     * depends on how the Error state is entered.
     */
    private void setMediaPlayerToErrorState() {
        try {
            mMediaPlayer.reset();
            mMediaPlayer.setDataSource(TEST_PATH);
            mMediaPlayer.start();
            synchronized(lock) {
                try {
                    lock.wait(WAIT_FOR_COMMAND_TO_COMPLETE);
                } catch(Exception e) {
                    Log.v(TAG, "setMediaPlayerToErrorState: wait was interrupted.");
                }
            }
        } catch(Exception e) {
            Log.v(TAG, "setMediaPlayerToErrorState: Exception " + e.getClass().getName() + " was thrown.");
            assertTrue(e instanceof IllegalStateException);
        }
        Log.v(TAG, "setMediaPlayerToErrorState: done.");
    }
    
    /*
     * Sets the state of the MediaPlayer object to the specified one.
     * 
     * @param state the state of the MediaPlayer object.
     */
    private void setMediaPlayerToState(MediaPlayerStateErrors.MediaPlayerState state) {
        mMediaPlayerState = state;
        switch(state) {
            case IDLE:
                // Does nothing.
                break;
            case IDLE_AFTER_RESET:
                setMediaPlayerToIdleStateAfterReset();
                break;
            case INITIALIZED:
                setMediaPlayerToInitializedState();
                break;
            case PREPARED:
                setMediaPlayerToPreparedState();
                break;
            case PREPARED_AFTER_STOP:
                setMediaPlayerToPreparedStateAfterStop();
                break;
            case STARTED:
                setMediaPlayerToStartedState();
                break;
            case STARTED_AFTER_PAUSE:
                setMediaPlayerToStartedStateAfterPause();
                break;
            case PAUSED:
                setMediaPlayerToPausedState();
                break;
            case STOPPED:
                setMediaPlayerToStoppedState();
                break;
            case PLAYBACK_COMPLETED:
                setMediaPlayerToPlaybackCompletedState();
                break;
            case ERROR:
                setMediaPlayerToErrorState();
                break;
        }
    }
    
    /*
     * Sets the error value of the corresponding state to the given error.
     * 
     * @param state the state of the MediaPlayer object.
     * @param error the value of the state error to be set.
     */
    private void setStateError(MediaPlayerStateErrors.MediaPlayerState state, boolean error) {
        switch(state) {
            case IDLE:
                mStateErrors.errorInIdleState = error;
                break;
            case IDLE_AFTER_RESET:
                mStateErrors.errorInIdleStateAfterReset = error;
                break;
            case INITIALIZED:
                mStateErrors.errorInInitializedState = error;
                break;
            case PREPARED:
                mStateErrors.errorInPreparedState = error;
                break;
            case PREPARED_AFTER_STOP:
                mStateErrors.errorInPreparedStateAfterStop = error;
                break;
            case STARTED:
                mStateErrors.errorInStartedState = error;
                break;
            case STARTED_AFTER_PAUSE:
                mStateErrors.errorInStartedStateAfterPause = error;
                break;
            case PAUSED:
                mStateErrors.errorInPausedState = error;
                break;
            case STOPPED:
                mStateErrors.errorInStoppedState = error;
                break;
            case PLAYBACK_COMPLETED:
                mStateErrors.errorInPlaybackCompletedState = error;
                break;
            case ERROR:
                mStateErrors.errorInErrorState = error;
                break;
        }
    }
    
    private void notifyStateError() {
        mHandler.sendMessage(mHandler.obtainMessage(MediaPlayerStateErrors.MEDIA_PLAYER_ERROR));
    }

    private void checkIdleState() {
        callMediaPlayerMethodUnderTestInState(MediaPlayerStateErrors.MediaPlayerState.IDLE);
    }
    
    private void checkIdleStateAfterReset() {
        callMediaPlayerMethodUnderTestInState(MediaPlayerStateErrors.MediaPlayerState.IDLE_AFTER_RESET);
    }
    
    private void checkInitializedState() {
        callMediaPlayerMethodUnderTestInState(MediaPlayerStateErrors.MediaPlayerState.INITIALIZED);
    }
    
    private void checkPreparedState() {
        callMediaPlayerMethodUnderTestInState(MediaPlayerStateErrors.MediaPlayerState.PREPARED);
    }
    
    private void checkPreparedStateAfterStop() {
        callMediaPlayerMethodUnderTestInState(MediaPlayerStateErrors.MediaPlayerState.PREPARED_AFTER_STOP);
    }
    
    private void checkStartedState() {
        callMediaPlayerMethodUnderTestInState(MediaPlayerStateErrors.MediaPlayerState.STARTED);
    }
    
    private void checkPausedState() {
        callMediaPlayerMethodUnderTestInState(MediaPlayerStateErrors.MediaPlayerState.PAUSED);
    }
    
    private void checkStartedStateAfterPause() {
        callMediaPlayerMethodUnderTestInState(MediaPlayerStateErrors.MediaPlayerState.STARTED_AFTER_PAUSE);
    }
    
    private void checkStoppedState() {
        callMediaPlayerMethodUnderTestInState(MediaPlayerStateErrors.MediaPlayerState.STOPPED);
    }
    
    private void checkPlaybackCompletedState() {
        callMediaPlayerMethodUnderTestInState(MediaPlayerStateErrors.MediaPlayerState.PLAYBACK_COMPLETED);
    }
    
    private void checkErrorState() {
        callMediaPlayerMethodUnderTestInState(MediaPlayerStateErrors.MediaPlayerState.ERROR);
    }

    /*
     * Checks the given method under test in all possible states of the MediaPlayer object.
     */
    private void checkMethodUnderTestInAllPossibleStates() {
        // Must be called first.
        checkIdleState(); 
        
        // The sequence of the following method calls should not 
        // affect the test results.
        checkErrorState();
        checkIdleStateAfterReset();
        checkInitializedState();
        checkStartedState();
        checkStartedStateAfterPause();
        checkPausedState();
        checkPreparedState();
        
        checkPreparedStateAfterStop();
        
        checkPlaybackCompletedState();
        checkStoppedState();
    }
    
    /*
     * Terminates the message looper thread.
     */
    private void terminateMessageLooper() {
        mLooper.quit();
        mMediaPlayer.release();
    }
    
    /*
     * Cleans up all the internal object references.
     */
    private void cleanUp() {
        mMediaPlayer = null;
        mMediaPlayerState = null;
        mLooper = null;
        mStateErrors = null;
        mMethodUnderTest = null;
    }
}
