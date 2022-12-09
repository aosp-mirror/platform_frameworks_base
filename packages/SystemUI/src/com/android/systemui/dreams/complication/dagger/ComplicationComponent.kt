package com.android.systemui.dreams.complication.dagger

import dagger.Subcomponent

@Subcomponent
interface ComplicationComponent {
    /** Factory for generating [ComplicationComponent]. */
    @Subcomponent.Factory
    interface Factory {
        fun create(): ComplicationComponent
    }
}
