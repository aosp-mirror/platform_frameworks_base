/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.text;

import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.graphics.text.LineBreakConfig;
import android.os.LocaleList;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@LargeTest
@RunWith(AndroidJUnit4.class)
public class StaticLayoutPerfTest {
    private static final int WORD_LENGTH = 9;  // Random word has 9 characters.
    private static final int WORDS_IN_LINE = 8;  // Roughly, 8 words in a line.
    private static final boolean NO_STYLE_TEXT = false;
    private static final boolean STYLE_TEXT = true;

    private static TextPaint PAINT = new TextPaint();
    private static final int TEXT_WIDTH = WORDS_IN_LINE * WORD_LENGTH * (int) PAINT.getTextSize();

    public StaticLayoutPerfTest() {}

    public static final String JP_TEXT_SHORT = "日本語でのパフォーマンス計測のための例文です。";
    // About 350 chars
    public static final String JP_TEXT_LONG = "日本語でのパフォーマンス計測のための文章ですが、長いです。"
            + "長い文章が必要なのですが、特に書くことが思いつかないので、コロッケの作り方でも書こうと思います。"
            + "じゃがいもを茹でて潰しておきます。私は少し形が残っているほうが好きなので、ある程度のところで潰すのを"
            + "やめます。別のフライパンで軽く塩をして玉ねぎのみじん切りを炒め、透き通ったら、一度取り出します。"
            + "きれいにしたフライパンに、豚ひき肉を入れてあまりイジらずに豚肉を炒めます。"
            + "しっかり火が通ったら炒めた玉ねぎを戻し入れ、塩コショウで味を決めます。"
            + "炒めた肉玉ねぎとじゃがいもをよく混ぜて、1個あたり100gになるように整形します。"
            + "整形したタネに小麦粉、卵、パン粉をつけて揚げます。"
            + "180℃で揚げ、衣がきつね色になったら引き上げて、油を切る。"
            + "盛り付けて出来上がり。";

    public static final String KO_TEXT_SHORT = "모든 인류 구성원의 천부의 존엄성과 동등하고 양도할 수 없는 권리를";
    // About 530 chars
    public static final String KO_TEXT_LONG = "모든 인류 구성원의 천부의 존엄성과 동등하고 양도할 수 없는 권리를"
            + " 인정하는 것이 세계의 자유, 정의 및 평화의 기초이며, 인권에 대한 무시와 경멸이 인류의 양심을 격분시키는"
            + " 만행을 초래하였으며, 인간이 언론과 신앙의 자유, 그리고 공포와 결핍으로부터의 자유를 누릴 수 있는 세계의"
            + " 도래가 모든 사람들의 지고한 열망으로서 천명되어 왔으며, 인간이 폭정과 억압에 대항하는 마지막 수단으로서"
            + " 반란을 일으키도록 강요받지 않으려면, 법에 의한 통치에 의하여 인권이 보호되어야 하는 것이 필수적이며,"
            + "국가간에 우호관계의 발전을 증진하는 것이 필수적이며, 국제연합의 모든 사람들은 그 헌장에서 기본적 인권,"
            + " 인간의 존엄과 가치, 그리고 남녀의 동등한 권리에 대한 신념을 재확인하였으며, 보다 폭넓은 "
            + "자유속에서 사회적 진보와 보다 나은 생활수준을 증진하기로 다짐하였고, 회원국들은 국제연합과 협력하여 인권과"
            + " 기본적 자유의 보편적 존중과 준수를 증진할 것을 스스로 서약하였으며, 이러한 권리와 자유에 대한 공통의"
            + " 이해가 이 서약의 완전한 이행을 위하여 가장 중요하므로,";

    @Rule
    public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    private TextPerfUtils mTextUtil = new TextPerfUtils();

    @Before
    public void setUp() {
        mTextUtil.resetRandom(0 /* seed */);
    }

    private PrecomputedText makeMeasured(CharSequence text, TextPaint paint) {
        PrecomputedText.Params param = new PrecomputedText.Params.Builder(paint).build();
        return PrecomputedText.create(text, param);
    }

    private PrecomputedText makeMeasured(CharSequence text, TextPaint paint, int strategy,
                                      int frequency) {
        PrecomputedText.Params param = new PrecomputedText.Params.Builder(paint)
                .setHyphenationFrequency(frequency).setBreakStrategy(strategy).build();
        return PrecomputedText.create(text, param);
    }

