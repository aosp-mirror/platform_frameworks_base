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

package android.nfc;

import android.content.ComponentName;
import android.nfc.cardemulation.NfcFServiceInfo;

/**
 * @hide
 */
interface INfcFCardEmulation
{
    String getSystemCodeForService(int userHandle, in ComponentName service);
    boolean registerSystemCodeForService(int userHandle, in ComponentName service, String systemCode);
    boolean removeSystemCodeForService(int userHandle, in ComponentName service);
    String getNfcid2ForService(int userHandle, in ComponentName service);
    boolean setNfcid2ForService(int userHandle, in ComponentName service, String nfcid2);
    boolean enableNfcFForegroundService(in ComponentName service);
    boolean disableNfcFForegroundService();
    List<NfcFServiceInfo> getNfcFServices(int userHandle);
    int getMaxNumOfRegisterableSystemCodes();
}
