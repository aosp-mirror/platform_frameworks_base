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

package com.android.internal.protolog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.platform.test.annotations.Presubmit;
import android.util.proto.ProtoInputStream;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import perfetto.protos.ProtologCommon;

@Presubmit
@RunWith(JUnit4.class)
public class ProtoLogViewerConfigReaderTest {
    private static final String TEST_GROUP_NAME = "MY_TEST_GROUP";
    private static final String TEST_GROUP_TAG = "TEST";

    private static final String OTHER_TEST_GROUP_NAME = "MY_OTHER_TEST_GROUP";
    private static final String OTHER_TEST_GROUP_TAG = "OTHER_TEST";

    private static final byte[] TEST_VIEWER_CONFIG =
            perfetto.protos.Protolog.ProtoLogViewerConfig.newBuilder()
                .addGroups(
                        perfetto.protos.Protolog.ProtoLogViewerConfig.Group.newBuilder()
                                .setId(1)
                                .setName(TEST_GROUP_NAME)
                                .setTag(TEST_GROUP_TAG)
                ).addGroups(
                        perfetto.protos.Protolog.ProtoLogViewerConfig.Group.newBuilder()
                                .setId(2)
                                .setName(OTHER_TEST_GROUP_NAME)
                                .setTag(OTHER_TEST_GROUP_TAG)
                ).addMessages(
                        perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.newBuilder()
                                .setMessageId(1)
                                .setMessage("My Test Log Message 1 %b")
                                .setLevel(ProtologCommon.ProtoLogLevel.PROTOLOG_LEVEL_DEBUG)
                                .setGroupId(1)
                ).addMessages(
                        perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.newBuilder()
                                .setMessageId(2)
                                .setMessage("My Test Log Message 2 %b")
                                .setLevel(ProtologCommon.ProtoLogLevel.PROTOLOG_LEVEL_VERBOSE)
                                .setGroupId(1)
                ).addMessages(
                        perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.newBuilder()
                                .setMessageId(3)
                                .setMessage("My Test Log Message 3 %b")
                                .setLevel(ProtologCommon.ProtoLogLevel.PROTOLOG_LEVEL_WARN)
                                .setGroupId(1)
                ).addMessages(
                        perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.newBuilder()
                                .setMessageId(4)
                                .setMessage("My Test Log Message 4 %b")
                                .setLevel(ProtologCommon.ProtoLogLevel.PROTOLOG_LEVEL_ERROR)
                                .setGroupId(2)
                ).addMessages(
                        perfetto.protos.Protolog.ProtoLogViewerConfig.MessageData.newBuilder()
                                .setMessageId(5)
                                .setMessage("My Test Log Message 5 %b")
                                .setLevel(ProtologCommon.ProtoLogLevel.PROTOLOG_LEVEL_WTF)
                                .setGroupId(2)
                ).build().toByteArray();

    private final ViewerConfigInputStreamProvider mViewerConfigInputStreamProvider =
            () -> new ProtoInputStream(TEST_VIEWER_CONFIG);

    private ProtoLogViewerConfigReader mConfig;

    @Before
    public void before() {
        mConfig = new ProtoLogViewerConfigReader(mViewerConfigInputStreamProvider);
    }

    @Test
    public void getViewerString_notLoaded() {
        assertNull(mConfig.getViewerString(1));
    }

    @Test
    public void loadViewerConfig() {
        mConfig.loadViewerConfig(new String[] { TEST_GROUP_NAME });
        assertEquals("My Test Log Message 1 %b", mConfig.getViewerString(1));
        assertEquals("My Test Log Message 2 %b", mConfig.getViewerString(2));
        assertEquals("My Test Log Message 3 %b", mConfig.getViewerString(3));
        assertNull(mConfig.getViewerString(4));
        assertNull(mConfig.getViewerString(5));
    }

    @Test
    public void unloadViewerConfig() {
        mConfig.loadViewerConfig(new String[] { TEST_GROUP_NAME, OTHER_TEST_GROUP_NAME });
        mConfig.unloadViewerConfig(new String[] { TEST_GROUP_NAME });
        assertNull(mConfig.getViewerString(1));
        assertNull(mConfig.getViewerString(2));
        assertNull(mConfig.getViewerString(3));
        assertEquals("My Test Log Message 4 %b", mConfig.getViewerString(4));
        assertEquals("My Test Log Message 5 %b", mConfig.getViewerString(5));

        mConfig.unloadViewerConfig(new String[] { OTHER_TEST_GROUP_NAME });
        assertNull(mConfig.getViewerString(4));
        assertNull(mConfig.getViewerString(5));
    }
}
