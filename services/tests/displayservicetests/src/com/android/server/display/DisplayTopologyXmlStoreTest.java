/*
 * Copyright 2025 The Android Open Source Project
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

package com.android.server.display;


import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.nio.charset.StandardCharsets.UTF_8;

import android.graphics.PointF;
import android.hardware.display.DisplayTopology;
import android.util.AtomicFilePrintWriter;
import android.util.SparseArray;

import androidx.test.filters.SmallTest;

import com.google.common.io.CharSource;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Tests for {@link DisplayTopologyXmlStore}
 * Run: atest PersistentTopologyStoreTest
 */
@SmallTest
@RunWith(TestParameterInjector.class)
public class DisplayTopologyXmlStoreTest {
    private static final String NO_TOPOLOGIES = """
                <?xml version="1.0" encoding="utf-8"?>
                <displayTopologyState version="1"/>
                """;

    private static final String SIMPLE_TOPOLOGY = """
                <?xml version="1.0" encoding="utf-8"?>
                <displayTopologyState version="1">
                    <topology id="uniqueid0|uniqueid1" order="0">
                        <display id="uniqueid0" primary="true">
                            <position>left</position>
                            <offset>0.0</offset>
                            <children>
                                <display id="uniqueid1" primary="false">
                                    <position>top</position>
                                    <offset>-560.0</offset>
                                    <children>
                                    </children>
                                </display>
                            </children>
                        </display>
                    </topology>
                </displayTopologyState>
                """;

    private static final String IMMUTABLE_TOPOLOGY = """
                <?xml version="1.0" encoding="utf-8"?>
                <displayTopologyState version="1">
                    <topology id="uniqueid10|uniqueid11" order="0" immutable="true">
                        <display id="uniqueid10" primary="true">
                            <position>left</position>
                            <offset>0.0</offset>
                            <children>
                                <display id="uniqueid11" primary="false">
                                    <position>top</position>
                                    <offset>-560.0</offset>
                                    <children>
                                    </children>
                                </display>
                            </children>
                        </display>
                    </topology>
                </displayTopologyState>
                """;

    private static final String MULTIPLE_TOPOLOGIES = """
                <?xml version="1.0" encoding="utf-8"?>
                <displayTopologyState version="1">
                    <topology id="uniqueid0|uniqueid1" order="0">
                        <display id="uniqueid0" primary="true">
                            <position>left</position>
                            <offset>0.0</offset>
                            <children>
                                <display id="uniqueid1" primary="false">
                                    <position>top</position>
                                    <offset>-560.0</offset>
                                    <children>
                                    </children>
                                </display>
                            </children>
                        </display>
                    </topology>
                    <topology id="uniqueid0|uniqueid1|uniqueid2|uniqueid3" order="0">
                        <display id="uniqueid1" primary="false">
                            <position>left</position>
                            <offset>0.0</offset>
                            <children>
                                <display id="uniqueid0" primary="false">
                                    <position>top</position>
                                    <offset>-50</offset>
                                    <children>
                                    </children>
                                </display>
                                <display id="uniqueid2" primary="true">
                                    <position>right</position>
                                    <offset>-100</offset>
                                    <children>
                                        <display id="uniqueid3" primary="false">
                                            <position>bottom</position>
                                            <offset>-300</offset>
                                            <children>
                                            </children>
                                        </display>
                                    </children>
                                </display>
                            </children>
                        </display>
                    </topology>
                </displayTopologyState>
                """;

    private static InputStream asInputStream(String value) throws IOException {
        return CharSource.wrap(value).asByteSource(UTF_8).openStream();
    }

    private static SparseArray<String> generateIdToUniqueId() {
        var res = new SparseArray<String>();
        for (int i = 0; i < 200; i++) {
            res.put(i, "uniqueid" + i);
        }
        return res;
    }

    private static Map<String, Integer> generateUniqueIdToId() {
        var res = new HashMap<String, Integer>();
        for (int i = 0; i < 200; i++) {
            res.put("uniqueid" + i, i);
        }
        return res;
    }

    private static DisplayTopology generateTopology(int displayId1, int displayId2) {
        var topology = new DisplayTopology();
        topology.addDisplay(displayId1, 800f, 600f);
        topology.addDisplay(displayId2, 1920f, 1080f);
        return topology;
    }

    @Mock
    private DisplayTopologyXmlStore.Injector mInjector;
    @Mock
    private AtomicFilePrintWriter mPrintWriter0;
    @Mock
    private AtomicFilePrintWriter mPrintWriter1;

    private DisplayTopology mTopology;

