/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.widget;

/**
 * {@link com.android.internal.logging.MetricsLogger} values for TextView.
 *
 * @hide
 */
public final class TextViewMetrics {

    private TextViewMetrics() {}

    /**
     * Long press on TextView - no special classification.
     */
    public static final int SUBTYPE_LONG_PRESS_OTHER = 0;
    /**
     * Long press on TextView - selection started.
     */
    public static final int SUBTYPE_LONG_PRESS_SELECTION = 1;
    /**
     * Long press on TextView - drag and drop started.
     */
    public static final int SUBTYPE_LONG_PRESS_DRAG_AND_DROP = 2;

    /**
     * Assist menu item (shown or clicked) - classification: other.
     */
    public static final int SUBTYPE_ASSIST_MENU_ITEM_OTHER = 0;

    /**
     * Assist menu item (shown or clicked) - classification: email.
     */
    public static final int SUBTYPE_ASSIST_MENU_ITEM_EMAIL = 1;

    /**
     * Assist menu item (shown or clicked) - classification: phone.
     */
    public static final int SUBTYPE_ASSIST_MENU_ITEM_PHONE = 2;

    /**
     * Assist menu item (shown or clicked) - classification: address.
     */
    public static final int SUBTYPE_ASSIST_MENU_ITEM_ADDRESS = 3;

    /**
     * Assist menu item (shown or clicked) - classification: url.
     */
    public static final int SUBTYPE_ASSIST_MENU_ITEM_URL = 4;
}
