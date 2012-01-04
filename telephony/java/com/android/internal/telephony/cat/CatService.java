/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.telephony.cat;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemProperties;

import com.android.internal.telephony.IccUtils;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.IccFileHandler;
import com.android.internal.telephony.IccRecords;


import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;

class RilMessage {
    int mId;
    Object mData;
    ResultCode mResCode;

    RilMessage(int msgId, String rawData) {
        mId = msgId;
        mData = rawData;
    }

    RilMessage(RilMessage other) {
        this.mId = other.mId;
        this.mData = other.mData;
        this.mResCode = other.mResCode;
    }
}

/**
 * Class that implements SIM Toolkit Telephony Service. Interacts with the RIL
 * and application.
 *
 * {@hide}
 */
public class CatService extends Handler implements AppInterface {

    // Class members
    private static IccRecords mIccRecords;

    // Service members.
    // Protects singleton instance lazy initialization.
    private static final Object sInstanceLock = new Object();
    private static CatService sInstance;
    private CommandsInterface mCmdIf;
    private Context mContext;
    private CatCmdMessage mCurrntCmd = null;
    private CatCmdMessage mMenuCmd = null;

    private RilMessageDecoder mMsgDecoder = null;
    private boolean mStkAppInstalled = false;

    // Service constants.
    static final int MSG_ID_SESSION_END              = 1;
    static final int MSG_ID_PROACTIVE_COMMAND        = 2;
    static final int MSG_ID_EVENT_NOTIFY             = 3;
    static final int MSG_ID_CALL_SETUP               = 4;
    static final int MSG_ID_REFRESH                  = 5;
    static final int MSG_ID_RESPONSE                 = 6;
    static final int MSG_ID_SIM_READY                = 7;

    static final int MSG_ID_RIL_MSG_DECODED          = 10;

    // Events to signal SIM presence or absent in the device.
    private static final int MSG_ID_ICC_RECORDS_LOADED       = 20;

    private static final int DEV_ID_KEYPAD      = 0x01;
    private static final int DEV_ID_DISPLAY     = 0x02;
    private static final int DEV_ID_EARPIECE    = 0x03;
    private static final int DEV_ID_UICC        = 0x81;
    private static final int DEV_ID_TERMINAL    = 0x82;
    private static final int DEV_ID_NETWORK     = 0x83;

    static final String STK_DEFAULT = "Defualt Message";

    /* Intentionally private for singleton */
    private CatService(CommandsInterface ci, IccRecords ir, Context context,
            IccFileHandler fh, IccCard ic) {
        if (ci == null || ir == null || context == null || fh == null
                || ic == null) {
            throw new NullPointerException(
                    "Service: Input parameters must not be null");
        }
        mCmdIf = ci;
        mContext = context;

        // Get the RilMessagesDecoder for decoding the messages.
        mMsgDecoder = RilMessageDecoder.getInstance(this, fh);

        // Register ril events handling.
        mCmdIf.setOnCatSessionEnd(this, MSG_ID_SESSION_END, null);
        mCmdIf.setOnCatProactiveCmd(this, MSG_ID_PROACTIVE_COMMAND, null);
        mCmdIf.setOnCatEvent(this, MSG_ID_EVENT_NOTIFY, null);
        mCmdIf.setOnCatCallSetUp(this, MSG_ID_CALL_SETUP, null);
        //mCmdIf.setOnSimRefresh(this, MSG_ID_REFRESH, null);

        mIccRecords = ir;

        // Register for SIM ready event.
        mCmdIf.registerForSIMReady(this, MSG_ID_SIM_READY, null);
        mCmdIf.registerForRUIMReady(this, MSG_ID_SIM_READY, null);
        mCmdIf.registerForNVReady(this, MSG_ID_SIM_READY, null);
        mIccRecords.registerForRecordsLoaded(this, MSG_ID_ICC_RECORDS_LOADED, null);

        // Check if STK application is availalbe
        mStkAppInstalled = isStkAppInstalled();

        CatLog.d(this, "Running CAT service. STK app installed:" + mStkAppInstalled);
    }

