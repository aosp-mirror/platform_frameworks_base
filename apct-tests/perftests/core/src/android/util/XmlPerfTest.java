/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.util;

import static org.junit.Assert.assertEquals;

import android.os.Bundle;
import android.os.Debug;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.HexDump;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class XmlPerfTest {
    /**
     * Since allocation measurement adds overhead, it's disabled by default for
     * performance runs. It can be manually enabled to compare GC behavior.
     */
    private static final boolean MEASURE_ALLOC = false;

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void timeWrite_Fast() throws Exception {
        doWrite(() -> Xml.newFastSerializer());
    }

    @Test
    public void timeWrite_Binary() throws Exception {
        doWrite(() -> Xml.newBinarySerializer());
    }

    private void doWrite(Supplier<TypedXmlSerializer> outFactory) throws Exception {
        if (MEASURE_ALLOC) {
            Debug.startAllocCounting();
        }

        int iterations = 0;
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            iterations++;
            try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
                final TypedXmlSerializer out = outFactory.get();
                out.setOutput(os, StandardCharsets.UTF_8.name());
                write(out);
            }
        }

        if (MEASURE_ALLOC) {
            Debug.stopAllocCounting();
            final Bundle results = new Bundle();
            results.putLong("threadAllocCount_mean", Debug.getThreadAllocCount() / iterations);
            results.putLong("threadAllocSize_mean", Debug.getThreadAllocSize() / iterations);
            InstrumentationRegistry.getInstrumentation().sendStatus(0, results);
        }
    }

    @Test
    public void timeRead_Fast() throws Exception {
        doRead(() -> Xml.newFastSerializer(), () -> Xml.newFastPullParser());
    }

    @Test
    public void timeRead_Binary() throws Exception {
        doRead(() -> Xml.newBinarySerializer(), () -> Xml.newBinaryPullParser());
    }

    private void doRead(Supplier<TypedXmlSerializer> outFactory,
            Supplier<TypedXmlPullParser> inFactory) throws Exception {
        final byte[] raw;
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            TypedXmlSerializer out = outFactory.get();
            out.setOutput(os, StandardCharsets.UTF_8.name());
            write(out);
            raw = os.toByteArray();
        }

        if (MEASURE_ALLOC) {
            Debug.startAllocCounting();
        }

        int iterations = 0;
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            iterations++;
            try (ByteArrayInputStream is = new ByteArrayInputStream(raw)) {
                TypedXmlPullParser xml = inFactory.get();
                xml.setInput(is, StandardCharsets.UTF_8.name());
                read(xml);
            }
        }

        if (MEASURE_ALLOC) {
            Debug.stopAllocCounting();
            final Bundle results = new Bundle();
            results.putLong("sizeBytes", raw.length);
            results.putLong("threadAllocCount_mean", Debug.getThreadAllocCount() / iterations);
            results.putLong("threadAllocSize_mean", Debug.getThreadAllocSize() / iterations);
            InstrumentationRegistry.getInstrumentation().sendStatus(0, results);
        } else {
            final Bundle results = new Bundle();
            results.putLong("sizeBytes", raw.length);
            InstrumentationRegistry.getInstrumentation().sendStatus(0, results);
        }
    }

    /**
     * Not even joking, this is a typical public key blob stored in
     * {@code packages.xml}.
     */
    private static final byte[] KEY_BLOB = HexDump.hexStringToByteArray(""
            + "308204a830820390a003020102020900a1573d0f45bea193300d06092a864886f70d010105050030819"
            + "4310b3009060355040613025553311330110603550408130a43616c69666f726e696131163014060355"
            + "0407130d4d6f756e7461696e20566965773110300e060355040a1307416e64726f69643110300e06035"
            + "5040b1307416e64726f69643110300e06035504031307416e64726f69643122302006092a864886f70d"
            + "0109011613616e64726f696440616e64726f69642e636f6d301e170d3131303931393138343232355a1"
            + "70d3339303230343138343232355a308194310b3009060355040613025553311330110603550408130a"
            + "43616c69666f726e6961311630140603550407130d4d6f756e7461696e20566965773110300e0603550"
            + "40a1307416e64726f69643110300e060355040b1307416e64726f69643110300e06035504031307416e"
            + "64726f69643122302006092a864886f70d0109011613616e64726f696440616e64726f69642e636f6d3"
            + "0820120300d06092a864886f70d01010105000382010d00308201080282010100de1b51336afc909d8b"
            + "cca5920fcdc8940578ec5c253898930e985481cfdea75ba6fc54b1f7bb492a03d98db471ab4200103a8"
            + "314e60ee25fef6c8b83bc1b2b45b084874cffef148fa2001bb25c672b6beba50b7ac026b546da762ea2"
            + "23829a22b80ef286131f059d2c9b4ca71d54e515a8a3fd6bf5f12a2493dfc2619b337b032a7cf8bbd34"
            + "b833f2b93aeab3d325549a93272093943bb59dfc0197ae4861ff514e019b73f5cf10023ad1a032adb4b"
            + "9bbaeb4debecb4941d6a02381f1165e1ac884c1fca9525c5854dce2ad8ec839b8ce78442c16367efc07"
            + "778a337d3ca2cdf9792ac722b95d67c345f1c00976ec372f02bfcbef0262cc512a6845e71cfea0d0201"
            + "03a381fc3081f9301d0603551d0e0416041478a0fc4517fb70ff52210df33c8d32290a44b2bb3081c90"
            + "603551d230481c13081be801478a0fc4517fb70ff52210df33c8d32290a44b2bba1819aa48197308194"
            + "310b3009060355040613025553311330110603550408130a43616c69666f726e6961311630140603550"
            + "407130d4d6f756e7461696e20566965773110300e060355040a1307416e64726f69643110300e060355"
            + "040b1307416e64726f69643110300e06035504031307416e64726f69643122302006092a864886f70d0"
            + "109011613616e64726f696440616e64726f69642e636f6d820900a1573d0f45bea193300c0603551d13"
            + "040530030101ff300d06092a864886f70d01010505000382010100977302dfbf668d7c61841c9c78d25"
            + "63bcda1b199e95e6275a799939981416909722713531157f3cdcfea94eea7bb79ca3ca972bd8058a36a"
            + "d1919291df42d7190678d4ea47a4b9552c9dfb260e6d0d9129b44615cd641c1080580e8a990dd768c6a"
            + "b500c3b964e185874e4105109d94c5bd8c405deb3cf0f7960a563bfab58169a956372167a7e2674a04c"
            + "4f80015d8f7869a7a4139aecbbdca2abc294144ee01e4109f0e47a518363cf6e9bf41f7560e94bdd4a5"
            + "d085234796b05c7a1389adfd489feec2a107955129d7991daa49afb3d327dc0dc4fe959789372b093a8"
            + "9c8dbfa41554f771c18015a6cb242a17e04d19d55d3b4664eae12caf2a11cd2b836e");

    /**
     * Typical list of permissions referenced in {@code packages.xml}.
     */
    private static final String[] PERMS = new String[] {
            "android.permission.ACCESS_CACHE_FILESYSTEM",
            "android.permission.WRITE_SETTINGS",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.SEND_DOWNLOAD_COMPLETED_INTENTS",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.WRITE_MEDIA_STORAGE",
            "android.permission.INTERNET",
            "android.permission.UPDATE_DEVICE_STATS",
            "android.permission.RECEIVE_DEVICE_CUSTOMIZATION_READY",
            "android.permission.MANAGE_USB",
            "android.permission.ACCESS_ALL_DOWNLOADS",
            "android.permission.ACCESS_DOWNLOAD_MANAGER",
            "android.permission.MANAGE_USERS",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_MTP",
            "android.permission.INTERACT_ACROSS_USERS",
            "android.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS",
            "android.permission.CLEAR_APP_CACHE",
            "android.permission.CONNECTIVITY_INTERNAL",
            "android.permission.START_ACTIVITIES_FROM_BACKGROUND",
            "android.permission.QUERY_ALL_PACKAGES",
            "android.permission.WAKE_LOCK",
            "android.permission.UPDATE_APP_OPS_STATS",
    };

    /**
     * Write a typical {@code packages.xml} file containing 100 applications,
     * each of which defines signing key and permission information.
     */
    private static void write(TypedXmlSerializer out) throws IOException {
        out.startDocument(null, true);
        out.startTag(null, "packages");
        for (int i = 0; i < 100; i++) {
            out.startTag(null, "package");
            out.attribute(null, "name", "com.android.providers.media");
            out.attribute(null, "codePath", "/system/priv-app/MediaProviderLegacy");
            out.attribute(null, "nativeLibraryPath", "/system/priv-app/MediaProviderLegacy/lib");
            out.attributeLong(null, "publicFlags", 944258629L);
            out.attributeLong(null, "privateFlags", -1946152952L);
            out.attributeLong(null, "ft", 1603899064000L);
            out.attributeLong(null, "it", 1603899064000L);
            out.attributeLong(null, "ut", 1603899064000L);
            out.attributeInt(null, "version", 1024);
            out.attributeInt(null, "sharedUserId", 10100);
            out.attributeBoolean(null, "isOrphaned", true);

            out.startTag(null, "sigs");
            out.startTag(null, "cert");
            out.attributeInt(null, "index", 10);
            out.attributeBytesHex(null, "key", KEY_BLOB);
            out.endTag(null, "cert");
            out.endTag(null, "sigs");

            out.startTag(null, "perms");
            for (String perm : PERMS) {
                out.startTag(null, "item");
                out.attributeInterned(null, "name", perm);
                out.attributeBoolean(null, "granted", true);
                out.attributeInt(null, "flags", 0);
                out.endTag(null, "item");
            }
            out.endTag(null, "perms");

            out.endTag(null, "package");
        }
        out.endTag(null, "packages");
        out.endDocument();
    }

    /**
     * Read a typical {@code packages.xml} file containing 100 applications, and
     * verify that data passes smell test.
     */
    private static void read(TypedXmlPullParser xml) throws Exception {
        int type;
        int packages = 0;
        int certs = 0;
        int perms = 0;
        while ((type = xml.next()) != XmlPullParser.END_DOCUMENT) {
            final String tag = xml.getName();
            if (type == XmlPullParser.START_TAG) {
                if ("package".equals(tag)) {
                    xml.getAttributeValue(null, "name");
                    xml.getAttributeValue(null, "codePath");
                    xml.getAttributeValue(null, "nativeLibraryPath");
                    xml.getAttributeLong(null, "publicFlags");
                    assertEquals(-1946152952L, xml.getAttributeLong(null, "privateFlags"));
                    xml.getAttributeLong(null, "ft");
                    xml.getAttributeLong(null, "it");
                    xml.getAttributeLong(null, "ut");
                    xml.getAttributeInt(null, "version");
                    xml.getAttributeInt(null, "sharedUserId");
                    xml.getAttributeBoolean(null, "isOrphaned");
                    packages++;
                } else if ("cert".equals(tag)) {
                    xml.getAttributeInt(null, "index");
                    xml.getAttributeBytesHex(null, "key");
                    certs++;
                } else if ("item".equals(tag)) {
                    xml.getAttributeValue(null, "name");
                    xml.getAttributeBoolean(null, "granted");
                    xml.getAttributeInt(null, "flags");
                    perms++;
                }
            } else if (type == XmlPullParser.TEXT) {
                xml.getText();
            }
        }

        assertEquals(100, packages);
        assertEquals(packages * 1, certs);
        assertEquals(packages * PERMS.length, perms);
    }
}
