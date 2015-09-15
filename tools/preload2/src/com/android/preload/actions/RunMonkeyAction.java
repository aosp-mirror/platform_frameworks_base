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

package com.android.preload.actions;

import com.android.ddmlib.IDevice;
import com.android.preload.DeviceUtils;
import com.android.preload.DumpData;
import com.android.preload.DumpTableModel;
import com.android.preload.Main;

import java.awt.event.ActionEvent;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.swing.AbstractAction;

public class RunMonkeyAction extends AbstractAction implements DeviceSpecific {

    private final static String DEFAULT_MONKEY_PACKAGES =
            "com.android.calendar,com.android.gallery3d";

    private IDevice device;
    private DumpTableModel dataTableModel;

    public RunMonkeyAction(IDevice device, DumpTableModel dataTableModel) {
        super("Run monkey");
        this.device = device;
        this.dataTableModel = dataTableModel;
    }

    @Override
    public void setDevice(IDevice device) {
        this.device = device;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        String packages = Main.getUI().showInputDialog("Please enter packages name to run with"
                + " the monkey, or leave empty for default.");
        if (packages == null) {
            return;
        }
        if (packages.isEmpty()) {
            packages = DEFAULT_MONKEY_PACKAGES;
        }
        new Thread(new RunMonkeyRunnable(packages)).start();
    }

    private class RunMonkeyRunnable implements Runnable {

        private String packages;
        private final static int ITERATIONS = 1000;

        public RunMonkeyRunnable(String packages) {
            this.packages = packages;
        }

        @Override
        public void run() {
            Main.getUI().showWaitDialog();

            try {
                String pkgs[] = packages.split(",");

                for (String pkg : pkgs) {
                    Main.getUI().updateWaitDialog("Running monkey on " + pkg);

                    try {
                        // Stop running app.
                        forceStop(pkg);

                        // Little bit of breather here.
                        try {
                            Thread.sleep(1000);
                        } catch (Exception e) {
                        }

                        DeviceUtils.doShell(device, "monkey -p " + pkg + " " + ITERATIONS, 1,
                                TimeUnit.MINUTES);

                        Main.getUI().updateWaitDialog("Retrieving heap data for " + pkg);
                        Map<String, String> data = Main.findAndGetClassData(device, pkg);
                        DumpData dumpData = new DumpData(pkg, data, new Date());
                        dataTableModel.addData(dumpData);
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        // Stop running app.
                        forceStop(pkg);
                    }
                }
            } finally {
                Main.getUI().hideWaitDialog();
            }
        }

        private void forceStop(String packageName) {
            // Stop running app.
            DeviceUtils.doShell(device, "force-stop " + packageName, 5, TimeUnit.SECONDS);
            DeviceUtils.doShell(device, "kill " + packageName, 5, TimeUnit.SECONDS);
            DeviceUtils.doShell(device, "kill `pid " + packageName + "`", 5, TimeUnit.SECONDS);
        }
    }
}