    /** Setup tests. */
    @Before
    public void setup() throws IOException {
        MockitoAnnotations.initMocks(this);
        configureTopologyFile(/*userId=*/ 0, NO_TOPOLOGIES, mPrintWriter0);
        configureTopologyFile(/*userId=*/ 1, SIMPLE_TOPOLOGY, mPrintWriter1);
        configureTopologyFile(/*userId=*/ 2, MULTIPLE_TOPOLOGIES, mPrintWriter1);

        when(mInjector.getDisplayIdToUniqueIdMapping()).thenReturn(generateIdToUniqueId());
        when(mInjector.getUniqueIdToDisplayIdMapping()).thenReturn(generateUniqueIdToId());

        mTopology = generateTopology(0, 1);
    }

    @Test
    public void testSaveAndRestoreTopologyWithoutFileStreams() throws IOException {
        final float initialOffset = -560f;
        final float newOffset = -300f;

        var store = new DisplayTopologyXmlStore(mInjector);
        assertThat(store.saveTopology(mTopology)).isTrue();
        assertThat(mTopology.getRoot().getChildren().getFirst().getOffset())
                .isEqualTo(initialOffset);
        assertThat(mTopology.getRoot().getWidth()).isEqualTo(800f);
        assertThat(mTopology.getRoot().getHeight()).isEqualTo(600f);

        // Change display size
        assertThat(mTopology.updateDisplay(0, 640f, 480f)).isTrue();
        assertThat(mTopology.getRoot().getWidth()).isEqualTo(640f);
        assertThat(mTopology.getRoot().getHeight()).isEqualTo(480f);

        // Move display#1.
        mTopology.rearrange(Map.of(0, new PointF(0, 0),
                1, new PointF(newOffset, -1080f)));
        assertThat(mTopology.getRoot().getChildren().getFirst().getOffset()).isEqualTo(newOffset);

        // Restore the topology, should apply saved offset, while keeping the current display sizes
        mTopology = store.restoreTopology(mTopology);

        // Offset is taken from the persisted topology.
        assertThat(mTopology.getRoot().getChildren().getFirst().getOffset())
                .isEqualTo(initialOffset);

        // Size is taken from the current topology
        assertThat(mTopology.getRoot().getWidth()).isEqualTo(640f);
        assertThat(mTopology.getRoot().getHeight()).isEqualTo(480f);

        // reloadTopologies was never called so, no file operations should have been performed.
        verify(mInjector, never()).readUserTopologies(anyInt());
        verify(mInjector, never()).getTopologyFilePrintWriter(anyInt());
    }

    @Test
    public void testSaveTopologyInPrintWriter() throws IOException {
        var store = new DisplayTopologyXmlStore(mInjector);
        store.reloadTopologies(/*userId=*/ 0);
        assertThat(store.saveTopology(mTopology)).isTrue();
        verify(mPrintWriter0).print(eq(SIMPLE_TOPOLOGY));
        verify(mPrintWriter0).markSuccess();
        verify(mPrintWriter0).close();
    }

    @Test
    public void testRestoreTopology() {
        var store = new DisplayTopologyXmlStore(mInjector);
        store.reloadTopologies(/*userId=*/ 0);
        var restoredTopology = store.restoreTopology(mTopology);

        // Should return null because there was nothing persisted before.
        assertThat(restoredTopology).isNull();

        // Persist topology
        assertThat(store.saveTopology(mTopology)).isTrue();

        // Should return new instance (restored), but equal.
        var restoredTopologyAfterSave = store.restoreTopology(mTopology);
        assertThat(restoredTopologyAfterSave).isNotSameInstanceAs(mTopology);
        assertThat(restoredTopologyAfterSave).isEqualTo(mTopology);
    }

    @Test
    public void testChangeUser() {
        var store = new DisplayTopologyXmlStore(mInjector);
        // Move display#1.
        mTopology.rearrange(Map.of(0, new PointF(0, 0),
                1, new PointF(-10f, -1080f)));
        assertThat(mTopology.getRoot().getChildren().getFirst().getOffset()).isEqualTo(-10f);

        store.reloadTopologies(/*userId=*/ 1);
        // Should return new instance (restored), with new offset.
        var restoredTopology = store.restoreTopology(mTopology);
        assertThat(restoredTopology).isNotSameInstanceAs(mTopology);
        assertThat(restoredTopology.getRoot().getChildren().getFirst().getOffset())
                .isEqualTo(-560f);

        // Change user.
        store.reloadTopologies(/*userId=*/ 0);
        // Should return null because the topology is not found for user 0.
        assertThat(store.restoreTopology(mTopology)).isNull();
    }

