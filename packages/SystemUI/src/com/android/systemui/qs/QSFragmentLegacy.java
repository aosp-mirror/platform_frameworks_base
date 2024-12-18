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

package com.android.systemui.qs;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Trace;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.FloatRange;
import androidx.annotation.Nullable;

import com.android.systemui.plugins.qs.QS;
import com.android.systemui.plugins.qs.QSContainerController;
import com.android.systemui.qs.dagger.QSFragmentComponent;
import com.android.systemui.qs.flags.QSComposeFragment;
import com.android.systemui.res.R;
import com.android.systemui.settings.brightness.MirrorController;
import com.android.systemui.util.LifecycleFragment;

import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Provider;

public class QSFragmentLegacy extends LifecycleFragment implements QS {

    private final Provider<QSImpl> mQsImplProvider;

    private final QSFragmentComponent.Factory mQsComponentFactory;

    @Nullable
    private QSImpl mQsImpl;

    @Inject
    public QSFragmentLegacy(
            Provider<QSImpl> qsImplProvider,
            QSFragmentComponent.Factory qsComponentFactory
    ) {
        mQsComponentFactory = qsComponentFactory;
        mQsImplProvider = qsImplProvider;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            Bundle savedInstanceState) {
        try {
            Trace.beginSection("QSFragment#onCreateView");
            inflater = inflater.cloneInContext(new ContextThemeWrapper(getContext(),
                    R.style.Theme_SystemUI_QuickSettings));
            return inflater.inflate(R.layout.qs_panel, container, false);
        } finally {
            Trace.endSection();
        }
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        QSFragmentComponent qsFragmentComponent = mQsComponentFactory.create(getView());
        mQsImpl = mQsImplProvider.get();
        mQsImpl.onCreate(null);
        mQsImpl.onComponentCreated(qsFragmentComponent, savedInstanceState);
    }

    @Override
    public void setScrollListener(ScrollListener listener) {
        if (mQsImpl != null) {
            mQsImpl.setScrollListener(listener);
        }
    }

