/*
 * Copyright 2022 The Android Open Source Project
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

package android.credentials.selection;

/**
 * Constants for the ui protocol that doesn't fit into other individual data structures.
 *
 * @hide
 */
public class Constants {

    /**
     * The intent extra key for the {@code ResultReceiver} object when launching the UX activities.
     */
    public static final String EXTRA_RESULT_RECEIVER =
            "android.credentials.selection.extra.RESULT_RECEIVER";

    /**
     * The intent extra key for indicating whether the bottom sheet should be started directly
     * on the 'All Options' screen.
     */
    public static final String EXTRA_REQ_FOR_ALL_OPTIONS =
            "android.credentials.selection.extra.REQ_FOR_ALL_OPTIONS";

    /**
     * The intent extra key for the final result receiver object
     */
    public static final String EXTRA_FINAL_RESPONSE_RECEIVER =
            "android.credentials.selection.extra.FINAL_RESPONSE_RECEIVER";

    private Constants() {}
}
