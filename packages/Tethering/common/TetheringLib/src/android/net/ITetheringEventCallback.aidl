/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net;

import android.net.Network;
import android.net.TetheringConfigurationParcel;
import android.net.TetherStatesParcel;

/**
 * Callback class for receiving tethering changed events.
 * @hide
 */
oneway interface ITetheringEventCallback
{
    void onCallbackStarted(in Network network, in TetheringConfigurationParcel config,
            in TetherStatesParcel states);
    void onCallbackStopped(int errorCode);
    void onUpstreamChanged(in Network network);
    void onConfigurationChanged(in TetheringConfigurationParcel config);
    void onTetherStatesChanged(in TetherStatesParcel states);
}
