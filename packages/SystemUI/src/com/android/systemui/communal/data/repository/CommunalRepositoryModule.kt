package com.android.systemui.communal.data.repository

import dagger.Binds
import dagger.Module

@Module
interface CommunalRepositoryModule {
    @Binds fun communalRepository(impl: CommunalRepositoryImpl): CommunalRepository
}
