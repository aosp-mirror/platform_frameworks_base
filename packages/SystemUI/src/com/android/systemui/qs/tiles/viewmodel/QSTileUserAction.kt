package com.android.systemui.qs.tiles.viewmodel

import android.content.Context
import android.view.View

sealed interface QSTileUserAction {

    val context: Context
    val view: View?

    class Click(override val context: Context, override val view: View?) : QSTileUserAction
    class LongClick(override val context: Context, override val view: View?) : QSTileUserAction
}
