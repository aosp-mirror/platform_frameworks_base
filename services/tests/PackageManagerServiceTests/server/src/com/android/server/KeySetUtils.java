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

package com.android.server.pm;

import android.util.ArraySet;
import android.util.LongSparseArray;

import java.lang.reflect.Field;
import java.security.PublicKey;

public class KeySetUtils {

    public static PublicKey getPubKey(KeySetManagerService ksms, long pkId)
            throws NoSuchFieldException, IllegalAccessException {
        Field pkField = ksms.getClass().getDeclaredField("mPublicKeys");
        pkField.setAccessible(true);
        LongSparseArray<KeySetManagerService.PublicKeyHandle> mPublicKeys =
            (LongSparseArray<KeySetManagerService.PublicKeyHandle>) pkField.get(ksms);
        KeySetManagerService.PublicKeyHandle pkh = mPublicKeys.get(pkId);
        if (pkh == null) {
            return null;
        } else {
            return pkh.getKey();
        }
    }

    public static int getPubKeyRefCount(KeySetManagerService ksms, long pkId)
            throws NoSuchFieldException, IllegalAccessException {
        Field pkField = ksms.getClass().getDeclaredField("mPublicKeys");
        pkField.setAccessible(true);
        LongSparseArray<KeySetManagerService.PublicKeyHandle> mPublicKeys =
            (LongSparseArray<KeySetManagerService.PublicKeyHandle>) pkField.get(ksms);
        KeySetManagerService.PublicKeyHandle pkh = mPublicKeys.get(pkId);
        if (pkh == null) {
            return 0;
        } else {
            return pkh.getRefCountLPr();
        }
    }

    public static int getKeySetRefCount(KeySetManagerService ksms, long keysetId)
            throws NoSuchFieldException, IllegalAccessException {
        Field ksField = ksms.getClass().getDeclaredField("mKeySets");
        ksField.setAccessible(true);
        LongSparseArray<KeySetHandle> mKeySets =
            (LongSparseArray<KeySetHandle>) ksField.get(ksms);
        KeySetHandle ksh = mKeySets.get(keysetId);
        if (ksh == null) {
            return 0;
        } else {
            return ksh.getRefCountLPr();
        }
    }

    public static LongSparseArray<ArraySet<Long>> getKeySetMapping(KeySetManagerService ksms)
            throws NoSuchFieldException, IllegalAccessException {
        Field ksField = ksms.getClass().getDeclaredField("mKeySetMapping");
        ksField.setAccessible(true);
        return (LongSparseArray<ArraySet<Long>>) ksField.get(ksms);
    }

    public static Long getLastIssuedKeyId(KeySetManagerService ksms)
            throws NoSuchFieldException, IllegalAccessException {
        Field ksField = ksms.getClass().getDeclaredField("lastIssuedKeyId");
        ksField.setAccessible(true);
        return (Long) ksField.get(ksms);
    }

    public static Long getLastIssuedKeySetId(KeySetManagerService ksms)
            throws NoSuchFieldException, IllegalAccessException {
        Field ksField = ksms.getClass().getDeclaredField("lastIssuedKeySetId");
        ksField.setAccessible(true);
        return (Long) ksField.get(ksms);
    }
}
