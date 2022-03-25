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

package com.android.systemui.qs.dagger;

import com.android.systemui.qs.FooterActionsController;
import com.android.systemui.qs.QSAnimator;
import com.android.systemui.qs.QSContainerImplController;
import com.android.systemui.qs.QSFooter;
import com.android.systemui.qs.QSFragment;
import com.android.systemui.qs.QSPanelController;
import com.android.systemui.qs.QSSquishinessController;
import com.android.systemui.qs.QuickQSPanelController;
import com.android.systemui.qs.customize.QSCustomizerController;

import dagger.BindsInstance;
import dagger.Subcomponent;

/**
 * Dagger Subcomponent for {@link QSFragment}.
 */
@Subcomponent(modules = {QSFragmentModule.class})
@QSScope
public interface QSFragmentComponent {

    /** Factory for building a {@link QSFragmentComponent}. */
    @Subcomponent.Factory
    interface Factory {
        QSFragmentComponent create(@BindsInstance QSFragment qsFragment);
    }

    /** Construct a {@link QSPanelController}. */
    QSPanelController getQSPanelController();

    /** Construct a {@link QuickQSPanelController}. */
    QuickQSPanelController getQuickQSPanelController();

    /** Construct a {@link QSAnimator}. */
    QSAnimator getQSAnimator();

    /** Construct a {@link QSContainerImplController}. */
    QSContainerImplController getQSContainerImplController();

    /** Construct a {@link QSFooter} */
    QSFooter getQSFooter();

    /** Construct a {@link QSCustomizerController}. */
    QSCustomizerController getQSCustomizerController();

    /** Construct a {@link QSSquishinessController}. */
    QSSquishinessController getQSSquishinessController();

    /** Construct a {@link FooterActionsController}. */
    FooterActionsController getQSFooterActionController();
}
