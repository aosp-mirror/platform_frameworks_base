/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.libcore.regression;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.text.BreakIterator;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;

@RunWith(JUnitParamsRunner.class)
@LargeTest
public final class BreakIteratorPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    public enum Text {
        LIPSUM(
                Locale.US,
                "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Morbi mollis consequat"
                    + " nisl non pharetra. Praesent pretium vehicula odio sed ultrices. Aenean a"
                    + " felis libero. Vivamus sed commodo nibh. Pellentesque turpis lectus,"
                    + " euismod vel ante nec, cursus posuere orci. Suspendisse velit neque,"
                    + " fermentum luctus ultrices in, ultrices vitae arcu. Duis tincidunt cursus"
                    + " lorem. Nam ultricies accumsan quam vitae imperdiet. Pellentesque habitant"
                    + " morbi tristique senectus et netus et malesuada fames ac turpis egestas."
                    + " Quisque aliquet pretium nisi, eget laoreet enim molestie sit amet. Class"
                    + " aptent taciti sociosqu ad litora torquent per conubia nostra, per inceptos"
                    + " himenaeos.\n"
                    + "Nam dapibus aliquam lacus ac suscipit. Proin in nibh sit amet purus congue"
                    + " laoreet eget quis nisl. Morbi gravida dignissim justo, a venenatis ante"
                    + " pulvinar at. Lorem ipsum dolor sit amet, consectetur adipiscing elit."
                    + " Proin ultrices vestibulum dui, vel aliquam lacus aliquam quis. Duis"
                    + " fringilla sapien ac lacus egestas, vel adipiscing elit euismod. Donec non"
                    + " tellus odio. Donec gravida eu massa ac feugiat. Aliquam erat volutpat."
                    + " Praesent id adipiscing metus, nec laoreet enim. Aliquam vitae posuere"
                    + " turpis. Mauris ac pharetra sem. In at placerat tortor. Vivamus ac vehicula"
                    + " neque. Cras volutpat ullamcorper massa et varius. Praesent sagittis neque"
                    + " vitae nulla euismod pharetra.\n"
                    + "Sed placerat sapien non molestie sollicitudin. Nullam sit amet dictum quam."
                    + " Etiam tincidunt tortor vel pretium vehicula. Praesent fringilla ipsum vel"
                    + " velit luctus dignissim. Nulla massa ligula, mattis in enim et, mattis"
                    + " lacinia odio. Suspendisse tristique urna a orci commodo tempor. Duis"
                    + " lacinia egestas arcu a sollicitudin.\n"
                    + "In ac feugiat lacus. Nunc fermentum eu est at tristique. Pellentesque quis"
                    + " ligula et orci placerat lacinia. Maecenas quis mauris diam. Etiam mi"
                    + " ipsum, tempus in purus quis, euismod faucibus orci. Nulla facilisi."
                    + " Praesent sit amet sapien vel elit porta adipiscing. Phasellus sit amet"
                    + " volutpat diam.\n"
                    + "Proin bibendum elit non lacus pharetra, quis eleifend tellus placerat."
                    + " Nulla facilisi. Maecenas ante diam, pellentesque mattis mattis in, porta"
                    + " ut lorem. Vestibulum ante ipsum primis in faucibus orci luctus et ultrices"
                    + " posuere cubilia Curae; Nunc interdum tristique metus, in scelerisque odio"
                    + " fermentum eget. Cras nec venenatis lacus. Aenean euismod eget metus quis"
                    + " molestie. Cras tincidunt dolor ut massa ornare, in elementum lacus auctor."
                    + " Cras sodales nisl lacus, id ultrices ligula varius at. Sed tristique sit"
                    + " amet tellus vel mollis. Sed sed sollicitudin quam. Sed sed adipiscing"
                    + " risus, et dictum orci. Cras tempor pellentesque turpis et tempus."),
        LONGPARA(
                Locale.US,
                "During dinner, Mr. Bennet scarcely spoke at all; but when the servants were"
                    + " withdrawn, he thought it time to have some conversation with his guest,"
                    + " and therefore started a subject in which he expected him to shine, by"
                    + " observing that he seemed very fortunate in his patroness. Lady Catherine"
                    + " de Bourgh's attention to his wishes, and consideration for his comfort,"
                    + " appeared very remarkable. Mr. Bennet could not have chosen better. Mr."
                    + " Collins was eloquent in her praise. The subject elevated him to more than"
                    + " usual solemnity of manner, and with a most important aspect he protested"
                    + " that \"he had never in his life witnessed such behaviour in a person of"
                    + " rank--such affability and condescension, as he had himself experienced"
                    + " from Lady Catherine. She had been graciously pleased to approve of both of"
                    + " the discourses which he had already had the honour of preaching before"
                    + " her. She had also asked him twice to dine at Rosings, and had sent for him"
                    + " only the Saturday before, to make up her pool of quadrille in the evening."
                    + " Lady Catherine was reckoned proud by many people he knew, but _he_ had"
                    + " never seen anything but affability in her. She had always spoken to him as"
                    + " she would to any other gentleman; she made not the smallest objection to"
                    + " his joining in the society of the neighbourhood nor to his leaving the"
                    + " parish occasionally for a week or two, to visit his relations. She had"
                    + " even condescended to advise him to marry as soon as he could, provided he"
                    + " chose with discretion; and had once paid him a visit in his humble"
                    + " parsonage, where she had perfectly approved all the alterations he had"
                    + " been making, and had even vouchsafed to suggest some herself--some shelves"
                    + " in the closet up stairs.\""),
        GERMAN(
                Locale.GERMANY,
                "Aber dieser Freiheit setzte endlich der Winter ein Ziel. Draußen auf den Feldern"
                    + " und den hohen Bergen lag der Schnee und Peter wäre in seinem dünnen"
                    + " Leinwandjäckchen bald erfroren. Es war also seine einzige Freude, hinaus"
                    + " vor die Hütte zu treten und den Sperlingen Brotkrümchen zu streuen, was er"
                    + " sich jedesmal an seinem Frühstück absparte. Wenn nun die Vögel so lustig"
                    + " zwitscherten und um ihn herumflogen, da klopfte ihm das Herz vor Lust, und"
                    + " oft gab er ihnen sein ganzes Stück Schwarzbrot, ohne daran zu denken, daß"
                    + " er dafür alsdann selbst hungern müsse."),
        THAI(
                Locale.forLanguageTag("th-TH"),
                "เป็นสำเนียงทางการของภาษาไทย"
                    + " เดิมทีเป็นการผสมผสานกันระหว่างสำเนียงอยุธยาและชาวไทยเชื้อสายจีนรุ่นหลังที่"
                    + "พูดไทยแทนกลุ่มภาษาจีน"
                    + " ลักษณะเด่นคือมีการออกเสียงที่ชัดเจนและแข็งกระด้างซึ่งได้รับอิทธิพลจากภาษาแต"
                    + "้จิ๋ว การออกเสียงพยัญชนะ สระ การผันวรรณยุกต์ที่ในภาษาไทยมาตรฐาน"
                    + " มาจากสำเนียงถิ่นนี้ในขณะที่ภาษาไทยสำเนียงอื่นล้วนเหน่อทั้งสิ้น"
                    + " คำศัพท์ที่ใช้ในสำเนียงกรุงเทพจำนวนมากได้รับมาจากกลุ่มภาษาจีนเช่นคำว่า โป๊,"
                    + " เฮ็ง, อาหมวย, อาซิ่ม ซึ่งมาจากภาษาแต้จิ๋ว และจากภาษาจีนเช่น ถู(涂), ชิ่ว(去"
                    + " อ่านว่า\"ชู่\") และคำว่า ทาย(猜 อ่านว่า \"ชาย\") เป็นต้น"
                    + " เนื่องจากสำเนียงกรุงเทพได้รับอิทธิพลมาจากภาษาจีนดังนั้นตัวอักษร \"ร\""
                    + " มักออกเสียงเหมารวมเป็น \"ล\" หรือคำควบกล่ำบางคำถูกละทิ้งไปด้วยเช่น รู้"
                    + " เป็น ลู้, เรื่อง เป็น เลื่อง หรือ ประเทศ เป็น ปะเทศ"
                    + " เป็นต้นสร้างความลำบากให้แก่ต่างชาติที่ต้องการเรียนภาษาไทย"
                    + " แต่อย่างไรก็ตามผู้ที่พูดสำเนียงถิ่นนี้ก็สามารถออกอักขระภาษาไทยตามมาตรฐานได"
                    + "้อย่างถูกต้องเพียงแต่มักเผลอไม่ค่อยออกเสียง"),
        THAI2(Locale.forLanguageTag("th-TH"), "this is the word browser in Thai: เบราว์เซอร์"),
        TABS(Locale.US, "one\t\t\t\t\t\t\t\t\t\t\t\t\t\ttwo\n"),
        ACCENT(Locale.US, "e\u0301\u00e9\nwhich is:\n\"e\\u0301\\u00e9\""),
        EMOJI(Locale.US, ">>\ud83d\ude01<<\nwhich is:\n\">>\\ud83d\\ude01<<\""),
        SPACES(Locale.US, "     leading spaces      and trailing ones too      "),
        EMPTY(Locale.US, ""),
        NEWLINE(Locale.US, "\\n:\n"),
        BIDI(
                Locale.forLanguageTag("he-IL"),
                "Sarah שרה is spelled sin ש resh ר heh ה from right to left.");

        final Locale mLocale;
        final String mText;

        Text(Locale locale, String text) {
            this.mText = text;
            this.mLocale = locale;
        }
    }

    public static Collection<Object[]> getData() {
        return Arrays.asList(
                new Object[][] {
                    {Text.ACCENT}, {Text.BIDI}, {Text.EMOJI}, {Text.EMPTY}, {Text.GERMAN},
                    {Text.LIPSUM}, {Text.LONGPARA}, {Text.NEWLINE}, {Text.SPACES}, {Text.TABS},
                    {Text.THAI}, {Text.THAI2}
                });
    }

    @Test
    @Parameters(method = "getData")
    public void timeBreakIterator(Text text) {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            BreakIterator it = BreakIterator.getLineInstance(text.mLocale);
            it.setText(text.mText);

            while (it.next() != BreakIterator.DONE) {
                // Keep iterating
            }
        }
    }

    @Test
    @Parameters(method = "getData")
    public void timeIcuBreakIterator(Text text) {
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            android.icu.text.BreakIterator it =
                    android.icu.text.BreakIterator.getLineInstance(text.mLocale);
            it.setText(text.mText);

            while (it.next() != android.icu.text.BreakIterator.DONE) {
                // Keep iterating
            }
        }
    }
}
