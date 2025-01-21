/*
 * Copyright 2024 The Android Open Source Project
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

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Comparator.comparingInt;

import android.annotation.Nullable;
import android.hardware.display.DisplayTopology;
import android.os.Environment;
import android.util.AtomicFile;
import android.util.AtomicFilePrintWriter;
import android.util.Slog;
import android.util.SparseArray;

// automatically generated classes from display-topology.xsd
import com.android.server.display.topology.Children;
import com.android.server.display.topology.Display;
import com.android.server.display.topology.DisplayTopologyState;
import com.android.server.display.topology.Position;
import com.android.server.display.topology.Topology;
import com.android.server.display.topology.XmlParser;
import com.android.server.display.topology.XmlWriter;
import com.android.server.display.utils.DebugUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.datatype.DatatypeConfigurationException;

/**
 * Saves and restores {@link DisplayTopology} to/from xml files with topologies for each
 * {@link DisplayTopologyXmlStore#mUserId} user.
 */
class DisplayTopologyXmlStore implements DisplayTopologyStore {
    private static final String TAG = "DisplayManager.DisplayTopologyXmlStore";
    private static final String ETC_DIR = "etc";
    private static final String DISPLAY_CONFIG_DIR = "displayconfig";

    // To enable these logs, run:
    // adb shell setprop persist.log.tag.DisplayManager.DisplayTopologyXmlStore DEBUG
    // adb reboot
    private static final boolean DEBUG = DebugUtils.isDebuggable(TAG);

    private static final int PERSISTENT_TOPOLOGY_VERSION = 1;
    /**
     * {@link #restoreTopology} needs to reorder topologies to keep the most recently used
     * topologies order close to 0. In case current topology displays change often, the persistence
     * of the reordered topologies can become a performance issue. To avoid persistence for small
     * changes in the order values lets use this constant, serving as the threshold when
     * to trigger persistence during {@link #restoreTopology}.
     */
    private static final int MIN_REORDER_WHICH_TRIGGERS_PERSISTENCE = 10;
    private static final int MAX_NUMBER_OF_TOPOLOGIES = 100;

    static File getUserTopologyFile(int userId) {
        return new File(Environment.getDataSystemCeDirectory(userId), "display_topology.xml");
    }

    private static File getVendorTopologyFile() {
        return Environment.buildPath(Environment.getVendorDirectory(),
                ETC_DIR, DISPLAY_CONFIG_DIR, "display_topology.xml");
    }

    private static File getProductTopologyFile() {
        return Environment.buildPath(Environment.getProductDirectory(),
                ETC_DIR, DISPLAY_CONFIG_DIR, "display_topology.xml");
    }

    private static List<Topology> readTopologiesFromInputStream(
            @Nullable InputStream iStream)
            throws DatatypeConfigurationException, XmlPullParserException, IOException {
        if (null == iStream) {
            if (DEBUG) {
                Slog.d(TAG, "iStream is null");
            }
            return List.of();
        }
        // use parser automatically generated from display-topology.xsd
        var topologyState = XmlParser.read(iStream);
        if (topologyState.getVersion() > PERSISTENT_TOPOLOGY_VERSION) {
            Slog.e(TAG, "Topology version=" + topologyState.getVersion()
                    + " is not supported by DisplayTopologyXmlStore version="
                    + PERSISTENT_TOPOLOGY_VERSION);
            return List.of();
        }
        if (DEBUG) {
            Slog.d(TAG, "readTopologiesFromInputStream: done");
        }

        var topologyList = topologyState.getTopology();
        topologyList.sort(comparingInt(Topology::getOrder));
        return topologyList;
    }

    private static int getOrderOrDefault(@Nullable Topology topology, int defaultOrder) {
        return null != topology ? topology.getOrder() : defaultOrder;
    }

    private final Injector mInjector;
    private int mUserId = -1;
    private final List<Topology> mImmutableTopologies = new ArrayList<>();
    private final Map<String, Topology> mTopologies = new HashMap<>();

    DisplayTopologyXmlStore(Injector injector) {
        mInjector = injector;
        reloadImmutableTopologies();
    }

