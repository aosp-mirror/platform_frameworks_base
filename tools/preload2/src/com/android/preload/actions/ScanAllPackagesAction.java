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

import com.android.ddmlib.Client;
import com.android.ddmlib.IDevice;
import com.android.preload.ClientUtils;
import com.android.preload.DumpData;
import com.android.preload.DumpTableModel;
import com.android.preload.Main;

import java.util.Date;
import java.util.Map;

public class ScanAllPackagesAction extends AbstractThreadedDeviceSpecificAction {

    private ClientUtils clientUtils;
    private DumpTableModel dataTableModel;

    public ScanAllPackagesAction(ClientUtils utils, IDevice device, DumpTableModel dataTableModel) {
        super("Scan all packages", device);
        this.clientUtils = utils;
        this.dataTableModel = dataTableModel;
    }

    @Override
    public void run() {
        Main.getUI().showWaitDialog();

        try {
            Client[] clients = clientUtils.findAllClients(device);
            for (Client c : clients) {
                String pkg = c.getClientData().getClientDescription();
                Main.getUI().showWaitDialog();
                Main.getUI().updateWaitDialog("Retrieving heap data for " + pkg);

                try {
                    Map<String, String> data = Main.getClassDataRetriever().getClassData(c);
                    DumpData dumpData = new DumpData(pkg, data, new Date());
                    dataTableModel.addData(dumpData);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } finally {
            Main.getUI().hideWaitDialog();
        }
    }
}