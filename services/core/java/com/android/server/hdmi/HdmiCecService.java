/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.hdmi;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.hdmi.HdmiCec;
import android.hardware.hdmi.HdmiCecMessage;
import android.hardware.hdmi.IHdmiCecListener;
import android.hardware.hdmi.IHdmiCecService;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;

import com.android.server.SystemService;
import libcore.util.EmptyArray;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Provides a service for sending and processing HDMI-CEC messages, and providing
 * the information on HDMI settings in general.
 */
public final class HdmiCecService extends SystemService {
    private static final String TAG = "HdmiCecService";

    // Maintains the allocated logical devices. Device type, not logical address,
    // is used for key as logical address is likely to change over time while
    // device type is permanent. Type-address mapping is maintained only at
    // native level.
    private final SparseArray<HdmiCecDevice> mLogicalDevices = new SparseArray<HdmiCecDevice>();

    // List of IBinder.DeathRecipient instances to handle dead IHdmiCecListener
    // objects.
    private final ArrayList<ListenerRecord> mListenerRecords = new ArrayList<ListenerRecord>();

    // Used to synchronize the access to the service.
    private final Object mLock = new Object();

    // Stores the pointer to the native implementation of the service that
    // interacts with HAL.
    private long mNativePtr;

    private static final String PERMISSION = "android.permission.HDMI_CEC";

    static final byte[] EMPTY_PARAM = EmptyArray.BYTE;

    public HdmiCecService(Context context) {
        super(context);
    }

    private static native long nativeInit(HdmiCecService service);

    @Override
    public void onStart() {
        mNativePtr = nativeInit(this);
        if (mNativePtr != 0) {
            // TODO: Consider using a dedicated, configurable identifier for OSD name, maybe from
            //       Settings. It should be ASCII only, not a very long one (limited to 15 chars).
            setOsdNameLocked(Build.MODEL);
            publishBinderService(Context.HDMI_CEC_SERVICE, new BinderService());
        }
    }

    /**
     * Called by native when an HDMI-CEC message arrived. Invokes the registered
     * listeners to handle the message.
     */
    private void handleMessage(int srcAddress, int dstAddress, int opcode, byte[] params) {
        // TODO: Messages like <Standby> may not need be passed to listener
        //       but better be handled in service by turning off the screen
        //       or putting the device into suspend mode. List up such messages
        //       and handle them here.
        synchronized (mLock) {
            if (dstAddress == HdmiCec.ADDR_BROADCAST) {
                for (int i = 0; i < mLogicalDevices.size(); ++i) {
                    mLogicalDevices.valueAt(i).handleMessage(srcAddress, dstAddress, opcode,
                            params);
                }
            } else {
                int type = HdmiCec.getTypeFromAddress(dstAddress);
                HdmiCecDevice device = mLogicalDevices.get(type);
                if (device == null) {
                    Log.w(TAG, "logical device not found. type: " + type);
                    return;
                }
                device.handleMessage(srcAddress, dstAddress, opcode, params);
            }
        }
    }

    /**
     * Called by native when internal HDMI hotplug event occurs. Invokes the registered
     * listeners to handle the event.
     */
    private void handleHotplug(boolean connected) {
        synchronized(mLock) {
            for (int i = 0; i < mLogicalDevices.size(); ++i) {
                mLogicalDevices.valueAt(i).handleHotplug(connected);
            }
        }
    }

    /**
     * Called by native when it needs to know whether we have an active source.
     * The native part uses the return value to respond to &lt;Request Active
     * Source &gt;.
     *
     * @return type of the device which is active; DEVICE_INACTIVE if there is
     *        no active logical device in the system.
     */
    private int getActiveSource() {
        synchronized(mLock) {
            for (int i = 0; i < mLogicalDevices.size(); ++i) {
                if (mLogicalDevices.valueAt(i).isActiveSource()) {
                    return mLogicalDevices.keyAt(i);
                }
            }
        }
        return HdmiCec.DEVICE_INACTIVE;
    }

    /**
     * Called by native when a request for the menu language of the device was
     * received. The native part uses the return value to generate the message
     * &lt;Set Menu Language&gt; in response. The language should be of
     * the 3-letter format as defined in ISO/FDIS 639-2. We use system default
     * locale.
     */
    private String getLanguage(int type) {
        return Locale.getDefault().getISO3Language();
    }

    private void enforceAccessPermission() {
        getContext().enforceCallingOrSelfPermission(PERMISSION, "HdmiCecService");
    }

    private void dumpInternal(PrintWriter pw) {
        pw.println("HdmiCecService (dumpsys hdmi_cec)");
        pw.println("");
        synchronized (mLock) {
            for (int i = 0; i < mLogicalDevices.size(); ++i) {
                HdmiCecDevice device = mLogicalDevices.valueAt(i);
                pw.println("Device: type=" + device.getType() +
                           ", active=" + device.isActiveSource());
            }
        }
    }

    // Remove logical device of a given type.
    private void removeLogicalDeviceLocked(int type) {
        ensureValidType(type);
        mLogicalDevices.remove(type);
        nativeRemoveLogicalAddress(mNativePtr, type);
    }

    private static void ensureValidType(int type) {
        if (!HdmiCec.isValidType(type)) {
            throw new IllegalArgumentException("invalid type: " + type);
        }
    }

    // Return the logical device identified by the given binder token.
    private HdmiCecDevice getLogicalDeviceLocked(IBinder b) {
        for (int i = 0; i < mLogicalDevices.size(); ++i) {
            HdmiCecDevice device = mLogicalDevices.valueAt(i);
            if (device.getToken() == b) {
                return device;
            }
        }
        throw new IllegalArgumentException("Device not found");
    }

