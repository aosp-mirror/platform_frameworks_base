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

package com.android.databinder.parser;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

public class Main {
    static String input = "`name` + last_name";
    static class Field {
        String fieldName;
    }
    public static void main(String[] args) {
        // ANTLRInputStream inputStream = new ANTLRInputStream(input);
//         DataBinderLexer lexer = new DataBinderLexer(inputStream);
//         CommonTokenStream tokenStream = new CommonTokenStream(lexer);
//         DataBinderParser parser = new DataBinderParser(tokenStream);
//         ParseTreeWalker walker = new ParseTreeWalker();
//         System.out.println(parser.expr().toStringTree(parser));
        float[] aa = new float[2];

    }
}
