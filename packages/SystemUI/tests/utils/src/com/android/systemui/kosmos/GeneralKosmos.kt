package com.android.systemui.kosmos

import com.android.systemui.kosmos.Kosmos.Fixture
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

val Kosmos.testDispatcher by Fixture { StandardTestDispatcher() }
val Kosmos.testScope by Fixture { TestScope(testDispatcher) }
