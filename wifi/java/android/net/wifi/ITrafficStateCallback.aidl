/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.net.wifi;

/**
 * Interface for Traffic state callback.
 *
 * @hide
 */
oneway interface ITrafficStateCallback
{
   /**
    * Callback invoked to inform clients about the current traffic state.
    *
    * @param state One of the values: {@link #DATA_ACTIVITY_NONE}, {@link #DATA_ACTIVITY_IN},
    * {@link #DATA_ACTIVITY_OUT} & {@link #DATA_ACTIVITY_INOUT}.
    * @hide
    */
   void onStateChanged(int state);
}
