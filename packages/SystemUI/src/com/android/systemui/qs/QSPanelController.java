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

import static com.android.systemui.qs.QSPanel.QS_SHOW_BRIGHTNESS;

import android.annotation.NonNull;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.media.MediaHost;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.customize.QSCustomizerController;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.settings.BrightnessController;
import com.android.systemui.statusbar.policy.BrightnessMirrorController;
import com.android.systemui.tuner.TunerService;

import javax.inject.Inject;

/**
 * Controller for {@link QSPanel}.
 */
@QSScope
public class QSPanelController extends QSPanelControllerBase<QSPanel> {
    private final QSSecurityFooter mQsSecurityFooter;
    private final TunerService mTunerService;
    private final QSCustomizerController mQsCustomizerController;
    private final BrightnessController mBrightnessController;

    private final QSPanel.OnConfigurationChangedListener mOnConfigurationChangedListener =
            new QSPanel.OnConfigurationChangedListener() {
        @Override
        public void onConfigurationChange(Configuration newConfig) {
            mView.updateResources();
            mQsSecurityFooter.onConfigurationChanged();
            if (mView.isListening()) {
                refreshAllTiles();
            }
        }
    };
    private BrightnessMirrorController mBrightnessMirrorController;

    @Inject
    QSPanelController(QSPanel view, QSSecurityFooter qsSecurityFooter, TunerService tunerService,
            QSTileHost qstileHost, QSCustomizerController qsCustomizerController,
            DumpManager dumpManager, MetricsLogger metricsLogger, UiEventLogger uiEventLogger,
            BrightnessController.Factory brightnessControllerFactory) {
        super(view, qstileHost, qsCustomizerController, metricsLogger, uiEventLogger, dumpManager);
        mQsSecurityFooter = qsSecurityFooter;
        mTunerService = tunerService;
        mQsCustomizerController = qsCustomizerController;
        mQsSecurityFooter.setHostEnvironment(qstileHost);
        mBrightnessController = brightnessControllerFactory.create(
                mView.findViewById(R.id.brightness_slider));

        mView.setCustomizer(mQsCustomizerController.getView());
    }

    @Override
    public void onInit() {
        mQsCustomizerController.init();
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();
        mTunerService.addTunable(mView, QS_SHOW_BRIGHTNESS);
        mView.updateResources();
        if (mView.isListening()) {
            refreshAllTiles();
        }
        mView.addOnConfigurationChangedListener(mOnConfigurationChangedListener);
        mView.setSecurityFooter(mQsSecurityFooter.getView());
        switchTileLayout(true);
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.addCallback(mView);
        }
    }

    @Override
    protected void onViewDetached() {
        mTunerService.removeTunable(mView);
        mView.removeOnConfigurationChangedListener(mOnConfigurationChangedListener);
        if (mBrightnessMirrorController != null) {
            mBrightnessMirrorController.removeCallback(mView);
        }
        super.onViewDetached();
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
    public boolean isShowingCustomize() {
        return mView.isShowingCustomize();
    }

    /** */
    public void setVisibility(int visibility) {
        mView.setVisibility(visibility);
    }

    /** */
    public void setListening(boolean listening, boolean expanded) {
        setListening(listening && expanded);
        if (mView.isListening()) {
            refreshAllTiles();
        }

        mQsSecurityFooter.setListening(listening);

        // Set the listening as soon as the QS fragment starts listening regardless of the
        //expansion, so it will update the current brightness before the slider is visible.
        if (listening) {
            mBrightnessController.registerCallbacks();
        } else {
            mBrightnessController.unregisterCallbacks();
        }
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
    public void setBrightnessMirror(BrightnessMirrorController brightnessMirrorController) {
        mBrightnessMirrorController = brightnessMirrorController;
        mView.setBrightnessMirror(brightnessMirrorController);
    }

    /** Get the QSTileHost this panel uses. */
    public QSTileHost getHost() {
        return mHost;
    }


    /** Open the details for a specific tile.. */
    public void openDetails(String subPanel) {
        QSTile tile = getTile(subPanel);
        if (tile != null) {
            mView.openDetails(tile);
        }
    }

    /** Show the device monitoring dialog. */
    public void showDeviceMonitoringDialog() {
        mQsSecurityFooter.showDeviceMonitoringDialog();
    }

    /** Update appearance of QSPanel. */
    public void updateResources() {
        mView.updateResources();
    }

    /** Update state of all tiles. */
    public void refreshAllTiles() {
        mBrightnessController.checkRestrictionAndSetEnabled();
        super.refreshAllTiles();
        mQsSecurityFooter.refreshState();
    }

    /** Start customizing the Quick Settings. */
    public void showEdit(View view) {
        view.post(() -> {
            if (!mQsCustomizerController.isCustomizing()) {
                int[] loc = view.getLocationOnScreen();
                int x = loc[0] + view.getWidth() / 2;
                int y = loc[1] + view.getHeight() / 2;
                mQsCustomizerController.show(x, y, false);
            }
        });
    }

    /** */
    public void setCallback(QSDetail.Callback qsPanelCallback) {
        mView.setCallback(qsPanelCallback);
    }

    /** */
    public void setGridContentVisibility(boolean visible) {
        mView.setGridContentVisibility(visible);
    }
}