    public void dispose() {
        mIccRecords.unregisterForRecordsLoaded(this);
        mCmdIf.unSetOnCatSessionEnd(this);
        mCmdIf.unSetOnCatProactiveCmd(this);
        mCmdIf.unSetOnCatEvent(this);
        mCmdIf.unSetOnCatCallSetUp(this);

        this.removeCallbacksAndMessages(null);
    }

    protected void finalize() {
        CatLog.d(this, "Service finalized");
    }

    private void handleRilMsg(RilMessage rilMsg) {
        if (rilMsg == null) {
            return;
        }

        // dispatch messages
        CommandParams cmdParams = null;
        switch (rilMsg.mId) {
        case MSG_ID_EVENT_NOTIFY:
            if (rilMsg.mResCode == ResultCode.OK) {
                cmdParams = (CommandParams) rilMsg.mData;
                if (cmdParams != null) {
                    handleCommand(cmdParams, false);
                }
            }
            break;
        case MSG_ID_PROACTIVE_COMMAND:
            try {
                cmdParams = (CommandParams) rilMsg.mData;
            } catch (ClassCastException e) {
                // for error handling : cast exception
                CatLog.d(this, "Fail to parse proactive command");
                sendTerminalResponse(mCurrntCmd.mCmdDet, ResultCode.CMD_DATA_NOT_UNDERSTOOD,
                                     false, 0x00, null);
                break;
            }
            if (cmdParams != null) {
                if (rilMsg.mResCode == ResultCode.OK) {
                    handleCommand(cmdParams, true);
                } else {
                    // for proactive commands that couldn't be decoded
                    // successfully respond with the code generated by the
                    // message decoder.
                    sendTerminalResponse(cmdParams.cmdDet, rilMsg.mResCode,
                            false, 0, null);
                }
            }
            break;
        case MSG_ID_REFRESH:
            cmdParams = (CommandParams) rilMsg.mData;
            if (cmdParams != null) {
                handleCommand(cmdParams, false);
            }
            break;
        case MSG_ID_SESSION_END:
            handleSessionEnd();
            break;
        case MSG_ID_CALL_SETUP:
            // prior event notify command supplied all the information
            // needed for set up call processing.
            break;
        }
    }

