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

package android.hardware.hdmi;

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.RemoteException;

/**
 * The {@link HdmiControlManager} class is used to send HDMI control messages
 * to attached CEC devices.
 *
 * <p>Provides various HDMI client instances that represent HDMI-CEC logical devices
 * hosted in the system. {@link #getTvClient()}, for instance will return an
 * {@link HdmiTvClient} object if the system is configured to host one. Android system
 * can host more than one logical CEC devices. If multiple types are configured they
 * all work as if they were independent logical devices running in the system.
 *
 * @hide
 */
@SystemApi
public final class HdmiControlManager {
    @Nullable private final IHdmiControlService mService;

    // True if we have a logical device of type playback hosted in the system.
    private final boolean mHasPlaybackDevice;
    // True if we have a logical device of type TV hosted in the system.
    private final boolean mHasTvDevice;

    /**
     * @hide - hide this constructor because it has a parameter of type
     * IHdmiControlService, which is a system private class. The right way
     * to create an instance of this class is using the factory
     * Context.getSystemService.
     */
    public HdmiControlManager(IHdmiControlService service) {
        mService = service;
        int[] types = null;
        if (mService != null) {
            try {
                types = mService.getSupportedTypes();
            } catch (RemoteException e) {
                // Do nothing.
            }
        }
        mHasTvDevice = hasDeviceType(types, HdmiCec.DEVICE_TV);
        mHasPlaybackDevice = hasDeviceType(types, HdmiCec.DEVICE_PLAYBACK);
    }

    private static boolean hasDeviceType(int[] types, int type) {
        if (types == null) {
            return false;
        }
        for (int t : types) {
            if (t == type) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets an object that represents a HDMI-CEC logical device of type playback on the system.
     *
     * <p>Used to send HDMI control messages to other devices like TV or audio amplifier through
     * HDMI bus. It is also possible to communicate with other logical devices hosted in the same
     * system if the system is configured to host more than one type of HDMI-CEC logical devices.
     *
     * @return {@link HdmiPlaybackClient} instance. {@code null} on failure.
     */
    @Nullable
    public HdmiPlaybackClient getPlaybackClient() {
        if (mService == null || !mHasPlaybackDevice) {
            return null;
        }
        return new HdmiPlaybackClient(mService);
    }

    /**
     * Gets an object that represents a HDMI-CEC logical device of type TV on the system.
     *
     * <p>Used to send HDMI control messages to other devices and manage them through
     * HDMI bus. It is also possible to communicate with other logical devices hosted in the same
     * system if the system is configured to host more than one type of HDMI-CEC logical devices.
     *
     * @return {@link HdmiTvClient} instance. {@code null} on failure.
     */
    @Nullable
    public HdmiTvClient getTvClient() {
        if (mService == null || !mHasTvDevice) {
                return null;
        }
        return new HdmiTvClient(mService);
    }

    /**
     * Listener used to get hotplug event from HDMI port.
     */
    public interface HotplugEventListener {
        void onReceived(HdmiHotplugEvent event);
    }

    /**
     * Adds a listener to get informed of {@link HdmiHotplugEvent}.
     *
     * <p>To stop getting the notification,
     * use {@link #removeHotplugEventListener(HotplugEventListener)}.
     *
     * @param listener {@link HotplugEventListener} instance
     * @see HdmiControlManager#removeHotplugEventListener(HotplugEventListener)
     */
    public void addHotplugEventListener(HotplugEventListener listener) {
        if (mService == null) {
            return;
        }
        try {
            mService.addHotplugEventListener(getHotplugEventListenerWrapper(listener));
        } catch (RemoteException e) {
            // Do nothing.
        }
    }

    /**
     * Removes a listener to stop getting informed of {@link HdmiHotplugEvent}.
     *
     * @param listener {@link HotplugEventListener} instance to be removed
     */
    public void removeHotplugEventListener(HotplugEventListener listener) {
        if (mService == null) {
            return;
        }
        try {
            mService.removeHotplugEventListener(getHotplugEventListenerWrapper(listener));
        } catch (RemoteException e) {
            // Do nothing.
        }
    }

    private IHdmiHotplugEventListener getHotplugEventListenerWrapper(
            final HotplugEventListener listener) {
        return new IHdmiHotplugEventListener.Stub() {
            public void onReceived(HdmiHotplugEvent event) {
                listener.onReceived(event);;
            }
        };
    }
}
