package com.android.databinder.parser;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

/**
 * Created by yboyar on 11/15/14.
 */
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

    }
}
