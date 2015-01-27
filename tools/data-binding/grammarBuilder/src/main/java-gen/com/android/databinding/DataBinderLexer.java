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

// Generated from DataBinder.g4 by ANTLR 4.4
package com.android.databinding;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.*;

public class DataBinderLexer extends Lexer {
	public static final int
		T__11=1, T__10=2, T__9=3, T__8=4, T__7=5, T__6=6, T__5=7, T__4=8, T__3=9, 
		T__2=10, T__1=11, T__0=12, Identifier=13, INT=14, BOOLEAN=15, HackyStringLiteral=16, 
		StringLiteral=17, CharacterLiteral=18, WS=19;
	public static String[] modeNames = {
		"DEFAULT_MODE"
	};

	public static final String[] tokenNames = {
		"'\\u0000'", "'\\u0001'", "'\\u0002'", "'\\u0003'", "'\\u0004'", "'\\u0005'", 
		"'\\u0006'", "'\\u0007'", "'\b'", "'\t'", "'\n'", "'\\u000B'", "'\f'", 
		"'\r'", "'\\u000E'", "'\\u000F'", "'\\u0010'", "'\\u0011'", "'\\u0012'", 
		"'\\u0013'"
	};
	public static final String[] ruleNames = {
		"T__11", "T__10", "T__9", "T__8", "T__7", "T__6", "T__5", "T__4", "T__3", 
		"T__2", "T__1", "T__0", "Identifier", "INT", "BOOLEAN", "JavaLetter", 
		"JavaLetterOrDigit", "HackyStringLiteral", "StringLiteral", "CharacterLiteral", 
		"SingleCharacter", "StringCharacters", "StringCharacter", "WS"
	};


	public DataBinderLexer(CharStream input) {
		super(input);
		_interp = new LexerATNSimulator(this,_ATN);
	}

	@Override
	public String getGrammarFileName() { return "DataBinder.g4"; }

	@Override
	public String[] getTokenNames() { return tokenNames; }

	@Override
	public String[] getRuleNames() { return ruleNames; }

	@Override
	public String getSerializedATN() { return _serializedATN; }

	@Override
	public String[] getModeNames() { return modeNames; }

	@Override
	public boolean sempred(RuleContext _localctx, int ruleIndex, int predIndex) {
		switch (ruleIndex) {
		case 15 : return JavaLetter_sempred(_localctx, predIndex);

		case 16 : return JavaLetterOrDigit_sempred(_localctx, predIndex);
		}
		return true;
	}
	private boolean JavaLetterOrDigit_sempred(RuleContext _localctx, int predIndex) {
		switch (predIndex) {
		case 2: return Character.isJavaIdentifierPart(_input.LA(-1));

		case 3: return Character.isJavaIdentifierPart(Character.toCodePoint((char)_input.LA(-2), (char)_input.LA(-1)));
		}
		return true;
	}
	private boolean JavaLetter_sempred(RuleContext _localctx, int predIndex) {
		switch (predIndex) {
		case 0: return Character.isJavaIdentifierStart(_input.LA(-1));

		case 1: return Character.isJavaIdentifierStart(Character.toCodePoint((char)_input.LA(-2), (char)_input.LA(-1)));
		}
		return true;
	}

