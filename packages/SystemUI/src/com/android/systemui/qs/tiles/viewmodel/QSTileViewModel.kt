package com.android.systemui.qs.tiles.viewmodel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Represents tiles behaviour logic. This ViewModel is a connection between tile view and data
 * layers.
 */
interface QSTileViewModel {

    /**
     * State of the tile to be shown by the view. Favor reactive consumption over the
     * [StateFlow.value], because there is no guarantee that current value would be available at any
     * time.
     */
    val state: StateFlow<QSTileState>

    val config: QSTileConfig

    val isAvailable: Flow<Boolean>

    /**
     * Handles ViewModel lifecycle. Implementations should be inactive outside of
     * [QSTileLifecycle.ON_CREATE] and [QSTileLifecycle.ON_DESTROY] bounds.
     */
    fun onLifecycle(lifecycle: QSTileLifecycle)

    /**
     * Notifies about the user change. Implementations should avoid using 3rd party userId sources
     * and use this value instead. This is to maintain consistent and concurrency-free behaviour
     * across different parts of QS.
     */
    fun onUserIdChanged(userId: Int)

    /** Triggers emit of the new [QSTileState] in [state]. */
    fun forceUpdate()

    /** Notifies underlying logic about user input. */
    fun onActionPerformed(userAction: QSTileUserAction)
}
