package com.android.systemui.qs.dagger

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import com.android.systemui.dagger.qualifiers.RootView
import com.android.systemui.qs.QSContainerImpl
import com.android.systemui.qs.QSFooter
import com.android.systemui.qs.QSFooterView
import com.android.systemui.qs.QSFooterViewController
import com.android.systemui.qs.QSPanel
import com.android.systemui.qs.QuickQSPanel
import com.android.systemui.qs.QuickStatusBarHeader
import com.android.systemui.qs.customize.QSCustomizer
import com.android.systemui.res.R
import dagger.Module
import dagger.Provides

@Module
interface QSScopeModule {
    companion object {
        const val QS_USING_MEDIA_PLAYER = "qs_using_media_player"
        const val QS_USING_COLLAPSED_LANDSCAPE_MEDIA = "qs_using_collapsed_landscape_media"

        @Provides
        @QSThemedContext
        @JvmStatic
        fun provideThemedContext(@RootView view: View): Context {
            return view.context
        }

        /**  */
        @Provides
        @QSThemedContext
        @JvmStatic
        fun provideThemedLayoutInflater(@QSThemedContext context: Context): LayoutInflater {
            return LayoutInflater.from(context)
        }

        /**  */
        @Provides
        @JvmStatic
        fun provideQSPanel(@RootView view: View): QSPanel {
            return view.requireViewById<QSPanel>(R.id.quick_settings_panel)
        }

        /**  */
        @Provides
        @JvmStatic
        fun providesQSContainerImpl(@RootView view: View): QSContainerImpl {
            return view.requireViewById<QSContainerImpl>(R.id.quick_settings_container)
        }

        /**  */
        @Provides
        @JvmStatic
        fun providesQuickStatusBarHeader(@RootView view: View): QuickStatusBarHeader {
            return view.requireViewById<QuickStatusBarHeader>(R.id.header)
        }

        /**  */
        @Provides
        @JvmStatic
        fun providesQuickQSPanel(quickStatusBarHeader: QuickStatusBarHeader): QuickQSPanel {
            return quickStatusBarHeader.requireViewById<QuickQSPanel>(R.id.quick_qs_panel)
        }

        /**  */
        @Provides
        @JvmStatic
        fun providesQSFooterView(@RootView view: View): QSFooterView {
            return view.requireViewById<QSFooterView>(R.id.qs_footer)
        }

        /**  */
        @Provides
        @QSScope
        @JvmStatic
        fun providesQSFooter(qsFooterViewController: QSFooterViewController): QSFooter {
            qsFooterViewController.init()
            return qsFooterViewController
        }

        /**  */
        @Provides
        @QSScope
        @JvmStatic
        fun providesQSCutomizer(@RootView view: View): QSCustomizer {
            return view.requireViewById<QSCustomizer>(R.id.qs_customize)
        }
    }
}
