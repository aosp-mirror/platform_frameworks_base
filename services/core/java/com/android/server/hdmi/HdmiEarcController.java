/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.hardware.tv.hdmi.earc.IEArc;
import android.hardware.tv.hdmi.earc.IEArcCallback;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;

import com.android.internal.annotations.VisibleForTesting;

final class HdmiEarcController {
    private static final String TAG = "HdmiEarcController";

    // Handler instance to process HAL calls.
    private Handler mControlHandler;

    private final HdmiControlService mService;

    private EarcNativeWrapper mEarcNativeWrapperImpl;

    protected interface EarcNativeWrapper {
        boolean nativeInit();
        void nativeSetEarcEnabled(boolean enabled);
        boolean nativeIsEarcEnabled();
        void nativeSetCallback(EarcAidlCallback callback);
        byte nativeGetState(int portId);
        byte[] nativeGetLastReportedAudioCapabilities(int portId);
    }

    private static final class EarcNativeWrapperImpl implements EarcNativeWrapper,
            IBinder.DeathRecipient {
        private IEArc mEarc;
        private EarcAidlCallback mEarcCallback;

        @Override
        public void binderDied() {
            mEarc.asBinder().unlinkToDeath(this, 0);
            connectToHal();
            if (mEarcCallback != null) {
                nativeSetCallback(mEarcCallback);
            }
        }

        boolean connectToHal() {
            mEarc =
                    IEArc.Stub.asInterface(
                            ServiceManager.getService(IEArc.DESCRIPTOR + "/default"));
            if (mEarc == null) {
                return false;
            }
            try {
                mEarc.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                HdmiLogger.error("Couldn't link callback object: ", e);
            }
            return true;
        }

        @Override
        public boolean nativeInit() {
            return connectToHal();
        }

        @Override
        public void nativeSetEarcEnabled(boolean enabled) {
            try {
                mEarc.setEArcEnabled(enabled);
            } catch (ServiceSpecificException sse) {
                HdmiLogger.error(
                        "Could not set eARC enabled to " + enabled + ". Error: ", sse.errorCode);
            } catch (RemoteException re) {
                HdmiLogger.error("Could not set eARC enabled to " + enabled + ":. Exception: ", re);
            }
        }

        @Override
        public boolean nativeIsEarcEnabled() {
            try {
                return mEarc.isEArcEnabled();
            } catch (RemoteException re) {
                HdmiLogger.error("Could not read if eARC is enabled. Exception: ", re);
                return false;
            }
        }

        @Override
        public void nativeSetCallback(EarcAidlCallback callback) {
            mEarcCallback = callback;
            try {
                mEarc.setCallback(callback);
            } catch (RemoteException re) {
                HdmiLogger.error("Could not set callback. Exception: ", re);
            }
        }

        @Override
        public byte nativeGetState(int portId) {
            try {
                return mEarc.getState(portId);
            } catch (RemoteException re) {
                HdmiLogger.error("Could not get eARC state. Exception: ", re);
                return -1;
            }
        }

        @Override
        public byte[] nativeGetLastReportedAudioCapabilities(int portId) {
            try {
                return mEarc.getLastReportedAudioCapabilities(portId);
            } catch (RemoteException re) {
                HdmiLogger.error(
                        "Could not read last reported audio capabilities. Exception: ", re);
                return null;
            }
        }
    }

    // Private constructor. Use HdmiEarcController.create().
    private HdmiEarcController(HdmiControlService service, EarcNativeWrapper nativeWrapper) {
        mService = service;
        mEarcNativeWrapperImpl = nativeWrapper;
    }

    /**
     * A factory method to get {@link HdmiEarcController}. If it fails to initialize
     * inner device or has no device it will return {@code null}.
     *
     * <p>Declared as package-private, accessed by {@link HdmiControlService} only.
     * @param service    {@link HdmiControlService} instance used to create internal handler
     *                   and to pass callback for incoming message or event.
     * @return {@link HdmiEarcController} if device is initialized successfully. Otherwise,
     *         returns {@code null}.
     */
    static HdmiEarcController create(HdmiControlService service) {
        return createWithNativeWrapper(service, new EarcNativeWrapperImpl());
    }

    /**
     * A factory method with injection of native methods for testing.
     */
    static HdmiEarcController createWithNativeWrapper(HdmiControlService service,
            EarcNativeWrapper nativeWrapper) {
        HdmiEarcController controller = new HdmiEarcController(service, nativeWrapper);
        if (!controller.init(nativeWrapper)) {
            HdmiLogger.warning("Could not connect to eARC AIDL HAL.");
            return null;
        }
        return controller;
    }

    private boolean init(EarcNativeWrapper nativeWrapper) {
        if (nativeWrapper.nativeInit()) {
            mControlHandler = new Handler(mService.getServiceLooper());
            mEarcNativeWrapperImpl.nativeSetCallback(new EarcAidlCallback());
            return true;
        }
        return false;
    }

    private void assertRunOnServiceThread() {
        if (Looper.myLooper() != mControlHandler.getLooper()) {
            throw new IllegalStateException("Should run on service thread.");
        }
    }

    @VisibleForTesting
    void runOnServiceThread(Runnable runnable) {
        mControlHandler.post(new WorkSourceUidPreservingRunnable(runnable));
    }

    /**
     * Enable eARC in the HAL
     * @param enabled
     */
    @HdmiAnnotations.ServiceThreadOnly
    void setEarcEnabled(boolean enabled) {
        assertRunOnServiceThread();
        mEarcNativeWrapperImpl.nativeSetEarcEnabled(enabled);
    }

    /**
     * Getter for the current eARC state.
     * @param portId the ID of the port on which to get the connection state
     * @return the current eARC state
     */
    @HdmiAnnotations.ServiceThreadOnly
    @Constants.EarcStatus
    int getState(int portId) {
        return mEarcNativeWrapperImpl.nativeGetState(portId);
    }

    /**
     * Ask the HAL to report the last eARC capabilities that the connected audio system reported.
     *
     * @return the raw eARC capabilities
     */
    @HdmiAnnotations.ServiceThreadOnly
    byte[] getLastReportedCaps(int portId) {
        return mEarcNativeWrapperImpl.nativeGetLastReportedAudioCapabilities(portId);
    }

    final class EarcAidlCallback extends IEArcCallback.Stub {
        public void onStateChange(@Constants.EarcStatus byte status, int portId) {
            runOnServiceThread(
                    () -> mService.handleEarcStateChange(status, portId));
        }

        public void onCapabilitiesReported(byte[] rawCapabilities, int portId) {
            runOnServiceThread(
                    () -> mService.handleEarcCapabilitiesReported(rawCapabilities, portId));
        }

        @Override
        public synchronized String getInterfaceHash() throws RemoteException {
            return IEArcCallback.Stub.HASH;
        }

        @Override
        public int getInterfaceVersion() throws RemoteException {
            return IEArcCallback.Stub.VERSION;
        }
    }
}
