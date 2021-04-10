/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.net;

import android.annotation.NonNull;

/**
 * Thrown when parsing failed.
 */
// See non-public class {@link WebAddress}.
public class ParseException extends RuntimeException {
    public String response;

    public ParseException(@NonNull String response) {
        super(response);
        this.response = response;
    }

    public ParseException(@NonNull String response, @NonNull Throwable cause) {
        super(response, cause);
        this.response = response;
    }
}