	public static final String _serializedATN =
		"\3\uaf6f\u8320\u479d\ub75c\u4880\u1605\u191c\uab37\2\25\u0096\b\1\4\2"+
		"\t\2\4\3\t\3\4\4\t\4\4\5\t\5\4\6\t\6\4\7\t\7\4\b\t\b\4\t\t\t\4\n\t\n\4"+
		"\13\t\13\4\f\t\f\4\r\t\r\4\16\t\16\4\17\t\17\4\20\t\20\4\21\t\21\4\22"+
		"\t\22\4\23\t\23\4\24\t\24\4\25\t\25\4\26\t\26\4\27\t\27\4\30\t\30\4\31"+
		"\t\31\3\2\3\2\3\2\3\2\3\2\3\3\3\3\3\4\3\4\3\5\3\5\3\6\3\6\3\7\3\7\3\b"+
		"\3\b\3\t\3\t\3\n\3\n\3\13\3\13\3\f\3\f\3\f\3\r\3\r\3\16\3\16\7\16R\n\16"+
		"\f\16\16\16U\13\16\3\17\6\17X\n\17\r\17\16\17Y\3\20\3\20\3\20\3\20\3\20"+
		"\3\20\3\20\3\20\3\20\5\20e\n\20\3\21\3\21\3\21\3\21\3\21\3\21\5\21m\n"+
		"\21\3\22\3\22\3\22\3\22\3\22\3\22\5\22u\n\22\3\23\3\23\5\23y\n\23\3\23"+
		"\3\23\3\24\3\24\5\24\177\n\24\3\24\3\24\3\25\3\25\3\25\3\25\3\26\3\26"+
		"\3\27\6\27\u008a\n\27\r\27\16\27\u008b\3\30\3\30\3\31\6\31\u0091\n\31"+
		"\r\31\16\31\u0092\3\31\3\31\2\2\2\32\3\2\3\5\2\4\7\2\5\t\2\6\13\2\7\r"+
		"\2\b\17\2\t\21\2\n\23\2\13\25\2\f\27\2\r\31\2\16\33\2\17\35\2\20\37\2"+
		"\21!\2\2#\2\2%\2\22\'\2\23)\2\24+\2\2-\2\2/\2\2\61\2\25\3\2\n\6\2&&C\\"+
		"aac|\4\2\2\u0101\ud802\udc01\3\2\ud802\udc01\3\2\udc02\ue001\7\2&&\62"+
		";C\\aac|\4\2))^^\4\2$$^^\5\2\13\f\17\17\"\"\u009b\2\3\3\2\2\2\2\5\3\2"+
		"\2\2\2\7\3\2\2\2\2\t\3\2\2\2\2\13\3\2\2\2\2\r\3\2\2\2\2\17\3\2\2\2\2\21"+
		"\3\2\2\2\2\23\3\2\2\2\2\25\3\2\2\2\2\27\3\2\2\2\2\31\3\2\2\2\2\33\3\2"+
		"\2\2\2\35\3\2\2\2\2\37\3\2\2\2\2%\3\2\2\2\2\'\3\2\2\2\2)\3\2\2\2\2\61"+
		"\3\2\2\2\3\63\3\2\2\2\58\3\2\2\2\7:\3\2\2\2\t<\3\2\2\2\13>\3\2\2\2\r@"+
		"\3\2\2\2\17B\3\2\2\2\21D\3\2\2\2\23F\3\2\2\2\25H\3\2\2\2\27J\3\2\2\2\31"+
		"M\3\2\2\2\33O\3\2\2\2\35W\3\2\2\2\37d\3\2\2\2!l\3\2\2\2#t\3\2\2\2%v\3"+
		"\2\2\2\'|\3\2\2\2)\u0082\3\2\2\2+\u0086\3\2\2\2-\u0089\3\2\2\2/\u008d"+
		"\3\2\2\2\61\u0090\3\2\2\2\63\64\7p\2\2\64\65\7w\2\2\65\66\7n\2\2\66\67"+
		"\7n\2\2\67\4\3\2\2\289\7+\2\29\6\3\2\2\2:;\7\60\2\2;\b\3\2\2\2<=\7.\2"+
		"\2=\n\3\2\2\2>?\7-\2\2?\f\3\2\2\2@A\7,\2\2A\16\3\2\2\2BC\7/\2\2C\20\3"+
		"\2\2\2DE\7*\2\2E\22\3\2\2\2FG\7<\2\2G\24\3\2\2\2HI\7\61\2\2I\26\3\2\2"+
		"\2JK\7?\2\2KL\7?\2\2L\30\3\2\2\2MN\7A\2\2N\32\3\2\2\2OS\5!\21\2PR\5#\22"+
		"\2QP\3\2\2\2RU\3\2\2\2SQ\3\2\2\2ST\3\2\2\2T\34\3\2\2\2US\3\2\2\2VX\4\62"+
		";\2WV\3\2\2\2XY\3\2\2\2YW\3\2\2\2YZ\3\2\2\2Z\36\3\2\2\2[\\\7v\2\2\\]\7"+
		"t\2\2]^\7w\2\2^e\7g\2\2_`\7h\2\2`a\7c\2\2ab\7n\2\2bc\7u\2\2ce\7g\2\2d"+
		"[\3\2\2\2d_\3\2\2\2e \3\2\2\2fm\t\2\2\2gh\n\3\2\2hm\6\21\2\2ij\t\4\2\2"+
		"jk\t\5\2\2km\6\21\3\2lf\3\2\2\2lg\3\2\2\2li\3\2\2\2m\"\3\2\2\2nu\t\6\2"+
		"\2op\n\3\2\2pu\6\22\4\2qr\t\4\2\2rs\t\5\2\2su\6\22\5\2tn\3\2\2\2to\3\2"+
		"\2\2tq\3\2\2\2u$\3\2\2\2vx\7b\2\2wy\5-\27\2xw\3\2\2\2xy\3\2\2\2yz\3\2"+
		"\2\2z{\7b\2\2{&\3\2\2\2|~\7$\2\2}\177\5-\27\2~}\3\2\2\2~\177\3\2\2\2\177"+
		"\u0080\3\2\2\2\u0080\u0081\7$\2\2\u0081(\3\2\2\2\u0082\u0083\7)\2\2\u0083"+
		"\u0084\5+\26\2\u0084\u0085\7)\2\2\u0085*\3\2\2\2\u0086\u0087\n\7\2\2\u0087"+
		",\3\2\2\2\u0088\u008a\5/\30\2\u0089\u0088\3\2\2\2\u008a\u008b\3\2\2\2"+
		"\u008b\u0089\3\2\2\2\u008b\u008c\3\2\2\2\u008c.\3\2\2\2\u008d\u008e\n"+
		"\b\2\2\u008e\60\3\2\2\2\u008f\u0091\t\t\2\2\u0090\u008f\3\2\2\2\u0091"+
		"\u0092\3\2\2\2\u0092\u0090\3\2\2\2\u0092\u0093\3\2\2\2\u0093\u0094\3\2"+
		"\2\2\u0094\u0095\b\31\2\2\u0095\62\3\2\2\2\f\2SYdltx~\u008b\u0092\3\b"+
		"\2\2";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
	}
}