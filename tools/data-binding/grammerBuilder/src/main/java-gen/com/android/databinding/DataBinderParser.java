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
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.*;
import org.antlr.v4.runtime.tree.*;
import java.util.List;
import java.util.Iterator;
import java.util.ArrayList;

public class DataBinderParser extends Parser {
	public static final int
		T__11=1, T__10=2, T__9=3, T__8=4, T__7=5, T__6=6, T__5=7, T__4=8, T__3=9, 
		T__2=10, T__1=11, T__0=12, Identifier=13, INT=14, BOOLEAN=15, HackyStringLiteral=16, 
		StringLiteral=17, CharacterLiteral=18, WS=19;
	public static final String[] tokenNames = {
		"<INVALID>", "'null'", "')'", "'.'", "','", "'+'", "'*'", "'-'", "'('", 
		"':'", "'/'", "'=='", "'?'", "Identifier", "INT", "BOOLEAN", "HackyStringLiteral", 
		"StringLiteral", "CharacterLiteral", "WS"
	};
	public static final int
		RULE_start = 0, RULE_expr = 1, RULE_exprList = 2, RULE_field = 3, RULE_hackyStringSymbol = 4, 
		RULE_symbol = 5, RULE_nil = 6;
	public static final String[] ruleNames = {
		"start", "expr", "exprList", "field", "hackyStringSymbol", "symbol", "nil"
	};

	@Override
	public String getGrammarFileName() { return "DataBinder.g4"; }

	@Override
	public String[] getTokenNames() { return tokenNames; }

	@Override
	public String[] getRuleNames() { return ruleNames; }

	@Override
	public String getSerializedATN() { return _serializedATN; }

