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

package com.android.internal.telephony;

import android.util.Log;

/**
 * Wrapper class for an ICC EF containing a bit field of enabled services.
 */
public abstract class IccServiceTable {
    protected final byte[] mServiceTable;

    protected IccServiceTable(byte[] table) {
        mServiceTable = table;
    }

    // Get the class name to use for log strings
    protected abstract String getTag();

    // Get the array of enums to use for toString
    protected abstract Object[] getValues();

    /**
     * Returns if the specified service is available.
     * @param service the service number as a zero-based offset (the enum ordinal)
     * @return true if the service is available; false otherwise
     */
    protected boolean isAvailable(int service) {
        int offset = service / 8;
        if (offset >= mServiceTable.length) {
            // Note: Enums are zero-based, but the TS service numbering is one-based
            Log.e(getTag(), "isAvailable for service " + (service + 1) + " fails, max service is " +
                    (mServiceTable.length * 8));
            return false;
        }
        int bit = service % 8;
        return (mServiceTable[offset] & (1 << bit)) != 0;
    }

    public String toString() {
        Object[] values = getValues();
        int numBytes = mServiceTable.length;
        StringBuilder builder = new StringBuilder(getTag()).append('[')
                .append(numBytes * 8).append("]={ ");

        boolean addComma = false;
        for (int i = 0; i < numBytes; i++) {
            byte currentByte = mServiceTable[i];
            for (int bit = 0; bit < 8; bit++) {
                if ((currentByte & (1 << bit)) != 0) {
                    if (addComma) {
                        builder.append(", ");
                    } else {
                        addComma = true;
                    }
                    int ordinal = (i * 8) + bit;
                    if (ordinal < values.length) {
                        builder.append(values[ordinal]);
                    } else {
                        builder.append('#').append(ordinal + 1);    // service number (one-based)
                    }
                }
            }
        }
        return builder.append(" }").toString();
    }
}
