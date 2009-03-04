/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.google.android.util;

import android.content.Context;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ImageSpan;

import java.util.ArrayList;

/**
 * Parses a text message typed by the user looking for smileys.
 */
public class SmileyParser extends AbstractMessageParser {

    private SmileyResources mRes;

    public SmileyParser(String text, SmileyResources res) {
        super(text,
                true,   // smilies
                false,  // acronyms
                false,  // formatting
                false,  // urls
                false,  // music
                false   // me text
        );
        mRes = res;
    }

    @Override
    protected Resources getResources() {
        return mRes;
    }

    /**
     * Retrieves the parsed text as a spannable string object.
     * @param context the context for fetching smiley resources.
     * @return the spannable string as CharSequence.
     */
    public CharSequence getSpannableString(Context context) {
        SpannableStringBuilder builder = new SpannableStringBuilder();

        if (getPartCount() == 0) {
            return "";
        }

        // should have only one part since we parse smiley only
        Part part = getPart(0);
        ArrayList<Token> tokens = part.getTokens();
        int len = tokens.size();
        for (int i = 0; i < len; i++) {
            Token token = tokens.get(i);
            int start = builder.length();
            builder.append(token.getRawText());
            if (token.getType() == AbstractMessageParser.Token.Type.SMILEY) {
                int resid = mRes.getSmileyRes(token.getRawText());
                if (resid != -1) {
                    builder.setSpan(new ImageSpan(context, resid),
                            start,
                            builder.length(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        return builder;
    }

}
