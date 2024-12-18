/**
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

package com.android.server.broadcastradio;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.broadcastradio.IBroadcastRadio;
import android.hardware.radio.IAnnouncementListener;
import android.hardware.radio.ICloseHandle;
import android.hardware.radio.IRadioService;
import android.hardware.radio.ITuner;
import android.hardware.radio.ITunerCallback;
import android.hardware.radio.RadioManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.IndentingPrintWriter;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.broadcastradio.aidl.BroadcastRadioServiceImpl;
import com.android.server.utils.Slogf;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Wrapper for AIDL interface for BroadcastRadio HAL
 */
final class IRadioServiceAidlImpl extends IRadioService.Stub {
    private static final String TAG = "BcRadioSrvAidl";

    private static final List<String> SERVICE_NAMES = Arrays.asList(
            IBroadcastRadio.DESCRIPTOR + "/amfm", IBroadcastRadio.DESCRIPTOR + "/dab");

    private final BroadcastRadioServiceImpl mAidlHalClient;
    private final BroadcastRadioService mService;

    /**
     * Gets names of all AIDL BroadcastRadio HAL services available.
     */
    public static ArrayList<String> getServicesNames() {
        ArrayList<String> serviceList = new ArrayList<>();
        for (int i = 0; i < SERVICE_NAMES.size(); i++) {
            IBinder serviceBinder = ServiceManager.waitForDeclaredService(SERVICE_NAMES.get(i));
            if (serviceBinder != null) {
                serviceList.add(SERVICE_NAMES.get(i));
            }
        }
        return serviceList;
    }

    IRadioServiceAidlImpl(BroadcastRadioService service, List<String> serviceList,
            RadioServiceUserController userController) {
        this(service, new BroadcastRadioServiceImpl(serviceList, userController));
        Slogf.i(TAG, "Initialize BroadcastRadioServiceAidl(%s)", service);
    }

    @VisibleForTesting
    IRadioServiceAidlImpl(BroadcastRadioService service, BroadcastRadioServiceImpl halAidl) {
        mService = Objects.requireNonNull(service, "Broadcast radio service cannot be null");
        mAidlHalClient = Objects.requireNonNull(halAidl,
                "Broadcast radio service implementation for AIDL HAL cannot be null");
    }

    @Override
    public List<RadioManager.ModuleProperties> listModules() {
        mService.enforcePolicyAccess();
        return mAidlHalClient.listModules();
    }

    @Override
    public ITuner openTuner(int moduleId, RadioManager.BandConfig bandConfig,
            boolean withAudio, ITunerCallback callback) throws RemoteException {
        if (isDebugEnabled()) {
            Slogf.d(TAG, "Opening module %d", moduleId);
        }
        mService.enforcePolicyAccess();
        if (callback == null) {
            throw new IllegalArgumentException("Callback must not be null");
        }
        return mAidlHalClient.openSession(moduleId, bandConfig, withAudio, callback);
    }

    @Override
    public ICloseHandle addAnnouncementListener(int[] enabledTypes,
            IAnnouncementListener listener) {
        if (isDebugEnabled()) {
            Slogf.d(TAG, "Adding announcement listener for %s", Arrays.toString(enabledTypes));
        }
        Objects.requireNonNull(enabledTypes, "Enabled announcement types cannot be null");
        Objects.requireNonNull(listener, "Announcement listener cannot be null");
        mService.enforcePolicyAccess();

        return mAidlHalClient.addAnnouncementListener(enabledTypes, listener);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter printWriter, String[] args) {
        if (mService.getContext().checkCallingOrSelfPermission(Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            printWriter.println("Permission Denial: can't dump AIDL BroadcastRadioService from "
                    + "from pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                    + " without permission " + Manifest.permission.DUMP);
            return;
        }
        IndentingPrintWriter radioPrintWriter = new IndentingPrintWriter(printWriter);
        radioPrintWriter.printf("BroadcastRadioService\n");

        radioPrintWriter.increaseIndent();
        radioPrintWriter.printf("AIDL HAL client:\n");

        radioPrintWriter.increaseIndent();
        mAidlHalClient.dumpInfo(radioPrintWriter);
        radioPrintWriter.decreaseIndent();

        radioPrintWriter.decreaseIndent();
    }

    private static boolean isDebugEnabled() {
        return Log.isLoggable(TAG, Log.DEBUG);
    }
}
