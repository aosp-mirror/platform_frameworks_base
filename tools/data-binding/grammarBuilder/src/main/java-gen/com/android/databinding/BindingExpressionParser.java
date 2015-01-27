// Generated from BindingExpression.g4 by ANTLR 4.4
package com.android.databinding;
import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.*;
import org.antlr.v4.runtime.tree.*;
import java.util.List;
import java.util.Iterator;
import java.util.ArrayList;

public class BindingExpressionParser extends Parser {
	public static final int
		T__42=1, T__41=2, T__40=3, T__39=4, T__38=5, T__37=6, T__36=7, T__35=8, 
		T__34=9, T__33=10, T__32=11, T__31=12, T__30=13, T__29=14, T__28=15, T__27=16, 
		T__26=17, T__25=18, T__24=19, T__23=20, T__22=21, T__21=22, T__20=23, 
		T__19=24, T__18=25, T__17=26, T__16=27, T__15=28, T__14=29, T__13=30, 
		T__12=31, T__11=32, T__10=33, T__9=34, T__8=35, T__7=36, T__6=37, T__5=38, 
		T__4=39, T__3=40, T__2=41, T__1=42, T__0=43, THIS=44, IntegerLiteral=45, 
		FloatingPointLiteral=46, BooleanLiteral=47, CharacterLiteral=48, SingleQuoteString=49, 
		DoubleQuoteString=50, NullLiteral=51, Identifier=52, WS=53, ResourceReference=54, 
		ResourceName=55;
	public static final String[] tokenNames = {
		"<INVALID>", "'long'", "'>>>'", "']'", "'short'", "'&'", "'default'", 
		"','", "'*'", "'['", "'-'", "'('", "':'", "'<'", "'int'", "'!='", "'<='", 
		"'?'", "'<<'", "'void'", "'double'", "'boolean'", "'float'", "'>>'", "'char'", 
		"'%'", "'^'", "'byte'", "')'", "'.'", "'+'", "'='", "'&&'", "'||'", "'>'", 
		"'??'", "'=='", "'/'", "'~'", "'>='", "'class'", "'|'", "'instanceof'", 
		"'!'", "'this'", "IntegerLiteral", "FloatingPointLiteral", "BooleanLiteral", 
		"CharacterLiteral", "SingleQuoteString", "DoubleQuoteString", "'null'", 
		"Identifier", "WS", "ResourceReference", "ResourceName"
	};
	public static final int
		RULE_bindingSyntax = 0, RULE_defaults = 1, RULE_constantValue = 2, RULE_expression = 3, 
		RULE_classExtraction = 4, RULE_expressionList = 5, RULE_literal = 6, RULE_identifier = 7, 
		RULE_javaLiteral = 8, RULE_stringLiteral = 9, RULE_explicitGenericInvocation = 10, 
		RULE_typeArguments = 11, RULE_type = 12, RULE_explicitGenericInvocationSuffix = 13, 
		RULE_arguments = 14, RULE_classOrInterfaceType = 15, RULE_primitiveType = 16;
	public static final String[] ruleNames = {
		"bindingSyntax", "defaults", "constantValue", "expression", "classExtraction", 
		"expressionList", "literal", "identifier", "javaLiteral", "stringLiteral", 
		"explicitGenericInvocation", "typeArguments", "type", "explicitGenericInvocationSuffix", 
		"arguments", "classOrInterfaceType", "primitiveType"
	};

	@Override
	public String getGrammarFileName() { return "BindingExpression.g4"; }

	@Override
	public String[] getTokenNames() { return tokenNames; }

	@Override
	public String[] getRuleNames() { return ruleNames; }

	@Override
	public String getSerializedATN() { return _serializedATN; }

