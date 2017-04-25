/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.annotation.MainThread;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

/**
 * This service is implemented by dialer apps that wishes to handle OMTP or similar visual
 * voicemails. Telephony binds to this service when the cell service is first connected, a visual
 * voicemail SMS has been received, or when a SIM has been removed. Telephony will only bind to the
 * default dialer for such events (See {@link TelecomManager#getDefaultDialerPackage()}). The
 * {@link android.service.carrier.CarrierMessagingService} precedes the VisualVoicemailService in
 * the SMS filtering chain and may intercept the visual voicemail SMS before it reaches this
 * service.
 * <p>
 * To extend this class, The service must be declared in the manifest file with
 * the {@link android.Manifest.permission#BIND_VISUAL_VOICEMAIL_SERVICE} permission and include an
 * intent filter with the {@link #SERVICE_INTERFACE} action.
 * <p>
 * Below is an example manifest registration for a {@code VisualVoicemailService}.
 * <pre>
 * {@code
 * <service android:name="your.package.YourVisualVoicemailServiceImplementation"
 *          android:permission="android.permission.BIND_VISUAL_VOICEMAIL_SERVICE">
 *      <intent-filter>
 *          <action android:name="android.telephony.VisualVoicemailService"/>
 *      </intent-filter>
 * </service>
 * }
 * </pre>
 */
public abstract class VisualVoicemailService extends Service {

    private static final String TAG = "VvmService";

    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.telephony.VisualVoicemailService";

    /**
     * @hide
     */
    public static final int MSG_ON_CELL_SERVICE_CONNECTED = 1;
    /**
     * @hide
     */
    public static final int MSG_ON_SMS_RECEIVED = 2;
    /**
     * @hide
     */
    public static final int MSG_ON_SIM_REMOVED = 3;
    /**
     * @hide
     */
    public static final int MSG_TASK_ENDED = 4;
    /**
     * @hide
     */
    public static final int MSG_TASK_STOPPED = 5;

    /**
     * @hide
     */
    public static final String DATA_PHONE_ACCOUNT_HANDLE = "data_phone_account_handle";
    /**
     * @hide
     */
    public static final String DATA_SMS = "data_sms";

    /**
     * Represents a visual voicemail event which needs to be handled. While the task is being
     * processed telephony will hold a wakelock for the VisualVoicemailService. The service can
     * unblock the main thread and pass the task to a worker thread. Once the task is finished,
     * {@link VisualVoicemailTask#finish()} should be called to signal telephony to release the
     * resources. Telephony will call {@link VisualVoicemailService#onStopped(VisualVoicemailTask)}
     * when the task is going to be terminated before completion.
     *
     * @see #onCellServiceConnected(VisualVoicemailTask, PhoneAccountHandle)
     * @see #onSmsReceived(VisualVoicemailTask, VisualVoicemailSms)
     * @see #onSimRemoved(VisualVoicemailTask, PhoneAccountHandle)
     * @see #onStopped(VisualVoicemailTask)
     */
    public static class VisualVoicemailTask {

        private final int mTaskId;
        private final Messenger mReplyTo;

        private VisualVoicemailTask(Messenger replyTo, int taskId) {
            mTaskId = taskId;
            mReplyTo = replyTo;
        }