    @Override
    public void onDestroyView() {
        if (mQsImpl != null) {
            mQsImpl.onDestroy();
            mQsImpl = null;
        }
        super.onDestroyView();
    }
    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mQsImpl != null) {
            mQsImpl.onSaveInstanceState(outState);
        }
    }

    @Override
    public View getHeader() {
        QSComposeFragment.assertInLegacyMode();
        if (mQsImpl != null) {
            return mQsImpl.getHeader();
        } else {
            return null;
        }
    }

    @Override
    public int getHeaderTop() {
        if (mQsImpl != null) {
            return mQsImpl.getHeaderTop();
        } else {
            return 0;
        }
    }

    @Override
    public int getHeaderBottom() {
        if (mQsImpl != null) {
            return mQsImpl.getHeaderBottom();
        } else {
            return 0;
        }
    }

    @Override
    public int getHeaderLeft() {
        if (mQsImpl != null) {
            return mQsImpl.getHeaderLeft();
        } else {
            return 0;
        }
    }

    @Override
    public void getHeaderBoundsOnScreen(Rect outBounds) {
        if (mQsImpl != null) {
            mQsImpl.getHeaderBoundsOnScreen(outBounds);
        } else {
            outBounds.setEmpty();
        }
    }

    @Override
    public boolean isHeaderShown() {
        if (mQsImpl != null) {
            return mQsImpl.isHeaderShown();
        } else {
            return false;
        }
    }

    @Override
    public void setHasNotifications(boolean hasNotifications) {
        if (mQsImpl != null) {
            mQsImpl.setHasNotifications(hasNotifications);
        }
    }

    @Override
    public void setPanelView(HeightListener panelView) {
        if (mQsImpl != null) {
            mQsImpl.setPanelView(panelView);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mQsImpl != null) {
            mQsImpl.onConfigurationChanged(newConfig);
        }
    }

    @Override
    public void setFancyClipping(int leftInset, int top, int rightInset, int bottom,
            int cornerRadius, boolean visible, boolean fullWidth) {
        if (mQsImpl != null) {
            mQsImpl.setFancyClipping(leftInset, top, rightInset, bottom, cornerRadius, visible,
                    fullWidth);
        }
    }

    @Override
    public boolean isFullyCollapsed() {
        if (mQsImpl != null) {
            return mQsImpl.isFullyCollapsed();
        } else {
            return true;
        }
    }

    @Override
    public void setCollapsedMediaVisibilityChangedListener(Consumer<Boolean> listener) {
        if (mQsImpl != null) {
            mQsImpl.setCollapsedMediaVisibilityChangedListener(listener);
        }
    }

    @Override
    public void setContainerController(QSContainerController controller) {
        if (mQsImpl != null) {
            mQsImpl.setContainerController(controller);
        }
    }

    @Override
    public boolean isCustomizing() {
        if (mQsImpl != null) {
            return mQsImpl.isCustomizing();
        } else {
            return false;
        }
    }

    public QSPanelController getQSPanelController() {
        if (mQsImpl != null) {
            return mQsImpl.getQSPanelController();
        } else {
            return null;
        }
    }

    public void setBrightnessMirrorController(
            MirrorController brightnessMirrorController) {
        if (mQsImpl != null) {
            mQsImpl.setBrightnessMirrorController(brightnessMirrorController);
        }
    }

    @Override
    public boolean isShowingDetail() {
        if (mQsImpl != null) {
            return mQsImpl.isShowingDetail();
        } else {
            return false;
        }
    }

    @Override
    public void setHeaderClickable(boolean clickable) {
        if (mQsImpl != null) {
            mQsImpl.setHeaderClickable(clickable);
        }
    }

    @Override
    public void setExpanded(boolean expanded) {
        if (mQsImpl != null) {
            mQsImpl.setExpanded(expanded);
        }
    }

    @Override
    public void setOverscrolling(boolean stackScrollerOverscrolling) {
        if (mQsImpl != null) {
            mQsImpl.setOverscrolling(stackScrollerOverscrolling);
        }
    }

    @Override
    public void setShouldUpdateSquishinessOnMedia(boolean shouldUpdate) {
        if (mQsImpl != null) {
            mQsImpl.setShouldUpdateSquishinessOnMedia(shouldUpdate);
        }
    }

    @Override
    public void setListening(boolean listening) {
        if (mQsImpl != null) {
            mQsImpl.setListening(listening);
        }
    }

    @Override
    public void setQsVisible(boolean visible) {
        if (mQsImpl != null) {
            mQsImpl.setQsVisible(visible);
        }
    }

    @Override
    public void setHeaderListening(boolean listening) {
        if (mQsImpl != null) {
            mQsImpl.setHeaderListening(listening);
        }
    }

    @Override
    public void notifyCustomizeChanged() {
        if (mQsImpl != null) {
            mQsImpl.notifyCustomizeChanged();
        }
    }

    @Override
    public void setInSplitShade(boolean inSplitShade) {
        if (mQsImpl != null) {
            mQsImpl.setInSplitShade(inSplitShade);
        }
    }

    @Override
    public void setTransitionToFullShadeProgress(
            boolean isTransitioningToFullShade,
            @FloatRange(from = 0.0, to = 1.0) float qsTransitionFraction,
            @FloatRange(from = 0.0, to = 1.0) float qsSquishinessFraction) {
        if (mQsImpl != null) {
            mQsImpl.setTransitionToFullShadeProgress(isTransitioningToFullShade,
                    qsTransitionFraction, qsSquishinessFraction);
        }
    }

    @Override
    public void setOverScrollAmount(int overScrollAmount) {
        if (mQsImpl != null) {
            mQsImpl.setOverScrollAmount(overScrollAmount);
        }
    }

    @Override
    public int getHeightDiff() {
        if (mQsImpl != null) {
            return mQsImpl.getHeightDiff();
        } else {
            return 0;
        }
    }

    @Override
    public void setIsNotificationPanelFullWidth(boolean isFullWidth) {
        if (mQsImpl != null) {
            mQsImpl.setIsNotificationPanelFullWidth(isFullWidth);
        }
    }

    @Override
    public void setQsExpansion(float expansion, float panelExpansionFraction,
            float proposedTranslation, float squishinessFraction) {
        if (mQsImpl != null) {
            mQsImpl.setQsExpansion(expansion, panelExpansionFraction, proposedTranslation,
                    squishinessFraction);
        }
    }

    @Override
    public void animateHeaderSlidingOut() {
        if (mQsImpl != null) {
            mQsImpl.animateHeaderSlidingOut();
        }
    }

    @Override
    public void setCollapseExpandAction(Runnable action) {
        if (mQsImpl != null) {
            mQsImpl.setCollapseExpandAction(action);
        }
    }

    @Override
    public void closeDetail() {
        if (mQsImpl != null) {
            mQsImpl.closeDetail();
        }
    }

    @Override
    public void closeCustomizer() {
        if (mQsImpl != null) {
            mQsImpl.closeDetail();
        }
    }

    /**
     * The height this view wants to be. This is different from {@link View#getMeasuredHeight} such
     * that during closing the detail panel, this already returns the smaller height.
     */
    @Override
    public int getDesiredHeight() {
        if (mQsImpl != null) {
            return mQsImpl.getDesiredHeight();
        } else {
            return 0;
        }
    }

    @Override
    public void setHeightOverride(int desiredHeight) {
        if (mQsImpl != null) {
            mQsImpl.setHeightOverride(desiredHeight);
        }
    }

    @Override
    public int getQsMinExpansionHeight() {
        if (mQsImpl != null) {
            return mQsImpl.getQsMinExpansionHeight();
        } else {
            return 0;
        }
    }

    @Override
    public void hideImmediately() {
        if (mQsImpl != null) {
            mQsImpl.hideImmediately();
        }
    }
}
