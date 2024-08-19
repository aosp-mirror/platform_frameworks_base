/*
 * Copyright (C) 2024 The Android Open Source Project
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
 * limitations under the License
 */

package android.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.graphics.text.LineBreakConfig
import android.os.LocaleList
import android.text.Layout
import android.text.ParcelableSpan
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.AccessibilityClickableSpan
import android.text.style.AccessibilityReplacementSpan
import android.text.style.AccessibilityURLSpan
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.EasyEditSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.LineBreakConfigSpan
import android.text.style.LineHeightSpan
import android.text.style.LocaleSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ScaleXSpan
import android.text.style.SpellCheckSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuggestionRangeSpan
import android.text.style.SuggestionSpan
import android.text.style.SuperscriptSpan
import android.text.style.TextAppearanceSpan
import android.text.style.TtsSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import android.util.proto.ProtoInputStream
import android.util.proto.ProtoOutputStream
import android.widget.RemoteViewsSerializers.createAbsoluteSizeSpanFromProto
import android.widget.RemoteViewsSerializers.createAccessibilityClickableSpanFromProto
import android.widget.RemoteViewsSerializers.createAccessibilityReplacementSpanFromProto
import android.widget.RemoteViewsSerializers.createAccessibilityURLSpanFromProto
import android.widget.RemoteViewsSerializers.createAnnotationFromProto
import android.widget.RemoteViewsSerializers.createBackgroundColorSpanFromProto
import android.widget.RemoteViewsSerializers.createBulletSpanFromProto
import android.widget.RemoteViewsSerializers.createCharSequenceFromProto
import android.widget.RemoteViewsSerializers.createEasyEditSpanFromProto
import android.widget.RemoteViewsSerializers.createForegroundColorSpanFromProto
import android.widget.RemoteViewsSerializers.createIconFromProto
import android.widget.RemoteViewsSerializers.createLeadingMarginSpanStandardFromProto
import android.widget.RemoteViewsSerializers.createLineBackgroundSpanStandardFromProto
import android.widget.RemoteViewsSerializers.createLineBreakConfigSpanFromProto
import android.widget.RemoteViewsSerializers.createLineHeightSpanStandardFromProto
import android.widget.RemoteViewsSerializers.createLocaleSpanFromProto
import android.widget.RemoteViewsSerializers.createQuoteSpanFromProto
import android.widget.RemoteViewsSerializers.createRelativeSizeSpanFromProto
import android.widget.RemoteViewsSerializers.createScaleXSpanFromProto
import android.widget.RemoteViewsSerializers.createStrikethroughSpanFromProto
import android.widget.RemoteViewsSerializers.createStyleSpanFromProto
import android.widget.RemoteViewsSerializers.createSubscriptSpanFromProto
import android.widget.RemoteViewsSerializers.createSuggestionRangeSpanFromProto
import android.widget.RemoteViewsSerializers.createSuggestionSpanFromProto
import android.widget.RemoteViewsSerializers.createSuperscriptSpanFromProto
import android.widget.RemoteViewsSerializers.createTextAppearanceSpanFromProto
import android.widget.RemoteViewsSerializers.createTtsSpanFromProto
import android.widget.RemoteViewsSerializers.createTypefaceSpanFromProto
import android.widget.RemoteViewsSerializers.createURLSpanFromProto
import android.widget.RemoteViewsSerializers.createUnderlineSpanFromProto
import android.widget.RemoteViewsSerializers.writeAbsoluteSizeSpanToProto
import android.widget.RemoteViewsSerializers.writeAccessibilityClickableSpanToProto
import android.widget.RemoteViewsSerializers.writeAccessibilityReplacementSpanToProto
import android.widget.RemoteViewsSerializers.writeAccessibilityURLSpanToProto
import android.widget.RemoteViewsSerializers.writeAlignmentSpanStandardToProto
import android.widget.RemoteViewsSerializers.writeAnnotationToProto
import android.widget.RemoteViewsSerializers.writeBackgroundColorSpanToProto
import android.widget.RemoteViewsSerializers.writeBulletSpanToProto
import android.widget.RemoteViewsSerializers.writeCharSequenceToProto
import android.widget.RemoteViewsSerializers.writeEasyEditSpanToProto
import android.widget.RemoteViewsSerializers.writeForegroundColorSpanToProto
import android.widget.RemoteViewsSerializers.writeIconToProto
import android.widget.RemoteViewsSerializers.writeLeadingMarginSpanStandardToProto
import android.widget.RemoteViewsSerializers.writeLineBackgroundSpanStandardToProto
import android.widget.RemoteViewsSerializers.writeLineBreakConfigSpanToProto
import android.widget.RemoteViewsSerializers.writeLineHeightSpanStandardToProto
import android.widget.RemoteViewsSerializers.writeLocaleSpanToProto
import android.widget.RemoteViewsSerializers.writeQuoteSpanToProto
import android.widget.RemoteViewsSerializers.writeRelativeSizeSpanToProto
import android.widget.RemoteViewsSerializers.writeScaleXSpanToProto
import android.widget.RemoteViewsSerializers.writeStrikethroughSpanToProto
import android.widget.RemoteViewsSerializers.writeStyleSpanToProto
import android.widget.RemoteViewsSerializers.writeSubscriptSpanToProto
import android.widget.RemoteViewsSerializers.writeSuggestionRangeSpanToProto
import android.widget.RemoteViewsSerializers.writeSuggestionSpanToProto
import android.widget.RemoteViewsSerializers.writeSuperscriptSpanToProto
import android.widget.RemoteViewsSerializers.writeTextAppearanceSpanToProto
import android.widget.RemoteViewsSerializers.writeTtsSpanToProto
import android.widget.RemoteViewsSerializers.writeTypefaceSpanToProto
import android.widget.RemoteViewsSerializers.writeURLSpanToProto
import android.widget.RemoteViewsSerializers.writeUnderlineSpanToProto
import androidx.core.os.persistableBundleOf
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.frameworks.coretests.R
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.random.Random
import kotlin.test.assertIs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RemoteViewsSerializersTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /**
     * Based on android.graphics.drawable.IconTest#testParcel
     */
    @Test
    fun testWriteIconToProto() {
        val bitmap = (context.getDrawable(R.drawable.landscape) as BitmapDrawable).bitmap
        val bitmapData = ByteArrayOutputStream().let {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            it.toByteArray()
        }

        for (icon in listOf(
            Icon.createWithBitmap(bitmap),
            Icon.createWithAdaptiveBitmap(bitmap),
            Icon.createWithData(bitmapData, 0, bitmapData.size),
            Icon.createWithResource(context, R.drawable.landscape),
            Icon.createWithContentUri("content://com.example.myapp/my_icon"),
            Icon.createWithAdaptiveBitmapContentUri("content://com.example.myapp/my_icon"),
        )) {
            icon.tintList = ColorStateList.valueOf(Color.RED)
            icon.tintBlendMode = BlendMode.SRC_OVER
            val bytes = ProtoOutputStream().let {
                writeIconToProto(it, context.resources, icon)
                it.bytes
            }

            val copy = ProtoInputStream(bytes).let {
                createIconFromProto(it).apply(context.resources)
            }
            assertThat(copy.type).isEqualTo(icon.type)
            assertThat(copy.tintBlendMode).isEqualTo(icon.tintBlendMode)
            assertThat(equalColorStateLists(copy.tintList, icon.tintList)).isTrue()

            when (icon.type) {
                Icon.TYPE_DATA, Icon.TYPE_URI, Icon.TYPE_URI_ADAPTIVE_BITMAP,
                Icon.TYPE_RESOURCE -> {
                    assertThat(copy.sameAs(icon)).isTrue()
                }

                Icon.TYPE_BITMAP, Icon.TYPE_ADAPTIVE_BITMAP -> {
                    assertThat(copy.bitmap.sameAs(icon.bitmap)).isTrue()
                }
            }
        }
    }

    @Test
    fun testWriteToProto() {
        // This test checks that all of the supported spans are written with their start, end and
        // flags. Span-specific data is tested in other tests.
        val string = "0123456789"
        data class SpanSpec(
            val span: ParcelableSpan,
            val start: Int = Random.nextInt(0, string.length),
            val end: Int = Random.nextInt(start, string.length),
            val flags: Int = Random.nextInt(0, 256).shl(Spanned.SPAN_USER_SHIFT),
        )

        val specs = listOf(
            AbsoluteSizeSpan(0),
            AccessibilityClickableSpan(0),
            AccessibilityReplacementSpan(null as String?),
            AccessibilityURLSpan(URLSpan(null)),
            AlignmentSpan.Standard(Layout.Alignment.ALIGN_LEFT),
            android.text.Annotation(null, null),
            BackgroundColorSpan(0),
            BulletSpan(0),
            EasyEditSpan(),
            ForegroundColorSpan(0),
            LeadingMarginSpan.Standard(0),
            LineBackgroundSpan.Standard(0),
            LineBreakConfigSpan(LineBreakConfig.NONE),
            LineHeightSpan.Standard(1),
            LocaleSpan(LocaleList.getDefault()),
            QuoteSpan(),
            RelativeSizeSpan(0f),
            ScaleXSpan(0f),
            SpellCheckSpan(),
            StrikethroughSpan(),
            StyleSpan(0),
            SubscriptSpan(),
            SuggestionRangeSpan(),
            SuggestionSpan(context, arrayOf(), 0),
            SuperscriptSpan(),
            TextAppearanceSpan(context, android.R.style.TextAppearance),
            TtsSpan(null, persistableBundleOf()),
            TypefaceSpan(null),
            UnderlineSpan(),
            URLSpan(null),
        ).map { SpanSpec(it) }

        val original = SpannableStringBuilder(string)
        for (spec in specs) {
            original.setSpan(spec.span, spec.start, spec.end, spec.flags)
        }

        val out = ProtoOutputStream()
        writeCharSequenceToProto(out, original)
        val input = ProtoInputStream(out.bytes)
        val copy = createCharSequenceFromProto(input)

        assertIs<Spanned>(copy)
        for (spec in specs) {
            val spans = copy.getSpans(spec.start, spec.end, Object::class.java)
            android.util.Log.e("TestRunner", "Can I find $spec")
            val span = spans.single { spec.span::class.java.name == it::class.java.name }
            assertEquals(spec.flags, copy.getSpanFlags(span))
        }
    }

    @Test
    fun writeToProto_notSpanned() {
        val string = "Hello World"
        val out = ProtoOutputStream()
        writeCharSequenceToProto(out, string)
        val input = ProtoInputStream(out.bytes)
        val copy = createCharSequenceFromProto(input)
        assertIs<String>(copy)
        assertEquals(copy, string)
    }

    @Test
    fun testAbsoluteSizeSpan() {
        for (span in arrayOf(AbsoluteSizeSpan(0, false), AbsoluteSizeSpan(2, true))) {
            val out = ProtoOutputStream()
            writeAbsoluteSizeSpanToProto(out, span)
            val input = ProtoInputStream(out.bytes)
            val copy = createAbsoluteSizeSpanFromProto(input)
            assertEquals(span.size, copy.size)
            assertEquals(span.dip, copy.dip)
        }
    }

    @Test
    fun testAccessibilityClickableSpan() {
        for (id in 0..1) {
            val span = AccessibilityClickableSpan(id)
            val out = ProtoOutputStream()
            writeAccessibilityClickableSpanToProto(out, span)
            val input = ProtoInputStream(out.bytes)
            val copy = createAccessibilityClickableSpanFromProto(input)
            assertEquals(span.originalClickableSpanId, copy.originalClickableSpanId)
        }
    }

    @Test
    fun testAccessibilityReplacementSpan() {
        for (contentDescription in arrayOf(null, "123")) {
            val span = AccessibilityReplacementSpan(contentDescription)
            val out = ProtoOutputStream()
            writeAccessibilityReplacementSpanToProto(out, span)
            val input = ProtoInputStream(out.bytes)
            val copy = createAccessibilityReplacementSpanFromProto(input)
            assertEquals(span.contentDescription, copy.contentDescription)
        }
    }

    @Test
    fun testAccessibilityURLSpan() {
        for (url in arrayOf(null, "123")) {
            val span = AccessibilityURLSpan(URLSpan(url))
            val out = ProtoOutputStream()
            writeAccessibilityURLSpanToProto(out, span)
            val input = ProtoInputStream(out.bytes)
            val copy = createAccessibilityURLSpanFromProto(input)
            assertEquals(span.url, copy.url)
        }
    }

    @Test
    fun testAlignmentSpanStandard() {
        for (alignment in arrayOf(
            Layout.Alignment.ALIGN_CENTER,
            Layout.Alignment.ALIGN_LEFT,
            Layout.Alignment.ALIGN_NORMAL,
            Layout.Alignment.ALIGN_OPPOSITE)) {
            val span = AlignmentSpan.Standard(alignment)
            val out = ProtoOutputStream()
            writeAlignmentSpanStandardToProto(out, span)
            val input = ProtoInputStream(out.bytes)
            val copy = RemoteViewsSerializers.createAlignmentSpanStandardFromProto(input)
            assertEquals(span.alignment, copy.alignment)
        }
    }

    @Test
    fun testAnnotation() {
        for ((key, value) in arrayOf(null to null, "key" to "value")) {
            val span = android.text.Annotation(key, value)
            val out = ProtoOutputStream()
            writeAnnotationToProto(out, span)
            val input = ProtoInputStream(out.bytes)
            val copy = createAnnotationFromProto(input)
            assertEquals(span.key, copy.key)
            assertEquals(span.value, copy.value)
        }
    }

    @Test
    fun testBackgroundColorSpan() {
        for (color in intArrayOf(Color.RED, Color.MAGENTA)) {
            val span = BackgroundColorSpan(color)
            val out = ProtoOutputStream()
            writeBackgroundColorSpanToProto(out, span)
            val input = ProtoInputStream(out.bytes)
            val copy = createBackgroundColorSpanFromProto(input)
            assertEquals(span.backgroundColor, copy.backgroundColor)
        }
    }

    @Test
    fun testBulletSpan() {
        for (span in arrayOf(BulletSpan(), BulletSpan(2, Color.RED, 5))) {
            val out = ProtoOutputStream()
            writeBulletSpanToProto(out, span)
            val input = ProtoInputStream(out.bytes)
            val copy = createBulletSpanFromProto(input)
            assertEquals(span.getLeadingMargin(true), copy.getLeadingMargin(true))
            assertEquals(span.color, copy.color)
            assertEquals(span.color, copy.color)
            assertEquals(span.gapWidth, copy.gapWidth)
        }
    }

    @Test
    fun testEasyEditSpan() {
        val span = EasyEditSpan()
        val out = ProtoOutputStream()
        writeEasyEditSpanToProto(out, span)
        val input = ProtoInputStream(out.bytes)
        createEasyEditSpanFromProto(input)
    }

    @Test
    fun testForegroundColorSpan() {
        for (color in intArrayOf(0, Color.RED, Color.MAGENTA)) {
            val span = ForegroundColorSpan(color)
            val out = ProtoOutputStream()
            writeForegroundColorSpanToProto(out, span)
            val input = ProtoInputStream(out.bytes)
            val copy = createForegroundColorSpanFromProto(input)
            assertEquals(span.foregroundColor.toLong(), copy.foregroundColor.toLong())
        }
    }

    @Test
    fun testLeadingMarginSpanStandard() {
        for (span in arrayOf(LeadingMarginSpan.Standard(10, 20), LeadingMarginSpan.Standard(0))) {
            val out = ProtoOutputStream()
            writeLeadingMarginSpanStandardToProto(out, span)
            val input = ProtoInputStream(out.bytes)
            val copy = createLeadingMarginSpanStandardFromProto(input)
            assertEquals(span.getLeadingMargin(true), copy.getLeadingMargin(true))
            assertEquals(span.getLeadingMargin(false), copy.getLeadingMargin(false))
        }
    }

    @Test
    fun testLineBackgroundSpan() {
        for (color in intArrayOf(0, Color.RED, Color.MAGENTA)) {
            val span = LineBackgroundSpan.Standard(color)
            val out = ProtoOutputStream()
            writeLineBackgroundSpanStandardToProto(out, span)
            val input = ProtoInputStream(out.bytes)
            val copy = createLineBackgroundSpanStandardFromProto(input)
            assertEquals(span.color, copy.color)
        }
    }

    @Test
    fun testLineBreakConfigSpan() {
        val config = LineBreakConfig.Builder()
            .setLineBreakStyle(LineBreakConfig.LINE_BREAK_STYLE_STRICT)
            .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_AUTO)
            .setHyphenation(LineBreakConfig.HYPHENATION_ENABLED)
            .build()
        val span = LineBreakConfigSpan(config)
        val out = ProtoOutputStream()
        writeLineBreakConfigSpanToProto(out, span)
        val input = ProtoInputStream(out.bytes)
        val copy = createLineBreakConfigSpanFromProto(input).lineBreakConfig
        assertEquals(copy.lineBreakStyle, config.lineBreakStyle)
        assertEquals(copy.lineBreakWordStyle, config.lineBreakWordStyle)
        assertEquals(copy.hyphenation, config.hyphenation)
    }

    @Test
    fun testLineHeightSpanStandard() {
        for (height in 1..2) {
            val span = LineHeightSpan.Standard(height)
            val out = ProtoOutputStream()
            writeLineHeightSpanStandardToProto(out, span)
            val input = ProtoInputStream(out.bytes)
            val copy = createLineHeightSpanStandardFromProto(input)
            assertEquals(span.height, copy.height)
        }
    }

    @Test
    fun testLocaleSpan() {
        for (list in arrayOf(
            LocaleList.getEmptyLocaleList(),
            LocaleList.forLanguageTags("en"),
            LocaleList.forLanguageTags("en-GB,en"),
        )) {
            val span = LocaleSpan(list)
            val out = ProtoOutputStream()
            writeLocaleSpanToProto(out, span)
            val input = ProtoInputStream(out.bytes)
            val copy = createLocaleSpanFromProto(input)
            assertEquals(span.locales[0], copy.locale)
            assertEquals(span.locales, copy.locales)
        }
    }

    @Test
    fun testQuoteSpan() {
        for (color in intArrayOf(0, Color.RED, Color.MAGENTA)) {
            val span = QuoteSpan(color)
            val out = ProtoOutputStream()
            writeQuoteSpanToProto(out, span)
            val input = ProtoInputStream(out.bytes)
            val copy = createQuoteSpanFromProto(input)
            assertEquals(span.color, copy.color)
            assertTrue(span.gapWidth > 0)
            assertTrue(span.stripeWidth > 0)
        }
    }

    @Test
    fun testRelativeSizeSpan() {
        for (size in arrayOf(0f, 1.0f)) {
            val span = RelativeSizeSpan(size)
            val out = ProtoOutputStream()
            writeRelativeSizeSpanToProto(out, span)
            val input = ProtoInputStream(out.bytes)
            val copy = createRelativeSizeSpanFromProto(input)
            assertEquals(span.sizeChange, copy.sizeChange)
        }
    }

    @Test
    fun testScaleXSpan() {
        for (scale in arrayOf(0f, 1.0f)) {
            val span = ScaleXSpan(scale)
            val out = ProtoOutputStream()
            writeScaleXSpanToProto(out, span)
            val input = ProtoInputStream(out.bytes)
            val copy = createScaleXSpanFromProto(input)
            assertEquals(span.scaleX, copy.scaleX, 0.0f)
        }
    }

    @Test
    fun testStrikethroughSpan() {
        val span = StrikethroughSpan()
        val out = ProtoOutputStream()
        writeStrikethroughSpanToProto(out, span)
        val input = ProtoInputStream(out.bytes)
        createStrikethroughSpanFromProto(input)
    }

    @Test
    fun testStyleSpan() {
        for (style in arrayOf(Typeface.BOLD, Typeface.NORMAL)) {
            val span = StyleSpan(style)
            val out = ProtoOutputStream()
            writeStyleSpanToProto(out, span)
            val input = ProtoInputStream(out.bytes)
            val copy = createStyleSpanFromProto(input)
            assertEquals(span.style, copy.style)
        }
    }

    @Test
    fun testSubscriptSpan() {
        val span = SubscriptSpan()
        val out = ProtoOutputStream()
        writeSubscriptSpanToProto(out, span)
        val input = ProtoInputStream(out.bytes)
        createSubscriptSpanFromProto(input)
    }

    @Test
    fun testSuggestionSpan() {
        val suggestions = arrayOf("suggestion1", "suggestion2")
        val span = SuggestionSpan(
            Locale.forLanguageTag("en"), suggestions,
            SuggestionSpan.FLAG_AUTO_CORRECTION)

        val out = ProtoOutputStream()
        writeSuggestionSpanToProto(out, span)
        val input = ProtoInputStream(out.bytes)
        val copy = createSuggestionSpanFromProto(input)
        assertArrayEquals("Should (de)serialize suggestions",
            suggestions, copy.suggestions)
    }

    @Test
    fun testSuggestionRangeSpan() {
        for (backgroundColor in 0..1) {
            val span = SuggestionRangeSpan()
            span.backgroundColor = backgroundColor
            val out = ProtoOutputStream()
            writeSuggestionRangeSpanToProto(out, span)
            val input = ProtoInputStream(out.bytes)
            val copy = createSuggestionRangeSpanFromProto(input)
            assertEquals(span.backgroundColor, copy.backgroundColor)
        }
    }

    @Test
    fun testSuperscriptSpan() {
        val span = SuperscriptSpan()
        val out = ProtoOutputStream()
        writeSuperscriptSpanToProto(out, span)
        val input = ProtoInputStream(out.bytes)
        createSuperscriptSpanFromProto(input)
    }


    @Test
    fun testTextAppearanceSpan_FontResource() {
        val span = TextAppearanceSpan(context, R.style.customFont)
        val out = ProtoOutputStream()
        writeTextAppearanceSpanToProto(out, span)
        val input = ProtoInputStream(out.bytes)
        val copy = createTextAppearanceSpanFromProto(input)
        val tp = TextPaint()
        span.updateDrawState(tp)
        val originalSpanTextWidth = tp.measureText("a")
        copy.updateDrawState(tp)
        assertEquals(originalSpanTextWidth, tp.measureText("a"), 0.0f)
    }

    @Test
    fun testTextAppearanceSpan_FontResource_WithStyle() {
        val span = TextAppearanceSpan(context, R.style.customFontWithStyle)
        val out = ProtoOutputStream()
        writeTextAppearanceSpanToProto(out, span)
        val input = ProtoInputStream(out.bytes)
        val copy = createTextAppearanceSpanFromProto(input)
        val tp = TextPaint()
        span.updateDrawState(tp)
        val originalSpanTextWidth = tp.measureText("a")
        copy.updateDrawState(tp)
        assertEquals(originalSpanTextWidth, tp.measureText("a"), 0.0f)
    }

    @Test
    fun testTextAppearanceSpan_WithAllAttributes() {
        val span = TextAppearanceSpan(context, R.style.textAppearanceWithAllAttributes)
        val out = ProtoOutputStream()
        writeTextAppearanceSpanToProto(out, span)
        val input = ProtoInputStream(out.bytes)
        val copy = createTextAppearanceSpanFromProto(input)
        val originalTextColor = span.textColor
        val copyTextColor = copy.textColor
        val originalLinkTextColor = span.linkTextColor
        val copyLinkTextColor = copy.linkTextColor
        assertEquals(span.family, copy.family)
        // ColorStateList doesn't implement equals(), so we borrow this code
        // from ColorStateListTest.java to test correctness of parceling.
        assertEquals(originalTextColor.isStateful, copyTextColor.isStateful)
        assertEquals(originalTextColor.defaultColor, copyTextColor.defaultColor)
        assertEquals(originalLinkTextColor.isStateful,
            copyLinkTextColor.isStateful)
        assertEquals(originalLinkTextColor.defaultColor,
            copyLinkTextColor.defaultColor)
        assertEquals(span.textSize.toLong(), copy.textSize.toLong())
        assertEquals(span.textStyle.toLong(), copy.textStyle.toLong())
        assertEquals(span.textFontWeight.toLong(), copy.textFontWeight.toLong())
        assertEquals(span.textLocales, copy.textLocales)
        assertEquals(span.shadowColor.toLong(), copy.shadowColor.toLong())
        assertEquals(span.shadowDx, copy.shadowDx, 0.0f)
        assertEquals(span.shadowDy, copy.shadowDy, 0.0f)
        assertEquals(span.shadowRadius, copy.shadowRadius, 0.0f)
        assertEquals(span.fontFeatureSettings, copy.fontFeatureSettings)
        assertEquals(span.fontVariationSettings, copy.fontVariationSettings)
        assertEquals(span.isElegantTextHeight, copy.isElegantTextHeight)
        assertEquals(span.letterSpacing, copy.letterSpacing, 0f)
        // typeface is omitted from TextAppearanceSpan proto
    }

    @Test
    fun testTtsSpan() {
        val bundle = persistableBundleOf(
            "argument.one" to "value.one",
            "argument.two" to "value.two",
            "argument.three" to 3L,
            "argument.four" to 4L,
        )
        val span = TtsSpan("test.type.five", bundle)
        val out = ProtoOutputStream()
        writeTtsSpanToProto(out, span)
        val input = ProtoInputStream(out.bytes)
        val copy = createTtsSpanFromProto(input)
        assertEquals("test.type.five", copy.type)
        val args = copy.args
        assertEquals(4, args.size())
        assertEquals("value.one", args.getString("argument.one"))
        assertEquals("value.two", args.getString("argument.two"))
        assertEquals(3, args.getLong("argument.three"))
        assertEquals(4, args.getLong("argument.four"))
    }


    @Test
    fun testTtsSpan_null() {
        val span = TtsSpan(null, null)
        val out = ProtoOutputStream()
        writeTtsSpanToProto(out, span)
        val input = ProtoInputStream(out.bytes)
        val copy = createTtsSpanFromProto(input)
        assertNull(copy.type)
        assertNull(copy.args)
    }

    @Test
    fun testTypefaceSpan() {
        for (family in arrayOf(null, "monospace")) {
            val span = TypefaceSpan(family)
            val out = ProtoOutputStream()
            writeTypefaceSpanToProto(out, span)
            val input = ProtoInputStream(out.bytes)
            val copy = createTypefaceSpanFromProto(input)
            assertEquals(span.family, copy.family)
        }
    }

    @Test
    fun testUnderlineSpan() {
        val span = UnderlineSpan()
        val out = ProtoOutputStream()
        writeUnderlineSpanToProto(out, span)
        val input = ProtoInputStream(out.bytes)
        createUnderlineSpanFromProto(input)
    }

    @Test
    fun testURLSpan() {
        for (url in arrayOf(null, "content://url")) {
            val span = URLSpan(url)
            val out = ProtoOutputStream()
            writeURLSpanToProto(out, span)
            val input = ProtoInputStream(out.bytes)
            val copy = createURLSpanFromProto(input)
            assertEquals(span.url, copy.url)
        }
    }
}

fun equalColorStateLists(a: ColorStateList?, b: ColorStateList?): Boolean {
    if (a == null && b == null) return true
    return a != null && b != null &&
            a.colors.contentEquals(b.colors) &&
            a.states.foldIndexed(true) { i, acc, it -> acc && it.contentEquals(b.states[i])}
}
