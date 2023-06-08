package com.android.systemui.fragments

import android.app.Fragment
import android.os.Looper
import android.test.suitebuilder.annotation.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.qs.QSFragment
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

@SmallTest
class FragmentServiceTest : SysuiTestCase() {
    private val fragmentCreator = TestFragmentCreator()
    private val fragmenetHostManagerFactory: FragmentHostManager.Factory = mock()
    private val fragmentCreatorFactory = FragmentService.FragmentCreator.Factory { fragmentCreator }

    private lateinit var fragmentService: FragmentService

    @Before
    fun setUp() {
        if (Looper.myLooper() == null) {
            Looper.prepare()
        }

        fragmentService =
            FragmentService(
                fragmentCreatorFactory,
                fragmenetHostManagerFactory,
                mock(),
                DumpManager()
            )
    }

    @Test
    fun constructor_addsFragmentCreatorMethodsToMap() {
        val map = fragmentService.injectionMap
        assertThat(map).hasSize(2)
        assertThat(map.keys).contains(QSFragment::class.java.name)
        assertThat(map.keys).contains(TestFragmentInCreator::class.java.name)
    }

    @Test
    fun addFragmentInstantiationProvider_objectHasNoFragmentMethods_nothingAdded() {
        fragmentService.addFragmentInstantiationProvider(Object())

        assertThat(fragmentService.injectionMap).hasSize(2)
    }

    @Test
    fun addFragmentInstantiationProvider_objectHasFragmentMethods_methodsAdded() {
        fragmentService.addFragmentInstantiationProvider(
            @Suppress("unused")
            object : Any() {
                fun createTestFragment2() = TestFragment2()
                fun createTestFragment3() = TestFragment3()
            }
        )

        val map = fragmentService.injectionMap
        assertThat(map).hasSize(4)
        assertThat(map.keys).contains(TestFragment2::class.java.name)
        assertThat(map.keys).contains(TestFragment3::class.java.name)
    }

    @Test
    fun addFragmentInstantiationProvider_objectFragmentMethodsAlreadyProvided_nothingAdded() {
        fragmentService.addFragmentInstantiationProvider(
            @Suppress("unused")
            object : Any() {
                fun createTestFragment() = TestFragmentInCreator()
            }
        )

        assertThat(fragmentService.injectionMap).hasSize(2)
    }

    class TestFragmentCreator : FragmentService.FragmentCreator {
        override fun createQSFragment(): QSFragment = mock()
        @Suppress("unused")
        fun createTestFragment(): TestFragmentInCreator = TestFragmentInCreator()
    }

    class TestFragmentInCreator : Fragment()
    class TestFragment2 : Fragment()
    class TestFragment3 : Fragment()
}
