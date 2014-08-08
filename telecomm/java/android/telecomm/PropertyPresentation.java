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

/**
 * Defines how properties such as phone numbers and names are displayed to the user.
 */
public class PropertyPresentation {
    /** Property is displayed normally. */
    public static final int ALLOWED = 1;

    /** Property was blocked. */
    public static final int RESTRICTED = 2;

    /** Presentation was not specified or is unknown. */
    public static final int UNKNOWN = 3;

    /** Property should be displayed as a pay phone. */
    public static final int PAYPHONE = 4;
}
