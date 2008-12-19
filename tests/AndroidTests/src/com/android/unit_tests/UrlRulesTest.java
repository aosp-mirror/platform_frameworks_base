/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.unit_tests;

import android.content.ContentResolver;
import android.provider.Settings;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;

import com.google.android.net.UrlRules;
import static com.google.android.net.UrlRules.Rule;

/** Test loading and matching URL rewrite rules for UrlRules.  */
public class UrlRulesTest extends AndroidTestCase {
    @SmallTest
    public void testEmptyRules() {
        UrlRules rules = new UrlRules(new Rule[] { });
        assertTrue(rules.matchRule("http://foo.bar/") == Rule.DEFAULT);
    }

    @SmallTest
    public void testInvalidRule() throws Exception {
        try {
            new Rule("rule", "foo bar");
        } catch (Exception e) {
            // Re-throw any exception except the one we're looking for.
            if (!e.toString().contains("Illegal rule: foo bar")) throw e;
        }
    }

    @SmallTest
    public void testRewriteRule() throws UrlRules.RuleFormatException {
        Rule rule = new Rule("test_rule",
                "http://foo.bar/ rewrite http://bar.foo/");
        assertEquals("test_rule", rule.mName);
        assertEquals("http://foo.bar/", rule.mPrefix);
        assertEquals("http://bar.foo/", rule.mRewrite);
        assertFalse(rule.mBlock);
        assertEquals("http://bar.foo/bat", rule.apply("http://foo.bar/bat"));
    }

    @SmallTest
    public void testBlockRule() throws UrlRules.RuleFormatException {
        Rule rule = new Rule("test_rule",
                "http://foo.bar/ block");
        assertEquals("test_rule", rule.mName);
        assertEquals("http://foo.bar/", rule.mPrefix);
        assertTrue(rule.mRewrite == null);
        assertTrue(rule.mBlock);
        assertTrue(rule.apply("http://foo.bar/bat") == null);
    }

    @SmallTest
    public void testMatchRule() throws UrlRules.RuleFormatException {
        UrlRules rules = new UrlRules(new Rule[] {
            new Rule("12", "http://one.two/ rewrite http://buckle.my.shoe/"),
            new Rule("34", "http://three.four/ rewrite http://close.the.door/"),
            new Rule("56", "http://five.six/ rewrite http://pick.up.sticks/"),
        });

        assertTrue(rules.matchRule("https://one.two/") == Rule.DEFAULT);
        assertTrue(rules.matchRule("http://one.two") == Rule.DEFAULT);
        assertEquals("12", rules.matchRule("http://one.two/foo").mName);

        String u = "http://five.six/bar";
        assertEquals("http://pick.up.sticks/bar", rules.matchRule(u).apply(u));
    }

    @SmallTest
    public void testAmbiguousMatch() throws UrlRules.RuleFormatException {
        // Rule is the longest match wins.
        UrlRules rules = new UrlRules(new Rule[] {
            new Rule("1", "http://xyz/one rewrite http://rewrite/"),
            new Rule("123", "http://xyz/onetwothree rewrite http://rewrite/"),
            new Rule("12", "http://xyz/onetwo rewrite http://rewrite/"),
        });

        assertEquals("1", rules.matchRule("http://xyz/one").mName);
        assertEquals("1", rules.matchRule("http://xyz/one...").mName);
        assertEquals("12", rules.matchRule("http://xyz/onetwo...").mName);
        assertEquals("123", rules.matchRule("http://xyz/onetwothree...").mName);

    }

    @MediumTest
    public void testGservicesRules() {
        // TODO: use a MockContentProvider/MockContentResolver instead.
        ContentResolver r = getContext().getContentResolver();

        // Update the digest, so the UrlRules cache is reloaded.
        Settings.Gservices.putString(r, "digest", "testGservicesRules");
        Settings.Gservices.putString(r, "url:blank_test", "");
        Settings.Gservices.putString(r, "url:test",
                "http://foo.bar/ rewrite http://bar.foo/");

        UrlRules rules = UrlRules.getRules(r);  // Don't crash, please.  :)
        assertTrue(rules.matchRule("http://bar.foo/") == Rule.DEFAULT);

        Rule rule = rules.matchRule("http://foo.bar/bat");
        assertEquals("test", rule.mName);
        assertEquals("http://foo.bar/", rule.mPrefix);
        assertEquals("http://bar.foo/", rule.mRewrite);
        assertFalse(rule.mBlock);
    }
}
