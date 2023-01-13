/**
 * Copyright (c) 2022, The Android Open Source Project
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

package android.app;

import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.CompatibilityInfo;
import android.os.Bundle;

/**
 * Collect the information needed for manifest and registered receivers into a single structure
 * that can be the element of a list.  All fields are already parcelable.
 * @hide
 */
parcelable ReceiverInfo {
    /**
     * Fields common to registered and manifest receivers.
     */
    Intent intent;
    String data;
    Bundle extras;
    boolean assumeDelivered;
    int sendingUser;
    int processState;
    int resultCode;

    /**
     * True if this instance represents a registered receiver and false if this instance
     * represents a manifest receiver.
     */
    boolean registered;

    /**
     * Fields used only for registered receivers.
     */
    IIntentReceiver receiver;
    boolean ordered;
    boolean sticky;

    /**
     * Fields used only for manifest receivers.
     */
    ActivityInfo activityInfo;
    CompatibilityInfo compatInfo;
    boolean sync;
}
