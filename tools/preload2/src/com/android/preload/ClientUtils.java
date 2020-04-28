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

package com.android.preload;

import com.android.ddmlib.AndroidDebugBridge;
import com.android.ddmlib.AndroidDebugBridge.IClientChangeListener;
import com.android.ddmlib.Client;
import com.android.ddmlib.IDevice;

/**
 * Helper class for common communication with a Client (the ddms name for a running application).
 *
 * Instances take a default timeout parameter that's applied to all functions without explicit
 * timeout. Timeouts are in milliseconds.
 */
public class ClientUtils {

    private int defaultTimeout;

    public ClientUtils() {
        this(10000);
    }

    public ClientUtils(int defaultTimeout) {
        this.defaultTimeout = defaultTimeout;
    }

    /**
     * Shortcut for findClient with default timeout.
     */
    public Client findClient(IDevice device, String processName, int processPid) {
        return findClient(device, processName, processPid, defaultTimeout);
    }

    /**
     * Find the client with the given process name or process id. The name takes precedence over
     * the process id (if valid). Stop looking after the given timeout.
     *
     * @param device The device to communicate with.
     * @param processName The name of the process. May be null.
     * @param processPid The pid of the process. Values less than or equal to zero are ignored.
     * @param timeout The amount of milliseconds to wait, at most.
     * @return The client, if found. Otherwise null.
     */
    public Client findClient(IDevice device, String processName, int processPid, int timeout) {
        WaitForClient wfc = new WaitForClient(device, processName, processPid, timeout);
        return wfc.get();
    }

    /**
     * Shortcut for findAllClients with default timeout.
     */
    public Client[] findAllClients(IDevice device) {
        return findAllClients(device, defaultTimeout);
    }

    /**
     * Retrieve all clients known to the given device. Wait at most the given timeout.
     *
     * @param device The device to investigate.
     * @param timeout The amount of milliseconds to wait, at most.
     * @return An array of clients running on the given device. May be null depending on the
     *         device implementation.
     */
    public Client[] findAllClients(IDevice device, int timeout) {
        if (device.hasClients()) {
            return device.getClients();
        }
        WaitForClients wfc = new WaitForClients(device, timeout);
        return wfc.get();
    }

    private static class WaitForClient implements IClientChangeListener {

        private IDevice device;
        private String processName;
        private int processPid;
        private long timeout;
        private Client result;

        public WaitForClient(IDevice device, String processName, int processPid, long timeout) {
            this.device = device;
            this.processName = processName;
            this.processPid = processPid;
            this.timeout = timeout;
            this.result = null;
        }

        public Client get() {
            synchronized (this) {
                AndroidDebugBridge.addClientChangeListener(this);

                // Maybe it's already there.
                if (result == null) {
                    result = searchForClient(device);
                }

                if (result == null) {
                    try {
                        wait(timeout);
                    } catch (InterruptedException e) {
                        // Note: doesn't guard for spurious wakeup.
                    }
                }
            }

            AndroidDebugBridge.removeClientChangeListener(this);
            return result;
        }

        private Client searchForClient(IDevice device) {
            if (processName != null) {
                Client tmp = device.getClient(processName);
                if (tmp != null) {
                    return tmp;
                }
            }
            if (processPid > 0) {
                String name = device.getClientName(processPid);
                if (name != null && !name.isEmpty()) {
                    Client tmp = device.getClient(name);
                    if (tmp != null) {
                        return tmp;
                    }
                }
            }
            if (processPid > 0) {
                // Try manual search.
                for (Client cl : device.getClients()) {
                    if (cl.getClientData().getPid() == processPid
                            && cl.getClientData().getClientDescription() != null) {
                        return cl;
                    }
                }
            }
            return null;
        }

        private boolean isTargetClient(Client c) {
            if (processPid > 0 && c.getClientData().getPid() == processPid) {
                return true;
            }
            if (processName != null
                    && processName.equals(c.getClientData().getClientDescription())) {
                return true;
            }
            return false;
        }

        @Override
        public void clientChanged(Client arg0, int arg1) {
            synchronized (this) {
                if ((arg1 & Client.CHANGE_INFO) != 0 && (arg0.getDevice() == device)) {
                    if (isTargetClient(arg0)) {
                        result = arg0;
                        notifyAll();
                    }
                }
            }
        }
    }

    private static class WaitForClients implements IClientChangeListener {

        private IDevice device;
        private long timeout;

        public WaitForClients(IDevice device, long timeout) {
            this.device = device;
            this.timeout = timeout;
        }

        public Client[] get() {
            synchronized (this) {
                AndroidDebugBridge.addClientChangeListener(this);

                if (device.hasClients()) {
                    return device.getClients();
                }

                try {
                    wait(timeout); // Note: doesn't guard for spurious wakeup.
                } catch (InterruptedException exc) {
                }

                // We will be woken up when the first client data arrives. Sleep a little longer
                // to give (hopefully all of) the rest of the clients a chance to become available.
                // Note: a loop with timeout is brittle as well and complicated, just accept this
                //       for now.
                try {
                    Thread.sleep(500);
                } catch (InterruptedException exc) {
                }
            }

            AndroidDebugBridge.removeClientChangeListener(this);

            return device.getClients();
        }

        @Override
        public void clientChanged(Client arg0, int arg1) {
            synchronized (this) {
                if ((arg1 & Client.CHANGE_INFO) != 0 && (arg0.getDevice() == device)) {
                    notifyAll();
                }
            }
        }
    }
}
