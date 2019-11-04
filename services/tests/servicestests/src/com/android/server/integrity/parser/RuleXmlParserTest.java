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

package com.android.server.integrity.parser;

import static com.android.server.testutils.TestUtils.assertExpectException;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.android.server.integrity.model.Rule;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

@RunWith(JUnit4.class)
public class RuleXmlParserTest {

    private static final String VALID_RULE_XML = "<RuleList>"
            + "<Rule>"
            + "<OpenFormula>"
            + "<Connector>NOT</Connector>"
            + "<AtomicFormula>"
            + "<Key>PACKAGE_NAME</Key>"
            + "<Operator>EQ</Operator>"
            + "<Value>com.app.test</Value>"
            + "</AtomicFormula>"
            + "</OpenFormula>"
            + "<Effect>DENY</Effect>"
            + "</Rule>"
            + "</RuleList>";

    @Test
    public void testXmlString_validRule() {
        RuleParser xmlParser = new RuleXmlParser();

        List<Rule> rules = xmlParser.parse(VALID_RULE_XML);

        assertNotNull(rules);
        assertTrue(rules.isEmpty());
    }

    @Test
    public void testXmlStream_validRule() {
        RuleParser xmlParser = new RuleXmlParser();
        InputStream inputStream = new ByteArrayInputStream(VALID_RULE_XML.getBytes());

        List<Rule> rules = xmlParser.parse(inputStream);

        assertNotNull(rules);
        assertTrue(rules.isEmpty());
    }

    @Test
    public void testXmlString_withNoRuleList() {
        String ruleXmlWithNoRuleList = "<Rule>"
                + "<OpenFormula>"
                + "<Connector>NOT</Connector>"
                + "<AtomicFormula>"
                + "<Key>PACKAGE_NAME</Key>"
                + "<Operator>EQ</Operator>"
                + "<Value>com.app.test</Value>"
                + "</AtomicFormula>"
                + "</OpenFormula>"
                + "<Effect>DENY</Effect>"
                + "</Rule>";
        RuleParser xmlParser = new RuleXmlParser();

        assertExpectException(
                RuntimeException.class,
                /* expectedExceptionMessageRegex */ "Rules must start with <RuleList> tag.",
                () -> xmlParser.parse(ruleXmlWithNoRuleList));
    }

    @Test
    public void testXmlStream_withNoRuleList() {
        String ruleXmlWithNoRuleList = "<Rule>"
                + "<OpenFormula>"
                + "<Connector>NOT</Connector>"
                + "<AtomicFormula>"
                + "<Key>PACKAGE_NAME</Key>"
                + "<Operator>EQ</Operator>"
                + "<Value>com.app.test</Value>"
                + "</AtomicFormula>"
                + "</OpenFormula>"
                + "<Effect>DENY</Effect>"
                + "</Rule>";
        InputStream inputStream = new ByteArrayInputStream(ruleXmlWithNoRuleList.getBytes());
        RuleParser xmlParser = new RuleXmlParser();

        assertExpectException(
                RuntimeException.class,
                /* expectedExceptionMessageRegex */ "Rules must start with <RuleList> tag.",
                () -> xmlParser.parse(inputStream));
    }
}
