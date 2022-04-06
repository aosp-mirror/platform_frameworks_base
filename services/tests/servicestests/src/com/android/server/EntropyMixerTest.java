/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.server;

import static org.junit.Assert.assertArrayEquals;

import android.content.Context;
import android.test.AndroidTestCase;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Tests for {@link com.android.server.EntropyMixer}
 */
public class EntropyMixerTest extends AndroidTestCase {

    private static final int SEED_FILE_SIZE = EntropyMixer.SEED_FILE_SIZE;

    private File dir;
    private File seedFile;
    private File randomReadDevice;
    private File randomWriteDevice;

    @Override
    public void setUp() throws Exception {
        dir = getContext().getDir("test", Context.MODE_PRIVATE);
        seedFile = createTempFile(dir, "entropy.dat");
        randomReadDevice = createTempFile(dir, "urandomRead");
        randomWriteDevice = createTempFile(dir, "urandomWrite");
    }

    private File createTempFile(File dir, String prefix) throws Exception {
        File file = File.createTempFile(prefix, null, dir);
        file.deleteOnExit();
        return file;
    }

    private byte[] repeatByte(byte b, int length) {
        byte[] data = new byte[length];
        Arrays.fill(data, b);
        return data;
    }

    // Test initializing the EntropyMixer when the seed file doesn't exist yet.
    @Test
    public void testInitFirstBoot() throws Exception {
        seedFile.delete();

        byte[] urandomInjectedData = repeatByte((byte) 0x01, SEED_FILE_SIZE);
        Files.write(randomReadDevice.toPath(), urandomInjectedData);

        // The constructor should have the side effect of writing to
        // randomWriteDevice and creating seedFile.
        new EntropyMixer(getContext(), seedFile, randomReadDevice, randomWriteDevice);

        // Since there was no old seed file, the data that was written to
        // randomWriteDevice should contain only device-specific information.
        assertTrue(isDeviceSpecificInfo(Files.readAllBytes(randomWriteDevice.toPath())));

        // The seed file should have been created.
        validateSeedFile(seedFile, new byte[0], urandomInjectedData);
    }

    // Test initializing the EntropyMixer when the seed file already exists.
    @Test
    public void testInitNonFirstBoot() throws Exception {
        byte[] previousSeed = repeatByte((byte) 0x01, SEED_FILE_SIZE);
        Files.write(seedFile.toPath(), previousSeed);

        byte[] urandomInjectedData = repeatByte((byte) 0x02, SEED_FILE_SIZE);
        Files.write(randomReadDevice.toPath(), urandomInjectedData);

        // The constructor should have the side effect of writing to
        // randomWriteDevice and updating seedFile.
        new EntropyMixer(getContext(), seedFile, randomReadDevice, randomWriteDevice);

        // The data that was written to randomWriteDevice should consist of the
        // previous seed followed by the device-specific information.
        byte[] dataWrittenToUrandom = Files.readAllBytes(randomWriteDevice.toPath());
        byte[] firstPartWritten = Arrays.copyOf(dataWrittenToUrandom, SEED_FILE_SIZE);
        byte[] secondPartWritten =
                Arrays.copyOfRange(
                        dataWrittenToUrandom, SEED_FILE_SIZE, dataWrittenToUrandom.length);
        assertArrayEquals(previousSeed, firstPartWritten);
        assertTrue(isDeviceSpecificInfo(secondPartWritten));

        // The seed file should have been updated.
        validateSeedFile(seedFile, previousSeed, urandomInjectedData);
    }

    private boolean isDeviceSpecificInfo(byte[] data) {
        return new String(data).startsWith(EntropyMixer.DEVICE_SPECIFIC_INFO_HEADER);
    }

    private void validateSeedFile(File seedFile, byte[] previousSeed, byte[] urandomInjectedData)
            throws Exception {
        final int unhashedLen = SEED_FILE_SIZE - 32;
        byte[] newSeed = Files.readAllBytes(seedFile.toPath());
        assertEquals(SEED_FILE_SIZE, newSeed.length);
        assertEquals(SEED_FILE_SIZE, urandomInjectedData.length);
        assertFalse(Arrays.equals(newSeed, previousSeed));
        // The new seed should consist of the first SEED_FILE_SIZE - 32 bytes
        // that were read from urandom, followed by a 32-byte hash that should
        // *not* be the same as the last 32 bytes that were read from urandom.
        byte[] firstPart = Arrays.copyOf(newSeed, unhashedLen);
        byte[] secondPart = Arrays.copyOfRange(newSeed, unhashedLen, SEED_FILE_SIZE);
        byte[] firstPartInjected = Arrays.copyOf(urandomInjectedData, unhashedLen);
        byte[] secondPartInjected =
                Arrays.copyOfRange(urandomInjectedData, unhashedLen, SEED_FILE_SIZE);
        assertArrayEquals(firstPart, firstPartInjected);
        assertFalse(Arrays.equals(secondPart, secondPartInjected));
    }
}
