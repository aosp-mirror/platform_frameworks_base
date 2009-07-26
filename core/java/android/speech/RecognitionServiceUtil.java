/*
 * Copyright (C) 2009 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.speech;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.speech.RecognitionResult;
import android.util.Log;

import java.util.List;

/**
 * Utils for Google's network-based speech recognizer, which lets you perform
 * speech-to-text translation through RecognitionService. IRecognitionService
 * and IRecognitionListener are the core interfaces; you begin recognition
 * through IRecognitionService and subscribe to callbacks about when the user
 * stopped speaking, results come in, errors, etc. through IRecognitionListener.
 * RecognitionServiceUtil includes default IRecognitionListener and
 * ServiceConnection implementations to reduce the amount of boilerplate.
 *
 * The Service provides no user interface. See RecognitionActivity if you
 * want the standard voice search UI.
 *
 * Below is a small skeleton of how to use the recognizer:
 *
 * ServiceConnection conn = new RecognitionServiceUtil.Connection();
 * mContext.bindService(RecognitionServiceUtil.sDefaultIntent,
 *     conn, Context.BIND_AUTO_CREATE);
 * IRecognitionListener listener = new RecognitionServiceWrapper.NullListener() {
 *         public void onResults(List<String> results) {
 *             // Do something with recognition transcripts
 *         }
 *     }
 *
 * // Must wait for conn.mService to be populated, then call below
 * conn.mService.startListening(null, listener);
 *
 * {@hide}
 */
public class RecognitionServiceUtil {
    public static final Intent sDefaultIntent = new Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH);

    // Recognize request parameters
    public static final String USE_LOCATION = "useLocation";
    public static final String CONTACT_AUTH_TOKEN = "contactAuthToken";
    
    // Bundles
    public static final String NOISE_LEVEL = "NoiseLevel";
    public static final String SIGNAL_NOISE_RATIO = "SignalNoiseRatio";

    private RecognitionServiceUtil() {}

    /**
     * IRecognitionListener which does nothing in response to recognition
     * callbacks. You can subclass from this and override only the methods
     * whose events you want to respond to.
     */
    public static class NullListener extends IRecognitionListener.Stub {
        public void onReadyForSpeech(Bundle bundle) {}
        public void onBeginningOfSpeech() {}
        public void onRmsChanged(float rmsdB) {}
        public void onBufferReceived(byte[] buf) {}
        public void onEndOfSpeech() {}
        public void onError(int error) {}
        public void onResults(List<RecognitionResult> results, long key) {}
    }

    /**
     * Basic ServiceConnection which just records mService variable.
     */
    public static class Connection implements ServiceConnection {
        public IRecognitionService mService;

        public synchronized void onServiceConnected(ComponentName name, IBinder service) {
            mService = IRecognitionService.Stub.asInterface(service);
        }

        public void onServiceDisconnected(ComponentName name) {
            mService = null;
        }
    }
}
