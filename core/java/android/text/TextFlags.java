/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.text;

/**
 * Flags in the "text" namespace.
 *
 * @hide
 */
public final class TextFlags {

    /**
     * The name space of the "text" feature.
     *
     * This needs to move to DeviceConfig constant.
     */
    public static final String NAMESPACE = "text";

    /**
     * Whether we use the new design of context menu.
     */
    public static final String ENABLE_NEW_CONTEXT_MENU =
            "TextEditing__enable_new_context_menu";

    /**
     * The key name used in app core settings for {@link #ENABLE_NEW_CONTEXT_MENU}.
     */
    public static final String KEY_ENABLE_NEW_CONTEXT_MENU = "text__enable_new_context_menu";

    /**
     * Default value for the flag {@link #ENABLE_NEW_CONTEXT_MENU}.
     */
    public static final boolean ENABLE_NEW_CONTEXT_MENU_DEFAULT = false;

}
