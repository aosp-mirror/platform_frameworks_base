/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.render

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.notification.NotificationSectionsFeatureManager
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.GroupEntryBuilder
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.getAttachState
import com.android.systemui.statusbar.notification.collection.listbuilder.NotifSection
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner
import com.android.systemui.statusbar.notification.collection.provider.SectionHeaderVisibilityProvider
import com.android.systemui.statusbar.notification.stack.BUCKET_ALERTING
import com.android.systemui.statusbar.notification.stack.BUCKET_PEOPLE
import com.android.systemui.statusbar.notification.stack.BUCKET_SILENT
import com.android.systemui.statusbar.notification.stack.PriorityBucket
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when` as whenever

@SmallTest
class NodeSpecBuilderTest : SysuiTestCase() {

    private val mediaContainerController: MediaContainerController = mock()
    private val sectionsFeatureManager: NotificationSectionsFeatureManager = mock()
    private val sectionHeaderVisibilityProvider: SectionHeaderVisibilityProvider = mock()
    private val viewBarn: NotifViewBarn = mock()
    private val logger: NodeSpecBuilderLogger = mock()

    private var rootController: NodeController = buildFakeController("rootController")
    private var headerController0: NodeController = buildFakeController("header0")
    private var headerController1: NodeController = buildFakeController("header1")
    private var headerController2: NodeController = buildFakeController("header2")

    private val section0Bucket = BUCKET_PEOPLE
    private val section1Bucket = BUCKET_ALERTING
    private val section2Bucket = BUCKET_SILENT

    private val section0 = buildSection(0, section0Bucket, headerController0)
    private val section0NoHeader = buildSection(0, section0Bucket, null)
    private val section1 = buildSection(1, section1Bucket, headerController1)
    private val section1NoHeader = buildSection(1, section1Bucket, null)
    private val section2 = buildSection(2, section2Bucket, headerController2)
    private val section3 = buildSection(3, section2Bucket, headerController2)

    private val fakeViewBarn = FakeViewBarn()

    private lateinit var specBuilder: NodeSpecBuilder

    @Before
    fun setUp() {
        whenever(mediaContainerController.mediaContainerView).thenReturn(mock())
        whenever(viewBarn.requireNodeController(any())).thenAnswer {
            fakeViewBarn.getViewByEntry(it.getArgument(0))
        }

        specBuilder = NodeSpecBuilder(mediaContainerController, sectionsFeatureManager,
                sectionHeaderVisibilityProvider, viewBarn, logger)
    }

    @Test
    fun testMultipleSectionsWithSameController() {
        whenever(sectionHeaderVisibilityProvider.sectionHeadersVisible).thenReturn(true)
        checkOutput(
                listOf(
                        notif(0, section0),
                        notif(1, section2),
                        notif(2, section3)
                ),
                tree(
                        node(headerController0),
                        notifNode(0),
                        node(headerController2),
                        notifNode(1),
                        notifNode(2)
                )
        )
    }

    @Test(expected = RuntimeException::class)
    fun testMultipleSectionsWithSameControllerNonConsecutive() {
        whenever(sectionHeaderVisibilityProvider.sectionHeadersVisible).thenReturn(true)
        checkOutput(
                listOf(
                        notif(0, section0),
                        notif(1, section1),
                        notif(2, section3),
                        notif(3, section1)
                ),
                tree()
        )
    }

    @Test
    fun testSimpleMapping() {
        whenever(sectionHeaderVisibilityProvider.sectionHeadersVisible).thenReturn(true)
        checkOutput(
            // GIVEN a simple flat list of notifications all in the same headerless section
            listOf(
                notif(0, section0NoHeader),
                notif(1, section0NoHeader),
                notif(2, section0NoHeader),
                notif(3, section0NoHeader)
            ),

            // THEN we output a similarly simple flag list of nodes
            tree(
                notifNode(0),
                notifNode(1),
                notifNode(2),
                notifNode(3)
            )
        )
    }

    @Test
    fun testSimpleMappingWithMedia() {
        whenever(sectionHeaderVisibilityProvider.sectionHeadersVisible).thenReturn(true)
        // WHEN media controls are enabled
        whenever(sectionsFeatureManager.isMediaControlsEnabled()).thenReturn(true)

        checkOutput(
                // GIVEN a simple flat list of notifications all in the same headerless section
                listOf(
                        notif(0, section0NoHeader),
                        notif(1, section0NoHeader),
                        notif(2, section0NoHeader),
                        notif(3, section0NoHeader)
                ),

                // THEN we output a similarly simple flag list of nodes, with media at the top
                tree(
                        node(mediaContainerController),
                        notifNode(0),
                        notifNode(1),
                        notifNode(2),
                        notifNode(3)
                )
        )
    }

    @Test
    fun testHeaderInjection() {
        // WHEN section headers are supposed to be visible
        whenever(sectionHeaderVisibilityProvider.sectionHeadersVisible).thenReturn(true)
        checkOutput(
                // GIVEN a flat list of notifications, spread across three sections
                listOf(
                        notif(0, section0),
                        notif(1, section0),
                        notif(2, section1),
                        notif(3, section2)
                ),

                // THEN each section has its header injected
                tree(
                        node(headerController0),
                        notifNode(0),
                        notifNode(1),
                        node(headerController1),
                        notifNode(2),
                        node(headerController2),
                        notifNode(3)
                )
        )
    }

    @Test
    fun testHeaderSuppression() {
        // WHEN section headers are supposed to be hidden
        whenever(sectionHeaderVisibilityProvider.sectionHeadersVisible).thenReturn(false)
        checkOutput(
                // GIVEN a flat list of notifications, spread across three sections
                listOf(
                        notif(0, section0),
                        notif(1, section0),
                        notif(2, section1),
                        notif(3, section2)
                ),

                // THEN each section has its header injected
                tree(
                        notifNode(0),
                        notifNode(1),
                        notifNode(2),
                        notifNode(3)
                )
        )
    }

    @Test
    fun testGroups() {
        whenever(sectionHeaderVisibilityProvider.sectionHeadersVisible).thenReturn(true)
        checkOutput(
                // GIVEN a mixed list of top-level notifications and groups
                listOf(
                    notif(0, section0),
                    group(1, section1,
                            notif(2),
                            notif(3),
                            notif(4)
                    ),
                    notif(5, section2),
                    group(6, section2,
                            notif(7),
                            notif(8),
                            notif(9)
                    )
                ),

                // THEN we properly construct all the nodes
                tree(
                        node(headerController0),
                        notifNode(0),
                        node(headerController1),
                        notifNode(1,
                                notifNode(2),
                                notifNode(3),
                                notifNode(4)
                        ),
                        node(headerController2),
                        notifNode(5),
                        notifNode(6,
                                notifNode(7),
                                notifNode(8),
                                notifNode(9)
                        )
                )
        )
    }

    @Test
    fun testSecondSectionWithNoHeader() {
        whenever(sectionHeaderVisibilityProvider.sectionHeadersVisible).thenReturn(true)
        checkOutput(
                // GIVEN a middle section with no associated header view
                listOf(
                        notif(0, section0),
                        notif(1, section1NoHeader),
                        group(2, section1NoHeader,
                                notif(3),
                                notif(4)
                        ),
                        notif(5, section2)
                ),

                // THEN the header view is left out of the tree (but the notifs are still present)
                tree(
                        node(headerController0),
                        notifNode(0),
                        notifNode(1),
                        notifNode(2,
                                notifNode(3),
                                notifNode(4)
                        ),
                        node(headerController2),
                        notifNode(5)
                )
        )
    }

    @Test(expected = RuntimeException::class)
    fun testRepeatedSectionsThrow() {
        whenever(sectionHeaderVisibilityProvider.sectionHeadersVisible).thenReturn(true)
        checkOutput(
                // GIVEN a malformed list where sections are not contiguous
                listOf(
                        notif(0, section0),
                        notif(1, section1),
                        notif(2, section0)
                ),

                // THEN an exception is thrown
                tree()
        )
    }

    private fun checkOutput(list: List<ListEntry>, desiredTree: NodeSpecImpl) {
        checkTree(desiredTree, specBuilder.buildNodeSpec(rootController, list))
    }

    private fun checkTree(desiredTree: NodeSpec, actualTree: NodeSpec) {
        try {
            checkNode(desiredTree, actualTree)
        } catch (e: AssertionError) {
            throw AssertionError("Trees don't match: ${e.message}\nActual tree:\n" +
                    treeSpecToStr(actualTree))
        }
    }

    private fun checkNode(desiredTree: NodeSpec, actualTree: NodeSpec) {
        if (actualTree.controller != desiredTree.controller) {
            throw AssertionError("Node {${actualTree.controller.nodeLabel}} should " +
                    "be ${desiredTree.controller.nodeLabel}")
        }
        for (i in 0 until desiredTree.children.size) {
            if (i >= actualTree.children.size) {
                throw AssertionError("Node {${actualTree.controller.nodeLabel}}" +
                        " is missing child ${desiredTree.children[i].controller.nodeLabel}")
            }
            checkNode(desiredTree.children[i], actualTree.children[i])
        }
    }

    private fun notif(id: Int, section: NotifSection? = null): NotificationEntry {
        val entry = NotificationEntryBuilder()
                .setId(id)
                .build()
        if (section != null) {
            getAttachState(entry).section = section
        }
        fakeViewBarn.buildNotifView(id, entry)
        return entry
    }

    private fun group(
        id: Int,
        section: NotifSection,
        vararg children: NotificationEntry
    ): GroupEntry {
        val group = GroupEntryBuilder()
                .setKey("group_$id")
                .setSummary(
                        NotificationEntryBuilder()
                                .setId(id)
                                .build())
                .setChildren(children.asList())
                .build()
        getAttachState(group).section = section
        fakeViewBarn.buildNotifView(id, group.summary!!)

        for (child in children) {
            getAttachState(child).section = section
        }
        return group
    }

    private fun tree(vararg children: NodeSpecImpl): NodeSpecImpl {
        return node(rootController, *children)
    }

    private fun node(view: NodeController, vararg children: NodeSpecImpl): NodeSpecImpl {
        val node = NodeSpecImpl(null, view)
        node.children.addAll(children)
        return node
    }

    private fun notifNode(id: Int, vararg children: NodeSpecImpl): NodeSpecImpl {
        return node(fakeViewBarn.getViewById(id), *children)
    }
}

private class FakeViewBarn {
    private val entries = mutableMapOf<Int, NotificationEntry>()
    private val views = mutableMapOf<NotificationEntry, NodeController>()

    fun buildNotifView(id: Int, entry: NotificationEntry) {
        if (entries.contains(id)) {
            throw RuntimeException("ID $id is already in use")
        }
        entries[id] = entry
        views[entry] = buildFakeController("Entry $id")
    }

    fun getViewById(id: Int): NodeController {
        return views[entries[id] ?: throw RuntimeException("No view with ID $id")]!!
    }

    fun getViewByEntry(entry: NotificationEntry): NodeController {
        return views[entry] ?: throw RuntimeException("No view defined for key ${entry.key}")
    }
}

private fun buildFakeController(name: String): NodeController {
    val controller = Mockito.mock(NodeController::class.java)
    whenever(controller.nodeLabel).thenReturn(name)
    return controller
}

private fun buildSection(
    index: Int,
    @PriorityBucket bucket: Int,
    nodeController: NodeController?
): NotifSection {
    return NotifSection(object : NotifSectioner("Section $index (bucket=$bucket)", bucket) {

        override fun isInSection(entry: ListEntry?): Boolean {
            throw NotImplementedError("This should never be called")
        }

        override fun getHeaderNodeController(): NodeController? {
            return nodeController
        }
    }, index)
}