    /**
     * Persists the topology into XML
     * @param topology the topology to persist
     * @return true if persisted successfully, false otherwise
     */
    @Override
    public boolean saveTopology(DisplayTopology topology) {
        String topologyId = getTopologyId(topology);
        if (DEBUG) {
            Slog.d(TAG, "saveTopology userId=" + mUserId + ", topologyId=" + topologyId);
        }
        if (null == topologyId) {
            Slog.w(TAG, "saveTopology cancelled: topology id is null for " + topology);
            return false;
        }

        Topology topologyToPersist = convertTopologyForPersistence(topology, topologyId);
        if (null == topologyToPersist) {
            Slog.w(TAG, "saveTopology cancelled: can't convert topology " + topology);
            return false;
        }

        if (!prependTopology(topologyToPersist)) {
            Slog.w(TAG, "saveTopology cancelled: can't prependTopology");
            return false;
        }
        saveTopologiesToFile();
        return true;
    }

    /**
     * Searches for the topology's id in the store. If topology is found in the store,
     * then uses the passed topology display width and height, and the persisted topology
     * structure, position and offset.
     * @param topology original topology which we would like to restore to a state which was
     *                 previously persisted, keeping the current width and height.
     * @return null if topology is not found, or the new restored topology otherwise.
     */
    @Nullable
    @Override
    public DisplayTopology restoreTopology(DisplayTopology topology) {
        String topologyId = getTopologyId(topology);
        if (DEBUG) {
            Slog.d(TAG, "restoreTopology userId=" + mUserId + ", topologyId=" + topologyId);
        }
        if (null == topologyId) {
            Slog.w(TAG, "restoreTopology cancelled: topology id is null for " + topology);
            return null;
        }

        Topology restoredTopology = mTopologies.get(topologyId);
        if (null == restoredTopology) {
            // Topology is not found in persistent storage.
            if (DEBUG) {
                Slog.d(TAG, "restoreTopology userId=" + mUserId + ", topologyId=" + topologyId
                        + " is not found");
            }
            return null;
        }

        // Reorder and save to file for significant changes in topologies order.
        if (restoredTopology.getOrder() >= MIN_REORDER_WHICH_TRIGGERS_PERSISTENCE) {
            moveTopologyToHead(restoredTopology);
            saveTopologiesToFile();
        }
        return convertPersistentTopologyToDisplayTopology(topology, restoredTopology.getDisplay(),
                mInjector.getUniqueIdToDisplayIdMapping());
    }

    @Override
    public void reloadTopologies(int userId) {
        if (DEBUG) {
            Slog.d(TAG, "reloadTopologies mUserId=" + mUserId + "->userId=" + userId);
        }
        if (mUserId != userId) {
            mUserId = userId;
            resetTopologies();
        }
        reloadTopologies();
    }

    private void resetTopologies() {
        mTopologies.clear();
        appendTopologies(mImmutableTopologies);
    }

    /**
     * Increases all orders by 1 for those topologies currently below the order of the
     * passed topology. Sets the order of the passed topology to 0.
     */
    private void moveTopologyToHead(Topology topology) {
        if (topology.getOrder() == 0) {
            return;
        }
        for (var t : mTopologies.values()) {
            if (t.getOrder() < topology.getOrder()) {
                t.setOrder(t.getOrder() + 1);
            }
        }
        topology.setOrder(0);
    }

    private void reloadImmutableTopologies() {
        mImmutableTopologies.clear();
        try (InputStream iStream = mInjector.readProductTopologies()) {
            mImmutableTopologies.addAll(readTopologiesFromInputStream(iStream));
        } catch (IOException | XmlPullParserException | DatatypeConfigurationException e) {
            Slog.e(TAG, "reloadImmutableTopologies for product topologies failed", e);
        }
        try (InputStream iStream = mInjector.readVendorTopologies()) {
            mImmutableTopologies.addAll(readTopologiesFromInputStream(iStream));
        } catch (IOException | XmlPullParserException | DatatypeConfigurationException e) {
            Slog.e(TAG, "reloadImmutableTopologies for vendor topologies failed", e);
        }
        for (var topology : mImmutableTopologies) {
            topology.setImmutable(true);
        }
    }

