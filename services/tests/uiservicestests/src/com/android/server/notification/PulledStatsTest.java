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
 * limitations under the License
 */
package com.android.server.notification;

import static com.android.server.notification.NotificationManagerService.REPORT_REMOTE_VIEWS;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotSame;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.service.notification.nano.NotificationRemoteViewsProto;
import android.test.MoreAsserts;
import android.util.proto.ProtoOutputStream;

import androidx.test.filters.SmallTest;

import com.android.server.UiServiceTestCase;

import com.google.protobuf.nano.InvalidProtocolBufferNanoException;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

@SmallTest
public class PulledStatsTest extends UiServiceTestCase {

    @Test
    public void testPulledStats_Empty() {
        PulledStats stats = new PulledStats(0L);
        assertEquals(0L, stats.endTimeMs());
    }

    @Test
    public void testPulledStats_UnknownReport() {
        PulledStats stats = new PulledStats(0L);
        stats.addUndecoratedPackage("foo", 456);
        stats.addUndecoratedPackage("bar", 123);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final ProtoOutputStream proto = new ProtoOutputStream(bytes);
        stats.writeToProto(1023123, proto); // a very large number
        proto.flush();

        // expect empty output in response to an unrecognized request
        assertEquals(0L, bytes.size());
    }

    @Test
    public void testPulledStats_RemoteViewReportPackages() {
        List<String> expectedPkgs = new ArrayList<>(2);
        expectedPkgs.add("foo");
        expectedPkgs.add("bar");

        PulledStats stats = new PulledStats(0L);
        for(String pkg: expectedPkgs) {
            stats.addUndecoratedPackage(pkg, 111);
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final ProtoOutputStream protoStream = new ProtoOutputStream(bytes);
        stats.writeToProto(REPORT_REMOTE_VIEWS, protoStream);
        protoStream.flush();

        try {
            NotificationRemoteViewsProto proto =
                    NotificationRemoteViewsProto.parseFrom(bytes.toByteArray());
            List<String> actualPkgs = new ArrayList<>(2);
            for(int i = 0 ; i < proto.packageRemoteViewInfo.length; i++) {
                actualPkgs.add(proto.packageRemoteViewInfo[i].packageName);
            }
            assertEquals(2, actualPkgs.size());
            assertTrue("missing packages", actualPkgs.containsAll(expectedPkgs));
            assertTrue("unexpected packages", expectedPkgs.containsAll(actualPkgs));
        } catch (InvalidProtocolBufferNanoException e) {
            e.printStackTrace();
            fail("writeToProto generated unparsable output");
        }

    }
    @Test
    public void testPulledStats_RemoteViewReportEndTime() {
        List<String> expectedPkgs = new ArrayList<>(2);
        expectedPkgs.add("foo");
        expectedPkgs.add("bar");

        PulledStats stats = new PulledStats(0L);
        long t = 111;
        for(String pkg: expectedPkgs) {
            t += 1000;
            stats.addUndecoratedPackage(pkg, t);
        }
        assertEquals(t, stats.endTimeMs());
    }

}
