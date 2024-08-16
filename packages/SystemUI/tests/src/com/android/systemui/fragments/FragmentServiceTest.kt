package com.android.systemui.fragments

import android.app.Fragment
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class FragmentServiceTest : SysuiTestCase() {
    private val fragmentHostManagerFactory: FragmentHostManager.Factory = mock()

    private lateinit var fragmentService: FragmentService

    @Before
    fun setUp() {
        if (Looper.myLooper() == null) {
            Looper.prepare()
        }

        fragmentService = FragmentService(fragmentHostManagerFactory, mock(), DumpManager())
    }

    @Test
    fun addFragmentInstantiationProvider_objectHasNoFragmentMethods_nothingAdded() {
        fragmentService.addFragmentInstantiationProvider(TestFragment::class.java) {
            TestFragment()
        }

        assertThat(fragmentService.injectionMap).hasSize(1)
    }

    @Test
    fun addFragmentInstantiationProvider_objectFragmentMethodsAlreadyProvided_nothingAdded() {
        fragmentService.addFragmentInstantiationProvider(TestFragment::class.java) {
            TestFragment()
        }
        fragmentService.addFragmentInstantiationProvider(TestFragment::class.java) {
            TestFragment()
        }

        assertThat(fragmentService.injectionMap).hasSize(1)
    }

    class TestFragment : Fragment()
}
