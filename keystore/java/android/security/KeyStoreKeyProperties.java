/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.security;

import android.annotation.IntDef;
import android.security.keymaster.KeymasterDefs;

import libcore.util.EmptyArray;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collection;

/**
 * Properties of {@code AndroidKeyStore} keys.
 */
public abstract class KeyStoreKeyProperties {
    private KeyStoreKeyProperties() {}

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true,
            value = {Purpose.ENCRYPT, Purpose.DECRYPT, Purpose.SIGN, Purpose.VERIFY})
    public @interface PurposeEnum {}

    /**
     * Purpose of key.
     */
    public static abstract class Purpose {
        private Purpose() {}

        /**
         * Purpose: encryption.
         */
        public static final int ENCRYPT = 1 << 0;

        /**
         * Purpose: decryption.
         */
        public static final int DECRYPT = 1 << 1;

        /**
         * Purpose: signing.
         */
        public static final int SIGN = 1 << 2;

        /**
         * Purpose: signature verification.
         */
        public static final int VERIFY = 1 << 3;

        /**
         * @hide
         */
        public static int toKeymaster(@PurposeEnum int purpose) {
            switch (purpose) {
                case ENCRYPT:
                    return KeymasterDefs.KM_PURPOSE_ENCRYPT;
                case DECRYPT:
                    return KeymasterDefs.KM_PURPOSE_DECRYPT;
                case SIGN:
                    return KeymasterDefs.KM_PURPOSE_SIGN;
                case VERIFY:
                    return KeymasterDefs.KM_PURPOSE_VERIFY;
                default:
                    throw new IllegalArgumentException("Unknown purpose: " + purpose);
            }
        }

        /**
         * @hide
         */
        public static @PurposeEnum int fromKeymaster(int purpose) {
            switch (purpose) {
                case KeymasterDefs.KM_PURPOSE_ENCRYPT:
                    return ENCRYPT;
                case KeymasterDefs.KM_PURPOSE_DECRYPT:
                    return DECRYPT;
                case KeymasterDefs.KM_PURPOSE_SIGN:
                    return SIGN;
                case KeymasterDefs.KM_PURPOSE_VERIFY:
                    return VERIFY;
                default:
                    throw new IllegalArgumentException("Unknown purpose: " + purpose);
            }
        }

        /**
         * @hide
         */
        public static int[] allToKeymaster(@PurposeEnum int purposes) {
            int[] result = getSetFlags(purposes);
            for (int i = 0; i < result.length; i++) {
                result[i] = toKeymaster(result[i]);
            }
            return result;
        }

        /**
         * @hide
         */
        public static @PurposeEnum int allFromKeymaster(Collection<Integer> purposes) {
            @PurposeEnum int result = 0;
            for (int keymasterPurpose : purposes) {
                result |= fromKeymaster(keymasterPurpose);
            }
            return result;
        }
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({Origin.GENERATED, Origin.IMPORTED, Origin.UNKNOWN})
    public @interface OriginEnum {}

    /**
     * Origin of the key.
     */
    public static abstract class Origin {
        private Origin() {}

        /** Key was generated inside AndroidKeyStore. */
        public static final int GENERATED = 1 << 0;

        /** Key was imported into AndroidKeyStore. */
        public static final int IMPORTED = 1 << 1;

        /**
         * Origin of the key is unknown. This can occur only for keys backed by an old TEE
         * implementation which does not record origin information.
         */
        public static final int UNKNOWN = 1 << 2;

        /**
         * @hide
         */
        public static @OriginEnum int fromKeymaster(int origin) {
            switch (origin) {
                case KeymasterDefs.KM_ORIGIN_GENERATED:
                    return GENERATED;
                case KeymasterDefs.KM_ORIGIN_IMPORTED:
                    return IMPORTED;
                case KeymasterDefs.KM_ORIGIN_UNKNOWN:
                    return UNKNOWN;
                default:
                    throw new IllegalArgumentException("Unknown origin: " + origin);
            }
        }
    }

    private static int[] getSetFlags(int flags) {
        if (flags == 0) {
            return EmptyArray.INT;
        }
        int result[] = new int[getSetBitCount(flags)];
        int resultOffset = 0;
        int flag = 1;
        while (flags != 0) {
            if ((flags & 1) != 0) {
                result[resultOffset] = flag;
                resultOffset++;
            }
            flags >>>= 1;
            flag <<= 1;
        }
        return result;
    }

    private static int getSetBitCount(int value) {
        if (value == 0) {
            return 0;
        }
        int result = 0;
        while (value != 0) {
            if ((value & 1) != 0) {
                result++;
            }
            value >>>= 1;
        }
        return result;
    }
}
