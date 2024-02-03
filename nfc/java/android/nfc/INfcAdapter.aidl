/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.nfc;

import android.app.PendingIntent;
import android.content.IntentFilter;
import android.nfc.NdefMessage;
import android.nfc.Tag;
import android.nfc.TechListParcel;
import android.nfc.IAppCallback;
import android.nfc.INfcAdapterExtras;
import android.nfc.INfcControllerAlwaysOnListener;
import android.nfc.INfcTag;
import android.nfc.INfcCardEmulation;
import android.nfc.INfcFCardEmulation;
import android.nfc.INfcUnlockHandler;
import android.nfc.ITagRemovedCallback;
import android.nfc.INfcDta;
import android.nfc.NfcAntennaInfo;
import android.os.Bundle;

/**
 * @hide
 */
interface INfcAdapter
{
    INfcTag getNfcTagInterface();
    INfcCardEmulation getNfcCardEmulationInterface();
    INfcFCardEmulation getNfcFCardEmulationInterface();
    INfcAdapterExtras getNfcAdapterExtrasInterface(in String pkg);
    INfcDta getNfcDtaInterface(in String pkg);
    int getState();
    boolean disable(boolean saveState);
    boolean enable();
    void pausePolling(int timeoutInMs);
    void resumePolling();

    void setForegroundDispatch(in PendingIntent intent,
            in IntentFilter[] filters, in TechListParcel techLists);
    void setAppCallback(in IAppCallback callback);

    boolean ignore(int nativeHandle, int debounceMs, ITagRemovedCallback callback);

    void dispatch(in Tag tag);

    void setReaderMode (IBinder b, IAppCallback callback, int flags, in Bundle extras);

    void addNfcUnlockHandler(INfcUnlockHandler unlockHandler, in int[] techList);
    void removeNfcUnlockHandler(INfcUnlockHandler unlockHandler);

    void verifyNfcPermission();
    boolean isNfcSecureEnabled();
    boolean deviceSupportsNfcSecure();
    boolean setNfcSecure(boolean enable);
    NfcAntennaInfo getNfcAntennaInfo();

    boolean setControllerAlwaysOn(boolean value);
    boolean isControllerAlwaysOn();
    boolean isControllerAlwaysOnSupported();
    void registerControllerAlwaysOnListener(in INfcControllerAlwaysOnListener listener);
    void unregisterControllerAlwaysOnListener(in INfcControllerAlwaysOnListener listener);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)")
    boolean isTagIntentAppPreferenceSupported();
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)")
    Map getTagIntentAppPreferenceForUser(int userId);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)")
    int setTagIntentAppPreferenceForUser(int userId, String pkg, boolean allow);

    boolean isReaderOptionEnabled();
    boolean isReaderOptionSupported();
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)")
    boolean enableReaderOption(boolean enable);
    boolean isObserveModeSupported();
    boolean setObserveMode(boolean enabled);
    void updateDiscoveryTechnology(IBinder b, int pollFlags, int listenFlags);
}
