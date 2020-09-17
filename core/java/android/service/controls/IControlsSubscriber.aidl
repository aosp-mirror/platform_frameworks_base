/*
 * Copyright (c) 2020, The Android Open Source Project
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

package android.service.controls;

import android.service.controls.Control;
import android.service.controls.IControlsSubscription;

/**
 * @hide
 */
oneway interface IControlsSubscriber {
    void onSubscribe(in IBinder token, in IControlsSubscription cs);
    void onNext(in IBinder token, in Control c);
    void onError(in IBinder token, in String s);
    void onComplete(in IBinder token);
}