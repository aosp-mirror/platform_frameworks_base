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

import com.android.systemui.ActivityStarterDelegate;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
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
@TestableLooper.RunWithLooper
public class NotificationSectionsManagerTest extends SysuiTestCase {

    @Rule public final MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock private NotificationStackScrollLayout mNssl;
    @Mock private ActivityStarterDelegate mActivityStarterDelegate;
    @Mock private StatusBarStateController mStatusBarStateController;
    @Mock private ConfigurationController mConfigurationController;

    private NotificationSectionsManager mSectionsManager;

    @Before
    public void setUp() {
        mSectionsManager =
                new NotificationSectionsManager(
                        mNssl,
                        mActivityStarterDelegate,
                        mStatusBarStateController,
                        mConfigurationController,
                        true);
        // Required in order for the header inflation to work properly
        when(mNssl.generateLayoutParams(any(AttributeSet.class)))
                .thenReturn(new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        mSectionsManager.initialize(LayoutInflater.from(mContext));
        when(mNssl.indexOfChild(any(View.class))).thenReturn(-1);
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.SHADE);
    }

    @Test(expected =  IllegalStateException.class)
    public void testDuplicateInitializeThrows() {
        mSectionsManager.initialize(LayoutInflater.from(mContext));
    }

    @Test
    public void testInsertHeader() {
        // GIVEN a stack with HI and LO rows but no section headers
        setStackState(ChildType.HIPRI, ChildType.HIPRI, ChildType.HIPRI, ChildType.LOPRI);

        // WHEN we update the section headers
        mSectionsManager.updateSectionBoundaries();

        // THEN a LO section header is added
        verify(mNssl).addView(mSectionsManager.getGentleHeaderView(), 3);
    }

    @Test
    public void testRemoveHeader() {
        // GIVEN a stack that originally had a header between the HI and LO sections
        setStackState(ChildType.HIPRI, ChildType.HIPRI, ChildType.LOPRI);
        mSectionsManager.updateSectionBoundaries();

        // WHEN the last LO row is replaced with a HI row
        setStackState(ChildType.HIPRI, ChildType.HIPRI, ChildType.HEADER, ChildType.HIPRI);
        clearInvocations(mNssl);
        mSectionsManager.updateSectionBoundaries();

        // THEN the LO section header is removed
        verify(mNssl).removeView(mSectionsManager.getGentleHeaderView());
    }

    @Test
    public void testDoNothingIfHeaderAlreadyRemoved() {
        // GIVEN a stack with only HI rows
        setStackState(ChildType.HIPRI, ChildType.HIPRI, ChildType.HIPRI);

        // WHEN we update the sections headers
        mSectionsManager.updateSectionBoundaries();

        // THEN we don't add any section headers
        verify(mNssl, never()).addView(eq(mSectionsManager.getGentleHeaderView()), anyInt());
    }

    @Test
    public void testMoveHeaderForward() {
        // GIVEN a stack that originally had a header between the HI and LO sections
        setStackState(
                ChildType.HIPRI,
                ChildType.HIPRI,
                ChildType.HIPRI,
                ChildType.LOPRI);
        mSectionsManager.updateSectionBoundaries();

        // WHEN the LO section moves forward
        setStackState(
                ChildType.HIPRI,
                ChildType.HIPRI,
                ChildType.LOPRI,
                ChildType.HEADER,
                ChildType.LOPRI);
        mSectionsManager.updateSectionBoundaries();

        // THEN the LO section header is also moved forward
        verify(mNssl).changeViewPosition(mSectionsManager.getGentleHeaderView(), 2);
    }

    @Test
    public void testMoveHeaderBackward() {
        // GIVEN a stack that originally had a header between the HI and LO sections
        setStackState(
                ChildType.HIPRI,
                ChildType.LOPRI,
                ChildType.LOPRI,
                ChildType.LOPRI);
        mSectionsManager.updateSectionBoundaries();

        // WHEN the LO section moves backward
        setStackState(
                ChildType.HIPRI,
                ChildType.HEADER,
                ChildType.HIPRI,
                ChildType.HIPRI,
                ChildType.LOPRI);
        mSectionsManager.updateSectionBoundaries();

        // THEN the LO section header is also moved backward (with appropriate index shifting)
        verify(mNssl).changeViewPosition(mSectionsManager.getGentleHeaderView(), 3);
    }

    @Test
    public void testHeaderRemovedFromTransientParent() {
        // GIVEN a stack where the header is animating away
        setStackState(
                ChildType.HIPRI,
                ChildType.LOPRI,
                ChildType.LOPRI,
                ChildType.LOPRI);
        mSectionsManager.updateSectionBoundaries();
        setStackState(
                ChildType.HIPRI,
                ChildType.HEADER);
        mSectionsManager.updateSectionBoundaries();
        clearInvocations(mNssl);

        ViewGroup transientParent = mock(ViewGroup.class);
        mSectionsManager.getGentleHeaderView().setTransientContainer(transientParent);

        // WHEN the LO section reappears
        setStackState(
                ChildType.HIPRI,
                ChildType.LOPRI);
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
        setStackState(ChildType.HIPRI, ChildType.HIPRI, ChildType.HIPRI, ChildType.LOPRI);

        // WHEN we update the section headers
        mSectionsManager.updateSectionBoundaries();

        // Then the section header is not added
        verify(mNssl, never()).addView(eq(mSectionsManager.getGentleHeaderView()), anyInt());
    }

    @Test
    public void testHeaderShownWhenEnterLockscreen() {
        // GIVEN a stack of HI and LO notifs on the lockscreen
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.KEYGUARD);
        setStackState(ChildType.HIPRI, ChildType.HIPRI, ChildType.HIPRI, ChildType.LOPRI);
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
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.SHADE_LOCKED);
        setStackState(ChildType.HIPRI, ChildType.HIPRI, ChildType.HIPRI, ChildType.LOPRI);
        mSectionsManager.updateSectionBoundaries();

        // WHEN we go back to the keyguard
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.KEYGUARD);
        mSectionsManager.updateSectionBoundaries();

        // Then the section header is removed
        verify(mNssl).removeView(eq(mSectionsManager.getGentleHeaderView()));
    }

    private enum ChildType { HEADER, HIPRI, LOPRI }

    private void setStackState(ChildType... children) {
        when(mNssl.getChildCount()).thenReturn(children.length);
        for (int i = 0; i < children.length; i++) {
            View child;
            switch (children[i]) {
                case HEADER:
                    child = mSectionsManager.getGentleHeaderView();
                    break;
                case HIPRI:
                case LOPRI:
                    ExpandableNotificationRow notifRow = mock(ExpandableNotificationRow.class,
                            RETURNS_DEEP_STUBS);
                    when(notifRow.getVisibility()).thenReturn(View.VISIBLE);
                    when(notifRow.getEntry().isHighPriority())
                            .thenReturn(children[i] == ChildType.HIPRI);
                    when(notifRow.getEntry().isTopBucket())
                            .thenReturn(children[i] == ChildType.HIPRI);
                    when(notifRow.getParent()).thenReturn(mNssl);
                    child = notifRow;
                    break;
                default:
                    throw new RuntimeException("Unknown ChildType: " + children[i]);
            }
            when(mNssl.getChildAt(i)).thenReturn(child);
            when(mNssl.indexOfChild(child)).thenReturn(i);
        }
    }
}
