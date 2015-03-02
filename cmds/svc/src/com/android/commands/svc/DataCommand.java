/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.commands.svc;

import android.os.ServiceManager;
import android.os.RemoteException;
import android.content.Context;
import com.android.internal.telephony.ITelephony;

public class DataCommand extends Svc.Command {
    public DataCommand() {
        super("data");
    }

    public String shortHelp() {
        return "Control mobile data connectivity";
    }

    public String longHelp() {
        return shortHelp() + "\n"
                + "\n"
                + "usage: svc data [enable|disable]\n"
                + "         Turn mobile data on or off.\n\n";
    }

    public void run(String[] args) {
        boolean validCommand = false;
        if (args.length >= 2) {
            boolean flag = false;
            if ("enable".equals(args[1])) {
                flag = true;
                validCommand = true;
            } else if ("disable".equals(args[1])) {
                flag = false;
                validCommand = true;
            }
            if (validCommand) {
                ITelephony phoneMgr
                        = ITelephony.Stub.asInterface(ServiceManager.getService(Context.TELEPHONY_SERVICE));
                try {
                    if (flag) {
                        phoneMgr.enableDataConnectivity();
                    } else
                        phoneMgr.disableDataConnectivity();
                }
                catch (RemoteException e) {
                    System.err.println("Mobile data operation failed: " + e);
                }
                return;
            }
        }
        System.err.println(longHelp());
    }
}
