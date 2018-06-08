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

package android.provider;

import android.content.ContentResolver;
import android.net.Uri;

/**
 * Provides a contract for platform-supported Settings {@link android.app.slice.Slice Slices}.
 * <p>
 * Contains definitions for the supported {@link android.app.slice.SliceProvider SliceProvider}
 * authority, authority {@link Uri}, and key constants.
 * <p>
 * {@link android.app.slice.Slice Slice} presenters interested in learning meta-data about the
 * {@link android.app.slice.Slice Slice} should read the {@link android.app.slice.Slice Slice}
 * object at runtime.
 * <p>
 * {@link Uri} builder example:
 * <pre>
 * Uri wifiActionUri = BASE_URI
 *         .buildUpon()
 *         .appendPath(PATH_SETTING_ACTION)
 *         .appendPath(KEY_WIFI)
 *         .build();
 * Uri bluetoothIntentUri = BASE_URI
 *         .buildUpon()
 *         .appendPath(PATH_SETTING_INTENT)
 *         .appendPath(KEY_BLUETOOTH)
 *         .build();
 * </pre>
 */
public class SettingsSlicesContract {
    private SettingsSlicesContract() {
    }

    /**
     * Authority for platform Settings Slices.
     */
    public static final String AUTHORITY = "android.settings.slices";

    /**
     * A content:// style uri to the Settings Slices authority, {@link #AUTHORITY}.
     */
    public static final Uri BASE_URI = new Uri.Builder()
            .scheme(ContentResolver.SCHEME_CONTENT)
            .authority(AUTHORITY)
            .build();

    /**
     * {@link Uri} path indicating that the requested {@link android.app.slice.Slice Slice} should
     * have inline controls for the corresponding setting.
     * <p>
     * This path will only contain Slices defined by keys in this class.
     */
    public static final String PATH_SETTING_ACTION = "action";

    /**
     * {@link Uri} path indicating that the requested {@link android.app.slice.Slice Slice} should
     * be {@link android.content.Intent Intent}-only.
     * <p>
     * {@link android.app.slice.Slice Slices} with actions should use the {@link
     * #PATH_SETTING_ACTION} path.
     * <p>
     * This path will only contain Slices defined by keys in this class
     */
    public static final String PATH_SETTING_INTENT = "intent";

    /**
     * {@link Uri} key for the Airplane Mode setting.
     */
    public static final String KEY_AIRPLANE_MODE = "airplane_mode";

    /**
     * {@link Uri} key for the Battery Saver setting.
     */
    public static final String KEY_BATTERY_SAVER = "battery_saver";

    /**
     * {@link Uri} key for the Bluetooth setting.
     */
    public static final String KEY_BLUETOOTH = "bluetooth";

    /**
     * {@link Uri} key for the Location setting.
     */
    public static final String KEY_LOCATION = "location";

    /**
     * {@link Uri} key for the Wi-fi setting.
     */
    public static final String KEY_WIFI = "wifi";
}
