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

package com.android.server.testing.shadows;

import android.content.Context;
import android.security.keystore.recovery.InternalRecoveryServiceException;
import android.security.keystore.recovery.LockScreenRequiredException;
import android.security.keystore.recovery.RecoveryController;

import com.google.common.collect.ImmutableList;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;

import java.lang.reflect.Constructor;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.util.HashMap;
import java.util.List;

import javax.crypto.KeyGenerator;

/**
 * Shadow of {@link RecoveryController}.
 *
 * <p>Instead of generating keys via the {@link RecoveryController}, this shadow generates them in
 * memory.
 */
@Implements(RecoveryController.class)
public class ShadowRecoveryController {
    private static final String KEY_GENERATOR_ALGORITHM = "AES";
    private static final int KEY_SIZE_BITS = 256;

    private static boolean sIsSupported = true;
    private static boolean sThrowsInternalError = false;
    private static HashMap<String, Key> sKeysByAlias = new HashMap<>();
    private static HashMap<String, Integer> sKeyStatusesByAlias = new HashMap<>();

    @Implementation
    public void __constructor__() {
        // do not throw
    }

    @Implementation
    public static RecoveryController getInstance(Context context) {
        // Call non-public constructor.
        try {
            Constructor<RecoveryController> constructor = RecoveryController.class.getConstructor();
            return constructor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Implementation
    public static boolean isRecoverableKeyStoreEnabled(Context context) {
        return sIsSupported;
    }

    @Implementation
    public Key generateKey(String alias)
            throws InternalRecoveryServiceException, LockScreenRequiredException {
        maybeThrowError();
        KeyGenerator keyGenerator;
        try {
            keyGenerator = KeyGenerator.getInstance(KEY_GENERATOR_ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            // Should never happen
            throw new RuntimeException(e);
        }

        keyGenerator.init(KEY_SIZE_BITS);
        Key key = keyGenerator.generateKey();
        sKeysByAlias.put(alias, key);
        sKeyStatusesByAlias.put(alias, RecoveryController.RECOVERY_STATUS_SYNC_IN_PROGRESS);
        return key;
    }

    @Implementation
    public Key getKey(String alias)
            throws InternalRecoveryServiceException, UnrecoverableKeyException {
        return sKeysByAlias.get(alias);
    }

    @Implementation
    public void removeKey(String alias) throws InternalRecoveryServiceException {
        sKeyStatusesByAlias.remove(alias);
        sKeysByAlias.remove(alias);
    }

    @Implementation
    public int getRecoveryStatus(String alias) throws InternalRecoveryServiceException {
        maybeThrowError();
        return sKeyStatusesByAlias.getOrDefault(
                alias, RecoveryController.RECOVERY_STATUS_PERMANENT_FAILURE);
    }

    @Implementation
    public List<String> getAliases() throws InternalRecoveryServiceException {
        return ImmutableList.copyOf(sKeyStatusesByAlias.keySet());
    }

    private static void maybeThrowError() throws InternalRecoveryServiceException {
        if (sThrowsInternalError) {
            throw new InternalRecoveryServiceException("test error");
        }
    }

    /** Sets the recovery status of the key with {@code alias} to {@code status}. */
    public static void setRecoveryStatus(String alias, int status) {
        sKeyStatusesByAlias.put(alias, status);
    }

    /** Sets all existing keys to being synced. */
    public static void syncAllKeys() {
        for (String alias : sKeysByAlias.keySet()) {
            sKeyStatusesByAlias.put(alias, RecoveryController.RECOVERY_STATUS_SYNCED);
        }
    }

    public static void setThrowsInternalError(boolean throwsInternalError) {
        ShadowRecoveryController.sThrowsInternalError = throwsInternalError;
    }

    public static void setIsSupported(boolean isSupported) {
        ShadowRecoveryController.sIsSupported = isSupported;
    }

    @Resetter
    public static void reset() {
        sIsSupported = true;
        sThrowsInternalError = false;
        sKeysByAlias.clear();
        sKeyStatusesByAlias.clear();
    }
}
