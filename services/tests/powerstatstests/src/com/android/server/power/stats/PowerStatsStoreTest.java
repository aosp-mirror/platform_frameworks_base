/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.power.stats;

import static com.google.common.truth.Truth.assertThat;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.runner.AndroidJUnit4;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SuppressWarnings("GuardedBy")
public class PowerStatsStoreTest {
    private static final long MAX_BATTERY_STATS_SNAPSHOT_STORAGE_BYTES = 2 * 1024;

    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    private PowerStatsStore mPowerStatsStore;
    private File mStoreDirectory;

    @Before
    public void setup() throws IOException {
        mStoreDirectory = Files.createTempDirectory("PowerStatsStoreTest").toFile();
        clearDirectory(mStoreDirectory);

        mPowerStatsStore = new PowerStatsStore(mStoreDirectory,
                MAX_BATTERY_STATS_SNAPSHOT_STORAGE_BYTES, new TestHandler());
    }

    @Test
    public void garbageCollectOldSpans() throws Exception {
        int spanSize = 500;
        final int numberOfSnaps =
                (int) (MAX_BATTERY_STATS_SNAPSHOT_STORAGE_BYTES / spanSize);
        for (int i = 0; i < numberOfSnaps + 2; i++) {
            PowerStatsSpan span = new PowerStatsSpan(i);
            span.addSection(new TestSection(i, spanSize));
            mPowerStatsStore.storePowerStatsSpan(span);
        }

        assertThat(getDirectorySize(mStoreDirectory))
                .isAtMost(MAX_BATTERY_STATS_SNAPSHOT_STORAGE_BYTES);

        List<PowerStatsSpan.Metadata> toc = mPowerStatsStore.getTableOfContents();
        assertThat(toc.size()).isLessThan(numberOfSnaps);
        int minPreservedSpanId = numberOfSnaps - toc.size();
        for (PowerStatsSpan.Metadata metadata : toc) {
            assertThat(metadata.getId()).isAtLeast(minPreservedSpanId);
        }
    }

    @Test
    public void reset() throws Exception {
        for (int i = 0; i < 3; i++) {
            PowerStatsSpan span = new PowerStatsSpan(i);
            span.addSection(new TestSection(i, 42));
            mPowerStatsStore.storePowerStatsSpan(span);
        }

        assertThat(getDirectorySize(mStoreDirectory)).isNotEqualTo(0);

        mPowerStatsStore.reset();

        assertThat(getDirectorySize(mStoreDirectory)).isEqualTo(0);
    }

    private void clearDirectory(File dir) {
        if (dir.exists()) {
            for (File child : dir.listFiles()) {
                if (child.isDirectory()) {
                    clearDirectory(child);
                }
                child.delete();
            }
        }
    }

    private long getDirectorySize(File dir) {
        long size = 0;
        if (dir.exists()) {
            for (File child : dir.listFiles()) {
                if (child.isDirectory()) {
                    size += getDirectorySize(child);
                } else {
                    size += child.length();
                }
            }
        }
        return size;
    }

    private static class TestSection extends PowerStatsSpan.Section {
        public static final String TYPE = "much-text";

        private final int mSize;
        private final int mValue;

        TestSection(int value, int size) {
            super(TYPE);
            mSize = size;
            mValue = value;
        }

        @Override
        public void write(TypedXmlSerializer serializer) throws IOException {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mSize; i++) {
                sb.append("X");
            }
            serializer.startTag(null, "much-text");
            serializer.attributeInt(null, "value", mValue);
            serializer.text(sb.toString());
            serializer.endTag(null, "much-text");
        }

        public static TestSection readXml(TypedXmlPullParser parser) throws XmlPullParserException {
            TestSection section = null;
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT
                   && !(eventType == XmlPullParser.END_TAG
                        && parser.getName().equals("much-text"))) {
                if (eventType == XmlPullParser.START_TAG && parser.getName().equals("much-text")) {
                    section = new TestSection(parser.getAttributeInt(null, "value"), 0);
                }
            }
            return section;
        }
    }

    private static class TestHandler extends Handler {
        TestHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
            msg.getCallback().run();
            return true;
        }
    }
}
