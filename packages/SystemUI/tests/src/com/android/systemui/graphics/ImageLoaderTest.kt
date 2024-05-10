package com.android.systemui.graphics

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.graphics.drawable.VectorDrawable
import android.net.Uri
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@kotlinx.coroutines.ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class ImageLoaderTest : SysuiTestCase() {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val imageLoader = ImageLoader(context, testDispatcher)

    private lateinit var imgFile: File

    @Before
    fun setUp() {
        val context = context.createPackageContext("com.android.systemui.tests", 0)
        val bitmap =
            BitmapFactory.decodeResource(
                context.resources,
                com.android.systemui.tests.R.drawable.romainguy_rockaway
            )

        imgFile = File.createTempFile("image", ".png", context.cacheDir)
        imgFile.deleteOnExit()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, FileOutputStream(imgFile))
    }

    @After
    fun tearDown() {
        imgFile.delete()
    }

    @Test
    fun invalidResource_drawable_returnsNull() =
        testScope.runTest { assertThat(imageLoader.loadDrawable(ImageLoader.Res(-1))).isNull() }

    @Test
    fun invalidResource_bitmap_returnsNull() =
        testScope.runTest { assertThat(imageLoader.loadBitmap(ImageLoader.Res(-1))).isNull() }

    @Test
    fun invalidUri_returnsNull() =
        testScope.runTest {
            assertThat(imageLoader.loadBitmap(ImageLoader.Uri("this.is/bogus"))).isNull()
        }

    @Test
    fun invalidFile_returnsNull() =
        testScope.runTest {
            assertThat(imageLoader.loadBitmap(ImageLoader.File("this is broken!"))).isNull()
        }

    @Test
    fun invalidIcon_loadDrawable_returnsNull() =
        testScope.runTest {
            assertThat(imageLoader.loadDrawable(Icon.createWithFilePath("this is broken"))).isNull()
        }

    @Test
    fun invalidIcon_loadSize_returnsNull() =
        testScope.runTest {
            assertThat(imageLoader.loadSize(Icon.createWithFilePath("this is broken"), context))
                .isNull()
        }

    @Test
    fun invalidIS_returnsNull() =
        testScope.runTest {
            assertThat(
                    imageLoader.loadDrawable(
                        ImageLoader.InputStream(ByteArrayInputStream(ByteArray(0)))
                    )
                )
                .isNull()
        }

    @Test
    fun validBitmapResource_loadDrawable_returnsBitmapDrawable() =
        testScope.runTest {
            val context = context.createPackageContext("com.android.systemui.tests", 0)
            val bitmap =
                BitmapFactory.decodeResource(
                    context.resources,
                    com.android.systemui.tests.R.drawable.romainguy_rockaway
                )
            assertThat(bitmap).isNotNull()
            val loadedDrawable =
                imageLoader.loadDrawable(
                    ImageLoader.Res(
                        com.android.systemui.tests.R.drawable.romainguy_rockaway,
                        context
                    )
                )
            assertBitmapEqualToDrawable(loadedDrawable, bitmap)
        }

    @Test
    fun validBitmapResource_loadBitmap_returnsBitmapDrawable() =
        testScope.runTest {
            val bitmap =
                BitmapFactory.decodeResource(
                    context.resources,
                    R.drawable.dessert_zombiegingerbread
                )
            val loadedBitmap =
                imageLoader.loadBitmap(ImageLoader.Res(R.drawable.dessert_zombiegingerbread))
            assertBitmapEqualToBitmap(loadedBitmap, bitmap)
        }

    @Test
    fun validBitmapUri_returnsBitmapDrawable() =
        testScope.runTest {
            val bitmap =
                BitmapFactory.decodeResource(
                    context.resources,
                    R.drawable.dessert_zombiegingerbread
                )

            val uri =
                "android.resource://${context.packageName}/${R.drawable.dessert_zombiegingerbread}"
            val loadedBitmap = imageLoader.loadBitmap(ImageLoader.Uri(uri))
            assertBitmapEqualToBitmap(loadedBitmap, bitmap)
        }

    @Test
    fun validBitmapFile_returnsBitmapDrawable() =
        testScope.runTest {
            val bitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
            val loadedBitmap = imageLoader.loadBitmap(ImageLoader.File(imgFile))
            assertBitmapEqualToBitmap(loadedBitmap, bitmap)
        }

    @Test
    fun validInputStream_returnsBitmapDrawable() =
        testScope.runTest {
            val bitmap = BitmapFactory.decodeFile(imgFile.absolutePath)
            val loadedBitmap =
                imageLoader.loadBitmap(ImageLoader.InputStream(FileInputStream(imgFile)))
            assertBitmapEqualToBitmap(loadedBitmap, bitmap)
        }

    @Test
    fun validBitmapIcon_returnsBitmapDrawable() =
        testScope.runTest {
            val bitmap =
                BitmapFactory.decodeResource(
                    context.resources,
                    R.drawable.dessert_zombiegingerbread
                )
            val loadedDrawable = imageLoader.loadDrawable(Icon.createWithBitmap(bitmap))
            assertBitmapEqualToDrawable(loadedDrawable, bitmap)
        }

    @Test
    fun validBitmapIcon_loadSize_returnsNull() =
        testScope.runTest {
            val bitmap =
                BitmapFactory.decodeResource(
                    context.resources,
                    R.drawable.dessert_zombiegingerbread
                )
            assertThat(imageLoader.loadSize(Icon.createWithBitmap(bitmap), context)).isNull()
        }

    @Test
    fun validUriIcon_returnsBitmapDrawable() =
        testScope.runTest {
            val bitmap =
                BitmapFactory.decodeResource(
                    context.resources,
                    R.drawable.dessert_zombiegingerbread
                )
            val uri =
                "android.resource://${context.packageName}/${R.drawable.dessert_zombiegingerbread}"
            val loadedDrawable = imageLoader.loadDrawable(Icon.createWithContentUri(Uri.parse(uri)))
            assertBitmapEqualToDrawable(loadedDrawable, bitmap)
        }

    @Test
    fun validUriIcon_returnsSize() =
        testScope.runTest {
            val drawable = context.resources.getDrawable(R.drawable.dessert_zombiegingerbread)
            val uri =
                "android.resource://${context.packageName}/${R.drawable.dessert_zombiegingerbread}"
            val loadedSize =
                imageLoader.loadSize(Icon.createWithContentUri(Uri.parse(uri)), context)
            assertSizeEqualToDrawableSize(loadedSize, drawable)
        }

    @Test
    fun validDataIcon_returnsBitmapDrawable() =
        testScope.runTest {
            val bitmap =
                BitmapFactory.decodeResource(
                    context.resources,
                    R.drawable.dessert_zombiegingerbread
                )
            val bos =
                ByteArrayOutputStream(
                    bitmap.byteCount * 2
                ) // Compressed bitmap should be smaller than its source.
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)

            val array = bos.toByteArray()
            val loadedDrawable = imageLoader.loadDrawable(Icon.createWithData(array, 0, array.size))
            assertBitmapEqualToDrawable(loadedDrawable, bitmap)
        }

    @Test
    fun validDataIcon_loadSize_returnsNull() =
        testScope.runTest {
            val bitmap =
                BitmapFactory.decodeResource(
                    context.resources,
                    R.drawable.dessert_zombiegingerbread
                )
            val bos =
                ByteArrayOutputStream(
                    bitmap.byteCount * 2
                ) // Compressed bitmap should be smaller than its source.
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)

            val array = bos.toByteArray()
            assertThat(imageLoader.loadSize(Icon.createWithData(array, 0, array.size), context))
                .isNull()
        }

    @Test
    fun validResourceIcon_returnsBitmapDrawable() =
        testScope.runTest {
            val bitmap = context.resources.getDrawable(R.drawable.dessert_zombiegingerbread)
            val loadedDrawable =
                imageLoader.loadDrawable(
                    Icon.createWithResource(
                        "com.android.systemui.tests",
                        R.drawable.dessert_zombiegingerbread
                    )
                )
            assertBitmapEqualToDrawable(loadedDrawable, (bitmap as BitmapDrawable).bitmap)
        }

    @Test
    fun validResourceIcon_loadSize_returnsNull() =
        testScope.runTest {
            assertThat(
                    imageLoader.loadSize(
                        Icon.createWithResource(
                            "com.android.systemui.tests",
                            R.drawable.dessert_zombiegingerbread
                        ),
                        context
                    )
                )
                .isNull()
        }

    @Test
    fun validSystemResourceIcon_returnsBitmapDrawable() =
        testScope.runTest {
            val bitmap =
                Resources.getSystem().getDrawable(android.R.drawable.ic_dialog_alert, context.theme)
            val loadedDrawable =
                imageLoader.loadDrawable(
                    Icon.createWithResource("android", android.R.drawable.ic_dialog_alert)
                )
            assertBitmapEqualToDrawable(loadedDrawable, (bitmap as BitmapDrawable).bitmap)
        }

    @Test
    fun validSystemResourceIcon_loadSize_returnsNull() =
        testScope.runTest {
            assertThat(
                    imageLoader.loadSize(
                        Icon.createWithResource("android", android.R.drawable.ic_dialog_alert),
                        context
                    )
                )
                .isNull()
        }

    @Test
    fun invalidDifferentPackageResourceIcon_returnsNull() =
        testScope.runTest {
            val loadedDrawable =
                imageLoader.loadDrawable(
                    Icon.createWithResource(
                        "noooope.wrong.package",
                        R.drawable.dessert_zombiegingerbread
                    )
                )
            assertThat(loadedDrawable).isNull()
        }

    @Test
    fun invalidDifferentPackageResourceIcon_loadSize_returnsNull() =
        testScope.runTest {
            assertThat(
                    imageLoader.loadDrawable(
                        Icon.createWithResource(
                            "noooope.wrong.package",
                            R.drawable.dessert_zombiegingerbread
                        )
                    )
                )
                .isNull()
        }

    @Test
    fun validBitmapResource_widthMoreRestricted_downsizesKeepingAspectRatio() =
        testScope.runTest {
            val loadedDrawable =
                imageLoader.loadDrawable(ImageLoader.File(imgFile), maxWidth = 160, maxHeight = 160)
            val loadedBitmap = assertBitmapInDrawable(loadedDrawable)
            assertThat(loadedBitmap.width).isEqualTo(160)
            assertThat(loadedBitmap.height).isEqualTo(106)
        }

    @Test
    fun validBitmapResource_heightMoreRestricted_downsizesKeepingAspectRatio() =
        testScope.runTest {
            val loadedDrawable =
                imageLoader.loadDrawable(ImageLoader.File(imgFile), maxWidth = 160, maxHeight = 50)
            val loadedBitmap = assertBitmapInDrawable(loadedDrawable)
            assertThat(loadedBitmap.width).isEqualTo(74)
            assertThat(loadedBitmap.height).isEqualTo(50)
        }

    @Test
    fun validBitmapResource_onlyWidthRestricted_downsizesKeepingAspectRatio() =
        testScope.runTest {
            val loadedDrawable =
                imageLoader.loadDrawable(
                    ImageLoader.File(imgFile),
                    maxWidth = 160,
                    maxHeight = ImageLoader.DO_NOT_RESIZE
                )
            val loadedBitmap = assertBitmapInDrawable(loadedDrawable)
            assertThat(loadedBitmap.width).isEqualTo(160)
            assertThat(loadedBitmap.height).isEqualTo(106)
        }

    @Test
    fun validBitmapResource_onlyHeightRestricted_downsizesKeepingAspectRatio() =
        testScope.runTest {
            val loadedDrawable =
                imageLoader.loadDrawable(
                    ImageLoader.Res(R.drawable.bubble_thumbnail),
                    maxWidth = ImageLoader.DO_NOT_RESIZE,
                    maxHeight = 120
                )
            val loadedBitmap = assertBitmapInDrawable(loadedDrawable)
            assertThat(loadedBitmap.width).isEqualTo(123)
            assertThat(loadedBitmap.height).isEqualTo(120)
        }

    @Test
    fun validVectorDrawable_loadDrawable_successfullyLoaded() =
        testScope.runTest {
            val loadedDrawable = imageLoader.loadDrawable(ImageLoader.Res(R.drawable.ic_settings))
            assertThat(loadedDrawable).isNotNull()
            assertThat(loadedDrawable).isInstanceOf(VectorDrawable::class.java)
        }

    @Test
    fun validVectorDrawable_loadBitmap_returnsNull() =
        testScope.runTest {
            val loadedBitmap = imageLoader.loadBitmap(ImageLoader.Res(R.drawable.ic_settings))
            assertThat(loadedBitmap).isNull()
        }

    @Test
    fun validVectorDrawableIcon_loadDrawable_successfullyLoaded() =
        testScope.runTest {
            val loadedDrawable =
                imageLoader.loadDrawable(Icon.createWithResource(context, R.drawable.ic_settings))
            assertThat(loadedDrawable).isNotNull()
            assertThat(loadedDrawable).isInstanceOf(VectorDrawable::class.java)
        }

    @Test
    fun hardwareAllocator_returnsHardwareBitmap() =
        testScope.runTest {
            val loadedDrawable =
                imageLoader.loadDrawable(
                    ImageLoader.File(imgFile),
                    allocator = ImageDecoder.ALLOCATOR_HARDWARE
                )
            assertThat(loadedDrawable).isNotNull()
            assertThat((loadedDrawable as BitmapDrawable).bitmap.config)
                .isEqualTo(Bitmap.Config.HARDWARE)
        }

    @Test
    fun softwareAllocator_returnsSoftwareBitmap() =
        testScope.runTest {
            val loadedDrawable =
                imageLoader.loadDrawable(
                    ImageLoader.File(imgFile),
                    allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                )
            assertThat(loadedDrawable).isNotNull()
            assertThat((loadedDrawable as BitmapDrawable).bitmap.config)
                .isNotEqualTo(Bitmap.Config.HARDWARE)
        }

    private fun assertBitmapInDrawable(drawable: Drawable?): Bitmap {
        assertThat(drawable).isNotNull()
        assertThat(drawable).isInstanceOf(BitmapDrawable::class.java)
        return (drawable as BitmapDrawable).bitmap
    }

    private fun assertBitmapEqualToDrawable(actual: Drawable?, expected: Bitmap) {
        val actualBitmap = assertBitmapInDrawable(actual)
        assertBitmapEqualToBitmap(actualBitmap, expected)
    }

    private fun assertBitmapEqualToBitmap(actual: Bitmap?, expected: Bitmap) {
        assertThat(actual).isNotNull()
        assertThat(actual?.width).isEqualTo(expected.width)
        assertThat(actual?.height).isEqualTo(expected.height)
    }

    private fun assertSizeEqualToDrawableSize(actual: Size?, expected: Drawable) {
        assertThat(actual).isNotNull()
        assertThat(actual?.width).isEqualTo(expected.intrinsicWidth)
        assertThat(actual?.height).isEqualTo(expected.intrinsicHeight)
    }
}
