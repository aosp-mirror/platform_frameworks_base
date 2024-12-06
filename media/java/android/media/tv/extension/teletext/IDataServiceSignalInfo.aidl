/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.media.tv.extension.teletext;

import android.media.tv.extension.teletext.IDataServiceSignalInfoListener;
import android.os.Bundle;


/**
 * @hide
 */
interface IDataServiceSignalInfo {
     // Get Teletext data service signal information.
     Bundle getDataServiceSignalInfo(String sessionToken);
     // Add a listener that receives notifications of teletext running information.
     void addDataServiceSignalInfoListener(String clientToken,
        IDataServiceSignalInfoListener listener);
     // Remove a listener that receives notifications of Teletext running information.
     void removeDataServiceSignalInfoListener(String clientToken,
        IDataServiceSignalInfoListener listener);
}
