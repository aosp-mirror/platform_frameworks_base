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

package com.android.internal.telephony.gsm;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

import android.database.Cursor;
import android.os.Message;
import android.os.SystemProperties;
import android.os.SystemService;
import com.android.internal.telephony.gsm.DataConnectionTracker.State;
import com.android.internal.util.ArrayUtils;
import android.util.Log;

/**
 * Represents a PPP link.
 * 
 * Ideally this would be managed by the RIL implementation, but
 * we currently have implementations where this is not the case.
 *
 * {@hide}
 */
final class PppLink extends DataLink implements DataLinkInterface {
    private static final String LOG_TAG = "GSM";

    static final String PATH_PPP_OPERSTATE = "/sys/class/net/ppp0/operstate";
    static final String SERVICE_PPPD_GPRS = "pppd_gprs";
    static final String PROPERTY_PPPD_EXIT_CODE = "net.gprs.ppp-exit";
    static final int POLL_SYSFS_MILLIS = 5 * 1000;
    static final int EVENT_POLL_DATA_CONNECTION = 2;
    static final int EVENT_PPP_OPERSTATE_CHANGED = 8;
    static final int EVENT_PPP_PIDFILE_CHANGED = 9;

    static final byte[] UP_ASCII_STRING = new byte[] {
        'u' & 0xff,
        'p' & 0xff,
    };
    static final byte[] DOWN_ASCII_STRING = new byte[] {
        'd' & 0xff,
        'o' & 0xff,
        'w' & 0xff,
        'n' & 0xff,
    };
    static final byte[] UNKNOWN_ASCII_STRING = new byte[] {
        'u' & 0xff,
        'n' & 0xff,
        'k' & 0xff,
        'n' & 0xff,
        'o' & 0xff,
        'w' & 0xff,
        'n' & 0xff,
    };
    private final byte[] mCheckPPPBuffer = new byte[32];

    int lastPppdExitCode = EXIT_OK;


    PppLink(DataConnectionTracker dc) {
        super(dc);
    }

    public void connect() {
        // Clear any previous exit code
        SystemProperties.set(PROPERTY_PPPD_EXIT_CODE, "");
        SystemService.start(SERVICE_PPPD_GPRS);
        removeMessages(EVENT_POLL_DATA_CONNECTION);
        Message poll = obtainMessage();
        poll.what = EVENT_POLL_DATA_CONNECTION;
        sendMessageDelayed(poll, POLL_SYSFS_MILLIS);
    }

    public void disconnect() {
        SystemService.stop(SERVICE_PPPD_GPRS);
    }

    public int getLastLinkExitCode() {
        return lastPppdExitCode;
    }

    public void setPasswordInfo(Cursor cursor) {
        StringBuilder builder = new StringBuilder();
        FileOutputStream output = null;

        try {
            output = new FileOutputStream("/etc/ppp/pap-secrets");
            if (cursor.moveToFirst()) {
                do {
                    builder.append(cursor.getString(cursor.getColumnIndex("user")));
                    builder.append(" ");
                    builder.append(cursor.getString(cursor.getColumnIndex("server")));
                    builder.append(" ");
                    builder.append(cursor.getString(cursor.getColumnIndex("password")));
                    builder.append("\n");
                } while (cursor.moveToNext());
            }

            output.write(builder.toString().getBytes());
        } catch (java.io.IOException e) {
            Log.e(LOG_TAG, "Could not create '/etc/ppp/pap-secrets'", e);
        } finally {
            try {
                if (output != null) output.close();
            } catch (java.io.IOException e) {
                Log.e(LOG_TAG, "Error closing '/etc/ppp/pap-secrets'", e);
            }
        }
    }

    public void handleMessage (Message msg) {

        switch (msg.what) {

            case EVENT_POLL_DATA_CONNECTION:
                checkPPP();

                // keep polling in case interface goes down
                if (dataConnection.state != State.IDLE) {                    
                    Message poll = obtainMessage();
                    poll.what = EVENT_POLL_DATA_CONNECTION;
                    sendMessageDelayed(poll, POLL_SYSFS_MILLIS);
                }
                break;
        }
    }

    private void checkPPP() {
        boolean connecting = (dataConnection.state == State.CONNECTING);

        try {
            RandomAccessFile file = new RandomAccessFile(PATH_PPP_OPERSTATE, "r");
            file.read(mCheckPPPBuffer);
            file.close();

            // Unfortunately, we're currently seeing operstate
            // "unknown" where one might otherwise expect "up"
            if (ArrayUtils.equals(mCheckPPPBuffer, UP_ASCII_STRING, UP_ASCII_STRING.length)
                    || ArrayUtils.equals(mCheckPPPBuffer, UNKNOWN_ASCII_STRING,
                            UNKNOWN_ASCII_STRING.length) 
                            && dataConnection.state == State.CONNECTING) {

                Log.i(LOG_TAG, 
                "found ppp interface. Notifying GPRS connected");

                if (mLinkChangeRegistrant != null) {
                    mLinkChangeRegistrant.notifyResult(LinkState.LINK_UP);
                }

                connecting = false;
            } else if (dataConnection.state == State.CONNECTED 
                    && ArrayUtils.equals(mCheckPPPBuffer, DOWN_ASCII_STRING,
                            DOWN_ASCII_STRING.length)) {

                Log.i(LOG_TAG, 
                "ppp interface went down. Reconnecting...");

                if (mLinkChangeRegistrant != null) {
                    mLinkChangeRegistrant.notifyResult(LinkState.LINK_DOWN);
                }
            }                                    
        } catch (IOException ex) {
            if (! (ex instanceof FileNotFoundException)) {
                Log.i(LOG_TAG, "Poll ppp0 ex " + ex.toString());
            }

            if (dataConnection.state == State.CONNECTED &&
                    mLinkChangeRegistrant != null) {
                mLinkChangeRegistrant.notifyResult(LinkState.LINK_DOWN);
            }
        }

        // CONNECTING means pppd has started but negotiation is not complete
        // If we're still CONNECTING here, check to see if pppd has
        // already exited
        if (connecting) {
            String exitCode;

            exitCode = SystemProperties.get(PROPERTY_PPPD_EXIT_CODE, "");

            if (!exitCode.equals("")) {
                // pppd has exited. Let's figure out why
                lastPppdExitCode = Integer.parseInt(exitCode);

                Log.d(LOG_TAG,"pppd exited with " + exitCode);

                if (mLinkChangeRegistrant != null) {
                    mLinkChangeRegistrant.notifyResult(LinkState.LINK_EXITED);
                }
            }
        }

    }
}
