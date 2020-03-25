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
import static com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_HEADS_UP;
import static com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_PEOPLE;
import static com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_SILENT;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
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

import com.android.keyguard.KeyguardMediaPlayer;
import com.android.systemui.ActivityStarterDelegate;
import com.android.systemui.SysuiTestCase;
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
    @Mock private KeyguardMediaPlayer mKeyguardMediaPlayer;
    @Mock private NotificationSectionsFeatureManager mSectionsFeatureManager;
    @Mock private NotificationRowComponent mNotificationRowComponent;
    @Mock private ActivatableNotificationViewController mActivatableNotificationViewController;

    private NotificationSectionsManager mSectionsManager;

    @Before
    public void setUp() {
        when(mSectionsFeatureManager.getNumberOfBuckets()).thenReturn(2);
        when(mNotificationRowComponent.getActivatableNotificationViewController()).thenReturn(
                mActivatableNotificationViewController
        );
        mSectionsManager =
                new NotificationSectionsManager(
                        mActivityStarterDelegate,
                        mStatusBarStateController,
                        mConfigurationController,
                        mPeopleHubAdapter,
                        mKeyguardMediaPlayer,
                        mSectionsFeatureManager
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
        setStackState(ChildType.HEADS_UP, ChildType.ALERTING, ChildType.GENTLE_HEADER,
                ChildType.GENTLE);

        // WHEN we go back to the keyguard
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.KEYGUARD);
        mSectionsManager.updateSectionBoundaries();

        // Then the media controls are added after HEADS_UP
        verify(mNssl).addView(mSectionsManager.getMediaControlsView(), 1);
    }

    @Test
    public void testMediaControls_RemoveWhenExitKeyguard() {
        enableMediaControls();

        // GIVEN a stack with media controls
        setStackState(ChildType.MEDIA_CONTROLS, ChildType.ALERTING, ChildType.GENTLE_HEADER,
                ChildType.GENTLE);

        // WHEN we leave the keyguard
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.SHADE);
        mSectionsManager.updateSectionBoundaries();

        // Then the media controls is removed
        verify(mNssl).removeView(mSectionsManager.getMediaControlsView());
    }

    @Test
    public void testMediaControls_RemoveWhenPullDownShade() {
        enableMediaControls();

        // GIVEN a stack with media controls
        setStackState(ChildType.MEDIA_CONTROLS, ChildType.ALERTING, ChildType.GENTLE_HEADER,
                ChildType.GENTLE);

        // WHEN we pull down the shade on the keyguard
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.SHADE_LOCKED);
        mSectionsManager.updateSectionBoundaries();

        // Then the media controls is removed
        verify(mNssl).removeView(mSectionsManager.getMediaControlsView());
    }

    private void enablePeopleFiltering() {
        when(mSectionsFeatureManager.isFilteringEnabled()).thenReturn(true);
        when(mSectionsFeatureManager.getNumberOfBuckets()).thenReturn(4);
    }

    private void enableMediaControls() {
        when(mSectionsFeatureManager.isMediaControlsEnabled()).thenReturn(true);
        when(mSectionsFeatureManager.getNumberOfBuckets()).thenReturn(4);
    }

    private enum ChildType {
        MEDIA_CONTROLS, PEOPLE_HEADER, ALERTING_HEADER, GENTLE_HEADER, HEADS_UP, PERSON, ALERTING,
            GENTLE, OTHER
    }

    private void setStackState(ChildType... children) {
        when(mNssl.getChildCount()).thenReturn(children.length);
        for (int i = 0; i < children.length; i++) {
            View child;
            switch (children[i]) {
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
}
