/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server;

import android.net.LocalSocketAddress;
import android.net.LocalSocket;
import android.os.Environment;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Config;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.lang.IllegalStateException;

import java.util.List;
import java.util.ArrayList;
import java.util.ListIterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Vold Connection class
 */
final class MountListener implements Runnable {
    private static final String TAG = "MountListener";
    private static final String VOLD_SOCKET = "vold";
    private static final int    RESPONSE_QUEUE_SIZE = 10;

    private MountService          mService;
    private BlockingQueue<String> mResponseQueue;
    private OutputStream          mOutputStream;

    class ResponseCode {
        public static final int ActionInitiated                = 100;
        public static final int VolumeListResult               = 110;
        public static final int AsecListResult                 = 111;

        public static final int CommandOkay                    = 200;
        public static final int ShareAvailabilityResult        = 210;
        public static final int AsecPathResult                 = 211;

        public static final int UnsolicitedInformational       = 600;
        public static final int VolumeStateChange              = 605;
        public static final int VolumeMountFailedBlank         = 610;
        public static final int VolumeMountFailedDamaged       = 611;
        public static final int VolumeMountFailedNoMedia       = 612;
        public static final int ShareAvailabilityChange        = 620;
        public static final int VolumeDiskInserted             = 630;
        public static final int VolumeDiskRemoved              = 631;
        public static final int VolumeBadRemoval               = 632;
    }

    MountListener(MountService service) {
        mService = service;
        mResponseQueue = new LinkedBlockingQueue<String>(RESPONSE_QUEUE_SIZE);
    }

    public void run() {
        // Vold does not run in the simulator, so fake out a mounted event to trigger the Media Scanner
        if ("simulator".equals(SystemProperties.get("ro.product.device"))) {
            mService.notifyMediaMounted(Environment.getExternalStorageDirectory().getPath(), false);
            return;
        }

        try {
            while (true) {
                listenToSocket();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Fatal error " + t + " in MountListener thread!");
        }
    }

    private void listenToSocket() {
       LocalSocket socket = null;

        try {
            socket = new LocalSocket();
            LocalSocketAddress address = new LocalSocketAddress(VOLD_SOCKET, 
                    LocalSocketAddress.Namespace.RESERVED);

            socket.connect(address);
            mService.onVoldConnected();

            InputStream inputStream = socket.getInputStream();
            mOutputStream = socket.getOutputStream();

            byte[] buffer = new byte[4096];

            while (true) {
                int count = inputStream.read(buffer);
                if (count < 0) break;

                int start = 0;
                for (int i = 0; i < count; i++) {
                    if (buffer[i] == 0) {
                        String event = new String(buffer, start, i - start);
//                        Log.d(TAG, "Got packet {" + event + "}");

                        String[] tokens = event.split(" ");
                        try {
                            int code = Integer.parseInt(tokens[0]);

                            if (code >= ResponseCode.UnsolicitedInformational) {
                                try {
                                    handleUnsolicitedEvent(code, event, tokens);
                                } catch (Exception ex) {
                                    Log.e(TAG, "Error handling unsolicited event '" + event + "'");
                                    Log.e(TAG, ex.toString());
                                }
                            } else {
                                try {
                                    mResponseQueue.put(event);
                                } catch (InterruptedException ex) {
                                    Log.e(TAG, "InterruptedException");
                                }
                            }
                        } catch (NumberFormatException nfe) {
                            Log.w(TAG,
                                  "Unknown msg from Vold '" + event + "'");
                        }
                        start = i + 1;
                    }                   
                }
            }                
        } catch (IOException ex) {
            Log.e(TAG, "IOException in listenToSocket");
        }
        
        synchronized (this) {
            if (mOutputStream != null) {
                try {
                    mOutputStream.close();
                } catch (IOException e) {
                    Log.w(TAG, "IOException closing output stream");
                }
                
                mOutputStream = null;
            }
        }
        
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ex) {
            Log.w(TAG, "IOException closing socket");
        }
       
        Log.e(TAG, "Failed to connect to Vold", new IllegalStateException());
        SystemClock.sleep(5000);
    }

    private void handleUnsolicitedEvent(int code, String raw,
                                        String[] cooked) throws IllegalStateException {
//        Log.d(TAG, "unsolicited {" + raw + "}");
        if (code == ResponseCode.VolumeStateChange) {
            // FMT: NNN Volume <label> <mountpoint> state changed from <old_#> (<old_str>) to <new_#> (<new_str>)
            mService.notifyVolumeStateChange(cooked[2], cooked[3],
                                             Integer.parseInt(cooked[7]),
                                             Integer.parseInt(cooked[10]));
        } else if (code == ResponseCode.VolumeMountFailedBlank) {
            // FMT: NNN Volume <label> <mountpoint> mount failed - no supported file-systems
            mService.notifyMediaNoFs(cooked[3]);
            // FMT: NNN Volume <label> <mountpoint> mount failed - no media
        } else if (code == ResponseCode.VolumeMountFailedNoMedia) {
            mService.notifyMediaRemoved(cooked[3]);
        } else if (code == ResponseCode.VolumeMountFailedDamaged) {
            // FMT: NNN Volume <label> <mountpoint> mount failed - filesystem check failed
            mService.notifyMediaUnmountable(cooked[3]);
        } else if (code == ResponseCode.ShareAvailabilityChange) {
            // FMT: NNN Share method <method> now <available|unavailable>
            boolean avail = false;
            if (cooked[5].equals("available")) {
                avail = true;
            }
            mService.notifyShareAvailabilityChange(cooked[3], avail);
        } else if (code == ResponseCode.VolumeDiskInserted) {
            // FMT: NNN Volume <label> <mountpoint> disk inserted (<major>:<minor>)
            mService.notifyMediaInserted(cooked[3]);
        } else if (code == ResponseCode.VolumeDiskRemoved) {
            // FMT: NNN Volume <label> <mountpoint> disk removed (<major>:<minor>)
            mService.notifyMediaRemoved(cooked[3]);
        } else if (code == ResponseCode.VolumeBadRemoval) {
            // FMT: NNN Volume <label> <mountpoint> bad removal (<major>:<minor>)
            mService.notifyMediaBadRemoval(cooked[3]);
        } else {
            Log.d(TAG, "Unhandled event {" + raw + "}");
        }
    }
    

