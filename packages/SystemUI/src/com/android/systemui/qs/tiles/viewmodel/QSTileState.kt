package com.android.systemui.qs.tiles.viewmodel

import android.graphics.drawable.Icon

data class QSTileState(
    val icon: Icon,
    val label: CharSequence,
// TODO(b/299908705): Fill necessary params
/*
   val subtitle: CharSequence = "",
   val activeState: ActivationState = Active,
   val enabledState: Enabled = Enabled,
   val loopIconAnimation: Boolean = false,
   val secondaryIcon: Icon? = null,
   val slashState: SlashState? = null,
   val supportedActions: Collection<UserAction> = listOf(Click), clicks should be a default action
*/
)
