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
import android.media.MediaRecorder;
import android.test.AndroidTestCase;

/**
 * A template class for running a method under test in all possible
 * states of a MediaRecorder object.
 * 
 * @see com.android.mediaframeworktest.unit.MediaRecorderStopStateUnitTest
 * for an example of using this class.
 * 
 * A typical concrete unit test class would implement the 
 * MediaRecorderMethodUnderTest interface and have a reference to an object of
 * this class. Then it calls runTestOnMethod() to actually perform the unit
 * tests. It is recommended that the toString() method of the concrete unit test
 * class be overridden to use the actual method name under test for logging 
 * purpose.
 * 
 */
class MediaRecorderStateUnitTestTemplate extends AndroidTestCase {
    public static final String RECORD_OUTPUT_PATH = "/sdcard/recording.3gp";
    public static final int OUTPUT_FORMAT= MediaRecorder.OutputFormat.THREE_GPP;
    public static final int AUDIO_ENCODER = MediaRecorder.AudioEncoder.AMR_NB;
    public static final int AUDIO_SOURCE = MediaRecorder.AudioSource.MIC;
    private static final String TAG = "MediaRecorderStateUnitTest";
    private MediaRecorderStateErrors mStateErrors = new MediaRecorderStateErrors();
    private MediaRecorder mMediaRecorder = new MediaRecorder();
    private MediaRecorderStateErrors.MediaRecorderState mMediaRecorderState = null;
    private MediaRecorderMethodUnderTest mMethodUnderTest = null;
    
    /**
     * Runs the given method under test in all possible states of a MediaRecorder
     * object.
     * 
     * @param testMethod the method under test.
     */
    public void runTestOnMethod(MediaRecorderMethodUnderTest testMethod) {
        mMethodUnderTest = testMethod;
        if (mMethodUnderTest != null) {  // Method under test has been set?
            checkMethodUnderTestInAllPossibleStates();
            mMethodUnderTest.checkStateErrors(mStateErrors);
            cleanUp();
        }
    }
    
    /*
     * Calls method under test in the given state of the MediaRecorder object.
     * 
     * @param state the MediaRecorder state in which the method under test is called.
     */
    private void callMediaRecorderMethodUnderTestInState(MediaRecorderStateErrors.MediaRecorderState state) {
        Log.v(TAG, "call " + mMethodUnderTest + ": started in state " + state);
        setMediaRecorderToState(state);
        try {
            mMethodUnderTest.invokeMethodUnderTest(mMediaRecorder);
        } catch(Exception e) {
            setStateError(mMediaRecorderState, true);
        }
        Log.v(TAG, "call " + mMethodUnderTest + ": ended in state " + state);
    }

    /*
     * The following setMediaRecorderToXXXStateXXX methods sets the MediaRecorder
     * object to the corresponding state, given the assumption that reset()
     * always resets the MediaRecorder object to Initial (after reset) state. 
     */
    private void setMediaRecorderToInitialStateAfterReset() {
        try {
            mMediaRecorder.reset();
        } catch(Exception e) {
            fail("setMediaRecorderToInitialStateAfterReset: Exception " + e.getClass().getName() + " was thrown.");
        }
    }

    // FIXME:
    // In the past, stop() == reset().
    // However, this is no longer true. The plan is to have a STOPPED state.
    // and from STOPPED state, start can be called without the need to
    // do the recording configuration again. 
    private void setMediaRecorderToInitialStateAfterStop() {
        try {
            mMediaRecorder.reset();
/*
            mMediaRecorder.setAudioSource(AUDIO_SOURCE);
            mMediaRecorder.setOutputFormat(OUTPUT_FORMAT);
            mMediaRecorder.setAudioEncoder(AUDIO_ENCODER);
            mMediaRecorder.setOutputFile(RECORD_OUTPUT_PATH);
            mMediaRecorder.prepare();
            mMediaRecorder.start();
            mMediaRecorder.stop();
*/
        } catch(Exception e) {
            fail("setMediaRecorderToInitialStateAfterReset: Exception " + e.getClass().getName() + " was thrown.");
        }
    }
    
