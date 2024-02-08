/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.credentials;

/**
 * Constants for credential manager service that doesn't fit into other structures
 *
 * @hide
 */
public class Constants {
    /**
     * The request is success and user selected an entry
     */
    public static final int SUCCESS_CREDMAN_SELECTOR = 0;
    /**
     * The error code for ui getting cancelled by user
     */
    public static final int FAILURE_CREDMAN_SELECTOR = -1;
}
