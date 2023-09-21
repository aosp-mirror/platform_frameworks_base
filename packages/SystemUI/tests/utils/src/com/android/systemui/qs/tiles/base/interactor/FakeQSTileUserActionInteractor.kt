package com.android.systemui.qs.tiles.base.interactor

import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FakeQSTileUserActionInteractor<T> : QSTileUserActionInteractor<T> {

    private val mutex: Mutex = Mutex()
    private val mutableInputs: MutableList<FakeInput<T>> = mutableListOf()

    val inputs: List<FakeInput<T>> = mutableInputs

    fun lastInput(): FakeInput<T>? = inputs.lastOrNull()

    override suspend fun handleInput(userAction: QSTileUserAction, currentData: T) {
        mutex.withLock { mutableInputs.add(FakeInput(userAction, currentData)) }
    }

    data class FakeInput<T>(val userAction: QSTileUserAction, val data: T)
}
