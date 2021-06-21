/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.telephony;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.WorkerThread;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteCallback;
import android.telephony.cdma.CdmaSmsCbProgramData;

import com.android.internal.util.FastPrintWriter;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.List;
import java.util.function.Consumer;

/**
 * A service which exposes the cell broadcast handling module to the system.
 * <p>
 * To extend this class, you must declare the service in your manifest file to require the
 * {@link android.Manifest.permission#BIND_CELL_BROADCAST_SERVICE} permission and include an intent
 * filter with the {@link #CELL_BROADCAST_SERVICE_INTERFACE}.
 * Implementations of this service should run in the phone process and with its UID.
 * <p>
 * For example:
 * <pre>{@code
 * <manifest xmlns:android="http://schemas.android.com/apk/res/android"
 *       android:sharedUserId="android.uid.phone">
 *   <service android:name=".MyCellBroadcastService"
 *         android:label="@string/service_name"
 *         android:process="com.android.phone"
 *         android:exported="true"
 *         android:permission="android.permission.BIND_CELL_BROADCAST_SERVICE">
 *     <intent-filter>
 *           <action android:name="android.telephony.CellBroadcastService" />
 *     </intent-filter>
 *   </service>
 * </manifest>
 * }</pre>
 *
 * @hide
 */
@SystemApi
public abstract class CellBroadcastService extends Service {

    public static final String CELL_BROADCAST_SERVICE_INTERFACE =
            "android.telephony.CellBroadcastService";

    private final ICellBroadcastService.Stub mStubWrapper;

    public CellBroadcastService() {
        mStubWrapper = new ICellBroadcastServiceWrapper();
    }

    /**
     * Handle a GSM cell broadcast SMS message forwarded from the system.
     *
     * @param slotIndex the index of the slot which received the message
     * @param message   the SMS PDU
     */
    public abstract void onGsmCellBroadcastSms(int slotIndex, @NonNull byte[] message);

    /**
     * Handle a CDMA cell broadcast SMS message forwarded from the system.
     *
     * @param slotIndex       the index of the slot which received the message
     * @param bearerData      the CDMA SMS bearer data
     * @param serviceCategory the CDMA SCPT service category
     */
    public abstract void onCdmaCellBroadcastSms(int slotIndex, @NonNull byte[] bearerData,
            @CdmaSmsCbProgramData.Category int serviceCategory);

    /**
     * Handle a CDMA cell broadcast SMS message forwarded from the system.
     *
     * @param slotIndex          the index of the slot which received the message
     * @param smsCbProgramData   the SMS CB program data of the message
     * @param originatingAddress the originating address of the message, as a non-separated dial
     *                           string
     * @param callback           a callback to run after each cell broadcast receiver has handled
     *                           the SCP message. The bundle will contain a non-separated
     *                           dial string as and an ArrayList of {@link CdmaSmsCbProgramResults}.
     */
    public abstract void onCdmaScpMessage(int slotIndex,
            @NonNull List<CdmaSmsCbProgramData> smsCbProgramData,
            @NonNull String originatingAddress, @NonNull Consumer<Bundle> callback);

    /**
     * Get broadcasted area information.
     *
     * @param slotIndex the index of the slot which received the area information.
     *
     * @return The area information string sent from the network. This is usually the human readable
     * string shown in Setting app's SIM status page.
     */
    @WorkerThread
    public abstract @NonNull CharSequence getCellBroadcastAreaInfo(int slotIndex);

    /**
     * If overriding this method, call through to the super method for any unknown actions.
     * {@inheritDoc}
     */
    @Override
    @CallSuper
    public IBinder onBind(@Nullable Intent intent) {
        return mStubWrapper;
    }

    /**
     * A wrapper around ICellBroadcastService that forwards calls to implementations of
     * {@link CellBroadcastService}.
     *
     * @hide
     */
    public class ICellBroadcastServiceWrapper extends ICellBroadcastService.Stub {
        /**
         * Handle a GSM cell broadcast SMS.
         *
         * @param slotIndex the index of the slot which received the broadcast
         * @param message   the SMS message PDU
         */
        @Override
        public void handleGsmCellBroadcastSms(int slotIndex, byte[] message) {
            CellBroadcastService.this.onGsmCellBroadcastSms(slotIndex, message);
        }

        /**
         * Handle a CDMA cell broadcast SMS.
         *
         * @param slotIndex       the index of the slot which received the broadcast
         * @param bearerData      the CDMA SMS bearer data
         * @param serviceCategory the CDMA SCPT service category
         */
        @Override
        public void handleCdmaCellBroadcastSms(int slotIndex, byte[] bearerData,
                int serviceCategory) {
            CellBroadcastService.this.onCdmaCellBroadcastSms(slotIndex, bearerData,
                    serviceCategory);
        }

        /**
         * Handle a CDMA Service Category Program message.
         *
         * @param slotIndex          the index of the slot which received the message
         * @param smsCbProgramData   the SMS CB program data of the message
         * @param originatingAddress the originating address of the message
         * @param callback           a callback to run after each cell broadcast receiver has
         *                           handled the SCP message
         */
        @Override
        public void handleCdmaScpMessage(int slotIndex,
                List<CdmaSmsCbProgramData> smsCbProgramData, String originatingAddress,
                RemoteCallback callback) {
            Consumer<Bundle> consumer = bundle -> {
                callback.sendResult(bundle);
            };
            CellBroadcastService.this.onCdmaScpMessage(slotIndex, smsCbProgramData,
                    originatingAddress, consumer);
        }

        /**
         * Get broadcasted area information
         *
         * @param slotIndex         the index of the slot which received the message
         *
         * @return The area information
         */
        @Override
        public @NonNull CharSequence getCellBroadcastAreaInfo(int slotIndex) {
            return CellBroadcastService.this.getCellBroadcastAreaInfo(slotIndex);
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
            CellBroadcastService.this.dump(fd, fout, args);
        }

        @Override
        public void dump(FileDescriptor fd, String[] args) {
            PrintWriter pw = new FastPrintWriter(new FileOutputStream(fd));
            CellBroadcastService.this.dump(fd, pw, args);
        }
    }
}
