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

package android.gesture;

interface GestureConstants {
    static final int STROKE_STRING_BUFFER_SIZE = 1024;
    static final int STROKE_POINT_BUFFER_SIZE = 100; // number of points

    static final int IO_BUFFER_SIZE = 32 * 1024; // 32K

    static final String LOG_TAG = "Gestures";
}