    // package-private. Used by HdmiCecDevice and its subclasses only.
    void sendMessage(int type, int address, int opcode, byte[] params) {
        nativeSendMessage(mNativePtr, type, address, opcode, params);
    }

    private void setOsdNameLocked(String name) {
        nativeSetOsdName(mNativePtr, name.getBytes(Charset.forName("US-ASCII")));
    }

    private final class ListenerRecord implements IBinder.DeathRecipient {
        private final IHdmiCecListener mListener;
        private final int mType;

        public ListenerRecord(IHdmiCecListener listener, int type) {
            mListener = listener;
            mType = type;
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                mListenerRecords.remove(this);
                HdmiCecDevice device = mLogicalDevices.get(mType);
                if (device != null) {
                    device.removeListener(mListener);
                    if (!device.hasListener()) {
                        removeLogicalDeviceLocked(mType);
                    }
                }
            }
        }
    }

    private final class BinderService extends IHdmiCecService.Stub {

        @Override
        public IBinder allocateLogicalDevice(int type, IHdmiCecListener listener) {
            enforceAccessPermission();
            ensureValidType(type);
            if (listener == null) {
                throw new IllegalArgumentException("listener must not be null");
            }
            synchronized (mLock) {
                HdmiCecDevice device = mLogicalDevices.get(type);
                if (device != null) {
                    Log.v(TAG, "Logical address already allocated. Adding listener only.");
                } else {
                    int address = nativeAllocateLogicalAddress(mNativePtr, type);
                    if (!HdmiCec.isValidAddress(address)) {
                        Log.e(TAG, "Logical address was not allocated");
                        return null;
                    } else {
                        device = HdmiCecDevice.create(HdmiCecService.this, type);
                        if (device == null) {
                            Log.e(TAG, "Device type not supported yet.");
                            return null;
                        }
                        device.initialize();
                        mLogicalDevices.put(type, device);
                    }
                }

                // Adds the listener and its monitor
                ListenerRecord record = new ListenerRecord(listener, type);
                try {
                    listener.asBinder().linkToDeath(record, 0);
                } catch (RemoteException e) {
                    Log.w(TAG, "Listener already died");
                    if (!device.hasListener()) {
                         removeLogicalDeviceLocked(type);
                    }
                    return null;
                }
                mListenerRecords.add(record);
                device.addListener(listener);
                return device.getToken();
            }
        }

        @Override
        public void sendActiveSource(IBinder b) {
            enforceAccessPermission();
            synchronized (mLock) {
                HdmiCecDevice device = getLogicalDeviceLocked(b);
                device.sendActiveSource(nativeGetPhysicalAddress(mNativePtr));
            }
        }

        @Override
        public void sendInactiveSource(IBinder b) {
            enforceAccessPermission();
            synchronized (mLock) {
                HdmiCecDevice device = getLogicalDeviceLocked(b);
                device.sendInactiveSource(nativeGetPhysicalAddress(mNativePtr));
            }
        }

        @Override
        public void sendImageViewOn(IBinder b) {
            enforceAccessPermission();
            synchronized (mLock) {
                HdmiCecDevice device = getLogicalDeviceLocked(b);
                device.sendImageViewOn();
            }
        }

        @Override
        public void sendTextViewOn(IBinder b) {
            enforceAccessPermission();
            synchronized (mLock) {
                HdmiCecDevice device = getLogicalDeviceLocked(b);
                device.sendTextViewOn();
            }
        }

        public void sendGiveDevicePowerStatus(IBinder b, int address) {
            enforceAccessPermission();
            synchronized (mLock) {
                HdmiCecDevice device = getLogicalDeviceLocked(b);
                nativeSendMessage(mNativePtr, device.getType(), address,
                        HdmiCec.MESSAGE_GIVE_DEVICE_POWER_STATUS, EMPTY_PARAM);
            }
        }

        @Override
        public boolean isTvOn(IBinder b) {
            enforceAccessPermission();
            synchronized (mLock) {
                HdmiCecDevice device = getLogicalDeviceLocked(b);
                return device.isSinkDeviceOn();
            }
        }

        @Override
        public void removeServiceListener(IBinder b, IHdmiCecListener listener) {
            enforceAccessPermission();
            if (listener == null) {
                throw new IllegalArgumentException("listener must not be null");
            }
            synchronized (mLock) {
                HdmiCecDevice device = getLogicalDeviceLocked(b);
                for (ListenerRecord record : mListenerRecords) {
                    if (record.mType == device.getType()
                            && record.mListener.asBinder() == listener.asBinder()) {
                        mListenerRecords.remove(record);
                        device.removeListener(record.mListener);
                        if (!device.hasListener()) {
                            removeLogicalDeviceLocked(record.mType);
                        }
                        break;
                    }
                }
            }
        }

        @Override
        public void sendMessage(IBinder b, HdmiCecMessage message) {
            enforceAccessPermission();
            if (message == null) {
                throw new IllegalArgumentException("message must not be null");
            }
            synchronized (mLock) {
                HdmiCecDevice device = getLogicalDeviceLocked(b);
                nativeSendMessage(mNativePtr, device.getType(), message.getDestination(),
                        message.getOpcode(), message.getParams());
            }
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (getContext().checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission denial: can't dump HdmiCecService from pid="
                        + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                        + " without permission " + android.Manifest.permission.DUMP);
                return;
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                dumpInternal(pw);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    private static native int nativeAllocateLogicalAddress(long handler, int deviceType);
    private static native void nativeRemoveLogicalAddress(long handler, int deviceType);
    private static native void nativeSendMessage(long handler, int deviceType, int destination,
            int opcode, byte[] params);
    private static native int nativeGetPhysicalAddress(long handler);
    private static native void nativeSetOsdName(long handler, byte[] name);
}
