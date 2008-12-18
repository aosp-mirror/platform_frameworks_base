// Copyright 2008, Google Inc.
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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Config;
import android.util.Log;
import android.webkit.WebView;
import java.util.List;

/**
 * WiFi data provider implementation for Android.
 * {@hide}
 */
public final class AndroidWifiDataProvider extends BroadcastReceiver {
  /**
   * Logging tag
   */
  private static final String TAG = "Gears-J-WifiProvider";
  /**
   * Our Wifi manager instance.
   */
  private WifiManager mWifiManager;
  /**
   * The native object ID.
   */
  private long mNativeObject;
  /**
   * The Context instance.
   */
  private Context mContext;

  /**
   * Constructs a instance of this class and registers for wifi scan
   * updates. Note that this constructor must be called on a Looper
   * thread. Suitable threads can be created on the native side using
   * the AndroidLooperThread C++ class.
   */
  public AndroidWifiDataProvider(WebView webview, long object) {
    mNativeObject = object;
    mContext = webview.getContext();
    mWifiManager =
        (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
    if (mWifiManager == null) {
      Log.e(TAG,
          "AndroidWifiDataProvider: could not get location manager.");
      throw new NullPointerException(
          "AndroidWifiDataProvider: locationManager is null.");
    }

    // Create a Handler that identifies the message loop associated
    // with the current thread. Note that it is not necessary to
    // override handleMessage() at all since the Intent
    // ReceiverDispatcher (see the ActivityThread class) only uses
    // this handler to post a Runnable to this thread's loop.
    Handler handler = new Handler(Looper.myLooper());

    IntentFilter filter = new IntentFilter();
    filter.addAction(mWifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
    mContext.registerReceiver(this, filter, null, handler);

    // Get the last scan results and pass them to the native side.
    // We can't just invoke the callback here, so we queue a message
    // to this thread's loop.
    handler.post(new Runnable() {
        public void run() {
          onUpdateAvailable(mWifiManager.getScanResults(), mNativeObject);
        }
      });
  }

  /**
   * Called when the provider is no longer needed.
   */
  public void shutdown() {
    mContext.unregisterReceiver(this);
    if (Config.LOGV) {
      Log.v(TAG, "Wifi provider closed.");
    }
  }

  /**
   * This method is called when the AndroidWifiDataProvider is receiving an
   * Intent broadcast.
   * @param context The Context in which the receiver is running.
   * @param intent The Intent being received.
   */
  public void onReceive(Context context, Intent intent) {
    if (intent.getAction().equals(
            mWifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
      if (Config.LOGV) {
        Log.v(TAG, "Wifi scan resulst available");
      }
      onUpdateAvailable(mWifiManager.getScanResults(), mNativeObject);
    }
  }

 /**
   * The native method called when new wifi data is available.
   * @param scanResults is a list of ScanResults  to pass to the native side.
   * @param nativeObject is a pointer to the corresponding
   * AndroidWifiDataProvider C++ instance.
   */
  private static native void onUpdateAvailable(
      List<ScanResult> scanResults, long nativeObject);
}
