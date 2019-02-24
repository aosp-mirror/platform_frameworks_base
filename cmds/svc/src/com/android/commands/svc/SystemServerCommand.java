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

package com.android.commands.svc;

import android.app.ActivityManager;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;

public class SystemServerCommand extends Svc.Command {
    public SystemServerCommand() {
        super("system-server");
    }

    @Override
    public String shortHelp() {
        return "System server process related command";
    }

    @Override
    public String longHelp() {
        return shortHelp() + "\n"
                + "\n"
                + "usage: system-server wait-for-crash\n"
                + "         Wait until the system server process crashes.\n\n";
    }

    private void waitForCrash() throws Exception {
        ParcelFileDescriptor fd = ActivityManager.getService().getLifeMonitor();
        if (fd == null) {
            System.err.println("Unable to get life monitor.");
            return;
        }
        System.out.println("Waiting for the system server process to die...");
        new FileInputStream(fd.getFileDescriptor()).read();
    }

    @Override
    public void run(String[] args) {
        try {
            if (args.length > 1) {
                switch (args[1]) {
                    case "wait-for-crash":
                        waitForCrash();
                        return;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.err.println(longHelp());
    }
}
