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

import java.util.Arrays;
import java.util.Comparator;

import javax.swing.DefaultListModel;

public class ReloadListAction extends AbstractThreadedDeviceSpecificAction {

    private ClientUtils clientUtils;
    private final DefaultListModel<Client> clientListModel;

    public ReloadListAction(ClientUtils utils, IDevice device,
            DefaultListModel<Client> clientListModel) {
        super("Reload", device);
        this.clientUtils = utils;
        this.clientListModel = clientListModel;
    }

    @Override
    public void run() {
        Client[] clients = clientUtils.findAllClients(device);
        if (clients != null) {
            Arrays.sort(clients, new ClientComparator());
        }
        clientListModel.removeAllElements();
        for (Client c : clients) {
            clientListModel.addElement(c);
        }
    }

    private static class ClientComparator implements Comparator<Client> {

        @Override
        public int compare(Client o1, Client o2) {
            String s1 = o1.getClientData().getClientDescription();
            String s2 = o2.getClientData().getClientDescription();

            if (s1 == null || s2 == null) {
                // Not good, didn't get all data?
                return (s1 == null) ? -1 : 1;
            }

            return s1.compareTo(s2);
        }

    }
}