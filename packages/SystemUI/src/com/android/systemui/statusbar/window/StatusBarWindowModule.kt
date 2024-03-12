package com.android.systemui.statusbar.window

import android.view.LayoutInflater
import com.android.systemui.res.R
import com.android.systemui.dagger.SysUISingleton
import dagger.Module
import dagger.Provides
import javax.inject.Qualifier

/** Module providing dependencies related to the status bar window. */
@Module
abstract class StatusBarWindowModule {
    /**
     * Provides a [StatusBarWindowView].
     *
     * Only [StatusBarWindowController] should inject the view.
     */
    @Module
    companion object {
        @JvmStatic
        @Provides
        @SysUISingleton
        @InternalWindowView
        fun providesStatusBarWindowView(layoutInflater: LayoutInflater): StatusBarWindowView {
            return layoutInflater.inflate(
                R.layout.super_status_bar,
                /* root= */null
            ) as StatusBarWindowView?
                ?: throw IllegalStateException(
                    "R.layout.super_status_bar could not be properly inflated"
                )
        }
    }

    /**
     * We want [StatusBarWindowView] to be provided to [StatusBarWindowController]'s constructor via
     * dagger so that we can provide a fake window view when testing the controller. However, we wan
     * want *only* the controller to be able to inject the window view.
     *
     * This protected qualifier annotation achieves this. [StatusBarWindowView] can only be injected
     * if it's annotated with [InternalWindowView], and only classes inside this [statusbar.window]
     * package can access the annotation.
     */
    @Retention(AnnotationRetention.BINARY)
    @Qualifier
    protected annotation class InternalWindowView
}
