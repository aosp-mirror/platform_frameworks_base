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

public class ScanPackageAction extends AbstractThreadedDeviceSpecificAction {

    private ClientUtils clientUtils;
    private DumpTableModel dataTableModel;

    public ScanPackageAction(ClientUtils utils, IDevice device, DumpTableModel dataTableModel) {
        super("Scan package", device);
        this.clientUtils = utils;
        this.dataTableModel = dataTableModel;
    }

    @Override
    public void run() {
        Main.getUI().showWaitDialog();

        try {
            Client client = Main.getUI().getSelectedClient();
            if (client != null) {
                work(client);
            } else {
                Client[] clients = clientUtils.findAllClients(device);
                if (clients.length > 0) {
                    ClientWrapper[] clientWrappers = new ClientWrapper[clients.length];
                    for (int i = 0; i < clientWrappers.length; i++) {
                        clientWrappers[i] = new ClientWrapper(clients[i]);
                    }
                    Main.getUI().hideWaitDialog();

                    ClientWrapper ret = Main.getUI().showChoiceDialog("Choose a package to scan",
                            "Choose package",
                            clientWrappers);
                    if (ret != null) {
                        work(ret.client);
                    }
                }
            }
        } finally {
            Main.getUI().hideWaitDialog();
        }
    }

    private void work(Client c) {
        String pkg = c.getClientData().getClientDescription();
        Main.getUI().showWaitDialog();
        Main.getUI().updateWaitDialog("Retrieving heap data for " + pkg);

        try {
            Map<String, String> data = Main.findAndGetClassData(device, pkg);
            DumpData dumpData = new DumpData(pkg, data, new Date());
            dataTableModel.addData(dumpData);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static class ClientWrapper {
        private Client client;

        public ClientWrapper(Client c) {
            client = c;
        }

        @Override
        public String toString() {
            return client.getClientData().getClientDescription() + " (pid "
                    + client.getClientData().getPid() + ")";
        }
    }
}