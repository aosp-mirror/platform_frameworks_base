/*
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

package android.service.controls;

import android.service.controls.IControlsActionCallback;
import android.service.controls.IControlsSubscriber;
import android.service.controls.actions.ControlActionWrapper;

/**
 * @hide
 */
oneway interface IControlsProvider {
    void load(IControlsSubscriber subscriber);

    void loadSuggested(IControlsSubscriber subscriber);

    void subscribe(in List<String> controlIds,
             IControlsSubscriber subscriber);

    void action(in String controlId, in ControlActionWrapper action,
             IControlsActionCallback cb);
}