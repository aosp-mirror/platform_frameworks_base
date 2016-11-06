/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.wifi.aware;

/**
 * Provides utilities for the Wifi Aware manager/service.
 *
 * @hide
 */
public class WifiAwareUtils {
    /**
     * Per spec: The Service Name is a UTF-8 encoded string from 1 to 255 bytes in length. The
     * only acceptable single-byte UTF-8 symbols for a Service Name are alphanumeric values (A-Z,
     * a-z, 0-9), the hyphen ('-'), and the period ('.'). All valid multi-byte UTF-8 characters
     * are acceptable in a Service Name.
     */
    public static void validateServiceName(byte[] serviceNameData) throws IllegalArgumentException {
        if (serviceNameData == null) {
            throw new IllegalArgumentException("Invalid service name - null");
        }

        if (serviceNameData.length < 1 || serviceNameData.length > 255) {
            throw new IllegalArgumentException("Invalid service name length - must be between "
                    + "1 and 255 bytes (UTF-8 encoding)");
        }

        int index = 0;
        while (index < serviceNameData.length) {
            byte b = serviceNameData[index];
            if ((b & 0x80) == 0x00) {
                if (!((b >= '0' && b <= '9') || (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')
                        || b == '-' || b == '.')) {
                    throw new IllegalArgumentException("Invalid service name - illegal characters,"
                            + " allowed = (0-9, a-z,A-Z, -, .)");
                }
            }
            ++index;
        }
    }
}
