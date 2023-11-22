package com.android.systemui.kosmos

import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos.Fixture
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

var Kosmos.testDispatcher by Fixture { StandardTestDispatcher() }
var Kosmos.testScope by Fixture { TestScope(testDispatcher) }
var Kosmos.applicationCoroutineScope by Fixture { testScope.backgroundScope }
var Kosmos.testCase: SysuiTestCase by Fixture()
