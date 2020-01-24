/*
  Copyright (C) 2019 The Android Open Source Project

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */

package android.widget;

/**
 * Keeps the flags related to the Widget namespace in {@link DeviceConfig}.
 *
 * @hide
 */
public final class WidgetFlags {

    /**
     * Whether the cursor control feature set is enabled.
     * TODO: Makes this flag key visible to webview/chrome.
     */
    public static final String ENABLE_CURSOR_CONTROL =
            "CursorControlFeature__enable_cursor_control";

    /**
     * The key name used in app core settings for enable cursor control.
     */
    public static final String KEY_ENABLE_CURSOR_CONTROL = "widget__enable_cursor_control";

    /**
     * The flag of delta height applies to the insertion handle when cursor control flag is enabled.
     * The default value is 25.
     */
    public static final String INSERTION_HANDLE_DELTA_HEIGHT =
            "CursorControlFeature__insertion_handle_delta_height";

    /**
     * The key name used in app core settings for {@link #INSERTION_HANDLE_DELTA_HEIGHT}.
     */
    public static final String KEY_INSERTION_HANDLE_DELTA_HEIGHT =
            "widget__insertion_handle_delta_height";

    /**
     * The flag of opacity applies to the insertion handle when cursor control flag is enabled.
     * The opacity value is in the range of {0..100}. The default value is 50.
     */
    public static final String INSERTION_HANDLE_OPACITY =
            "CursorControlFeature__insertion_handle_opacity";

    /**
     * The key name used in app core settings for {@link #INSERTION_HANDLE_OPACITY}.
     */
    public static final String KEY_INSERTION_HANDLE_OPACITY =
            "widget__insertion_handle_opacity";

    /**
     * The flag of enabling the new magnifier.
     */
    public static final String ENABLE_NEW_MAGNIFIER = "CursorControlFeature__enable_new_magnifier";

    /**
     * The key name used in app core settings for {@link #ENABLE_NEW_MAGNIFIER}.
     */
    public static final String KEY_ENABLE_NEW_MAGNIFIER = "widget__enable_new_magnifier";

    /**
     * The flag of zoom factor applies to the new magnifier.
     * The default value is 1.5f.
     */
    public static final String MAGNIFIER_ZOOM_FACTOR =
            "CursorControlFeature__magnifier_zoom_factor";

    /**
     * The key name used in app core settings for {@link #MAGNIFIER_ZOOM_FACTOR}.
     */
    public static final String KEY_MAGNIFIER_ZOOM_FACTOR = "widget__magnifier_zoom_factor";

    /**
     * The flag of aspect ratio (width/height) applies to the new magnifier.
     * The default value is 5.5f.
     */
    public static final String MAGNIFIER_ASPECT_RATIO =
            "CursorControlFeature__magnifier_aspect_ratio";

    /**
     * The key name used in app core settings for {@link #MAGNIFIER_ASPECT_RATIO}.
     */
    public static final String KEY_MAGNIFIER_ASPECT_RATIO = "widget__magnifier_aspect_ratio";

    private WidgetFlags() {
    }
}
