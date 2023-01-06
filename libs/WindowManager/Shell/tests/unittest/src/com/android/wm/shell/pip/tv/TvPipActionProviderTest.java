/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.wm.shell.pip.tv.TvPipAction.ACTION_CLOSE;
import static com.android.wm.shell.pip.tv.TvPipAction.ACTION_CUSTOM;
import static com.android.wm.shell.pip.tv.TvPipAction.ACTION_CUSTOM_CLOSE;
import static com.android.wm.shell.pip.tv.TvPipAction.ACTION_EXPAND_COLLAPSE;
import static com.android.wm.shell.pip.tv.TvPipAction.ACTION_FULLSCREEN;
import static com.android.wm.shell.pip.tv.TvPipAction.ACTION_MOVE;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.PendingIntent;
import android.app.RemoteAction;
import android.graphics.drawable.Icon;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.Log;

import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.pip.PipMediaController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link TvPipActionsProvider}
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class TvPipActionProviderTest extends ShellTestCase {
    private static final String TAG = TvPipActionProviderTest.class.getSimpleName();
    private TvPipActionsProvider mActionsProvider;

    @Mock
    private PipMediaController mMockPipMediaController;
    @Mock
    private TvPipActionsProvider.Listener mMockListener;
    @Mock
    private TvPipAction.SystemActionsHandler mMockSystemActionsHandler;
    @Mock
    private Icon mMockIcon;
    @Mock
    private PendingIntent mMockPendingIntent;

    private RemoteAction createRemoteAction(int identifier) {
        return new RemoteAction(mMockIcon, "" + identifier, "" + identifier, mMockPendingIntent);
    }

    private List<RemoteAction> createRemoteActions(int numberOfActions) {
        List<RemoteAction> actions = new ArrayList<>();
        for (int i = 0; i < numberOfActions; i++) {
            actions.add(createRemoteAction(i));
        }
        return actions;
    }

    private boolean checkActionsMatch(List<TvPipAction> actions, int[] actionTypes) {
        for (int i = 0; i < actions.size(); i++) {
            int type = actions.get(i).getActionType();
            if (type != actionTypes[i]) {
                Log.e(TAG, "Action at index " + i + ": found " + type
                        + ", expected " + actionTypes[i]);
                return false;
            }
        }
        return true;
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mActionsProvider = new TvPipActionsProvider(mContext, mMockPipMediaController,
                mMockSystemActionsHandler);
    }

    @Test
    public void defaultSystemActions_regularPip() {
        assumeTelevision();
        mActionsProvider.updateExpansionEnabled(false);
        assertTrue(checkActionsMatch(mActionsProvider.getActionsList(),
                new int[]{ACTION_FULLSCREEN, ACTION_CLOSE, ACTION_MOVE}));
    }

    @Test
    public void defaultSystemActions_expandedPip() {
        assumeTelevision();
        mActionsProvider.updateExpansionEnabled(true);
        assertTrue(checkActionsMatch(mActionsProvider.getActionsList(),
                new int[]{ACTION_FULLSCREEN, ACTION_CLOSE, ACTION_MOVE, ACTION_EXPAND_COLLAPSE}));
    }

    @Test
    public void expandedPip_enableExpansion_enable() {
        assumeTelevision();
        // PiP has expanded PiP disabled.
        mActionsProvider.updateExpansionEnabled(false);

        mActionsProvider.addListener(mMockListener);
        mActionsProvider.updateExpansionEnabled(true);

        assertTrue(checkActionsMatch(mActionsProvider.getActionsList(),
                new int[]{ACTION_FULLSCREEN, ACTION_CLOSE, ACTION_MOVE, ACTION_EXPAND_COLLAPSE}));
        verify(mMockListener).onActionsChanged(/* added= */ 1, /* updated= */ 0, /* index= */ 3);
    }

    @Test
    public void expandedPip_enableExpansion_disable() {
        assumeTelevision();
        mActionsProvider.updateExpansionEnabled(true);

        mActionsProvider.addListener(mMockListener);
        mActionsProvider.updateExpansionEnabled(false);

        assertTrue(checkActionsMatch(mActionsProvider.getActionsList(),
                new int[]{ACTION_FULLSCREEN, ACTION_CLOSE, ACTION_MOVE}));
        verify(mMockListener).onActionsChanged(/* added= */ -1, /* updated= */ 0, /* index= */ 3);
    }

    @Test
    public void expandedPip_enableExpansion_AlreadyEnabled() {
        assumeTelevision();
        mActionsProvider.updateExpansionEnabled(true);

        mActionsProvider.addListener(mMockListener);
        mActionsProvider.updateExpansionEnabled(true);

        assertTrue(checkActionsMatch(mActionsProvider.getActionsList(),
                new int[]{ACTION_FULLSCREEN, ACTION_CLOSE, ACTION_MOVE, ACTION_EXPAND_COLLAPSE}));
    }

    @Test
    public void expandedPip_toggleExpansion() {
        assumeTelevision();
        // PiP has expanded PiP enabled, but is in a collapsed state
        mActionsProvider.updateExpansionEnabled(true);
        mActionsProvider.onPipExpansionToggled(/* expanded= */ false);

        mActionsProvider.addListener(mMockListener);
        mActionsProvider.onPipExpansionToggled(/* expanded= */ true);

        assertTrue(checkActionsMatch(mActionsProvider.getActionsList(),
                new int[]{ACTION_FULLSCREEN, ACTION_CLOSE, ACTION_MOVE, ACTION_EXPAND_COLLAPSE}));
        verify(mMockListener).onActionsChanged(0, 1, 3);
    }

    @Test
    public void customActions_added() {
        assumeTelevision();
        mActionsProvider.updateExpansionEnabled(false);
        mActionsProvider.addListener(mMockListener);

        mActionsProvider.setAppActions(createRemoteActions(2), null);

        assertTrue(checkActionsMatch(mActionsProvider.getActionsList(),
                new int[]{ACTION_FULLSCREEN, ACTION_CLOSE, ACTION_CUSTOM, ACTION_CUSTOM,
                        ACTION_MOVE}));
        verify(mMockListener).onActionsChanged(/* added= */ 2, /* updated= */ 0, /* index= */ 2);
    }

    @Test
    public void customActions_replacedMore() {
        assumeTelevision();
        mActionsProvider.updateExpansionEnabled(false);
        mActionsProvider.setAppActions(createRemoteActions(2), null);

        mActionsProvider.addListener(mMockListener);
        mActionsProvider.setAppActions(createRemoteActions(3), null);

        assertTrue(checkActionsMatch(mActionsProvider.getActionsList(),
                new int[]{ACTION_FULLSCREEN, ACTION_CLOSE, ACTION_CUSTOM, ACTION_CUSTOM,
                        ACTION_CUSTOM, ACTION_MOVE}));
        verify(mMockListener).onActionsChanged(/* added= */ 1, /* updated= */ 2, /* index= */ 2);
    }

    @Test
    public void customActions_replacedLess() {
        assumeTelevision();
        mActionsProvider.updateExpansionEnabled(false);
        mActionsProvider.setAppActions(createRemoteActions(2), null);

        mActionsProvider.addListener(mMockListener);
        mActionsProvider.setAppActions(createRemoteActions(0), null);

        assertTrue(checkActionsMatch(mActionsProvider.getActionsList(),
                new int[]{ACTION_FULLSCREEN, ACTION_CLOSE, ACTION_MOVE}));
        verify(mMockListener).onActionsChanged(/* added= */ -2, /* updated= */ 0, /* index= */ 2);
    }

    @Test
    public void customCloseAdded() {
        assumeTelevision();
        mActionsProvider.updateExpansionEnabled(false);

        List<RemoteAction> customActions = new ArrayList<>();
        mActionsProvider.setAppActions(customActions, null);

        mActionsProvider.addListener(mMockListener);
        mActionsProvider.setAppActions(customActions, createRemoteAction(0));

        assertTrue(checkActionsMatch(mActionsProvider.getActionsList(),
                new int[]{ACTION_FULLSCREEN, ACTION_CUSTOM_CLOSE, ACTION_MOVE}));
        verify(mMockListener).onActionsChanged(/* added= */ 0, /* updated= */ 1, /* index= */ 1);
    }

    @Test
    public void customClose_matchesOtherCustomAction() {
        assumeTelevision();
        mActionsProvider.updateExpansionEnabled(false);

        List<RemoteAction> customActions = createRemoteActions(2);
        RemoteAction customClose = createRemoteAction(/* id= */ 10);
        customActions.add(customClose);

        mActionsProvider.addListener(mMockListener);
        mActionsProvider.setAppActions(customActions, customClose);

        assertTrue(checkActionsMatch(mActionsProvider.getActionsList(),
                new int[]{ACTION_FULLSCREEN, ACTION_CUSTOM_CLOSE, ACTION_CUSTOM, ACTION_CUSTOM,
                        ACTION_MOVE}));
        verify(mMockListener).onActionsChanged(/* added= */ 0, /* updated= */ 1, /* index= */ 1);
        verify(mMockListener).onActionsChanged(/* added= */ 2, /* updated= */ 0, /* index= */ 2);
    }

    @Test
    public void mediaActions_added_whileCustomActionsExist() {
        assumeTelevision();
        mActionsProvider.updateExpansionEnabled(false);
        mActionsProvider.setAppActions(createRemoteActions(2), null);

        mActionsProvider.addListener(mMockListener);
        mActionsProvider.onMediaActionsChanged(createRemoteActions(3));

        assertTrue(checkActionsMatch(mActionsProvider.getActionsList(),
                new int[]{ACTION_FULLSCREEN, ACTION_CLOSE, ACTION_CUSTOM, ACTION_CUSTOM,
                        ACTION_MOVE}));
        verify(mMockListener, times(0)).onActionsChanged(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void customActions_removed_whileMediaActionsExist() {
        assumeTelevision();
        mActionsProvider.updateExpansionEnabled(false);
        mActionsProvider.onMediaActionsChanged(createRemoteActions(2));
        mActionsProvider.setAppActions(createRemoteActions(3), null);

        mActionsProvider.addListener(mMockListener);
        mActionsProvider.setAppActions(createRemoteActions(0), null);

        assertTrue(checkActionsMatch(mActionsProvider.getActionsList(),
                new int[]{ACTION_FULLSCREEN, ACTION_CLOSE, ACTION_CUSTOM, ACTION_CUSTOM,
                        ACTION_MOVE}));
        verify(mMockListener).onActionsChanged(/* added= */ -1, /* updated= */ 2, /* index= */ 2);
    }

    @Test
    public void customCloseOnly_mediaActionsShowing() {
        assumeTelevision();
        mActionsProvider.updateExpansionEnabled(false);
        mActionsProvider.onMediaActionsChanged(createRemoteActions(2));

        mActionsProvider.addListener(mMockListener);
        mActionsProvider.setAppActions(createRemoteActions(0), createRemoteAction(5));

        assertTrue(checkActionsMatch(mActionsProvider.getActionsList(),
                new int[]{ACTION_FULLSCREEN, ACTION_CUSTOM_CLOSE, ACTION_CUSTOM, ACTION_CUSTOM,
                        ACTION_MOVE}));
        verify(mMockListener).onActionsChanged(/* added= */ 0, /* updated= */ 1, /* index= */ 1);
    }

    @Test
    public void customActions_showDisabledActions() {
        assumeTelevision();
        mActionsProvider.updateExpansionEnabled(false);

        List<RemoteAction> customActions = createRemoteActions(2);
        customActions.get(0).setEnabled(false);
        mActionsProvider.setAppActions(customActions, null);

        assertTrue(checkActionsMatch(mActionsProvider.getActionsList(),
                new int[]{ACTION_FULLSCREEN, ACTION_CLOSE, ACTION_CUSTOM, ACTION_CUSTOM,
                        ACTION_MOVE}));
    }

    @Test
    public void mediaActions_hideDisabledActions() {
        assumeTelevision();
        mActionsProvider.updateExpansionEnabled(false);

        List<RemoteAction> customActions = createRemoteActions(2);
        customActions.get(0).setEnabled(false);
        mActionsProvider.onMediaActionsChanged(customActions);

        assertTrue(checkActionsMatch(mActionsProvider.getActionsList(),
                new int[]{ACTION_FULLSCREEN, ACTION_CLOSE, ACTION_CUSTOM, ACTION_MOVE}));
    }

}

