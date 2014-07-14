/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.telecomm;

/** Defines how numbers and names are displayed in caller id. */
public class CallPropertyPresentation {
    /** Property is displayed normally. */
    public static final int ALLOWED = 0;

    /** Property was blocked. */
    public static final int RESTRICTED = 1;

    /** Presentation was not specified or is unknown. */
    public static final int UNKNOWN = 2;

    /** Property should be displayed as a pay phone. */
    public static final int PAYPHONE = 3;
}
