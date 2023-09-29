package com.android.systemui.qs.tiles.viewmodel

import android.view.View

sealed interface QSTileUserAction {

    val view: View?

    class Click(override val view: View?) : QSTileUserAction
    class LongClick(override val view: View?) : QSTileUserAction
}
