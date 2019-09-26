/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.testing.fakes;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;

import android.util.Pair;

import com.android.server.backup.encryption.client.UnexpectedActiveSecondaryOnServerException;
import com.android.server.backup.encryption.protos.nano.WrappedKeyProto;

import org.junit.Before;
import org.junit.Test;

import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class FakeCryptoBackupServerTest {
    private static final String PACKAGE_NAME_1 = "package1";
    private static final String PACKAGE_NAME_2 = "package2";
    private static final String PACKAGE_NAME_3 = "package3";
    private static final WrappedKeyProto.WrappedKey PACKAGE_KEY_1 = createWrappedKey("key1");
    private static final WrappedKeyProto.WrappedKey PACKAGE_KEY_2 = createWrappedKey("key2");
    private static final WrappedKeyProto.WrappedKey PACKAGE_KEY_3 = createWrappedKey("key3");

    private FakeCryptoBackupServer mServer;

    @Before
    public void setUp() {
        mServer = new FakeCryptoBackupServer();
    }

    @Test
    public void getActiveSecondaryKeyAlias_isInitiallyAbsent() throws Exception {
        assertFalse(mServer.getActiveSecondaryKeyAlias().isPresent());
    }

    @Test
    public void setActiveSecondaryKeyAlias_setsTheKeyAlias() throws Exception {
        String keyAlias = "test";
        mServer.setActiveSecondaryKeyAlias(keyAlias, Collections.emptyMap());
        assertThat(mServer.getActiveSecondaryKeyAlias().get()).isEqualTo(keyAlias);
    }

    @Test
    public void getAllTertiaryKeys_returnsWrappedKeys() throws Exception {
        Map<String, WrappedKeyProto.WrappedKey> entries =
                createKeyMap(
                        new Pair<>(PACKAGE_NAME_1, PACKAGE_KEY_1),
                        new Pair<>(PACKAGE_NAME_2, PACKAGE_KEY_2));
        String secondaryKeyAlias = "doge";
        mServer.setActiveSecondaryKeyAlias(secondaryKeyAlias, entries);

        assertThat(mServer.getAllTertiaryKeys(secondaryKeyAlias)).containsExactlyEntriesIn(entries);
    }

    @Test
    public void addTertiaryKeys_updatesExistingSet() throws Exception {
        String keyId = "karlin";
        WrappedKeyProto.WrappedKey replacementKey = createWrappedKey("some replacement bytes");

        mServer.setActiveSecondaryKeyAlias(
                keyId,
                createKeyMap(
                        new Pair<>(PACKAGE_NAME_1, PACKAGE_KEY_1),
                        new Pair<>(PACKAGE_NAME_2, PACKAGE_KEY_2)));

        mServer.setActiveSecondaryKeyAlias(
                keyId,
                createKeyMap(
                        new Pair<>(PACKAGE_NAME_1, replacementKey),
                        new Pair<>(PACKAGE_NAME_3, PACKAGE_KEY_3)));

        assertThat(mServer.getAllTertiaryKeys(keyId))
                .containsExactlyEntriesIn(
                        createKeyMap(
                                new Pair<>(PACKAGE_NAME_1, replacementKey),
                                new Pair<>(PACKAGE_NAME_2, PACKAGE_KEY_2),
                                new Pair<>(PACKAGE_NAME_3, PACKAGE_KEY_3)));
    }

    @Test
    public void getAllTertiaryKeys_throwsForUnknownSecondaryKeyAlias() throws Exception {
        assertThrows(
                UnexpectedActiveSecondaryOnServerException.class,
                () -> mServer.getAllTertiaryKeys("unknown"));
    }

    @Test
    public void uploadIncrementalBackup_throwsUnsupportedOperationException() {
        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        mServer.uploadIncrementalBackup(
                                PACKAGE_NAME_1,
                                "docid",
                                new byte[0],
                                new WrappedKeyProto.WrappedKey()));
    }

    @Test
    public void uploadNonIncrementalBackup_throwsUnsupportedOperationException() {
        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        mServer.uploadNonIncrementalBackup(
                                PACKAGE_NAME_1, new byte[0], new WrappedKeyProto.WrappedKey()));
    }

    private static WrappedKeyProto.WrappedKey createWrappedKey(String data) {
        WrappedKeyProto.WrappedKey wrappedKey = new WrappedKeyProto.WrappedKey();
        wrappedKey.key = data.getBytes(Charset.forName("UTF-8"));
        return wrappedKey;
    }

    private Map<String, WrappedKeyProto.WrappedKey> createKeyMap(
            Pair<String, WrappedKeyProto.WrappedKey>... pairs) {
        Map<String, WrappedKeyProto.WrappedKey> map = new HashMap<>();
        for (Pair<String, WrappedKeyProto.WrappedKey> pair : pairs) {
            map.put(pair.first, pair.second);
        }
        return map;
    }
}
