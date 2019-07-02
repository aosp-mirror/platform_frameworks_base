/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.signedconfig;

/**
 * Thrown when there is a problem parsing the config embedded in an APK.
 */
public class InvalidConfigException extends Exception {

    public InvalidConfigException(String message) {
        super(message);
    }

    public InvalidConfigException(String message, Exception cause) {
        super(message, cause);
    }


}