        /**
         * Call to signal telephony the task has completed. Must be called for every task.
         */
        public final void finish() {
            Message message = Message.obtain();
            try {
                message.what = MSG_TASK_ENDED;
                message.arg1 = mTaskId;
                mReplyTo.send(message);
            } catch (RemoteException e) {
                Log.e(TAG,
                        "Cannot send MSG_TASK_ENDED, remote handler no longer exist");
            }
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof VisualVoicemailTask)) {
                return false;
            }
            return mTaskId == ((VisualVoicemailTask) obj).mTaskId;
        }

        @Override
        public int hashCode() {
            return mTaskId;
        }
    }

    /**
     * Handles messages sent by telephony.
     */
    private final Messenger mMessenger = new Messenger(new Handler() {
        @Override
        public void handleMessage(final Message msg) {
            final PhoneAccountHandle handle = msg.getData()
                    .getParcelable(DATA_PHONE_ACCOUNT_HANDLE);
            VisualVoicemailTask task = new VisualVoicemailTask(msg.replyTo, msg.arg1);
            switch (msg.what) {
                case MSG_ON_CELL_SERVICE_CONNECTED:
                    onCellServiceConnected(task, handle);
                    break;
                case MSG_ON_SMS_RECEIVED:
                    VisualVoicemailSms sms = msg.getData().getParcelable(DATA_SMS);
                    onSmsReceived(task, sms);
                    break;
                case MSG_ON_SIM_REMOVED:
                    onSimRemoved(task, handle);
                    break;
                case MSG_TASK_STOPPED:
                    onStopped(task);
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    });

    @Override
    public IBinder onBind(Intent intent) {
        return mMessenger.getBinder();
    }

    /**
     * Called when the cellular service is connected on a {@link PhoneAccountHandle} for the first
     * time, or when the carrier config has changed. It will not be called when the signal is lost
     * then restored.
     *
     * @param task The task representing this event. {@link VisualVoicemailTask#finish()} must be
     * called when the task is completed.
     * @param phoneAccountHandle The {@link PhoneAccountHandle} triggering this event.
     */
    @MainThread
    public abstract void onCellServiceConnected(VisualVoicemailTask task,
                                                PhoneAccountHandle phoneAccountHandle);

    /**
     * Called when a SMS matching the {@link VisualVoicemailSmsFilterSettings} set by
     * {@link TelephonyManager#setVisualVoicemailSmsFilterSettings(VisualVoicemailSmsFilterSettings)
     * }
     * is received.
     *
     * @param task The task representing this event. {@link VisualVoicemailTask#finish()} must be
     * called when the task is completed.
     * @param sms The content of the received SMS.
     */
    @MainThread
    public abstract void onSmsReceived(VisualVoicemailTask task,
                                       VisualVoicemailSms sms);

    /**
     * Called when a SIM is removed.
     *
     * @param task The task representing this event. {@link VisualVoicemailTask#finish()} must be
     * called when the task is completed.
     * @param phoneAccountHandle The {@link PhoneAccountHandle} triggering this event.
     */
    @MainThread
    public abstract void onSimRemoved(VisualVoicemailTask task,
                                      PhoneAccountHandle phoneAccountHandle);

    /**
     * Called before the system is about to terminate a task. The service should persist any
     * necessary data and call finish on the task immediately.
     */
    @MainThread
    public abstract void onStopped(VisualVoicemailTask task);

    /**
     * Set the visual voicemail SMS filter settings for the VisualVoicemailService.
     * {@link #onSmsReceived(VisualVoicemailTask, VisualVoicemailSms)} will be called when
     * a SMS matching the settings is received. The caller should have
     * {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE} and implements a
     * VisualVoicemailService.
     * <p>
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     *
     * @param phoneAccountHandle The account to apply the settings to.
     * @param settings The settings for the filter, or {@code null} to disable the filter.
     *
     * @hide
     */
    @SystemApi
    public static final void setSmsFilterSettings(Context context,
            PhoneAccountHandle phoneAccountHandle,
            VisualVoicemailSmsFilterSettings settings) {
        TelephonyManager telephonyManager = context.getSystemService(TelephonyManager.class);
        int subId = getSubId(context, phoneAccountHandle);
        if (settings == null) {
            telephonyManager.disableVisualVoicemailSmsFilter(subId);
        } else {
            telephonyManager.enableVisualVoicemailSmsFilter(subId, settings);
        }
    }

    /**
     * Send a visual voicemail SMS. The caller must be the current default dialer.
     * <p>
     * <p>Requires Permission:
     * {@link android.Manifest.permission#SEND_SMS SEND_SMS}
     *
     * @param phoneAccountHandle The account to send the SMS with.
     * @param number The destination number.
     * @param port The destination port for data SMS, or 0 for text SMS.
     * @param text The message content. For data sms, it will be encoded as a UTF-8 byte stream.
     * @param sentIntent The sent intent passed to the {@link SmsManager}
     *
     * @throws SecurityException if the caller is not the current default dialer
     *
     * @see SmsManager#sendDataMessage(String, String, short, byte[], PendingIntent, PendingIntent)
     * @see SmsManager#sendTextMessage(String, String, String, PendingIntent, PendingIntent)
     *
     * @hide
     */
    @SystemApi
    public static final void sendVisualVoicemailSms(Context context,
            PhoneAccountHandle phoneAccountHandle, String number,
            short port, String text, PendingIntent sentIntent) {
        TelephonyManager telephonyManager = context.getSystemService(TelephonyManager.class);
        telephonyManager.sendVisualVoicemailSmsForSubscriber(getSubId(context, phoneAccountHandle),
                number, port, text, sentIntent);
    }

    private static int getSubId(Context context, PhoneAccountHandle phoneAccountHandle) {
        TelephonyManager telephonyManager = context.getSystemService(TelephonyManager.class);
        TelecomManager telecomManager = context.getSystemService(TelecomManager.class);
        return telephonyManager
                .getSubIdForPhoneAccount(telecomManager.getPhoneAccount(phoneAccountHandle));
    }

}
