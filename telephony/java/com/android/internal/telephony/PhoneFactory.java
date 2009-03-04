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

package com.android.internal.telephony;

import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import java.net.InetSocketAddress;

import java.util.Collections;

import android.util.Log;
import com.android.internal.telephony.gsm.GSMPhone;
import com.android.internal.telephony.gsm.RIL;
import com.android.internal.telephony.test.ModelInterpreter;
import com.android.internal.telephony.test.SimulatedCommands;
import android.os.Looper;
import android.os.SystemProperties;
import android.content.Context;
import android.content.Intent;
import android.net.LocalServerSocket;
import android.app.ActivityManagerNative;

/**
 * {@hide}
 */
public class PhoneFactory
{
    static final String LOG_TAG="GSM";

    static final int SOCKET_OPEN_RETRY_MILLIS = 2 * 1000;
    static final int SOCKET_OPEN_MAX_RETRY = 3;
    //***** Class Variables 

    static private ArrayList<Phone> sPhones = new ArrayList<Phone>();

    static private boolean sMadeDefaults = false;
    static private PhoneNotifier sPhoneNotifier;
    static private Looper sLooper;

    static private Object testMailbox;

    //***** Class Methods

    private static void
    useNewRIL(Context context)
    {
        ModelInterpreter mi = null;
        GSMPhone phone;

        try {
            if (false) {
                mi = new ModelInterpreter(new InetSocketAddress("127.0.0.1", 6502));
            }
            
            phone = new GSMPhone(context, new RIL(context), sPhoneNotifier);

            registerPhone (phone);
        } catch (IOException ex) {
            Log.e(LOG_TAG, "Error creating ModelInterpreter", ex);
        }
    }


    /**
     * FIXME replace this with some other way of making these
     * instances
     */
    public static void 
    makeDefaultPhones(Context context)
    {
        synchronized(Phone.class) {        
            if (!sMadeDefaults) {  
                sLooper = Looper.myLooper();

                if (sLooper == null) {
                    throw new RuntimeException(
                        "PhoneFactory.makeDefaultPhones must be called from Looper thread");
                }

                int retryCount = 0;
                for(;;) {
                    boolean hasException = false;
                    retryCount ++;

                    try {
                        // use UNIX domain socket to
                        // prevent subsequent initialization
                        new LocalServerSocket("com.android.internal.telephony");
                    } catch (java.io.IOException ex) {
                        hasException = true;
                    }

                    if ( !hasException ) {
                        break;
                    } else if (retryCount > SOCKET_OPEN_MAX_RETRY) {
                        throw new RuntimeException("PhoneFactory probably already running");
                    }else {
                        try {
                            Thread.sleep(SOCKET_OPEN_RETRY_MILLIS);
                        } catch (InterruptedException er) {
                        }
                    }
                }

                sPhoneNotifier = new DefaultPhoneNotifier();

                if ((SystemProperties.get("ro.radio.noril","")).equals("")) {
                    useNewRIL(context);
                } else {
                    GSMPhone phone;
                    phone = new GSMPhone(context, new SimulatedCommands(), sPhoneNotifier);
                    registerPhone (phone);
                }

                sMadeDefaults = true;
            }
        }
    }

    public static Phone getDefaultPhone()
    {
        if (!sMadeDefaults) {
            throw new IllegalStateException("Default phones haven't been made yet!");
        }

        if (sLooper != Looper.myLooper()) {
            throw new RuntimeException(
                "PhoneFactory.getDefaultPhone must be called from Looper thread");
        }

        synchronized (sPhones) {
            return sPhones.isEmpty() ? null : sPhones.get(0);
        }
    }
    
    public static void registerPhone(Phone p)
    {
        if (sLooper != Looper.myLooper()) {
            throw new RuntimeException(
                "PhoneFactory.getDefaultPhone must be called from Looper thread");
        }
        synchronized (sPhones) {
            sPhones.add(p);
        }
    }
}

