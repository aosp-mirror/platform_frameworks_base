/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.bluetooth.BluetoothAdapter;
import android.os.RemoteException;

public class BluetoothCommand extends Svc.Command {

    public BluetoothCommand() {
        super("bluetooth");
    }

    @Override
    public String shortHelp() {
        return "Control Bluetooth service";
    }

    @Override
    public String longHelp() {
        return shortHelp() + "\n"
                + "\n"
                + "usage: svc bluetooth [enable|disable]\n"
                + "         Turn Bluetooth on or off.\n\n";
    }

    @Override
    public void run(String[] args) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

        if (adapter == null) {
            System.err.println("Got a null BluetoothAdapter, is the system running?");
            return;
        }

        if (args.length == 2 && "enable".equals(args[1])) {
            adapter.enable();
        } else if (args.length == 2 && "disable".equals(args[1])) {
            adapter.disable();
        } else {
            System.err.println(longHelp());
        }
    }
}
