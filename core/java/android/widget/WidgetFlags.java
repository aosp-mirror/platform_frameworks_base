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
 * Flags in the {@link android.provider.DeviceConfig#NAMESPACE_WIDGET "widget" namespace}.
 *
 * @hide
 */
public final class WidgetFlags {

    /**
     * Whether starting a cursor drag from anywhere in the text should be enabled.
     */
    public static final String ENABLE_CURSOR_DRAG_FROM_ANYWHERE =
            "CursorControlFeature__enable_cursor_drag_from_anywhere";

    /**
     * The key used in app core settings for the flag {@link #ENABLE_CURSOR_DRAG_FROM_ANYWHERE}.
     */
    public static final String KEY_ENABLE_CURSOR_DRAG_FROM_ANYWHERE =
            "widget__enable_cursor_drag_from_anywhere";

    /**
     * Default value for the flag {@link #ENABLE_CURSOR_DRAG_FROM_ANYWHERE}.
     */
    public static final boolean ENABLE_CURSOR_DRAG_FROM_ANYWHERE_DEFAULT = true;

    /**
     * Threshold for the direction of a swipe gesture in order for it to be handled as a cursor drag
     * rather than a scroll. The direction angle of the swipe gesture must exceed this value in
     * order to trigger cursor drag; otherwise, the swipe will be assumed to be a scroll gesture.
     * The value units for this flag is degrees and the valid range is [0,90] inclusive. If a value
     * < 0 is set, 0 will be used instead; if a value > 90 is set, 90 will be used instead.
     */
    public static final String CURSOR_DRAG_MIN_ANGLE_FROM_VERTICAL =
            "CursorControlFeature__min_angle_from_vertical_to_start_cursor_drag";

    /**
     * The key used in app core settings for the flag
     * {@link #CURSOR_DRAG_MIN_ANGLE_FROM_VERTICAL}.
     */
    public static final String KEY_CURSOR_DRAG_MIN_ANGLE_FROM_VERTICAL =
            "widget__min_angle_from_vertical_to_start_cursor_drag";

    /**
     * Default value for the flag {@link #CURSOR_DRAG_MIN_ANGLE_FROM_VERTICAL}.
     */
    public static final int CURSOR_DRAG_MIN_ANGLE_FROM_VERTICAL_DEFAULT = 45;

    /**
     * The flag of finger-to-cursor distance in DP for cursor dragging.
     * The value unit is DP and the range is {0..100}. If the value is out of range, the legacy
     * value, which is based on handle size, will be used.
     */
    public static final String FINGER_TO_CURSOR_DISTANCE =
            "CursorControlFeature__finger_to_cursor_distance";

    /**
     * The key used in app core settings for the flag {@link #FINGER_TO_CURSOR_DISTANCE}.
     */
    public static final String KEY_FINGER_TO_CURSOR_DISTANCE =
            "widget__finger_to_cursor_distance";

    /**
     * Default value for the flag {@link #FINGER_TO_CURSOR_DISTANCE}.
     */
    public static final int FINGER_TO_CURSOR_DISTANCE_DEFAULT = -1;

    /**
     * Whether additional gestures should be enabled for the insertion cursor handle (e.g.
     * long-press or double-tap on the handle to trigger selection).
     */
    public static final String ENABLE_INSERTION_HANDLE_GESTURES =
            "CursorControlFeature__enable_insertion_handle_gestures";

    /**
     * The key used in app core settings for the flag {@link #ENABLE_INSERTION_HANDLE_GESTURES}.
     */
    public static final String KEY_ENABLE_INSERTION_HANDLE_GESTURES =
            "widget__enable_insertion_handle_gestures";

    /**
     * Default value for the flag {@link #ENABLE_INSERTION_HANDLE_GESTURES}.
     */
    public static final boolean ENABLE_INSERTION_HANDLE_GESTURES_DEFAULT = false;

    /**
     * The flag of delta height applies to the insertion handle when cursor control flag is enabled.
     */
    public static final String INSERTION_HANDLE_DELTA_HEIGHT =
            "CursorControlFeature__insertion_handle_delta_height";

    /**
     * The key name used in app core settings for {@link #INSERTION_HANDLE_DELTA_HEIGHT}.
     */
    public static final String KEY_INSERTION_HANDLE_DELTA_HEIGHT =
            "widget__insertion_handle_delta_height";

    /**
     * Default value for the flag {@link #INSERTION_HANDLE_DELTA_HEIGHT}.
     */
    public static final int INSERTION_HANDLE_DELTA_HEIGHT_DEFAULT = 25;

    /**
     * The flag of opacity applies to the insertion handle when cursor control flag is enabled.
     * The opacity value is in the range of {0..100}.
     */
    public static final String INSERTION_HANDLE_OPACITY =
            "CursorControlFeature__insertion_handle_opacity";

    /**
     * The key name used in app core settings for {@link #INSERTION_HANDLE_OPACITY}.
     */
    public static final String KEY_INSERTION_HANDLE_OPACITY =
            "widget__insertion_handle_opacity";

    /**
     * Default value for the flag {@link #INSERTION_HANDLE_OPACITY}.
     */
    public static final int INSERTION_HANDLE_OPACITY_DEFAULT = 50;

    /**
     * The flag of enabling the new magnifier.
     */
    public static final String ENABLE_NEW_MAGNIFIER = "CursorControlFeature__enable_new_magnifier";

    /**
     * The key name used in app core settings for {@link #ENABLE_NEW_MAGNIFIER}.
     */
    public static final String KEY_ENABLE_NEW_MAGNIFIER = "widget__enable_new_magnifier";

    /**
     * Default value for the flag {@link #ENABLE_NEW_MAGNIFIER}.
     */
    public static final boolean ENABLE_NEW_MAGNIFIER_DEFAULT = false;

    /**
     * The flag of zoom factor applies to the new magnifier.
     */
    public static final String MAGNIFIER_ZOOM_FACTOR =
            "CursorControlFeature__magnifier_zoom_factor";

    /**
     * The key name used in app core settings for {@link #MAGNIFIER_ZOOM_FACTOR}.
     */
    public static final String KEY_MAGNIFIER_ZOOM_FACTOR = "widget__magnifier_zoom_factor";

    /**
     * Default value for the flag {@link #MAGNIFIER_ZOOM_FACTOR}.
     */
    public static final float MAGNIFIER_ZOOM_FACTOR_DEFAULT = 1.5f;

    /**
     * The flag of aspect ratio (width/height) applies to the new magnifier.
     */
    public static final String MAGNIFIER_ASPECT_RATIO =
            "CursorControlFeature__magnifier_aspect_ratio";

    /**
     * The key name used in app core settings for {@link #MAGNIFIER_ASPECT_RATIO}.
     */
    public static final String KEY_MAGNIFIER_ASPECT_RATIO = "widget__magnifier_aspect_ratio";

    /**
     * Default value for the flag {@link #MAGNIFIER_ASPECT_RATIO}.
     */
    public static final float MAGNIFIER_ASPECT_RATIO_DEFAULT = 5.5f;

    private WidgetFlags() {
    }
}
