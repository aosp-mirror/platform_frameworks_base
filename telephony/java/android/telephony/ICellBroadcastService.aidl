/**
 * Copyright (c) 2019, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.telephony;

import android.os.RemoteCallback;
import android.telephony.cdma.CdmaSmsCbProgramData;

/**
 * Service bound to by the system to allow custom handling of cell broadcast messages.
 * <p>
 * @see android.telephony.CellBroadcastService
 * @hide
 */
interface ICellBroadcastService {

    /** @see android.telephony.CellBroadcastService#onGsmCellBroadcastSms */
    oneway void handleGsmCellBroadcastSms(int slotId, in byte[] message);

    /** @see android.telephony.CellBroadcastService#onCdmaCellBroadcastSms */
    oneway void handleCdmaCellBroadcastSms(int slotId, in byte[] bearerData, int serviceCategory);

    /** @see android.telephony.CellBroadcastService#onCdmaScpMessage */
    oneway void handleCdmaScpMessage(int slotId, in List<CdmaSmsCbProgramData> programData,
            String originatingAddress, in RemoteCallback callback);

    /** @see android.telephony.CellBroadcastService#getCellBroadcastAreaInfo */
    CharSequence getCellBroadcastAreaInfo(int slotIndex);
}