    private void sendCommand(String command) {
        sendCommand(command, null);
    }

    /**
     * Sends a command to Vold with a single argument
     *
     * @param command  The command to send to the mount service daemon
     * @param argument The argument to send with the command (or null)
     */
    private void sendCommand(String command, String argument) {
        synchronized (this) {
            Log.d(TAG, "sendCommand {" + command + "} {" + argument + "}");
            if (mOutputStream == null) {
                Log.e(TAG, "No connection to Vold", new IllegalStateException());
            } else {
                StringBuilder builder = new StringBuilder(command);
                if (argument != null) {
                    builder.append(argument);
                }
                builder.append('\0');

                try {
                    mOutputStream.write(builder.toString().getBytes());
                } catch (IOException ex) {
                    Log.e(TAG, "IOException in sendCommand", ex);
                }
            }
        }
    }

    private synchronized ArrayList<String> doCommand(String cmd) throws IllegalStateException {
        sendCommand(cmd);

        ArrayList<String> response = new ArrayList<String>();
        boolean complete = false;
        int code = -1;

        while (!complete) {
            try {
                String line = mResponseQueue.take();
//                Log.d(TAG, "Removed off queue -> " + line);
                String[] tokens = line.split(" ");
                code = Integer.parseInt(tokens[0]);

                if ((code >= 200) && (code < 600))
                    complete = true;
                response.add(line);
            } catch (InterruptedException ex) {
                Log.e(TAG, "InterruptedException");
            }
        }

        if (code >= 400 && code < 600) {
            throw new IllegalStateException(String.format(
                                               "Command %s failed with code %d",
                                                cmd, code));
        }
        return response;
    }

    boolean getShareAvailable(String method) throws IllegalStateException  {
        ArrayList<String> rsp = doCommand("share_available " + method);

        for (String line : rsp) {
            String []tok = line.split(" ");
            int code = Integer.parseInt(tok[0]);
            if (code == ResponseCode.ShareAvailabilityResult) {
                if (tok[2].equals("available"))
                    return true;
                return false;
            } else {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
        }
        throw new IllegalStateException("Got an empty response");
    }

    /**
     * Enables or disables USB mass storage support.
     * 
     * @param enable  true to enable USB mass storage support
     */
    void setShareMethodEnabled(String mountPoint, String method,
                               boolean enable) throws IllegalStateException {
        doCommand((enable ? "" : "un") + "share " + mountPoint + " " + method);
    }

    /**
     * Mount media at given mount point.
     */
    public void mountVolume(String label) throws IllegalStateException {
        doCommand("mount " + label);
    }

    /**
     * Unmount media at given mount point.
     */
    public void unmountVolume(String label) throws IllegalStateException {
        doCommand("unmount " + label);
    }

    /**
     * Format media at given mount point.
     */
    public void formatVolume(String label) throws IllegalStateException {
        doCommand("format " + label);
    }

    public String createAsec(String id, int sizeMb, String fstype, String key,
                           int ownerUid) throws IllegalStateException {
        String cmd = String.format("create_asec %s %d %s %s %d",
                                   id, sizeMb, fstype, key, ownerUid);
        doCommand(cmd);
        return getAsecPath(id);
    }

    public void finalizeAsec(String id) throws IllegalStateException {
        doCommand("finalize_asec " + id);
    }

    public void destroyAsec(String id) throws IllegalStateException {
        doCommand("destroy_asec " + id);
    }

    public String mountAsec(String id, String key, int ownerUid) throws IllegalStateException {
        String cmd = String.format("mount_asec %s %s %d",
                                   id, key, ownerUid);
        doCommand(cmd);
        return getAsecPath(id);
    }

    public String getAsecPath(String id) throws IllegalStateException {
        ArrayList<String> rsp = doCommand("asec_path " + id);

        for (String line : rsp) {
            String []tok = line.split(" ");
            int code = Integer.parseInt(tok[0]);
            if (code == ResponseCode.AsecPathResult) {
                return tok[1];
            } else {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
        }
        throw new IllegalStateException("Got an empty response");
    }

    public String[] listAsec() throws IllegalStateException {
        ArrayList<String> rsp = doCommand("list_asec");

        String[] rdata = new String[rsp.size()];
        int idx = 0;

        for (String line : rsp) {
            String []tok = line.split(" ");
            int code = Integer.parseInt(tok[0]);
            if (code == ResponseCode.AsecPathResult) {
                rdata[idx++] = tok[1];
            } else if (code == ResponseCode.CommandOkay) {
                return rdata;
            } else {
                throw new IllegalStateException(String.format("Unexpected response code %d", code));
            }
        }
        throw new IllegalStateException("Got an empty response");
    }
}
