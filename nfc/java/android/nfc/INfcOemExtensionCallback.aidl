/*
 * Copyright 2024 The Android Open Source Project
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

import android.nfc.Tag;
import android.os.ResultReceiver;

/**
 * @hide
 */
interface INfcOemExtensionCallback {
   void onTagConnected(boolean connected, in Tag tag);
   void onStateUpdated(int state);
   void onApplyRouting(in ResultReceiver isSkipped);
   void onNdefRead(in ResultReceiver isSkipped);
   void onEnable(in ResultReceiver isAllowed);
   void onDisable(in ResultReceiver isAllowed);
   void onBootStarted();
   void onEnableStarted();
   void onDisableStarted();
   void onBootFinished(int status);
   void onEnableFinished(int status);
   void onDisableFinished(int status);
   void onTagDispatch(in ResultReceiver isSkipped);
   void onRoutingChanged();
   void onHceEventReceived(int action);
   void onReaderOptionChanged(boolean enabled);
   void onCardEmulationActivated(boolean isActivated);
   void onRfFieldActivated(boolean isActivated);
   void onRfDiscoveryStarted(boolean isDiscoveryStarted);
}
