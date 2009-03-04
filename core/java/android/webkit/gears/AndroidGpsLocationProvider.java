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
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebView;

/**
 * GPS provider implementation for Android.
 */
public final class AndroidGpsLocationProvider implements LocationListener {
  /**
   * Logging tag
   */
  private static final String TAG = "Gears-J-GpsProvider";
  /**
   * Our location manager instance.
   */
  private LocationManager locationManager;
  /**
   * The native object ID.
   */
  private long nativeObject;

  public AndroidGpsLocationProvider(WebView webview, long object) {
    nativeObject = object;
    locationManager = (LocationManager) webview.getContext().getSystemService(
        Context.LOCATION_SERVICE);
    if (locationManager == null) {
      Log.e(TAG,
          "AndroidGpsLocationProvider: could not get location manager.");
      throw new NullPointerException(
          "AndroidGpsLocationProvider: locationManager is null.");
    }
    // Register for location updates.
    try {
      locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0,
          this);
    } catch (IllegalArgumentException ex) {
      Log.e(TAG,
          "AndroidLocationGpsProvider: could not register for updates: " + ex);
      throw ex;
    } catch (SecurityException ex) {
      Log.e(TAG,
          "AndroidGpsLocationProvider: not allowed to register for update: "
          + ex);
      throw ex;
    }
  }

  /**
   * Called when the provider is no longer needed.
   */
  public void shutdown() {
    locationManager.removeUpdates(this);
    Log.i(TAG, "GPS provider closed.");
  }

 /**
  * Called when the location has changed.
  * @param location The new location, as a Location object.
  */
  public void onLocationChanged(Location location) {
    Log.i(TAG, "Location changed: " + location);
    nativeLocationChanged(location, nativeObject);
  }

  /**
   * Called when the provider status changes.
   *
   * @param provider the name of the location provider associated with this
   * update.
   * @param status {@link LocationProvider#OUT_OF_SERVICE} if the
   * provider is out of service, and this is not expected to change in the
   * near future; {@link LocationProvider#TEMPORARILY_UNAVAILABLE} if
   * the provider is temporarily unavailable but is expected to be available
   * shortly; and {@link LocationProvider#AVAILABLE} if the
   * provider is currently available.
   * @param extras an optional Bundle which will contain provider specific
   * status variables (such as number of satellites).
   */
  public void onStatusChanged(String provider, int status, Bundle extras) {
    Log.i(TAG, "Provider " + provider + " status changed to " + status);
    if (status == LocationProvider.OUT_OF_SERVICE ||
        status == LocationProvider.TEMPORARILY_UNAVAILABLE) {
      nativeProviderError(false, nativeObject);
    }
  }

  /**
   * Called when the provider is enabled.
   *
   * @param provider the name of the location provider that is now enabled.
   */
  public void onProviderEnabled(String provider) {
    Log.i(TAG, "Provider " + provider + " enabled.");
    // No need to notify the native side. It's enough to start sending
    // valid position fixes again.
  }

  /**
   * Called when the provider is disabled.
   *
   * @param provider the name of the location provider that is now disabled.
   */
  public void onProviderDisabled(String provider) {
    Log.i(TAG, "Provider " + provider + " disabled.");
    nativeProviderError(true, nativeObject);
  }

  /**
   * The native method called when a new location is available.
   * @param location is the new Location instance to pass to the native side.
   * @param nativeObject is a pointer to the corresponding
   * AndroidGpsLocationProvider C++ instance.
   */
  private native void nativeLocationChanged(Location location, long object);

  /**
   * The native method called when there is a GPS provder error.
   * @param isDisabled is true when the error signifies the fact that the GPS
   * HW is disabled. For other errors, this param is always false.
   * @param nativeObject is a pointer to the corresponding
   * AndroidGpsLocationProvider C++ instance.
   */
  private native void nativeProviderError(boolean isDisabled, long object);
}
