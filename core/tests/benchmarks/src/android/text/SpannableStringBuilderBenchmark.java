/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.text;

import com.google.caliper.AfterExperiment;
import com.google.caliper.BeforeExperiment;
import com.google.caliper.Benchmark;
import com.google.caliper.Param;

public class SpannableStringBuilderBenchmark {

    @Param({"android.text.style.ImageSpan",
            "android.text.style.ParagraphStyle",
            "android.text.style.CharacterStyle",
            "java.lang.Object"})
    private String paramType;

    @Param({"1", "4", "16"})
    private String paramStringMult;

    private Class clazz;
    private SpannableStringBuilder builder;

    @BeforeExperiment
    protected void setUp() throws Exception {
        clazz = Class.forName(paramType);
        int strSize = Integer.parseInt(paramStringMult);
        StringBuilder strBuilder = new StringBuilder();
        for (int i = 0; i < strSize; i++) {
            strBuilder.append(TEST_STRING);
        }
        builder = new SpannableStringBuilder(Html.fromHtml(strBuilder.toString()));
    }

    @AfterExperiment
    protected void tearDown() {
        builder.clear();
        builder = null;
    }

    @Benchmark
    public void timeGetSpans(int reps) throws Exception {
        for (int i = 0; i < reps; i++) {
            builder.getSpans(0, builder.length(), clazz);
        }
    }

    //contains 0 ImageSpans, 2 ParagraphSpans, 53 CharacterStyleSpans
    public static String TEST_STRING =
            "<p><span><a href=\"http://android.com\">some link</a></span></p>\n" +
            "<h1 style=\"margin: 0.0px 0.0px 10.0px 0.0px; line-height: 64.0px; font: 62.0px " +
                    "'Helvetica Neue Light'; color: #000000; \"><span>some title</span></h1>\n" +
            "<p><span>by <a href=\"http://android.com\"><span>some name</span></a>\n" +
            "  <a href=\"https://android.com\"><span>some text</span></a></span></p>\n" +
            "<p><span>some date</span></p>\n" +
            "<table cellspacing=\"0\" cellpadding=\"0\">\n" +
            "  <tbody><tr><td valign=\"bottom\">\n" +
            "        <p><span><blockquote>a paragraph</blockquote></span><br></p>\n" +
            "  </tbody></tr></td>\n" +
            "</table>\n" +
            "<h2 style=\"margin: 0.0px 0.0px 0.0px 0.0px; line-height: 38.0px; font: 26.0px " +
                    "'Helvetica Neue Light'; color: #262626; -webkit-text-stroke: #262626\">" +
                    "<span>some header two</span></h2>\n" +
            "<p><span>Lorem ipsum dolor concludaturque. </span></p>\n" +
            "<p><span></span><br></p>\n" +
            "<p><span>Vix te doctus</span></p>\n" +
            "<p><span><b>Error mel</b></span><span>, est ei. <a href=\"http://andorid.com\">" +
                    "<span>asda</span></a> ullamcorper eam.</span></p>\n" +
            "<p><span>adversarium <a href=\"http://android.com\"><span>efficiantur</span></a>, " +
                    "mea te.</span></p>\n" +
            "<p><span></span><br></p>\n" +
            "<h1>Testing display of HTML elements</h1>\n" +
            "<h2>2nd level heading</h2>\n" +
            "<p>test paragraph.</p>\n" +
            "<h3>3rd level heading</h3>\n" +
            "<p>test paragraph.</p>\n" +
            "<h4>4th level heading</h4>\n" +
            "<p>test paragraph.</p>\n" +
            "<h5>5th level heading</h5>\n" +
            "<p>test paragraph.</p>\n" +
            "<h6>6th level heading</h6>\n" +
            "<p>test paragraph.</p>\n" +
            "<h2>level elements</h2>\n" +
            "<p>a normap paragraph(<code>p</code> element).\n" +
            "  with some <strong>strong</strong>.</p>\n" +
            "<div>This is a <code>div</code> element. </div>\n" +
            "<blockquote><p>This is a block quotation with some <em>style</em></p></blockquote>\n" +
            "<address>an address element</address>\n" +
            "<h2>Text-level markup</h2>\n" +
            "<ul>\n" +
            "  <li> <abbr title=\"Cascading Style Sheets\">CSS</abbr> (an abbreviation;\n" +
            "    <code>abbr</code> markup used)\n" +
            "  <li> <acronym title=\"radio detecting and ranging\">radar</acronym>\n" +
            "  <li> <b>bolded</b>\n" +
            "  <li> <big>big thing</big>\n" +
            "  <li> <font size=6>large size</font>\n" +
            "  <li> <font face=Courier>Courier font</font>\n" +
            "  <li> <font color=red>red text</font>\n" +
            "  <li> <cite>Origin of Species</cite>\n" +
            "  <li> <code>a[i] = b[i] + c[i);</code>\n" +
            "  <li> some <del>deleted</del> text\n" +
            "  <li> an <dfn>octet</dfn> is an\n" +
            "  <li> this is <em>very</em> simple\n" +
            "  <li> <i lang=\"la\">Homo sapiens</i>\n" +
            "  <li> some <ins>inserted</ins> text\n" +
            "  <li> type <kbd>yes</kbd> when\n" +
            "  <li> <q>Hello!</q>\n" +
            "  <li> <q>She said <q>Hello!</q></q>\n" +
            "  <li> <samp>ccc</samp>\n" +
            "  <li> <small>important</small>\n" +
            "  <li> <strike>overstruck</strike>\n" +
            "  <li> <strong>this is highlighted text</strong>\n" +
            "  <li> <code>sub</code> and\n" +
            "    <code>sup</code> x<sub>1</sub> and H<sub>2</sub>O\n" +
            "    M<sup>lle</sup>, 1<sup>st</sup>, e<sup>x</sup>, sin<sup>2</sup> <i>x</i>,\n" +
            "    e<sup>x<sup>2</sup></sup> and f(x)<sup>g(x)<sup>a+b+c</sup></sup>\n" +
            "    (where 2 and a+b+c should appear as exponents of exponents).\n" +
            "  <li> <tt>text in monospace font</tt>\n" +
            "  <li> <u>underlined</u> text\n" +
            "  <li> <code>cat</code> <var>filename</var> displays the\n" +
            "    the <var>filename</var>.\n" +
            "</ul>\n";

}