	public DataBinderParser(TokenStream input) {
		super(input);
		_interp = new ParserATNSimulator(this,_ATN);
	}
	public static class StartContext extends ParserRuleContext {
		public ExprContext expr() {
			return getRuleContext(ExprContext.class,0);
		}
		public StartContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_start; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).enterStart(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).exitStart(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof DataBinderVisitor<?> ) return ((DataBinderVisitor<? extends Result>)visitor).visitStart(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final StartContext start() throws RecognitionException {
		StartContext _localctx = new StartContext(_ctx, getState());
		enterRule(_localctx, 0, RULE_start);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(14); expr(0);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class ExprContext extends ParserRuleContext {
		public ExprContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_expr; }
	 
		public ExprContext() { }
		public void copyFrom(ExprContext ctx) {
			super.copyFrom(ctx);
		}
	}
	public static class OpExprContext extends ExprContext {
		public ExprContext left;
		public Token op;
		public ExprContext right;
		public List<? extends ExprContext> expr() {
			return getRuleContexts(ExprContext.class);
		}
		public ExprContext expr(int i) {
			return getRuleContext(ExprContext.class,i);
		}
		public OpExprContext(ExprContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).enterOpExpr(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).exitOpExpr(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof DataBinderVisitor<?> ) return ((DataBinderVisitor<? extends Result>)visitor).visitOpExpr(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class NilExprContext extends ExprContext {
		public NilContext nil() {
			return getRuleContext(NilContext.class,0);
		}
		public NilExprContext(ExprContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).enterNilExpr(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).exitNilExpr(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof DataBinderVisitor<?> ) return ((DataBinderVisitor<? extends Result>)visitor).visitNilExpr(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class IdExprContext extends ExprContext {
		public FieldContext field() {
			return getRuleContext(FieldContext.class,0);
		}
		public IdExprContext(ExprContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).enterIdExpr(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).exitIdExpr(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof DataBinderVisitor<?> ) return ((DataBinderVisitor<? extends Result>)visitor).visitIdExpr(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AtomExprContext extends ExprContext {
		public SymbolContext atom;
		public SymbolContext symbol() {
			return getRuleContext(SymbolContext.class,0);
		}
		public AtomExprContext(ExprContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).enterAtomExpr(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).exitAtomExpr(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof DataBinderVisitor<?> ) return ((DataBinderVisitor<? extends Result>)visitor).visitAtomExpr(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class HackyStringExprContext extends ExprContext {
		public HackyStringSymbolContext atomString;
		public HackyStringSymbolContext hackyStringSymbol() {
			return getRuleContext(HackyStringSymbolContext.class,0);
		}
		public HackyStringExprContext(ExprContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).enterHackyStringExpr(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).exitHackyStringExpr(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof DataBinderVisitor<?> ) return ((DataBinderVisitor<? extends Result>)visitor).visitHackyStringExpr(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class TernaryExprContext extends ExprContext {
		public ExprContext pred;
		public ExprContext t;
		public ExprContext f;
		public List<? extends ExprContext> expr() {
			return getRuleContexts(ExprContext.class);
		}
		public ExprContext expr(int i) {
			return getRuleContext(ExprContext.class,i);
		}
		public TernaryExprContext(ExprContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).enterTernaryExpr(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).exitTernaryExpr(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof DataBinderVisitor<?> ) return ((DataBinderVisitor<? extends Result>)visitor).visitTernaryExpr(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class InnerExprContext extends ExprContext {
		public ExprContext inner;
		public ExprContext expr() {
			return getRuleContext(ExprContext.class,0);
		}
		public InnerExprContext(ExprContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).enterInnerExpr(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).exitInnerExpr(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof DataBinderVisitor<?> ) return ((DataBinderVisitor<? extends Result>)visitor).visitInnerExpr(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class GlobalMethodCallExprContext extends ExprContext {
		public Token methodName;
		public ExprListContext arguments;
		public ExprListContext exprList() {
			return getRuleContext(ExprListContext.class,0);
		}
		public TerminalNode Identifier() { return getToken(DataBinderParser.Identifier, 0); }
		public GlobalMethodCallExprContext(ExprContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).enterGlobalMethodCallExpr(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).exitGlobalMethodCallExpr(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof DataBinderVisitor<?> ) return ((DataBinderVisitor<? extends Result>)visitor).visitGlobalMethodCallExpr(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class MethodCallExprContext extends ExprContext {
		public ExprContext ownerObj;
		public Token methodName;
		public ExprListContext arguments;
		public ExprListContext exprList() {
			return getRuleContext(ExprListContext.class,0);
		}
		public ExprContext expr() {
			return getRuleContext(ExprContext.class,0);
		}
		public TerminalNode Identifier() { return getToken(DataBinderParser.Identifier, 0); }
		public MethodCallExprContext(ExprContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).enterMethodCallExpr(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).exitMethodCallExpr(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof DataBinderVisitor<?> ) return ((DataBinderVisitor<? extends Result>)visitor).visitMethodCallExpr(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class EqExprContext extends ExprContext {
		public ExprContext left;
		public ExprContext right;
		public List<? extends ExprContext> expr() {
			return getRuleContexts(ExprContext.class);
		}
		public ExprContext expr(int i) {
			return getRuleContext(ExprContext.class,i);
		}
		public EqExprContext(ExprContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).enterEqExpr(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).exitEqExpr(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof DataBinderVisitor<?> ) return ((DataBinderVisitor<? extends Result>)visitor).visitEqExpr(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final ExprContext expr() throws RecognitionException {
		return expr(0);
	}

	private ExprContext expr(int _p) throws RecognitionException {
		ParserRuleContext _parentctx = _ctx;
		int _parentState = getState();
		ExprContext _localctx = new ExprContext(_ctx, _parentState);
		ExprContext _prevctx = _localctx;
		int _startState = 2;
		enterRecursionRule(_localctx, 2, RULE_expr, _p);
		int _la;
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(31);
			switch ( getInterpreter().adaptivePredict(_input,1,_ctx) ) {
			case 1:
				{
				_localctx = new NilExprContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;

				setState(17); nil();
				}
				break;

			case 2:
				{
				_localctx = new InnerExprContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(18); match(T__4);
				setState(19); ((InnerExprContext)_localctx).inner = expr(0);
				setState(20); match(T__10);
				}
				break;

			case 3:
				{
				_localctx = new GlobalMethodCallExprContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(22); ((GlobalMethodCallExprContext)_localctx).methodName = match(Identifier);
				setState(23); match(T__4);
				setState(25);
				_la = _input.LA(1);
				if ((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << T__11) | (1L << T__4) | (1L << Identifier) | (1L << INT) | (1L << BOOLEAN) | (1L << HackyStringLiteral) | (1L << StringLiteral) | (1L << CharacterLiteral))) != 0)) {
					{
					setState(24); ((GlobalMethodCallExprContext)_localctx).arguments = exprList();
					}
				}

				setState(27); match(T__10);
				}
				break;

			case 4:
				{
				_localctx = new HackyStringExprContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(28); ((HackyStringExprContext)_localctx).atomString = hackyStringSymbol();
				}
				break;

			case 5:
				{
				_localctx = new AtomExprContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(29); ((AtomExprContext)_localctx).atom = symbol();
				}
				break;

			case 6:
				{
				_localctx = new IdExprContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(30); field();
				}
				break;
			}
			_ctx.stop = _input.LT(-1);
			setState(58);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,4,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					if ( _parseListeners!=null ) triggerExitRuleEvent();
					_prevctx = _localctx;
					{
					setState(56);
					switch ( getInterpreter().adaptivePredict(_input,3,_ctx) ) {
					case 1:
						{
						_localctx = new OpExprContext(new ExprContext(_parentctx, _parentState));
						((OpExprContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expr);
						setState(33);
						if (!(precpred(_ctx, 4))) throw new FailedPredicateException(this, "precpred(_ctx, 4)");
						setState(34);
						((OpExprContext)_localctx).op = _input.LT(1);
						_la = _input.LA(1);
						if ( !(_la==T__6 || _la==T__2) ) {
							((OpExprContext)_localctx).op = _errHandler.recoverInline(this);
						}
						consume();
						setState(35); ((OpExprContext)_localctx).right = expr(5);
						}
						break;

					case 2:
						{
						_localctx = new OpExprContext(new ExprContext(_parentctx, _parentState));
						((OpExprContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expr);
						setState(36);
						if (!(precpred(_ctx, 3))) throw new FailedPredicateException(this, "precpred(_ctx, 3)");
						setState(37);
						((OpExprContext)_localctx).op = _input.LT(1);
						_la = _input.LA(1);
						if ( !(_la==T__7 || _la==T__5) ) {
							((OpExprContext)_localctx).op = _errHandler.recoverInline(this);
						}
						consume();
						setState(38); ((OpExprContext)_localctx).right = expr(4);
						}
						break;

					case 3:
						{
						_localctx = new EqExprContext(new ExprContext(_parentctx, _parentState));
						((EqExprContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expr);
						setState(39);
						if (!(precpred(_ctx, 2))) throw new FailedPredicateException(this, "precpred(_ctx, 2)");
						setState(40); match(T__1);
						setState(41); ((EqExprContext)_localctx).right = expr(3);
						}
						break;

					case 4:
						{
						_localctx = new TernaryExprContext(new ExprContext(_parentctx, _parentState));
						((TernaryExprContext)_localctx).pred = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expr);
						setState(42);
						if (!(precpred(_ctx, 1))) throw new FailedPredicateException(this, "precpred(_ctx, 1)");
						setState(43); match(T__0);
						setState(44); ((TernaryExprContext)_localctx).t = expr(0);
						setState(45); match(T__3);
						setState(46); ((TernaryExprContext)_localctx).f = expr(2);
						}
						break;

					case 5:
						{
						_localctx = new MethodCallExprContext(new ExprContext(_parentctx, _parentState));
						((MethodCallExprContext)_localctx).ownerObj = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expr);
						setState(48);
						if (!(precpred(_ctx, 9))) throw new FailedPredicateException(this, "precpred(_ctx, 9)");
						setState(49); match(T__9);
						setState(50); ((MethodCallExprContext)_localctx).methodName = match(Identifier);
						setState(51); match(T__4);
						setState(53);
						_la = _input.LA(1);
						if ((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << T__11) | (1L << T__4) | (1L << Identifier) | (1L << INT) | (1L << BOOLEAN) | (1L << HackyStringLiteral) | (1L << StringLiteral) | (1L << CharacterLiteral))) != 0)) {
							{
							setState(52); ((MethodCallExprContext)_localctx).arguments = exprList();
							}
						}

						setState(55); match(T__10);
						}
						break;
					}
					} 
				}
				setState(60);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,4,_ctx);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			unrollRecursionContexts(_parentctx);
		}
		return _localctx;
	}

	public static class ExprListContext extends ParserRuleContext {
		public List<? extends ExprContext> expr() {
			return getRuleContexts(ExprContext.class);
		}
		public ExprContext expr(int i) {
			return getRuleContext(ExprContext.class,i);
		}
		public ExprListContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_exprList; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).enterExprList(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).exitExprList(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof DataBinderVisitor<?> ) return ((DataBinderVisitor<? extends Result>)visitor).visitExprList(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final ExprListContext exprList() throws RecognitionException {
		ExprListContext _localctx = new ExprListContext(_ctx, getState());
		enterRule(_localctx, 4, RULE_exprList);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(61); expr(0);
			setState(66);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==T__8) {
				{
				{
				setState(62); match(T__8);
				setState(63); expr(0);
				}
				}
				setState(68);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class FieldContext extends ParserRuleContext {
		public Token name;
		public TerminalNode Identifier(int i) {
			return getToken(DataBinderParser.Identifier, i);
		}
		public List<? extends TerminalNode> Identifier() { return getTokens(DataBinderParser.Identifier); }
		public FieldContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_field; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).enterField(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).exitField(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof DataBinderVisitor<?> ) return ((DataBinderVisitor<? extends Result>)visitor).visitField(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final FieldContext field() throws RecognitionException {
		FieldContext _localctx = new FieldContext(_ctx, getState());
		enterRule(_localctx, 6, RULE_field);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(69); _localctx.name = match(Identifier);
			setState(74);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,6,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(70); match(T__9);
					setState(71); match(Identifier);
					}
					} 
				}
				setState(76);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,6,_ctx);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class HackyStringSymbolContext extends ParserRuleContext {
		public TerminalNode HackyStringLiteral() { return getToken(DataBinderParser.HackyStringLiteral, 0); }
		public HackyStringSymbolContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_hackyStringSymbol; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).enterHackyStringSymbol(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).exitHackyStringSymbol(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof DataBinderVisitor<?> ) return ((DataBinderVisitor<? extends Result>)visitor).visitHackyStringSymbol(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final HackyStringSymbolContext hackyStringSymbol() throws RecognitionException {
		HackyStringSymbolContext _localctx = new HackyStringSymbolContext(_ctx, getState());
		enterRule(_localctx, 8, RULE_hackyStringSymbol);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(77); match(HackyStringLiteral);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class SymbolContext extends ParserRuleContext {
		public TerminalNode BOOLEAN() { return getToken(DataBinderParser.BOOLEAN, 0); }
		public TerminalNode INT() { return getToken(DataBinderParser.INT, 0); }
		public TerminalNode CharacterLiteral() { return getToken(DataBinderParser.CharacterLiteral, 0); }
		public TerminalNode StringLiteral() { return getToken(DataBinderParser.StringLiteral, 0); }
		public SymbolContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_symbol; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).enterSymbol(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).exitSymbol(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof DataBinderVisitor<?> ) return ((DataBinderVisitor<? extends Result>)visitor).visitSymbol(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final SymbolContext symbol() throws RecognitionException {
		SymbolContext _localctx = new SymbolContext(_ctx, getState());
		enterRule(_localctx, 10, RULE_symbol);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(79);
			_la = _input.LA(1);
			if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << INT) | (1L << BOOLEAN) | (1L << StringLiteral) | (1L << CharacterLiteral))) != 0)) ) {
			_errHandler.recoverInline(this);
			}
			consume();
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public static class NilContext extends ParserRuleContext {
		public NilContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_nil; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).enterNil(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof DataBinderListener ) ((DataBinderListener)listener).exitNil(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof DataBinderVisitor<?> ) return ((DataBinderVisitor<? extends Result>)visitor).visitNil(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final NilContext nil() throws RecognitionException {
		NilContext _localctx = new NilContext(_ctx, getState());
		enterRule(_localctx, 12, RULE_nil);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(81); match(T__11);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public boolean sempred(RuleContext _localctx, int ruleIndex, int predIndex) {
		switch (ruleIndex) {
		case 1: return expr_sempred((ExprContext)_localctx, predIndex);
		}
		return true;
	}
	private boolean expr_sempred(ExprContext _localctx, int predIndex) {
		switch (predIndex) {
		case 0: return precpred(_ctx, 4);

		case 1: return precpred(_ctx, 3);

		case 2: return precpred(_ctx, 2);

		case 3: return precpred(_ctx, 1);

		case 4: return precpred(_ctx, 9);
		}
		return true;
	}

	public static final String _serializedATN =
		"\3\uaf6f\u8320\u479d\ub75c\u4880\u1605\u191c\uab37\3\25V\4\2\t\2\4\3\t"+
		"\3\4\4\t\4\4\5\t\5\4\6\t\6\4\7\t\7\4\b\t\b\3\2\3\2\3\3\3\3\3\3\3\3\3\3"+
		"\3\3\3\3\3\3\3\3\5\3\34\n\3\3\3\3\3\3\3\3\3\5\3\"\n\3\3\3\3\3\3\3\3\3"+
		"\3\3\3\3\3\3\3\3\3\3\3\3\3\3\3\3\3\3\3\3\3\3\3\3\3\3\3\3\3\3\3\3\5\38"+
		"\n\3\3\3\7\3;\n\3\f\3\16\3>\13\3\3\4\3\4\3\4\7\4C\n\4\f\4\16\4F\13\4\3"+
		"\5\3\5\3\5\7\5K\n\5\f\5\16\5N\13\5\3\6\3\6\3\7\3\7\3\b\3\b\3\b\2\2\3\4"+
		"\t\2\2\4\2\6\2\b\2\n\2\f\2\16\2\2\5\4\2\b\b\f\f\4\2\7\7\t\t\4\2\20\21"+
		"\23\24\\\2\20\3\2\2\2\4!\3\2\2\2\6?\3\2\2\2\bG\3\2\2\2\nO\3\2\2\2\fQ\3"+
		"\2\2\2\16S\3\2\2\2\20\21\5\4\3\2\21\3\3\2\2\2\22\23\b\3\1\2\23\"\5\16"+
		"\b\2\24\25\7\n\2\2\25\26\5\4\3\2\26\27\7\4\2\2\27\"\3\2\2\2\30\31\7\17"+
		"\2\2\31\33\7\n\2\2\32\34\5\6\4\2\33\32\3\2\2\2\33\34\3\2\2\2\34\35\3\2"+
		"\2\2\35\"\7\4\2\2\36\"\5\n\6\2\37\"\5\f\7\2 \"\5\b\5\2!\22\3\2\2\2!\24"+
		"\3\2\2\2!\30\3\2\2\2!\36\3\2\2\2!\37\3\2\2\2! \3\2\2\2\"<\3\2\2\2#$\f"+
		"\6\2\2$%\t\2\2\2%;\5\4\3\7&\'\f\5\2\2\'(\t\3\2\2(;\5\4\3\6)*\f\4\2\2*"+
		"+\7\r\2\2+;\5\4\3\5,-\f\3\2\2-.\7\16\2\2./\5\4\3\2/\60\7\13\2\2\60\61"+
		"\5\4\3\4\61;\3\2\2\2\62\63\f\13\2\2\63\64\7\5\2\2\64\65\7\17\2\2\65\67"+
		"\7\n\2\2\668\5\6\4\2\67\66\3\2\2\2\678\3\2\2\289\3\2\2\29;\7\4\2\2:#\3"+
		"\2\2\2:&\3\2\2\2:)\3\2\2\2:,\3\2\2\2:\62\3\2\2\2;>\3\2\2\2<:\3\2\2\2<"+
		"=\3\2\2\2=\5\3\2\2\2><\3\2\2\2?D\5\4\3\2@A\7\6\2\2AC\5\4\3\2B@\3\2\2\2"+
		"CF\3\2\2\2DB\3\2\2\2DE\3\2\2\2E\7\3\2\2\2FD\3\2\2\2GL\7\17\2\2HI\7\5\2"+
		"\2IK\7\17\2\2JH\3\2\2\2KN\3\2\2\2LJ\3\2\2\2LM\3\2\2\2M\t\3\2\2\2NL\3\2"+
		"\2\2OP\7\22\2\2P\13\3\2\2\2QR\t\4\2\2R\r\3\2\2\2ST\7\3\2\2T\17\3\2\2\2"+
		"\t\33!\67:<DL";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
	}
}