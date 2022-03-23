/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.security.keystore;

import static org.junit.Assert.assertTrue;

import android.security.KeyStoreException;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class KeyStoreExceptionTest {
    @Test
    public void testKeystoreMessageIsIncluded() {
        final String primaryMessage = "some_message";
        final String keystoreMessage = "ks_message";
        KeyStoreException exception = new KeyStoreException(-1, primaryMessage, keystoreMessage);

        String exceptionMessage = exception.getMessage();
        assertTrue(exceptionMessage.contains(primaryMessage));
        assertTrue(exceptionMessage.contains(keystoreMessage));

        String exceptionString = exception.toString();
        assertTrue(exceptionString.contains(primaryMessage));
        assertTrue(exceptionString.contains(keystoreMessage));
    }
}
