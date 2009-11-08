/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.webkit;

/**
 * This class is simply a container for the methods used to configure WebKit's
 * mock Geolocation service for use in LayoutTests.
 * @hide
 */
public final class MockGeolocation {

    // Global instance of a MockGeolocation
    private static MockGeolocation sMockGeolocation;

    /**
     * Set the position for the mock Geolocation service.
     */
    public void setPosition(double latitude, double longitude, double accuracy) {
        // This should only ever be called on the WebKit thread.
        nativeSetPosition(latitude, longitude, accuracy);
    }

    /**
     * Set the error for the mock Geolocation service.
     */
    public void setError(int code, String message) {
        // This should only ever be called on the WebKit thread.
        nativeSetError(code, message);
    }

    /**
     * Get the global instance of MockGeolocation.
     * @return The global MockGeolocation instance.
     */
    public static MockGeolocation getInstance() {
      if (sMockGeolocation == null) {
          sMockGeolocation = new MockGeolocation();
      }
      return sMockGeolocation;
    }

    // Native functions
    private static native void nativeSetPosition(double latitude, double longitude, double accuracy);
    private static native void nativeSetError(int code, String message);
}
