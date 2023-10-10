package com.android.keyguard.dagger

import android.content.Context
import android.content.res.Resources
import android.view.Display
import com.android.systemui.dagger.qualifiers.DisplaySpecific
import com.android.systemui.util.kotlin.getOrNull
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import java.util.Optional

/**
 * Binds display specific context and resources.
 *
 * When a [Display] is available in the scope, binds a [DisplaySpecific] [Context] and [Resources].
 * When not available, the default display context and resources are used.
 */
@Module
abstract class KeyguardDisplayModule {

    @BindsOptionalOf abstract fun optionalDisplay(): Display

    companion object {
        @Provides
        @DisplaySpecific
        fun getDisplayContext(context: Context, optionalDisplay: Optional<Display>): Context {
            val display = optionalDisplay.getOrNull() ?: return context
            return if (context.displayId == display.displayId) {
                context
            } else {
                context.createDisplayContext(display)
            }
        }

        @Provides
        @DisplaySpecific
        fun getDisplayResources(@DisplaySpecific context: Context): Resources {
            return context.resources
        }
    }
}
