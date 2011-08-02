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
import android.content.ComponentName;
import android.content.IntentFilter;
import android.nfc.NdefMessage;
import android.nfc.Tag;
import android.nfc.TechListParcel;
import android.nfc.INdefPushCallback;
import android.nfc.INfcTag;
import android.nfc.INfcAdapterExtras;

/**
 * @hide
 */
interface INfcAdapter
{
    INfcTag getNfcTagInterface();
    INfcAdapterExtras getNfcAdapterExtrasInterface();

    // NfcAdapter-class related methods
    boolean isEnabled();
    void enableForegroundDispatch(in ComponentName activity, in PendingIntent intent,
            in IntentFilter[] filters, in TechListParcel techLists);
    void disableForegroundDispatch(in ComponentName activity);
    void enableForegroundNdefPush(in ComponentName activity, in NdefMessage msg);
    void enableForegroundNdefPushWithCallback(in ComponentName activity, in INdefPushCallback callback);
    void disableForegroundNdefPush(in ComponentName activity);

    // Non-public methods
    boolean disable();
    boolean enable();
    boolean enableZeroClick();
    boolean disableZeroClick();
    boolean zeroClickEnabled();
}
