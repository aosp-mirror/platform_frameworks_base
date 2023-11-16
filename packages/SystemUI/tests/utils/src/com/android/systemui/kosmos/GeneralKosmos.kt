package com.android.systemui.kosmos

import android.content.Context
import android.os.UserManager
import com.android.systemui.kosmos.Kosmos.Fixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.mockito.Mockito

var Kosmos.testDispatcher by Fixture { StandardTestDispatcher() }
var Kosmos.testScope by Fixture { TestScope(testDispatcher) }
var Kosmos.context by Fixture<Context>()
var Kosmos.lifecycleScope by Fixture<CoroutineScope>()

val Kosmos.userManager by Fixture { Mockito.mock(UserManager::class.java) }
