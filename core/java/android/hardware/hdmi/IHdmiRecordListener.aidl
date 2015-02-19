/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.hdmi;

/**
 * @hide
 */
 interface IHdmiRecordListener {
     /**
      * Called when TV received one touch record request from record device.
      *
      * @param recorderAddress
      * @return record source in byte array.
      */
     byte[] getOneTouchRecordSource(int recorderAddress);

     /**
      * Called when one touch record is started or failed during initialization.
      *
      * @param recorderAddress An address of recorder that reports result of one touch record
      *            request
      * @param result result code for one touch record
      */
     void onOneTouchRecordResult(int recorderAddress, int result);
     /**
      * Called when timer recording is started or failed during initialization.
      *
      * @param recorderAddress An address of recorder that reports result of timer recording
      *            request
      * @param result result code for timer recording
      */
     void onTimerRecordingResult(int recorderAddress, int result);
     /**
      * Called when receiving result for clear timer recording request.
      *
      * @param recorderAddress An address of recorder that reports result of clear timer recording
      *            request
      * @param result result of clear timer
      */
     void onClearTimerRecordingResult(int recorderAddress, int result);
 }