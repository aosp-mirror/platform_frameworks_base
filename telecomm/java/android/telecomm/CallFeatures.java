/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package android.telecomm;

/**
 * Defines features of a call.  These features represent properties of a call for which it may be
 * desirable to display an in-call indicator.
 */
public final class CallFeatures {
    private CallFeatures() {};

    public static final int NONE = 0x0;
    public static final int VoLTE = 0x1;
    public static final int VoWIFI = 0x2;
}