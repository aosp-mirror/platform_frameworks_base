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

package com.android.commands.svc;

import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.nfc.INfcAdapter;
import android.os.RemoteException;
import android.os.ServiceManager;

public class NfcCommand extends Svc.Command {

    public NfcCommand() {
        super("nfc");
    }

    @Override
    public String shortHelp() {
        return "Control NFC functions";
    }

    @Override
    public String longHelp() {
        return shortHelp() + "\n"
                + "\n"
                + "usage: svc nfc [enable|disable]\n"
                + "         Turn NFC on or off.\n\n";
    }

    @Override
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
                IPackageManager pm = IPackageManager.Stub.asInterface(
                        ServiceManager.getService("package"));
                try {
                    if (pm.hasSystemFeature(PackageManager.FEATURE_NFC)) {
                        INfcAdapter nfc = INfcAdapter.Stub
                                .asInterface(ServiceManager.getService(Context.NFC_SERVICE));
                        try {
                            if (flag) {
                                nfc.enable();
                            } else
                                nfc.disable(true);
                        } catch (RemoteException e) {
                            System.err.println("NFC operation failed: " + e);
                        }
                    } else {
                        System.err.println("NFC feature not supported.");
                    }
                } catch (RemoteException e) {
                    System.err.println("RemoteException while calling PackageManager, is the "
                            + "system running?");
                }
                return;
            }
        }
        System.err.println(longHelp());
    }

}
