/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.content.res.loader.test

import android.content.res.AssetManager
import android.content.res.loader.DirectoryResourceLoader
import android.content.res.loader.ResourceLoader
import android.content.res.loader.ResourcesProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.eq
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Paths

@RunWith(Parameterized::class)
class ResourceLoaderAssetTest : ResourceLoaderTestBase() {

    companion object {
        private const val BASE_TEST_PATH = "android/content/res/loader/test/file.txt"
        private const val TEST_TEXT = "some text"

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters(): Array<Array<out Any?>> {
            val fromInputStream: ResourceLoader.(String) -> Any? = {
                loadAsset(eq(it), anyInt())
            }

            val fromFileDescriptor: ResourceLoader.(String) -> Any? = {
                loadAssetFd(eq(it))
            }

            val openAsset: AssetManager.() -> String? = {
                open(BASE_TEST_PATH).reader().readText()
            }

            val openNonAsset: AssetManager.() -> String? = {
                openNonAssetFd(BASE_TEST_PATH).readText()
            }

            return arrayOf(
                    arrayOf("assets", fromInputStream, openAsset),
                    arrayOf("", fromFileDescriptor, openNonAsset)
            )
        }
    }

    @get:Rule
    val testName = TestName()

    @JvmField
    @field:Parameterized.Parameter(0)
    var prefix: String? = null

    @field:Parameterized.Parameter(1)
    lateinit var loadAssetFunction: ResourceLoader.(String) -> Any?

    @field:Parameterized.Parameter(2)
    lateinit var openAssetFunction: AssetManager.() -> String?

    private val testPath: String
        get() = Paths.get(prefix.orEmpty(), BASE_TEST_PATH).toString()

    private fun ResourceLoader.loadAsset() = loadAssetFunction(testPath)

    private fun AssetManager.openAsset() = openAssetFunction()

    private lateinit var testDir: File

    @Before
    fun setUpTestDir() {
        testDir = context.filesDir.resolve("DirectoryResourceLoaderTest_${testName.methodName}")
        testDir.resolve(testPath).apply { parentFile!!.mkdirs() }.writeText(TEST_TEXT)
    }

    @Test
    fun multipleLoadersSearchesBackwards() {
        // DirectoryResourceLoader relies on a private field and can't be spied directly, so wrap it
        val loader = DirectoryResourceLoader(testDir)
        val loaderWrapper = mock(ResourceLoader::class.java).apply {
            doAnswer { loader.loadAsset(it.arguments[0] as String, it.arguments[1] as Int) }
                    .`when`(this).loadAsset(anyString(), anyInt())
            doAnswer { loader.loadAssetFd(it.arguments[0] as String) }
                    .`when`(this).loadAssetFd(anyString())
        }

        val one = loaderWrapper to ResourcesProvider.empty()
        val two = mockLoader {
            doReturn(null).`when`(it).loadAsset()
        }

        addLoader(one, two)

        assertOpenedAsset()
        inOrder(two.first, one.first).apply {
            verify(two.first).loadAsset()
            verify(one.first).loadAsset()
        }
    }

    @Test(expected = FileNotFoundException::class)
    fun failToFindThrowsFileNotFound() {
        val one = mockLoader {
            doReturn(null).`when`(it).loadAsset()
        }
        val two = mockLoader {
            doReturn(null).`when`(it).loadAsset()
        }

        addLoader(one, two)

        assertOpenedAsset()
    }

    @Test
    fun throwingIOExceptionIsSkipped() {
        val one = DirectoryResourceLoader(testDir) to ResourcesProvider.empty()
        val two = mockLoader {
            doAnswer { throw IOException() }.`when`(it).loadAsset()
        }

        addLoader(one, two)

        assertOpenedAsset()
    }

    @Test(expected = IllegalStateException::class)
    fun throwingNonIOExceptionCausesFailure() {
        val one = DirectoryResourceLoader(testDir) to ResourcesProvider.empty()
        val two = mockLoader {
            doAnswer { throw IllegalStateException() }.`when`(it).loadAsset()
        }

        addLoader(one, two)

        assertOpenedAsset()
    }

    private fun assertOpenedAsset() {
        assertThat(resources.assets.openAsset()).isEqualTo(TEST_TEXT)
    }
}
