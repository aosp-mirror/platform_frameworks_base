// Copyright 2008, The Android Open Source Project
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
//  1. Redistributions of source code must retain the above copyright notice,
//     this list of conditions and the following disclaimer.
//  2. Redistributions in binary form must reproduce the above copyright notice,
//     this list of conditions and the following disclaimer in the documentation
//     and/or other materials provided with the distribution.
//  3. Neither the name of Google Inc. nor the names of its contributors may be
//     used to endorse or promote products derived from this software without
//     specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED
// WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
// MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
// EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
// SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
// PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
// OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
// WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
// OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
// ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package android.webkit.gears;

import android.content.Context;
import android.telephony.CellLocation;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.gsm.GsmCellLocation;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.webkit.WebView;

/**
 * Radio data provider implementation for Android.
 */
public final class AndroidRadioDataProvider extends PhoneStateListener {

  /** Logging tag */
  private static final String TAG = "Gears-J-RadioProvider";

  /** Network types */
  private static final int RADIO_TYPE_UNKNOWN = 0;
  private static final int RADIO_TYPE_GSM = 1;
  private static final int RADIO_TYPE_WCDMA = 2;
  private static final int RADIO_TYPE_CDMA = 3;
  private static final int RADIO_TYPE_EVDO = 4;
  private static final int RADIO_TYPE_1xRTT = 5;

  /** Simple container for radio data */
  public static final class RadioData {
    public int cellId = -1;
    public int locationAreaCode = -1;
    // TODO: use new SignalStrength instead of asu
    public int signalStrength = -1;
    public int mobileCountryCode = -1;
    public int mobileNetworkCode = -1;
    public int homeMobileCountryCode = -1;
    public int homeMobileNetworkCode = -1;
    public int radioType = RADIO_TYPE_UNKNOWN;
    public String carrierName;

    /**
     * Constructs radioData object from the given telephony data.
     * @param telephonyManager contains the TelephonyManager instance.
     * @param cellLocation contains information about the current GSM cell.
     * @param signalStrength is the strength of the network signal.
     * @param serviceState contains information about the network service.
     * @return a new RadioData object populated with the currently
     *         available network information or null if there isn't
     *         enough information.
     */
    public static RadioData getInstance(TelephonyManager telephonyManager,
        CellLocation cellLocation, int signalStrength,
        ServiceState serviceState) {

      if (!(cellLocation instanceof GsmCellLocation)) {
        // This also covers the case when cellLocation is null.
        // When that happens, we do not bother creating a
        // RadioData instance.
        return null;
      }

      RadioData radioData = new RadioData();
      GsmCellLocation gsmCellLocation = (GsmCellLocation) cellLocation;

      // Extract the cell id, LAC, and signal strength.
      radioData.cellId = gsmCellLocation.getCid();
      radioData.locationAreaCode = gsmCellLocation.getLac();
      radioData.signalStrength = signalStrength;

      // Extract the home MCC and home MNC.
      String operator = telephonyManager.getSimOperator();
      radioData.setMobileCodes(operator, true);

      if (serviceState != null) {
        // Extract the carrier name.
        radioData.carrierName = serviceState.getOperatorAlphaLong();

        // Extract the MCC and MNC.
        operator = serviceState.getOperatorNumeric();
        radioData.setMobileCodes(operator, false);
      }

      // Finally get the radio type.
      //TODO We have to edit the parameter for getNetworkType regarding CDMA
      int type = telephonyManager.getNetworkType();
      if (type == TelephonyManager.NETWORK_TYPE_UMTS) {
        radioData.radioType = RADIO_TYPE_WCDMA;
      } else if (type == TelephonyManager.NETWORK_TYPE_GPRS
                 || type == TelephonyManager.NETWORK_TYPE_EDGE) {
        radioData.radioType = RADIO_TYPE_GSM;
      } else if (type == TelephonyManager.NETWORK_TYPE_CDMA) {
          radioData.radioType = RADIO_TYPE_CDMA;
      } else if (type == TelephonyManager.NETWORK_TYPE_EVDO_0) {
          radioData.radioType = RADIO_TYPE_EVDO;
      } else if (type == TelephonyManager.NETWORK_TYPE_EVDO_A) {
          radioData.radioType = RADIO_TYPE_EVDO;
      } else if (type == TelephonyManager.NETWORK_TYPE_1xRTT) {
          radioData.radioType = RADIO_TYPE_1xRTT;
      }

      // Print out what we got.
      Log.i(TAG, "Got the following data:");
      Log.i(TAG, "CellId: " + radioData.cellId);
      Log.i(TAG, "LAC: " + radioData.locationAreaCode);
      Log.i(TAG, "MNC: " + radioData.mobileNetworkCode);
      Log.i(TAG, "MCC: " + radioData.mobileCountryCode);
      Log.i(TAG, "home MNC: " + radioData.homeMobileNetworkCode);
      Log.i(TAG, "home MCC: " + radioData.homeMobileCountryCode);
      Log.i(TAG, "Signal strength: " + radioData.signalStrength);
      Log.i(TAG, "Carrier: " + radioData.carrierName);
      Log.i(TAG, "Network type: " + radioData.radioType);

      return radioData;
    }