    @Test
    public void testCreate_FixedText_NoStyle_Greedy_NoHyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
        while (state.keepRunning()) {
            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .build();
        }
    }

    @Test
    public void testCreate_RandomText_NoStyled_Greedy_NoHyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .build();
        }
    }

    @Test
    public void testCreate_RandomText_NoStyled_Greedy_Hyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .build();
        }
    }

    @Test
    public void testCreate_RandomText_NoStyled_Balanced_NoHyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                    .build();
        }
    }

    @Test
    public void testCreate_RandomText_NoStyled_Balanced_Hyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                    .build();
        }
    }

    @Test
    public void testCreate_RandomText_NoStyled_Balanced_Hyphenation_Fast() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL_FAST)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                    .build();
        }
    }

    @Test
    public void testCreate_RandomText_Styled_Greedy_NoHyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, STYLE_TEXT);
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .build();
        }
    }

    @Test
    public void testCreate_PrecomputedText_NoStyled_Greedy_NoHyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText text = makeMeasured(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), PAINT,
                    Layout.BREAK_STRATEGY_SIMPLE, Layout.HYPHENATION_FREQUENCY_NONE);
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .build();
        }
    }

    @Test
    public void testCreate_PrecomputedText_NoStyled_Greedy_NoHyphenation_DirDifferent() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText text = makeMeasured(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), PAINT,
                    Layout.BREAK_STRATEGY_SIMPLE, Layout.HYPHENATION_FREQUENCY_NONE);
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .setTextDirection(TextDirectionHeuristics.RTL)
                    .build();
        }
    }

    @Test
    public void testCreate_PrecomputedText_NoStyled_Greedy_Hyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText text = makeMeasured(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), PAINT,
                    Layout.BREAK_STRATEGY_SIMPLE, Layout.HYPHENATION_FREQUENCY_NORMAL);
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .build();
        }
    }

    @Test
    public void testCreate_PrecomputedText_NoStyled_Balanced_NoHyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText text = makeMeasured(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), PAINT,
                    Layout.BREAK_STRATEGY_BALANCED, Layout.HYPHENATION_FREQUENCY_NONE);
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                    .build();
        }
    }

    @Test
    public void testCreate_PrecomputedText_NoStyled_Balanced_Hyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText text = makeMeasured(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), PAINT,
                    Layout.BREAK_STRATEGY_BALANCED, Layout.HYPHENATION_FREQUENCY_NORMAL);
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                    .build();
        }
    }

    @Test
    public void testCreate_PrecomputedText_Styled_Greedy_NoHyphenation() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText text = makeMeasured(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, STYLE_TEXT), PAINT,
                    Layout.BREAK_STRATEGY_SIMPLE, Layout.HYPHENATION_FREQUENCY_NONE);
            state.resumeTiming();

            StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                    .build();
        }
    }

    @Test
    public void testDraw_FixedText_NoStyled() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
        final RenderNode node = RenderNode.create("benchmark", null);
        while (state.keepRunning()) {
            state.pauseTiming();
            final StaticLayout layout =
                    StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH).build();
            final RecordingCanvas c = node.beginRecording(1200, 200);
            state.resumeTiming();

            layout.draw(c);
            node.endRecording();
        }
    }

    @Test
    public void testDraw_RandomText_Styled() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final RenderNode node = RenderNode.create("benchmark", null);
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, STYLE_TEXT);
            final StaticLayout layout =
                    StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH).build();
            final RecordingCanvas c = node.beginRecording(1200, 200);
            state.resumeTiming();

            layout.draw(c);
            node.endRecording();
        }
    }

    @Test
    public void testDraw_RandomText_NoStyled() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final RenderNode node = RenderNode.create("benchmark", null);
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            final StaticLayout layout =
                    StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH).build();
            final RecordingCanvas c = node.beginRecording(1200, 200);
            state.resumeTiming();

            layout.draw(c);
            node.endRecording();
        }
    }

    @Test
    public void testDraw_RandomText_Styled_WithoutCache() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final RenderNode node = RenderNode.create("benchmark", null);
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, STYLE_TEXT);
            final StaticLayout layout =
                    StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH).build();
            final RecordingCanvas c = node.beginRecording(1200, 200);
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            layout.draw(c);
            node.endRecording();
        }
    }

    @Test
    public void testDraw_RandomText_NoStyled_WithoutCache() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final RenderNode node = RenderNode.create("benchmark", null);
        while (state.keepRunning()) {
            state.pauseTiming();
            final CharSequence text = mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT);
            final StaticLayout layout =
                    StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH).build();
            final RecordingCanvas c = node.beginRecording(1200, 200);
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            layout.draw(c);
            node.endRecording();
        }
    }

    @Test
    public void testDraw_PrecomputedText_Styled() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final RenderNode node = RenderNode.create("benchmark", null);
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText text = makeMeasured(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, STYLE_TEXT), PAINT);
            final StaticLayout layout =
                    StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH).build();
            final RecordingCanvas c = node.beginRecording(1200, 200);
            state.resumeTiming();

            layout.draw(c);
            node.endRecording();
        }
    }

    @Test
    public void testDraw_PrecomputedText_NoStyled() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final RenderNode node = RenderNode.create("benchmark", null);
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText text = makeMeasured(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), PAINT);
            final StaticLayout layout =
                    StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH).build();
            final RecordingCanvas c = node.beginRecording(1200, 200);
            state.resumeTiming();

            layout.draw(c);
            node.endRecording();
        }
    }

    @Test
    public void testDraw_PrecomputedText_Styled_WithoutCache() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final RenderNode node = RenderNode.create("benchmark", null);
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText text = makeMeasured(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, STYLE_TEXT), PAINT);
            final StaticLayout layout =
                    StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH).build();
            final RecordingCanvas c = node.beginRecording(1200, 200);
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            layout.draw(c);
            node.endRecording();
        }
    }

    @Test
    public void testDraw_PrecomputedText_NoStyled_WithoutCache() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final RenderNode node = RenderNode.create("benchmark", null);
        while (state.keepRunning()) {
            state.pauseTiming();
            final PrecomputedText text = makeMeasured(
                    mTextUtil.nextRandomParagraph(WORD_LENGTH, NO_STYLE_TEXT), PAINT);
            final StaticLayout layout =
                    StaticLayout.Builder.obtain(text, 0, text.length(), PAINT, TEXT_WIDTH).build();
            final RecordingCanvas c = node.beginRecording(1200, 200);
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();

            layout.draw(c);
            node.endRecording();
        }
    }

    @Test
    public void testCreate_JPText_Phrase_Short() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final String text = JP_TEXT_SHORT;
        final LineBreakConfig config = new LineBreakConfig.Builder()
                .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE)
                .build();
        final TextPaint paint = new TextPaint(PAINT);
        paint.setTextLocales(LocaleList.forLanguageTags("ja-JP"));
        while (state.keepRunning()) {
            state.pauseTiming();
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();
            StaticLayout.Builder.obtain(text, 0, text.length(), paint, TEXT_WIDTH)
                    .setLineBreakConfig(config)
                    .build();
        }
    }

    @Test
    public void testCreate_JPText_Phrase_Long() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final String text = JP_TEXT_LONG;
        final LineBreakConfig config = new LineBreakConfig.Builder()
                .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE)
                .build();
        final TextPaint paint = new TextPaint(PAINT);
        paint.setTextLocales(LocaleList.forLanguageTags("ja-JP"));
        while (state.keepRunning()) {
            state.pauseTiming();
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();
            StaticLayout.Builder.obtain(text, 0, text.length(), paint, TEXT_WIDTH)
                    .setLineBreakConfig(config)
                    .build();
        }
    }

    @Test
    public void testCreate_JPText_Phrase_LongLong() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final String text = JP_TEXT_LONG.repeat(20);  // 250 * 20 = 7000 chars
        final LineBreakConfig config = new LineBreakConfig.Builder()
                .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE)
                .build();
        final TextPaint paint = new TextPaint(PAINT);
        paint.setTextLocales(LocaleList.forLanguageTags("ja-JP"));
        while (state.keepRunning()) {
            state.pauseTiming();
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();
            StaticLayout.Builder.obtain(text, 0, text.length(), paint, TEXT_WIDTH)
                    .setLineBreakConfig(config)
                    .build();
        }
    }

    @Test
    public void testCreate_JPText_NoPhrase_Short() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final String text = JP_TEXT_SHORT;
        final LineBreakConfig config = new LineBreakConfig.Builder()
                .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE)
                .build();
        final TextPaint paint = new TextPaint(PAINT);
        paint.setTextLocales(LocaleList.forLanguageTags("ja-JP"));
        while (state.keepRunning()) {
            state.pauseTiming();
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();
            StaticLayout.Builder.obtain(text, 0, text.length(), paint, TEXT_WIDTH)
                    .setLineBreakConfig(config)
                    .build();
        }
    }

    @Test
    public void testCreate_JPText_NoPhrase_Long() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final String text = JP_TEXT_LONG;
        final LineBreakConfig config = new LineBreakConfig.Builder()
                .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE)
                .build();
        final TextPaint paint = new TextPaint(PAINT);
        paint.setTextLocales(LocaleList.forLanguageTags("ja-JP"));
        while (state.keepRunning()) {
            state.pauseTiming();
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();
            StaticLayout.Builder.obtain(text, 0, text.length(), paint, TEXT_WIDTH)
                    .setLineBreakConfig(config)
                    .build();
        }
    }

    @Test
    public void testCreate_JPText_NoPhrase_LongLong() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final String text = JP_TEXT_LONG.repeat(20);  // 250 * 20 = 7000 chars
        final LineBreakConfig config = new LineBreakConfig.Builder()
                .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE)
                .build();
        final TextPaint paint = new TextPaint(PAINT);
        paint.setTextLocales(LocaleList.forLanguageTags("ja-JP"));
        while (state.keepRunning()) {
            state.pauseTiming();
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();
            StaticLayout.Builder.obtain(text, 0, text.length(), paint, TEXT_WIDTH)
                    .setLineBreakConfig(config)
                    .build();
        }
    }

    @Test
    public void testCreate_KOText_Phrase_Short() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final String text = KO_TEXT_SHORT;
        final LineBreakConfig config = new LineBreakConfig.Builder()
                .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE)
                .build();
        final TextPaint paint = new TextPaint(PAINT);
        paint.setTextLocales(LocaleList.forLanguageTags("ko-KR"));
        while (state.keepRunning()) {
            state.pauseTiming();
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();
            StaticLayout.Builder.obtain(text, 0, text.length(), paint, TEXT_WIDTH)
                    .setLineBreakConfig(config)
                    .build();
        }
    }

    @Test
    public void testCreate_KOText_Phrase_Long() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final String text = KO_TEXT_LONG;
        final LineBreakConfig config = new LineBreakConfig.Builder()
                .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE)
                .build();
        final TextPaint paint = new TextPaint(PAINT);
        paint.setTextLocales(LocaleList.forLanguageTags("ko-KR"));
        while (state.keepRunning()) {
            state.pauseTiming();
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();
            StaticLayout.Builder.obtain(text, 0, text.length(), paint, TEXT_WIDTH)
                    .setLineBreakConfig(config)
                    .build();
        }
    }

    @Test
    public void testCreate_KOText_Phrase_LongLong() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final String text = KO_TEXT_LONG.repeat(20);  // 250 * 20 = 7000 chars
        final LineBreakConfig config = new LineBreakConfig.Builder()
                .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE)
                .build();
        final TextPaint paint = new TextPaint(PAINT);
        paint.setTextLocales(LocaleList.forLanguageTags("ko-KR"));
        while (state.keepRunning()) {
            state.pauseTiming();
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();
            StaticLayout.Builder.obtain(text, 0, text.length(), paint, TEXT_WIDTH)
                    .setLineBreakConfig(config)
                    .build();
        }
    }

    @Test
    public void testCreate_KOText_NoPhrase_Short() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final String text = KO_TEXT_SHORT;
        final LineBreakConfig config = new LineBreakConfig.Builder()
                .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE)
                .build();
        final TextPaint paint = new TextPaint(PAINT);
        paint.setTextLocales(LocaleList.forLanguageTags("ko-KR"));
        while (state.keepRunning()) {
            state.pauseTiming();
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();
            StaticLayout.Builder.obtain(text, 0, text.length(), paint, TEXT_WIDTH)
                    .setLineBreakConfig(config)
                    .build();
        }
    }

    @Test
    public void testCreate_KOText_NoPhrase_Long() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final String text = KO_TEXT_LONG;
        final LineBreakConfig config = new LineBreakConfig.Builder()
                .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE)
                .build();
        final TextPaint paint = new TextPaint(PAINT);
        paint.setTextLocales(LocaleList.forLanguageTags("ko-KR"));
        while (state.keepRunning()) {
            state.pauseTiming();
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();
            StaticLayout.Builder.obtain(text, 0, text.length(), paint, TEXT_WIDTH)
                    .setLineBreakConfig(config)
                    .build();
        }
    }

    @Test
    public void testCreate_KOText_NoPhrase_LongLong() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        final String text = KO_TEXT_LONG.repeat(20);  // 520 * 20 = 10400 chars
        final LineBreakConfig config = new LineBreakConfig.Builder()
                .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE)
                .build();
        final TextPaint paint = new TextPaint(PAINT);
        paint.setTextLocales(LocaleList.forLanguageTags("ko-KR"));
        while (state.keepRunning()) {
            state.pauseTiming();
            Canvas.freeTextLayoutCaches();
            state.resumeTiming();
            StaticLayout.Builder.obtain(text, 0, text.length(), paint, TEXT_WIDTH)
                    .setLineBreakConfig(config)
                    .build();
        }
    }
}
