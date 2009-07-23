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

package com.google.android.net;

import android.content.ContentResolver;
import android.database.Cursor;
import android.provider.Checkin;
import android.provider.Settings;
import android.util.Config;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A set of rules rewriting and blocking URLs.  Used to offer a point of
 * control for redirecting HTTP requests, often to the Android proxy server.
 *
 * <p>Each rule has the following format:
 *
 * <pre><em>url-prefix</em> [REWRITE <em>new-prefix</em>] [BLOCK]</pre>
 *
 * <p>Any URL which starts with <em>url-prefix</em> will trigger the rule.
 * If BLOCK is specified, requests to that URL will be blocked and fail.
 * If REWRITE is specified, the matching prefix will be removed and replaced
 * with <em>new-prefix</em>.  (If both are specified, BLOCK wins.)  Case is
 * insensitive for the REWRITE and BLOCK keywords, but sensitive for URLs.
 *
 * <p>In Gservices, the value of any key that starts with "url:" will be
 * interpreted as a rule.  The full name of the key is unimportant (but can
 * be used to document the intent of the rule, and must be unique).
 * Example gservices keys:
 *
 * <pre>
 * url:use_proxy_for_calendar = "http://www.google.com/calendar/ REWRITE http://android.clients.google.com/proxy/calendar/"
 * url:stop_crash_reports = "http://android.clients.google.com/crash/ BLOCK"
 * url:use_ssl_for_contacts = "http://www.google.com/m8/ REWRITE https://www.google.com/m8/"
 * </pre>
 */
public class UrlRules {
    public static final String TAG = "UrlRules";
    public static final boolean LOCAL_LOGV = Config.LOGV || false;

    /** Thrown when the rewrite rules can't be parsed. */
    public static class RuleFormatException extends Exception {
        public RuleFormatException(String msg) { super(msg); }
    }

    /** A single rule specifying actions for URLs matching a certain prefix. */
    public static class Rule implements Comparable {
        /** Name assigned to the rule (for logging and debugging). */
        public final String mName;

        /** Prefix required to match this rule. */
        public final String mPrefix;

        /** Text to replace mPrefix with (null to leave alone). */
        public final String mRewrite;

        /** True if matching URLs should be blocked. */
        public final boolean mBlock;

        /** Default rule that does nothing. */
        public static final Rule DEFAULT = new Rule();

        /** Parse a rewrite rule as given in a Gservices value. */
        public Rule(String name, String rule) throws RuleFormatException {
            mName = name;
            String[] words = PATTERN_SPACE_PLUS.split(rule);
            if (words.length == 0) throw new RuleFormatException("Empty rule");

            mPrefix = words[0];
            String rewrite = null;
            boolean block = false;
            for (int pos = 1; pos < words.length; ) {
                String word = words[pos].toLowerCase();
                if (word.equals("rewrite") && pos + 1 < words.length) {
                    rewrite = words[pos + 1];
                    pos += 2;
                } else if (word.equals("block")) {
                    block = true;
                    pos += 1;
                } else {
                    throw new RuleFormatException("Illegal rule: " + rule);
                }
                // TODO: Parse timeout specifications, etc.
            }

            mRewrite = rewrite;
            mBlock = block;
        }

        /** Create the default Rule. */
        private Rule() {
            mName = "DEFAULT";
            mPrefix = "";
            mRewrite = null;
            mBlock = false;
        }

        /**
         * Apply the rule to a particular URL (assumed to match the rule).
         * @param url to rewrite or modify.
         * @return modified URL, or null if the URL is blocked.
         */
         public String apply(String url) {
             if (mBlock) {
                 return null;
             } else if (mRewrite != null) {
                 return mRewrite + url.substring(mPrefix.length());
             } else {
                 return url;
             }
         }

         /** More generic rules are greater than more specific rules. */
         public int compareTo(Object o) {
             return ((Rule) o).mPrefix.compareTo(mPrefix);
         }
    }

