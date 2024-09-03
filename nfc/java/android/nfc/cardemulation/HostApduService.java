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

package android.nfc.cardemulation;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>HostApduService is a convenience {@link Service} class that can be
 * extended to emulate an NFC card inside an Android
 * service component.
 *
 * <div class="special reference">
 * <h3>Developer Guide</h3>
 * For a general introduction to card emulation, see
 * <a href="{@docRoot}guide/topics/connectivity/nfc/hce.html">
 * Host-based Card Emulation</a>.</p>
 * </div>
 *
 * <h3>NFC Protocols</h3>
 * <p>Cards emulated by this class are based on the NFC-Forum ISO-DEP
 * protocol (based on ISO/IEC 14443-4) and support processing
 * command Application Protocol Data Units (APDUs) as
 * defined in the ISO/IEC 7816-4 specification.
 *
 * <h3>Service selection</h3>
 * <p>When a remote NFC device wants to talk to your
 * service, it sends a so-called
 * "SELECT AID" APDU as defined in the ISO/IEC 7816-4 specification.
 * The AID is an application identifier defined in ISO/IEC 7816-4.
 *
 * <p>The registration procedure for AIDs is defined in the
 * ISO/IEC 7816-5 specification. If you don't want to register an
 * AID, you are free to use AIDs in the proprietary range:
 * bits 8-5 of the first byte must each be set to '1'. For example,
 * "0xF00102030405" is a proprietary AID. If you do use proprietary
 * AIDs, it is recommended to choose an AID of at least 6 bytes,
 * to reduce the risk of collisions with other applications that
 * might be using proprietary AIDs as well.
 *
 * <h3>AID groups</h3>
 * <p>In some cases, a service may need to register multiple AIDs
 * to implement a certain application, and it needs to be sure
 * that it is the default handler for all of these AIDs (as opposed
 * to some AIDs in the group going to another service).
 *
 * <p>An AID group is a list of AIDs that should be considered as
 * belonging together by the OS. For all AIDs in an AID group, the
 * OS will guarantee one of the following:
 * <ul>
 * <li>All AIDs in the group are routed to this service
 * <li>No AIDs in the group are routed to this service
 * </ul>
 * In other words, there is no in-between state, where some AIDs
 * in the group can be routed to this service, and some to another.
 * <h3>AID groups and categories</h3>
 * <p>Each AID group can be associated with a category. This allows
 * the Android OS to classify services, and it allows the user to
 * set defaults at the category level instead of the AID level.
 *
 * <p>You can use
 * {@link CardEmulation#isDefaultServiceForCategory(android.content.ComponentName, String)}
 * to determine if your service is the default handler for a category.
 *
 * <p>In this version of the platform, the only known categories
 * are {@link CardEmulation#CATEGORY_PAYMENT} and {@link CardEmulation#CATEGORY_OTHER}.
 * AID groups without a category, or with a category that is not recognized
 * by the current platform version, will automatically be
 * grouped into the {@link CardEmulation#CATEGORY_OTHER} category.
 * <h3>Service AID registration</h3>
 * <p>To tell the platform which AIDs groups
 * are requested by this service, a {@link #SERVICE_META_DATA}
 * entry must be included in the declaration of the service. An
 * example of a HostApduService manifest declaration is shown below:
 * <pre> &lt;service android:name=".MyHostApduService" android:exported="true" android:permission="android.permission.BIND_NFC_SERVICE"&gt;
 *     &lt;intent-filter&gt;
 *         &lt;action android:name="android.nfc.cardemulation.action.HOST_APDU_SERVICE"/&gt;
 *     &lt;/intent-filter&gt;
 *     &lt;meta-data android:name="android.nfc.cardemulation.host_apdu_ervice" android:resource="@xml/apduservice"/&gt;
 * &lt;/service&gt;</pre>
 *
 * This meta-data tag points to an apduservice.xml file.
 * An example of this file with a single AID group declaration is shown below:
 * <pre>
 * &lt;host-apdu-service xmlns:android="http://schemas.android.com/apk/res/android"
 *           android:description="@string/servicedesc" android:requireDeviceUnlock="false"&gt;
 *       &lt;aid-group android:description="@string/aiddescription" android:category="other">
 *           &lt;aid-filter android:name="F0010203040506"/&gt;
 *           &lt;aid-filter android:name="F0394148148100"/&gt;
 *       &lt;/aid-group&gt;
 * &lt;/host-apdu-service&gt;
 * </pre>
 *
 * <p>The {@link android.R.styleable#HostApduService &lt;host-apdu-service&gt;} is required
 * to contain a
 * {@link android.R.styleable#HostApduService_description &lt;android:description&gt;}
 * attribute that contains a user-friendly description of the service that may be shown in UI.
 * The
 * {@link android.R.styleable#HostApduService_requireDeviceUnlock &lt;requireDeviceUnlock&gt;}
 * attribute can be used to specify that the device must be unlocked before this service
 * can be invoked to handle APDUs.
 * <p>The {@link android.R.styleable#HostApduService &lt;host-apdu-service&gt;} must
 * contain one or more {@link android.R.styleable#AidGroup &lt;aid-group&gt;} tags.
 * Each {@link android.R.styleable#AidGroup &lt;aid-group&gt;} must contain one or
 * more {@link android.R.styleable#AidFilter &lt;aid-filter&gt;} tags, each of which
 * contains a single AID. The AID must be specified in hexadecimal format, and contain
 * an even number of characters.
 * <h3>AID conflict resolution</h3>
 * Multiple HostApduServices may be installed on a single device, and the same AID
 * can be registered by more than one service. The Android platform resolves AID
 * conflicts depending on which category an AID belongs to. Each category may
 * have a different conflict resolution policy. For example, for some categories
 * the user may be able to select a default service in the Android settings UI.
 * For other categories, to policy may be to always ask the user which service
 * is to be invoked in case of conflict.
 *
 * To query the conflict resolution policy for a certain category, see
 * {@link CardEmulation#getSelectionModeForCategory(String)}.
 *
 * <h3>Data exchange</h3>
 * <p>Once the platform has resolved a "SELECT AID" command APDU to a specific
 * service component, the "SELECT AID" command APDU and all subsequent
 * command APDUs will be sent to that service through
 * {@link #processCommandApdu(byte[], Bundle)}, until either:
 * <ul>
 * <li>The NFC link is broken</li>
 * <li>A "SELECT AID" APDU is received which resolves to another service</li>
 * </ul>
 * These two scenarios are indicated by a call to {@link #onDeactivated(int)}.
 *
 * <p class="note">Use of this class requires the
 * {@link PackageManager#FEATURE_NFC_HOST_CARD_EMULATION} to be present
 * on the device.
 *
 */
public abstract class HostApduService extends Service {
    /**
     * The {@link Intent} action that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.nfc.cardemulation.action.HOST_APDU_SERVICE";

    /**
     * The name of the meta-data element that contains
     * more information about this service.
     */
    public static final String SERVICE_META_DATA =
            "android.nfc.cardemulation.host_apdu_service";

    /**
     * Reason for {@link #onDeactivated(int)}.
     * Indicates deactivation was due to the NFC link
     * being lost.
     */
    public static final int DEACTIVATION_LINK_LOSS = 0;

    /**
     * Reason for {@link #onDeactivated(int)}.
     *
     * <p>Indicates deactivation was due to a different AID
     * being selected (which implicitly deselects the AID
     * currently active on the logical channel).
     *
     * <p>Note that this next AID may still be resolved to this
     * service, in which case {@link #processCommandApdu(byte[], Bundle)}
     * will be called again.
     */
    public static final int DEACTIVATION_DESELECTED = 1;

    static final String TAG = "ApduService";

    /**
     * MSG_COMMAND_APDU is sent by NfcService when
     * a 7816-4 command APDU has been received.
     *
     * @hide
     */
    public static final int MSG_COMMAND_APDU = 0;

    /**
     * MSG_RESPONSE_APDU is sent to NfcService to send
     * a response APDU back to the remote device.
     *
     * @hide
     */
    public static final int MSG_RESPONSE_APDU = 1;

    /**
     * MSG_DEACTIVATED is sent by NfcService when
     * the current session is finished; either because
     * another AID was selected that resolved to
     * another service, or because the NFC link
     * was deactivated.
     *
     * @hide
     */
    public static final int MSG_DEACTIVATED = 2;

    /**
     *
     * @hide
     */
    public static final int MSG_UNHANDLED = 3;

    /**
     * @hide
     */
    public static final int MSG_POLLING_LOOP = 4;

    /**
     * @hide
     */
    public static final int MSG_OBSERVE_MODE_CHANGE = 5;

    /**
     * @hide
     */
    public static final int MSG_PREFERRED_SERVICE_CHANGED = 6;

    /**
     * @hide
     */
    public static final String KEY_DATA = "data";

    /**
     * @hide
     */
    public static final String KEY_POLLING_LOOP_FRAMES_BUNDLE =
            "android.nfc.cardemulation.POLLING_FRAMES";

    /**
     * Messenger interface to NfcService for sending responses.
     * Only accessed on main thread by the message handler.
     *
     * @hide
     */
    Messenger mNfcService = null;

    final Messenger mMessenger = new Messenger(new MsgHandler());

    final class MsgHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_COMMAND_APDU:
                Bundle dataBundle = msg.getData();
                if (dataBundle == null) {
                    return;
                }
                if (mNfcService == null) mNfcService = msg.replyTo;

                byte[] apdu = dataBundle.getByteArray(KEY_DATA);
                if (apdu != null) {
                        HostApduService has = HostApduService.this;
                    byte[] responseApdu = processCommandApdu(apdu, null);
                    if (responseApdu != null) {
                        if (mNfcService == null) {
                            Log.e(TAG, "Response not sent; service was deactivated.");
                            return;
                        }
                        Message responseMsg = Message.obtain(null, MSG_RESPONSE_APDU);
                        Bundle responseBundle = new Bundle();
                        responseBundle.putByteArray(KEY_DATA, responseApdu);
                        responseMsg.setData(responseBundle);
                        responseMsg.replyTo = mMessenger;
                        try {
                            mNfcService.send(responseMsg);
                        } catch (RemoteException e) {
                            Log.e("TAG", "Response not sent; RemoteException calling into " +
                                    "NfcService.");
                        }
                    }
                } else {
                    Log.e(TAG, "Received MSG_COMMAND_APDU without data.");
                }
                break;
            case MSG_RESPONSE_APDU:
                if (mNfcService == null) {
                    Log.e(TAG, "Response not sent; service was deactivated.");
                    return;
                }
                try {
                    msg.replyTo = mMessenger;
                    mNfcService.send(msg);
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException calling into NfcService.");
                }
                break;
            case MSG_DEACTIVATED:
                // Make sure we won't call into NfcService again
                mNfcService = null;
                onDeactivated(msg.arg1);
                break;
            case MSG_UNHANDLED:
                if (mNfcService == null) {
                    Log.e(TAG, "notifyUnhandled not sent; service was deactivated.");
                    return;
                }
                try {
                    msg.replyTo = mMessenger;
                    mNfcService.send(msg);
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException calling into NfcService.");
                }
                break;
                case MSG_POLLING_LOOP:
                    if (android.nfc.Flags.nfcReadPollingLoop()) {
                        ArrayList<PollingFrame> pollingFrames =
                                msg.getData().getParcelableArrayList(
                                    KEY_POLLING_LOOP_FRAMES_BUNDLE, PollingFrame.class);
                        processPollingFrames(pollingFrames);
                    }
                    break;
                case MSG_OBSERVE_MODE_CHANGE:
                    if (android.nfc.Flags.nfcEventListener()) {
                        onObserveModeStateChanged(msg.arg1 == 1);
                    }
                    break;
                case MSG_PREFERRED_SERVICE_CHANGED:
                    if (android.nfc.Flags.nfcEventListener()) {
                        onPreferredServiceChanged(msg.arg1 == 1);
                    }
                    break;
                default:
                super.handleMessage(msg);
            }
        }
    }

    @Override
    public final IBinder onBind(Intent intent) {
        return mMessenger.getBinder();
    }

    /**
     * Sends a response APDU back to the remote device.
     *
     * <p>Note: this method may be called from any thread and will not block.
     * @param responseApdu A byte-array containing the reponse APDU.
     */
    public final void sendResponseApdu(byte[] responseApdu) {
        Message responseMsg = Message.obtain(null, MSG_RESPONSE_APDU);
        Bundle dataBundle = new Bundle();
        dataBundle.putByteArray(KEY_DATA, responseApdu);
        responseMsg.setData(dataBundle);
        try {
            mMessenger.send(responseMsg);
        } catch (RemoteException e) {
            Log.e("TAG", "Local messenger has died.");
        }
    }

    /**
     * Calling this method allows the service to tell the OS
     * that it won't be able to complete this transaction -
     * for example, because it requires data connectivity
     * that is not present at that moment.
     *
     * The OS may use this indication to give the user a list
     * of alternative applications that can handle the last
     * AID that was selected. If the user would select an
     * application from the list, that action by itself
     * will not cause the default to be changed; the selected
     * application will be invoked for the next tap only.
     *
     * If there are no other applications that can handle
     * this transaction, the OS will show an error dialog
     * indicating your service could not complete the
     * transaction.
     *
     * <p>Note: this method may be called anywhere between
     *    the first {@link #processCommandApdu(byte[], Bundle)}
     *    call and a {@link #onDeactivated(int)} call.
     */
    public final void notifyUnhandled() {
        Message unhandledMsg = Message.obtain(null, MSG_UNHANDLED);
        try {
            mMessenger.send(unhandledMsg);
        } catch (RemoteException e) {
            Log.e("TAG", "Local messenger has died.");
        }
    }

    /**
     * This method is called when polling frames have been received from a
     * remote device. If the device is in observe mode, the service should
     * call {@link NfcAdapter#allowTransaction()} once it is ready to proceed
     * with the transaction. If the device is not in observe mode, the service
     * can use this polling frame information to determine how to proceed if it
     * subsequently has {@link #processCommandApdu(byte[], Bundle)} called. The
     * service must override this method inorder to receive polling frames,
     * otherwise the base implementation drops the frame.
     *
     * @param frame A description of the polling frame.
     */
    @SuppressLint("OnNameExpected")
    @FlaggedApi(android.nfc.Flags.FLAG_NFC_READ_POLLING_LOOP)
    public void processPollingFrames(@NonNull List<PollingFrame> frame) {
    }

    /**
     * <p>This method will be called when a command APDU has been received
     * from a remote device. A response APDU can be provided directly
     * by returning a byte-array in this method. Note that in general
     * response APDUs must be sent as quickly as possible, given the fact
     * that the user is likely holding their device over an NFC reader
     * when this method is called.
     *
     * <p class="note">If there are multiple services that have registered for the same
     * AIDs in their meta-data entry, you will only get called if the user has
     * explicitly selected your service, either as a default or just for the next tap.
     *
     * <p class="note">This method is running on the main thread of your application.
     * If you cannot return a response APDU immediately, return null
     * and use the {@link #sendResponseApdu(byte[])} method later.
     *
     * @param commandApdu The APDU that was received from the remote device
     * @param extras A bundle containing extra data. May be null.
     * @return a byte-array containing the response APDU, or null if no
     *         response APDU can be sent at this point.
     */
    public abstract byte[] processCommandApdu(byte[] commandApdu, Bundle extras);

    /**
     * This method will be called in two possible scenarios:
     * <li>The NFC link has been deactivated or lost
     * <li>A different AID has been selected and was resolved to a different
     *     service component
     * @param reason Either {@link #DEACTIVATION_LINK_LOSS} or {@link #DEACTIVATION_DESELECTED}
     */
    public abstract void onDeactivated(int reason);


    /**
     * This method is called when this service is the preferred Nfc service and
     * Observe mode has been enabled or disabled.
     *
     * @param isEnabled true if observe mode has been enabled, false if it has been disabled
     */
    @FlaggedApi(android.nfc.Flags.FLAG_NFC_EVENT_LISTENER)
    public void onObserveModeStateChanged(boolean isEnabled) {

    }

    /**
     * This method is called when this service gains or loses preferred Nfc service status.
     *
     * @param isPreferred true is this service has become the preferred Nfc service,
     * false if it is no longer the preferred service
     */
    @FlaggedApi(android.nfc.Flags.FLAG_NFC_EVENT_LISTENER)
    public void onPreferredServiceChanged(boolean isPreferred) {
    }
}
