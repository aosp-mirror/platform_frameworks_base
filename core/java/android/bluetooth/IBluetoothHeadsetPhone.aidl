/*
 * Copyright (C) 2012 Google Inc.
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

  // Internal for phone app to call
  void updateBtHandsfreeAfterRadioTechnologyChange();
  void cdmaSwapSecondCallState();
  void cdmaSetSecondCallState(boolean state);
}
