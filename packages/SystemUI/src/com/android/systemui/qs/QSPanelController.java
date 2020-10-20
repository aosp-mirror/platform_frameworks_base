/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs;

import android.annotation.NonNull;
import android.view.ViewGroup;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.media.MediaHost;
import com.android.systemui.qs.customize.QSCustomizer;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/**
 * Controller for {@link QSPanel}.
 */
@QSScope
public class QSPanelController extends ViewController<QSPanel> {
    private final BroadcastDispatcher mBroadcastDispatcher;

    @Inject
    QSPanelController(QSPanel view, BroadcastDispatcher broadcastDispatcher) {
        super(view);
        mBroadcastDispatcher = broadcastDispatcher;
    }

    @Override
    protected void onViewAttached() {

    }

    @Override
    protected void onViewDetached() {

    }

    /** TODO(b/168904199): Remove this method once view is controllerized. */
    QSPanel getView() {
        return mView;
    }


    /**
     * Set the header container of quick settings.
     */
    public void setHeaderContainer(@NonNull ViewGroup headerContainer) {
        mView.setHeaderContainer(headerContainer);
    }

    public QSPanel.QSTileLayout getTileLayout() {
        return mView.getTileLayout();
    }

    /** */
    public void setHost(QSTileHost host, QSCustomizer customizer) {
        mView.setHost(host, customizer);
    }

    /** */
    public void setExpanded(boolean qsExpanded) {
        mView.setExpanded(qsExpanded);
    }

    /** */
    public boolean isShowingCustomize() {
        return mView.isShowingCustomize();
    }

    /** */
    public void setVisibility(int visibility) {
        mView.setVisibility(visibility);
    }

    /** */
    public void setListening(boolean listening, boolean expanded) {
        mView.setListening(listening, expanded);
    }

    /** */
    public QSTileRevealController getQsTileRevealController() {
        return mView.getQsTileRevealController();
    }

    /** */
    public MediaHost getMediaHost() {
        return mView.getMediaHost();
    }

    /** */
    public void closeDetail() {
        mView.closeDetail();
    }
}