    /**
     * Handles RIL_UNSOL_STK_EVENT_NOTIFY or RIL_UNSOL_STK_PROACTIVE_COMMAND command
     * from RIL.
     * Sends valid proactive command data to the application using intents.
     * RIL_REQUEST_STK_SEND_TERMINAL_RESPONSE will be send back if the command is
     * from RIL_UNSOL_STK_PROACTIVE_COMMAND.
     */
    private void handleCommand(CommandParams cmdParams, boolean isProactiveCmd) {
        CatLog.d(this, cmdParams.getCommandType().name());

        CharSequence message;
        CatCmdMessage cmdMsg = new CatCmdMessage(cmdParams);
        switch (cmdParams.getCommandType()) {
            case SET_UP_MENU:
                if (removeMenu(cmdMsg.getMenu())) {
                    mMenuCmd = null;
                } else {
                    mMenuCmd = cmdMsg;
                }
                sendTerminalResponse(cmdParams.cmdDet, ResultCode.OK, false, 0, null);
                break;
            case DISPLAY_TEXT:
                // when application is not required to respond, send an immediate response.
                if (!cmdMsg.geTextMessage().responseNeeded) {
                    sendTerminalResponse(cmdParams.cmdDet, ResultCode.OK, false, 0, null);
                }
                break;
            case REFRESH:
                // ME side only handles refresh commands which meant to remove IDLE
                // MODE TEXT.
                cmdParams.cmdDet.typeOfCommand = CommandType.SET_UP_IDLE_MODE_TEXT.value();
                break;
            case SET_UP_IDLE_MODE_TEXT:
                sendTerminalResponse(cmdParams.cmdDet, ResultCode.OK, false, 0, null);
                break;
            case PROVIDE_LOCAL_INFORMATION:
                ResponseData resp;
                switch (cmdParams.cmdDet.commandQualifier) {
                    case CommandParamsFactory.DTTZ_SETTING:
                        resp = new DTTZResponseData(null);
                        sendTerminalResponse(cmdParams.cmdDet, ResultCode.OK, false, 0, resp);
                        break;
                    case CommandParamsFactory.LANGUAGE_SETTING:
                        resp = new LanguageResponseData(Locale.getDefault().getLanguage());
                        sendTerminalResponse(cmdParams.cmdDet, ResultCode.OK, false, 0, resp);
                        break;
                    default:
                        sendTerminalResponse(cmdParams.cmdDet, ResultCode.OK, false, 0, null);
                }
                // No need to start STK app here.
                return;
            case LAUNCH_BROWSER:
                if ((((LaunchBrowserParams) cmdParams).confirmMsg.text != null)
                        && (((LaunchBrowserParams) cmdParams).confirmMsg.text.equals(STK_DEFAULT))) {
                    message = mContext.getText(com.android.internal.R.string.launchBrowserDefault);
                    ((LaunchBrowserParams) cmdParams).confirmMsg.text = message.toString();
                }
                break;
            case SELECT_ITEM:
            case GET_INPUT:
            case GET_INKEY:
                break;
            case SEND_DTMF:
            case SEND_SMS:
            case SEND_SS:
            case SEND_USSD:
                if ((((DisplayTextParams)cmdParams).textMsg.text != null)
                        && (((DisplayTextParams)cmdParams).textMsg.text.equals(STK_DEFAULT))) {
                    message = mContext.getText(com.android.internal.R.string.sending);
                    ((DisplayTextParams)cmdParams).textMsg.text = message.toString();
                }
                break;
            case PLAY_TONE:
                break;
            case SET_UP_CALL:
                if ((((CallSetupParams) cmdParams).confirmMsg.text != null)
                        && (((CallSetupParams) cmdParams).confirmMsg.text.equals(STK_DEFAULT))) {
                    message = mContext.getText(com.android.internal.R.string.SetupCallDefault);
                    ((CallSetupParams) cmdParams).confirmMsg.text = message.toString();
                }
                break;
            case OPEN_CHANNEL:
            case CLOSE_CHANNEL:
            case RECEIVE_DATA:
            case SEND_DATA:
                BIPClientParams cmd = (BIPClientParams) cmdParams;
                if (cmd.bHasAlphaId && (cmd.textMsg.text == null)) {
                    CatLog.d(this, "cmd " + cmdParams.getCommandType() + " with null alpha id");
                    // If alpha length is zero, we just respond with OK.
                    if (isProactiveCmd) {
                        sendTerminalResponse(cmdParams.cmdDet, ResultCode.OK, false, 0, null);
                    }
                    return;
                }
                // Respond with permanent failure to avoid retry if STK app is not present.
                if (!mStkAppInstalled) {
                    CatLog.d(this, "No STK application found.");
                    if (isProactiveCmd) {
                        sendTerminalResponse(cmdParams.cmdDet,
                                             ResultCode.BEYOND_TERMINAL_CAPABILITY,
                                             false, 0, null);
                        return;
                    }
                }
                /*
                 * CLOSE_CHANNEL, RECEIVE_DATA and SEND_DATA can be delivered by
                 * either PROACTIVE_COMMAND or EVENT_NOTIFY.
                 * If PROACTIVE_COMMAND is used for those commands, send terminal
                 * response here.
                 */
                if (isProactiveCmd &&
                    ((cmdParams.getCommandType() == CommandType.CLOSE_CHANNEL) ||
                     (cmdParams.getCommandType() == CommandType.RECEIVE_DATA) ||
                     (cmdParams.getCommandType() == CommandType.SEND_DATA))) {
                    sendTerminalResponse(cmdParams.cmdDet, ResultCode.OK, false, 0, null);
                }
                break;
            default:
                CatLog.d(this, "Unsupported command");
                return;
        }
        mCurrntCmd = cmdMsg;
        Intent intent = new Intent(AppInterface.CAT_CMD_ACTION);
        intent.putExtra("STK CMD", cmdMsg);
        mContext.sendBroadcast(intent);
    }

