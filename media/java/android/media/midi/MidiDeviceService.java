/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.media.midi;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * A service that implements a virtual MIDI device.
 * Subclasses must implement the {@link #onGetInputPortReceivers} method to provide a
 * list of {@link MidiReceiver}s to receive data sent to the device's input ports.
 * Similarly, subclasses can call {@link #getOutputPortReceivers} to fetch a list
 * of {@link MidiReceiver}s for sending data out the output ports.
 *
 * <p>To extend this class, you must declare the service in your manifest file with
 * an intent filter with the {@link #SERVICE_INTERFACE} action
 * and meta-data to describe the virtual device.
 For example:</p>
 * <pre>
 * &lt;service android:name=".VirtualDeviceService"
 *          android:label="&#64;string/service_name">
 *     &lt;intent-filter>
 *         &lt;action android:name="android.media.midi.MidiDeviceService" />
 *     &lt;/intent-filter>
 *           &lt;meta-data android:name="android.media.midi.MidiDeviceService"
                android:resource="@xml/device_info" />
 * &lt;/service></pre>
 */
abstract public class MidiDeviceService extends Service {
    private static final String TAG = "MidiDeviceService";

    public static final String SERVICE_INTERFACE = "android.media.midi.MidiDeviceService";

    private IMidiManager mMidiManager;
    private MidiDeviceServer mServer;
    private MidiDeviceInfo mDeviceInfo;

    private final MidiDeviceServer.Callback mCallback = new MidiDeviceServer.Callback() {
        @Override
        public void onDeviceStatusChanged(MidiDeviceServer server, MidiDeviceStatus status) {
            MidiDeviceService.this.onDeviceStatusChanged(status);
        }

        @Override
        public void onClose() {
            MidiDeviceService.this.onClose();
        }
    };

    @Override
    public void onCreate() {
        mMidiManager = IMidiManager.Stub.asInterface(
                    ServiceManager.getService(Context.MIDI_SERVICE));
        MidiDeviceServer server;
        try {
            MidiDeviceInfo deviceInfo = mMidiManager.getServiceDeviceInfo(getPackageName(),
                    this.getClass().getName());
            if (deviceInfo == null) {
                Log.e(TAG, "Could not find MidiDeviceInfo for MidiDeviceService " + this);
                return;
            }
            mDeviceInfo = deviceInfo;
            MidiReceiver[] inputPortReceivers = onGetInputPortReceivers();
            if (inputPortReceivers == null) {
                inputPortReceivers = new MidiReceiver[0];
            }
            server = new MidiDeviceServer(mMidiManager, inputPortReceivers, deviceInfo, mCallback);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in IMidiManager.getServiceDeviceInfo");
            server = null;
        }
        mServer = server;
   }

    /**
     * Returns an array of {@link MidiReceiver} for the device's input ports.
     * Subclasses must override this to provide the receivers which will receive
     * data sent to the device's input ports. An empty array should be returned if
     * the device has no input ports.
     * @return array of MidiReceivers
     */
    abstract public MidiReceiver[] onGetInputPortReceivers();

    /**
     * Returns an array of {@link MidiReceiver} for the device's output ports.
     * These can be used to send data out the device's output ports.
     * @return array of MidiReceivers
     */
    public final MidiReceiver[] getOutputPortReceivers() {
        if (mServer == null) {
            return null;
        } else {
            return mServer.getOutputPortReceivers();
        }
    }

    /**
     * returns the {@link MidiDeviceInfo} instance for this service
     * @return our MidiDeviceInfo
     */
    public final MidiDeviceInfo getDeviceInfo() {
        return mDeviceInfo;
    }

    /**
     * Called to notify when an our {@link MidiDeviceStatus} has changed
     * @param status the number of the port that was opened
     */
    public void onDeviceStatusChanged(MidiDeviceStatus status) {
    }

    /**
     * Called to notify when our device has been closed by all its clients
     */
    public void onClose() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction()) && mServer != null) {
             return mServer.getBinderInterface().asBinder();
        } else {
             return null;
       }
    }
}
