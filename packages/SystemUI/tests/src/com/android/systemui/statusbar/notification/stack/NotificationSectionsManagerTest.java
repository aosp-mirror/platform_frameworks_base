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

package com.android.systemui.statusbar.notification.stack;

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import static com.android.systemui.statusbar.notification.stack.NotificationSectionsManagerKt.BUCKET_ALERTING;
import static com.android.systemui.statusbar.notification.stack.NotificationSectionsManagerKt.BUCKET_FOREGROUND_SERVICE;
import static com.android.systemui.statusbar.notification.stack.NotificationSectionsManagerKt.BUCKET_HEADS_UP;
import static com.android.systemui.statusbar.notification.stack.NotificationSectionsManagerKt.BUCKET_PEOPLE;
import static com.android.systemui.statusbar.notification.stack.NotificationSectionsManagerKt.BUCKET_SILENT;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.filters.SmallTest;

import com.android.systemui.ActivityStarterDelegate;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.media.KeyguardMediaController;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.NotificationSectionsFeatureManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.people.PeopleHubViewAdapter;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationViewController;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.dagger.NotificationRowComponent;
import com.android.systemui.statusbar.policy.ConfigurationController;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class NotificationSectionsManagerTest extends SysuiTestCase {

    @Rule public final MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock private NotificationStackScrollLayout mNssl;
    @Mock private ActivityStarterDelegate mActivityStarterDelegate;
    @Mock private StatusBarStateController mStatusBarStateController;
    @Mock private ConfigurationController mConfigurationController;
    @Mock private PeopleHubViewAdapter mPeopleHubAdapter;
    @Mock private KeyguardMediaController mKeyguardMediaController;
    @Mock private NotificationSectionsFeatureManager mSectionsFeatureManager;
    @Mock private NotificationRowComponent mNotificationRowComponent;
    @Mock private ActivatableNotificationViewController mActivatableNotificationViewController;
    @Mock private NotificationSectionsLogger mLogger;

    private NotificationSectionsManager mSectionsManager;

    @Before
    public void setUp() {
        when(mSectionsFeatureManager.getNumberOfBuckets()).thenAnswer(
                invocation -> {
                    int count = 2;
                    if (mSectionsFeatureManager.isFilteringEnabled()) {
                        count = 5;
                    }
                    if (mSectionsFeatureManager.isMediaControlsEnabled()) {
                        if (!mSectionsFeatureManager.isFilteringEnabled()) {
                            count = 5;
                        } else {
                            count += 1;
                        }
                    }
                    return count;
                });
        when(mNotificationRowComponent.getActivatableNotificationViewController())
                .thenReturn(mActivatableNotificationViewController);
        mSectionsManager =
                new NotificationSectionsManager(
                        mActivityStarterDelegate,
                        mStatusBarStateController,
                        mConfigurationController,
                        mPeopleHubAdapter,
                        mKeyguardMediaController,
                        mSectionsFeatureManager,
                        mLogger
                );
        // Required in order for the header inflation to work properly
        when(mNssl.generateLayoutParams(any(AttributeSet.class)))
                .thenReturn(new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        mSectionsManager.initialize(mNssl, LayoutInflater.from(mContext));
        when(mNssl.indexOfChild(any(View.class))).thenReturn(-1);
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.SHADE);

    }

    @Test(expected =  IllegalStateException.class)
    public void testDuplicateInitializeThrows() {
        mSectionsManager.initialize(mNssl, LayoutInflater.from(mContext));
    }

    @Test
    public void testInsertHeader() {
        // GIVEN a stack with HI and LO rows but no section headers
        setStackState(
                ALERTING,
                ALERTING,
                ALERTING,
                GENTLE);

        // WHEN we update the section headers
        mSectionsManager.updateSectionBoundaries();

        // THEN a LO section header is added
        verify(mNssl).addView(mSectionsManager.getSilentHeaderView(), 3);
    }

    @Test
    public void testRemoveHeader() {
        // GIVEN a stack that originally had a header between the HI and LO sections
        setStackState(
                ALERTING,
                ALERTING,
                GENTLE);
        mSectionsManager.updateSectionBoundaries();

        // WHEN the last LO row is replaced with a HI row
        setStackState(
                ALERTING,
                ALERTING,
                GENTLE_HEADER,
                ALERTING);
        clearInvocations(mNssl);
        mSectionsManager.updateSectionBoundaries();

        // THEN the LO section header is removed
        verify(mNssl).removeView(mSectionsManager.getSilentHeaderView());
    }

    @Test
    public void testDoNothingIfHeaderAlreadyRemoved() {
        // GIVEN a stack with only HI rows
        setStackState(
                ALERTING,
                ALERTING,
                ALERTING);

        // WHEN we update the sections headers
        mSectionsManager.updateSectionBoundaries();

        // THEN we don't add any section headers
        verify(mNssl, never()).addView(eq(mSectionsManager.getSilentHeaderView()), anyInt());
    }

    @Test
    public void testMoveHeaderForward() {
        // GIVEN a stack that originally had a header between the HI and LO sections
        setStackState(
                ALERTING,
                ALERTING,
                ALERTING,
                GENTLE);
        mSectionsManager.updateSectionBoundaries();

        // WHEN the LO section moves forward
        setStackState(
                ALERTING,
                ALERTING,
                GENTLE,
                GENTLE_HEADER,
                GENTLE);
        mSectionsManager.updateSectionBoundaries();

        // THEN the LO section header is also moved forward
        verify(mNssl).changeViewPosition(mSectionsManager.getSilentHeaderView(), 2);
    }

    @Test
    public void testMoveHeaderBackward() {
        // GIVEN a stack that originally had a header between the HI and LO sections
        setStackState(
                ALERTING,
                GENTLE,
                GENTLE,
                GENTLE);
        mSectionsManager.updateSectionBoundaries();

        // WHEN the LO section moves backward
        setStackState(
                ALERTING,
                GENTLE_HEADER,
                ALERTING,
                ALERTING,
                GENTLE);
        mSectionsManager.updateSectionBoundaries();

        // THEN the LO section header is also moved backward (with appropriate index shifting)
        verify(mNssl).changeViewPosition(mSectionsManager.getSilentHeaderView(), 3);
    }

    @Test
    public void testHeaderRemovedFromTransientParent() {
        // GIVEN a stack where the header is animating away
        setStackState(
                ALERTING,
                GENTLE_HEADER);
        mSectionsManager.updateSectionBoundaries();
        clearInvocations(mNssl);

        ViewGroup transientParent = mock(ViewGroup.class);
        mSectionsManager.getSilentHeaderView().setTransientContainer(transientParent);

        // WHEN the LO section reappears
        setStackState(
                ALERTING,
                GENTLE);
        mSectionsManager.updateSectionBoundaries();

        // THEN the header is first removed from the transient parent before being added to the
        // NSSL.
        verify(transientParent).removeTransientView(mSectionsManager.getSilentHeaderView());
        verify(mNssl).addView(mSectionsManager.getSilentHeaderView(), 1);
    }

    @Test
    public void testHeaderNotShownOnLockscreen() {
        // GIVEN a stack of HI and LO notifs on the lockscreen
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.KEYGUARD);
        setStackState(
                ALERTING,
                ALERTING,
                ALERTING,
                GENTLE);

        // WHEN we update the section headers
        mSectionsManager.updateSectionBoundaries();

        // Then the section header is not added
        verify(mNssl, never()).addView(eq(mSectionsManager.getSilentHeaderView()), anyInt());
    }

    @Test
    public void testHeaderShownWhenEnterLockscreen() {
        // GIVEN a stack of HI and LO notifs on the lockscreen
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.KEYGUARD);
        setStackState(
                ALERTING,
                ALERTING,
                ALERTING,
                GENTLE);
        mSectionsManager.updateSectionBoundaries();

        // WHEN we unlock
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.SHADE);
        mSectionsManager.updateSectionBoundaries();

        // Then the section header is added
        verify(mNssl).addView(mSectionsManager.getSilentHeaderView(), 3);
    }

    @Test
    public void testHeaderHiddenWhenEnterLockscreen() {
        // GIVEN a stack of HI and LO notifs on the shade
        setStackState(
                ALERTING,
                GENTLE_HEADER,
                GENTLE);

        // WHEN we go back to the keyguard
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.KEYGUARD);
        mSectionsManager.updateSectionBoundaries();

        // Then the section header is removed
        verify(mNssl).removeView(mSectionsManager.getSilentHeaderView());
    }

    @Test
    public void testPeopleFiltering_addHeadersFromShowingOnlyGentle() {
        enablePeopleFiltering();

        setStackState(
                GENTLE_HEADER,
                PERSON,
                ALERTING,
                GENTLE);
        mSectionsManager.updateSectionBoundaries();

        verify(mNssl).changeViewPosition(mSectionsManager.getSilentHeaderView(), 2);
        verify(mNssl).addView(mSectionsManager.getAlertingHeaderView(), 1);
        verify(mNssl).addView(mSectionsManager.getPeopleHeaderView(), 0);
    }

    @Test
    public void testPeopleFiltering_addAllHeaders() {
        enablePeopleFiltering();

        setStackState(
                PERSON,
                ALERTING,
                GENTLE);
        mSectionsManager.updateSectionBoundaries();

        verify(mNssl).addView(mSectionsManager.getSilentHeaderView(), 2);
        verify(mNssl).addView(mSectionsManager.getAlertingHeaderView(), 1);
        verify(mNssl).addView(mSectionsManager.getPeopleHeaderView(), 0);
    }

    @Test
    public void testPeopleFiltering_moveAllHeaders() {
        enablePeopleFiltering();

        setStackState(
                PEOPLE_HEADER,
                ALERTING_HEADER,
                GENTLE_HEADER,
                PERSON,
                ALERTING,
                GENTLE);
        mSectionsManager.updateSectionBoundaries();

        verify(mNssl).changeViewPosition(mSectionsManager.getSilentHeaderView(), 4);
        verify(mNssl).changeViewPosition(mSectionsManager.getAlertingHeaderView(), 2);
        verify(mNssl).changeViewPosition(mSectionsManager.getPeopleHeaderView(), 0);
    }

    @Test
    public void testPeopleFiltering_keepPeopleHeaderWhenSectionEmpty() {
        mSectionsManager.setPeopleHubVisible(true);
        enablePeopleFiltering();

        setStackState(
                PEOPLE_HEADER,
                ALERTING_HEADER,
                ALERTING,
                GENTLE_HEADER,
                GENTLE);
        mSectionsManager.updateSectionBoundaries();

        verify(mNssl, never()).removeView(mSectionsManager.getPeopleHeaderView());
        verify(mNssl).changeViewPosition(mSectionsManager.getPeopleHeaderView(), 0);
    }

    @Test
    public void testPeopleFiltering_AlertingHunWhilePeopleVisible() {
        enablePeopleFiltering();

        setupMockStack(
                PEOPLE_HEADER,
                ALERTING,
                PERSON,
                ALERTING_HEADER,
                GENTLE_HEADER,
                GENTLE
        );
        mSectionsManager.updateSectionBoundaries();

        verifyMockStack(
                ChildType.INCOMING_HEADER,
                ChildType.HEADS_UP,
                ChildType.PEOPLE_HEADER,
                ChildType.PERSON,
                ChildType.GENTLE_HEADER,
                ChildType.GENTLE
        );
    }

    @Test
    public void testPeopleFiltering_PersonHunWhileAlertingHunVisible() {
        enablePeopleFiltering();

        setupMockStack(
                PERSON,
                INCOMING_HEADER,
                ALERTING,
                PEOPLE_HEADER,
                PERSON
        );
        mSectionsManager.updateSectionBoundaries();

        verifyMockStack(
                ChildType.INCOMING_HEADER,
                ChildType.HEADS_UP,
                ChildType.HEADS_UP,
                ChildType.PEOPLE_HEADER,
                ChildType.PERSON
        );
    }

    @Test
    public void testPeopleFiltering_PersonHun() {
        enablePeopleFiltering();

        setupMockStack(
                PERSON,
                PEOPLE_HEADER,
                PERSON
        );
        mSectionsManager.updateSectionBoundaries();

        verifyMockStack(
                ChildType.PEOPLE_HEADER,
                ChildType.PERSON,
                ChildType.PERSON
        );
    }

    @Test
    public void testPeopleFiltering_AlertingHunWhilePersonHunning() {
        enablePeopleFiltering();

        setupMockStack(
                ALERTING,
                PERSON
        );
        mSectionsManager.updateSectionBoundaries();
        verifyMockStack(
                ChildType.INCOMING_HEADER,
                ChildType.HEADS_UP,
                ChildType.PEOPLE_HEADER,
                ChildType.PERSON
        );
    }

    @Test
    public void testPeopleFiltering_Fsn() {
        enablePeopleFiltering();

        setupMockStack(
                INCOMING_HEADER,
                ALERTING,
                PEOPLE_HEADER,
                FSN,
                PERSON,
                ALERTING,
                GENTLE
        );
        mSectionsManager.updateSectionBoundaries();

        verifyMockStack(
                ChildType.INCOMING_HEADER,
                ChildType.HEADS_UP,
                ChildType.FSN,
                ChildType.PEOPLE_HEADER,
                ChildType.PERSON,
                ChildType.ALERTING_HEADER,
                ChildType.ALERTING,
                ChildType.GENTLE_HEADER,
                ChildType.GENTLE
        );
    }

    @Test
    public void testMediaControls_AddWhenEnterKeyguard() {
        enableMediaControls();

        // GIVEN a stack that doesn't include media controls
        setStackState(ALERTING, GENTLE_HEADER, GENTLE);

        // WHEN we go back to the keyguard
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.KEYGUARD);
        mSectionsManager.updateSectionBoundaries();

        // Then the media controls are added
        verify(mNssl).addView(mSectionsManager.getMediaControlsView(), 0);
    }

    @Test
    public void testMediaControls_AddWhenEnterKeyguardWithHeadsUp() {
        enableMediaControls();

        // GIVEN a stack that doesn't include media
        setupMockStack(
                ALERTING,
                ALERTING,
                GENTLE_HEADER,
                GENTLE);

        // WHEN we go back to the keyguard
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.KEYGUARD);
        mSectionsManager.updateSectionBoundaries();

        verifyMockStack(
                ChildType.MEDIA_CONTROLS,
                ChildType.ALERTING,
                ChildType.ALERTING,
                ChildType.GENTLE);
    }

    @Test
    public void testRemoveIncomingHeader() {
        enablePeopleFiltering();
        enableMediaControls();

        setupMockStack(
                MEDIA_CONTROLS,
                INCOMING_HEADER,
                PERSON,
                ALERTING,
                PEOPLE_HEADER,
                ALERTING_HEADER,
                ALERTING,
                ALERTING,
                GENTLE_HEADER,
                GENTLE,
                GENTLE
        );

        mSectionsManager.updateSectionBoundaries();

        verifyMockStack(
                ChildType.MEDIA_CONTROLS,
                ChildType.PEOPLE_HEADER,
                ChildType.PERSON,
                ChildType.ALERTING_HEADER,
                ChildType.ALERTING,
                ChildType.ALERTING,
                ChildType.ALERTING,
                ChildType.GENTLE_HEADER,
                ChildType.GENTLE,
                ChildType.GENTLE
        );
    }

    @Test
    public void testExpandIncomingSection() {
        enablePeopleFiltering();

        setupMockStack(
                INCOMING_HEADER,
                PERSON,
                ALERTING,
                PEOPLE_HEADER,
                ALERTING,
                PERSON,
                ALERTING_HEADER,
                ALERTING
        );

        mSectionsManager.updateSectionBoundaries();

        verifyMockStack(
                ChildType.INCOMING_HEADER,
                ChildType.HEADS_UP,
                ChildType.HEADS_UP,
                ChildType.HEADS_UP,
                ChildType.PEOPLE_HEADER,
                ChildType.PERSON,
                ChildType.ALERTING_HEADER,
                ChildType.ALERTING
        );
    }

    @Test
    public void testIgnoreGoneView() {
        enablePeopleFiltering();

        setupMockStack(
                PERSON.gone(),
                ALERTING,
                GENTLE
        );

        mSectionsManager.updateSectionBoundaries();

        verifyMockStack(
                ChildType.ALERTING_HEADER,
                ChildType.PERSON,
                ChildType.ALERTING,
                ChildType.GENTLE_HEADER,
                ChildType.GENTLE
        );
    }

    private void enablePeopleFiltering() {
        when(mSectionsFeatureManager.isFilteringEnabled()).thenReturn(true);
    }

    private void enableMediaControls() {
        when(mSectionsFeatureManager.isMediaControlsEnabled()).thenReturn(true);
    }

    private enum ChildType {
        INCOMING_HEADER, MEDIA_CONTROLS, PEOPLE_HEADER, ALERTING_HEADER, GENTLE_HEADER, HEADS_UP,
        FSN, PERSON, ALERTING, GENTLE, OTHER
    }

    private void setStackState(StackEntry... children) {
        when(mNssl.getChildCount()).thenReturn(children.length);
        for (int i = 0; i < children.length; i++) {
            View child;
            StackEntry entry = children[i];
            switch (entry.mChildType) {
                case INCOMING_HEADER:
                    child = mSectionsManager.getIncomingHeaderView();
                    break;
                case MEDIA_CONTROLS:
                    child = mSectionsManager.getMediaControlsView();
                    break;
                case PEOPLE_HEADER:
                    child = mSectionsManager.getPeopleHeaderView();
                    break;
                case ALERTING_HEADER:
                    child = mSectionsManager.getAlertingHeaderView();
                    break;
                case GENTLE_HEADER:
                    child = mSectionsManager.getSilentHeaderView();
                    break;
                case FSN:
                    child = mockNotification(BUCKET_FOREGROUND_SERVICE, entry.mIsGone);
                    break;
                case PERSON:
                    child = mockNotification(BUCKET_PEOPLE, entry.mIsGone);
                    break;
                case ALERTING:
                    child = mockNotification(BUCKET_ALERTING, entry.mIsGone);
                    break;
                case GENTLE:
                    child = mockNotification(BUCKET_SILENT, entry.mIsGone);
                    break;
                case OTHER:
                    child = mock(View.class);
                    when(child.getVisibility()).thenReturn(View.VISIBLE);
                    when(child.getParent()).thenReturn(mNssl);
                    break;
                default:
                    throw new RuntimeException("Unknown ChildType: " + children[i]);
            }
            when(mNssl.getChildAt(i)).thenReturn(child);
            when(mNssl.indexOfChild(child)).thenReturn(i);
        }
    }

    private View mockNotification(int bucket, boolean isGone) {
        ExpandableNotificationRow notifRow =
                mock(ExpandableNotificationRow.class, RETURNS_DEEP_STUBS);
        when(notifRow.getVisibility()).thenReturn(View.VISIBLE);
        when(notifRow.getParent()).thenReturn(mNssl);

        NotificationEntry mockEntry = mock(NotificationEntry.class);
        when(notifRow.getEntry()).thenReturn(mockEntry);

        int[] bucketRef = new int[] { bucket };
        when(mockEntry.getBucket()).thenAnswer(invocation -> bucketRef[0]);
        doAnswer(invocation -> {
            bucketRef[0] = invocation.getArgument(0);
            return null;
        }).when(mockEntry).setBucket(anyInt());

        when(notifRow.getVisibility()).thenReturn(isGone ? View.GONE : View.VISIBLE);
        return notifRow;
    }

    private void verifyMockStack(ChildType... expected) {
        final List<ChildType> actual = new ArrayList<>();
        int childCount = mNssl.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = mNssl.getChildAt(i);
            if (child == mSectionsManager.getIncomingHeaderView()) {
                actual.add(ChildType.INCOMING_HEADER);
                continue;
            }
            if (child == mSectionsManager.getMediaControlsView()) {
                actual.add(ChildType.MEDIA_CONTROLS);
                continue;
            }
            if (child == mSectionsManager.getPeopleHeaderView()) {
                actual.add(ChildType.PEOPLE_HEADER);
                continue;
            }
            if (child == mSectionsManager.getAlertingHeaderView()) {
                actual.add(ChildType.ALERTING_HEADER);
                continue;
            }
            if (child == mSectionsManager.getSilentHeaderView()) {
                actual.add(ChildType.GENTLE_HEADER);
                continue;
            }
            if (child instanceof ExpandableNotificationRow) {
                switch (((ExpandableNotificationRow) child).getEntry().getBucket()) {
                    case BUCKET_HEADS_UP:
                        actual.add(ChildType.HEADS_UP);
                        break;
                    case BUCKET_FOREGROUND_SERVICE:
                        actual.add(ChildType.FSN);
                        break;
                    case BUCKET_PEOPLE:
                        actual.add(ChildType.PERSON);
                        break;
                    case BUCKET_ALERTING:
                        actual.add(ChildType.ALERTING);
                        break;
                    case BUCKET_SILENT:
                        actual.add(ChildType.GENTLE);
                        break;
                    default:
                        actual.add(ChildType.OTHER);
                        break;
                }
                continue;
            }
            actual.add(ChildType.OTHER);
        }
        assertThat(actual).containsExactly((Object[]) expected).inOrder();
    }

    private void setupMockStack(StackEntry... entries) {
        final List<View> children = new ArrayList<>();
        when(mNssl.getChildCount()).thenAnswer(invocation -> children.size());
        when(mNssl.getChildAt(anyInt()))
                .thenAnswer(invocation -> {
                    Integer index = invocation.getArgument(0);
                    if (index == null || index < 0 || index >= children.size()) {
                        return null;
                    }
                    return children.get(index);
                });
        when(mNssl.indexOfChild(any()))
                .thenAnswer(invocation -> children.indexOf(invocation.getArgument(0)));
        doAnswer(invocation -> {
            View child = invocation.getArgument(0);
            int index = invocation.getArgument(1);
            children.add(index, child);
            return null;
        }).when(mNssl).addView(any(), anyInt());
        doAnswer(invocation -> {
            View child = invocation.getArgument(0);
            children.remove(child);
            return null;
        }).when(mNssl).removeView(any());
        doAnswer(invocation -> {
            View child = invocation.getArgument(0);
            int newIndex = invocation.getArgument(1);
            children.remove(child);
            children.add(newIndex, child);
            return null;
        }).when(mNssl).changeViewPosition(any(), anyInt());
        for (StackEntry entry : entries) {
            View child;
            switch (entry.mChildType) {
                case INCOMING_HEADER:
                    child = mSectionsManager.getIncomingHeaderView();
                    break;
                case MEDIA_CONTROLS:
                    child = mSectionsManager.getMediaControlsView();
                    break;
                case PEOPLE_HEADER:
                    child = mSectionsManager.getPeopleHeaderView();
                    break;
                case ALERTING_HEADER:
                    child = mSectionsManager.getAlertingHeaderView();
                    break;
                case GENTLE_HEADER:
                    child = mSectionsManager.getSilentHeaderView();
                    break;
                case FSN:
                    child = mockNotification(BUCKET_FOREGROUND_SERVICE, entry.mIsGone);
                    break;
                case PERSON:
                    child = mockNotification(BUCKET_PEOPLE, entry.mIsGone);
                    break;
                case ALERTING:
                    child = mockNotification(BUCKET_ALERTING, entry.mIsGone);
                    break;
                case GENTLE:
                    child = mockNotification(BUCKET_SILENT, entry.mIsGone);
                    break;
                case OTHER:
                    child = mock(View.class);
                    when(child.getVisibility()).thenReturn(View.VISIBLE);
                    when(child.getParent()).thenReturn(mNssl);
                    break;
                default:
                    throw new RuntimeException("Unknown ChildType: " + entry.mChildType);
            }
            children.add(child);
        }
    }

    private static final StackEntry INCOMING_HEADER = new StackEntry(ChildType.INCOMING_HEADER);
    private static final StackEntry MEDIA_CONTROLS = new StackEntry(ChildType.MEDIA_CONTROLS);
    private static final StackEntry PEOPLE_HEADER = new StackEntry(ChildType.PEOPLE_HEADER);
    private static final StackEntry ALERTING_HEADER = new StackEntry(ChildType.ALERTING_HEADER);
    private static final StackEntry GENTLE_HEADER = new StackEntry(ChildType.GENTLE_HEADER);
    private static final StackEntry FSN = new StackEntry(ChildType.FSN);
    private static final StackEntry PERSON = new StackEntry(ChildType.PERSON);
    private static final StackEntry ALERTING = new StackEntry(ChildType.ALERTING);
    private static final StackEntry GENTLE = new StackEntry(ChildType.GENTLE);

    private static class StackEntry {
        final ChildType mChildType;
        final boolean mIsGone;

        StackEntry(ChildType childType) {
            this(childType, false);
        }

        StackEntry(ChildType childType, boolean isGone) {
            mChildType = childType;
            mIsGone = isGone;
        }

        public StackEntry gone() {
            return new StackEntry(mChildType, true);
        }
    }
}
