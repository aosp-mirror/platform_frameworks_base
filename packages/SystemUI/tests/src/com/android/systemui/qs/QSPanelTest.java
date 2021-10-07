/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import static junit.framework.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Configuration;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.qs.QSTileView;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.util.animation.UniqueObjectHostView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class QSPanelTest extends SysuiTestCase {

    private TestableLooper mTestableLooper;
    private QSPanel mQsPanel;
    @Mock
    private QSTileHost mHost;
    @Mock
    private QSTileImpl dndTile;
    @Mock
    private QSPanelControllerBase.TileRecord mDndTileRecord;
    private ViewGroup mParentView;
    @Mock
    private QSDetail.Callback mCallback;
    @Mock
    private QSTileView mQSTileView;

    private UniqueObjectHostView mMediaView;
    private View mSecurityFooter;
    @Mock
    private FrameLayout mHeaderContainer;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);

        mDndTileRecord.tile = dndTile;
        mDndTileRecord.tileView = mQSTileView;

        mMediaView = new UniqueObjectHostView(mContext);
        mSecurityFooter = new View(mContext);

        mTestableLooper.runWithLooper(() -> {
            mQsPanel = new QSPanel(mContext, null);
            mQsPanel.initialize();
            mQsPanel.onFinishInflate();
            // Provides a parent with non-zero size for QSPanel
            mParentView = new FrameLayout(mContext);
            mParentView.addView(mQsPanel);

            when(dndTile.getTileSpec()).thenReturn("dnd");
            when(mHost.getTiles()).thenReturn(Collections.emptyList());
            when(mHost.createTileView(any(), any(), anyBoolean())).thenReturn(mQSTileView);
            mQsPanel.addTile(mDndTileRecord);
            mQsPanel.setCallback(mCallback);
            mQsPanel.setHeaderContainer(mHeaderContainer);
        });
    }

    @Test
    public void testOpenDetailsWithExistingTile_NoException() {
        mTestableLooper.processAllMessages();
        mQsPanel.openDetails(dndTile);
        mTestableLooper.processAllMessages();

        verify(mCallback).onShowingDetail(any(), anyInt(), anyInt());
    }

    @Test
    public void testOpenDetailsWithNullParameter_NoException() {
        mTestableLooper.processAllMessages();
        mQsPanel.openDetails(null);
        mTestableLooper.processAllMessages();

        verify(mCallback, never()).onShowingDetail(any(), anyInt(), anyInt());
    }

    @Test
    public void testSecurityFooterAtEndNoMedia_portrait() {
        mTestableLooper.processAllMessages();

        mContext.getResources().getConfiguration().orientation = Configuration.ORIENTATION_PORTRAIT;

        mQsPanel.setSecurityFooter(mSecurityFooter);

        int children = mQsPanel.getChildCount();
        assertEquals(children - 1, mQsPanel.indexOfChild(mSecurityFooter));
    }

    @Test
    public void testSecurityFooterRightBeforeMedia_portrait() {
        mTestableLooper.processAllMessages();

        mContext.getResources().getConfiguration().orientation = Configuration.ORIENTATION_PORTRAIT;

        mQsPanel.addView(mMediaView);

        mQsPanel.setSecurityFooter(mSecurityFooter);

        int securityFooterIndex = mQsPanel.indexOfChild(mSecurityFooter);
        int mediaIndex = mQsPanel.indexOfChild(mMediaView);

        assertEquals(mediaIndex - 1, securityFooterIndex);
    }

    @Test
    public void testSecurityFooterRightBeforeMedia_portrait_configChange() {
        mTestableLooper.processAllMessages();

        mContext.getResources().getConfiguration().orientation = Configuration.ORIENTATION_PORTRAIT;

        mQsPanel.addView(mMediaView);

        mQsPanel.setSecurityFooter(mSecurityFooter);

        mQsPanel.onConfigurationChanged(mContext.getResources().getConfiguration());

        int securityFooterIndex = mQsPanel.indexOfChild(mSecurityFooter);
        int mediaIndex = mQsPanel.indexOfChild(mMediaView);

        assertEquals(mediaIndex - 1, securityFooterIndex);
    }

    @Test
    public void testSecurityFooterInHeader_landscape() {
        mTestableLooper.processAllMessages();

        mContext.getResources().getConfiguration().orientation =
                Configuration.ORIENTATION_LANDSCAPE;

        mQsPanel.setSecurityFooter(mSecurityFooter);

        verify(mHeaderContainer).addView(mSecurityFooter, 0);
    }
}
