package com.android.systemui.qs.dagger

import android.view.View
import com.android.systemui.dagger.qualifiers.RootView
import com.android.systemui.qs.FooterActionsController
import com.android.systemui.qs.QSAnimator
import com.android.systemui.qs.QSContainerImplController
import com.android.systemui.qs.QSFooter
import com.android.systemui.qs.QSPanelController
import com.android.systemui.qs.QSSquishinessController
import com.android.systemui.qs.QuickQSPanelController
import com.android.systemui.qs.customize.QSCustomizerController

interface QSComponent {
    /** Construct a [QSPanelController]. */
    fun getQSPanelController(): QSPanelController

    /** Construct a [QuickQSPanelController]. */
    fun getQuickQSPanelController(): QuickQSPanelController

    /** Construct a [QSAnimator]. */
    fun getQSAnimator(): QSAnimator

    /** Construct a [QSContainerImplController]. */
    fun getQSContainerImplController(): QSContainerImplController

    /** Construct a [QSFooter] */
    fun getQSFooter(): QSFooter

    /** Construct a [QSCustomizerController]. */
    fun getQSCustomizerController(): QSCustomizerController

    /** Construct a [QSSquishinessController]. */
    fun getQSSquishinessController(): QSSquishinessController

    /** Construct a [FooterActionsController]. */
    fun getQSFooterActionController(): FooterActionsController

    @RootView fun getRootView(): View
}
