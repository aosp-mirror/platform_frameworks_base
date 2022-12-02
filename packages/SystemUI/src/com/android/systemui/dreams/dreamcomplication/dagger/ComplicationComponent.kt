package com.android.systemui.dreams.dreamcomplication.dagger

import com.android.systemui.dreams.complication.Complication
import com.android.systemui.dreams.dreamcomplication.HideComplicationTouchHandler
import com.android.systemui.touch.TouchInsetManager
import dagger.BindsInstance
import dagger.Subcomponent

@Subcomponent(modules = [ComplicationModule::class])
interface ComplicationComponent {
    /** Factory for generating [ComplicationComponent]. */
    @Subcomponent.Factory
    interface Factory {
        fun create(
            @BindsInstance visibilityController: Complication.VisibilityController,
            @BindsInstance touchInsetManager: TouchInsetManager
        ): ComplicationComponent
    }

    fun getHideComplicationTouchHandler(): HideComplicationTouchHandler
}
