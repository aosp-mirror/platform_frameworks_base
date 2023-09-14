package com.android.systemui.communal.data.repository

/** Fake implementation of [CommunalRepository]. */
class FakeCommunalRepository : CommunalRepository {
    override var isCommunalEnabled = false

    fun setIsCommunalEnabled(value: Boolean) {
        isCommunalEnabled = value
    }
}
