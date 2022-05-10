/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.app;

/**
 * This callback allows ActivityRecord to ask the calling View to apply the treatment for stretched
 * issues affecting camera viewfinders when the user clicks on the camera compat control.
 *
 * {@hide}
 */
oneway interface ICompatCameraControlCallback {

    void applyCameraCompatTreatment();

    void revertCameraCompatTreatment();
}