    private void setMediaRecorderToInitializedState() {
        try {
            mMediaRecorder.reset();
            if (mMethodUnderTest.toString() != "setAudioSource()") {
                mMediaRecorder.setAudioSource(AUDIO_SOURCE);
            }
        } catch(Exception e) {
            fail("setMediaRecorderToInitializedState: Exception " + e.getClass().getName() + " was thrown.");
        }
    }
    
    private void setMediaRecorderToPreparedState() {
        try {
            mMediaRecorder.reset();
            mMediaRecorder.setAudioSource(AUDIO_SOURCE);
            mMediaRecorder.setOutputFormat(OUTPUT_FORMAT);
            mMediaRecorder.setAudioEncoder(AUDIO_ENCODER);
            mMediaRecorder.setOutputFile(RECORD_OUTPUT_PATH);
            mMediaRecorder.prepare();
        } catch(Exception e) {
            fail("setMediaRecorderToPreparedState: Exception " + e.getClass().getName() + " was thrown.");
        }
    }
    
    private void setMediaRecorderToRecordingState() {
        try {
            mMediaRecorder.reset();
            mMediaRecorder.setAudioSource(AUDIO_SOURCE);
            mMediaRecorder.setOutputFormat(OUTPUT_FORMAT);
            mMediaRecorder.setAudioEncoder(AUDIO_ENCODER);
            mMediaRecorder.setOutputFile(RECORD_OUTPUT_PATH);
            mMediaRecorder.prepare();
            mMediaRecorder.start();
        } catch(Exception e) {
            fail("setMediaRecorderToRecordingState: Exception " + e.getClass().getName() + " was thrown.");
        }
    }
    
    private void setMediaRecorderToDataSourceConfiguredState() {
        try {
            mMediaRecorder.reset();
            mMediaRecorder.setAudioSource(AUDIO_SOURCE);
            mMediaRecorder.setOutputFormat(OUTPUT_FORMAT);
            
            /* Skip setAudioEncoder() and setOutputFile() calls if
             * the method under test is setAudioEncoder() since this
             * method can only be called once even in the DATASOURCECONFIGURED state
             */
            if (mMethodUnderTest.toString() != "setAudioEncoder()") {
                mMediaRecorder.setAudioEncoder(AUDIO_ENCODER);
            }
            
            if (mMethodUnderTest.toString() != "setOutputFile()") {
                mMediaRecorder.setOutputFile(RECORD_OUTPUT_PATH);
            }
        } catch(Exception e) {
            fail("setMediaRecorderToDataSourceConfiguredState: Exception " + e.getClass().getName() + " was thrown.");
        }
    }
    
    /*
     * There are a lot of ways to force the MediaRecorder object to enter
     * the Error state. We arbitrary choose one here.
     */
    private void setMediaRecorderToErrorState() {
        try {
            mMediaRecorder.reset();
            
            /* Skip setAudioSource() if the method under test is setAudioEncoder()
             * Because, otherwise, it is valid to call setAudioEncoder() after
             * start() since start() will fail, and then the mMediaRecorder 
             * won't be set to the Error state
             */ 
            if (mMethodUnderTest.toString() != "setAudioEncoder()") {
                mMediaRecorder.setAudioSource(AUDIO_SOURCE);
            }
            
            /* Skip setOutputFormat if the method under test is setOutputFile()
             *  Because, otherwise, it is valid to call setOutputFile() after
             * start() since start() will fail, and then the mMediaRecorder 
             * won't be set to the Error state
             */ 
            if (mMethodUnderTest.toString() != "setOutputFile()") {
                mMediaRecorder.setOutputFormat(OUTPUT_FORMAT);
            }
            
            mMediaRecorder.start();
        } catch(Exception e) {
            if (!(e instanceof IllegalStateException)) {
                fail("setMediaRecorderToErrorState: Exception " + e.getClass().getName() + " was thrown.");
            }
        }
        Log.v(TAG, "setMediaRecorderToErrorState: done.");
    }
    