    /**
     * Handles RIL_UNSOL_STK_SESSION_END unsolicited command from RIL.
     *
     */
    private void handleSessionEnd() {
        CatLog.d(this, "SESSION END");

        mCurrntCmd = mMenuCmd;
        Intent intent = new Intent(AppInterface.CAT_SESSION_END_ACTION);
        mContext.sendBroadcast(intent);
    }

    private void sendTerminalResponse(CommandDetails cmdDet,
            ResultCode resultCode, boolean includeAdditionalInfo,
            int additionalInfo, ResponseData resp) {

        if (cmdDet == null) {
            return;
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        Input cmdInput = null;
        if (mCurrntCmd != null) {
            cmdInput = mCurrntCmd.geInput();
        }

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
        // According to TS102.223/TS31.111 section 6.8 Structure of
        // TERMINAL RESPONSE, "For all SIMPLE-TLV objects with Min=N,
        // the ME should set the CR(comprehension required) flag to
        // comprehension not required.(CR=0)"
        // Since DEVICE_IDENTITIES and DURATION TLVs have Min=N,
        // the CR flag is not set.
        tag = ComprehensionTlvTag.DEVICE_IDENTITIES.value();
        buf.write(tag);
        buf.write(0x02); // length
        buf.write(DEV_ID_TERMINAL); // source device id
        buf.write(DEV_ID_UICC); // destination device id

        // result
        tag = 0x80 | ComprehensionTlvTag.RESULT.value();
        buf.write(tag);
        int length = includeAdditionalInfo ? 2 : 1;
        buf.write(length);
        buf.write(resultCode.value());

        // additional info
        if (includeAdditionalInfo) {
            buf.write(additionalInfo);
        }

        // Fill optional data for each corresponding command
        if (resp != null) {
            resp.format(buf);
        } else {
            encodeOptionalTags(cmdDet, resultCode, cmdInput, buf);
        }

        byte[] rawData = buf.toByteArray();
        String hexString = IccUtils.bytesToHexString(rawData);
        if (false) {
            CatLog.d(this, "TERMINAL RESPONSE: " + hexString);
        }

        mCmdIf.sendTerminalResponse(hexString, null);
    }

    private void encodeOptionalTags(CommandDetails cmdDet,
            ResultCode resultCode, Input cmdInput, ByteArrayOutputStream buf) {
        CommandType cmdType = AppInterface.CommandType.fromInt(cmdDet.typeOfCommand);
        if (cmdType != null) {
            switch (cmdType) {
                case GET_INKEY:
                    // ETSI TS 102 384,27.22.4.2.8.4.2.
                    // If it is a response for GET_INKEY command and the response timeout
                    // occured, then add DURATION TLV for variable timeout case.
                    if ((resultCode.value() == ResultCode.NO_RESPONSE_FROM_USER.value()) &&
                        (cmdInput != null) && (cmdInput.duration != null)) {
                        getInKeyResponse(buf, cmdInput);
                    }
                    break;
                case PROVIDE_LOCAL_INFORMATION:
                    if ((cmdDet.commandQualifier == CommandParamsFactory.LANGUAGE_SETTING) &&
                        (resultCode.value() == ResultCode.OK.value())) {
                        getPliResponse(buf);
                    }
                    break;
                default:
                    CatLog.d(this, "encodeOptionalTags() Unsupported Cmd:" + cmdDet.typeOfCommand);
                    break;
            }
        } else {
            CatLog.d(this, "encodeOptionalTags() bad Cmd:" + cmdDet.typeOfCommand);
        }
    }

    private void getInKeyResponse(ByteArrayOutputStream buf, Input cmdInput) {
        int tag = ComprehensionTlvTag.DURATION.value();

        buf.write(tag);
        buf.write(0x02); // length
        buf.write(cmdInput.duration.timeUnit.SECOND.value()); // Time (Unit,Seconds)
        buf.write(cmdInput.duration.timeInterval); // Time Duration
    }

    private void getPliResponse(ByteArrayOutputStream buf) {

        // Locale Language Setting
        String lang = SystemProperties.get("persist.sys.language");

        if (lang != null) {
            // tag
            int tag = ComprehensionTlvTag.LANGUAGE.value();
            buf.write(tag);
            ResponseData.writeLength(buf, lang.length());
            buf.write(lang.getBytes(), 0, lang.length());
        }
    }

    private void sendMenuSelection(int menuId, boolean helpRequired) {

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

        String hexString = IccUtils.bytesToHexString(rawData);

        mCmdIf.sendEnvelope(hexString, null);
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

        String hexString = IccUtils.bytesToHexString(rawData);

        mCmdIf.sendEnvelope(hexString, null);
    }

    /**
     * Used for instantiating/updating the Service from the GsmPhone or CdmaPhone constructor.
     *
     * @param ci CommandsInterface object
     * @param ir IccRecords object
     * @param context phone app context
     * @param fh Icc file handler
     * @param ic Icc card
     * @return The only Service object in the system
     */
    public static CatService getInstance(CommandsInterface ci, IccRecords ir,
            Context context, IccFileHandler fh, IccCard ic) {
        synchronized (sInstanceLock) {
            if (sInstance == null) {
                if (ci == null || ir == null || context == null || fh == null
                        || ic == null) {
                    return null;
                }
                HandlerThread thread = new HandlerThread("Cat Telephony service");
                thread.start();
                sInstance = new CatService(ci, ir, context, fh, ic);
                CatLog.d(sInstance, "NEW sInstance");
            } else if ((ir != null) && (mIccRecords != ir)) {
                CatLog.d(sInstance, "Reinitialize the Service with SIMRecords");
                mIccRecords = ir;

                // re-Register for SIM ready event.
                mIccRecords.registerForRecordsLoaded(sInstance, MSG_ID_ICC_RECORDS_LOADED, null);
                CatLog.d(sInstance, "sr changed reinitialize and return current sInstance");
            } else {
                CatLog.d(sInstance, "Return current sInstance");
            }
            return sInstance;
        }
    }

    /**
     * Used by application to get an AppInterface object.
     *
     * @return The only Service object in the system
     */
    public static AppInterface getInstance() {
        return getInstance(null, null, null, null, null);
    }

    @Override
    public void handleMessage(Message msg) {

        switch (msg.what) {
        case MSG_ID_SESSION_END:
        case MSG_ID_PROACTIVE_COMMAND:
        case MSG_ID_EVENT_NOTIFY:
        case MSG_ID_REFRESH:
            CatLog.d(this, "ril message arrived");
            String data = null;
            if (msg.obj != null) {
                AsyncResult ar = (AsyncResult) msg.obj;
                if (ar != null && ar.result != null) {
                    try {
                        data = (String) ar.result;
                    } catch (ClassCastException e) {
                        break;
                    }
                }
            }
            mMsgDecoder.sendStartDecodingMessageParams(new RilMessage(msg.what, data));
            break;
        case MSG_ID_CALL_SETUP:
            mMsgDecoder.sendStartDecodingMessageParams(new RilMessage(msg.what, null));
            break;
        case MSG_ID_ICC_RECORDS_LOADED:
            break;
        case MSG_ID_RIL_MSG_DECODED:
            handleRilMsg((RilMessage) msg.obj);
            break;
        case MSG_ID_RESPONSE:
            handleCmdResponse((CatResponseMessage) msg.obj);
            break;
        case MSG_ID_SIM_READY:
            CatLog.d(this, "SIM ready. Reporting STK service running now...");
            mCmdIf.reportStkServiceIsRunning(null);
            break;
        default:
            throw new AssertionError("Unrecognized CAT command: " + msg.what);
        }
    }

    public synchronized void onCmdResponse(CatResponseMessage resMsg) {
        if (resMsg == null) {
            return;
        }
        // queue a response message.
        Message msg = this.obtainMessage(MSG_ID_RESPONSE, resMsg);
        msg.sendToTarget();
    }

    private boolean validateResponse(CatResponseMessage resMsg) {
        if (mCurrntCmd != null) {
            return (resMsg.cmdDet.compareTo(mCurrntCmd.mCmdDet));
        }
        return false;
    }

    private boolean removeMenu(Menu menu) {
        try {
            if (menu.items.size() == 1 && menu.items.get(0) == null) {
                return true;
            }
        } catch (NullPointerException e) {
            CatLog.d(this, "Unable to get Menu's items size");
            return true;
        }
        return false;
    }

    private void handleCmdResponse(CatResponseMessage resMsg) {
        // Make sure the response details match the last valid command. An invalid
        // response is a one that doesn't have a corresponding proactive command
        // and sending it can "confuse" the baseband/ril.
        // One reason for out of order responses can be UI glitches. For example,
        // if the application launch an activity, and that activity is stored
        // by the framework inside the history stack. That activity will be
        // available for relaunch using the latest application dialog
        // (long press on the home button). Relaunching that activity can send
        // the same command's result again to the CatService and can cause it to
        // get out of sync with the SIM.
        if (!validateResponse(resMsg)) {
            return;
        }
        ResponseData resp = null;
        boolean helpRequired = false;
        CommandDetails cmdDet = resMsg.getCmdDetails();

        switch (resMsg.resCode) {
        case HELP_INFO_REQUIRED:
            helpRequired = true;
            // fall through
        case OK:
        case PRFRMD_WITH_PARTIAL_COMPREHENSION:
        case PRFRMD_WITH_MISSING_INFO:
        case PRFRMD_WITH_ADDITIONAL_EFS_READ:
        case PRFRMD_ICON_NOT_DISPLAYED:
        case PRFRMD_MODIFIED_BY_NAA:
        case PRFRMD_LIMITED_SERVICE:
        case PRFRMD_WITH_MODIFICATION:
        case PRFRMD_NAA_NOT_ACTIVE:
        case PRFRMD_TONE_NOT_PLAYED:
            switch (AppInterface.CommandType.fromInt(cmdDet.typeOfCommand)) {
            case SET_UP_MENU:
                helpRequired = resMsg.resCode == ResultCode.HELP_INFO_REQUIRED;
                sendMenuSelection(resMsg.usersMenuSelection, helpRequired);
                return;
            case SELECT_ITEM:
                resp = new SelectItemResponseData(resMsg.usersMenuSelection);
                break;
            case GET_INPUT:
            case GET_INKEY:
                Input input = mCurrntCmd.geInput();
                if (!input.yesNo) {
                    // when help is requested there is no need to send the text
                    // string object.
                    if (!helpRequired) {
                        resp = new GetInkeyInputResponseData(resMsg.usersInput,
                                input.ucs2, input.packed);
                    }
                } else {
                    resp = new GetInkeyInputResponseData(
                            resMsg.usersYesNoSelection);
                }
                break;
            case DISPLAY_TEXT:
            case LAUNCH_BROWSER:
                break;
            case SET_UP_CALL:
                mCmdIf.handleCallSetupRequestFromSim(resMsg.usersConfirm, null);
                // No need to send terminal response for SET UP CALL. The user's
                // confirmation result is send back using a dedicated ril message
                // invoked by the CommandInterface call above.
                mCurrntCmd = null;
                return;
            }
            break;
        case NO_RESPONSE_FROM_USER:
        case UICC_SESSION_TERM_BY_USER:
        case BACKWARD_MOVE_BY_USER:
        case USER_NOT_ACCEPT:
            resp = null;
            break;
        default:
            return;
        }
        sendTerminalResponse(cmdDet, resMsg.resCode, false, 0, resp);
        mCurrntCmd = null;
    }

    private boolean isStkAppInstalled() {
        Intent intent = new Intent(AppInterface.CAT_CMD_ACTION);
        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> broadcastReceivers =
                            pm.queryBroadcastReceivers(intent, PackageManager.GET_META_DATA);
        int numReceiver = broadcastReceivers == null ? 0 : broadcastReceivers.size();

        return (numReceiver > 0);
    }
}
