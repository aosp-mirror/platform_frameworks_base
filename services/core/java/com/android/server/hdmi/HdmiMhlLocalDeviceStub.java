package com.android.server.hdmi;

import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.hdmi.IHdmiControlCallback;

/**
 * Stub class that models a logical mhl device hosted in this system.
 */
final class HdmiMhlLocalDeviceStub {

    private static final HdmiDeviceInfo INFO = new HdmiDeviceInfo(
            Constants.INVALID_PHYSICAL_ADDRESS, Constants.INVALID_PORT_ID, -1, -1);
    private final HdmiControlService mService;
    private final int mPortId;

    protected HdmiMhlLocalDeviceStub(HdmiControlService service, int portId) {
        mService = service;
        mPortId = portId;
    }

    void onDeviceRemoved() {
    }

    HdmiDeviceInfo getInfo() {
        return INFO;
    }

    void setBusMode(int cbusmode) {
    }

    void onBusOvercurrentDetected(boolean on) {
    }

    void setDeviceStatusChange(int adopterId, int deviceId) {
    }

    int getPortId() {
        return mPortId;
    }

    void turnOn(IHdmiControlCallback callback) {
    }

    void sendKeyEvent(int keycode, boolean isPressed) {
    }
}