    /*
     * Sets the state of the MediaRecorder object to the specified one.
     * 
     * @param state the state of the MediaRecorder object.
     */
    private void setMediaRecorderToState(MediaRecorderStateErrors.MediaRecorderState state) {
        mMediaRecorderState = state;
        switch(state) {
            case INITIAL:
                // Does nothing.
                break;
            case INITIAL_AFTER_RESET:
                setMediaRecorderToInitialStateAfterReset();
                break;
            case INITIAL_AFTER_STOP:
                setMediaRecorderToInitialStateAfterStop();
                break;
            case INITIALIZED:
                setMediaRecorderToInitializedState();
                break;
            case DATASOURCECONFIGURED:
                setMediaRecorderToDataSourceConfiguredState();
                break;
            case PREPARED:
                setMediaRecorderToPreparedState();
                break;
            case RECORDING:
                setMediaRecorderToRecordingState();
                break;
            case ERROR:
                setMediaRecorderToErrorState();
                break;
        }
    }
    
    /*
     * Sets the error value of the corresponding state to the given error.
     * 
     * @param state the state of the MediaRecorder object.
     * @param error the value of the state error to be set.
     */
    private void setStateError(MediaRecorderStateErrors.MediaRecorderState state, boolean error) {
        switch(state) {
            case INITIAL:
                mStateErrors.errorInInitialState = error;
                break;
            case INITIAL_AFTER_RESET:
                mStateErrors.errorInInitialStateAfterReset = error;
                break;
            case INITIAL_AFTER_STOP:
                mStateErrors.errorInInitialStateAfterStop = error;
                break;
            case INITIALIZED:
                mStateErrors.errorInInitializedState = error;
                break;
            case DATASOURCECONFIGURED:
                mStateErrors.errorInDataSourceConfiguredState = error;
                break;
            case PREPARED:
                mStateErrors.errorInPreparedState = error;
                break;
            case RECORDING:
                mStateErrors.errorInRecordingState = error;
                break;
            case ERROR:
                mStateErrors.errorInErrorState = error;
                break;
        }
    }

    private void checkInitialState() {
        callMediaRecorderMethodUnderTestInState(MediaRecorderStateErrors.MediaRecorderState.INITIAL);
    }
    
    private void checkInitialStateAfterReset() {
        callMediaRecorderMethodUnderTestInState(MediaRecorderStateErrors.MediaRecorderState.INITIAL_AFTER_RESET);
    }
    
    private void checkInitialStateAfterStop() {
        callMediaRecorderMethodUnderTestInState(MediaRecorderStateErrors.MediaRecorderState.INITIAL_AFTER_STOP);
    }

    private void checkInitializedState() {
        callMediaRecorderMethodUnderTestInState(MediaRecorderStateErrors.MediaRecorderState.INITIALIZED);
    }
    
    private void checkPreparedState() {
        callMediaRecorderMethodUnderTestInState(MediaRecorderStateErrors.MediaRecorderState.PREPARED);
    }
    
    private void checkRecordingState() {
        callMediaRecorderMethodUnderTestInState(MediaRecorderStateErrors.MediaRecorderState.RECORDING);
    }
    
    private void checkDataSourceConfiguredState() {
        callMediaRecorderMethodUnderTestInState(MediaRecorderStateErrors.MediaRecorderState.DATASOURCECONFIGURED);
    }
    
    private void checkErrorState() {
        callMediaRecorderMethodUnderTestInState(MediaRecorderStateErrors.MediaRecorderState.ERROR);
    }

    /*
     * Checks the given method under test in all possible states of the MediaRecorder object.
     */
    private void checkMethodUnderTestInAllPossibleStates() {
        // Must be called first.
        checkInitialState(); 
        
        // The sequence of the following method calls should not 
        // affect the test results.
        checkErrorState();
        checkInitialStateAfterReset();
        checkInitialStateAfterStop();
        checkInitializedState();
        checkRecordingState();
        checkDataSourceConfiguredState();
        checkPreparedState();
    }
    
    /*
     * Cleans up all the internal object references.
     */
    private void cleanUp() {
        mMediaRecorder.release();
        mMediaRecorder = null;
        mMediaRecorderState = null;
        mStateErrors = null;
        mMethodUnderTest = null;
    }
}
