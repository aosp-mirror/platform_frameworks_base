/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.util;

import java.io.IOException;

/**
 * This exception is thrown by {@link Base64InputStream} or {@link Base64OutputStream}
 * when an error is detected in the data being decoded.  This allows problems with the base64 data
 * to be disambiguated from errors in the underlying streams (e.g. actual connection errors.)
 */
public class Base64DataException extends IOException {
    public Base64DataException(String detailMessage) {
        super(detailMessage);
    }
}