    private RadioData() {}

    /**
     * Parses a string containing a mobile country code and a mobile
     * network code and sets the corresponding member variables.
     * @param codes is the string to parse.
     * @param homeValues flags whether the codes are for the home operator.
     */
    private void setMobileCodes(String codes, boolean homeValues) {
      if (codes != null) {
        try {
          // The operator numeric format is 3 digit country code plus 2 or
          // 3 digit network code.
          int mcc = Integer.parseInt(codes.substring(0, 3));
          int mnc = Integer.parseInt(codes.substring(3));
          if (homeValues) {
            homeMobileCountryCode = mcc;
            homeMobileNetworkCode = mnc;
          } else {
            mobileCountryCode = mcc;
            mobileNetworkCode = mnc;
          }
        } catch (IndexOutOfBoundsException ex) {
          Log.e(
              TAG,
              "AndroidRadioDataProvider: Invalid operator numeric data: " + ex);
        } catch (NumberFormatException ex) {
          Log.e(
              TAG,
              "AndroidRadioDataProvider: Operator numeric format error: " + ex);
        }
      }
    }
  };

  /** The native object ID */
  private long nativeObject;

  /** The last known cellLocation */
  private CellLocation cellLocation = null;

  /** The last known signal strength */
  // TODO: use new SignalStrength instead of asu
  private int signalStrength = -1;

  /** The last known serviceState */
  private ServiceState serviceState = null;

  /**
   * Our TelephonyManager instance.
   */
  private TelephonyManager telephonyManager;

  /**
   * Public constructor. Uses the webview to get the Context object.
   */
  public AndroidRadioDataProvider(WebView webview, long object) {
    super();
    nativeObject = object;
    telephonyManager = (TelephonyManager) webview.getContext().getSystemService(
        Context.TELEPHONY_SERVICE);
    if (telephonyManager == null) {
      Log.e(TAG,
          "AndroidRadioDataProvider: could not get tepephony manager.");
      throw new NullPointerException(
          "AndroidRadioDataProvider: telephonyManager is null.");
    }

    // Register for cell id, signal strength and service state changed
    // notifications.
    telephonyManager.listen(this, PhoneStateListener.LISTEN_CELL_LOCATION
        | PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
        | PhoneStateListener.LISTEN_SERVICE_STATE);
  }

  /**
   * Should be called when the provider is no longer needed.
   */
  public void shutdown() {
    telephonyManager.listen(this, PhoneStateListener.LISTEN_NONE);
    Log.i(TAG, "AndroidRadioDataProvider shutdown.");
  }

  @Override
  public void onServiceStateChanged(ServiceState state) {
    serviceState = state;
    notifyListeners();
  }

  @Override
  public void onSignalStrengthsChanged(SignalStrength ss) {
    int gsmSignalStrength = ss.getGsmSignalStrength();
    signalStrength = (gsmSignalStrength == 99 ? -1 : gsmSignalStrength);
    notifyListeners();
  }

  @Override
  public void onCellLocationChanged(CellLocation location) {
    cellLocation = location;
    notifyListeners();
  }

  private void notifyListeners() {
    RadioData radioData = RadioData.getInstance(telephonyManager, cellLocation,
        signalStrength, serviceState);
    if (radioData != null) {
      onUpdateAvailable(radioData, nativeObject);
    }
  }

  /**
   * The native method called when new radio data is available.
   * @param radioData is the RadioData instance to pass to the native side.
   * @param nativeObject is a pointer to the corresponding
   * AndroidRadioDataProvider C++ instance.
   */
  private static native void onUpdateAvailable(
      RadioData radioData, long nativeObject);
}