    private void reloadTopologies() {
        if (mUserId < 0) {
            Slog.e(TAG, "Can't reload topologies for userId=" + mUserId);
            return;
        }
        try (InputStream iStream = mInjector.readUserTopologies(mUserId)) {
            appendTopologies(readTopologiesFromInputStream(iStream));
        } catch (IOException | XmlPullParserException | DatatypeConfigurationException e) {
            Slog.e(TAG, "reloadTopologies failed", e);
        }
    }

    private void appendTopologies(List<Topology> topologyList) {
        for (var topology : topologyList) {
            appendTopology(topology);
        }
    }

    private void appendTopology(Topology topology) {
        Topology restoredTopology = mTopologies.get(topology.getId());
        if (null != restoredTopology && restoredTopology.getImmutable()) {
            Slog.w(TAG, "addTopology: can't override immutable topology "
                    + topology.getId());
            return;
        }

        // If topology is not found, and we exceed the limit of topologies
        // (so we can't add more topologies), then skip this topology
        if (null == restoredTopology && mTopologies.size() >= MAX_NUMBER_OF_TOPOLOGIES) {
            if (DEBUG) {
                Slog.d(TAG, "appendTopology: MAX_NUMBER_OF_TOPOLOGIES is reached,"
                        + " can't append topology" + topology.getId());
            }
            return;
        }
        topology.setOrder(getOrderOrDefault(restoredTopology, mTopologies.size()));
        mTopologies.put(topology.getId(), topology);
    }

    private boolean prependTopology(Topology topology) {
        Topology restoredTopology = mTopologies.get(topology.getId());
        if (null != restoredTopology && restoredTopology.getImmutable()) {
            Slog.w(TAG, "prependTopology: can't override immutable topology "
                    + topology.getId());
            return false;
        }

        // If topology is not found, and we exceed the limit of topologies
        // remove the max order mutable topology.
        if (null == restoredTopology && mTopologies.size() >= MAX_NUMBER_OF_TOPOLOGIES) {
            Topology topologyToRemove = findMaxOrderMutableTopology();
            if (topologyToRemove == null) {
                Slog.w(TAG, "prependTopology: can't find a topology to remove to free up space");
                return false;
            }
            mTopologies.remove(topologyToRemove.getId());
            if (DEBUG) {
                Slog.d(TAG, "prependTopology: remove topology " + topologyToRemove.getId());
            }
        }

        topology.setOrder(Integer.MAX_VALUE);
        moveTopologyToHead(topology);
        mTopologies.put(topology.getId(), topology);
        return true;
    }

    /**
     * Higher order of the topology means lower priority.
     */
    @Nullable
    private Topology findMaxOrderMutableTopology() {
        Topology res = null;
        for (var topology : mTopologies.values()) {
            if (topology.getImmutable()) {
                continue;
            }
            if (res == null || res.getOrder() < topology.getOrder()) {
                res = topology;
            }
        }
        return res;
    }

    private void saveTopologiesToFile() {
        if (mUserId < 0) {
            Slog.e(TAG, "Can't save topologies for userId=" + mUserId);
            return;
        }
        if (mTopologies.isEmpty()) {
            if (DEBUG) {
                Slog.d(TAG, "No topologies to save for userId=" + mUserId);
            }
            return;
        }
        var topologyState = new DisplayTopologyState();
        topologyState.setVersion(PERSISTENT_TOPOLOGY_VERSION);
        for (var topology : mTopologies.values()) {
            if (!topology.getImmutable()) {
                topologyState.getTopology().add(topology);
            }
        }

        try (var pw = mInjector.getTopologyFilePrintWriter(mUserId)) {
            // use writer automatically generated from display-topology.xsd
            XmlWriter.write(new XmlWriter(pw), topologyState);
            pw.markSuccess();
            if (DEBUG) Slog.d(TAG, "saveTopologiesToFile " + pw);
        } catch (IOException e) {
            Slog.e(TAG, "saveTopologiesToFile failed", e);
        }
    }

