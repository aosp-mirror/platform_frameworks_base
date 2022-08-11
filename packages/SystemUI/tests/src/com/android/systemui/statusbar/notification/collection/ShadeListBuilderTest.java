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

package com.android.systemui.statusbar.notification.collection;

import static com.android.systemui.statusbar.notification.collection.ListDumper.dumpTree;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;

import android.os.SystemClock;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.ArrayMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.statusbar.NotificationInteractionTracker;
import com.android.systemui.statusbar.RankingBuilder;
import com.android.systemui.statusbar.notification.NotifPipelineFlags;
import com.android.systemui.statusbar.notification.collection.ShadeListBuilder.OnRenderListListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.NotifSection;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeFinalizeFilterListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeRenderListListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeSortListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeTransformGroupsListener;
import com.android.systemui.statusbar.notification.collection.listbuilder.ShadeListBuilderLogger;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Invalidator;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifComparator;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifSectioner;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifStabilityManager;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Pluggable;
import com.android.systemui.statusbar.notification.collection.notifcollection.CollectionReadyForBuildListener;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ShadeListBuilderTest extends SysuiTestCase {

    private ShadeListBuilder mListBuilder;
    private FakeSystemClock mSystemClock = new FakeSystemClock();

    @Mock private NotifPipelineFlags mNotifPipelineFlags;
    @Mock private ShadeListBuilderLogger mLogger;
    @Mock private DumpManager mDumpManager;
    @Mock private NotifCollection mNotifCollection;
    @Mock private NotificationInteractionTracker mInteractionTracker;
    @Spy private OnBeforeTransformGroupsListener mOnBeforeTransformGroupsListener;
    @Spy private OnBeforeSortListener mOnBeforeSortListener;
    @Spy private OnBeforeFinalizeFilterListener mOnBeforeFinalizeFilterListener;
    @Spy private OnBeforeRenderListListener mOnBeforeRenderListListener;
    @Spy private OnRenderListListener mOnRenderListListener = list -> mBuiltList = list;

    @Captor private ArgumentCaptor<CollectionReadyForBuildListener> mBuildListenerCaptor;

    private final FakeNotifPipelineChoreographer mPipelineChoreographer =
            new FakeNotifPipelineChoreographer();
    private CollectionReadyForBuildListener mReadyForBuildListener;
    private List<NotificationEntryBuilder> mPendingSet = new ArrayList<>();
    private List<NotificationEntry> mEntrySet = new ArrayList<>();
    private List<ListEntry> mBuiltList = new ArrayList<>();
    private TestableStabilityManager mStabilityManager;
    private TestableNotifFilter mFinalizeFilter;

    private Map<String, Integer> mNextIdMap = new ArrayMap<>();
    private int mNextRank = 0;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        allowTestableLooperAsMainThread();

        mListBuilder = new ShadeListBuilder(
                mDumpManager,
                mPipelineChoreographer,
                mNotifPipelineFlags,
                mInteractionTracker,
                mLogger,
                mSystemClock
        );
        mListBuilder.setOnRenderListListener(mOnRenderListListener);

        mListBuilder.attach(mNotifCollection);

        mStabilityManager = spy(new TestableStabilityManager());
        mListBuilder.setNotifStabilityManager(mStabilityManager);
        mFinalizeFilter = spy(new TestableNotifFilter());
        mListBuilder.addFinalizeFilter(mFinalizeFilter);

        Mockito.verify(mNotifCollection).setBuildListener(mBuildListenerCaptor.capture());
        mReadyForBuildListener = Objects.requireNonNull(mBuildListenerCaptor.getValue());
    }

    @Test
    public void testNotifsAreSortedByRankAndWhen() {
        // GIVEN a simple pipeline

        // WHEN a series of notifs with jumbled ranks are added
        addNotif(0, PACKAGE_1).setRank(2);
        addNotif(1, PACKAGE_2).setRank(4).modifyNotification(mContext).setWhen(22);
        addNotif(2, PACKAGE_3).setRank(4).modifyNotification(mContext).setWhen(33);
        addNotif(3, PACKAGE_3).setRank(3);
        addNotif(4, PACKAGE_5).setRank(4).modifyNotification(mContext).setWhen(11);
        addNotif(5, PACKAGE_3).setRank(1);
        addNotif(6, PACKAGE_1).setRank(0);
        dispatchBuild();

        // The final output is sorted based first by rank and then by when
        verifyBuiltList(
                notif(6),
                notif(5),
                notif(0),
                notif(3),
                notif(2),
                notif(1),
                notif(4)
        );
    }

    @Test
    public void testNotifsAreGrouped() {
        // GIVEN a simple pipeline

        // WHEN a group is added
        addGroupChild(0, PACKAGE_1, GROUP_1);
        addGroupChild(1, PACKAGE_1, GROUP_1);
        addGroupChild(2, PACKAGE_1, GROUP_1);
        addGroupSummary(3, PACKAGE_1, GROUP_1);
        dispatchBuild();

        // THEN the notifs are grouped together
        verifyBuiltList(
                group(
                        summary(3),
                        child(0),
                        child(1),
                        child(2)
                )
        );
    }

    @Test
    public void testNotifsWithDifferentGroupKeysAreGrouped() {
        // GIVEN a simple pipeline

        // WHEN a package posts two different groups
        addGroupChild(0, PACKAGE_1, GROUP_1);
        addGroupChild(1, PACKAGE_1, GROUP_2);
        addGroupSummary(2, PACKAGE_1, GROUP_2);
        addGroupChild(3, PACKAGE_1, GROUP_2);
        addGroupChild(4, PACKAGE_1, GROUP_1);
        addGroupChild(5, PACKAGE_1, GROUP_2);
        addGroupChild(6, PACKAGE_1, GROUP_1);
        addGroupSummary(7, PACKAGE_1, GROUP_1);
        dispatchBuild();

        // THEN the groups are separated separately
        verifyBuiltList(
                group(
                        summary(2),
                        child(1),
                        child(3),
                        child(5)
                ),
                group(
                        summary(7),
                        child(0),
                        child(4),
                        child(6)
                )
        );
    }

    @Test
    public void testNotifsNotifChildrenAreSorted() {
        // GIVEN a simple pipeline

        // WHEN a group is added
        addGroupChild(0, PACKAGE_1, GROUP_1).setRank(4);
        addGroupChild(1, PACKAGE_1, GROUP_1).setRank(2)
                .modifyNotification(mContext).setWhen(11);
        addGroupChild(2, PACKAGE_1, GROUP_1).setRank(1);
        addGroupChild(3, PACKAGE_1, GROUP_1).setRank(2)
                .modifyNotification(mContext).setWhen(33);
        addGroupChild(4, PACKAGE_1, GROUP_1).setRank(2)
                .modifyNotification(mContext).setWhen(22);
        addGroupChild(5, PACKAGE_1, GROUP_1).setRank(0);
        addGroupSummary(6, PACKAGE_1, GROUP_1).setRank(3);
        dispatchBuild();

        // THEN the children are sorted by rank and when
        verifyBuiltList(
                group(
                        summary(6),
                        child(5),
                        child(2),
                        child(3),
                        child(4),
                        child(1),
                        child(0)
                )
        );
    }

    @Test
    public void testDuplicateGroupSummariesAreDiscarded() {
        // GIVEN a simple pipeline

        // WHEN a group with multiple summaries is added
        addNotif(0, PACKAGE_3);
        addGroupChild(1, PACKAGE_1, GROUP_1);
        addGroupChild(2, PACKAGE_1, GROUP_1);
        addGroupSummary(3, PACKAGE_1, GROUP_1).setPostTime(22);
        addGroupSummary(4, PACKAGE_1, GROUP_1).setPostTime(33);
        addNotif(5, PACKAGE_2);
        addGroupSummary(6, PACKAGE_1, GROUP_1).setPostTime(11);
        addGroupChild(7, PACKAGE_1, GROUP_1);
        dispatchBuild();

        // THEN only most recent summary is used
        verifyBuiltList(
                notif(0),
                group(
                        summary(4),
                        child(1),
                        child(2),
                        child(7)
                ),
                notif(5)
        );

        // THEN the extra summaries have their parents set to null
        assertNull(mEntrySet.get(3).getParent());
        assertNull(mEntrySet.get(6).getParent());
    }

    @Test
    public void testGroupsWithNoSummaryAreUngrouped() {
        // GIVEN a group with no summary
        addNotif(0, PACKAGE_2);
        addGroupChild(1, PACKAGE_4, GROUP_2);
        addGroupChild(2, PACKAGE_4, GROUP_2);
        addGroupChild(3, PACKAGE_4, GROUP_2);
        addGroupChild(4, PACKAGE_4, GROUP_2);

        // WHEN we build the list
        dispatchBuild();

        // THEN the children aren't grouped
        verifyBuiltList(
                notif(0),
                notif(1),
                notif(2),
                notif(3),
                notif(4)
        );
    }

    @Test
    public void testGroupsWithNoChildrenAreUngrouped() {
        // GIVEN a group with a summary but no children
        addGroupSummary(0, PACKAGE_5, GROUP_1);
        addNotif(1, PACKAGE_5);
        addNotif(2, PACKAGE_1);

        // WHEN we build the list
        dispatchBuild();

        // THEN the summary isn't grouped but is still added to the final list
        verifyBuiltList(
                notif(0),
                notif(1),
                notif(2)
        );
    }

    @Test
    public void testGroupsWithTooFewChildrenAreSplitUp() {
        // GIVEN a group with one child
        addGroupChild(0, PACKAGE_2, GROUP_1);
        addGroupSummary(1, PACKAGE_2, GROUP_1);

        // WHEN we build the list
        dispatchBuild();

        // THEN the child is added at top level and the summary is discarded
        verifyBuiltList(
                notif(0)
        );

        assertNull(mEntrySet.get(1).getParent());
    }

    @Test
    public void testGroupsWhoLoseChildrenMidPipelineAreSplitUp() {
        // GIVEN a group with two children
        addGroupChild(0, PACKAGE_2, GROUP_1);
        addGroupSummary(1, PACKAGE_2, GROUP_1);
        addGroupChild(2, PACKAGE_2, GROUP_1);

        // GIVEN a promoter that will promote one of children to top level
        mListBuilder.addPromoter(new IdPromoter(0));

        // WHEN we build the list
        dispatchBuild();

        // THEN both children end up at top level (because group is now too small)
        verifyBuiltList(
                notif(0),
                notif(2)
        );

        // THEN the summary is discarded
        assertNull(mEntrySet.get(1).getParent());
    }

    @Test
    public void testGroupsWhoLoseAllChildrenToPromotionSuppressSummary() {
        // GIVEN a group with two children
        addGroupChild(0, PACKAGE_2, GROUP_1);
        addGroupSummary(1, PACKAGE_2, GROUP_1);
        addGroupChild(2, PACKAGE_2, GROUP_1);

        // GIVEN a promoter that will promote one of children to top level
        mListBuilder.addPromoter(new IdPromoter(0, 2));

        // WHEN we build the list
        dispatchBuild();

        // THEN both children end up at top level (because group is now too small)
        verifyBuiltList(
                notif(0),
                notif(2)
        );

        // THEN the summary is discarded
        assertNull(mEntrySet.get(1).getParent());
    }

    @Test
    public void testGroupsWhoLoseOnlyChildToPromotionSuppressSummary() {
        // GIVEN a group with two children
        addGroupChild(0, PACKAGE_2, GROUP_1);
        addGroupSummary(1, PACKAGE_2, GROUP_1);

        // GIVEN a promoter that will promote one of children to top level
        mListBuilder.addPromoter(new IdPromoter(0));

        // WHEN we build the list
        dispatchBuild();

        // THEN both children end up at top level (because group is now too small)
        verifyBuiltList(
                notif(0)
        );

        // THEN the summary is discarded
        assertNull(mEntrySet.get(1).getParent());
    }

    @Test
    public void testPreviousParentsAreSetProperly() {
        // GIVEN a notification that is initially added to the list
        PackageFilter filter = new PackageFilter(PACKAGE_2);
        filter.setEnabled(false);
        mListBuilder.addPreGroupFilter(filter);

        addNotif(0, PACKAGE_1);
        addNotif(1, PACKAGE_2);
        addNotif(2, PACKAGE_3);
        dispatchBuild();

        // WHEN it is suddenly filtered out
        filter.setEnabled(true);
        dispatchBuild();

        // THEN its previous parent indicates that it used to be added
        assertNull(mEntrySet.get(1).getParent());
        assertEquals(GroupEntry.ROOT_ENTRY, mEntrySet.get(1).getPreviousParent());
    }

    @Test
    public void testThatAnnulledGroupsAndSummariesAreProperlyRolledBack() {
        // GIVEN a registered transform groups listener
        RecordingOnBeforeTransformGroupsListener listener =
                new RecordingOnBeforeTransformGroupsListener();
        mListBuilder.addOnBeforeTransformGroupsListener(listener);

        // GIVEN a malformed group that will be dismantled
        addGroupChild(0, PACKAGE_2, GROUP_1);
        addGroupSummary(1, PACKAGE_2, GROUP_1);
        addNotif(2, PACKAGE_1);

        // WHEN we build the list
        dispatchBuild();

        // THEN only the child appears in the final list
        verifyBuiltList(
                notif(0),
                notif(2)
        );

        // THEN the summary has a null parent and an unset firstAddedIteration
        assertNull(mEntrySet.get(1).getParent());
    }

    @Test
    public void testPreGroupNotifsAreFiltered() {
        // GIVEN a PreGroupNotifFilter and PreRenderFilter that filters out the same package
        NotifFilter preGroupFilter = spy(new PackageFilter(PACKAGE_2));
        NotifFilter preRenderFilter = spy(new PackageFilter(PACKAGE_2));
        mListBuilder.addPreGroupFilter(preGroupFilter);
        mListBuilder.addFinalizeFilter(preRenderFilter);

        // WHEN the pipeline is kicked off on a list of notifs
        addNotif(0, PACKAGE_1);
        addNotif(1, PACKAGE_2);
        addNotif(2, PACKAGE_3);
        addNotif(3, PACKAGE_2);
        dispatchBuild();

        // THEN the preGroupFilter is called on each notif in the original set
        verify(preGroupFilter).shouldFilterOut(eq(mEntrySet.get(0)), anyLong());
        verify(preGroupFilter).shouldFilterOut(eq(mEntrySet.get(1)), anyLong());
        verify(preGroupFilter).shouldFilterOut(eq(mEntrySet.get(2)), anyLong());
        verify(preGroupFilter).shouldFilterOut(eq(mEntrySet.get(3)), anyLong());

        // THEN the preRenderFilter is only called on the notifications not already filtered out
        verify(preRenderFilter).shouldFilterOut(eq(mEntrySet.get(0)), anyLong());
        verify(preRenderFilter, never()).shouldFilterOut(eq(mEntrySet.get(1)), anyLong());
        verify(preRenderFilter).shouldFilterOut(eq(mEntrySet.get(2)), anyLong());
        verify(preRenderFilter, never()).shouldFilterOut(eq(mEntrySet.get(3)), anyLong());

        // THEN the final list doesn't contain any filtered-out notifs
        verifyBuiltList(
                notif(0),
                notif(2)
        );

        // THEN each filtered notif records the NotifFilter that did it
        assertEquals(preGroupFilter, mEntrySet.get(1).getExcludingFilter());
        assertEquals(preGroupFilter, mEntrySet.get(3).getExcludingFilter());
    }

    @Test
    public void testPreRenderNotifsAreFiltered() {
        // GIVEN a NotifFilter that filters out a specific package
        NotifFilter filter1 = spy(new PackageFilter(PACKAGE_2));
        mListBuilder.addFinalizeFilter(filter1);

        // WHEN the pipeline is kicked off on a list of notifs
        addNotif(0, PACKAGE_1);
        addNotif(1, PACKAGE_2);
        addNotif(2, PACKAGE_3);
        addNotif(3, PACKAGE_2);
        dispatchBuild();

        // THEN the filter is called on each notif in the original set
        verify(filter1).shouldFilterOut(eq(mEntrySet.get(0)), anyLong());
        verify(filter1).shouldFilterOut(eq(mEntrySet.get(1)), anyLong());
        verify(filter1).shouldFilterOut(eq(mEntrySet.get(2)), anyLong());
        verify(filter1).shouldFilterOut(eq(mEntrySet.get(3)), anyLong());

        // THEN the final list doesn't contain any filtered-out notifs
        verifyBuiltList(
                notif(0),
                notif(2)
        );

        // THEN each filtered notif records the filter that did it
        assertEquals(filter1, mEntrySet.get(1).getExcludingFilter());
        assertEquals(filter1, mEntrySet.get(3).getExcludingFilter());
    }

    @Test
    public void testPreRenderNotifsFilteredBreakupGroups() {
        final String filterTag = "FILTER_ME";
        // GIVEN a NotifFilter that filters out notifications with a tag
        NotifFilter filter1 = spy(new NotifFilterWithTag(filterTag));
        mListBuilder.addFinalizeFilter(filter1);

        // WHEN the pipeline is kicked off on a list of notifs
        addGroupChildWithTag(0, PACKAGE_2, GROUP_1, filterTag);
        addGroupChild(1, PACKAGE_2, GROUP_1);
        addGroupSummary(2, PACKAGE_2, GROUP_1);
        dispatchBuild();

        // THEN the final list doesn't contain any filtered-out notifs
        // and groups that are too small are broken up
        verifyBuiltList(
                notif(1)
        );

        // THEN each filtered notif records the filter that did it
        assertEquals(filter1, mEntrySet.get(0).getExcludingFilter());
    }

    @Test
    public void testFilter_resetsInitalizationTime() {
        // GIVEN a NotifFilter that filters out a specific package
        NotifFilter filter1 = spy(new PackageFilter(PACKAGE_1));
        mListBuilder.addFinalizeFilter(filter1);

        // GIVEN a notification that was initialized 1 second ago that will be filtered out
        final NotificationEntry entry = new NotificationEntryBuilder()
                .setPkg(PACKAGE_1)
                .setId(nextId(PACKAGE_1))
                .setRank(nextRank())
                .build();
        entry.setInitializationTime(SystemClock.elapsedRealtime() - 1000);
        assertTrue(entry.hasFinishedInitialization());

        // WHEN the pipeline is kicked off
        mReadyForBuildListener.onBuildList(singletonList(entry), "test");
        mPipelineChoreographer.runIfScheduled();

        // THEN the entry's initialization time is reset
        assertFalse(entry.hasFinishedInitialization());
    }

    @Test
    public void testNotifFiltersCanBePreempted() {
        // GIVEN two notif filters
        NotifFilter filter1 = spy(new PackageFilter(PACKAGE_2));
        NotifFilter filter2 = spy(new PackageFilter(PACKAGE_5));
        mListBuilder.addPreGroupFilter(filter1);
        mListBuilder.addPreGroupFilter(filter2);

        // WHEN the pipeline is kicked off on a list of notifs
        addNotif(0, PACKAGE_1);
        addNotif(1, PACKAGE_2);
        addNotif(2, PACKAGE_5);
        dispatchBuild();

        // THEN both filters are called on the first notif but the second filter is never called
        // on the already-filtered second notif
        verify(filter1).shouldFilterOut(eq(mEntrySet.get(0)), anyLong());
        verify(filter1).shouldFilterOut(eq(mEntrySet.get(1)), anyLong());
        verify(filter1).shouldFilterOut(eq(mEntrySet.get(2)), anyLong());
        verify(filter2).shouldFilterOut(eq(mEntrySet.get(0)), anyLong());
        verify(filter2).shouldFilterOut(eq(mEntrySet.get(2)), anyLong());

        // THEN the final list doesn't contain any filtered-out notifs
        verifyBuiltList(
                notif(0)
        );

        // THEN each filtered notif records the filter that did it
        assertEquals(filter1, mEntrySet.get(1).getExcludingFilter());
        assertEquals(filter2, mEntrySet.get(2).getExcludingFilter());
    }

    @Test
    public void testNotifsArePromoted() {
        // GIVEN a NotifPromoter that promotes certain notif IDs
        NotifPromoter promoter = spy(new IdPromoter(1, 2));
        mListBuilder.addPromoter(promoter);

        // WHEN the pipeline is kicked off
        addNotif(0, PACKAGE_1);
        addGroupChild(1, PACKAGE_2, GROUP_1);
        addGroupChild(2, PACKAGE_2, GROUP_1);
        addGroupChild(3, PACKAGE_2, GROUP_1);
        addGroupChild(4, PACKAGE_2, GROUP_1);
        addGroupSummary(5, PACKAGE_2, GROUP_1);
        addNotif(6, PACKAGE_3);
        dispatchBuild();

        // THEN the filter is called on each group child
        verify(promoter).shouldPromoteToTopLevel(mEntrySet.get(1));
        verify(promoter).shouldPromoteToTopLevel(mEntrySet.get(2));
        verify(promoter).shouldPromoteToTopLevel(mEntrySet.get(3));
        verify(promoter).shouldPromoteToTopLevel(mEntrySet.get(4));

        // THEN the final list contains the promoted entries at top level
        verifyBuiltList(
                notif(0),
                notif(2),
                notif(3),
                group(
                        summary(5),
                        child(1),
                        child(4)),
                notif(6)
        );

        // THEN each promoted notif records the promoter that did it
        assertEquals(promoter, mEntrySet.get(2).getNotifPromoter());
        assertEquals(promoter, mEntrySet.get(3).getNotifPromoter());
    }

    @Test
    public void testNotifPromotersCanBePreempted() {
        // GIVEN two notif promoters
        NotifPromoter promoter1 = spy(new IdPromoter(1));
        NotifPromoter promoter2 = spy(new IdPromoter(2));
        mListBuilder.addPromoter(promoter1);
        mListBuilder.addPromoter(promoter2);

        // WHEN the pipeline is kicked off on some notifs and a group
        addNotif(0, PACKAGE_1);
        addGroupChild(1, PACKAGE_2, GROUP_1);
        addGroupChild(2, PACKAGE_2, GROUP_1);
        addGroupChild(3, PACKAGE_2, GROUP_1);
        addGroupSummary(4, PACKAGE_2, GROUP_1);
        addNotif(5, PACKAGE_3);
        dispatchBuild();

        // THEN both promoters are called on each child, except for children that a previous
        // promoter has already promoted
        verify(promoter1).shouldPromoteToTopLevel(mEntrySet.get(1));
        verify(promoter1).shouldPromoteToTopLevel(mEntrySet.get(2));
        verify(promoter1).shouldPromoteToTopLevel(mEntrySet.get(3));

        verify(promoter2).shouldPromoteToTopLevel(mEntrySet.get(1));
        verify(promoter2).shouldPromoteToTopLevel(mEntrySet.get(3));

        // THEN each promoter is recorded on each notif it promoted
        assertEquals(promoter1, mEntrySet.get(2).getNotifPromoter());
        assertEquals(promoter2, mEntrySet.get(3).getNotifPromoter());
    }

    @Test
    public void testNotifSectionsChildrenUpdated() {
        ArrayList<ListEntry> pkg1Entries = new ArrayList<>();
        ArrayList<ListEntry> pkg2Entries = new ArrayList<>();
        ArrayList<ListEntry> pkg3Entries = new ArrayList<>();
        final NotifSectioner pkg1Sectioner = spy(new PackageSectioner(PACKAGE_1) {
            @Override
            public void onEntriesUpdated(List<ListEntry> entries) {
                super.onEntriesUpdated(entries);
                pkg1Entries.addAll(entries);
            }
        });
        final NotifSectioner pkg2Sectioner = spy(new PackageSectioner(PACKAGE_2) {
            @Override
            public void onEntriesUpdated(List<ListEntry> entries) {
                super.onEntriesUpdated(entries);
                pkg2Entries.addAll(entries);
            }
        });
        final NotifSectioner pkg3Sectioner = spy(new PackageSectioner(PACKAGE_3) {
            @Override
            public void onEntriesUpdated(List<ListEntry> entries) {
                super.onEntriesUpdated(entries);
                pkg3Entries.addAll(entries);
            }
        });
        mListBuilder.setSectioners(asList(pkg1Sectioner, pkg2Sectioner, pkg3Sectioner));

        addNotif(0, PACKAGE_1);
        addNotif(1, PACKAGE_1);
        addNotif(2, PACKAGE_3);
        addNotif(3, PACKAGE_3);
        addNotif(4, PACKAGE_3);

        dispatchBuild();

        verify(pkg1Sectioner).onEntriesUpdated(any());
        verify(pkg2Sectioner).onEntriesUpdated(any());
        verify(pkg3Sectioner).onEntriesUpdated(any());
        assertThat(pkg1Entries).containsExactly(
                mEntrySet.get(0),
                mEntrySet.get(1)
        ).inOrder();
        assertThat(pkg2Entries).isEmpty();
        assertThat(pkg3Entries).containsExactly(
                mEntrySet.get(2),
                mEntrySet.get(3),
                mEntrySet.get(4)
        ).inOrder();
    }

    @Test
    public void testNotifSections() {
        // GIVEN a filter that removes all PACKAGE_4 notifs and sections that divide
        // notifs based on package name
        mListBuilder.addPreGroupFilter(new PackageFilter(PACKAGE_4));
        final NotifSectioner pkg1Sectioner = spy(new PackageSectioner(PACKAGE_1));
        final NotifSectioner pkg2Sectioner = spy(new PackageSectioner(PACKAGE_2));
        // NOTE: no package 3 section explicitly added, so notifs with package 3 will get set by
        // ShadeListBuilder's sDefaultSection which will demote it to the last section
        final NotifSectioner pkg4Sectioner = spy(new PackageSectioner(PACKAGE_4));
        final NotifSectioner pkg5Sectioner = spy(new PackageSectioner(PACKAGE_5));
        mListBuilder.setSectioners(
                asList(pkg1Sectioner, pkg2Sectioner, pkg4Sectioner, pkg5Sectioner));

        final NotifSection pkg1Section = new NotifSection(pkg1Sectioner, 0);
        final NotifSection pkg2Section = new NotifSection(pkg2Sectioner, 1);
        final NotifSection pkg5Section = new NotifSection(pkg5Sectioner, 3);

        // WHEN we build a list with different packages
        addNotif(0, PACKAGE_4);
        addNotif(1, PACKAGE_2);
        addNotif(2, PACKAGE_1);
        addNotif(3, PACKAGE_3);
        addGroupSummary(4, PACKAGE_2, GROUP_1);
        addGroupChild(5, PACKAGE_2, GROUP_1);
        addGroupChild(6, PACKAGE_2, GROUP_1);
        addNotif(7, PACKAGE_1);
        addNotif(8, PACKAGE_2);
        addNotif(9, PACKAGE_5);
        addNotif(10, PACKAGE_4);
        dispatchBuild();

        // THEN the list is sorted according to section
        verifyBuiltList(
                notif(2),
                notif(7),
                notif(1),
                group(
                        summary(4),
                        child(5),
                        child(6)
                ),
                notif(8),
                notif(9),
                notif(3)
        );

        // THEN the first section (pkg1Section) is called on all top level elements (but
        // no children and no entries that were filtered out)
        verify(pkg1Sectioner).isInSection(mEntrySet.get(1));
        verify(pkg1Sectioner).isInSection(mEntrySet.get(2));
        verify(pkg1Sectioner).isInSection(mEntrySet.get(3));
        verify(pkg1Sectioner).isInSection(mEntrySet.get(7));
        verify(pkg1Sectioner).isInSection(mEntrySet.get(8));
        verify(pkg1Sectioner).isInSection(mEntrySet.get(9));
        verify(pkg1Sectioner).isInSection(mBuiltList.get(3));

        verify(pkg1Sectioner, never()).isInSection(mEntrySet.get(0));
        verify(pkg1Sectioner, never()).isInSection(mEntrySet.get(4));
        verify(pkg1Sectioner, never()).isInSection(mEntrySet.get(5));
        verify(pkg1Sectioner, never()).isInSection(mEntrySet.get(6));
        verify(pkg1Sectioner, never()).isInSection(mEntrySet.get(10));

        // THEN the last section (pkg5Section) is not called on any of the entries that were
        // filtered or already in a section
        verify(pkg5Sectioner, never()).isInSection(mEntrySet.get(0));
        verify(pkg5Sectioner, never()).isInSection(mEntrySet.get(1));
        verify(pkg5Sectioner, never()).isInSection(mEntrySet.get(2));
        verify(pkg5Sectioner, never()).isInSection(mEntrySet.get(4));
        verify(pkg5Sectioner, never()).isInSection(mEntrySet.get(5));
        verify(pkg5Sectioner, never()).isInSection(mEntrySet.get(6));
        verify(pkg5Sectioner, never()).isInSection(mEntrySet.get(7));
        verify(pkg5Sectioner, never()).isInSection(mEntrySet.get(8));
        verify(pkg5Sectioner, never()).isInSection(mEntrySet.get(10));

        verify(pkg5Sectioner).isInSection(mEntrySet.get(3));
        verify(pkg5Sectioner).isInSection(mEntrySet.get(9));

        // THEN the correct section is assigned for entries in pkg1Section
        assertEquals(pkg1Section, mEntrySet.get(2).getSection());
        assertEquals(pkg1Section, mEntrySet.get(7).getSection());

        // THEN the correct section is assigned for entries in pkg2Section
        assertEquals(pkg2Section, mEntrySet.get(1).getSection());
        assertEquals(pkg2Section, mEntrySet.get(8).getSection());
        assertEquals(pkg2Section, mBuiltList.get(3).getSection());

        // THEN no section was assigned to entries in pkg4Section (since they were filtered)
        assertNull(mEntrySet.get(0).getSection());
        assertNull(mEntrySet.get(10).getSection());

        // THEN the correct section is assigned for entries in pkg5Section
        assertEquals(pkg5Section, mEntrySet.get(9).getSection());

        // THEN the children entries are assigned the same section as its parent
        assertEquals(mBuiltList.get(3).getSection(), child(5).entry.getSection());
        assertEquals(mBuiltList.get(3).getSection(), child(6).entry.getSection());
    }

    @Test
    public void testNotifUsesDefaultSection() {
        // GIVEN a Section for Package2
        final NotifSectioner pkg2Section = spy(new PackageSectioner(PACKAGE_2));
        mListBuilder.setSectioners(singletonList(pkg2Section));

        // WHEN we build a list with pkg1 and pkg2 packages
        addNotif(0, PACKAGE_1);
        addNotif(1, PACKAGE_2);
        dispatchBuild();

        // THEN the list is sorted according to section
        verifyBuiltList(
                notif(1),
                notif(0)
        );

        // THEN the entry that didn't have an explicit section gets assigned the DefaultSection
        assertNotNull(notif(0).entry.getSection());
        assertEquals(1, notif(0).entry.getSectionIndex());
    }

    @Test
    public void testThatNotifComparatorsAreCalled() {
        // GIVEN a set of comparators that care about specific packages
        mListBuilder.setComparators(asList(
                new HypeComparator(PACKAGE_4),
                new HypeComparator(PACKAGE_1, PACKAGE_3),
                new HypeComparator(PACKAGE_2)
        ));

        // WHEN the pipeline is kicked off on a bunch of notifications
        addNotif(0, PACKAGE_1);
        addNotif(1, PACKAGE_5);
        addNotif(2, PACKAGE_3);
        addNotif(3, PACKAGE_4);
        addNotif(4, PACKAGE_2);
        dispatchBuild();

        // THEN the notifs are sorted according to the hierarchy of comparators
        verifyBuiltList(
                notif(3),
                notif(0),
                notif(2),
                notif(4),
                notif(1)
        );
    }

    @Test
    public void testThatSectionComparatorsAreCalled() {
        // GIVEN a section with a comparator that elevates some packages over others
        NotifComparator comparator = spy(new HypeComparator(PACKAGE_2, PACKAGE_4));
        NotifSectioner sectioner = new PackageSectioner(
                List.of(PACKAGE_1, PACKAGE_2, PACKAGE_4, PACKAGE_5), comparator);
        mListBuilder.setSectioners(List.of(sectioner));

        // WHEN the pipeline is kicked off on a bunch of notifications
        addNotif(0, PACKAGE_0);
        addNotif(1, PACKAGE_1);
        addNotif(2, PACKAGE_2);
        addNotif(3, PACKAGE_3);
        addNotif(4, PACKAGE_4);
        addNotif(5, PACKAGE_5);
        dispatchBuild();

        // THEN the notifs are sorted according to both sectioning and the section's comparator
        verifyBuiltList(
                notif(2),
                notif(4),
                notif(1),
                notif(5),
                notif(0),
                notif(3)
        );

        // VERIFY that the comparator is invoked at least 3 times
        verify(comparator, atLeast(3)).compare(any(), any());

        // VERIFY that the comparator is never invoked with the entry from package 0 or 3.
        final NotificationEntry package0Entry = mEntrySet.get(0);
        verify(comparator, never()).compare(eq(package0Entry), any());
        verify(comparator, never()).compare(any(), eq(package0Entry));
        final NotificationEntry package3Entry = mEntrySet.get(3);
        verify(comparator, never()).compare(eq(package3Entry), any());
        verify(comparator, never()).compare(any(), eq(package3Entry));
    }

    @Test
    public void testThatSectionComparatorsAreNotCalledForSectionWithSingleEntry() {
        // GIVEN a section with a comparator that will have only 1 element
        NotifComparator comparator = spy(new HypeComparator(PACKAGE_3));
        NotifSectioner sectioner = new PackageSectioner(List.of(PACKAGE_3), comparator);
        mListBuilder.setSectioners(List.of(sectioner));

        // WHEN the pipeline is kicked off on a bunch of notifications
        addNotif(0, PACKAGE_1);
        addNotif(1, PACKAGE_2);
        addNotif(2, PACKAGE_3);
        addNotif(3, PACKAGE_4);
        addNotif(4, PACKAGE_5);
        dispatchBuild();

        // THEN the notifs are sorted according to the sectioning
        verifyBuiltList(
                notif(2),
                notif(0),
                notif(1),
                notif(3),
                notif(4)
        );

        // VERIFY that the comparator is never invoked
        verify(comparator, never()).compare(any(), any());
    }

    @Test
    public void testListenersAndPluggablesAreFiredInOrder() {
        // GIVEN a bunch of registered listeners and pluggables
        NotifFilter preGroupFilter = spy(new PackageFilter(PACKAGE_1));
        NotifPromoter promoter = spy(new IdPromoter(3));
        NotifSectioner section = spy(new PackageSectioner(PACKAGE_1));
        NotifComparator comparator = spy(new HypeComparator(PACKAGE_4));
        NotifFilter preRenderFilter = spy(new PackageFilter(PACKAGE_5));
        mListBuilder.addPreGroupFilter(preGroupFilter);
        mListBuilder.addOnBeforeTransformGroupsListener(mOnBeforeTransformGroupsListener);
        mListBuilder.addPromoter(promoter);
        mListBuilder.addOnBeforeSortListener(mOnBeforeSortListener);
        mListBuilder.setComparators(singletonList(comparator));
        mListBuilder.setSectioners(singletonList(section));
        mListBuilder.addOnBeforeFinalizeFilterListener(mOnBeforeFinalizeFilterListener);
        mListBuilder.addFinalizeFilter(preRenderFilter);
        mListBuilder.addOnBeforeRenderListListener(mOnBeforeRenderListListener);

        // WHEN a few new notifs are added
        addNotif(0, PACKAGE_1);
        addGroupSummary(1, PACKAGE_2, GROUP_1);
        addGroupChild(2, PACKAGE_2, GROUP_1);
        addGroupChild(3, PACKAGE_2, GROUP_1);
        addNotif(4, PACKAGE_5);
        addNotif(5, PACKAGE_5);
        addNotif(6, PACKAGE_4);
        dispatchBuild();

        // THEN the pluggables and listeners are called in order
        InOrder inOrder = inOrder(
                preGroupFilter,
                mOnBeforeTransformGroupsListener,
                promoter,
                mOnBeforeSortListener,
                section,
                comparator,
                mOnBeforeFinalizeFilterListener,
                preRenderFilter,
                mOnBeforeRenderListListener,
                mOnRenderListListener);

        inOrder.verify(preGroupFilter, atLeastOnce())
                .shouldFilterOut(any(NotificationEntry.class), anyLong());
        inOrder.verify(mOnBeforeTransformGroupsListener)
                .onBeforeTransformGroups(anyList());
        inOrder.verify(promoter, atLeastOnce())
                .shouldPromoteToTopLevel(any(NotificationEntry.class));
        inOrder.verify(mOnBeforeSortListener).onBeforeSort(anyList());
        inOrder.verify(section, atLeastOnce()).isInSection(any(ListEntry.class));
        inOrder.verify(comparator, atLeastOnce())
                .compare(any(ListEntry.class), any(ListEntry.class));
        inOrder.verify(mOnBeforeFinalizeFilterListener).onBeforeFinalizeFilter(anyList());
        inOrder.verify(preRenderFilter, atLeastOnce())
                .shouldFilterOut(any(NotificationEntry.class), anyLong());
        inOrder.verify(mOnBeforeRenderListListener).onBeforeRenderList(anyList());
        inOrder.verify(mOnRenderListListener).onRenderList(anyList());
    }

    @Test
    public void testThatPluggableInvalidationsTriggersRerun() {
        // GIVEN a variety of pluggables
        NotifFilter packageFilter = new PackageFilter(PACKAGE_1);
        NotifPromoter idPromoter = new IdPromoter(4);
        NotifComparator sectionComparator = new HypeComparator(PACKAGE_1);
        NotifSectioner section = new PackageSectioner(List.of(PACKAGE_1), sectionComparator);
        NotifComparator hypeComparator = new HypeComparator(PACKAGE_2);
        Invalidator preRenderInvalidator = new Invalidator("PreRenderInvalidator") {};

        mListBuilder.addPreGroupFilter(packageFilter);
        mListBuilder.addPromoter(idPromoter);
        mListBuilder.setSectioners(singletonList(section));
        mListBuilder.setComparators(singletonList(hypeComparator));
        mListBuilder.addPreRenderInvalidator(preRenderInvalidator);

        // GIVEN a set of random notifs
        addNotif(0, PACKAGE_1);
        addNotif(1, PACKAGE_2);
        addNotif(2, PACKAGE_3);
        dispatchBuild();

        // WHEN each pluggable is invalidated THEN the list is re-rendered

        clearInvocations(mOnRenderListListener);
        packageFilter.invalidateList(null);
        assertTrue(mPipelineChoreographer.isScheduled());
        mPipelineChoreographer.runIfScheduled();
        verify(mOnRenderListListener).onRenderList(anyList());

        clearInvocations(mOnRenderListListener);
        idPromoter.invalidateList(null);
        assertTrue(mPipelineChoreographer.isScheduled());
        mPipelineChoreographer.runIfScheduled();
        verify(mOnRenderListListener).onRenderList(anyList());

        clearInvocations(mOnRenderListListener);
        section.invalidateList(null);
        assertTrue(mPipelineChoreographer.isScheduled());
        mPipelineChoreographer.runIfScheduled();
        verify(mOnRenderListListener).onRenderList(anyList());

        clearInvocations(mOnRenderListListener);
        hypeComparator.invalidateList(null);
        assertTrue(mPipelineChoreographer.isScheduled());
        mPipelineChoreographer.runIfScheduled();
        verify(mOnRenderListListener).onRenderList(anyList());

        clearInvocations(mOnRenderListListener);
        sectionComparator.invalidateList(null);
        assertTrue(mPipelineChoreographer.isScheduled());
        mPipelineChoreographer.runIfScheduled();
        verify(mOnRenderListListener).onRenderList(anyList());

        clearInvocations(mOnRenderListListener);
        preRenderInvalidator.invalidateList(null);
        assertTrue(mPipelineChoreographer.isScheduled());
        mPipelineChoreographer.runIfScheduled();
        verify(mOnRenderListListener).onRenderList(anyList());
    }

    @Test
    public void testNotifFiltersAreAllSentTheSameNow() {
        // GIVEN three notif filters
        NotifFilter filter1 = spy(new PackageFilter(PACKAGE_5));
        NotifFilter filter2 = spy(new PackageFilter(PACKAGE_5));
        NotifFilter filter3 = spy(new PackageFilter(PACKAGE_5));
        mListBuilder.addPreGroupFilter(filter1);
        mListBuilder.addPreGroupFilter(filter2);
        mListBuilder.addPreGroupFilter(filter3);

        // GIVEN the SystemClock is set to a particular time:
        mSystemClock.setUptimeMillis(10047);

        // WHEN the pipeline is kicked off on a list of notifs
        addNotif(0, PACKAGE_1);
        addNotif(1, PACKAGE_2);
        dispatchBuild();

        // THEN the value of `now` is the same for all calls to shouldFilterOut
        verify(filter1).shouldFilterOut(mEntrySet.get(0), 10047);
        verify(filter2).shouldFilterOut(mEntrySet.get(0), 10047);
        verify(filter3).shouldFilterOut(mEntrySet.get(0), 10047);
        verify(filter1).shouldFilterOut(mEntrySet.get(1), 10047);
        verify(filter2).shouldFilterOut(mEntrySet.get(1), 10047);
        verify(filter3).shouldFilterOut(mEntrySet.get(1), 10047);
    }

    @Test
    public void testGroupTransformEntries() {
        // GIVEN a registered OnBeforeTransformGroupsListener
        RecordingOnBeforeTransformGroupsListener listener =
                new RecordingOnBeforeTransformGroupsListener();
        mListBuilder.addOnBeforeTransformGroupsListener(listener);

        // GIVEN some new notifs
        addNotif(0, PACKAGE_1);
        addGroupChild(1, PACKAGE_2, GROUP_1);
        addGroupSummary(2, PACKAGE_2, GROUP_1);
        addGroupChild(3, PACKAGE_2, GROUP_1);
        addNotif(4, PACKAGE_3);
        addGroupChild(5, PACKAGE_2, GROUP_1);

        // WHEN we run the pipeline
        dispatchBuild();

        verifyBuiltList(
                notif(0),
                group(
                        summary(2),
                        child(1),
                        child(3),
                        child(5)
                ),
                notif(4)
        );

        // THEN all the new notifs, including the new GroupEntry, are passed to the listener
        assertThat(listener.mEntriesReceived).containsExactly(
                mEntrySet.get(0),
                mBuiltList.get(1),
                mEntrySet.get(4)
        ).inOrder(); // Order is a bonus because this listener is before sort
    }

    @Test
    public void testGroupTransformEntriesOnSecondRun() {
        // GIVEN a registered OnBeforeTransformGroupsListener
        RecordingOnBeforeTransformGroupsListener listener =
                spy(new RecordingOnBeforeTransformGroupsListener());
        mListBuilder.addOnBeforeTransformGroupsListener(listener);

        // GIVEN some notifs that have already been added (two of which are in malformed groups)
        addNotif(0, PACKAGE_1);
        addGroupChild(1, PACKAGE_2, GROUP_1);
        addGroupChild(2, PACKAGE_3, GROUP_2);

        dispatchBuild();
        clearInvocations(listener);

        // WHEN we run the pipeline
        addGroupSummary(3, PACKAGE_2, GROUP_1);
        addGroupChild(4, PACKAGE_3, GROUP_2);
        addGroupSummary(5, PACKAGE_3, GROUP_2);
        addGroupChild(6, PACKAGE_3, GROUP_2);
        addNotif(7, PACKAGE_2);

        dispatchBuild();

        verifyBuiltList(
                notif(0),
                notif(1),
                group(
                        summary(5),
                        child(2),
                        child(4),
                        child(6)
                ),
                notif(7)
        );

        // THEN all the new notifs, including the new GroupEntry, are passed to the listener
        assertThat(listener.mEntriesReceived).containsExactly(
                mEntrySet.get(0),
                mEntrySet.get(1),
                mBuiltList.get(2),
                mEntrySet.get(7)
        ).inOrder(); // Order is a bonus because this listener is before sort
    }

    @Test
    public void testStabilizeGroupsAlwaysAllowsGroupChangeFromDeletedGroupToRoot() {
        // GIVEN a group w/ summary and two children
        addGroupSummary(0, PACKAGE_1, GROUP_1);
        addGroupChild(1, PACKAGE_1, GROUP_1);
        addGroupChild(2, PACKAGE_1, GROUP_1);
        dispatchBuild();

        // GIVEN visual stability manager doesn't allow any group changes
        mStabilityManager.setAllowGroupChanges(false);

        // WHEN we run the pipeline with the summary and one child removed
        mEntrySet.remove(2);
        mEntrySet.remove(0);
        dispatchBuild();

        // THEN all that remains is the one child at top-level, despite no group change allowed by
        // visual stability manager.
        verifyBuiltList(
                notif(0)
        );
    }

    @Test
    public void testStabilizeGroupsDoesNotAllowGroupingExistingNotifications() {
        // GIVEN one group child without a summary yet
        addGroupChild(0, PACKAGE_1, GROUP_1);

        dispatchBuild();

        // GIVEN visual stability manager doesn't allow any group changes
        mStabilityManager.setAllowGroupChanges(false);

        // WHEN we run the pipeline with the addition of a group summary & child
        addGroupSummary(1, PACKAGE_1, GROUP_1);
        addGroupChild(2, PACKAGE_1, GROUP_1);

        dispatchBuild();

        // THEN all notifications are top-level and the summary doesn't show yet
        // because group changes aren't allowed by the stability manager
        verifyBuiltList(
                notif(0),
                group(
                        summary(1),
                        child(2)
                )
        );
    }

    @Test
    public void testStabilizeGroupsAllowsGroupingAllNewNotifications() {
        // GIVEN visual stability manager doesn't allow any group changes
        mStabilityManager.setAllowGroupChanges(false);

        // WHEN we run the pipeline with all new notification groups
        addGroupChild(0, PACKAGE_1, GROUP_1);
        addGroupSummary(1, PACKAGE_1, GROUP_1);
        addGroupChild(2, PACKAGE_1, GROUP_1);
        addGroupSummary(3, PACKAGE_2, GROUP_2);
        addGroupChild(4, PACKAGE_2, GROUP_2);
        addGroupChild(5, PACKAGE_2, GROUP_2);

        dispatchBuild();

        // THEN all notifications are grouped since they're all new
        verifyBuiltList(
                group(
                        summary(1),
                        child(0),
                        child(2)
                ),
                group(
                        summary(3),
                        child(4),
                        child(5)
                )
        );
    }


    @Test
    public void testStabilizeGroupsAllowsGroupingOnlyNewNotifications() {
        // GIVEN one group child without a summary yet
        addGroupChild(0, PACKAGE_1, GROUP_1);

        dispatchBuild();

        // GIVEN visual stability manager doesn't allow any group changes
        mStabilityManager.setAllowGroupChanges(false);

        // WHEN we run the pipeline with the addition of a group summary & child
        addGroupSummary(1, PACKAGE_1, GROUP_1);
        addGroupChild(2, PACKAGE_1, GROUP_1);
        addGroupSummary(3, PACKAGE_2, GROUP_2);
        addGroupChild(4, PACKAGE_2, GROUP_2);
        addGroupChild(5, PACKAGE_2, GROUP_2);

        dispatchBuild();

        // THEN first notification stays top-level but the other notifications are grouped.
        verifyBuiltList(
                notif(0),
                group(
                        summary(1),
                        child(2)
                ),
                group(
                        summary(3),
                        child(4),
                        child(5)
                )
        );
    }

    @Test
    public void testFinalizeFilteringGroupSummaryDoesNotBreakSort() {
        // GIVEN children from 3 packages, with one in the middle of the sort order being a group
        addNotif(0, PACKAGE_1);
        addNotif(1, PACKAGE_2);
        addNotif(2, PACKAGE_3);
        addNotif(3, PACKAGE_1);
        addNotif(4, PACKAGE_2);
        addNotif(5, PACKAGE_3);
        addGroupSummary(6, PACKAGE_2, GROUP_1);
        addGroupChild(7, PACKAGE_2, GROUP_1);
        addGroupChild(8, PACKAGE_2, GROUP_1);

        // GIVEN that they should be sorted by package
        mListBuilder.setComparators(asList(
                new HypeComparator(PACKAGE_1),
                new HypeComparator(PACKAGE_2),
                new HypeComparator(PACKAGE_3)
        ));

        // WHEN a finalize filter removes the summary
        mListBuilder.addFinalizeFilter(new NotifFilter("Test") {
            @Override
            public boolean shouldFilterOut(@NonNull NotificationEntry entry, long now) {
                return entry == notif(6).entry;
            }
        });

        dispatchBuild();

        // THEN the notifications remain ordered by package, even though the children were promoted
        verifyBuiltList(
                notif(0),
                notif(3),
                notif(1),
                notif(4),
                notif(7),  // promoted child
                notif(8),  // promoted child
                notif(2),
                notif(5)
        );
    }

    @Test
    public void testFinalizeFilteringGroupChildDoesNotBreakSort() {
        // GIVEN children from 3 packages, with one in the middle of the sort order being a group
        addNotif(0, PACKAGE_1);
        addNotif(1, PACKAGE_2);
        addNotif(2, PACKAGE_3);
        addNotif(3, PACKAGE_1);
        addNotif(4, PACKAGE_2);
        addNotif(5, PACKAGE_3);
        addGroupSummary(6, PACKAGE_2, GROUP_1);
        addGroupChild(7, PACKAGE_2, GROUP_1);
        addGroupChild(8, PACKAGE_2, GROUP_1);

        // GIVEN that they should be sorted by package
        mListBuilder.setComparators(asList(
                new HypeComparator(PACKAGE_1),
                new HypeComparator(PACKAGE_2),
                new HypeComparator(PACKAGE_3)
        ));

        // WHEN a finalize filter one of the 2 children from a group
        mListBuilder.addFinalizeFilter(new NotifFilter("Test") {
            @Override
            public boolean shouldFilterOut(@NonNull NotificationEntry entry, long now) {
                return entry == notif(7).entry;
            }
        });

        dispatchBuild();

        // THEN the notifications remain ordered by package, even though the children were promoted
        verifyBuiltList(
                notif(0),
                notif(3),
                notif(1),
                notif(4),
                notif(8),  // promoted child
                notif(2),
                notif(5)
        );
    }

    @Test
    public void testStabilityIsolationAllowsGroupToHaveSingleChild() {
        // GIVEN a group with only one child was already drawn
        addGroupSummary(0, PACKAGE_1, GROUP_1);
        addGroupChild(1, PACKAGE_1, GROUP_1);

        dispatchBuild();
        // NOTICE that the group is pruned and the child is moved to the top level
        verifyBuiltList(
                notif(1)  // group with only one child is promoted
        );

        // WHEN another child is added while group changes are disabled.
        mStabilityManager.setAllowGroupChanges(false);
        addGroupChild(2, PACKAGE_1, GROUP_1);

        dispatchBuild();

        // THEN the new child should be added to the group
        verifyBuiltList(
                group(
                        summary(0),
                        child(2)
                ),
                notif(1)
        );
    }

    @Test
    public void testStabilityIsolationExemptsGroupWithFinalizeFilteredChildFromShowingSummary() {
        // GIVEN a group with only one child was already drawn
        addGroupSummary(0, PACKAGE_1, GROUP_1);
        addGroupChild(1, PACKAGE_1, GROUP_1);

        dispatchBuild();
        // NOTICE that the group is pruned and the child is moved to the top level
        verifyBuiltList(
                notif(1)  // group with only one child is promoted
        );

        // WHEN another child is added but still filtered while group changes are disabled.
        mStabilityManager.setAllowGroupChanges(false);
        mFinalizeFilter.mIndicesToFilter.add(2);
        addGroupChild(2, PACKAGE_1, GROUP_1);

        dispatchBuild();

        // THEN the new child should be shown without the summary
        verifyBuiltList(
                notif(1)  // previously promoted child
        );
    }

    @Test
    public void testStabilityIsolationOfRemovedChildDoesNotExemptGroupFromPrune() {
        // GIVEN a group with only one child was already drawn
        addGroupSummary(0, PACKAGE_1, GROUP_1);
        addGroupChild(1, PACKAGE_1, GROUP_1);

        dispatchBuild();
        // NOTICE that the group is pruned and the child is moved to the top level
        verifyBuiltList(
                notif(1)  // group with only one child is promoted
        );

        // WHEN a new child is added and the old one gets filtered while group changes are disabled.
        mStabilityManager.setAllowGroupChanges(false);
        mStabilityManager.setAllowGroupPruning(false);
        mFinalizeFilter.mIndicesToFilter.add(1);
        addGroupChild(2, PACKAGE_1, GROUP_1);

        dispatchBuild();

        // THEN the new child should be shown without a group
        // (Note that this is the same as the expected result if there were no stability rules.)
        verifyBuiltList(
                notif(2)  // new child
        );
    }

    @Test
    public void testGroupWithChildRemovedByFilterIsPrunedWhenOtherwiseEmpty() {
        // GIVEN a group with only one child
        addGroupSummary(0, PACKAGE_1, GROUP_1);
        addGroupChild(1, PACKAGE_1, GROUP_1);
        dispatchBuild();
        // NOTICE that the group is pruned and the child is moved to the top level
        verifyBuiltList(
                notif(1)  // group with only one child is promoted
        );

        // WHEN the only child is filtered
        mFinalizeFilter.mIndicesToFilter.add(1);
        dispatchBuild();

        // THEN the new list should be empty (the group summary should not be promoted)
        verifyBuiltList();
    }

    @Test
    public void testFinalizeFilteredSummaryPromotesChildren() {
        // GIVEN a group with only one child was already drawn
        addGroupSummary(0, PACKAGE_1, GROUP_1);
        addGroupChild(1, PACKAGE_1, GROUP_1);
        addGroupChild(2, PACKAGE_1, GROUP_1);

        // WHEN the parent is filtered out at the finalize step
        mFinalizeFilter.mIndicesToFilter.add(0);

        dispatchBuild();

        // THEN the children should be promoted to the top level
        verifyBuiltList(
                notif(1),
                notif(2)
        );
    }

    @Test
    public void testFinalizeFilteredChildPromotesSibling() {
        // GIVEN a group with only one child was already drawn
        addGroupSummary(0, PACKAGE_1, GROUP_1);
        addGroupChild(1, PACKAGE_1, GROUP_1);
        addGroupChild(2, PACKAGE_1, GROUP_1);

        // WHEN the parent is filtered out at the finalize step
        mFinalizeFilter.mIndicesToFilter.add(1);

        dispatchBuild();

        // THEN the children should be promoted to the top level
        verifyBuiltList(
                notif(2)
        );
    }

    @Test
    public void testBrokenGroupNotificationOrdering() {
        // GIVEN two group children with different sections & without a summary yet
        addGroupChild(0, PACKAGE_2, GROUP_1);
        addNotif(1, PACKAGE_1);
        addGroupChild(2, PACKAGE_2, GROUP_1);
        addGroupChild(3, PACKAGE_2, GROUP_1);

        dispatchBuild();

        // THEN all notifications are not grouped and posted in order by index
        verifyBuiltList(
                notif(0),
                notif(1),
                notif(2),
                notif(3)
        );
    }

    @Test
    public void testContiguousSections() {
        mListBuilder.setSectioners(List.of(
                new PackageSectioner("pkg", 1),
                new PackageSectioner("pkg", 1),
                new PackageSectioner("pkg", 3),
                new PackageSectioner("pkg", 2)
        ));
    }

    @Test(expected = IllegalStateException.class)
    public void testNonContiguousSections() {
        mListBuilder.setSectioners(List.of(
                new PackageSectioner("pkg", 1),
                new PackageSectioner("pkg", 1),
                new PackageSectioner("pkg", 3),
                new PackageSectioner("pkg", 1)
        ));
    }

    @Test(expected = IllegalStateException.class)
    public void testBucketZeroNotAllowed() {
        mListBuilder.setSectioners(List.of(
                new PackageSectioner("pkg", 0),
                new PackageSectioner("pkg", 1)
        ));
    }

    @Test
    public void testStabilizeGroupsDelayedSummaryRendersAllNotifsTopLevel() {
        // GIVEN group children posted without a summary
        addGroupChild(0, PACKAGE_1, GROUP_1);
        addGroupChild(1, PACKAGE_1, GROUP_1);
        addGroupChild(2, PACKAGE_1, GROUP_1);
        addGroupChild(3, PACKAGE_1, GROUP_1);

        dispatchBuild();

        // GIVEN visual stability manager doesn't allow any group changes
        mStabilityManager.setAllowGroupChanges(false);

        // WHEN the delayed summary is posted
        addGroupSummary(4, PACKAGE_1, GROUP_1);

        dispatchBuild();

        // THEN all entries are top-level, but summary is suppressed
        verifyBuiltList(
                notif(0),
                notif(1),
                notif(2),
                notif(3)
        );

        // WHEN visual stability manager allows group changes again
        mStabilityManager.setAllowGroupChanges(true);
        mStabilityManager.invalidateList(null);
        mPipelineChoreographer.runIfScheduled();

        // THEN entries are grouped
        verifyBuiltList(
                group(
                        summary(4),
                        child(0),
                        child(1),
                        child(2),
                        child(3)
                )
        );
    }

    @Test
    public void testStabilizeSectionDisallowsNewSection() {
        // GIVEN one non-default sections
        final NotifSectioner originalSectioner = new PackageSectioner(PACKAGE_1);
        mListBuilder.setSectioners(List.of(originalSectioner));

        // GIVEN notifications that's sectioned by sectioner1
        addNotif(0, PACKAGE_1);
        dispatchBuild();
        assertEquals(originalSectioner, mEntrySet.get(0).getSection().getSectioner());

        // WHEN section changes aren't allowed
        mStabilityManager.setAllowSectionChanges(false);

        // WHEN we try to change the section
        final NotifSectioner newSectioner = new PackageSectioner(PACKAGE_1);
        mListBuilder.setSectioners(List.of(newSectioner, originalSectioner));
        dispatchBuild();

        // THEN the section remains the same since section changes aren't allowed
        assertEquals(originalSectioner, mEntrySet.get(0).getSection().getSectioner());

        // WHEN section changes are allowed again
        mStabilityManager.setAllowSectionChanges(true);
        mStabilityManager.invalidateList(null);
        mPipelineChoreographer.runIfScheduled();

        // THEN the section updates
        assertEquals(newSectioner, mEntrySet.get(0).getSection().getSectioner());
    }

    @Test
    public void testDispatchListOnBeforeSort() {
        // GIVEN a registered OnBeforeSortListener
        RecordingOnBeforeSortListener listener =
                new RecordingOnBeforeSortListener();
        mListBuilder.addOnBeforeSortListener(listener);
        mListBuilder.setComparators(singletonList(new HypeComparator(PACKAGE_3)));

        // GIVEN some new notifs out of order
        addNotif(0, PACKAGE_1);
        addNotif(1, PACKAGE_2);
        addNotif(2, PACKAGE_3);

        // WHEN we run the pipeline
        dispatchBuild();

        // THEN all the new notifs are passed to the listener out of order
        assertThat(listener.mEntriesReceived).containsExactly(
                mEntrySet.get(0),
                mEntrySet.get(1),
                mEntrySet.get(2)
        ).inOrder();  // Checking out-of-order input to validate sorted output

        // THEN the final list is in order
        verifyBuiltList(
                notif(2),
                notif(0),
                notif(1)
        );
    }

    @Test
    public void testDispatchListOnBeforeRender() {
        // GIVEN a registered OnBeforeRenderList
        RecordingOnBeforeRenderListener listener =
                new RecordingOnBeforeRenderListener();
        mListBuilder.addOnBeforeRenderListListener(listener);

        // GIVEN some new notifs out of order
        addNotif(0, PACKAGE_1);
        addNotif(1, PACKAGE_2);
        addNotif(2, PACKAGE_3);

        // WHEN we run the pipeline
        dispatchBuild();

        // THEN all the new notifs are passed to the listener
        assertThat(listener.mEntriesReceived).containsExactly(
                mEntrySet.get(0),
                mEntrySet.get(1),
                mEntrySet.get(2)
        ).inOrder();
    }

    @Test
    public void testAnnulledGroupsHaveParentSetProperly() {
        // GIVEN a list containing a small group that's already been built once
        addGroupChild(0, PACKAGE_2, GROUP_2);
        addGroupSummary(1, PACKAGE_2, GROUP_2);
        addGroupChild(2, PACKAGE_2, GROUP_2);
        dispatchBuild();

        verifyBuiltList(
                group(
                        summary(1),
                        child(0),
                        child(2)
                )
        );
        GroupEntry group = (GroupEntry) mBuiltList.get(0);

        // WHEN a child is removed such that the group is no longer big enough
        mEntrySet.remove(2);
        dispatchBuild();

        // THEN the group is annulled and its parent is set back to null
        verifyBuiltList(
                notif(0)
        );
        assertNull(group.getParent());

        // but its previous parent indicates that it was added in the previous iteration
        assertEquals(GroupEntry.ROOT_ENTRY, group.getPreviousParent());
    }

    static class CountingInvalidator {
        CountingInvalidator(Pluggable pluggableToInvalidate) {
            mPluggableToInvalidate = pluggableToInvalidate;
            mInvalidationCount = 0;
        }

        public void setInvalidationCount(int invalidationCount) {
            mInvalidationCount = invalidationCount;
        }

        public void maybeInvalidate() {
            if (mInvalidationCount > 0) {
                mPluggableToInvalidate.invalidateList("test invalidation");
                mInvalidationCount--;
            }
        }

        private Pluggable mPluggableToInvalidate;
        private int mInvalidationCount;

        private static final String TAG = "ShadeListBuilderTestCountingInvalidator";
    }

    @Test
    public void testOutOfOrderPreGroupFilterInvalidationDoesNotThrowBeforeTooManyRuns() {
        // GIVEN a PreGroupNotifFilter that gets invalidated during the grouping stage,
        NotifFilter filter = new PackageFilter(PACKAGE_1);
        CountingInvalidator invalidator = new CountingInvalidator(filter);
        OnBeforeTransformGroupsListener listener = (list) -> invalidator.maybeInvalidate();
        mListBuilder.addPreGroupFilter(filter);
        mListBuilder.addOnBeforeTransformGroupsListener(listener);

        // WHEN we try to run the pipeline and the filter is invalidated exactly
        // MAX_CONSECUTIVE_REENTRANT_REBUILDS times,
        addNotif(0, PACKAGE_2);
        invalidator.setInvalidationCount(ShadeListBuilder.MAX_CONSECUTIVE_REENTRANT_REBUILDS);
        dispatchBuild();
        runWhileScheduledUpTo(ShadeListBuilder.MAX_CONSECUTIVE_REENTRANT_REBUILDS + 2);

        // THEN an exception is NOT thrown.
    }

    @Test(expected = IllegalStateException.class)
    public void testOutOfOrderPreGroupFilterInvalidationThrowsAfterTooManyRuns() {
        // GIVEN a PreGroupNotifFilter that gets invalidated during the grouping stage,
        NotifFilter filter = new PackageFilter(PACKAGE_1);
        CountingInvalidator invalidator = new CountingInvalidator(filter);
        OnBeforeTransformGroupsListener listener = (list) -> invalidator.maybeInvalidate();
        mListBuilder.addPreGroupFilter(filter);
        mListBuilder.addOnBeforeTransformGroupsListener(listener);

        // WHEN we try to run the pipeline and the filter is invalidated more than
        // MAX_CONSECUTIVE_REENTRANT_REBUILDS times,
        addNotif(0, PACKAGE_2);
        invalidator.setInvalidationCount(ShadeListBuilder.MAX_CONSECUTIVE_REENTRANT_REBUILDS + 1);
        dispatchBuild();
        runWhileScheduledUpTo(ShadeListBuilder.MAX_CONSECUTIVE_REENTRANT_REBUILDS + 2);

        // THEN an exception IS thrown.
    }

    @Test
    public void testNonConsecutiveOutOfOrderInvalidationDontThrowAfterTooManyRuns() {
        // GIVEN a PreGroupNotifFilter that gets invalidated during the grouping stage,
        NotifFilter filter = new PackageFilter(PACKAGE_1);
        CountingInvalidator invalidator = new CountingInvalidator(filter);
        OnBeforeTransformGroupsListener listener = (list) -> invalidator.maybeInvalidate();
        mListBuilder.addPreGroupFilter(filter);
        mListBuilder.addOnBeforeTransformGroupsListener(listener);

        // WHEN we try to run the pipeline and the filter is invalidated at least
        // MAX_CONSECUTIVE_REENTRANT_REBUILDS times,
        addNotif(0, PACKAGE_2);
        invalidator.setInvalidationCount(ShadeListBuilder.MAX_CONSECUTIVE_REENTRANT_REBUILDS);
        dispatchBuild();
        runWhileScheduledUpTo(ShadeListBuilder.MAX_CONSECUTIVE_REENTRANT_REBUILDS + 2);
        invalidator.setInvalidationCount(ShadeListBuilder.MAX_CONSECUTIVE_REENTRANT_REBUILDS);
        dispatchBuild();
        runWhileScheduledUpTo(ShadeListBuilder.MAX_CONSECUTIVE_REENTRANT_REBUILDS + 2);

        // THEN an exception is NOT thrown.
    }

    @Test
    public void testOutOfOrderPrompterInvalidationDoesNotThrowBeforeTooManyRuns() {
        // GIVEN a NotifPromoter that gets invalidated during the sorting stage,
        NotifPromoter promoter = new IdPromoter(47);
        CountingInvalidator invalidator = new CountingInvalidator(promoter);
        OnBeforeSortListener listener = (list) -> invalidator.maybeInvalidate();
        mListBuilder.addPromoter(promoter);
        mListBuilder.addOnBeforeSortListener(listener);

        // WHEN we try to run the pipeline and the promoter is invalidated exactly
        // MAX_CONSECUTIVE_REENTRANT_REBUILDS times,
        addNotif(0, PACKAGE_1);
        invalidator.setInvalidationCount(ShadeListBuilder.MAX_CONSECUTIVE_REENTRANT_REBUILDS);
        dispatchBuild();
        runWhileScheduledUpTo(ShadeListBuilder.MAX_CONSECUTIVE_REENTRANT_REBUILDS + 2);

        // THEN an exception is NOT thrown.
    }

    @Test(expected = IllegalStateException.class)
    public void testOutOfOrderPrompterInvalidationThrowsAfterTooManyRuns() {
        // GIVEN a NotifPromoter that gets invalidated during the sorting stage,
        NotifPromoter promoter = new IdPromoter(47);
        CountingInvalidator invalidator = new CountingInvalidator(promoter);
        OnBeforeSortListener listener = (list) -> invalidator.maybeInvalidate();
        mListBuilder.addPromoter(promoter);
        mListBuilder.addOnBeforeSortListener(listener);

        // WHEN we try to run the pipeline and the promoter is invalidated more than
        // MAX_CONSECUTIVE_REENTRANT_REBUILDS times,
        addNotif(0, PACKAGE_1);
        invalidator.setInvalidationCount(ShadeListBuilder.MAX_CONSECUTIVE_REENTRANT_REBUILDS + 1);
        dispatchBuild();
        runWhileScheduledUpTo(ShadeListBuilder.MAX_CONSECUTIVE_REENTRANT_REBUILDS + 2);

        // THEN an exception IS thrown.
    }

    @Test
    public void testOutOfOrderComparatorInvalidationDoesNotThrowBeforeTooManyRuns() {
        // GIVEN a NotifComparator that gets invalidated during the finalizing stage,
        NotifComparator comparator = new HypeComparator(PACKAGE_1);
        CountingInvalidator invalidator = new CountingInvalidator(comparator);
        OnBeforeRenderListListener listener = (list) -> invalidator.maybeInvalidate();
        mListBuilder.setComparators(singletonList(comparator));
        mListBuilder.addOnBeforeRenderListListener(listener);

        // WHEN we try to run the pipeline and the comparator is invalidated exactly
        // MAX_CONSECUTIVE_REENTRANT_REBUILDS times,
        addNotif(0, PACKAGE_2);
        invalidator.setInvalidationCount(ShadeListBuilder.MAX_CONSECUTIVE_REENTRANT_REBUILDS);
        dispatchBuild();
        runWhileScheduledUpTo(ShadeListBuilder.MAX_CONSECUTIVE_REENTRANT_REBUILDS + 2);

        // THEN an exception is NOT thrown.
    }

    @Test(expected = IllegalStateException.class)
    public void testOutOfOrderComparatorInvalidationThrowsAfterTooManyRuns() {
        // GIVEN a NotifComparator that gets invalidated during the finalizing stage,
        NotifComparator comparator = new HypeComparator(PACKAGE_1);
        CountingInvalidator invalidator = new CountingInvalidator(comparator);
        OnBeforeRenderListListener listener = (list) -> invalidator.maybeInvalidate();
        mListBuilder.setComparators(singletonList(comparator));
        mListBuilder.addOnBeforeRenderListListener(listener);

        // WHEN we try to run the pipeline and the comparator is invalidated more than
        // MAX_CONSECUTIVE_REENTRANT_REBUILDS times,
        addNotif(0, PACKAGE_2);
        invalidator.setInvalidationCount(ShadeListBuilder.MAX_CONSECUTIVE_REENTRANT_REBUILDS + 1);
        dispatchBuild();
        runWhileScheduledUpTo(ShadeListBuilder.MAX_CONSECUTIVE_REENTRANT_REBUILDS + 2);

        // THEN an exception IS thrown.
    }

    @Test
    public void testOutOfOrderPreRenderFilterInvalidationDoesNotThrowBeforeTooManyRuns() {
        // GIVEN a PreRenderNotifFilter that gets invalidated during the finalizing stage,
        NotifFilter filter = new PackageFilter(PACKAGE_1);
        CountingInvalidator invalidator = new CountingInvalidator(filter);
        OnBeforeRenderListListener listener = (list) -> invalidator.maybeInvalidate();
        mListBuilder.addFinalizeFilter(filter);
        mListBuilder.addOnBeforeRenderListListener(listener);

        // WHEN we try to run the pipeline and the PreRenderFilter is invalidated exactly
        // MAX_CONSECUTIVE_REENTRANT_REBUILDS times,
        addNotif(0, PACKAGE_2);
        invalidator.setInvalidationCount(ShadeListBuilder.MAX_CONSECUTIVE_REENTRANT_REBUILDS);
        dispatchBuild();
        runWhileScheduledUpTo(ShadeListBuilder.MAX_CONSECUTIVE_REENTRANT_REBUILDS + 2);

        // THEN an exception is NOT thrown.
    }

    @Test(expected = IllegalStateException.class)
    public void testOutOfOrderPreRenderFilterInvalidationThrowsAfterTooManyRuns() {
        // GIVEN a PreRenderNotifFilter that gets invalidated during the finalizing stage,
        NotifFilter filter = new PackageFilter(PACKAGE_1);
        CountingInvalidator invalidator = new CountingInvalidator(filter);
        OnBeforeRenderListListener listener = (list) -> invalidator.maybeInvalidate();
        mListBuilder.addFinalizeFilter(filter);
        mListBuilder.addOnBeforeRenderListListener(listener);

        // WHEN we try to run the pipeline and the PreRenderFilter is invalidated more than
        // MAX_CONSECUTIVE_REENTRANT_REBUILDS times,
        addNotif(0, PACKAGE_2);
        invalidator.setInvalidationCount(ShadeListBuilder.MAX_CONSECUTIVE_REENTRANT_REBUILDS + 1);
        dispatchBuild();
        runWhileScheduledUpTo(ShadeListBuilder.MAX_CONSECUTIVE_REENTRANT_REBUILDS + 2);

        // THEN an exception IS thrown.
    }

    @Test
    public void testStableOrdering() {
        mStabilityManager.setAllowEntryReordering(false);
        assertOrder("ABCDEFG", "ACDEFXBG", "XABCDEFG"); // X
        assertOrder("ABCDEFG", "ACDEFBG", "ABCDEFG"); // no change
        assertOrder("ABCDEFG", "ACDEFBXZG", "XZABCDEFG"); // Z and X
        assertOrder("ABCDEFG", "AXCDEZFBG", "XZABCDEFG"); // Z and X + gap
        verify(mStabilityManager, times(4)).onEntryReorderSuppressed();
    }

    @Test
    public void testActiveOrdering() {
        assertOrder("ABCDEFG", "ACDEFXBG", "ACDEFXBG"); // X
        assertOrder("ABCDEFG", "ACDEFBG", "ACDEFBG"); // no change
        assertOrder("ABCDEFG", "ACDEFBXZG", "ACDEFBXZG"); // Z and X
        assertOrder("ABCDEFG", "AXCDEZFBG", "AXCDEZFBG"); // Z and X + gap
        verify(mStabilityManager, never()).onEntryReorderSuppressed();
    }

    @Test
    public void testStableMultipleSectionOrdering() {
        // WHEN the list is originally built with reordering disabled
        mListBuilder.setSectioners(asList(
                new PackageSectioner(PACKAGE_1), new PackageSectioner(PACKAGE_2)));
        mStabilityManager.setAllowEntryReordering(false);

        addNotif(0, PACKAGE_1).setRank(1);
        addNotif(1, PACKAGE_1).setRank(2);
        addNotif(2, PACKAGE_2).setRank(0);
        addNotif(3, PACKAGE_1).setRank(3);
        dispatchBuild();

        // VERIFY the order and that entry reordering has not been suppressed
        verifyBuiltList(
                notif(0),
                notif(1),
                notif(3),
                notif(2)
        );
        verify(mStabilityManager, never()).onEntryReorderSuppressed();

        // WHEN the ranks change
        setNewRank(notif(0).entry, 4);
        dispatchBuild();

        // VERIFY the order does not change that entry reordering has been suppressed
        verifyBuiltList(
                notif(0),
                notif(1),
                notif(3),
                notif(2)
        );
        verify(mStabilityManager).onEntryReorderSuppressed();

        // WHEN reordering is now allowed again
        mStabilityManager.setAllowEntryReordering(true);
        dispatchBuild();

        // VERIFY that list order changes
        verifyBuiltList(
                notif(1),
                notif(3),
                notif(0),
                notif(2)
        );
    }

    @Test
    public void testStableChildOrdering() {
        // WHEN the list is originally built with reordering disabled
        mStabilityManager.setAllowEntryReordering(false);
        addGroupSummary(0, PACKAGE_1, GROUP_1).setRank(0);
        addGroupChild(1, PACKAGE_1, GROUP_1).setRank(1);
        addGroupChild(2, PACKAGE_1, GROUP_1).setRank(2);
        addGroupChild(3, PACKAGE_1, GROUP_1).setRank(3);
        dispatchBuild();

        // VERIFY the order and that entry reordering has not been suppressed
        verifyBuiltList(
                group(
                        summary(0),
                        child(1),
                        child(2),
                        child(3)
                )
        );
        verify(mStabilityManager, never()).onEntryReorderSuppressed();

        // WHEN the ranks change
        setNewRank(notif(2).entry, 5);
        dispatchBuild();

        // VERIFY the order does not change that entry reordering has been suppressed
        verifyBuiltList(
                group(
                        summary(0),
                        child(1),
                        child(2),
                        child(3)
                )
        );
        verify(mStabilityManager).onEntryReorderSuppressed();

        // WHEN reordering is now allowed again
        mStabilityManager.setAllowEntryReordering(true);
        dispatchBuild();

        // VERIFY that list order changes
        verifyBuiltList(
                group(
                        summary(0),
                        child(1),
                        child(3),
                        child(2)
                )
        );
    }

    private static void setNewRank(NotificationEntry entry, int rank) {
        entry.setRanking(new RankingBuilder(entry.getRanking()).setRank(rank).build());
    }

    @Test
    public void testInOrderPreRenderFilter() {
        // GIVEN a PreRenderFilter that gets invalidated during the grouping stage
        NotifFilter filter = new PackageFilter(PACKAGE_5);
        OnBeforeTransformGroupsListener listener = (list) -> filter.invalidateList(null);
        mListBuilder.addFinalizeFilter(filter);
        mListBuilder.addOnBeforeTransformGroupsListener(listener);

        // WHEN we try to run the pipeline and the filter is invalidated
        addNotif(0, PACKAGE_1);
        dispatchBuild();

        // THEN no exception thrown
    }

    @Test
    public void testPipelineRunDisallowedDueToVisualStability() {
        // GIVEN pipeline run not allowed due to visual stability
        mStabilityManager.setAllowPipelineRun(false);

        // WHEN we try to run the pipeline with a change
        addNotif(0, PACKAGE_1);
        dispatchBuild();

        // THEN there is no change; the pipeline did not run
        verifyBuiltList();
    }

    @Test
    public void testMultipleInvalidationsCoalesce() {
        // GIVEN a PreGroupFilter and a FinalizeFilter
        NotifFilter filter1 = new PackageFilter(PACKAGE_5);
        NotifFilter filter2 = new PackageFilter(PACKAGE_0);
        mListBuilder.addPreGroupFilter(filter1);
        mListBuilder.addFinalizeFilter(filter2);

        // WHEN both filters invalidate
        filter1.invalidateList(null);
        filter2.invalidateList(null);

        // THEN the pipeline choreographer is scheduled to evaluate, AND the pipeline hasn't
        // actually run.
        assertTrue(mPipelineChoreographer.isScheduled());
        verify(mOnRenderListListener, never()).onRenderList(anyList());

        // WHEN the pipeline choreographer actually runs
        mPipelineChoreographer.runIfScheduled();

        // THEN the pipeline runs
        verify(mOnRenderListListener).onRenderList(anyList());
    }

    @Test
    public void testIsSorted() {
        Comparator<Integer> intCmp = Integer::compare;
        assertTrue(ShadeListBuilder.isSorted(Collections.emptyList(), intCmp));
        assertTrue(ShadeListBuilder.isSorted(Collections.singletonList(1), intCmp));
        assertTrue(ShadeListBuilder.isSorted(Arrays.asList(1, 2), intCmp));
        assertTrue(ShadeListBuilder.isSorted(Arrays.asList(1, 2, 3), intCmp));
        assertTrue(ShadeListBuilder.isSorted(Arrays.asList(1, 2, 3, 4), intCmp));
        assertTrue(ShadeListBuilder.isSorted(Arrays.asList(1, 2, 3, 4, 5), intCmp));
        assertTrue(ShadeListBuilder.isSorted(Arrays.asList(1, 1, 1, 1, 1), intCmp));
        assertTrue(ShadeListBuilder.isSorted(Arrays.asList(1, 1, 2, 2, 3, 3), intCmp));

        assertFalse(ShadeListBuilder.isSorted(Arrays.asList(2, 1), intCmp));
        assertFalse(ShadeListBuilder.isSorted(Arrays.asList(2, 1, 2), intCmp));
        assertFalse(ShadeListBuilder.isSorted(Arrays.asList(1, 2, 1), intCmp));
        assertFalse(ShadeListBuilder.isSorted(Arrays.asList(1, 2, 3, 2, 5), intCmp));
        assertFalse(ShadeListBuilder.isSorted(Arrays.asList(5, 2, 3, 4, 5), intCmp));
        assertFalse(ShadeListBuilder.isSorted(Arrays.asList(1, 2, 3, 4, 1), intCmp));
    }

    /**
     * Adds a notif to the collection that will be passed to the list builder when
     * {@link #dispatchBuild()}s is called.
     *
     * @param index Index of this notification in the set. This must be the current size of the set.
     *              it exists to improve readability of the resulting code, since later tests will
     *              have to refer to notifs by index.
     * @param packageId Package that the notif should be posted under
     * @return A NotificationEntryBuilder that can be used to further modify the notif. Do not call
     *         build() on the builder; that will be done on the next dispatchBuild().
     */
    private NotificationEntryBuilder addNotif(int index, String packageId) {
        final NotificationEntryBuilder builder = new NotificationEntryBuilder()
                .setPkg(packageId)
                .setId(nextId(packageId))
                .setRank(nextRank());

        builder.modifyNotification(mContext)
                .setContentTitle("Top level singleton")
                .setChannelId("test_channel");

        assertEquals(mEntrySet.size() + mPendingSet.size(), index);
        mPendingSet.add(builder);
        return builder;
    }

    /** Same behavior as {@link #addNotif(int, String)}. */
    private NotificationEntryBuilder addGroupSummary(int index, String packageId, String groupId) {
        final NotificationEntryBuilder builder = new NotificationEntryBuilder()
                .setPkg(packageId)
                .setId(nextId(packageId))
                .setRank(nextRank());

        builder.modifyNotification(mContext)
                .setChannelId("test_channel")
                .setContentTitle("Group summary")
                .setGroup(groupId)
                .setGroupSummary(true);

        assertEquals(mEntrySet.size() + mPendingSet.size(), index);
        mPendingSet.add(builder);
        return builder;
    }

    private NotificationEntryBuilder addGroupChildWithTag(int index, String packageId,
            String groupId, String tag) {
        final NotificationEntryBuilder builder = new NotificationEntryBuilder()
                .setTag(tag)
                .setPkg(packageId)
                .setId(nextId(packageId))
                .setRank(nextRank());

        builder.modifyNotification(mContext)
                .setChannelId("test_channel")
                .setContentTitle("Group child")
                .setGroup(groupId);

        assertEquals(mEntrySet.size() + mPendingSet.size(), index);
        mPendingSet.add(builder);
        return builder;
    }

    /** Same behavior as {@link #addNotif(int, String)}. */
    private NotificationEntryBuilder addGroupChild(int index, String packageId, String groupId) {
        return addGroupChildWithTag(index, packageId, groupId, null);
    }

    private void assertOrder(String visible, String active, String expected) {
        StringBuilder differenceSb = new StringBuilder();
        for (char c : active.toCharArray()) {
            if (visible.indexOf(c) < 0) differenceSb.append(c);
        }
        String difference = differenceSb.toString();

        for (int i = 0; i < visible.length(); i++) {
            addNotif(i, String.valueOf(visible.charAt(i)))
                    .setRank(active.indexOf(visible.charAt(i)))
                    .setStableIndex(i);

        }

        for (int i = 0; i < difference.length(); i++) {
            addNotif(i + visible.length(), String.valueOf(difference.charAt(i)))
                    .setRank(active.indexOf(difference.charAt(i)))
                    .setStableIndex(-1);
        }

        dispatchBuild();
        StringBuilder resultSb = new StringBuilder();
        for (int i = 0; i < expected.length(); i++) {
            resultSb.append(mBuiltList.get(i).getRepresentativeEntry().getSbn().getPackageName());
        }

        assertEquals("visible [" + visible + "] active [" + active + "]",
                expected, resultSb.toString());
        mEntrySet.clear();
    }

    private int nextId(String packageName) {
        Integer nextId = mNextIdMap.get(packageName);
        if (nextId == null) {
            nextId = 0;
        }
        mNextIdMap.put(packageName, nextId + 1);
        return nextId;
    }

    private int nextRank() {
        int nextRank = mNextRank;
        mNextRank++;
        return nextRank;
    }

    private void dispatchBuild() {
        if (mPendingSet.size() > 0) {
            for (NotificationEntryBuilder builder : mPendingSet) {
                mEntrySet.add(builder.build());
            }
            mPendingSet.clear();
        }

        mReadyForBuildListener.onBuildList(mEntrySet, "test");
        mPipelineChoreographer.runIfScheduled();
    }

    private void runWhileScheduledUpTo(int maxRuns) {
        int runs = 0;
        while (mPipelineChoreographer.isScheduled()) {
            if (runs > maxRuns) {
                throw new IndexOutOfBoundsException(
                        "Pipeline scheduled itself more than " + maxRuns + "times");
            }
            runs++;
            mPipelineChoreographer.runIfScheduled();
        }
    }

    private void verifyBuiltList(ExpectedEntry ...expectedEntries) {
        try {
            assertEquals(
                    "List is the wrong length",
                    expectedEntries.length,
                    mBuiltList.size());

            for (int i = 0; i < expectedEntries.length; i++) {
                ListEntry outEntry = mBuiltList.get(i);
                ExpectedEntry expectedEntry = expectedEntries[i];

                if (expectedEntry instanceof ExpectedNotif) {
                    assertEquals(
                            "Entry " + i + " isn't a NotifEntry",
                            NotificationEntry.class,
                            outEntry.getClass());
                    assertEquals(
                            "Entry " + i + " doesn't match expected value.",
                            ((ExpectedNotif) expectedEntry).entry, outEntry);
                } else {
                    ExpectedGroup cmpGroup = (ExpectedGroup) expectedEntry;

                    assertEquals(
                            "Entry " + i + " isn't a GroupEntry",
                            GroupEntry.class,
                            outEntry.getClass());

                    GroupEntry outGroup = (GroupEntry) outEntry;

                    assertEquals(
                            "Summary notif for entry " + i
                                    + " doesn't match expected value",
                            cmpGroup.summary,
                            outGroup.getSummary());
                    assertEquals(
                            "Summary notif for entry " + i
                                        + " doesn't have proper parent",
                            outGroup,
                            outGroup.getSummary().getParent());

                    assertEquals("Children for entry " + i,
                            cmpGroup.children,
                            outGroup.getChildren());

                    for (int j = 0; j < outGroup.getChildren().size(); j++) {
                        NotificationEntry child = outGroup.getChildren().get(j);
                        assertEquals(
                                "Child " + j + " for entry " + i
                                        + " doesn't have proper parent",
                                outGroup,
                                child.getParent());
                    }
                }
            }
        } catch (AssertionError err) {
            throw new AssertionError(
                    "List under test failed verification:\n" + dumpTree(mBuiltList,
                            mInteractionTracker, true, ""), err);
        }
    }

    private ExpectedNotif notif(int index) {
        return new ExpectedNotif(mEntrySet.get(index));
    }

    private ExpectedGroup group(ExpectedSummary summary, ExpectedChild...children) {
        return new ExpectedGroup(
                summary.entry,
                Arrays.stream(children)
                        .map(child -> child.entry)
                        .collect(Collectors.toList()));
    }

    private ExpectedSummary summary(int index) {
        return new ExpectedSummary(mEntrySet.get(index));
    }

    private ExpectedChild child(int index) {
        return new ExpectedChild(mEntrySet.get(index));
    }

    private abstract static class ExpectedEntry {
    }

    private static class ExpectedNotif extends ExpectedEntry {
        public final NotificationEntry entry;

        private ExpectedNotif(NotificationEntry entry) {
            this.entry = entry;
        }
    }

    private static class ExpectedGroup extends ExpectedEntry {
        public final NotificationEntry summary;
        public final List<NotificationEntry> children;

        private ExpectedGroup(
                NotificationEntry summary,
                List<NotificationEntry> children) {
            this.summary = summary;
            this.children = children;
        }
    }

    private static class ExpectedSummary {
        public final NotificationEntry entry;

        private ExpectedSummary(NotificationEntry entry) {
            this.entry = entry;
        }
    }

    private static class ExpectedChild {
        public final NotificationEntry entry;

        private ExpectedChild(NotificationEntry entry) {
            this.entry = entry;
        }
    }

    /** Filters out notifs from a particular package */
    private static class PackageFilter extends NotifFilter {
        private final String mPackageName;

        private boolean mEnabled = true;

        PackageFilter(String packageName) {
            super("PackageFilter");

            mPackageName = packageName;
        }

        @Override
        public boolean shouldFilterOut(NotificationEntry entry, long now) {
            return mEnabled && entry.getSbn().getPackageName().equals(mPackageName);
        }

        public void setEnabled(boolean enabled) {
            mEnabled = enabled;
        }
    }

    /** Filters out notifications with a particular tag */
    private static class NotifFilterWithTag extends NotifFilter {
        private final String mTag;

        NotifFilterWithTag(String tag) {
            super("NotifFilterWithTag_" + tag);
            mTag = tag;
        }

        @Override
        public boolean shouldFilterOut(NotificationEntry entry, long now) {
            return Objects.equals(entry.getSbn().getTag(), mTag);
        }
    }

    /** Promotes notifs with particular IDs */
    private static class IdPromoter extends NotifPromoter {
        private final List<Integer> mIds;

        IdPromoter(Integer... ids) {
            super("IdPromoter");
            mIds = asList(ids);
        }

        @Override
        public boolean shouldPromoteToTopLevel(NotificationEntry child) {
            return mIds.contains(child.getSbn().getId());
        }
    }

    /** Sorts specific notifs above all others. */
    private static class HypeComparator extends NotifComparator {

        private final List<String> mPreferredPackages;

        HypeComparator(String ...preferredPackages) {
            super("HypeComparator");
            mPreferredPackages = asList(preferredPackages);
        }

        @Override
        public int compare(@NonNull ListEntry o1, @NonNull ListEntry o2) {
            boolean contains1 = mPreferredPackages.contains(
                    o1.getRepresentativeEntry().getSbn().getPackageName());
            boolean contains2 = mPreferredPackages.contains(
                    o2.getRepresentativeEntry().getSbn().getPackageName());

            return Boolean.compare(contains2, contains1);
        }
    }

    /** Represents a section for the passed pkg */
    private static class PackageSectioner extends NotifSectioner {
        private final List<String> mPackages;
        private final NotifComparator mComparator;

        PackageSectioner(List<String> pkgs, NotifComparator comparator) {
            super("PackageSection_" + pkgs, 0);
            mPackages = pkgs;
            mComparator = comparator;
        }

        PackageSectioner(String pkg) {
            this(pkg, 0);
        }

        PackageSectioner(String pkg, int bucket) {
            super("PackageSection_" + pkg, bucket);
            mPackages = List.of(pkg);
            mComparator = null;
        }

        @Nullable
        @Override
        public NotifComparator getComparator() {
            return mComparator;
        }

        @Override
        public boolean isInSection(ListEntry entry) {
            return mPackages.contains(entry.getRepresentativeEntry().getSbn().getPackageName());
        }
    }

    private static class RecordingOnBeforeTransformGroupsListener
            implements OnBeforeTransformGroupsListener {
        List<ListEntry> mEntriesReceived;

        @Override
        public void onBeforeTransformGroups(List<ListEntry> list) {
            mEntriesReceived = new ArrayList<>(list);
        }
    }

    private static class RecordingOnBeforeSortListener
            implements OnBeforeSortListener {
        List<ListEntry> mEntriesReceived;

        @Override
        public void onBeforeSort(List<ListEntry> list) {
            mEntriesReceived = new ArrayList<>(list);
        }
    }

    private static class RecordingOnBeforeRenderListener
            implements OnBeforeRenderListListener {
        List<ListEntry> mEntriesReceived;

        @Override
        public void onBeforeRenderList(List<ListEntry> list) {
            mEntriesReceived = new ArrayList<>(list);
        }
    }

    private class TestableNotifFilter extends NotifFilter {
        ArrayList<Integer> mIndicesToFilter = new ArrayList<>();

        protected TestableNotifFilter() {
            super("TestFilter");
        }

        @Override
        public boolean shouldFilterOut(@NonNull NotificationEntry entry, long now) {
            return mIndicesToFilter.stream().anyMatch(i -> notif(i).entry == entry);
        }
    }

    private static class TestableStabilityManager extends NotifStabilityManager {
        boolean mAllowPipelineRun = true;
        boolean mAllowGroupChanges = true;
        boolean mAllowGroupPruning = true;
        boolean mAllowSectionChanges = true;
        boolean mAllowEntryReodering = true;

        TestableStabilityManager() {
            super("Test");
        }

        TestableStabilityManager setAllowGroupChanges(boolean allowGroupChanges) {
            mAllowGroupChanges = allowGroupChanges;
            return this;
        }

        TestableStabilityManager setAllowGroupPruning(boolean allowGroupPruning) {
            mAllowGroupPruning = allowGroupPruning;
            return this;
        }

        TestableStabilityManager setAllowSectionChanges(boolean allowSectionChanges) {
            mAllowSectionChanges = allowSectionChanges;
            return this;
        }

        TestableStabilityManager setAllowEntryReordering(boolean allowSectionChanges) {
            mAllowEntryReodering = allowSectionChanges;
            return this;
        }

        TestableStabilityManager setAllowPipelineRun(boolean allowPipelineRun) {
            mAllowPipelineRun = allowPipelineRun;
            return this;
        }

        @Override
        public boolean isPipelineRunAllowed() {
            return mAllowPipelineRun;
        }

        @Override
        public void onBeginRun() {
        }

        @Override
        public boolean isGroupChangeAllowed(@NonNull NotificationEntry entry) {
            return mAllowGroupChanges;
        }

        @Override
        public boolean isGroupPruneAllowed(@NonNull GroupEntry entry) {
            return mAllowGroupPruning;
        }

        @Override
        public boolean isSectionChangeAllowed(@NonNull NotificationEntry entry) {
            return mAllowSectionChanges;
        }

        @Override
        public boolean isEntryReorderingAllowed(@NonNull ListEntry entry) {
            return mAllowEntryReodering;
        }

        @Override
        public boolean isEveryChangeAllowed() {
            return mAllowEntryReodering && mAllowGroupChanges && mAllowSectionChanges;
        }

        @Override
        public void onEntryReorderSuppressed() {
        }
    }

    private static final String PACKAGE_0 = "com.test0";
    private static final String PACKAGE_1 = "com.test1";
    private static final String PACKAGE_2 = "com.test2";
    private static final String PACKAGE_3 = "org.test3";
    private static final String PACKAGE_4 = "com.test4";
    private static final String PACKAGE_5 = "com.test5";

    private static final String GROUP_1 = "group_1";
    private static final String GROUP_2 = "group_2";
}
