/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.bluetooth;

/**
 * API for Bluetooth Headset Phone Service in phone app
 *
 * {@hide}
 */
interface IBluetoothHeadsetPhone {
  // Internal functions, not be made public
  boolean answerCall();
  boolean hangupCall();
  boolean sendDtmf(int dtmf);
  boolean processChld(int chld);
  String getNetworkOperator();
  String getSubscriberNumber();
  boolean listCurrentCalls();
  boolean queryPhoneState();

  // Internal for phone app to call
  void updateBtHandsfreeAfterRadioTechnologyChange();
  void cdmaSwapSecondCallState();
  void cdmaSetSecondCallState(boolean state);
}
