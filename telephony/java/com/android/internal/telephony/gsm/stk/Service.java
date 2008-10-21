/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.gsm.stk;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;

import com.android.internal.telephony.gsm.CommandsInterface;
import com.android.internal.telephony.gsm.EncodeException;
import com.android.internal.telephony.gsm.GsmAlphabet;
import com.android.internal.telephony.gsm.GsmSimCard;
import com.android.internal.telephony.gsm.SIMFileHandler;
import com.android.internal.telephony.gsm.SIMRecords;
import com.android.internal.telephony.gsm.SimUtils;
import com.android.internal.telephony.gsm.stk.Duration.TimeUnit;

import android.util.Config;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Enumeration for representing the tag value of COMPREHENSION-TLV objects. If
 * you want to get the actual value, call {@link #value() value} method.
 * 
 * {@hide}
 */
enum ComprehensionTlvTag {
    COMMAND_DETAILS(0x01), 
    DEVICE_IDENTITIES(0x02), 
    RESULT(0x03), 
    DURATION(0x04), 
    ALPHA_ID(0x05), 
    USSD_STRING(0x0a), 
    TEXT_STRING(0x0d), 
    TONE(0x0e), 
    ITEM(0x0f), 
    ITEM_ID(0x10), 
    RESPONSE_LENGTH(0x11), 
    FILE_LIST(0x12), 
    HELP_REQUEST(0x15), 
    DEFAULT_TEXT(0x17), 
    EVENT_LIST(0x19), 
    ICON_ID(0x1e),
    ITEM_ICON_ID_LIST(0x1f),
    IMMEDIATE_RESPONSE(0x2b), 
    LANGUAGE(0x2d), 
    URL(0x31),
    BROWSER_TERMINATION_CAUSE(0x34), 
    TEXT_ATTRIBUTE(0x50);

    private int mValue;

    ComprehensionTlvTag(int value) {
        mValue = value;
    }

    /**
     * Returns the actual value of this COMPREHENSION-TLV object.
     * 
     * @return Actual tag value of this object
     */
    public int value() {
        return mValue;
    }
}

/**
 * Enumeration for representing "Type of Command" of proactive commands. If you
 * want to create a CommandType object, call the static method {@link
 * #fromInt(int) fromInt}.
 * 
 * {@hide}
 */
enum CommandType {
    DISPLAY_TEXT(0x21), 
    GET_INKEY(0x22), 
    GET_INPUT(0x23), 
    LAUNCH_BROWSER(0x15), 
    PLAY_TONE(0x20), 
    REFRESH(0x01), 
    SELECT_ITEM(0x24), 
    SEND_SS(0x11), 
    SEND_USSD(0x12), 
    SEND_SMS(0x13), 
    SEND_DTMF(0x14), 
    SET_UP_EVENT_LIST(0x05), 
    SET_UP_IDLE_MODE_TEXT(0x28), 
    SET_UP_MENU(0x25), 
    SET_UP_CALL(0x10);

    private int mValue;

    CommandType(int value) {
        mValue = value;
    }

    /**
     * Create a CommandType object.
     * 
     * @param value Integer value to be converted to a CommandType object.
     * @return CommandType object whose "Type of Command" value is {@code
     *         value}. If no CommandType object has that value, null is
     *         returned.
     */
    public static CommandType fromInt(int value) {
        for (CommandType e : CommandType.values()) {
            if (e.mValue == value) {
                return e;
            }
        }
        return null;
    }
}

/**
 * Main class that implements SIM Toolkit Service.
 * 
 * {@hide}
 */
public class Service extends Handler implements AppInterface {

    // Service members.
    private static Service sInstance;
    private CommandsInterface mCmdIf;
    private SIMRecords mSimRecords;
    private Context mContext;
    private GsmSimCard mSimCard;
    private CommandListener mCmdListener;
    private Object mCmdListenerLock = new Object();
    private CommandParams mCmdParams = null;
    private CommandParams mNextCmdParams = null;
    private State mState = State.IDLE;
    private Menu mMainMenu = null;
    private String mServiceName = "";
    private NotificationManager mNm = null;
    private int mAppIndicator = APP_INDICATOR_PRE_BOOT;
    private int mInstallIndicator = APP_INDICATOR_UNINSTALLED;
    private IconLoader mIconLoader = null;

    private static final String TAG = "STK";

    // Service constants.
    private static final int EVENT_SESSION_END              = 1;
    private static final int EVENT_PROACTIVE_COMMAND        = 2;
    private static final int EVENT_EVENT_NOTIFY             = 3;
    private static final int EVENT_CALL_SETUP               = 4;
    // Events to signal SIM presence or absent in the device.
    private static final int EVENT_SIM_LOADED               = 12;
    private static final int EVENT_SIM_ABSENT               = 13;
    static final int EVENT_LOAD_ICON_DONE                   = 14;

    private static final int DEV_ID_KEYPAD      = 0x01;
    private static final int DEV_ID_DISPLAY     = 0x02;
    private static final int DEV_ID_EARPIECE    = 0x03;
    private static final int DEV_ID_UICC        = 0x81;
    private static final int DEV_ID_TERMINAL    = 0x82;
    private static final int DEV_ID_NETWORK     = 0x83;

    // Event value for Event List COMPREHENSION-TLV object
    public static final int UICC_EVENT_MT_CALL                      = 0x00;
    public static final int UICC_EVENT_CALL_CONNECTED               = 0x01;
    public static final int UICC_EVENT_CALL_DISCONNECTED            = 0x02;
    public static final int UICC_EVENT_LOCATION_STATUS              = 0x03;
    public static final int UICC_EVENT_USER_ACTIVITY                = 0x04;
    public static final int UICC_EVENT_IDLE_SCREEN_AVAILABLE        = 0x05;
    public static final int UICC_EVENT_CARD_READER_STATUS           = 0x06;
    public static final int UICC_EVENT_LANGUAGE_SELECTION           = 0x07;
    public static final int UICC_EVENT_BROWSER_TERMINATION          = 0x08;
    public static final int UICC_EVENT_DATA_AVAILABLE               = 0x09;
    public static final int UICC_EVENT_CHANNEL_STATUS               = 0x0a;
    public static final int UICC_EVENT_ACCESS_TECH_CHANGE           = 0x0b;
    public static final int UICC_EVENT_DISPLAY_PARAMS_CHANGE        = 0x0c;
    public static final int UICC_EVENT_LOCAL_CONNECTION             = 0x0d;
    public static final int UICC_EVENT_NETWORK_SEARCH_MODE_CHANGE   = 0x0e;
    public static final int UICC_EVENT_BROWSING_STATUS              = 0x0f;
    public static final int UICC_EVENT_FRAMES_INFO_CHANGE           = 0x10;
    public static final int UICC_EVENT_I_WLAN_ACESS_STATUS          = 0x11;
    
    // Command Qualifier values
    static final int REFRESH_NAA_INIT_AND_FULL_FILE_CHANGE  = 0x00;
    static final int REFRESH_NAA_INIT_AND_FILE_CHANGE       = 0x02;
    static final int REFRESH_NAA_INIT                       = 0x03;
    static final int REFRESH_UICC_RESET                     = 0x04;

    // GetInKey Yes/No response characters constants.
    private static final byte GET_INKEY_YES = 0x01;
    private static final byte GET_INKEY_NO = 0x00;

    // Notification id used to display Idle Mode text in NotificationManager.
    static final int STK_NOTIFICATION_ID = 333;

    private static String APP_PACKAGE_NAME = "com.android.stk";
    private static String APP_FULL_NAME = APP_PACKAGE_NAME + ".StkActivity";

    // Application indicators constants
    static final int APP_INDICATOR_PRE_BOOT             = 0;
    static final int APP_INDICATOR_UNINSTALLED          = 1;
    static final int APP_INDICATOR_INSTALLED_NORMAL     = 2;
    static final int APP_INDICATOR_INSTALLED_SPECIAL    = 3;
    private static final int APP_INDICATOR_LAUNCHED     = 4;
    // Use setAppIndication(APP_INSTALL_INDICATOR) to go back for the original 
    // install indication.
    private static final int APP_INSTALL_INDICATOR      = 5;

    // Container class to hold temporary icon identifier TLV object info. 
    class IconId {
        int recordNumber;
        boolean selfExplanatory;
    }

    // Container class to hold temporary item icon identifier list TLV object info. 
    class ItemsIconId {
        int [] recordNumbers;
        boolean selfExplanatory;
    }

    /* Intentionally private for singleton */
    private Service(CommandsInterface ci, SIMRecords sr, Context context,
            SIMFileHandler fh, GsmSimCard sc) {
        if (ci == null || sr == null || context == null || fh == null
                || sc == null) {
            throw new NullPointerException(
                    "Service: Input parameters must not be null");
        }
        mCmdIf = ci;
        mContext = context;

        mCmdIf.setOnStkSessionEnd(this, EVENT_SESSION_END, null);
        mCmdIf.setOnStkProactiveCmd(this, EVENT_PROACTIVE_COMMAND, null);
        mCmdIf.setOnStkEvent(this, EVENT_EVENT_NOTIFY, null);
        mCmdIf.setOnStkCallSetUp(this, EVENT_CALL_SETUP, null);

        mSimRecords = sr;

        mSimCard = sc;
        mNm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        mIconLoader = IconLoader.getInstance(this, fh);

        // Register a receiver for install/unistall application.
        StkAppStateReceiver receiver = new StkAppStateReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(StkAppInstaller.STK_APP_INSTALL_ACTION);
        filter.addAction(StkAppInstaller.STK_APP_UNINSTALL_ACTION);
        mContext.registerReceiver(receiver, filter);

        // Register for SIM ready event.
        mSimRecords.registerForRecordsLoaded(this, EVENT_SIM_LOADED, null);
        mSimCard.registerForAbsent(this, EVENT_SIM_ABSENT, null);
    }

    /**
     * Used for retrieving the only Service object in the system. There is only
     * one Service object.
     * 
     * @param ci CommandsInterface object
     * @param sr SIMRecords object
     * @return The only Service object in the system
     */
    public static Service getInstance(CommandsInterface ci, SIMRecords sr,
            Context context, SIMFileHandler fh, GsmSimCard sc) {
        if (sInstance == null) {
            if (ci == null || sr == null || context == null || fh == null
                    || sc == null) {
                return null;
            }
            sInstance = new Service(ci, sr, context, fh, sc);
        }
        return sInstance;
    }

    /**
     * Used for retrieving the only Service object in the system. There is only
     * one Service object.
     * 
     * @return The only Service object in the system
     */
    public static Service getInstance() {
        return getInstance(null, null, null, null, null);
    }

    /**
     * {@inheritDoc}
     */
    public void setCommandListener(CommandListener l) {
        synchronized (mCmdListenerLock) {
            mCmdListener = l;
            if (mCmdListener != null) {
                setAppIndication(APP_INDICATOR_LAUNCHED);
            } else {
                setAppIndication(APP_INSTALL_INDICATOR);
            }
        }
    }

    synchronized void setAppIndication(int indication) {
        switch(indication) {
        case APP_INDICATOR_PRE_BOOT:
        case APP_INDICATOR_UNINSTALLED:
        case APP_INDICATOR_INSTALLED_NORMAL:
        case APP_INDICATOR_INSTALLED_SPECIAL:
        case APP_INDICATOR_LAUNCHED:
            mAppIndicator = indication;
            break;
        case APP_INSTALL_INDICATOR:
            mAppIndicator = mInstallIndicator;
            break;
        default:
            throw new NullPointerException("Trying to set wrong app indication");
        }
    }

    public synchronized int getAppIndication() {
        return mAppIndicator;
    }

    /**
     * {@inheritDoc}
     */
    public State getState() {
        return mState;
    }

    /**
     * {@inheritDoc}
     */
    public void notifyMenuSelection(int menuId, boolean helpRequired) {
        if (mState != State.MAIN_MENU) {
            return;
        }

        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        // tag
        int tag = BerTlv.BER_MENU_SELECTION_TAG;
        buf.write(tag);

        // length
        buf.write(0x00); // place holder

        // device identities
        tag = 0x80 | ComprehensionTlvTag.DEVICE_IDENTITIES.value();
        buf.write(tag);
        buf.write(0x02); // length
        buf.write(DEV_ID_KEYPAD); // source device id
        buf.write(DEV_ID_UICC); // destination device id

        // item identifier
        tag = 0x80 | ComprehensionTlvTag.ITEM_ID.value();
        buf.write(tag);
        buf.write(0x01); // length
        buf.write(menuId); // menu identifier chosen

        // help request
        if (helpRequired) {
            tag = ComprehensionTlvTag.HELP_REQUEST.value();
            buf.write(tag);
            buf.write(0x00); // length
        }

        byte[] rawData = buf.toByteArray();

        // write real length
        int len = rawData.length - 2; // minus (tag + length)
        rawData[1] = (byte) len;

        String hexString = SimUtils.bytesToHexString(rawData);

        mCmdIf.sendEnvelope(hexString, null);
    }

    /**
     * {@inheritDoc}
     */
    public void notifyUserActivity() {
        eventDownload(UICC_EVENT_USER_ACTIVITY, DEV_ID_TERMINAL, DEV_ID_UICC,
                null, true);
    }

    /**
     * {@inheritDoc}
     */
    public void notifyDisplayTextEnded(ResultCode terminationCode) {
        if (mState != State.DISPLAY_TEXT) {
            return;
        }
        ResultCode rc = ResultCode.OK;

        switch (terminationCode) {
        case OK:
        case BACKWARD_MOVE_BY_USER:
        case NO_RESPONSE_FROM_USER:
            rc = terminationCode;
            break;
        default:
            Log.d(TAG, "Invalid termination code for Display Text");
            return;
        }
        sendTerminalResponse(mCmdParams.cmdDet, rc, false, 0, null);
    }

    /**
     * {@inheritDoc}
     */
    public void notifyToneEnded() {
        if (mState != State.PLAY_TONE) {
            return;
        }

        sendTerminalResponse(mCmdParams.cmdDet, ResultCode.OK, false, 0,
                null);
    }

    /**
     * {@inheritDoc}
     */
    public void notifyIdleScreenAvailable() {
        eventDownload(UICC_EVENT_IDLE_SCREEN_AVAILABLE, DEV_ID_DISPLAY,
                DEV_ID_UICC, null, true);
    }

    /**
     * {@inheritDoc}
     */
    public void notifyLanguageSelection(String langCode) {
        assert langCode.length() == 2 : "Language code must be two characters";

        byte[] lang = GsmAlphabet.stringToGsm8BitPacked(langCode);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        // language
        int tag = 0x80 | ComprehensionTlvTag.LANGUAGE.value();
        buf.write(tag);
        buf.write(0x02); // length
        buf.write(lang[0]);
        buf.write(lang[1]);

        byte[] info = buf.toByteArray();

        eventDownload(UICC_EVENT_LANGUAGE_SELECTION, DEV_ID_TERMINAL,
                DEV_ID_UICC, info, false);
    }

    /**
     * {@inheritDoc}
     */
    public void notifyBrowserTermination(boolean isErrorTermination) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        int cause = isErrorTermination ? 1 : 0;

        // browser termination cause
        int tag = 0x80 | ComprehensionTlvTag.BROWSER_TERMINATION_CAUSE.value();
        buf.write(tag);
        buf.write(0x01); // length
        buf.write(cause);

        byte[] info = buf.toByteArray();

        eventDownload(UICC_EVENT_BROWSER_TERMINATION, DEV_ID_TERMINAL,
                DEV_ID_UICC, info, true);
    }

    /**
     * {@inheritDoc}
     */
    public void notifyLaunchBrowser(boolean userConfirmed) {

        if (mState != State.LAUNCH_BROWSER) {
            return;
        }

        ResultCode rc = userConfirmed ? ResultCode.OK
                : ResultCode.TERMINAL_CRNTLY_UNABLE_TO_PROCESS;
        sendTerminalResponse(mCmdParams.cmdDet, rc, false, 0, null);
    }

    private void eventDownload(int event, int sourceId, int destinationId,
            byte[] additionalInfo, boolean oneShot) {

        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        // tag
        int tag = BerTlv.BER_EVENT_DOWNLOAD_TAG;
        buf.write(tag);

        // length
        buf.write(0x00); // place holder, assume length < 128.

        // event list
        tag = 0x80 | ComprehensionTlvTag.EVENT_LIST.value();
        buf.write(tag);
        buf.write(0x01); // length
        buf.write(event); // event value

        // device identities
        tag = 0x80 | ComprehensionTlvTag.DEVICE_IDENTITIES.value();
        buf.write(tag);
        buf.write(0x02); // length
        buf.write(sourceId); // source device id
        buf.write(destinationId); // destination device id

        // additional information
        if (additionalInfo != null) {
            for (byte b : additionalInfo) {
                buf.write(b);
            }
        }

        byte[] rawData = buf.toByteArray();

        // write real length
        int len = rawData.length - 2; // minus (tag + length)
        rawData[1] = (byte) len;

        String hexString = SimUtils.bytesToHexString(rawData);

        mCmdIf.sendEnvelope(hexString, null);
    }

    /**
     * {@inheritDoc}
     */
    public void notifyInkey(char key, boolean helpRequired) {
        if (mState != State.GET_INKEY) {
            return;
        }

        GetInkeyInputResponseData resp = null;
        ResultCode result = ResultCode.OK;
        GetInkeyParams request = (GetInkeyParams) mCmdParams;
        if (helpRequired) {
            result = ResultCode.HELP_INFO_REQUIRED;
        } else {
            resp = new GetInkeyInputResponseData(Character.toString(key),
                    request.isUcs2, false);           
        }

        sendTerminalResponse(request.cmdDet, result, false, 0, resp);
    }

    /**
     * {@inheritDoc}
     */
    public void notifyInkey(boolean yesNoResponse, boolean helpRequired) {
        if (mState != State.GET_INKEY) {
            return;
        }

        GetInkeyInputResponseData resp = null;
        ResultCode result = ResultCode.OK;
        GetInkeyParams cmdParams = (GetInkeyParams) mCmdParams;
        if (!cmdParams.isYesNo) {
            // Illegal use of this call.
            return;
        }
        if (helpRequired) {
            result = ResultCode.HELP_INFO_REQUIRED;
        } else {
            resp = new GetInkeyInputResponseData(yesNoResponse);            
        }

        sendTerminalResponse(cmdParams.cmdDet, result, false, 0, resp);
    }

    /**
     * {@inheritDoc}
     */
    public void notifyInput(String input, boolean helpRequired) {
        if (mState != State.GET_INPUT) {
            return;
        }

        GetInkeyInputResponseData resp = null;
        GetInputParams cmdParams = (GetInputParams) mCmdParams;
        ResultCode result = ResultCode.OK;

        if (helpRequired) {
            result = ResultCode.HELP_INFO_REQUIRED;
        } else {
            resp = new GetInkeyInputResponseData(input, cmdParams.isUcs2,
                    cmdParams.isPacked);
        }
        sendTerminalResponse(cmdParams.cmdDet, result, false, 0, resp);
    }

    /**
     * {@inheritDoc}
     */
    public void notifySelectedItem(int id, boolean helpRequired) {
        if (mState != State.SELECT_ITEM) {
            return;
        }

        SelectItemResponseData resp = new SelectItemResponseData(id);
        ResultCode result = helpRequired ? ResultCode.HELP_INFO_REQUIRED
                : ResultCode.OK;

        sendTerminalResponse(mCmdParams.cmdDet, result, false, 0, resp);
    }

    /**
     * {@inheritDoc}
     */
    public void notifyNoResponse() {
        CtlvCommandDetails cmdDet = getCurrentCmdDet();
        if (cmdDet == null) {
            // Unable to continue;
            return;
        }
        sendTerminalResponse(cmdDet, ResultCode.NO_RESPONSE_FROM_USER, false,
                0, null);
    }

    /**
     * {@inheritDoc}
     */
    public void acceptOrRejectCall(boolean accept) {
        if (mState != State.CALL_SETUP) {
            return;
        }
        mCmdIf.handleCallSetupRequestFromSim(accept, null);
    }

    /**
     * Indicates if STK is supported by the SIM card.
     */
    public boolean isStkSupported() {
        switch (getAppIndication()) {
        case APP_INDICATOR_PRE_BOOT:
        case APP_INDICATOR_UNINSTALLED:
            return false;
        }

        return true;
    }

    /**
     * Returns the unique service name for STK.
     */
    public String getServiceName() {
        return mServiceName;
    }

    /**
     * {@inheritDoc}
     */
    public void handleMessage(Message msg) {
        AsyncResult ar;

        switch (msg.what) {
        case EVENT_SESSION_END:
            ar = (AsyncResult) msg.obj;
            handleSessionEnd(ar.result);
            break;
        case EVENT_PROACTIVE_COMMAND:
            ar = (AsyncResult) msg.obj;
            handleProactiveCommand((String) ar.result);
            break;
        case EVENT_EVENT_NOTIFY:
            ar = (AsyncResult) msg.obj;
            handleEventNotify((String) ar.result);
            break;
        case EVENT_CALL_SETUP:
            mState = State.CALL_SETUP;
            break;
        case EVENT_SIM_LOADED:
        case EVENT_SIM_ABSENT:
            if (!isStkSupported()) {
                setAppState(false);
            }
            break;
        case EVENT_LOAD_ICON_DONE:
            handleProactiveCommandIcons(msg.obj);
            break;
        default:
            throw new AssertionError("Unrecognized STK command: " + msg.what);
        }
    }
    
    /**
     * Send terminal response for backward move in the proactive SIM session
     * requested by the user
     * 
     * Only available when responding following proactive commands
     *      DISPLAY_TEXT(0x21), 
     *      GET_INKEY(0x22), 
     *      GET_INPUT(0x23), 
     *      SET_UP_MENU(0x25);
     * 
     * @return true if stk can send backward move response
     * 
     */
    public boolean backwardMove() {
        CtlvCommandDetails cmdDet = null;

        cmdDet = getCurrentCmdDet();

        if (cmdDet == null) {
            return false;
        }

        sendTerminalResponse(cmdDet, ResultCode.BACKWARD_MOVE_BY_USER, false,
                0, null);
        return true;
    }

    /**
     * Send terminal response for proactive SIM session terminated by the user
     * 
     * Only available when responding following proactive commands
     *      DISPLAY_TEXT(0x21), 
     *      GET_INKEY(0x22), 
     *      GET_INPUT(0x23), 
     *      PLAY_TONE(0x20),
     *      SET_UP_MENU(0x25);
     * 
     * @return true if stk can send terminate session response
     */
    public boolean terminateSession() {
        CtlvCommandDetails cmdDet = null;

        cmdDet = getCurrentCmdDet();

        if (cmdDet == null) {
            return false;
        }

        sendTerminalResponse(cmdDet, ResultCode.UICC_SESSION_TERM_BY_USER,
                false, 0, null);
        mState = State.MAIN_MENU;
        return true;
    }

    private CtlvCommandDetails getCurrentCmdDet() {
        CtlvCommandDetails cmdDet = null;

        if (mCmdParams != null) {
            cmdDet = mCmdParams.cmdDet;
        }

        return cmdDet;
    }

    /**
     * Handles RIL_UNSOL_STK_SESSION_END unsolicited command from RIL.
     * 
     * @param data Null object. Do not use this.
     */
    private void handleSessionEnd(Object data) {
        if (Config.LOGD) {
            Log.d(TAG, "handleSessionEnd begins");
        }
        switch (mInstallIndicator) {
        case APP_INDICATOR_INSTALLED_NORMAL:
            mState = State.MAIN_MENU;
            break;
        case APP_INDICATOR_INSTALLED_SPECIAL:
        case APP_INDICATOR_UNINSTALLED:
            mState = State.IDLE;
            break;
        default:
            Log.d(TAG, "Can't set service state");
        }
        synchronized (mCmdListenerLock) {
            if (mCmdListener != null) {
                mCmdListener.onSessionEnd();
            }
        }
    }

    class CtlvDeviceIdentities {
        public int sourceId;
        public int destinationId;
    }

    abstract class ResponseData {
        /**
         * Format the data appropriate for TERMINAL RESPONSE and write it into
         * the ByteArrayOutputStream object.
         */
        public abstract void format(ByteArrayOutputStream buf);
    }

    class GetInkeyInputResponseData extends ResponseData {
        private boolean mIsUcs2;
        private boolean mIsPacked;
        private boolean mIsYesNo;
        private boolean mYesNoResponse;
        public String mInData;

        public GetInkeyInputResponseData(String inData, boolean ucs2,
                boolean packed) {
            super();
            this.mIsUcs2 = ucs2;
            this.mIsPacked = packed;
            this.mInData = inData;
            this.mIsYesNo = false;
        }

        public GetInkeyInputResponseData(boolean yesNoResponse) {
            super();
            this.mIsUcs2 = false;
            this.mIsPacked = false;
            this.mInData = "";
            this.mIsYesNo = true;
            this.mYesNoResponse = yesNoResponse;
        }

        @Override
        public void format(ByteArrayOutputStream buf) {
            if (buf == null) {
                return;
            }

            // Text string object
            int tag = 0x80 | ComprehensionTlvTag.TEXT_STRING.value();
            buf.write(tag); // tag

            byte[] data;

            if (mIsYesNo) {
                data = new byte[1];
                data[0] = mYesNoResponse ? GET_INKEY_YES : GET_INKEY_NO;
            } else if (mInData != null && mInData.length() > 0) {
                try {
                    if (mIsUcs2) {
                        data = mInData.getBytes("UTF-16");
                    } else if (mIsPacked) {
                        int size = mInData.length();

                        byte[] tempData = GsmAlphabet
                                .stringToGsm7BitPacked(mInData);
                        data = new byte[size];
                        // Since stringToGsm7BitPacked() set byte 0 in the
                        // returned byte array to the count of septets used...
                        // copy to a new array without byte 0.
                        System.arraycopy(tempData, 1, data, 0, size);
                    } else {
                        data = GsmAlphabet.stringToGsm8BitPacked(mInData);
                    }
                } catch (UnsupportedEncodingException e) {
                    data = new byte[0];
                } catch (EncodeException e) {
                    data = new byte[0];
                }
            } else {
                data = new byte[0];
            }

            // length - one more for data coding scheme.
            buf.write(data.length + 1);

            // data coding scheme
            if (mIsUcs2) {
                buf.write(0x08); // UCS2
            } else if (mIsPacked) {
                buf.write(0x00); // 7 bit packed
            } else {
                buf.write(0x04); // 8 bit unpacked
            }

            for (byte b : data) {
                buf.write(b);
            }
        }
    }

    class SelectItemResponseData extends ResponseData {
        private int id;

        public SelectItemResponseData(int id) {
            super();
            this.id = id;
        }

        @Override
        public void format(ByteArrayOutputStream buf) {
            // Item identifier object
            int tag = 0x80 | ComprehensionTlvTag.ITEM_ID.value();
            buf.write(tag); // tag
            buf.write(1); // length
            buf.write(id); // identifier of item chosen
        }
    }

    /**
     * Handles RIL_UNSOL_STK_PROACTIVE_COMMAND unsolicited command from RIL.
     * This method parses the data transmitted from the SIM card, and handles
     * the command according to the "Type of Command". Each proactive command is
     * handled by a corresponding handleXXX() method.
     * 
     * @param data String containing SAT/USAT proactive command in hexadecimal
     *        format starting with command tag
     */
    private void handleProactiveCommand(String data) {
        if (Config.LOGD) {
            Log.d(TAG, "handleProactiveCommand begins");
        }
        // If commands arrives before the SIM loaded/SIM absent events have
        // arrived post a message for a delayed processing in 2 seconds.
        if (getAppIndication() == APP_INDICATOR_PRE_BOOT) {
            Message installMsg = this.obtainMessage(EVENT_PROACTIVE_COMMAND);
            AsyncResult.forMessage(installMsg, data, null);
            sendMessageDelayed(installMsg, 2000);
            return;
        }

        CtlvCommandDetails cmdDet = null;
        try {
            byte[] rawData = SimUtils.hexStringToBytes(data);
            BerTlv berTlv = BerTlv.decode(rawData);

            List<ComprehensionTlv> ctlvs = berTlv.getComprehensionTlvs();
            cmdDet = retrieveCommandDetails(ctlvs);

            CommandType cmdType = CommandType.fromInt(cmdDet.typeOfCommand);
            if (cmdType == null) {
                throw new ResultException(ResultCode.BEYOND_TERMINAL_CAPABILITY);
            }

            // SET UP MENU & SET up IDLE MODE TEXT commands should not trigger
            // the special install & launch sequence.
            if (cmdType != CommandType.SET_UP_MENU
                    && cmdType != CommandType.SET_UP_IDLE_MODE_TEXT) {
                switch (getAppIndication()) {
                case APP_INDICATOR_UNINSTALLED:
                    setAppState(true);
                    setAppIndication(APP_INDICATOR_INSTALLED_SPECIAL);
                    mInstallIndicator = APP_INDICATOR_INSTALLED_SPECIAL;
                    Message installMsg = this
                            .obtainMessage(EVENT_PROACTIVE_COMMAND);
                    AsyncResult.forMessage(installMsg, data, null);
                    sendMessageDelayed(installMsg, 20);
                    return;
                case APP_INDICATOR_INSTALLED_SPECIAL:
                case APP_INDICATOR_INSTALLED_NORMAL:
                    Intent intent = new Intent(Intent.ACTION_MAIN);
                    intent.setClassName("com.android.stk",
                            "com.android.stk.StkActivity");
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mContext.startActivity(intent);
                    Message launchMsg = this
                            .obtainMessage(EVENT_PROACTIVE_COMMAND);
                    AsyncResult.forMessage(launchMsg, data, null);
                    sendMessageDelayed(launchMsg, 2000);
                    return;
                }
            }

            CtlvDeviceIdentities devIds = retrieveDeviceIdentities(ctlvs);
            boolean cmdPending = false;
            boolean responseNeeded = false;

            switch (cmdType) {
            case DISPLAY_TEXT:
                cmdPending = processDisplayText(cmdDet, devIds, ctlvs);
                responseNeeded = true;
                break;
            case SET_UP_MENU:
                cmdPending = processSetUpMenu(cmdDet, devIds, ctlvs);
                responseNeeded = true;
                break;
            case SET_UP_IDLE_MODE_TEXT:
                cmdPending = processSetUpIdleModeText(cmdDet, devIds, ctlvs);
                responseNeeded = true;
                break;
            case GET_INKEY:
                cmdPending = processGetInkey(cmdDet, devIds, ctlvs);
                break;
            case GET_INPUT:
                cmdPending = processGetInput(cmdDet, devIds, ctlvs);
                break;
            case REFRESH:
                processRefresh(cmdDet, devIds, ctlvs);
                responseNeeded = true;
                break;
            case SELECT_ITEM:
                cmdPending = processSelectItem(cmdDet, devIds, ctlvs);
                break;
            case LAUNCH_BROWSER:
                cmdPending = processLaunchBrowser(cmdDet, devIds, ctlvs);
                break;
            case PLAY_TONE:
                cmdPending = processPlayTone(cmdDet, devIds, ctlvs);
                break;
            default:
                // This should never be reached!
                throw new AssertionError(
                        "Add case statements for the newly added "
                                + "command types!");
            }
            if (!cmdPending) {
                callStkApp(cmdType);
            }
            if (responseNeeded) {
                sendTerminalResponse(cmdDet, ResultCode.OK, false, 0, null);
            }
        } catch (ResultException e) {
            sendTerminalResponse(cmdDet, e.result(), e.hasAdditionalInfo(), e
                    .additionalInfo(), null);
        }
    }

    private void handleProactiveCommandIcons(Object data) {
        CommandType cmdType = CommandType
                .fromInt(mNextCmdParams.cmdDet.typeOfCommand);
        boolean needsResponse = false;
        Bitmap[] icons = null;
        int iconIndex = 0;

        switch (cmdType) {
        case SET_UP_IDLE_MODE_TEXT:
            ((CommonUIParams) mNextCmdParams).mIcon = (Bitmap) (data);
            callStkApp(CommandType.SET_UP_IDLE_MODE_TEXT);
            break;
        case DISPLAY_TEXT:
            ((DisplayTextParams) mNextCmdParams).icon = (Bitmap) (data);
            callStkApp(CommandType.DISPLAY_TEXT);
            break;
        case SELECT_ITEM:
            
            SelectItemParams params = ((SelectItemParams) mNextCmdParams);
            Menu menu = params.mMenu;
            switch(params.mIconLoadState) {
            case SelectItemParams.LOAD_TITLE_ICON:
                menu.titleIcon = (Bitmap) data;
                break;
            case SelectItemParams.LOAD_ITEMS_ICONS:
                icons = (Bitmap[]) data;
                // set each item icon.
                for (Item item : menu.items) {
                    item.icon = icons[iconIndex++];
                }
                break;
            case SelectItemParams.LOAD_TITLE_ITEMS_ICONS:
                icons = (Bitmap[]) data;
                // set title icon
                menu.titleIcon = icons[iconIndex++];
                // set each item icon.
                for (Item item : menu.items) {
                    item.icon = icons[iconIndex++];
                }
            }
            callStkApp(CommandType.SELECT_ITEM);
            break;
        default:
            // This should never be reached!
            throw new AssertionError("Add case statements for the newly added "
                    + "command types!");
        }
    }
    
    private void callStkApp(CommandType cmdType) {
        boolean needsResponse = false;
        mCmdParams = mNextCmdParams;

        synchronized (mCmdListenerLock) {
            switch (cmdType) {
            case SET_UP_IDLE_MODE_TEXT:
                if (mNm == null) {
                    break;
                }
                CommonUIParams i = (CommonUIParams) mCmdParams;
                if (i.mText == null) {
                    mNm.cancel(STK_NOTIFICATION_ID);
                } else {
                    Notification notification = new Notification();
                    RemoteViews contentView = new RemoteViews(
                            mContext.getPackageName(),
                            com.android.internal.R.layout.status_bar_latest_event_content);

                    // Set text and icon for the status bar.
                    notification.icon = com.android.internal.R.drawable.stat_notify_sim_toolkit;
                    notification.tickerText = i.mText;
                    notification.flags |= Notification.FLAG_NO_CLEAR;

                    // Set text and icon for the notification body.
                    if (!i.mIconSelfExplanatory) {
                        contentView.setTextViewText(
                                com.android.internal.R.id.text, i.mText);
                    }
                    if (i.mIcon != null) {
                        contentView.setImageViewBitmap(
                                com.android.internal.R.id.icon, i.mIcon);
                    } else {
                        contentView
                                .setImageViewResource(
                                        com.android.internal.R.id.icon,
                                        com.android.internal.R.drawable.stat_notify_sim_toolkit);
                    }
                    notification.contentView = contentView;

                    mNm.notify(STK_NOTIFICATION_ID, notification);
                }
            case SET_UP_MENU:
                needsResponse = true;
                break;
            case SELECT_ITEM:
                mState = State.SELECT_ITEM;
                SelectItemParams s = (SelectItemParams) mCmdParams;
                mCmdListener.onSelectItem(s.mMenu, s.mPresentationType);
                needsResponse = false;
                break;
            case DISPLAY_TEXT:
                mState = State.DISPLAY_TEXT;
                DisplayTextParams d = (DisplayTextParams) mCmdParams;
                mCmdListener.onDisplayText(d.text, d.textAttrs, d.isHighPriority,
                        d.userClear, !d.immediateResponse, d.icon);

                needsResponse = d.immediateResponse;
                break;
            default:
                // This should never be reached!
                throw new AssertionError(
                        "Add case statements for the newly added "
                                + "command types!");
            }
        }

        if (needsResponse) {
            sendTerminalResponse(mCmdParams.cmdDet, ResultCode.OK, false, 0, null);
        }
    }

    private void sendTerminalResponse(CtlvCommandDetails cmdDet,
            ResultCode resultCode, boolean includeAdditionalInfo,
            int additionalInfo, ResponseData resp) {

        if (cmdDet == null) {
            return;
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        // command details
        int tag = ComprehensionTlvTag.COMMAND_DETAILS.value();
        if (cmdDet.compRequired) {
            tag |= 0x80;
        }
        buf.write(tag);
        buf.write(0x03); // length
        buf.write(cmdDet.commandNumber);
        buf.write(cmdDet.typeOfCommand);
        buf.write(cmdDet.commandQualifier);

        // device identities
        tag = 0x80 | ComprehensionTlvTag.DEVICE_IDENTITIES.value();
        buf.write(tag);
        buf.write(0x02); // length
        buf.write(DEV_ID_TERMINAL); // source device id
        buf.write(DEV_ID_UICC); // destination device id

        // result
        tag = 0x80 | ComprehensionTlvTag.RESULT.value();
        buf.write(tag);
        int length = includeAdditionalInfo ? 2 : 1;
        buf.write(length);
        buf.write(resultCode.code());

        // additional info
        if (includeAdditionalInfo) {
            buf.write(additionalInfo);
        }

        // Fill optional data for each corresponding command
        if (resp != null) {
            resp.format(buf);
        }

        byte[] rawData = buf.toByteArray();
        String hexString = SimUtils.bytesToHexString(rawData);
        if (Config.LOGD) {
            Log.d(TAG, "TERMINAL RESPONSE: " + hexString);
        }

        mCmdIf.sendTerminalResponse(hexString, null);
    }

    /**
     * Search for a COMPREHENSION-TLV object with the given tag from a list
     * 
     * @param tag A tag to search for
     * @param ctlvs List of ComprehensionTlv objects used to search in
     * 
     * @return A ComprehensionTlv object that has the tag value of {@code tag}.
     *         If no object is found with the tag, null is returned.
     */
    private ComprehensionTlv searchForTag(ComprehensionTlvTag tag,
            List<ComprehensionTlv> ctlvs) {
        Iterator<ComprehensionTlv> iter = ctlvs.iterator();
        return searchForNextTag(tag, iter);
    }

    /**
     * Search for the next COMPREHENSION-TLV object with the given tag from a
     * list iterated by {@code iter}. {@code iter} points to the object next to
     * the found object when this method returns. Used for searching the same
     * list for similar tags, usually item id.
     * 
     * @param tag A tag to search for
     * @param iter Iterator for ComprehensionTlv objects used for search
     * 
     * @return A ComprehensionTlv object that has the tag value of {@code tag}.
     *         If no object is found with the tag, null is returned.
     */
    private ComprehensionTlv searchForNextTag(ComprehensionTlvTag tag,
            Iterator<ComprehensionTlv> iter) {
        int tagValue = tag.value();
        while (iter.hasNext()) {
            ComprehensionTlv ctlv = iter.next();
            if (ctlv.getTag() == tagValue) {
                return ctlv;
            }
        }
        return null;
    }

    /**
     * Search for a Command Details object from a list.
     * 
     * @param ctlvs List of ComprehensionTlv objects used for search
     * @return An CtlvCommandDetails object found from the objects. If no
     *         Command Details object is found, ResultException is thrown.
     * @throws ResultException
     */
    private CtlvCommandDetails retrieveCommandDetails(
            List<ComprehensionTlv> ctlvs) throws ResultException {

        ComprehensionTlv ctlv = searchForTag(
                ComprehensionTlvTag.COMMAND_DETAILS, ctlvs);
        if (ctlv != null) {
            CtlvCommandDetails cmdDet = new CtlvCommandDetails();
            byte[] rawValue = ctlv.getRawValue();
            int valueIndex = ctlv.getValueIndex();
            try {
                cmdDet.compRequired = ctlv.isComprehensionRequired();
                cmdDet.commandNumber = rawValue[valueIndex] & 0xff;
                cmdDet.typeOfCommand = rawValue[valueIndex + 1] & 0xff;
                cmdDet.commandQualifier = rawValue[valueIndex + 2] & 0xff;
                return cmdDet;
            } catch (IndexOutOfBoundsException e) {
                throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
            }
        } else {
            throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
        }
    }

    /**
     * Search for a Device Identities object from a list.
     * 
     * @param ctlvs List of ComprehensionTlv objects used for search
     * @return An CtlvDeviceIdentities object found from the objects. If no
     *         Command Details object is found, ResultException is thrown.
     * @throws ResultException
     */
    private CtlvDeviceIdentities retrieveDeviceIdentities(
            List<ComprehensionTlv> ctlvs) throws ResultException {

        ComprehensionTlv ctlv = searchForTag(
                ComprehensionTlvTag.DEVICE_IDENTITIES, ctlvs);
        if (ctlv != null) {
            CtlvDeviceIdentities devIds = new CtlvDeviceIdentities();
            byte[] rawValue = ctlv.getRawValue();
            int valueIndex = ctlv.getValueIndex();
            try {
                devIds.sourceId = rawValue[valueIndex] & 0xff;
                devIds.destinationId = rawValue[valueIndex + 1] & 0xff;
                return devIds;
            } catch (IndexOutOfBoundsException e) {
                throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
            }
        } else {
            throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
        }
    }

    /**
     * Processes SETUP_CALL proactive command from the SIM card.
     * 
     * @param cmdDet Command Details object retrieved from the proactive command
     *        object
     * @param devIds Device Identities object retrieved from the proactive
     *        command object
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional 
     *         asynchronous processing is required.
     */
    private void processSetupCall(CtlvCommandDetails cmdDet,
            CtlvDeviceIdentities devIds, List<ComprehensionTlv> ctlvs) {
        if (Config.LOGD) {
            Log.d(TAG, "processSetupCall begins");
        }

        // User confirmation phase message.
        String confirmMsg = null;
        // Call set up phase message.
        String callMsg = null;
        List<TextAttribute> textAttrs = null;
        Iterator<ComprehensionTlv> iter = ctlvs.iterator();
        ComprehensionTlv ctlv = null;

        try {
            // get confirmation message string.
            ctlv = searchForNextTag(ComprehensionTlvTag.ALPHA_ID, iter);
            if (ctlv != null) {
                confirmMsg = retrieveAlphaId(ctlv);
            } else {
                // No message to show.
                throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
            }
            // get call set up message string.
            ctlv = searchForNextTag(ComprehensionTlvTag.ALPHA_ID, iter);
            if (ctlv != null) {
                callMsg = retrieveAlphaId(ctlv);
            }

            ctlv = searchForTag(ComprehensionTlvTag.TEXT_ATTRIBUTE, ctlvs);
            if (ctlv != null) {
                textAttrs = retrieveTextAttribute(ctlv);
            }
        } catch (ResultException e) {
            // Unable to process command. Send terminal response when service is
            // in call state.
            while (mState != State.CALL_SETUP) {
                Thread.yield();
            }
            sendTerminalResponse(cmdDet, ResultCode.REQUIRED_VALUES_MISSING,
                    false, 0, null);
            return;
        }

        synchronized (mCmdListenerLock) {
            if (mCmdListener != null) {
                mCmdListener.onCallSetup(confirmMsg, textAttrs, callMsg);
            }
        }
    }

    /**
     * Processes DISPLAY_TEXT proactive command from the SIM card.
     * 
     * @param cmdDet Command Details object retrieved from the proactive command
     *        object
     * @param devIds Device Identities object retrieved from the proactive
     *        command object
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional 
     *         asynchronous processing is required.
     * @throws ResultException
     */
    private boolean processDisplayText(CtlvCommandDetails cmdDet,
            CtlvDeviceIdentities devIds, List<ComprehensionTlv> ctlvs)
            throws ResultException {

        if (Config.LOGD) {
            Log.d(TAG, "processDisplayText begins");
        }

        DisplayTextParams params = new DisplayTextParams(cmdDet);
        IconId iconId = null;

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.TEXT_STRING,
                ctlvs);
        if (ctlv != null) {
            params.text = retrieveTextString(ctlv);
        } 
        // If the tlv object doesn't exist or the it is a null object reply 
        // with command not understood.
        if (params.text == null) {
            throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
        }

        ctlv = searchForTag(ComprehensionTlvTag.TEXT_ATTRIBUTE, ctlvs);
        if (ctlv != null) {
            params.textAttrs = retrieveTextAttribute(ctlv);
        }

        ctlv = searchForTag(ComprehensionTlvTag.IMMEDIATE_RESPONSE, ctlvs);
        if (ctlv != null) {
            params.immediateResponse = true;
        }

        ctlv = searchForTag(ComprehensionTlvTag.ICON_ID, ctlvs);
        if (ctlv != null) {
            iconId = retrieveIconId(ctlv);
        }

        // Parse command qualifier parameters.
        params.isHighPriority = (cmdDet.commandQualifier & 0x01) != 0;
        params.userClear = (cmdDet.commandQualifier & 0x80) != 0;

        mNextCmdParams = params;

        // If there's no icon to load call stk application.
        if (iconId != null) {
            mIconLoader.loadIcon(iconId.recordNumber, this
                    .obtainMessage(EVENT_LOAD_ICON_DONE));
            return true;
        } 
        return false;
    }

    /**
     * Processes SET_UP_MENU proactive command from the SIM card.
     * 
     * @param cmdDet Command Details object retrieved from the proactive command
     *        object
     * @param devIds Device Identities object retrieved from the proactive
     *        command object
     * @param ctlvs Iterator for ComprehensionTlv objects following Command
     *        Details object and Device Identities object within the proactive
     *        command
     * @return true if the command is processing is pending and additional 
     *         asynchronous processing is required.
     * @throws ResultException
     */
    private boolean processSetUpMenu(CtlvCommandDetails cmdDet,
            CtlvDeviceIdentities devIds, List<ComprehensionTlv> ctlvs)
            throws ResultException {

        if (Config.LOGD) {
            Log.d(TAG, "processSetUpMenu begins");
        }

        Menu menu = new Menu();
        boolean first = true;
        boolean removeExistingMenu = false;
        Iterator<ComprehensionTlv> iter = ctlvs.iterator();

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.ALPHA_ID,
                ctlvs);
        if (ctlv != null) {
            menu.title = retrieveAlphaId(ctlv);
        }

        ctlv = searchForTag(ComprehensionTlvTag.TEXT_ATTRIBUTE, ctlvs);
        if (ctlv != null) {
            menu.titleAttrs = retrieveTextAttribute(ctlv);
        }

        while (true) {
            ctlv = searchForNextTag(ComprehensionTlvTag.ITEM, iter);
            if (ctlv != null) {
                Item item = retrieveItem(ctlv);
                // If the first item is a "null" object, it means that
                // the existing menu should be removed.
                if (first && item == null) {
                    removeExistingMenu = true;
                    break;
                } 
                menu.items.add(retrieveItem(ctlv));
                first = false;
            } else {
                break;
            }
        }

        // Extract command details.
        menu.softKeyPreferred = (cmdDet.commandQualifier & 0x01) != 0;
        menu.helpAvailable = (cmdDet.commandQualifier & 0x80) != 0;

        // We must have at least one menu item.
        if (menu.items.size() == 0 && !removeExistingMenu) {
            if (Config.LOGD) {
                Log.d(TAG, "processSetUpMenu: Need at least one menu item");
            }
            throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
        }
        mCmdParams = new CommandParams(cmdDet);
        if (removeExistingMenu) {
            mState = State.IDLE;
            mMainMenu = null;
            setAppState(false);
        } else {
            Menu currentMenu = mMainMenu;
            mState = State.MAIN_MENU;
            mMainMenu = menu;
            if (!isStkSupported()) {
                setAppState(true);
                setAppIndication(APP_INDICATOR_INSTALLED_NORMAL);
                mInstallIndicator = APP_INDICATOR_INSTALLED_NORMAL;
            }
        }
        return true;
    }

    private void setAppState(boolean installed) {
        if (installed) {
            StkAppInstaller.installApp(mContext);
        } else {
            setAppIndication(APP_INDICATOR_UNINSTALLED);
            mInstallIndicator = APP_INDICATOR_UNINSTALLED;
            StkAppInstaller.unInstallApp(mContext);
        }
    }

    public Menu getCurrentMenu() {
        Menu menu = null;
        switch(mState) {
        case MAIN_MENU:
            menu = mMainMenu;
            break;
        case SELECT_ITEM:
            menu = ((SelectItemParams) mCmdParams).mMenu;
            break;
        }
       return menu;
    }

    /**
     * Processes SET_UP_IDLE_MODE_TEXT proactive command from the SIM card.
     * 
     * @param cmdDet Command Details object retrieved from the proactive command
     *        object
     * @param devIds Device Identities object retrieved from the proactive
     *        command object
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional 
     *         asynchronous processing is required.
     * @throws ResultException
     */
    private boolean processSetUpIdleModeText(CtlvCommandDetails cmdDet,
            CtlvDeviceIdentities devIds, List<ComprehensionTlv> ctlvs)
            throws ResultException {

        if (Config.LOGD) {
            Log.d(TAG, "processSetUpIdleModeText begins");
        }

        if (mNm == null) {
            throw new ResultException(ResultCode.TERMINAL_CRNTLY_UNABLE_TO_PROCESS);
        }

        String text = null;
        IconId iconId = null;
        List<TextAttribute> textAttrs = null;

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.TEXT_STRING,
                ctlvs);
        if (ctlv != null) {
            text = retrieveTextString(ctlv);
        } else {
            throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
        }

        ctlv = searchForTag(ComprehensionTlvTag.ICON_ID, ctlvs);
        if (ctlv != null) {
            iconId = retrieveIconId(ctlv);
        }

        ctlv = searchForTag(ComprehensionTlvTag.TEXT_ATTRIBUTE, ctlvs);
        if (ctlv != null) {
            textAttrs = retrieveTextAttribute(ctlv);
        }

        CommonUIParams params = new CommonUIParams(cmdDet, text, null);
        mNextCmdParams = params;
        if (iconId != null) {
            params.mIconSelfExplanatory = iconId.selfExplanatory;
            mIconLoader.loadIcon(iconId.recordNumber, this
                    .obtainMessage(EVENT_LOAD_ICON_DONE));
            return true;
        } 
        return false;
    }

    /**
     * Processes GET_INKEY proactive command from the SIM card.
     * 
     * @param cmdDet Command Details object retrieved from the proactive command
     *        object
     * @param devIds Device Identities object retrieved from the proactive
     *        command object
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional 
     *         asynchronous processing is required.
     * @throws ResultException
     */
    private boolean processGetInkey(CtlvCommandDetails cmdDet,
            CtlvDeviceIdentities devIds, List<ComprehensionTlv> ctlvs)
            throws ResultException {

        if (Config.LOGD) {
            Log.d(TAG, "processGetInkey begins");
        }

        String text = null;
        List<TextAttribute> textAttrs = null;

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.TEXT_STRING,
                ctlvs);
        if (ctlv != null) {
            text = retrieveTextString(ctlv);
        } else {
            throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
        }

        ctlv = searchForTag(ComprehensionTlvTag.TEXT_ATTRIBUTE, ctlvs);
        if (ctlv != null) {
            textAttrs = retrieveTextAttribute(ctlv);
        }

        boolean digitOnly = (cmdDet.commandQualifier & 0x01) == 0;
        boolean ucs2 = (cmdDet.commandQualifier & 0x02) != 0;
        boolean yesNo = (cmdDet.commandQualifier & 0x04) != 0;
        boolean immediateResponse = (cmdDet.commandQualifier & 0x08) != 0;
        boolean helpAvailable = (cmdDet.commandQualifier & 0x80) != 0;

        synchronized (mCmdListenerLock) {
            if (mCmdListener != null) {
                mCmdParams = new GetInkeyParams(cmdDet, yesNo, ucs2);
                mState = State.GET_INKEY;

                mCmdListener.onGetInkey(text, textAttrs, yesNo, digitOnly,
                        ucs2, immediateResponse, helpAvailable);
                return true;
            } else {
                // '0' means "No specific cause can be given"
                throw new ResultException(
                        ResultCode.TERMINAL_CRNTLY_UNABLE_TO_PROCESS, 0);
            }
        }
    }

    /**
     * Processes GET_INPUT proactive command from the SIM card.
     * 
     * @param cmdDet Command Details object retrieved from the proactive command
     *        object
     * @param devIds Device Identities object retrieved from the proactive
     *        command object
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional 
     *         asynchronous processing is required.
     * @throws ResultException
     */
    private boolean processGetInput(CtlvCommandDetails cmdDet,
            CtlvDeviceIdentities devIds, List<ComprehensionTlv> ctlvs)
            throws ResultException {

        if (Config.LOGD) {
            Log.d(TAG, "processGetInput begins");
        }

        String text = null;
        String defaultText = null;
        int minLen, maxLen;
        List<TextAttribute> textAttrs = null;

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.TEXT_STRING,
                ctlvs);
        if (ctlv != null) {
            text = retrieveTextString(ctlv);
        } else {
            throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
        }

        ctlv = searchForTag(ComprehensionTlvTag.RESPONSE_LENGTH, ctlvs);
        if (ctlv != null) {
            try {
                byte[] rawValue = ctlv.getRawValue();
                int valueIndex = ctlv.getValueIndex();
                minLen = rawValue[valueIndex] & 0xff;
                maxLen = rawValue[valueIndex + 1] & 0xff;
            } catch (IndexOutOfBoundsException e) {
                throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
            }
        } else {
            throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
        }

        ctlv = searchForTag(ComprehensionTlvTag.TEXT_ATTRIBUTE, ctlvs);
        if (ctlv != null) {
            textAttrs = retrieveTextAttribute(ctlv);
        }

        ctlv = searchForTag(ComprehensionTlvTag.DEFAULT_TEXT, ctlvs);
        if (ctlv != null) {
            defaultText = retrieveTextString(ctlv);
        }

        boolean digitOnly = (cmdDet.commandQualifier & 0x01) == 0;
        boolean ucs2 = (cmdDet.commandQualifier & 0x02) != 0;
        boolean echo = (cmdDet.commandQualifier & 0x04) == 0;
        boolean packed = (cmdDet.commandQualifier & 0x08) != 0;
        boolean helpAvailable = (cmdDet.commandQualifier & 0x80) != 0;

        synchronized (mCmdListenerLock) {
            if (mCmdListener != null) {
                mCmdParams = new GetInputParams(cmdDet, ucs2, packed);
                mState = State.GET_INPUT;

                boolean noMaxLimit = maxLen == 0xff;
                mCmdListener.onGetInput(text, defaultText, minLen, maxLen,
                        noMaxLimit, textAttrs, digitOnly, ucs2, echo,
                        helpAvailable);
                return true;
            } else {
                // '0' means "No specific cause can be given"
                throw new ResultException(
                        ResultCode.TERMINAL_CRNTLY_UNABLE_TO_PROCESS, 0);
            }
        }
    }

    /**
     * Processes REFRESH proactive command from the SIM card.
     * 
     * @param cmdDet Command Details object retrieved from the proactive command
     *        object
     * @param devIds Device Identities object retrieved from the proactive
     *        command object
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @throws ResultException
     */
    private void processRefresh(CtlvCommandDetails cmdDet,
            CtlvDeviceIdentities devIds, List<ComprehensionTlv> ctlvs)
            throws ResultException {

        if (Config.LOGD) {
            Log.d(TAG, "processRefresh begins");
        }

        // REFRESH proactive command is rerouted by the baseband and handled by
        // the telephony layer. IDLE TEXT should be removed for a REFRESH command 
        // with "initialization" or "reset" 

        if (mNm == null) {
            throw new ResultException(ResultCode.TERMINAL_CRNTLY_UNABLE_TO_PROCESS);
        }

        boolean removeIdleText = false;

        switch (cmdDet.commandQualifier) {
        case REFRESH_NAA_INIT_AND_FULL_FILE_CHANGE:
        case REFRESH_NAA_INIT_AND_FILE_CHANGE:
        case REFRESH_NAA_INIT:
        case REFRESH_UICC_RESET:
            removeIdleText = true;
        }
        if (removeIdleText) {
            mNm.cancel(STK_NOTIFICATION_ID);
        }
    }

    /**
     * Processes SELECT_ITEM proactive command from the SIM card.
     * 
     * @param cmdDet Command Details object retrieved from the proactive command
     *        object
     * @param devIds Device Identities object retrieved from the proactive
     *        command object
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional 
     *         asynchronous processing is required.
     * @throws ResultException
     */
    private boolean processSelectItem(CtlvCommandDetails cmdDet,
            CtlvDeviceIdentities devIds, List<ComprehensionTlv> ctlvs)
            throws ResultException {

        if (Config.LOGD) {
            Log.d(TAG, "processSelectItem begins");
        }

        Menu menu = new Menu();
        IconId titleIconId = null;
        ItemsIconId itemsIconId = null;
        int iconLoadState = SelectItemParams.LOAD_NO_ICON;
        PresentationType presentType = PresentationType.NOT_SPECIFIED;
        Iterator<ComprehensionTlv> iter = ctlvs.iterator();

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.ALPHA_ID,
                ctlvs);
        if (ctlv != null) {
            menu.title = retrieveAlphaId(ctlv);
        }

        ctlv = searchForTag(ComprehensionTlvTag.TEXT_ATTRIBUTE, ctlvs);
        if (ctlv != null) {
            menu.titleAttrs = retrieveTextAttribute(ctlv);
        }

        while (true) {
            ctlv = searchForNextTag(ComprehensionTlvTag.ITEM, iter);
            if (ctlv != null) {
                menu.items.add(retrieveItem(ctlv));
            } else {
                break;
            }
        }

        // We must have at least one menu item.
        if (menu.items.size() == 0) {
            throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
        }

        ctlv = searchForTag(ComprehensionTlvTag.ITEM_ID, ctlvs);
        if (ctlv != null) {
            // STK items are listed 1...n while list start at 0, need to
            // subtract one.
            menu.defaultItem = retrieveItemId(ctlv) - 1;
        }
        
        ctlv = searchForTag(ComprehensionTlvTag.ICON_ID, ctlvs);
        if (ctlv != null) {
            iconLoadState = SelectItemParams.LOAD_TITLE_ICON;
            titleIconId = retrieveIconId(ctlv);
            menu.titleIconSelfExplanatory = titleIconId.selfExplanatory;
        }

        ctlv = searchForTag(ComprehensionTlvTag.ITEM_ICON_ID_LIST, ctlvs);
        if (ctlv != null) {
            if (iconLoadState == SelectItemParams.LOAD_TITLE_ICON) {
                iconLoadState = SelectItemParams.LOAD_TITLE_ITEMS_ICONS;
            } else {
                iconLoadState = SelectItemParams.LOAD_ITEMS_ICONS;
            }
            itemsIconId = retrieveItemsIconId(ctlv);
            menu.itemsIconSelfExplanatory = itemsIconId.selfExplanatory;
        }

        boolean presentTypeSpecified = (cmdDet.commandQualifier & 0x01) != 0;
        if (presentTypeSpecified) {
            if ((cmdDet.commandQualifier & 0x02) == 0) {
                presentType = PresentationType.DATA_VALUES;
            } else {
                presentType = PresentationType.NAVIGATION_OPTIONS;
            }
        }
        menu.softKeyPreferred = (cmdDet.commandQualifier & 0x04) != 0;
        menu.helpAvailable = (cmdDet.commandQualifier & 0x80) != 0;

        mNextCmdParams = new SelectItemParams(cmdDet, menu, presentType,
                iconLoadState);

        // Load icons data if needed.
        switch(iconLoadState) {
        case SelectItemParams.LOAD_NO_ICON:
            return false;
        case SelectItemParams.LOAD_TITLE_ICON:
            mIconLoader.loadIcon(titleIconId.recordNumber, this
                    .obtainMessage(EVENT_LOAD_ICON_DONE));
            break;
        case SelectItemParams.LOAD_ITEMS_ICONS:
            mIconLoader.loadIcons(itemsIconId.recordNumbers, this
                    .obtainMessage(EVENT_LOAD_ICON_DONE));
            break;
        case SelectItemParams.LOAD_TITLE_ITEMS_ICONS:
            // Create a new array for all the icons (title and items).
            int[] recordNumbers = new int[itemsIconId.recordNumbers.length + 1];
            recordNumbers[0] = titleIconId.recordNumber;
            System.arraycopy(itemsIconId.recordNumbers, 0, recordNumbers, 1,
                    itemsIconId.recordNumbers.length);
            mIconLoader.loadIcons(recordNumbers, this
                    .obtainMessage(EVENT_LOAD_ICON_DONE));
            break;
        }
        return true;
    }

    /**
     * Processes EVENT_NOTIFY message from baseband.
     * 
     * @param cmdDet Command Details object retrieved from the proactive command
     *        object
     * @param devIds Device Identities object retrieved from the proactive
     *        command object
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     */
    synchronized private void processEventNotify(CtlvCommandDetails cmdDet,
            CtlvDeviceIdentities devIds, List<ComprehensionTlv> ctlvs) {

        if (Config.LOGD) {
            Log.d(TAG, "processEventNotify begins");
        }

        String text = null;
        List<TextAttribute> textAttrs = null;

        try {
            ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.ALPHA_ID,
                    ctlvs);
            if (ctlv != null) {
                text = retrieveAlphaId(ctlv);
            } else {
                // No message to show.
                throw new ResultException(ResultCode.REQUIRED_VALUES_MISSING);
            }

            ctlv = searchForTag(ComprehensionTlvTag.TEXT_ATTRIBUTE, ctlvs);
            if (ctlv != null) {
                textAttrs = retrieveTextAttribute(ctlv);
            }
        } catch (ResultException e) {
            // Unable to process command.
            return;
        }

        Toast toast = Toast.makeText(mContext.getApplicationContext(), text,
                Toast.LENGTH_LONG);
        toast.setGravity(Gravity.BOTTOM, 0, 0);
        toast.show();
    }

    /**
     * Processes SET_UP_EVENT_LIST proactive command from the SIM card.
     * 
     * @param cmdDet Command Details object retrieved from the proactive command
     *        object
     * @param devIds Device Identities object retrieved from the proactive
     *        command object
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional 
     *         asynchronous processing is required.
     */
    private boolean processSetUpEventList(CtlvCommandDetails cmdDet,
            CtlvDeviceIdentities devIds, List<ComprehensionTlv> ctlvs) {

        if (Config.LOGD) {
            Log.d(TAG, "processSetUpEventList begins");
        }

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.EVENT_LIST,
                ctlvs);
        if (ctlv != null) {
            try {
                byte[] rawValue = ctlv.getRawValue();
                int valueIndex = ctlv.getValueIndex();
                int valueLen = ctlv.getLength();

            } catch (IndexOutOfBoundsException e) {}
        }
        return true;
    }

    /**
     * Processes LAUNCH_BROWSER proactive command from the SIM card.
     * 
     * @param cmdDet Command Details object retrieved from the proactive command
     *        object
     * @param devIds Device Identities object retrieved from the proactive
     *        command object
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional 
     *         asynchronous processing is required.
     * @throws ResultException
     */
    private boolean processLaunchBrowser(CtlvCommandDetails cmdDet,
            CtlvDeviceIdentities devIds, List<ComprehensionTlv> ctlvs)
            throws ResultException {

        if (Config.LOGD) {
            Log.d(TAG, "processLaunchBrowser begins");
        }

        String url = null;
        String confirmMsg = null;
        List<TextAttribute> confirmMsgAttrs = null;

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.URL, ctlvs);
        if (ctlv != null) {
            try {
                byte[] rawValue = ctlv.getRawValue();
                int valueIndex = ctlv.getValueIndex();
                int valueLen = ctlv.getLength();
                if (valueLen > 0) {
                    url = GsmAlphabet.gsm8BitUnpackedToString(rawValue,
                            valueIndex, valueLen);
                } else {
                    url = null;
                }
            } catch (IndexOutOfBoundsException e) {
                throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
            }
        }

        ctlv = searchForTag(ComprehensionTlvTag.ALPHA_ID, ctlvs);
        if (ctlv != null) {
            confirmMsg = retrieveAlphaId(ctlv);
        }

        ctlv = searchForTag(ComprehensionTlvTag.TEXT_ATTRIBUTE, ctlvs);
        if (ctlv != null) {
            confirmMsgAttrs = retrieveTextAttribute(ctlv);
        }

        LaunchBrowserMode mode;
        switch (cmdDet.commandQualifier) {
        case 0x00:
        default:
            mode = LaunchBrowserMode.LAUNCH_IF_NOT_ALREADY_LAUNCHED;
            break;
        case 0x02:
            mode = LaunchBrowserMode.USE_EXISTING_BROWSER;
            break;
        case 0x03:
            mode = LaunchBrowserMode.LAUNCH_NEW_BROWSER;
            break;
        }

        synchronized (mCmdListenerLock) {
            if (mCmdListener != null) {
                mCmdParams = new CommandParams(cmdDet);
                mState = State.LAUNCH_BROWSER;

                mCmdListener.onLaunchBrowser(url, confirmMsg, confirmMsgAttrs,
                        mode);
                return true;
            } else {
                // '0' means "No specific cause can be given"
                throw new ResultException(
                        ResultCode.TERMINAL_CRNTLY_UNABLE_TO_PROCESS, 0);
            }
        }
    }

    /**
     * Processes PLAY_TONE proactive command from the SIM card.
     * 
     * @param cmdDet Command Details object retrieved from the proactive command
     *        object
     * @param devIds Device Identities object retrieved from the proactive
     *        command object
     * @param ctlvs List of ComprehensionTlv objects following Command Details
     *        object and Device Identities object within the proactive command
     * @return true if the command is processing is pending and additional 
     *         asynchronous processing is required.t
     * @throws ResultException
     */
    private boolean processPlayTone(CtlvCommandDetails cmdDet,
            CtlvDeviceIdentities devIds, List<ComprehensionTlv> ctlvs)
            throws ResultException {

        if (Config.LOGD) {
            Log.d(TAG, "processPlayTone begins");
        }

        Tone tone = null;
        String text = null;
        List<TextAttribute> textAttrs = null;
        Duration duration = null;

        ComprehensionTlv ctlv = searchForTag(ComprehensionTlvTag.TONE, ctlvs);
        if (ctlv != null) {
            // Nothing to do for null objects.
            if (ctlv.getLength() > 0) {
                try {
                    byte[] rawValue = ctlv.getRawValue();
                    int valueIndex = ctlv.getValueIndex();
                    int toneVal = rawValue[valueIndex];
                    tone = Tone.fromInt(toneVal);
                } catch (IndexOutOfBoundsException e) {
                    throw new ResultException(
                            ResultCode.CMD_DATA_NOT_UNDERSTOOD);
                }
            }
        }
        ctlv = searchForTag(ComprehensionTlvTag.ALPHA_ID, ctlvs);
        if (ctlv != null) {
            text = retrieveAlphaId(ctlv);
        }

        ctlv = searchForTag(ComprehensionTlvTag.TEXT_ATTRIBUTE, ctlvs);
        if (ctlv != null) {
            textAttrs = retrieveTextAttribute(ctlv);
        }

        ctlv = searchForTag(ComprehensionTlvTag.DURATION, ctlvs);
        if (ctlv != null) {
            duration = retrieveDuration(ctlv);
        }

        synchronized (mCmdListenerLock) {
            if (mCmdListener != null) {
                mState = State.PLAY_TONE;
                mCmdParams = new CommandParams(cmdDet);
                mCmdListener.onPlayTone(tone, text, textAttrs, duration);
                return true;
            } else {
                // '0' means "No specific cause can be given"
                throw new ResultException(
                        ResultCode.TERMINAL_CRNTLY_UNABLE_TO_PROCESS, 0);
            }
        }
    }

    /**
     * Retrieves text from the Text COMPREHENSION-TLV object, and decodes it
     * into a {@link java.lang.String}.
     * 
     * @param ctlv A Text COMPREHENSION-TLV object
     * @return A {@link java.lang.String} object decoded from the Text object
     * @throws ResultException
     */
    private String retrieveTextString(ComprehensionTlv ctlv)
            throws ResultException {
        byte[] rawValue = ctlv.getRawValue();
        int valueIndex = ctlv.getValueIndex();
        byte codingScheme = 0x00;
        String text = null;
        int textLen = ctlv.getLength();

        // In case the text length is 0, return a null string.
        if (textLen == 0) {
            return text;
        } else {
            // one byte is coding scheme
            textLen -= 1;
        }

        try {
            codingScheme = (byte) (rawValue[valueIndex] & 0x0c);

            if (codingScheme == 0x00) { // GSM 7-bit packed
                text = GsmAlphabet.gsm7BitPackedToString(rawValue,
                        valueIndex + 1, (textLen * 8) / 7);
            } else if (codingScheme == 0x04) { // GSM 8-bit unpacked
                text = GsmAlphabet.gsm8BitUnpackedToString(rawValue,
                        valueIndex + 1, textLen);
            } else if (codingScheme == 0x08) { // UCS2
                text = new String(rawValue, valueIndex + 1, textLen, "UTF-16");
            } else {
                throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
            }

            return text;
        } catch (IndexOutOfBoundsException e) {
            throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
        } catch (UnsupportedEncodingException e) {
            // This should never happen.
            throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
        }
    }

    /**
     * Retrieves Duration information from the Duration COMPREHENSION-TLV
     * object.
     * 
     * @param ctlv A Text Attribute COMPREHENSION-TLV object
     * @return A Duration object
     * @throws ResultException
     */
    private Duration retrieveDuration(ComprehensionTlv ctlv)
            throws ResultException {
        int timeInterval = 0;
        TimeUnit timeUnit = TimeUnit.SECOND;

        byte[] rawValue = ctlv.getRawValue();
        int valueIndex = ctlv.getValueIndex();

        try {
            timeUnit = TimeUnit.values()[(rawValue[valueIndex] & 0xff)];
            timeInterval = rawValue[valueIndex + 1] & 0xff;
        } catch (IndexOutOfBoundsException e) {
            throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
        }
        return new Duration(timeInterval, timeUnit);
    }

    /**
     * Retrieves Item information from the COMPREHENSION-TLV object.
     * 
     * @param ctlv A Text Attribute COMPREHENSION-TLV object
     * @return An Item
     * @throws ResultException
     */
    private Item retrieveItem(ComprehensionTlv ctlv) throws ResultException {
        Item item = null;

        byte[] rawValue = ctlv.getRawValue();
        int valueIndex = ctlv.getValueIndex();
        int length = ctlv.getLength();

        if (length != 0) {
            int textLen = length - 1;

            try {
                int id = rawValue[valueIndex] & 0xff;
                String text = SimUtils.adnStringFieldToString(rawValue,
                        valueIndex + 1, textLen);
                item = new Item(id, text);
            } catch (IndexOutOfBoundsException e) {
                throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
            }
        }

        return item;
    }

    /**
     * Retrieves Item id information from the COMPREHENSION-TLV object.
     * 
     * @param ctlv A Text Attribute COMPREHENSION-TLV object
     * @return An Item id
     * @throws ResultException
     */
    private int retrieveItemId(ComprehensionTlv ctlv) throws ResultException {
        int id = 0;

        byte[] rawValue = ctlv.getRawValue();
        int valueIndex = ctlv.getValueIndex();

        try {
            id = rawValue[valueIndex] & 0xff;
        } catch (IndexOutOfBoundsException e) {
            throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
        }

        return id;
    }

    /**
     * Retrieves icon id from an Icon Identifier COMPREHENSION-TLV object
     *
     * @param ctlv An Icon Identifier COMPREHENSION-TLV object
     * @return IconId instance
     * @throws ResultException
     */
    private IconId retrieveIconId(ComprehensionTlv ctlv) throws ResultException {
        IconId id = new IconId();

        byte[] rawValue = ctlv.getRawValue();
        int valueIndex = ctlv.getValueIndex();
        try {
            id.selfExplanatory = (rawValue[valueIndex++] & 0xff) == 0x00;
            id.recordNumber = rawValue[valueIndex] & 0xff;
        } catch (IndexOutOfBoundsException e) {
            throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
        }

        return id;
    }

    /**
     * Retrieves item icons id from an Icon Identifier List COMPREHENSION-TLV object
     *
     * @param ctlv An Item Icon List Identifier COMPREHENSION-TLV object
     * @return ItemsIconId instance
     * @throws ResultException
     */
    private ItemsIconId retrieveItemsIconId(ComprehensionTlv ctlv)
            throws ResultException{
        Log.d(TAG, "retrieveIconIdList:");
        ItemsIconId id = new ItemsIconId();

        byte[] rawValue = ctlv.getRawValue();
        int valueIndex = ctlv.getValueIndex();
        int numOfItems = ctlv.getLength() - 1;
        id.recordNumbers = new int[numOfItems];

        try {
            // get icon self-explanatory
            id.selfExplanatory = (rawValue[valueIndex++] & 0xff) == 0x00;

            for (int index = 0; index < numOfItems;) {
                id.recordNumbers[index++] = rawValue[valueIndex++];
            }
        } catch (IndexOutOfBoundsException e) {
            throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
        }
        return id;
    }

    /**
     * Retrieves text attribute information from the Text Attribute
     * COMPREHENSION-TLV object.
     * 
     * @param ctlv A Text Attribute COMPREHENSION-TLV object
     * @return A list of TextAttribute objects
     * @throws ResultException
     */
    private List<TextAttribute> retrieveTextAttribute(ComprehensionTlv ctlv)
            throws ResultException {
        ArrayList<TextAttribute> lst = new ArrayList<TextAttribute>();

        byte[] rawValue = ctlv.getRawValue();
        int valueIndex = ctlv.getValueIndex();
        int length = ctlv.getLength();

        if (length != 0) {
            // Each attribute is consisted of four bytes
            int itemCount = length / 4;

            try {
                for (int i = 0; i < itemCount; i++, valueIndex += 4) {
                    int start = rawValue[valueIndex] & 0xff;
                    int textLength = rawValue[valueIndex + 1] & 0xff;
                    int format = rawValue[valueIndex + 2] & 0xff;
                    int colorValue = rawValue[valueIndex + 3] & 0xff;

                    int alignValue = format & 0x03;
                    TextAlignment align = TextAlignment.fromInt(alignValue);

                    int sizeValue = (format >> 2) & 0x03;
                    FontSize size = FontSize.fromInt(sizeValue);
                    if (size == null) {
                        // Font size value is not defined. Use default.
                        size = FontSize.NORMAL;
                    }

                    boolean bold = (format & 0x10) != 0;
                    boolean italic = (format & 0x20) != 0;
                    boolean underlined = (format & 0x40) != 0;
                    boolean strikeThrough = (format & 0x80) != 0;

                    TextColor color = TextColor.fromInt(colorValue);

                    TextAttribute attr = new TextAttribute(start, textLength,
                            align, size, bold, italic, underlined,
                            strikeThrough, color);
                    lst.add(attr);
                }

                return lst;

            } catch (IndexOutOfBoundsException e) {
                throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
            }
        }
        return null;
    }

    /**
     * Retrieves alpha identifier from an Alpha Identifier COMPREHENSION-TLV
     * object.
     * 
     * @param ctlv An Alpha Identifier COMPREHENSION-TLV object
     * @return String corresponding to the alpha identifier
     * @throws ResultException
     */
    private String retrieveAlphaId(ComprehensionTlv ctlv)
            throws ResultException {

        byte[] rawValue = ctlv.getRawValue();
        int valueIndex = ctlv.getValueIndex();
        int length = ctlv.getLength();
        if (length != 0) {
            try {
                return SimUtils.adnStringFieldToString(rawValue, valueIndex,
                        length);
            } catch (IndexOutOfBoundsException e) {
                throw new ResultException(ResultCode.CMD_DATA_NOT_UNDERSTOOD);
            }
        }
        return null;
    }

    /**
     * Handles RIL_UNSOL_STK_EVENT_NOTIFY unsolicited command from RIL.
     * 
     * @param data String containing SAT/USAT commands or responses sent by ME
     *        to SIM or commands handled by ME, in hexadecimal format starting
     *        with first byte of response data or command tag
     */
    private void handleEventNotify(String data) {
        if (Config.LOGD) {
            Log.d(TAG, "handleEventNotify begins");
        }
        byte[] rawData = null;
        BerTlv berTlv = null;
        CtlvCommandDetails cmdDet = null;
        CtlvDeviceIdentities devIds = null;
        CommandType cmdType = null;

        // Nothing to do for empty strings.
        if (data.length() == 0) {
            return;
        }
        rawData = SimUtils.hexStringToBytes(data);
        try {
            berTlv = BerTlv.decode(rawData);
        } catch (ResultException e) {
            // Can't parse command buffer.
            return;
        }

        // Extract command details & Device identities tlv objects list.
        List<ComprehensionTlv> ctlvs = berTlv.getComprehensionTlvs();
        try {
            cmdDet = retrieveCommandDetails(ctlvs);
            devIds = retrieveDeviceIdentities(ctlvs);
        } catch (ResultException e) {
            if (Config.LOGD) {
                Log.d(TAG, "invlaid command details/device identities");
            }
            return;
        }

        // Check to see if we support this command.
        cmdType = CommandType.fromInt(cmdDet.typeOfCommand);
        if (cmdType == null) {
            return;
        }

        // There are two scenarios for EVENT_NOTIFY messages:
        // 1. A proactive command which is partially handled by the baseband and 
        //    requires UI processing from the application. This messages will be
        //    tagged with PROACTIVE COMMAND tag.
        // 2. A notification for an action completed by the baseband. This 
        //    messages will be tagged with UNKNOWN tag and the command type inside 
        //    the Command details object should indicate which action was completed.
        if (berTlv.getTag() == BerTlv.BER_PROACTIVE_COMMAND_TAG) {
            switch (cmdType) {
            case SEND_SS:
            case SEND_USSD:
            case SEND_SMS:
            case SEND_DTMF:
                processEventNotify(cmdDet, devIds, ctlvs);
                break;
            case SET_UP_EVENT_LIST:
                processSetUpEventList(cmdDet, devIds, ctlvs);
                break;
            case SET_UP_CALL:
                processSetupCall(cmdDet, devIds, ctlvs);
                break;
            default:
                // nada
                break;
            }
        }
    }
}
