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

import static com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_ALERTING;
import static com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_FOREGROUND_SERVICE;
import static com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_HEADS_UP;
import static com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_PEOPLE;
import static com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_SILENT;

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
        setStackState(ChildType.ALERTING, ChildType.ALERTING, ChildType.ALERTING, ChildType.GENTLE);

        // WHEN we update the section headers
        mSectionsManager.updateSectionBoundaries();

        // THEN a LO section header is added
        verify(mNssl).addView(mSectionsManager.getGentleHeaderView(), 3);
    }

    @Test
    public void testRemoveHeader() {
        // GIVEN a stack that originally had a header between the HI and LO sections
        setStackState(ChildType.ALERTING, ChildType.ALERTING, ChildType.GENTLE);
        mSectionsManager.updateSectionBoundaries();

        // WHEN the last LO row is replaced with a HI row
        setStackState(
                ChildType.ALERTING,
                ChildType.ALERTING,
                ChildType.GENTLE_HEADER,
                ChildType.ALERTING);
        clearInvocations(mNssl);
        mSectionsManager.updateSectionBoundaries();

        // THEN the LO section header is removed
        verify(mNssl).removeView(mSectionsManager.getGentleHeaderView());
    }

    @Test
    public void testDoNothingIfHeaderAlreadyRemoved() {
        // GIVEN a stack with only HI rows
        setStackState(ChildType.ALERTING, ChildType.ALERTING, ChildType.ALERTING);

        // WHEN we update the sections headers
        mSectionsManager.updateSectionBoundaries();

        // THEN we don't add any section headers
        verify(mNssl, never()).addView(eq(mSectionsManager.getGentleHeaderView()), anyInt());
    }

    @Test
    public void testMoveHeaderForward() {
        // GIVEN a stack that originally had a header between the HI and LO sections
        setStackState(
                ChildType.ALERTING,
                ChildType.ALERTING,
                ChildType.ALERTING,
                ChildType.GENTLE);
        mSectionsManager.updateSectionBoundaries();

        // WHEN the LO section moves forward
        setStackState(
                ChildType.ALERTING,
                ChildType.ALERTING,
                ChildType.GENTLE,
                ChildType.GENTLE_HEADER,
                ChildType.GENTLE);
        mSectionsManager.updateSectionBoundaries();

        // THEN the LO section header is also moved forward
        verify(mNssl).changeViewPosition(mSectionsManager.getGentleHeaderView(), 2);
    }

    @Test
    public void testMoveHeaderBackward() {
        // GIVEN a stack that originally had a header between the HI and LO sections
        setStackState(
                ChildType.ALERTING,
                ChildType.GENTLE,
                ChildType.GENTLE,
                ChildType.GENTLE);
        mSectionsManager.updateSectionBoundaries();

        // WHEN the LO section moves backward
        setStackState(
                ChildType.ALERTING,
                ChildType.GENTLE_HEADER,
                ChildType.ALERTING,
                ChildType.ALERTING,
                ChildType.GENTLE);
        mSectionsManager.updateSectionBoundaries();

        // THEN the LO section header is also moved backward (with appropriate index shifting)
        verify(mNssl).changeViewPosition(mSectionsManager.getGentleHeaderView(), 3);
    }

    @Test
    public void testHeaderRemovedFromTransientParent() {
        // GIVEN a stack where the header is animating away
        setStackState(
                ChildType.ALERTING,
                ChildType.GENTLE,
                ChildType.GENTLE,
                ChildType.GENTLE);
        mSectionsManager.updateSectionBoundaries();
        setStackState(
                ChildType.ALERTING,
                ChildType.GENTLE_HEADER);
        mSectionsManager.updateSectionBoundaries();
        clearInvocations(mNssl);

        ViewGroup transientParent = mock(ViewGroup.class);
        mSectionsManager.getGentleHeaderView().setTransientContainer(transientParent);

        // WHEN the LO section reappears
        setStackState(
                ChildType.ALERTING,
                ChildType.GENTLE);
        mSectionsManager.updateSectionBoundaries();

        // THEN the header is first removed from the transient parent before being added to the
        // NSSL.
        verify(transientParent).removeTransientView(mSectionsManager.getGentleHeaderView());
        verify(mNssl).addView(mSectionsManager.getGentleHeaderView(), 1);
    }

    @Test
    public void testHeaderNotShownOnLockscreen() {
        // GIVEN a stack of HI and LO notifs on the lockscreen
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.KEYGUARD);
        setStackState(ChildType.ALERTING, ChildType.ALERTING, ChildType.ALERTING, ChildType.GENTLE);

        // WHEN we update the section headers
        mSectionsManager.updateSectionBoundaries();

        // Then the section header is not added
        verify(mNssl, never()).addView(eq(mSectionsManager.getGentleHeaderView()), anyInt());
    }

    @Test
    public void testHeaderShownWhenEnterLockscreen() {
        // GIVEN a stack of HI and LO notifs on the lockscreen
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.KEYGUARD);
        setStackState(ChildType.ALERTING, ChildType.ALERTING, ChildType.ALERTING, ChildType.GENTLE);
        mSectionsManager.updateSectionBoundaries();

        // WHEN we unlock
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.SHADE);
        mSectionsManager.updateSectionBoundaries();

        // Then the section header is added
        verify(mNssl).addView(mSectionsManager.getGentleHeaderView(), 3);
    }

    @Test
    public void testHeaderHiddenWhenEnterLockscreen() {
        // GIVEN a stack of HI and LO notifs on the shade
        setStackState(ChildType.ALERTING, ChildType.GENTLE_HEADER, ChildType.GENTLE);

        // WHEN we go back to the keyguard
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.KEYGUARD);
        mSectionsManager.updateSectionBoundaries();

        // Then the section header is removed
        verify(mNssl).removeView(mSectionsManager.getGentleHeaderView());
    }

    @Test
    public void testPeopleFiltering_addHeadersFromShowingOnlyGentle() {
        enablePeopleFiltering();

        setStackState(
                ChildType.GENTLE_HEADER,
                ChildType.PERSON,
                ChildType.ALERTING,
                ChildType.GENTLE);
        mSectionsManager.updateSectionBoundaries();

        verify(mNssl).changeViewPosition(mSectionsManager.getGentleHeaderView(), 2);
        verify(mNssl).addView(mSectionsManager.getAlertingHeaderView(), 1);
        verify(mNssl).addView(mSectionsManager.getPeopleHeaderView(), 0);
    }

    @Test
    public void testPeopleFiltering_addAllHeaders() {
        enablePeopleFiltering();

        setStackState(
                ChildType.PERSON,
                ChildType.ALERTING,
                ChildType.GENTLE);
        mSectionsManager.updateSectionBoundaries();

        verify(mNssl).addView(mSectionsManager.getGentleHeaderView(), 2);
        verify(mNssl).addView(mSectionsManager.getAlertingHeaderView(), 1);
        verify(mNssl).addView(mSectionsManager.getPeopleHeaderView(), 0);
    }

    @Test
    public void testPeopleFiltering_moveAllHeaders() {
        enablePeopleFiltering();

        setStackState(
                ChildType.PEOPLE_HEADER,
                ChildType.ALERTING_HEADER,
                ChildType.GENTLE_HEADER,
                ChildType.PERSON,
                ChildType.ALERTING,
                ChildType.GENTLE);
        mSectionsManager.updateSectionBoundaries();

        verify(mNssl).changeViewPosition(mSectionsManager.getGentleHeaderView(), 4);
        verify(mNssl).changeViewPosition(mSectionsManager.getAlertingHeaderView(), 2);
        verify(mNssl).changeViewPosition(mSectionsManager.getPeopleHeaderView(), 0);
    }

    @Test
    public void testPeopleFiltering_keepPeopleHeaderWhenSectionEmpty() {
        mSectionsManager.setPeopleHubVisible(true);
        enablePeopleFiltering();

        setStackState(
                ChildType.PEOPLE_HEADER,
                ChildType.ALERTING_HEADER,
                ChildType.ALERTING,
                ChildType.GENTLE_HEADER,
                ChildType.GENTLE
        );
        mSectionsManager.updateSectionBoundaries();

        verify(mNssl, never()).removeView(mSectionsManager.getPeopleHeaderView());
        verify(mNssl).changeViewPosition(mSectionsManager.getPeopleHeaderView(), 0);
    }

    @Test
    public void testPeopleFiltering_HunWhilePeopleVisible() {
        enablePeopleFiltering();

        setupMockStack(
                ChildType.PEOPLE_HEADER,
                ChildType.HEADS_UP,
                ChildType.PERSON,
                ChildType.ALERTING_HEADER,
                ChildType.GENTLE_HEADER,
                ChildType.GENTLE
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
    public void testPeopleFiltering_Fsn() {
        enablePeopleFiltering();

        setupMockStack(
                ChildType.INCOMING_HEADER,
                ChildType.HEADS_UP,
                ChildType.PEOPLE_HEADER,
                ChildType.FSN,
                ChildType.PERSON,
                ChildType.ALERTING,
                ChildType.GENTLE
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
        setStackState(ChildType.ALERTING, ChildType.GENTLE_HEADER, ChildType.GENTLE);

        // WHEN we go back to the keyguard
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.KEYGUARD);
        mSectionsManager.updateSectionBoundaries();

        // Then the media controls are added
        verify(mNssl).addView(mSectionsManager.getMediaControlsView(), 0);
    }

    @Test
    public void testMediaControls_AddWhenEnterKeyguardWithHeadsUp() {
        enableMediaControls();

        // GIVEN a stack that doesn't include media controls but includes HEADS_UP
        setupMockStack(ChildType.HEADS_UP, ChildType.ALERTING, ChildType.GENTLE_HEADER,
                ChildType.GENTLE);

        // WHEN we go back to the keyguard
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.KEYGUARD);
        mSectionsManager.updateSectionBoundaries();

        verifyMockStack(ChildType.HEADS_UP, ChildType.MEDIA_CONTROLS, ChildType.ALERTING,
                ChildType.GENTLE);
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

    private void setStackState(ChildType... children) {
        when(mNssl.getChildCount()).thenReturn(children.length);
        for (int i = 0; i < children.length; i++) {
            View child;
            switch (children[i]) {
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
                    child = mSectionsManager.getGentleHeaderView();
                    break;
                case HEADS_UP:
                    child = mockNotification(BUCKET_HEADS_UP);
                    break;
                case FSN:
                    child = mockNotification(BUCKET_FOREGROUND_SERVICE);
                    break;
                case PERSON:
                    child = mockNotification(BUCKET_PEOPLE);
                    break;
                case ALERTING:
                    child = mockNotification(BUCKET_ALERTING);
                    break;
                case GENTLE:
                    child = mockNotification(BUCKET_SILENT);
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

    private View mockNotification(int bucket) {
        ExpandableNotificationRow notifRow = mock(ExpandableNotificationRow.class,
                RETURNS_DEEP_STUBS);
        when(notifRow.getVisibility()).thenReturn(View.VISIBLE);
        when(notifRow.getEntry().getBucket()).thenReturn(bucket);
        when(notifRow.getParent()).thenReturn(mNssl);
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
            if (child == mSectionsManager.getGentleHeaderView()) {
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

    private void setupMockStack(ChildType... childTypes) {
        final List<View> children = new ArrayList<>();
        when(mNssl.getChildCount()).thenAnswer(invocation -> children.size());
        when(mNssl.getChildAt(anyInt()))
                .thenAnswer(invocation -> children.get(invocation.getArgument(0)));
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
        for (ChildType childType : childTypes) {
            View child;
            switch (childType) {
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
                    child = mSectionsManager.getGentleHeaderView();
                    break;
                case HEADS_UP:
                    child = mockNotification(BUCKET_HEADS_UP);
                    break;
                case FSN:
                    child = mockNotification(BUCKET_FOREGROUND_SERVICE);
                    break;
                case PERSON:
                    child = mockNotification(BUCKET_PEOPLE);
                    break;
                case ALERTING:
                    child = mockNotification(BUCKET_ALERTING);
                    break;
                case GENTLE:
                    child = mockNotification(BUCKET_SILENT);
                    break;
                case OTHER:
                    child = mock(View.class);
                    when(child.getVisibility()).thenReturn(View.VISIBLE);
                    when(child.getParent()).thenReturn(mNssl);
                    break;
                default:
                    throw new RuntimeException("Unknown ChildType: " + childType);
            }
            children.add(child);
        }
    }
}