    private DisplayTopology convertPersistentTopologyToDisplayTopology(
            DisplayTopology currentDisplayTopology,
            Display persistentDisplayTopology,
            Map<String, Integer> uniqueIdToDisplayIdMapping) {
        var rootNode = convertPersistentDisplayToTreeNode(persistentDisplayTopology,
                currentDisplayTopology, uniqueIdToDisplayIdMapping);
        int primaryDisplayId = findPrimaryDisplayId(persistentDisplayTopology,
                uniqueIdToDisplayIdMapping);
        if (primaryDisplayId == INVALID_DISPLAY) {
            Slog.e(TAG, "Primary display id is not found in persistent topology");
            primaryDisplayId = DEFAULT_DISPLAY;
        }
        return new DisplayTopology(rootNode, primaryDisplayId);
    }

    private DisplayTopology.TreeNode convertPersistentDisplayToTreeNode(
            Display persistentDisplay,
            DisplayTopology currentDisplayTopology,
            Map<String, Integer> uniqueIdToDisplayIdMapping
    ) {
        Integer displayId = uniqueIdToDisplayIdMapping.get(persistentDisplay.getId());
        if (null == displayId) {
            throw new IllegalStateException("Can't map uniqueId="
                    + persistentDisplay.getId() + " to displayId");
        }

        var displayNode = DisplayTopology.findDisplay(displayId,
                currentDisplayTopology.getRoot());
        if (null == displayNode) {
            throw new IllegalStateException("Can't find displayId="
                    + displayId + " in current topology");
        }

        List<DisplayTopology.TreeNode> children = new ArrayList<>();
        for (var child : persistentDisplay.getChildren().getDisplay()) {
            children.add(convertPersistentDisplayToTreeNode(child, currentDisplayTopology,
                    uniqueIdToDisplayIdMapping));
        }

        return new DisplayTopology.TreeNode(
                displayId, displayNode.getWidth(), displayNode.getHeight(),
                toDisplayTopologyPosition(persistentDisplay.getPosition()),
                persistentDisplay.getOffset(), children);
    }

    private int findPrimaryDisplayId(Display persistentDisplay,
            Map<String, Integer> uniqueIdToDisplayIdMapping) {
        if (persistentDisplay.getPrimary()) {
            var displayId = uniqueIdToDisplayIdMapping.get(persistentDisplay.getId());
            if (null == displayId) {
                throw new IllegalStateException("Can't map uniqueId="
                        + persistentDisplay.getId() + " to displayId");
            }
            return displayId;
        }
        for (var child : persistentDisplay.getChildren().getDisplay()) {
            var displayId = findPrimaryDisplayId(child, uniqueIdToDisplayIdMapping);
            if (displayId != INVALID_DISPLAY) {
                return displayId;
            }
        }
        return INVALID_DISPLAY;
    }

    @Nullable
    private Topology convertTopologyForPersistence(DisplayTopology topology, String topologyId) {
        var rootNode = convertTreeNodeForPersistence(topology.getRoot(),
                topology.getPrimaryDisplayId(), mInjector.getDisplayIdToUniqueIdMapping());
        if (null == rootNode) {
            return null;
        }

        Topology persistentTopology = new Topology();
        persistentTopology.setDisplay(rootNode);
        persistentTopology.setId(topologyId);
        return persistentTopology;
    }

    @Nullable
    private Display convertTreeNodeForPersistence(
            @Nullable DisplayTopology.TreeNode node,
            int primaryDisplayId,
            SparseArray<String> idsToUniqueIds) {
        if (null == node) {
            Slog.e(TAG, "Can't convertTreeNodeForPersistence, node == null");
            return null;
        }
        var uniqueId = idsToUniqueIds.get(node.getDisplayId());
        if (null == uniqueId) {
            Slog.e(TAG, "Can't convertTreeNodeForPersistence,"
                    + " uniqueId is not found for " + node.getDisplayId());
            return null;
        }
        Children children = new Children();
        for (var child : node.getChildren()) {
            var display = convertTreeNodeForPersistence(child, primaryDisplayId, idsToUniqueIds);
            if (null == display) {
                return null;
            }
            children.getDisplay().add(display);
        }
        var root = new Display();
        root.setPosition(toPersistentPosition(node.getPosition()));
        root.setId(uniqueId);
        root.setOffset(node.getOffset());
        root.setPrimary(node.getDisplayId() == primaryDisplayId);
        root.setChildren(children);
        return root;
    }

