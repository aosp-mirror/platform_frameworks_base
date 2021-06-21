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
 * limitations under the License.
 */

package com.android.server.storage;

import static com.google.common.truth.Truth.assertThat;

import android.app.usage.CacheQuotaHint;
import android.test.AndroidTestCase;
import android.util.Pair;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.util.FastXmlSerializer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

@RunWith(JUnit4.class)
public class CacheQuotaStrategyTest extends AndroidTestCase {
    StringWriter mWriter;
    TypedXmlSerializer mOut;

    @Before
    public void setUp() throws Exception {
        mWriter = new StringWriter();
        mOut = Xml.newFastSerializer();
        mOut.setOutput(mWriter);
    }

    @Test
    public void testEmptyWrite() throws Exception {
        CacheQuotaStrategy.saveToXml(mOut, new ArrayList<>(), 0);
        mOut.flush();

        assertThat(mWriter.toString()).isEqualTo(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" +
                        "<cache-info previousBytes=\"0\" />\n");
    }

    @Test
    public void testWriteOneQuota() throws Exception {
        ArrayList<CacheQuotaHint> requests = new ArrayList<>();
        requests.add(buildCacheQuotaHint("uuid", 0, 100));

        CacheQuotaStrategy.saveToXml(mOut, requests, 1000);
        mOut.flush();

        assertThat(mWriter.toString()).isEqualTo(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" +
                "<cache-info previousBytes=\"1000\">\n"
                        + "<quota uuid=\"uuid\" uid=\"0\" bytes=\"100\" />\n"
                        + "</cache-info>\n");
    }

    @Test
    public void testWriteMultipleQuotas() throws Exception {
        ArrayList<CacheQuotaHint> requests = new ArrayList<>();
        requests.add(buildCacheQuotaHint("uuid", 0, 100));
        requests.add(buildCacheQuotaHint("uuid2", 10, 250));

        CacheQuotaStrategy.saveToXml(mOut, requests, 1000);
        mOut.flush();

        assertThat(mWriter.toString()).isEqualTo(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" +
                        "<cache-info previousBytes=\"1000\">\n"
                        + "<quota uuid=\"uuid\" uid=\"0\" bytes=\"100\" />\n"
                        + "<quota uuid=\"uuid2\" uid=\"10\" bytes=\"250\" />\n"
                        + "</cache-info>\n");
    }

    @Test
    public void testNullUuidDoesntCauseCrash() throws Exception {
        ArrayList<CacheQuotaHint> requests = new ArrayList<>();
        requests.add(buildCacheQuotaHint(null, 0, 100));
        requests.add(buildCacheQuotaHint(null, 10, 250));

        CacheQuotaStrategy.saveToXml(mOut, requests, 1000);
        mOut.flush();

        assertThat(mWriter.toString()).isEqualTo(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" +
                        "<cache-info previousBytes=\"1000\">\n"
                        + "<quota uid=\"0\" bytes=\"100\" />\n"
                        + "<quota uid=\"10\" bytes=\"250\" />\n"
                        + "</cache-info>\n");
    }

    @Test
    public void testReadMultipleQuotas() throws Exception {
        String input = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<cache-info previousBytes=\"1000\">\n"
                + "<quota uuid=\"uuid\" uid=\"0\" bytes=\"100\" />\n"
                + "<quota uuid=\"uuid2\" uid=\"10\" bytes=\"250\" />\n"
                + "</cache-info>\n";

        Pair<Long, List<CacheQuotaHint>> output =
                CacheQuotaStrategy.readFromXml(new ByteArrayInputStream(input.getBytes("UTF-8")));

        assertThat(output.first).isEqualTo(1000);
        assertThat(output.second).containsExactly(buildCacheQuotaHint("uuid", 0, 100),
                buildCacheQuotaHint("uuid2", 10, 250));
    }

    private CacheQuotaHint buildCacheQuotaHint(String volumeUuid, int uid, long quota) {
        return new CacheQuotaHint.Builder()
                .setVolumeUuid(volumeUuid).setUid(uid).setQuota(quota).build();
    }
}