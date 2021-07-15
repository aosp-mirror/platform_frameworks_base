/* //device/java/android/android/os/IRecoverySystem.aidl
**
** Copyright 2016, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package android.os;

import android.content.IntentSender;
import android.os.IRecoverySystemProgressListener;

/** @hide */

interface IRecoverySystem {
    boolean allocateSpaceForUpdate(in String packageFilePath);
    boolean uncrypt(in String packageFile, IRecoverySystemProgressListener listener);
    boolean setupBcb(in String command);
    boolean clearBcb();
    void rebootRecoveryWithCommand(in String command);
    boolean requestLskf(in String packageName, in IntentSender sender);
    boolean clearLskf(in String packageName);
    boolean isLskfCaptured(in String packageName);
    int rebootWithLskfAssumeSlotSwitch(in String packageName, in String reason);
    int rebootWithLskf(in String packageName, in String reason, in boolean slotSwitch);
}
