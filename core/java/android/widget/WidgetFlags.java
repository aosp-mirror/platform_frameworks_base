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

    private WidgetFlags() {
    }
}