    @Test
    public void testMultipleUserTopologies() {
        var store = new DisplayTopologyXmlStore(mInjector);
        store.reloadTopologies(/*userId=*/ 2);

        var topology4Displays = new DisplayTopology();
        topology4Displays.addDisplay(0, 800f, 600f);
        topology4Displays.addDisplay(1, 1920f, 1080f);
        topology4Displays.addDisplay(2, 480f, 640f);
        topology4Displays.addDisplay(3, 768f, 1024f);

        var restored = store.restoreTopology(topology4Displays);
        assertThat(restored).isNotNull();
        assertThat(restored.getPrimaryDisplayId()).isEqualTo(2);
        assertThat(restored.getRoot()).isNotNull();
        assertThat(restored.getRoot().getDisplayId()).isEqualTo(1);
        assertThat(restored.getRoot().getWidth()).isEqualTo(1920f);
        assertThat(restored.getRoot().getHeight()).isEqualTo(1080f);
        assertThat(restored.getRoot().getOffset()).isEqualTo(0);
        assertThat(restored.getRoot().getChildren().size()).isEqualTo(2);
        assertThat(restored.getRoot().getChildren().getFirst().getDisplayId()).isEqualTo(0);
        assertThat(restored.getRoot().getChildren().getFirst().getWidth()).isEqualTo(800f);
        assertThat(restored.getRoot().getChildren().getFirst().getHeight()).isEqualTo(600f);
        assertThat(restored.getRoot().getChildren().getFirst().getOffset()).isEqualTo(-50);
        assertThat(restored.getRoot().getChildren().getFirst().getChildren().size())
                .isEqualTo(0);
        assertThat(restored.getRoot().getChildren().getLast().getDisplayId()).isEqualTo(2);
        assertThat(restored.getRoot().getChildren().getLast().getWidth()).isEqualTo(480f);
        assertThat(restored.getRoot().getChildren().getLast().getHeight()).isEqualTo(640f);
        assertThat(restored.getRoot().getChildren().getLast().getOffset()).isEqualTo(-100);
        assertThat(restored.getRoot().getChildren().getLast().getChildren().size())
                .isEqualTo(1);

        assertThat(restored.getRoot().getChildren().getLast().getChildren().getFirst()
                 .getDisplayId()).isEqualTo(3);
        assertThat(restored.getRoot().getChildren().getLast().getChildren().getFirst()
                 .getWidth()).isEqualTo(768f);
        assertThat(restored.getRoot().getChildren().getLast().getChildren().getFirst()
                 .getHeight()).isEqualTo(1024f);
        assertThat(restored.getRoot().getChildren().getLast().getChildren().getFirst()
                 .getOffset()).isEqualTo(-300);
    }

    @Test
    public void testLimitNumberOfTopologies() {
        var store = new DisplayTopologyXmlStore(mInjector);
        store.reloadTopologies(/*userId=*/ 0);
        for (int i = 0; i < 110; i++) {
            assertThat(store.saveTopology(generateTopology(i, i + 1))).isTrue();
        }

        assertThat(store.restoreTopology(generateTopology(110, 111))).isNull();
        assertThat(store.restoreTopology(generateTopology(109, 110))).isNotNull();
        assertThat(store.restoreTopology(generateTopology(10, 11))).isNotNull();
        assertThat(store.restoreTopology(generateTopology(9, 10))).isNull();
        for (int i = 0; i < 100; i++) {
            assertThat(store.restoreTopology(generateTopology(i + 10, i + 11))).isNotNull();
        }
    }

    @Test
    public void testVendorTopology() throws IOException {
        configureVendorTopologyFile(IMMUTABLE_TOPOLOGY);
        var store = new DisplayTopologyXmlStore(mInjector);
        store.reloadTopologies(/*userId=*/ 0);
        var restored = store.restoreTopology(generateTopology(10, 11));
        assertThat(restored).isNotNull();
        assertThat(store.saveTopology(restored)).isFalse();

        var userTopology = generateTopology(0, 1);
        assertThat(store.restoreTopology(userTopology)).isNull();
        assertThat(store.saveTopology(userTopology)).isTrue();
    }

    @Test
    public void testProductTopology() throws IOException {
        configureProductTopologyFile(IMMUTABLE_TOPOLOGY);
        var store = new DisplayTopologyXmlStore(mInjector);
        store.reloadTopologies(/*userId=*/ 0);
        var restored = store.restoreTopology(generateTopology(10, 11));
        assertThat(restored).isNotNull();
        assertThat(store.saveTopology(restored)).isFalse();

        var userTopology = generateTopology(0, 1);
        assertThat(store.restoreTopology(userTopology)).isNull();
        assertThat(store.saveTopology(userTopology)).isTrue();
    }

    private void configureTopologyFile(int userId, String initialFileContent,
            AtomicFilePrintWriter printWriter) throws IOException {
        doReturn(asInputStream(initialFileContent)).when(mInjector).readUserTopologies(eq(userId));
        doReturn(printWriter).when(mInjector).getTopologyFilePrintWriter(eq(userId));
    }

    private void configureVendorTopologyFile(String initialFileContent) throws IOException {
        doReturn(asInputStream(initialFileContent)).when(mInjector).readVendorTopologies();
    }

    private void configureProductTopologyFile(String initialFileContent) throws IOException {
        doReturn(asInputStream(initialFileContent)).when(mInjector).readProductTopologies();
    }
}