    /** Cached rule set from Gservices. */
    private static UrlRules sCachedRules = new UrlRules(new Rule[] {});

    private static final Pattern PATTERN_SPACE_PLUS = Pattern.compile(" +");
    private static final Pattern RULE_PATTERN = Pattern.compile("\\W");

    /** Gservices digest when sCachedRules was cached. */
    private static String sCachedDigest = null;

    /** Currently active set of Rules. */
    private final Rule[] mRules;

    /** Regular expression with one capturing group for each Rule. */
    private final Pattern mPattern;

    /**
     * Create a rewriter from an array of Rules.  Normally used only for
     * testing.  Instead, use {@link #getRules} to get rules from Gservices.
     * @param rules to use.
     */
    public UrlRules(Rule[] rules) {
        // Sort the rules to put the most specific rules first.
        Arrays.sort(rules);

        // Construct a regular expression, escaping all the prefix strings.
        StringBuilder pattern = new StringBuilder("(");
        for (int i = 0; i < rules.length; ++i) {
            if (i > 0) pattern.append(")|(");
            pattern.append(RULE_PATTERN.matcher(rules[i].mPrefix).replaceAll("\\\\$0"));
        }
        mPattern = Pattern.compile(pattern.append(")").toString());
        mRules = rules;
    }

    /**
     * Match a string against every Rule and find one that matches.
     * @param uri to match against the Rules in the rewriter.
     * @return the most specific matching Rule, or Rule.DEFAULT if none match.
     */
    public Rule matchRule(String url) {
        Matcher matcher = mPattern.matcher(url);
        if (matcher.lookingAt()) {
            for (int i = 0; i < mRules.length; ++i) {
                if (matcher.group(i + 1) != null) {
                    return mRules[i];  // Rules are sorted most specific first.
                }
            }
        }
        return Rule.DEFAULT;
    }

    /**
     * Get the (possibly cached) UrlRules based on the rules in Gservices.
     * @param resolver to use for accessing the Gservices database.
     * @return an updated UrlRules instance
     */
    public static synchronized UrlRules getRules(ContentResolver resolver) {
        String digest = Settings.Gservices.getString(resolver,
                Settings.Gservices.PROVISIONING_DIGEST);
        if (sCachedDigest != null && sCachedDigest.equals(digest)) {
            // The digest is the same, so the rules are the same.
            if (LOCAL_LOGV) Log.v(TAG, "Using cached rules for digest: " + digest);
            return sCachedRules;
        }

        if (LOCAL_LOGV) Log.v(TAG, "Scanning for Gservices \"url:*\" rules");
        Cursor cursor = resolver.query(Settings.Gservices.CONTENT_URI,
                new String[] {
                    Settings.Gservices.NAME,
                    Settings.Gservices.VALUE
                },
                Settings.Gservices.NAME + " like \"url:%\"", null,
                Settings.Gservices.NAME);
        try {
            ArrayList<Rule> rules = new ArrayList<Rule>();
            while (cursor.moveToNext()) {
                try {
                    String name = cursor.getString(0).substring(4);  // "url:X"
                    String value = cursor.getString(1);
                    if (value == null || value.length() == 0) continue;
                    if (LOCAL_LOGV) Log.v(TAG, "  Rule " + name + ": " + value);
                    rules.add(new Rule(name, value));
                } catch (RuleFormatException e) {
                    // Oops, Gservices has an invalid rule!  Skip it.
                    Log.e(TAG, "Invalid rule from Gservices", e);
                    Checkin.logEvent(resolver,
                        Checkin.Events.Tag.GSERVICES_ERROR, e.toString());
                }
            }
            sCachedRules = new UrlRules(rules.toArray(new Rule[rules.size()]));
            sCachedDigest = digest;
            if (LOCAL_LOGV) Log.v(TAG, "New rules stored for digest: " + digest);
        } finally {
            cursor.close();
        }

        return sCachedRules;
    }
}
