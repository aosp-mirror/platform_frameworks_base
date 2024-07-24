package com.android.systemui.keyguard.ui.preview

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@android.platform.test.annotations.EnabledOnRavenwood
class KeyguardRemotePreviewManagerTest : SysuiTestCase() {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Test
    fun onDestroy_clearsReferencesToRenderer() =
        testScope.runTest {
            val renderer = mock<KeyguardPreviewRenderer>()
            val onDestroy: (PreviewLifecycleObserver) -> Unit = {}

            val observer = PreviewLifecycleObserver(this, testDispatcher, renderer, onDestroy)

            // Precondition check.
            assertThat(observer.renderer).isNotNull()
            assertThat(observer.onDestroy).isNotNull()

            observer.onDestroy()

            // The verification checks renderer/requestDestruction lambda because they-re
            // non-singletons which can't leak KeyguardPreviewRenderer.
            assertThat(observer.renderer).isNull()
            assertThat(observer.onDestroy).isNull()
        }
}
