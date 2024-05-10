/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A service that implements a virtual MIDI device for Universal MIDI Packets (UMP).
 * Subclasses must implement the {@link #onGetInputPortReceivers} method to provide a
 * list of {@link MidiReceiver}s to receive data sent to the device's input ports.
 * Similarly, subclasses can call {@link #getOutputPortReceivers} to fetch a list
 * of {@link MidiReceiver}s for sending data out the output ports.
 *
 * Unlike traditional MIDI byte streams, only complete UMPs should be sent.
 * Unlike with {@link MidiDeviceService}, the number of input and output ports must be equal.
 *
 * <p>To extend this class, you must declare the service in your manifest file with
 * an intent filter with the {@link #SERVICE_INTERFACE} action
 * and meta-data to describe the virtual device.
 * For example:</p>
 * <pre>
 * &lt;service android:name=".VirtualDeviceService"
 *         android:label="&#64;string/service_name">
 *     &lt;intent-filter>
 *             &lt;action android:name="android.media.midi.MidiUmpDeviceService" />
 *     &lt;/intent-filter>
 *     &lt;property android:name="android.media.midi.MidiUmpDeviceService"
 *             android:resource="@xml/device_info" />
 * &lt;/service></pre>
 */
@FlaggedApi(Flags.FLAG_VIRTUAL_UMP)
public abstract class MidiUmpDeviceService extends Service {
    private static final String TAG = "MidiUmpDeviceService";

    @FlaggedApi(Flags.FLAG_VIRTUAL_UMP)
    public static final String SERVICE_INTERFACE = "android.media.midi.MidiUmpDeviceService";

    private IMidiManager mMidiManager;
    private MidiDeviceServer mServer;
    private MidiDeviceInfo mDeviceInfo;

    private final MidiDeviceServer.Callback mCallback = new MidiDeviceServer.Callback() {
        @Override
        public void onDeviceStatusChanged(MidiDeviceServer server, MidiDeviceStatus status) {
            MidiUmpDeviceService.this.onDeviceStatusChanged(status);
        }

        @Override
        public void onClose() {
            MidiUmpDeviceService.this.onClose();
        }
    };

    @FlaggedApi(Flags.FLAG_VIRTUAL_UMP)
    @Override
    public void onCreate() {
        mMidiManager = IMidiManager.Stub.asInterface(
                    ServiceManager.getService(Context.MIDI_SERVICE));
        MidiDeviceServer server;
        try {
            MidiDeviceInfo deviceInfo = mMidiManager.getServiceDeviceInfo(getPackageName(),
                    this.getClass().getName());
            if (deviceInfo == null) {
                Log.e(TAG, "Could not find MidiDeviceInfo for MidiUmpDeviceService " + this);
                return;
            }
            mDeviceInfo = deviceInfo;

            List<MidiReceiver> inputPortReceivers = onGetInputPortReceivers();
            if (inputPortReceivers == null) {
                Log.e(TAG, "Could not get input port receivers for MidiUmpDeviceService " + this);
                return;
            }
            MidiReceiver[] inputPortReceiversArr = new MidiReceiver[inputPortReceivers.size()];
            inputPortReceivers.toArray(inputPortReceiversArr);
            server = new MidiDeviceServer(mMidiManager, inputPortReceiversArr, deviceInfo,
                    mCallback);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in IMidiManager.getServiceDeviceInfo");
            server = null;
        }
        mServer = server;
    }

    /**
     * Returns a list of {@link MidiReceiver} for the device's input ports.
     * Subclasses must override this to provide the receivers which will receive
     * data sent to the device's input ports.
     * The number of input and output ports must be equal and non-zero.
     * @return list of MidiReceivers
     */
    @FlaggedApi(Flags.FLAG_VIRTUAL_UMP)
    public abstract @NonNull List<MidiReceiver> onGetInputPortReceivers();

    /**
     * Returns a list of {@link MidiReceiver} for the device's output ports.
     * These can be used to send data out the device's output ports.
     * The number of input and output ports must be equal and non-zero.
     * @return the list of MidiReceivers
     */
    @FlaggedApi(Flags.FLAG_VIRTUAL_UMP)
    public final @NonNull List<MidiReceiver> getOutputPortReceivers() {
        if (mServer == null) {
            return new ArrayList<MidiReceiver>();
        } else {
            return Arrays.asList(mServer.getOutputPortReceivers());
        }
    }

    /**
     * Returns the {@link MidiDeviceInfo} instance for this service
     * @return the MidiDeviceInfo of the virtual MIDI device if it was successfully created
     */
    @FlaggedApi(Flags.FLAG_VIRTUAL_UMP)
    public final @Nullable MidiDeviceInfo getDeviceInfo() {
        return mDeviceInfo;
    }

    /**
     * Called to notify when the {@link MidiDeviceStatus} has changed
     * @param status the current status of the MIDI device
     */
    @FlaggedApi(Flags.FLAG_VIRTUAL_UMP)
    public void onDeviceStatusChanged(@NonNull MidiDeviceStatus status) {
    }

    /**
     * Called to notify when the virtual MIDI device running in this service has been closed by
     * all its clients
     */
    @FlaggedApi(Flags.FLAG_VIRTUAL_UMP)
    public void onClose() {
    }

    @FlaggedApi(Flags.FLAG_VIRTUAL_UMP)
    @Override
    public @Nullable IBinder onBind(@NonNull Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction()) && mServer != null) {
            return mServer.getBinderInterface().asBinder();
        } else {
            return null;
        }
    }
}