	public BindingExpressionParser(TokenStream input) {
		super(input);
		_interp = new ParserATNSimulator(this,_ATN);
	}
	public static class BindingSyntaxContext extends ParserRuleContext {
		public DefaultsContext defaults() {
			return getRuleContext(DefaultsContext.class,0);
		}
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public BindingSyntaxContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_bindingSyntax; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterBindingSyntax(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitBindingSyntax(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitBindingSyntax(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final BindingSyntaxContext bindingSyntax() throws RecognitionException {
		BindingSyntaxContext _localctx = new BindingSyntaxContext(_ctx, getState());
		enterRule(_localctx, 0, RULE_bindingSyntax);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(34); expression(0);
			setState(36);
			_la = _input.LA(1);
			if (_la==T__36) {
				{
				setState(35); defaults();
				}
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

	public static class DefaultsContext extends ParserRuleContext {
		public ConstantValueContext constantValue() {
			return getRuleContext(ConstantValueContext.class,0);
		}
		public DefaultsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_defaults; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterDefaults(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitDefaults(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitDefaults(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final DefaultsContext defaults() throws RecognitionException {
		DefaultsContext _localctx = new DefaultsContext(_ctx, getState());
		enterRule(_localctx, 2, RULE_defaults);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(38); match(T__36);
			setState(39); match(T__37);
			setState(40); match(T__12);
			setState(41); constantValue();
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

	public static class ConstantValueContext extends ParserRuleContext {
		public LiteralContext literal() {
			return getRuleContext(LiteralContext.class,0);
		}
		public IdentifierContext identifier() {
			return getRuleContext(IdentifierContext.class,0);
		}
		public TerminalNode ResourceReference() { return getToken(BindingExpressionParser.ResourceReference, 0); }
		public ConstantValueContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_constantValue; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterConstantValue(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitConstantValue(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitConstantValue(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final ConstantValueContext constantValue() throws RecognitionException {
		ConstantValueContext _localctx = new ConstantValueContext(_ctx, getState());
		enterRule(_localctx, 4, RULE_constantValue);
		try {
			setState(46);
			switch (_input.LA(1)) {
			case IntegerLiteral:
			case FloatingPointLiteral:
			case BooleanLiteral:
			case CharacterLiteral:
			case SingleQuoteString:
			case DoubleQuoteString:
			case NullLiteral:
				enterOuterAlt(_localctx, 1);
				{
				setState(43); literal();
				}
				break;
			case ResourceReference:
				enterOuterAlt(_localctx, 2);
				{
				setState(44); match(ResourceReference);
				}
				break;
			case Identifier:
				enterOuterAlt(_localctx, 3);
				{
				setState(45); identifier();
				}
				break;
			default:
				throw new NoViableAltException(this);
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

	public static class ExpressionContext extends ParserRuleContext {
		public ExpressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_expression; }
	 
		public ExpressionContext() { }
		public void copyFrom(ExpressionContext ctx) {
			super.copyFrom(ctx);
		}
	}
	public static class BracketOpContext extends ExpressionContext {
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public List<? extends ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public BracketOpContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterBracketOp(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitBracketOp(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitBracketOp(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class ResourceContext extends ExpressionContext {
		public TerminalNode ResourceReference() { return getToken(BindingExpressionParser.ResourceReference, 0); }
		public ResourceContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterResource(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitResource(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitResource(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class CastOpContext extends ExpressionContext {
		public TypeContext type() {
			return getRuleContext(TypeContext.class,0);
		}
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public CastOpContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterCastOp(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitCastOp(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitCastOp(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class UnaryOpContext extends ExpressionContext {
		public Token op;
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public UnaryOpContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterUnaryOp(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitUnaryOp(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitUnaryOp(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AndOrOpContext extends ExpressionContext {
		public ExpressionContext left;
		public Token op;
		public ExpressionContext right;
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public List<? extends ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public AndOrOpContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterAndOrOp(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitAndOrOp(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitAndOrOp(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class MethodInvocationContext extends ExpressionContext {
		public ExpressionContext target;
		public Token methodName;
		public ExpressionListContext args;
		public ExpressionListContext expressionList() {
			return getRuleContext(ExpressionListContext.class,0);
		}
		public TerminalNode Identifier() { return getToken(BindingExpressionParser.Identifier, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public MethodInvocationContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterMethodInvocation(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitMethodInvocation(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitMethodInvocation(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class PrimaryContext extends ExpressionContext {
		public ClassExtractionContext classExtraction() {
			return getRuleContext(ClassExtractionContext.class,0);
		}
		public LiteralContext literal() {
			return getRuleContext(LiteralContext.class,0);
		}
		public IdentifierContext identifier() {
			return getRuleContext(IdentifierContext.class,0);
		}
		public PrimaryContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterPrimary(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitPrimary(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitPrimary(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class GroupingContext extends ExpressionContext {
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public GroupingContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterGrouping(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitGrouping(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitGrouping(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class TernaryOpContext extends ExpressionContext {
		public ExpressionContext left;
		public Token op;
		public ExpressionContext iftrue;
		public ExpressionContext iffalse;
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public List<? extends ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public TernaryOpContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterTernaryOp(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitTernaryOp(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitTernaryOp(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class ComparisonOpContext extends ExpressionContext {
		public ExpressionContext left;
		public Token op;
		public ExpressionContext right;
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public List<? extends ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ComparisonOpContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterComparisonOp(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitComparisonOp(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitComparisonOp(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class DotOpContext extends ExpressionContext {
		public TerminalNode Identifier() { return getToken(BindingExpressionParser.Identifier, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public DotOpContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterDotOp(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitDotOp(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitDotOp(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class MathOpContext extends ExpressionContext {
		public ExpressionContext left;
		public Token op;
		public ExpressionContext right;
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public List<? extends ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public MathOpContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterMathOp(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitMathOp(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitMathOp(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class BitShiftOpContext extends ExpressionContext {
		public ExpressionContext left;
		public Token op;
		public ExpressionContext right;
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public List<? extends ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public BitShiftOpContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterBitShiftOp(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitBitShiftOp(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitBitShiftOp(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class QuestionQuestionOpContext extends ExpressionContext {
		public ExpressionContext left;
		public Token op;
		public ExpressionContext right;
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public List<? extends ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public QuestionQuestionOpContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterQuestionQuestionOp(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitQuestionQuestionOp(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitQuestionQuestionOp(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class InstanceOfOpContext extends ExpressionContext {
		public TypeContext type() {
			return getRuleContext(TypeContext.class,0);
		}
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public InstanceOfOpContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterInstanceOfOp(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitInstanceOfOp(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitInstanceOfOp(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class BinaryOpContext extends ExpressionContext {
		public ExpressionContext left;
		public Token op;
		public ExpressionContext right;
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public List<? extends ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public BinaryOpContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterBinaryOp(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitBinaryOp(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitBinaryOp(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final ExpressionContext expression() throws RecognitionException {
		return expression(0);
	}

	private ExpressionContext expression(int _p) throws RecognitionException {
		ParserRuleContext _parentctx = _ctx;
		int _parentState = getState();
		ExpressionContext _localctx = new ExpressionContext(_ctx, _parentState);
		ExpressionContext _prevctx = _localctx;
		int _startState = 6;
		enterRecursionRule(_localctx, 6, RULE_expression, _p);
		int _la;
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(66);
			switch ( getInterpreter().adaptivePredict(_input,2,_ctx) ) {
			case 1:
				{
				_localctx = new CastOpContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;

				setState(49); match(T__32);
				setState(50); type();
				setState(51); match(T__15);
				setState(52); expression(16);
				}
				break;

			case 2:
				{
				_localctx = new UnaryOpContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(54);
				((UnaryOpContext)_localctx).op = _input.LT(1);
				_la = _input.LA(1);
				if ( !(_la==T__33 || _la==T__13) ) {
					((UnaryOpContext)_localctx).op = _errHandler.recoverInline(this);
				}
				consume();
				setState(55); expression(15);
				}
				break;

			case 3:
				{
				_localctx = new UnaryOpContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(56);
				((UnaryOpContext)_localctx).op = _input.LT(1);
				_la = _input.LA(1);
				if ( !(_la==T__5 || _la==T__0) ) {
					((UnaryOpContext)_localctx).op = _errHandler.recoverInline(this);
				}
				consume();
				setState(57); expression(14);
				}
				break;

			case 4:
				{
				_localctx = new GroupingContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(58); match(T__32);
				setState(59); expression(0);
				setState(60); match(T__15);
				}
				break;

			case 5:
				{
				_localctx = new PrimaryContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(62); literal();
				}
				break;

			case 6:
				{
				_localctx = new PrimaryContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(63); identifier();
				}
				break;

			case 7:
				{
				_localctx = new PrimaryContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(64); classExtraction();
				}
				break;

			case 8:
				{
				_localctx = new ResourceContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(65); match(ResourceReference);
				}
				break;
			}
			_ctx.stop = _input.LT(-1);
			setState(128);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,5,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					if ( _parseListeners!=null ) triggerExitRuleEvent();
					_prevctx = _localctx;
					{
					setState(126);
					switch ( getInterpreter().adaptivePredict(_input,4,_ctx) ) {
					case 1:
						{
						_localctx = new MathOpContext(new ExpressionContext(_parentctx, _parentState));
						((MathOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(68);
						if (!(precpred(_ctx, 13))) throw new FailedPredicateException(this, "precpred(_ctx, 13)");
						setState(69);
						((MathOpContext)_localctx).op = _input.LT(1);
						_la = _input.LA(1);
						if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << T__35) | (1L << T__18) | (1L << T__6))) != 0)) ) {
							((MathOpContext)_localctx).op = _errHandler.recoverInline(this);
						}
						consume();
						setState(70); ((MathOpContext)_localctx).right = expression(14);
						}
						break;

					case 2:
						{
						_localctx = new MathOpContext(new ExpressionContext(_parentctx, _parentState));
						((MathOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(71);
						if (!(precpred(_ctx, 12))) throw new FailedPredicateException(this, "precpred(_ctx, 12)");
						setState(72);
						((MathOpContext)_localctx).op = _input.LT(1);
						_la = _input.LA(1);
						if ( !(_la==T__33 || _la==T__13) ) {
							((MathOpContext)_localctx).op = _errHandler.recoverInline(this);
						}
						consume();
						setState(73); ((MathOpContext)_localctx).right = expression(13);
						}
						break;

					case 3:
						{
						_localctx = new BitShiftOpContext(new ExpressionContext(_parentctx, _parentState));
						((BitShiftOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(74);
						if (!(precpred(_ctx, 11))) throw new FailedPredicateException(this, "precpred(_ctx, 11)");
						setState(75);
						((BitShiftOpContext)_localctx).op = _input.LT(1);
						_la = _input.LA(1);
						if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << T__41) | (1L << T__25) | (1L << T__20))) != 0)) ) {
							((BitShiftOpContext)_localctx).op = _errHandler.recoverInline(this);
						}
						consume();
						setState(76); ((BitShiftOpContext)_localctx).right = expression(12);
						}
						break;

					case 4:
						{
						_localctx = new ComparisonOpContext(new ExpressionContext(_parentctx, _parentState));
						((ComparisonOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(77);
						if (!(precpred(_ctx, 10))) throw new FailedPredicateException(this, "precpred(_ctx, 10)");
						setState(78);
						((ComparisonOpContext)_localctx).op = _input.LT(1);
						_la = _input.LA(1);
						if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << T__30) | (1L << T__27) | (1L << T__9) | (1L << T__4))) != 0)) ) {
							((ComparisonOpContext)_localctx).op = _errHandler.recoverInline(this);
						}
						consume();
						setState(79); ((ComparisonOpContext)_localctx).right = expression(11);
						}
						break;

					case 5:
						{
						_localctx = new ComparisonOpContext(new ExpressionContext(_parentctx, _parentState));
						((ComparisonOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(80);
						if (!(precpred(_ctx, 8))) throw new FailedPredicateException(this, "precpred(_ctx, 8)");
						setState(81);
						((ComparisonOpContext)_localctx).op = _input.LT(1);
						_la = _input.LA(1);
						if ( !(_la==T__28 || _la==T__7) ) {
							((ComparisonOpContext)_localctx).op = _errHandler.recoverInline(this);
						}
						consume();
						setState(82); ((ComparisonOpContext)_localctx).right = expression(9);
						}
						break;

					case 6:
						{
						_localctx = new BinaryOpContext(new ExpressionContext(_parentctx, _parentState));
						((BinaryOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(83);
						if (!(precpred(_ctx, 7))) throw new FailedPredicateException(this, "precpred(_ctx, 7)");
						setState(84); ((BinaryOpContext)_localctx).op = match(T__38);
						setState(85); ((BinaryOpContext)_localctx).right = expression(8);
						}
						break;

					case 7:
						{
						_localctx = new BinaryOpContext(new ExpressionContext(_parentctx, _parentState));
						((BinaryOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(86);
						if (!(precpred(_ctx, 6))) throw new FailedPredicateException(this, "precpred(_ctx, 6)");
						setState(87); ((BinaryOpContext)_localctx).op = match(T__17);
						setState(88); ((BinaryOpContext)_localctx).right = expression(7);
						}
						break;

					case 8:
						{
						_localctx = new BinaryOpContext(new ExpressionContext(_parentctx, _parentState));
						((BinaryOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(89);
						if (!(precpred(_ctx, 5))) throw new FailedPredicateException(this, "precpred(_ctx, 5)");
						setState(90); ((BinaryOpContext)_localctx).op = match(T__2);
						setState(91); ((BinaryOpContext)_localctx).right = expression(6);
						}
						break;

					case 9:
						{
						_localctx = new AndOrOpContext(new ExpressionContext(_parentctx, _parentState));
						((AndOrOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(92);
						if (!(precpred(_ctx, 4))) throw new FailedPredicateException(this, "precpred(_ctx, 4)");
						setState(93); ((AndOrOpContext)_localctx).op = match(T__11);
						setState(94); ((AndOrOpContext)_localctx).right = expression(5);
						}
						break;

					case 10:
						{
						_localctx = new AndOrOpContext(new ExpressionContext(_parentctx, _parentState));
						((AndOrOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(95);
						if (!(precpred(_ctx, 3))) throw new FailedPredicateException(this, "precpred(_ctx, 3)");
						setState(96); ((AndOrOpContext)_localctx).op = match(T__10);
						setState(97); ((AndOrOpContext)_localctx).right = expression(4);
						}
						break;

					case 11:
						{
						_localctx = new TernaryOpContext(new ExpressionContext(_parentctx, _parentState));
						((TernaryOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(98);
						if (!(precpred(_ctx, 2))) throw new FailedPredicateException(this, "precpred(_ctx, 2)");
						setState(99); ((TernaryOpContext)_localctx).op = match(T__26);
						setState(100); ((TernaryOpContext)_localctx).iftrue = expression(0);
						setState(101); match(T__31);
						setState(102); ((TernaryOpContext)_localctx).iffalse = expression(3);
						}
						break;

					case 12:
						{
						_localctx = new QuestionQuestionOpContext(new ExpressionContext(_parentctx, _parentState));
						((QuestionQuestionOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(104);
						if (!(precpred(_ctx, 1))) throw new FailedPredicateException(this, "precpred(_ctx, 1)");
						setState(105); ((QuestionQuestionOpContext)_localctx).op = match(T__8);
						setState(106); ((QuestionQuestionOpContext)_localctx).right = expression(2);
						}
						break;

					case 13:
						{
						_localctx = new DotOpContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(107);
						if (!(precpred(_ctx, 19))) throw new FailedPredicateException(this, "precpred(_ctx, 19)");
						setState(108); match(T__14);
						setState(109); match(Identifier);
						}
						break;

					case 14:
						{
						_localctx = new BracketOpContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(110);
						if (!(precpred(_ctx, 18))) throw new FailedPredicateException(this, "precpred(_ctx, 18)");
						setState(111); match(T__34);
						setState(112); expression(0);
						setState(113); match(T__40);
						}
						break;

					case 15:
						{
						_localctx = new MethodInvocationContext(new ExpressionContext(_parentctx, _parentState));
						((MethodInvocationContext)_localctx).target = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(115);
						if (!(precpred(_ctx, 17))) throw new FailedPredicateException(this, "precpred(_ctx, 17)");
						setState(116); match(T__14);
						setState(117); ((MethodInvocationContext)_localctx).methodName = match(Identifier);
						setState(118); match(T__32);
						setState(120);
						_la = _input.LA(1);
						if ((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << T__42) | (1L << T__39) | (1L << T__33) | (1L << T__32) | (1L << T__29) | (1L << T__24) | (1L << T__23) | (1L << T__22) | (1L << T__21) | (1L << T__19) | (1L << T__16) | (1L << T__13) | (1L << T__5) | (1L << T__0) | (1L << IntegerLiteral) | (1L << FloatingPointLiteral) | (1L << BooleanLiteral) | (1L << CharacterLiteral) | (1L << SingleQuoteString) | (1L << DoubleQuoteString) | (1L << NullLiteral) | (1L << Identifier) | (1L << ResourceReference))) != 0)) {
							{
							setState(119); ((MethodInvocationContext)_localctx).args = expressionList();
							}
						}

						setState(122); match(T__15);
						}
						break;

					case 16:
						{
						_localctx = new InstanceOfOpContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(123);
						if (!(precpred(_ctx, 9))) throw new FailedPredicateException(this, "precpred(_ctx, 9)");
						setState(124); match(T__1);
						setState(125); type();
						}
						break;
					}
					} 
				}
				setState(130);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,5,_ctx);
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

	public static class ClassExtractionContext extends ParserRuleContext {
		public TypeContext type() {
			return getRuleContext(TypeContext.class,0);
		}
		public ClassExtractionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_classExtraction; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterClassExtraction(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitClassExtraction(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitClassExtraction(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final ClassExtractionContext classExtraction() throws RecognitionException {
		ClassExtractionContext _localctx = new ClassExtractionContext(_ctx, getState());
		enterRule(_localctx, 8, RULE_classExtraction);
		try {
			setState(138);
			switch (_input.LA(1)) {
			case T__42:
			case T__39:
			case T__29:
			case T__23:
			case T__22:
			case T__21:
			case T__19:
			case T__16:
			case Identifier:
				enterOuterAlt(_localctx, 1);
				{
				setState(131); type();
				setState(132); match(T__14);
				setState(133); match(T__3);
				}
				break;
			case T__24:
				enterOuterAlt(_localctx, 2);
				{
				setState(135); match(T__24);
				setState(136); match(T__14);
				setState(137); match(T__3);
				}
				break;
			default:
				throw new NoViableAltException(this);
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

	public static class ExpressionListContext extends ParserRuleContext {
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public List<? extends ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionListContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_expressionList; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterExpressionList(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitExpressionList(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitExpressionList(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final ExpressionListContext expressionList() throws RecognitionException {
		ExpressionListContext _localctx = new ExpressionListContext(_ctx, getState());
		enterRule(_localctx, 10, RULE_expressionList);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(140); expression(0);
			setState(145);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==T__36) {
				{
				{
				setState(141); match(T__36);
				setState(142); expression(0);
				}
				}
				setState(147);
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

	public static class LiteralContext extends ParserRuleContext {
		public StringLiteralContext stringLiteral() {
			return getRuleContext(StringLiteralContext.class,0);
		}
		public JavaLiteralContext javaLiteral() {
			return getRuleContext(JavaLiteralContext.class,0);
		}
		public LiteralContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_literal; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterLiteral(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitLiteral(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitLiteral(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final LiteralContext literal() throws RecognitionException {
		LiteralContext _localctx = new LiteralContext(_ctx, getState());
		enterRule(_localctx, 12, RULE_literal);
		try {
			setState(150);
			switch (_input.LA(1)) {
			case IntegerLiteral:
			case FloatingPointLiteral:
			case BooleanLiteral:
			case CharacterLiteral:
			case NullLiteral:
				enterOuterAlt(_localctx, 1);
				{
				setState(148); javaLiteral();
				}
				break;
			case SingleQuoteString:
			case DoubleQuoteString:
				enterOuterAlt(_localctx, 2);
				{
				setState(149); stringLiteral();
				}
				break;
			default:
				throw new NoViableAltException(this);
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

	public static class IdentifierContext extends ParserRuleContext {
		public TerminalNode Identifier() { return getToken(BindingExpressionParser.Identifier, 0); }
		public IdentifierContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_identifier; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterIdentifier(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitIdentifier(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitIdentifier(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final IdentifierContext identifier() throws RecognitionException {
		IdentifierContext _localctx = new IdentifierContext(_ctx, getState());
		enterRule(_localctx, 14, RULE_identifier);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(152); match(Identifier);
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

	public static class JavaLiteralContext extends ParserRuleContext {
		public TerminalNode NullLiteral() { return getToken(BindingExpressionParser.NullLiteral, 0); }
		public TerminalNode CharacterLiteral() { return getToken(BindingExpressionParser.CharacterLiteral, 0); }
		public TerminalNode IntegerLiteral() { return getToken(BindingExpressionParser.IntegerLiteral, 0); }
		public TerminalNode FloatingPointLiteral() { return getToken(BindingExpressionParser.FloatingPointLiteral, 0); }
		public TerminalNode BooleanLiteral() { return getToken(BindingExpressionParser.BooleanLiteral, 0); }
		public JavaLiteralContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_javaLiteral; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterJavaLiteral(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitJavaLiteral(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitJavaLiteral(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final JavaLiteralContext javaLiteral() throws RecognitionException {
		JavaLiteralContext _localctx = new JavaLiteralContext(_ctx, getState());
		enterRule(_localctx, 16, RULE_javaLiteral);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(154);
			_la = _input.LA(1);
			if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << IntegerLiteral) | (1L << FloatingPointLiteral) | (1L << BooleanLiteral) | (1L << CharacterLiteral) | (1L << NullLiteral))) != 0)) ) {
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

	public static class StringLiteralContext extends ParserRuleContext {
		public TerminalNode SingleQuoteString() { return getToken(BindingExpressionParser.SingleQuoteString, 0); }
		public TerminalNode DoubleQuoteString() { return getToken(BindingExpressionParser.DoubleQuoteString, 0); }
		public StringLiteralContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_stringLiteral; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterStringLiteral(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitStringLiteral(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitStringLiteral(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final StringLiteralContext stringLiteral() throws RecognitionException {
		StringLiteralContext _localctx = new StringLiteralContext(_ctx, getState());
		enterRule(_localctx, 18, RULE_stringLiteral);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(156);
			_la = _input.LA(1);
			if ( !(_la==SingleQuoteString || _la==DoubleQuoteString) ) {
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

	public static class ExplicitGenericInvocationContext extends ParserRuleContext {
		public ExplicitGenericInvocationSuffixContext explicitGenericInvocationSuffix() {
			return getRuleContext(ExplicitGenericInvocationSuffixContext.class,0);
		}
		public TypeArgumentsContext typeArguments() {
			return getRuleContext(TypeArgumentsContext.class,0);
		}
		public ExplicitGenericInvocationContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_explicitGenericInvocation; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterExplicitGenericInvocation(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitExplicitGenericInvocation(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitExplicitGenericInvocation(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final ExplicitGenericInvocationContext explicitGenericInvocation() throws RecognitionException {
		ExplicitGenericInvocationContext _localctx = new ExplicitGenericInvocationContext(_ctx, getState());
		enterRule(_localctx, 20, RULE_explicitGenericInvocation);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(158); typeArguments();
			setState(159); explicitGenericInvocationSuffix();
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

	public static class TypeArgumentsContext extends ParserRuleContext {
		public TypeContext type(int i) {
			return getRuleContext(TypeContext.class,i);
		}
		public List<? extends TypeContext> type() {
			return getRuleContexts(TypeContext.class);
		}
		public TypeArgumentsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_typeArguments; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterTypeArguments(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitTypeArguments(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitTypeArguments(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final TypeArgumentsContext typeArguments() throws RecognitionException {
		TypeArgumentsContext _localctx = new TypeArgumentsContext(_ctx, getState());
		enterRule(_localctx, 22, RULE_typeArguments);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(161); match(T__30);
			setState(162); type();
			setState(167);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==T__36) {
				{
				{
				setState(163); match(T__36);
				setState(164); type();
				}
				}
				setState(169);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(170); match(T__9);
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

	public static class TypeContext extends ParserRuleContext {
		public PrimitiveTypeContext primitiveType() {
			return getRuleContext(PrimitiveTypeContext.class,0);
		}
		public ClassOrInterfaceTypeContext classOrInterfaceType() {
			return getRuleContext(ClassOrInterfaceTypeContext.class,0);
		}
		public TypeContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_type; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterType(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitType(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitType(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final TypeContext type() throws RecognitionException {
		TypeContext _localctx = new TypeContext(_ctx, getState());
		enterRule(_localctx, 24, RULE_type);
		try {
			int _alt;
			setState(188);
			switch (_input.LA(1)) {
			case Identifier:
				enterOuterAlt(_localctx, 1);
				{
				setState(172); classOrInterfaceType();
				setState(177);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,10,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(173); match(T__34);
						setState(174); match(T__40);
						}
						} 
					}
					setState(179);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,10,_ctx);
				}
				}
				break;
			case T__42:
			case T__39:
			case T__29:
			case T__23:
			case T__22:
			case T__21:
			case T__19:
			case T__16:
				enterOuterAlt(_localctx, 2);
				{
				setState(180); primitiveType();
				setState(185);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,11,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(181); match(T__34);
						setState(182); match(T__40);
						}
						} 
					}
					setState(187);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,11,_ctx);
				}
				}
				break;
			default:
				throw new NoViableAltException(this);
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

	public static class ExplicitGenericInvocationSuffixContext extends ParserRuleContext {
		public TerminalNode Identifier() { return getToken(BindingExpressionParser.Identifier, 0); }
		public ArgumentsContext arguments() {
			return getRuleContext(ArgumentsContext.class,0);
		}
		public ExplicitGenericInvocationSuffixContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_explicitGenericInvocationSuffix; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterExplicitGenericInvocationSuffix(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitExplicitGenericInvocationSuffix(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitExplicitGenericInvocationSuffix(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final ExplicitGenericInvocationSuffixContext explicitGenericInvocationSuffix() throws RecognitionException {
		ExplicitGenericInvocationSuffixContext _localctx = new ExplicitGenericInvocationSuffixContext(_ctx, getState());
		enterRule(_localctx, 26, RULE_explicitGenericInvocationSuffix);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(190); match(Identifier);
			setState(191); arguments();
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

	public static class ArgumentsContext extends ParserRuleContext {
		public ExpressionListContext expressionList() {
			return getRuleContext(ExpressionListContext.class,0);
		}
		public ArgumentsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_arguments; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterArguments(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitArguments(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitArguments(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final ArgumentsContext arguments() throws RecognitionException {
		ArgumentsContext _localctx = new ArgumentsContext(_ctx, getState());
		enterRule(_localctx, 28, RULE_arguments);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(193); match(T__32);
			setState(195);
			_la = _input.LA(1);
			if ((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << T__42) | (1L << T__39) | (1L << T__33) | (1L << T__32) | (1L << T__29) | (1L << T__24) | (1L << T__23) | (1L << T__22) | (1L << T__21) | (1L << T__19) | (1L << T__16) | (1L << T__13) | (1L << T__5) | (1L << T__0) | (1L << IntegerLiteral) | (1L << FloatingPointLiteral) | (1L << BooleanLiteral) | (1L << CharacterLiteral) | (1L << SingleQuoteString) | (1L << DoubleQuoteString) | (1L << NullLiteral) | (1L << Identifier) | (1L << ResourceReference))) != 0)) {
				{
				setState(194); expressionList();
				}
			}

			setState(197); match(T__15);
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

	public static class ClassOrInterfaceTypeContext extends ParserRuleContext {
		public TypeArgumentsContext typeArguments(int i) {
			return getRuleContext(TypeArgumentsContext.class,i);
		}
		public TerminalNode Identifier(int i) {
			return getToken(BindingExpressionParser.Identifier, i);
		}
		public List<? extends TerminalNode> Identifier() { return getTokens(BindingExpressionParser.Identifier); }
		public List<? extends TypeArgumentsContext> typeArguments() {
			return getRuleContexts(TypeArgumentsContext.class);
		}
		public IdentifierContext identifier() {
			return getRuleContext(IdentifierContext.class,0);
		}
		public ClassOrInterfaceTypeContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_classOrInterfaceType; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterClassOrInterfaceType(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitClassOrInterfaceType(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitClassOrInterfaceType(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final ClassOrInterfaceTypeContext classOrInterfaceType() throws RecognitionException {
		ClassOrInterfaceTypeContext _localctx = new ClassOrInterfaceTypeContext(_ctx, getState());
		enterRule(_localctx, 30, RULE_classOrInterfaceType);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(199); identifier();
			setState(201);
			switch ( getInterpreter().adaptivePredict(_input,14,_ctx) ) {
			case 1:
				{
				setState(200); typeArguments();
				}
				break;
			}
			setState(210);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,16,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(203); match(T__14);
					setState(204); match(Identifier);
					setState(206);
					switch ( getInterpreter().adaptivePredict(_input,15,_ctx) ) {
					case 1:
						{
						setState(205); typeArguments();
						}
						break;
					}
					}
					} 
				}
				setState(212);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,16,_ctx);
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

	public static class PrimitiveTypeContext extends ParserRuleContext {
		public PrimitiveTypeContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_primitiveType; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterPrimitiveType(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitPrimitiveType(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitPrimitiveType(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final PrimitiveTypeContext primitiveType() throws RecognitionException {
		PrimitiveTypeContext _localctx = new PrimitiveTypeContext(_ctx, getState());
		enterRule(_localctx, 32, RULE_primitiveType);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(213);
			_la = _input.LA(1);
			if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << T__42) | (1L << T__39) | (1L << T__29) | (1L << T__23) | (1L << T__22) | (1L << T__21) | (1L << T__19) | (1L << T__16))) != 0)) ) {
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

	public boolean sempred(RuleContext _localctx, int ruleIndex, int predIndex) {
		switch (ruleIndex) {
		case 3: return expression_sempred((ExpressionContext)_localctx, predIndex);
		}
		return true;
	}
	private boolean expression_sempred(ExpressionContext _localctx, int predIndex) {
		switch (predIndex) {
		case 0: return precpred(_ctx, 13);

		case 1: return precpred(_ctx, 12);

		case 2: return precpred(_ctx, 11);

		case 3: return precpred(_ctx, 10);

		case 4: return precpred(_ctx, 8);

		case 5: return precpred(_ctx, 7);

		case 6: return precpred(_ctx, 6);

		case 7: return precpred(_ctx, 5);

		case 8: return precpred(_ctx, 4);

		case 9: return precpred(_ctx, 3);

		case 10: return precpred(_ctx, 2);

		case 11: return precpred(_ctx, 1);

		case 12: return precpred(_ctx, 19);

		case 13: return precpred(_ctx, 18);

		case 14: return precpred(_ctx, 17);

		case 15: return precpred(_ctx, 9);
		}
		return true;
	}

	public static final String _serializedATN =
		"\3\uaf6f\u8320\u479d\ub75c\u4880\u1605\u191c\uab37\39\u00da\4\2\t\2\4"+
		"\3\t\3\4\4\t\4\4\5\t\5\4\6\t\6\4\7\t\7\4\b\t\b\4\t\t\t\4\n\t\n\4\13\t"+
		"\13\4\f\t\f\4\r\t\r\4\16\t\16\4\17\t\17\4\20\t\20\4\21\t\21\4\22\t\22"+
		"\3\2\3\2\5\2\'\n\2\3\3\3\3\3\3\3\3\3\3\3\4\3\4\3\4\5\4\61\n\4\3\5\3\5"+
		"\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\5\5E"+
		"\n\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3"+
		"\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5"+
		"\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3"+
		"\5\5\5{\n\5\3\5\3\5\3\5\3\5\7\5\u0081\n\5\f\5\16\5\u0084\13\5\3\6\3\6"+
		"\3\6\3\6\3\6\3\6\3\6\5\6\u008d\n\6\3\7\3\7\3\7\7\7\u0092\n\7\f\7\16\7"+
		"\u0095\13\7\3\b\3\b\5\b\u0099\n\b\3\t\3\t\3\n\3\n\3\13\3\13\3\f\3\f\3"+
		"\f\3\r\3\r\3\r\3\r\7\r\u00a8\n\r\f\r\16\r\u00ab\13\r\3\r\3\r\3\16\3\16"+
		"\3\16\7\16\u00b2\n\16\f\16\16\16\u00b5\13\16\3\16\3\16\3\16\7\16\u00ba"+
		"\n\16\f\16\16\16\u00bd\13\16\5\16\u00bf\n\16\3\17\3\17\3\17\3\20\3\20"+
		"\5\20\u00c6\n\20\3\20\3\20\3\21\3\21\5\21\u00cc\n\21\3\21\3\21\3\21\5"+
		"\21\u00d1\n\21\7\21\u00d3\n\21\f\21\16\21\u00d6\13\21\3\22\3\22\3\22\2"+
		"\2\3\b\23\2\2\4\2\6\2\b\2\n\2\f\2\16\2\20\2\22\2\24\2\26\2\30\2\32\2\34"+
		"\2\36\2 \2\"\2\2\13\4\2\f\f  \4\2((--\5\2\n\n\33\33\'\'\5\2\4\4\24\24"+
		"\31\31\6\2\17\17\22\22$$))\4\2\21\21&&\4\2/\62\65\65\3\2\63\64\b\2\3\3"+
		"\6\6\20\20\26\30\32\32\35\35\u00ee\2$\3\2\2\2\4(\3\2\2\2\6\60\3\2\2\2"+
		"\bD\3\2\2\2\n\u008c\3\2\2\2\f\u008e\3\2\2\2\16\u0098\3\2\2\2\20\u009a"+
		"\3\2\2\2\22\u009c\3\2\2\2\24\u009e\3\2\2\2\26\u00a0\3\2\2\2\30\u00a3\3"+
		"\2\2\2\32\u00be\3\2\2\2\34\u00c0\3\2\2\2\36\u00c3\3\2\2\2 \u00c9\3\2\2"+
		"\2\"\u00d7\3\2\2\2$&\5\b\5\2%\'\5\4\3\2&%\3\2\2\2&\'\3\2\2\2\'\3\3\2\2"+
		"\2()\7\t\2\2)*\7\b\2\2*+\7!\2\2+,\5\6\4\2,\5\3\2\2\2-\61\5\16\b\2.\61"+
		"\78\2\2/\61\5\20\t\2\60-\3\2\2\2\60.\3\2\2\2\60/\3\2\2\2\61\7\3\2\2\2"+
		"\62\63\b\5\1\2\63\64\7\r\2\2\64\65\5\32\16\2\65\66\7\36\2\2\66\67\5\b"+
		"\5\22\67E\3\2\2\289\t\2\2\29E\5\b\5\21:;\t\3\2\2;E\5\b\5\20<=\7\r\2\2"+
		"=>\5\b\5\2>?\7\36\2\2?E\3\2\2\2@E\5\16\b\2AE\5\20\t\2BE\5\n\6\2CE\78\2"+
		"\2D\62\3\2\2\2D8\3\2\2\2D:\3\2\2\2D<\3\2\2\2D@\3\2\2\2DA\3\2\2\2DB\3\2"+
		"\2\2DC\3\2\2\2E\u0082\3\2\2\2FG\f\17\2\2GH\t\4\2\2H\u0081\5\b\5\20IJ\f"+
		"\16\2\2JK\t\2\2\2K\u0081\5\b\5\17LM\f\r\2\2MN\t\5\2\2N\u0081\5\b\5\16"+
		"OP\f\f\2\2PQ\t\6\2\2Q\u0081\5\b\5\rRS\f\n\2\2ST\t\7\2\2T\u0081\5\b\5\13"+
		"UV\f\t\2\2VW\7\7\2\2W\u0081\5\b\5\nXY\f\b\2\2YZ\7\34\2\2Z\u0081\5\b\5"+
		"\t[\\\f\7\2\2\\]\7+\2\2]\u0081\5\b\5\b^_\f\6\2\2_`\7\"\2\2`\u0081\5\b"+
		"\5\7ab\f\5\2\2bc\7#\2\2c\u0081\5\b\5\6de\f\4\2\2ef\7\23\2\2fg\5\b\5\2"+
		"gh\7\16\2\2hi\5\b\5\5i\u0081\3\2\2\2jk\f\3\2\2kl\7%\2\2l\u0081\5\b\5\4"+
		"mn\f\25\2\2no\7\37\2\2o\u0081\7\66\2\2pq\f\24\2\2qr\7\13\2\2rs\5\b\5\2"+
		"st\7\5\2\2t\u0081\3\2\2\2uv\f\23\2\2vw\7\37\2\2wx\7\66\2\2xz\7\r\2\2y"+
		"{\5\f\7\2zy\3\2\2\2z{\3\2\2\2{|\3\2\2\2|\u0081\7\36\2\2}~\f\13\2\2~\177"+
		"\7,\2\2\177\u0081\5\32\16\2\u0080F\3\2\2\2\u0080I\3\2\2\2\u0080L\3\2\2"+
		"\2\u0080O\3\2\2\2\u0080R\3\2\2\2\u0080U\3\2\2\2\u0080X\3\2\2\2\u0080["+
		"\3\2\2\2\u0080^\3\2\2\2\u0080a\3\2\2\2\u0080d\3\2\2\2\u0080j\3\2\2\2\u0080"+
		"m\3\2\2\2\u0080p\3\2\2\2\u0080u\3\2\2\2\u0080}\3\2\2\2\u0081\u0084\3\2"+
		"\2\2\u0082\u0080\3\2\2\2\u0082\u0083\3\2\2\2\u0083\t\3\2\2\2\u0084\u0082"+
		"\3\2\2\2\u0085\u0086\5\32\16\2\u0086\u0087\7\37\2\2\u0087\u0088\7*\2\2"+
		"\u0088\u008d\3\2\2\2\u0089\u008a\7\25\2\2\u008a\u008b\7\37\2\2\u008b\u008d"+
		"\7*\2\2\u008c\u0085\3\2\2\2\u008c\u0089\3\2\2\2\u008d\13\3\2\2\2\u008e"+
		"\u0093\5\b\5\2\u008f\u0090\7\t\2\2\u0090\u0092\5\b\5\2\u0091\u008f\3\2"+
		"\2\2\u0092\u0095\3\2\2\2\u0093\u0091\3\2\2\2\u0093\u0094\3\2\2\2\u0094"+
		"\r\3\2\2\2\u0095\u0093\3\2\2\2\u0096\u0099\5\22\n\2\u0097\u0099\5\24\13"+
		"\2\u0098\u0096\3\2\2\2\u0098\u0097\3\2\2\2\u0099\17\3\2\2\2\u009a\u009b"+
		"\7\66\2\2\u009b\21\3\2\2\2\u009c\u009d\t\b\2\2\u009d\23\3\2\2\2\u009e"+
		"\u009f\t\t\2\2\u009f\25\3\2\2\2\u00a0\u00a1\5\30\r\2\u00a1\u00a2\5\34"+
		"\17\2\u00a2\27\3\2\2\2\u00a3\u00a4\7\17\2\2\u00a4\u00a9\5\32\16\2\u00a5"+
		"\u00a6\7\t\2\2\u00a6\u00a8\5\32\16\2\u00a7\u00a5\3\2\2\2\u00a8\u00ab\3"+
		"\2\2\2\u00a9\u00a7\3\2\2\2\u00a9\u00aa\3\2\2\2\u00aa\u00ac\3\2\2\2\u00ab"+
		"\u00a9\3\2\2\2\u00ac\u00ad\7$\2\2\u00ad\31\3\2\2\2\u00ae\u00b3\5 \21\2"+
		"\u00af\u00b0\7\13\2\2\u00b0\u00b2\7\5\2\2\u00b1\u00af\3\2\2\2\u00b2\u00b5"+
		"\3\2\2\2\u00b3\u00b1\3\2\2\2\u00b3\u00b4\3\2\2\2\u00b4\u00bf\3\2\2\2\u00b5"+
		"\u00b3\3\2\2\2\u00b6\u00bb\5\"\22\2\u00b7\u00b8\7\13\2\2\u00b8\u00ba\7"+
		"\5\2\2\u00b9\u00b7\3\2\2\2\u00ba\u00bd\3\2\2\2\u00bb\u00b9\3\2\2\2\u00bb"+
		"\u00bc\3\2\2\2\u00bc\u00bf\3\2\2\2\u00bd\u00bb\3\2\2\2\u00be\u00ae\3\2"+
		"\2\2\u00be\u00b6\3\2\2\2\u00bf\33\3\2\2\2\u00c0\u00c1\7\66\2\2\u00c1\u00c2"+
		"\5\36\20\2\u00c2\35\3\2\2\2\u00c3\u00c5\7\r\2\2\u00c4\u00c6\5\f\7\2\u00c5"+
		"\u00c4\3\2\2\2\u00c5\u00c6\3\2\2\2\u00c6\u00c7\3\2\2\2\u00c7\u00c8\7\36"+
		"\2\2\u00c8\37\3\2\2\2\u00c9\u00cb\5\20\t\2\u00ca\u00cc\5\30\r\2\u00cb"+
		"\u00ca\3\2\2\2\u00cb\u00cc\3\2\2\2\u00cc\u00d4\3\2\2\2\u00cd\u00ce\7\37"+
		"\2\2\u00ce\u00d0\7\66\2\2\u00cf\u00d1\5\30\r\2\u00d0\u00cf\3\2\2\2\u00d0"+
		"\u00d1\3\2\2\2\u00d1\u00d3\3\2\2\2\u00d2\u00cd\3\2\2\2\u00d3\u00d6\3\2"+
		"\2\2\u00d4\u00d2\3\2\2\2\u00d4\u00d5\3\2\2\2\u00d5!\3\2\2\2\u00d6\u00d4"+
		"\3\2\2\2\u00d7\u00d8\t\n\2\2\u00d8#\3\2\2\2\23&\60Dz\u0080\u0082\u008c"+
		"\u0093\u0098\u00a9\u00b3\u00bb\u00be\u00c5\u00cb\u00d0\u00d4";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
	}
}