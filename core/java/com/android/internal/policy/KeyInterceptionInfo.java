/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.policy;


/**
 * Stores a snapshot of window information used to decide whether to intercept a key event.
 */
public class KeyInterceptionInfo {
    // Window layout params attributes.
    public final int layoutParamsType;
    public final int layoutParamsPrivateFlags;
    // Debug friendly name to help identify the window
    public final String windowTitle;
    public final int windowOwnerUid;

    public KeyInterceptionInfo(int type, int flags, String title, int uid) {
        layoutParamsType = type;
        layoutParamsPrivateFlags = flags;
        windowTitle = title;
        windowOwnerUid = uid;
    }
}
