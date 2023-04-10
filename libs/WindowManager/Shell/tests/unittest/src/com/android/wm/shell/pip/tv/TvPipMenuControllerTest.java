/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.wm.shell.pip.tv;

import static android.view.KeyEvent.KEYCODE_DPAD_UP;

import static com.android.wm.shell.pip.tv.TvPipMenuController.MODE_ALL_ACTIONS_MENU;
import static com.android.wm.shell.pip.tv.TvPipMenuController.MODE_MOVE_MENU;
import static com.android.wm.shell.pip.tv.TvPipMenuController.MODE_NO_MENU;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.os.Handler;
import android.view.SurfaceControl;

import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.common.SystemWindows;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class TvPipMenuControllerTest extends ShellTestCase {
    private static final int TEST_MOVE_KEYCODE = KEYCODE_DPAD_UP;

    @Mock
    private TvPipMenuController.Delegate mMockDelegate;
    @Mock
    private TvPipBoundsState mMockTvPipBoundsState;
    @Mock
    private SystemWindows mMockSystemWindows;
    @Mock
    private SurfaceControl mMockPipLeash;
    @Mock
    private Handler mMockHandler;
    @Mock
    private TvPipActionsProvider mMockActionsProvider;
    @Mock
    private TvPipMenuView mMockTvPipMenuView;
    @Mock
    private TvPipBackgroundView mMockTvPipBackgroundView;

    private TvPipMenuController mTvPipMenuController;

    @Before
    public void setUp() {
        assumeTrue(isTelevision());

        MockitoAnnotations.initMocks(this);

        mTvPipMenuController = new TestTvPipMenuController();
        mTvPipMenuController.setDelegate(mMockDelegate);
        mTvPipMenuController.setTvPipActionsProvider(mMockActionsProvider);
        mTvPipMenuController.attach(mMockPipLeash);
    }

    @Test
    public void testMenuNotOpenByDefault() {
        assertMenuIsOpen(false);
    }

    @Test
    public void testSwitch_FromNoMenuMode_ToMoveMode() {
        showAndAssertMoveMenu();
    }

    @Test
    public void testSwitch_FromNoMenuMode_ToAllActionsMode() {
        showAndAssertAllActionsMenu();
    }

    @Test
    public void testSwitch_FromMoveMode_ToAllActionsMode() {
        showAndAssertMoveMenu();
        showAndAssertAllActionsMenu();
    }

    @Test
    public void testSwitch_FromAllActionsMode_ToMoveMode() {
        showAndAssertAllActionsMenu();
        showAndAssertMoveMenu();
    }

    @Test
    public void testCloseMenu_NoMenuMode() {
        mTvPipMenuController.closeMenu();
        assertMenuIsOpen(false);
        verify(mMockDelegate, never()).onMenuClosed();
    }

    @Test
    public void testCloseMenu_MoveMode() {
        showAndAssertMoveMenu();

        closeMenuAndAssertMenuClosed();
        verify(mMockDelegate, times(2)).onInMoveModeChanged();
    }

    @Test
    public void testCloseMenu_AllActionsMode() {
        showAndAssertAllActionsMenu();

        closeMenuAndAssertMenuClosed();
    }

    @Test
    public void testCloseMenu_MoveModeFollowedByAllActionsMode() {
        showAndAssertMoveMenu();
        showAndAssertAllActionsMenu();
        verify(mMockDelegate, times(2)).onInMoveModeChanged();

        closeMenuAndAssertMenuClosed();
    }

    @Test
    public void testCloseMenu_AllActionsModeFollowedByMoveMode() {
        showAndAssertAllActionsMenu();
        showAndAssertMoveMenu();

        closeMenuAndAssertMenuClosed();
        verify(mMockDelegate, times(2)).onInMoveModeChanged();
    }

    @Test
    public void testExitMoveMode_NoMenuMode() {
        mTvPipMenuController.onExitMoveMode();
        assertMenuIsOpen(false);
        verify(mMockDelegate, never()).onMenuClosed();
    }

    @Test
    public void testExitMoveMode_MoveMode() {
        showAndAssertMoveMenu();

        mTvPipMenuController.onExitMoveMode();
        assertMenuClosed();
        verify(mMockDelegate, times(2)).onInMoveModeChanged();
    }

    @Test
    public void testExitMoveMode_AllActionsMode() {
        showAndAssertAllActionsMenu();

        mTvPipMenuController.onExitMoveMode();
        assertMenuIsInAllActionsMode();

    }

    @Test
    public void testExitMoveMode_AllActionsModeFollowedByMoveMode() {
        showAndAssertAllActionsMenu();
        showAndAssertMoveMenu();

        mTvPipMenuController.onExitMoveMode();
        assertMenuIsInAllActionsMode();
        verify(mMockDelegate, times(2)).onInMoveModeChanged();
        verify(mMockTvPipMenuView).transitionToMenuMode(eq(MODE_ALL_ACTIONS_MENU), eq(false));
        verify(mMockTvPipBackgroundView, times(2)).transitionToMenuMode(eq(MODE_ALL_ACTIONS_MENU));
    }

    @Test
    public void testOnBackPress_NoMenuMode() {
        mTvPipMenuController.onBackPress();
        assertMenuIsOpen(false);
        verify(mMockDelegate, never()).onMenuClosed();
    }

    @Test
    public void testOnBackPress_MoveMode() {
        showAndAssertMoveMenu();

        pressBackAndAssertMenuClosed();
        verify(mMockDelegate, times(2)).onInMoveModeChanged();
    }

    @Test
    public void testOnBackPress_AllActionsMode() {
        showAndAssertAllActionsMenu();

        pressBackAndAssertMenuClosed();
    }

    @Test
    public void testOnBackPress_MoveModeFollowedByAllActionsMode() {
        showAndAssertMoveMenu();
        showAndAssertAllActionsMenu();
        verify(mMockDelegate, times(2)).onInMoveModeChanged();

        pressBackAndAssertMenuClosed();
    }

    @Test
    public void testOnBackPress_AllActionsModeFollowedByMoveMode() {
        showAndAssertAllActionsMenu();
        showAndAssertMoveMenu();

        mTvPipMenuController.onBackPress();
        assertMenuIsInAllActionsMode();
        verify(mMockDelegate, times(2)).onInMoveModeChanged();
        verify(mMockTvPipMenuView).transitionToMenuMode(eq(MODE_ALL_ACTIONS_MENU), eq(false));
        verify(mMockTvPipBackgroundView, times(2)).transitionToMenuMode(eq(MODE_ALL_ACTIONS_MENU));

        pressBackAndAssertMenuClosed();
    }

    @Test
    public void testOnPipMovement_NoMenuMode() {
        assertPipMoveSuccessful(false, mTvPipMenuController.onPipMovement(TEST_MOVE_KEYCODE));
    }

    @Test
    public void testOnPipMovement_MoveMode() {
        showAndAssertMoveMenu();
        assertPipMoveSuccessful(true, mTvPipMenuController.onPipMovement(TEST_MOVE_KEYCODE));
        verify(mMockDelegate).movePip(eq(TEST_MOVE_KEYCODE));
    }

    @Test
    public void testOnPipMovement_AllActionsMode() {
        showAndAssertAllActionsMenu();
        assertPipMoveSuccessful(false, mTvPipMenuController.onPipMovement(TEST_MOVE_KEYCODE));
    }

    @Test
    public void testOnPipWindowFocusChanged_NoMenuMode() {
        mTvPipMenuController.onPipWindowFocusChanged(false);
        assertMenuIsOpen(false);
    }

    @Test
    public void testOnPipWindowFocusChanged_MoveMode() {
        showAndAssertMoveMenu();
        mTvPipMenuController.onPipWindowFocusChanged(false);
        assertMenuClosed();
    }

    @Test
    public void testOnPipWindowFocusChanged_AllActionsMode() {
        showAndAssertAllActionsMenu();
        mTvPipMenuController.onPipWindowFocusChanged(false);
        assertMenuClosed();
    }

    private void showAndAssertMoveMenu() {
        mTvPipMenuController.showMovementMenu();
        assertMenuIsInMoveMode();
        verify(mMockDelegate).onInMoveModeChanged();
        verify(mMockTvPipMenuView).transitionToMenuMode(eq(MODE_MOVE_MENU), eq(false));
        verify(mMockTvPipBackgroundView).transitionToMenuMode(eq(MODE_MOVE_MENU));
    }

    private void showAndAssertAllActionsMenu() {
        mTvPipMenuController.showMenu();
        assertMenuIsInAllActionsMode();
        verify(mMockTvPipMenuView).transitionToMenuMode(eq(MODE_ALL_ACTIONS_MENU), eq(true));
        verify(mMockTvPipBackgroundView).transitionToMenuMode(eq(MODE_ALL_ACTIONS_MENU));
    }

    private void closeMenuAndAssertMenuClosed() {
        mTvPipMenuController.closeMenu();
        assertMenuClosed();
    }

    private void pressBackAndAssertMenuClosed() {
        mTvPipMenuController.onBackPress();
        assertMenuClosed();
    }

    private void assertMenuClosed() {
        assertMenuIsOpen(false);
        verify(mMockDelegate).onMenuClosed();
        verify(mMockTvPipMenuView).transitionToMenuMode(eq(MODE_NO_MENU), eq(false));
        verify(mMockTvPipBackgroundView).transitionToMenuMode(eq(MODE_NO_MENU));
    }

    private void assertMenuIsOpen(boolean open) {
        assertTrue("The TV PiP menu should " + (open ? "" : "not ") + "be open, but it"
                + " is in mode " + mTvPipMenuController.getMenuModeString(),
                mTvPipMenuController.isMenuOpen() == open);
    }

    private void assertMenuIsInMoveMode() {
        assertTrue("Expected MODE_MOVE_MENU, but got " + mTvPipMenuController.getMenuModeString(),
                mTvPipMenuController.isInMoveMode());
        assertMenuIsOpen(true);
    }

    private void assertMenuIsInAllActionsMode() {
        assertTrue("Expected MODE_ALL_ACTIONS_MENU, but got "
                + mTvPipMenuController.getMenuModeString(),
                mTvPipMenuController.isInAllActionsMode());
        assertMenuIsOpen(true);
    }

    private void assertPipMoveSuccessful(boolean expected, boolean actual) {
        assertTrue("Should " + (expected ? "" : "not ") + "move PiP when the menu is in mode "
                + mTvPipMenuController.getMenuModeString(), expected == actual);
    }

    private class TestTvPipMenuController extends TvPipMenuController {

        TestTvPipMenuController() {
            super(mContext, mMockTvPipBoundsState, mMockSystemWindows, mMockHandler);
        }

        @Override
        TvPipMenuView createTvPipMenuView() {
            return mMockTvPipMenuView;
        }

        @Override
        TvPipBackgroundView createTvPipBackgroundView() {
            return mMockTvPipBackgroundView;
        }
    }
}
