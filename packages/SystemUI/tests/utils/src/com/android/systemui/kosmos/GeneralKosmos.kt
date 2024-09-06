package com.android.systemui.kosmos

import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos.Fixture
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

var Kosmos.testDispatcher by Fixture { StandardTestDispatcher() }
var Kosmos.unconfinedTestDispatcher by Fixture { UnconfinedTestDispatcher() }
var Kosmos.testScope by Fixture { TestScope(testDispatcher) }
var Kosmos.unconfinedTestScope by Fixture { TestScope(unconfinedTestDispatcher) }
var Kosmos.applicationCoroutineScope by Fixture { testScope.backgroundScope }
var Kosmos.testCase: SysuiTestCase by Fixture()
var Kosmos.backgroundCoroutineContext: CoroutineContext by Fixture {
    testScope.backgroundScope.coroutineContext
}
var Kosmos.mainCoroutineContext: CoroutineContext by Fixture { testScope.coroutineContext }
