/*
 * Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard.data.quickaffordance

/**
 * Unique identifier keys for all known built-in quick affordances.
 *
 * Please ensure uniqueness by never associating more than one class with each key.
 */
object BuiltInKeyguardQuickAffordanceKeys {
    // Please keep alphabetical order of const names to simplify future maintenance.
    const val CAMERA = "camera"
    const val CREATE_NOTE = "create_note"
    const val DO_NOT_DISTURB = "do_not_disturb"
    const val FLASHLIGHT = "flashlight"
    const val HOME_CONTROLS = "home"
    const val MUTE = "mute"
    const val QR_CODE_SCANNER = "qr_code_scanner"
    const val QUICK_ACCESS_WALLET = "wallet"
    const val VIDEO_CAMERA = "video_camera"
    // Please keep alphabetical order of const names to simplify future maintenance.
}