    private Position toPersistentPosition(@DisplayTopology.TreeNode.Position int pos) {
        return switch (pos) {
            case DisplayTopology.TreeNode.POSITION_LEFT -> Position.left;
            case DisplayTopology.TreeNode.POSITION_TOP -> Position.top;
            case DisplayTopology.TreeNode.POSITION_RIGHT -> Position.right;
            case DisplayTopology.TreeNode.POSITION_BOTTOM -> Position.bottom;
            default -> throw new IllegalArgumentException("Unknown position=" + pos);
        };
    }

    @DisplayTopology.TreeNode.Position
    private int toDisplayTopologyPosition(Position pos) {
        return switch (pos) {
            case left ->  DisplayTopology.TreeNode.POSITION_LEFT;
            case top ->  DisplayTopology.TreeNode.POSITION_TOP;
            case right ->  DisplayTopology.TreeNode.POSITION_RIGHT;
            case bottom ->  DisplayTopology.TreeNode.POSITION_BOTTOM;
        };
    }

    private List<String> getUniqueIds(@Nullable DisplayTopology.TreeNode node,
            SparseArray<String> mapping, List<String> uniqueIds) {
        if (null == node) {
            return uniqueIds;
        }
        uniqueIds.add(mapping.get(node.getDisplayId()));
        for (var child : node.getChildren()) {
            getUniqueIds(child, mapping, uniqueIds);
        }
        return uniqueIds;
    }

    @Nullable
    private String getTopologyId(DisplayTopology topology) {
        SparseArray<String> mapping = mInjector.getDisplayIdToUniqueIdMapping();
        return getTopologyId(getUniqueIds(topology.getRoot(), mapping, new ArrayList<>()));
    }

    @Nullable
    private String getTopologyId(List<String> uniqueIds) {
        if (uniqueIds.isEmpty() || uniqueIds.contains(null)) {
            return null;
        }
        Collections.sort(uniqueIds);
        return String.join("|", uniqueIds);
    }

    abstract static class Injector {
        /**
         * Necessary mapping for conversion of {@link DisplayTopology} which uses
         * {@link android.view.DisplayInfo#displayId} to {@link DisplayTopologyState}
         * which uses {@link android.view.DisplayInfo#uniqueId}
         *
         * @return mapping from {@link android.view.DisplayInfo#displayId}
         * to {@link android.view.DisplayInfo#uniqueId}
         */
        public abstract SparseArray<String> getDisplayIdToUniqueIdMapping();

        /**
         * Necessary mapping for conversion opposite to {@link #getDisplayIdToUniqueIdMapping()}
         *
         * @return mapping from {@link android.view.DisplayInfo#uniqueId}
         *  to {@link android.view.DisplayInfo#displayId}
         */
        public abstract Map<String, Integer> getUniqueIdToDisplayIdMapping();

        /**
         * Reads vendor topologies, if configured.
         * @return input stream with vendor-defined topologies, or null if not configured.
         */
        @Nullable
        public InputStream readVendorTopologies() throws FileNotFoundException {
            return getFileInputStream(getVendorTopologyFile());
        }

        /**
         * Reads product topologies, if configured.
         * @return input stream with product-defined topologies, or null if not configured.
         */
        @Nullable
        public InputStream readProductTopologies() throws FileNotFoundException {
            return getFileInputStream(getProductTopologyFile());
        }

        @Nullable
        InputStream readUserTopologies(int userId) throws FileNotFoundException {
            return getFileInputStream(getUserTopologyFile(userId));
        }

        AtomicFilePrintWriter getTopologyFilePrintWriter(int userId) throws IOException {
            var atomicFile = new AtomicFile(getUserTopologyFile(userId),
                    /*commitTag=*/ "topology-state");
            return new AtomicFilePrintWriter(atomicFile, UTF_8);
        }

        @Nullable
        private FileInputStream getFileInputStream(File file) throws FileNotFoundException {
            if (DEBUG) {
                Slog.d(TAG, "File: " + file + " exists=" + file.exists());
            }
            return !file.exists() ? null : new FileInputStream(file);
        }
    }
}
