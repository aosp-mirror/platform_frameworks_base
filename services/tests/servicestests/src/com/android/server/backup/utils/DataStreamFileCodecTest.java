/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup.utils;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public final class DataStreamFileCodecTest {
    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    @Test
    public void serialize_writesToTheFile() throws Exception {
        File unicornFile = mTemporaryFolder.newFile();

        DataStreamFileCodec<MythicalCreature> mythicalCreatureCodec = new DataStreamFileCodec<>(
                unicornFile, new MythicalCreatureDataStreamCodec());
        MythicalCreature unicorn = new MythicalCreature(
                10000, "Unicorn");
        mythicalCreatureCodec.serialize(unicorn);

        DataStreamFileCodec<MythicalCreature> newCodecWithSameFile = new DataStreamFileCodec<>(
                unicornFile, new MythicalCreatureDataStreamCodec());
        MythicalCreature deserializedUnicorn = newCodecWithSameFile.deserialize();

        assertThat(deserializedUnicorn.averageLifespanInYears)
                .isEqualTo(unicorn.averageLifespanInYears);
        assertThat(deserializedUnicorn.name).isEqualTo(unicorn.name);
    }

    private static class MythicalCreature {
        int averageLifespanInYears;
        String name;

        MythicalCreature(int averageLifespanInYears, String name) {
            this.averageLifespanInYears = averageLifespanInYears;
            this.name = name;
        }
    }

    private static class MythicalCreatureDataStreamCodec implements
            DataStreamCodec<MythicalCreature> {
        @Override
        public void serialize(MythicalCreature mythicalCreature,
                DataOutputStream dataOutputStream) throws IOException {
            dataOutputStream.writeInt(mythicalCreature.averageLifespanInYears);
            dataOutputStream.writeUTF(mythicalCreature.name);
        }

        @Override
        public MythicalCreature deserialize(DataInputStream dataInputStream)
                throws IOException {
            int years = dataInputStream.readInt();
            String name = dataInputStream.readUTF();
            return new MythicalCreature(years, name);
        }
    }
}
