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
		PackageName=55, ResourceType=56;
	public static final String[] tokenNames = {
		"<INVALID>", "'long'", "'>>>'", "']'", "'short'", "'&'", "'default'", 
		"','", "'*'", "'['", "'-'", "'('", "':'", "'<'", "'int'", "'!='", "'<='", 
		"'?'", "'<<'", "'void'", "'double'", "'boolean'", "'float'", "'>>'", "'char'", 
		"'%'", "'^'", "'byte'", "')'", "'.'", "'+'", "'='", "'&&'", "'||'", "'>'", 
		"'??'", "'=='", "'/'", "'~'", "'>='", "'class'", "'|'", "'instanceof'", 
		"'!'", "'this'", "IntegerLiteral", "FloatingPointLiteral", "BooleanLiteral", 
		"CharacterLiteral", "SingleQuoteString", "DoubleQuoteString", "'null'", 
		"Identifier", "WS", "ResourceReference", "PackageName", "ResourceType"
	};
	public static final int
		RULE_bindingSyntax = 0, RULE_defaults = 1, RULE_constantValue = 2, RULE_expression = 3, 
		RULE_classExtraction = 4, RULE_expressionList = 5, RULE_literal = 6, RULE_identifier = 7, 
		RULE_javaLiteral = 8, RULE_stringLiteral = 9, RULE_explicitGenericInvocation = 10, 
		RULE_typeArguments = 11, RULE_type = 12, RULE_explicitGenericInvocationSuffix = 13, 
		RULE_arguments = 14, RULE_classOrInterfaceType = 15, RULE_primitiveType = 16, 
		RULE_resources = 17, RULE_resourceParameters = 18;
	public static final String[] ruleNames = {
		"bindingSyntax", "defaults", "constantValue", "expression", "classExtraction", 
		"expressionList", "literal", "identifier", "javaLiteral", "stringLiteral", 
		"explicitGenericInvocation", "typeArguments", "type", "explicitGenericInvocationSuffix", 
		"arguments", "classOrInterfaceType", "primitiveType", "resources", "resourceParameters"
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
			setState(38); expression(0);
			setState(40);
			_la = _input.LA(1);
			if (_la==T__36) {
				{
				setState(39); defaults();
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
			setState(42); match(T__36);
			setState(43); match(T__37);
			setState(44); match(T__12);
			setState(45); constantValue();
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
			setState(50);
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
				setState(47); literal();
				}
				break;
			case ResourceReference:
				enterOuterAlt(_localctx, 2);
				{
				setState(48); match(ResourceReference);
				}
				break;
			case Identifier:
				enterOuterAlt(_localctx, 3);
				{
				setState(49); identifier();
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
		public ResourcesContext resources() {
			return getRuleContext(ResourcesContext.class,0);
		}
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
			setState(70);
			switch ( getInterpreter().adaptivePredict(_input,2,_ctx) ) {
			case 1:
				{
				_localctx = new CastOpContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;

				setState(53); match(T__32);
				setState(54); type();
				setState(55); match(T__15);
				setState(56); expression(16);
				}
				break;

			case 2:
				{
				_localctx = new UnaryOpContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(58);
				((UnaryOpContext)_localctx).op = _input.LT(1);
				_la = _input.LA(1);
				if ( !(_la==T__33 || _la==T__13) ) {
					((UnaryOpContext)_localctx).op = _errHandler.recoverInline(this);
				}
				consume();
				setState(59); expression(15);
				}
				break;

			case 3:
				{
				_localctx = new UnaryOpContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(60);
				((UnaryOpContext)_localctx).op = _input.LT(1);
				_la = _input.LA(1);
				if ( !(_la==T__5 || _la==T__0) ) {
					((UnaryOpContext)_localctx).op = _errHandler.recoverInline(this);
				}
				consume();
				setState(61); expression(14);
				}
				break;

			case 4:
				{
				_localctx = new GroupingContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(62); match(T__32);
				setState(63); expression(0);
				setState(64); match(T__15);
				}
				break;

			case 5:
				{
				_localctx = new PrimaryContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(66); literal();
				}
				break;

			case 6:
				{
				_localctx = new PrimaryContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(67); identifier();
				}
				break;

			case 7:
				{
				_localctx = new PrimaryContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(68); classExtraction();
				}
				break;

			case 8:
				{
				_localctx = new ResourceContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(69); resources();
				}
				break;
			}
			_ctx.stop = _input.LT(-1);
			setState(132);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,5,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					if ( _parseListeners!=null ) triggerExitRuleEvent();
					_prevctx = _localctx;
					{
					setState(130);
					switch ( getInterpreter().adaptivePredict(_input,4,_ctx) ) {
					case 1:
						{
						_localctx = new MathOpContext(new ExpressionContext(_parentctx, _parentState));
						((MathOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(72);
						if (!(precpred(_ctx, 13))) throw new FailedPredicateException(this, "precpred(_ctx, 13)");
						setState(73);
						((MathOpContext)_localctx).op = _input.LT(1);
						_la = _input.LA(1);
						if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << T__35) | (1L << T__18) | (1L << T__6))) != 0)) ) {
							((MathOpContext)_localctx).op = _errHandler.recoverInline(this);
						}
						consume();
						setState(74); ((MathOpContext)_localctx).right = expression(14);
						}
						break;

					case 2:
						{
						_localctx = new MathOpContext(new ExpressionContext(_parentctx, _parentState));
						((MathOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(75);
						if (!(precpred(_ctx, 12))) throw new FailedPredicateException(this, "precpred(_ctx, 12)");
						setState(76);
						((MathOpContext)_localctx).op = _input.LT(1);
						_la = _input.LA(1);
						if ( !(_la==T__33 || _la==T__13) ) {
							((MathOpContext)_localctx).op = _errHandler.recoverInline(this);
						}
						consume();
						setState(77); ((MathOpContext)_localctx).right = expression(13);
						}
						break;

					case 3:
						{
						_localctx = new BitShiftOpContext(new ExpressionContext(_parentctx, _parentState));
						((BitShiftOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(78);
						if (!(precpred(_ctx, 11))) throw new FailedPredicateException(this, "precpred(_ctx, 11)");
						setState(79);
						((BitShiftOpContext)_localctx).op = _input.LT(1);
						_la = _input.LA(1);
						if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << T__41) | (1L << T__25) | (1L << T__20))) != 0)) ) {
							((BitShiftOpContext)_localctx).op = _errHandler.recoverInline(this);
						}
						consume();
						setState(80); ((BitShiftOpContext)_localctx).right = expression(12);
						}
						break;

					case 4:
						{
						_localctx = new ComparisonOpContext(new ExpressionContext(_parentctx, _parentState));
						((ComparisonOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(81);
						if (!(precpred(_ctx, 10))) throw new FailedPredicateException(this, "precpred(_ctx, 10)");
						setState(82);
						((ComparisonOpContext)_localctx).op = _input.LT(1);
						_la = _input.LA(1);
						if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << T__30) | (1L << T__27) | (1L << T__9) | (1L << T__4))) != 0)) ) {
							((ComparisonOpContext)_localctx).op = _errHandler.recoverInline(this);
						}
						consume();
						setState(83); ((ComparisonOpContext)_localctx).right = expression(11);
						}
						break;

					case 5:
						{
						_localctx = new ComparisonOpContext(new ExpressionContext(_parentctx, _parentState));
						((ComparisonOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(84);
						if (!(precpred(_ctx, 8))) throw new FailedPredicateException(this, "precpred(_ctx, 8)");
						setState(85);
						((ComparisonOpContext)_localctx).op = _input.LT(1);
						_la = _input.LA(1);
						if ( !(_la==T__28 || _la==T__7) ) {
							((ComparisonOpContext)_localctx).op = _errHandler.recoverInline(this);
						}
						consume();
						setState(86); ((ComparisonOpContext)_localctx).right = expression(9);
						}
						break;

					case 6:
						{
						_localctx = new BinaryOpContext(new ExpressionContext(_parentctx, _parentState));
						((BinaryOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(87);
						if (!(precpred(_ctx, 7))) throw new FailedPredicateException(this, "precpred(_ctx, 7)");
						setState(88); ((BinaryOpContext)_localctx).op = match(T__38);
						setState(89); ((BinaryOpContext)_localctx).right = expression(8);
						}
						break;

					case 7:
						{
						_localctx = new BinaryOpContext(new ExpressionContext(_parentctx, _parentState));
						((BinaryOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(90);
						if (!(precpred(_ctx, 6))) throw new FailedPredicateException(this, "precpred(_ctx, 6)");
						setState(91); ((BinaryOpContext)_localctx).op = match(T__17);
						setState(92); ((BinaryOpContext)_localctx).right = expression(7);
						}
						break;

					case 8:
						{
						_localctx = new BinaryOpContext(new ExpressionContext(_parentctx, _parentState));
						((BinaryOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(93);
						if (!(precpred(_ctx, 5))) throw new FailedPredicateException(this, "precpred(_ctx, 5)");
						setState(94); ((BinaryOpContext)_localctx).op = match(T__2);
						setState(95); ((BinaryOpContext)_localctx).right = expression(6);
						}
						break;

					case 9:
						{
						_localctx = new AndOrOpContext(new ExpressionContext(_parentctx, _parentState));
						((AndOrOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(96);
						if (!(precpred(_ctx, 4))) throw new FailedPredicateException(this, "precpred(_ctx, 4)");
						setState(97); ((AndOrOpContext)_localctx).op = match(T__11);
						setState(98); ((AndOrOpContext)_localctx).right = expression(5);
						}
						break;

					case 10:
						{
						_localctx = new AndOrOpContext(new ExpressionContext(_parentctx, _parentState));
						((AndOrOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(99);
						if (!(precpred(_ctx, 3))) throw new FailedPredicateException(this, "precpred(_ctx, 3)");
						setState(100); ((AndOrOpContext)_localctx).op = match(T__10);
						setState(101); ((AndOrOpContext)_localctx).right = expression(4);
						}
						break;

					case 11:
						{
						_localctx = new TernaryOpContext(new ExpressionContext(_parentctx, _parentState));
						((TernaryOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(102);
						if (!(precpred(_ctx, 2))) throw new FailedPredicateException(this, "precpred(_ctx, 2)");
						setState(103); ((TernaryOpContext)_localctx).op = match(T__26);
						setState(104); ((TernaryOpContext)_localctx).iftrue = expression(0);
						setState(105); match(T__31);
						setState(106); ((TernaryOpContext)_localctx).iffalse = expression(3);
						}
						break;

					case 12:
						{
						_localctx = new QuestionQuestionOpContext(new ExpressionContext(_parentctx, _parentState));
						((QuestionQuestionOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(108);
						if (!(precpred(_ctx, 1))) throw new FailedPredicateException(this, "precpred(_ctx, 1)");
						setState(109); ((QuestionQuestionOpContext)_localctx).op = match(T__8);
						setState(110); ((QuestionQuestionOpContext)_localctx).right = expression(2);
						}
						break;

					case 13:
						{
						_localctx = new DotOpContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(111);
						if (!(precpred(_ctx, 19))) throw new FailedPredicateException(this, "precpred(_ctx, 19)");
						setState(112); match(T__14);
						setState(113); match(Identifier);
						}
						break;

					case 14:
						{
						_localctx = new BracketOpContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(114);
						if (!(precpred(_ctx, 18))) throw new FailedPredicateException(this, "precpred(_ctx, 18)");
						setState(115); match(T__34);
						setState(116); expression(0);
						setState(117); match(T__40);
						}
						break;

					case 15:
						{
						_localctx = new MethodInvocationContext(new ExpressionContext(_parentctx, _parentState));
						((MethodInvocationContext)_localctx).target = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(119);
						if (!(precpred(_ctx, 17))) throw new FailedPredicateException(this, "precpred(_ctx, 17)");
						setState(120); match(T__14);
						setState(121); ((MethodInvocationContext)_localctx).methodName = match(Identifier);
						setState(122); match(T__32);
						setState(124);
						_la = _input.LA(1);
						if ((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << T__42) | (1L << T__39) | (1L << T__33) | (1L << T__32) | (1L << T__29) | (1L << T__24) | (1L << T__23) | (1L << T__22) | (1L << T__21) | (1L << T__19) | (1L << T__16) | (1L << T__13) | (1L << T__5) | (1L << T__0) | (1L << IntegerLiteral) | (1L << FloatingPointLiteral) | (1L << BooleanLiteral) | (1L << CharacterLiteral) | (1L << SingleQuoteString) | (1L << DoubleQuoteString) | (1L << NullLiteral) | (1L << Identifier) | (1L << ResourceReference))) != 0)) {
							{
							setState(123); ((MethodInvocationContext)_localctx).args = expressionList();
							}
						}

						setState(126); match(T__15);
						}
						break;

					case 16:
						{
						_localctx = new InstanceOfOpContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(127);
						if (!(precpred(_ctx, 9))) throw new FailedPredicateException(this, "precpred(_ctx, 9)");
						setState(128); match(T__1);
						setState(129); type();
						}
						break;
					}
					} 
				}
				setState(134);
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
			setState(142);
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
				setState(135); type();
				setState(136); match(T__14);
				setState(137); match(T__3);
				}
				break;
			case T__24:
				enterOuterAlt(_localctx, 2);
				{
				setState(139); match(T__24);
				setState(140); match(T__14);
				setState(141); match(T__3);
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
			setState(144); expression(0);
			setState(149);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==T__36) {
				{
				{
				setState(145); match(T__36);
				setState(146); expression(0);
				}
				}
				setState(151);
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
			setState(154);
			switch (_input.LA(1)) {
			case IntegerLiteral:
			case FloatingPointLiteral:
			case BooleanLiteral:
			case CharacterLiteral:
			case NullLiteral:
				enterOuterAlt(_localctx, 1);
				{
				setState(152); javaLiteral();
				}
				break;
			case SingleQuoteString:
			case DoubleQuoteString:
				enterOuterAlt(_localctx, 2);
				{
				setState(153); stringLiteral();
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
			setState(156); match(Identifier);
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
			setState(158);
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
			setState(160);
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
			setState(162); typeArguments();
			setState(163); explicitGenericInvocationSuffix();
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
			setState(165); match(T__30);
			setState(166); type();
			setState(171);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==T__36) {
				{
				{
				setState(167); match(T__36);
				setState(168); type();
				}
				}
				setState(173);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(174); match(T__9);
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
			setState(192);
			switch (_input.LA(1)) {
			case Identifier:
				enterOuterAlt(_localctx, 1);
				{
				setState(176); classOrInterfaceType();
				setState(181);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,10,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(177); match(T__34);
						setState(178); match(T__40);
						}
						} 
					}
					setState(183);
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
				setState(184); primitiveType();
				setState(189);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,11,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(185); match(T__34);
						setState(186); match(T__40);
						}
						} 
					}
					setState(191);
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
			setState(194); match(Identifier);
			setState(195); arguments();
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
			setState(197); match(T__32);
			setState(199);
			_la = _input.LA(1);
			if ((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << T__42) | (1L << T__39) | (1L << T__33) | (1L << T__32) | (1L << T__29) | (1L << T__24) | (1L << T__23) | (1L << T__22) | (1L << T__21) | (1L << T__19) | (1L << T__16) | (1L << T__13) | (1L << T__5) | (1L << T__0) | (1L << IntegerLiteral) | (1L << FloatingPointLiteral) | (1L << BooleanLiteral) | (1L << CharacterLiteral) | (1L << SingleQuoteString) | (1L << DoubleQuoteString) | (1L << NullLiteral) | (1L << Identifier) | (1L << ResourceReference))) != 0)) {
				{
				setState(198); expressionList();
				}
			}

			setState(201); match(T__15);
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
			setState(203); identifier();
			setState(205);
			switch ( getInterpreter().adaptivePredict(_input,14,_ctx) ) {
			case 1:
				{
				setState(204); typeArguments();
				}
				break;
			}
			setState(214);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,16,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(207); match(T__14);
					setState(208); match(Identifier);
					setState(210);
					switch ( getInterpreter().adaptivePredict(_input,15,_ctx) ) {
					case 1:
						{
						setState(209); typeArguments();
						}
						break;
					}
					}
					} 
				}
				setState(216);
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
			setState(217);
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

	public static class ResourcesContext extends ParserRuleContext {
		public ResourceParametersContext resourceParameters() {
			return getRuleContext(ResourceParametersContext.class,0);
		}
		public TerminalNode ResourceReference() { return getToken(BindingExpressionParser.ResourceReference, 0); }
		public ResourcesContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_resources; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterResources(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitResources(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitResources(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final ResourcesContext resources() throws RecognitionException {
		ResourcesContext _localctx = new ResourcesContext(_ctx, getState());
		enterRule(_localctx, 34, RULE_resources);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(219); match(ResourceReference);
			setState(221);
			switch ( getInterpreter().adaptivePredict(_input,17,_ctx) ) {
			case 1:
				{
				setState(220); resourceParameters();
				}
				break;
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

	public static class ResourceParametersContext extends ParserRuleContext {
		public ExpressionListContext expressionList() {
			return getRuleContext(ExpressionListContext.class,0);
		}
		public ResourceParametersContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_resourceParameters; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterResourceParameters(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitResourceParameters(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitResourceParameters(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final ResourceParametersContext resourceParameters() throws RecognitionException {
		ResourceParametersContext _localctx = new ResourceParametersContext(_ctx, getState());
		enterRule(_localctx, 36, RULE_resourceParameters);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(223); match(T__32);
			setState(224); expressionList();
			setState(225); match(T__15);
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
		"\3\uaf6f\u8320\u479d\ub75c\u4880\u1605\u191c\uab37\3:\u00e6\4\2\t\2\4"+
		"\3\t\3\4\4\t\4\4\5\t\5\4\6\t\6\4\7\t\7\4\b\t\b\4\t\t\t\4\n\t\n\4\13\t"+
		"\13\4\f\t\f\4\r\t\r\4\16\t\16\4\17\t\17\4\20\t\20\4\21\t\21\4\22\t\22"+
		"\4\23\t\23\4\24\t\24\3\2\3\2\5\2+\n\2\3\3\3\3\3\3\3\3\3\3\3\4\3\4\3\4"+
		"\5\4\65\n\4\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3"+
		"\5\3\5\3\5\3\5\5\5I\n\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3"+
		"\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5"+
		"\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3\5\3"+
		"\5\3\5\3\5\3\5\3\5\3\5\5\5\177\n\5\3\5\3\5\3\5\3\5\7\5\u0085\n\5\f\5\16"+
		"\5\u0088\13\5\3\6\3\6\3\6\3\6\3\6\3\6\3\6\5\6\u0091\n\6\3\7\3\7\3\7\7"+
		"\7\u0096\n\7\f\7\16\7\u0099\13\7\3\b\3\b\5\b\u009d\n\b\3\t\3\t\3\n\3\n"+
		"\3\13\3\13\3\f\3\f\3\f\3\r\3\r\3\r\3\r\7\r\u00ac\n\r\f\r\16\r\u00af\13"+
		"\r\3\r\3\r\3\16\3\16\3\16\7\16\u00b6\n\16\f\16\16\16\u00b9\13\16\3\16"+
		"\3\16\3\16\7\16\u00be\n\16\f\16\16\16\u00c1\13\16\5\16\u00c3\n\16\3\17"+
		"\3\17\3\17\3\20\3\20\5\20\u00ca\n\20\3\20\3\20\3\21\3\21\5\21\u00d0\n"+
		"\21\3\21\3\21\3\21\5\21\u00d5\n\21\7\21\u00d7\n\21\f\21\16\21\u00da\13"+
		"\21\3\22\3\22\3\23\3\23\5\23\u00e0\n\23\3\24\3\24\3\24\3\24\3\24\2\2\3"+
		"\b\25\2\2\4\2\6\2\b\2\n\2\f\2\16\2\20\2\22\2\24\2\26\2\30\2\32\2\34\2"+
		"\36\2 \2\"\2$\2&\2\2\13\4\2\f\f  \4\2((--\5\2\n\n\33\33\'\'\5\2\4\4\24"+
		"\24\31\31\6\2\17\17\22\22$$))\4\2\21\21&&\4\2/\62\65\65\3\2\63\64\b\2"+
		"\3\3\6\6\20\20\26\30\32\32\35\35\u00f9\2(\3\2\2\2\4,\3\2\2\2\6\64\3\2"+
		"\2\2\bH\3\2\2\2\n\u0090\3\2\2\2\f\u0092\3\2\2\2\16\u009c\3\2\2\2\20\u009e"+
		"\3\2\2\2\22\u00a0\3\2\2\2\24\u00a2\3\2\2\2\26\u00a4\3\2\2\2\30\u00a7\3"+
		"\2\2\2\32\u00c2\3\2\2\2\34\u00c4\3\2\2\2\36\u00c7\3\2\2\2 \u00cd\3\2\2"+
		"\2\"\u00db\3\2\2\2$\u00dd\3\2\2\2&\u00e1\3\2\2\2(*\5\b\5\2)+\5\4\3\2*"+
		")\3\2\2\2*+\3\2\2\2+\3\3\2\2\2,-\7\t\2\2-.\7\b\2\2./\7!\2\2/\60\5\6\4"+
		"\2\60\5\3\2\2\2\61\65\5\16\b\2\62\65\78\2\2\63\65\5\20\t\2\64\61\3\2\2"+
		"\2\64\62\3\2\2\2\64\63\3\2\2\2\65\7\3\2\2\2\66\67\b\5\1\2\678\7\r\2\2"+
		"89\5\32\16\29:\7\36\2\2:;\5\b\5\22;I\3\2\2\2<=\t\2\2\2=I\5\b\5\21>?\t"+
		"\3\2\2?I\5\b\5\20@A\7\r\2\2AB\5\b\5\2BC\7\36\2\2CI\3\2\2\2DI\5\16\b\2"+
		"EI\5\20\t\2FI\5\n\6\2GI\5$\23\2H\66\3\2\2\2H<\3\2\2\2H>\3\2\2\2H@\3\2"+
		"\2\2HD\3\2\2\2HE\3\2\2\2HF\3\2\2\2HG\3\2\2\2I\u0086\3\2\2\2JK\f\17\2\2"+
		"KL\t\4\2\2L\u0085\5\b\5\20MN\f\16\2\2NO\t\2\2\2O\u0085\5\b\5\17PQ\f\r"+
		"\2\2QR\t\5\2\2R\u0085\5\b\5\16ST\f\f\2\2TU\t\6\2\2U\u0085\5\b\5\rVW\f"+
		"\n\2\2WX\t\7\2\2X\u0085\5\b\5\13YZ\f\t\2\2Z[\7\7\2\2[\u0085\5\b\5\n\\"+
		"]\f\b\2\2]^\7\34\2\2^\u0085\5\b\5\t_`\f\7\2\2`a\7+\2\2a\u0085\5\b\5\b"+
		"bc\f\6\2\2cd\7\"\2\2d\u0085\5\b\5\7ef\f\5\2\2fg\7#\2\2g\u0085\5\b\5\6"+
		"hi\f\4\2\2ij\7\23\2\2jk\5\b\5\2kl\7\16\2\2lm\5\b\5\5m\u0085\3\2\2\2no"+
		"\f\3\2\2op\7%\2\2p\u0085\5\b\5\4qr\f\25\2\2rs\7\37\2\2s\u0085\7\66\2\2"+
		"tu\f\24\2\2uv\7\13\2\2vw\5\b\5\2wx\7\5\2\2x\u0085\3\2\2\2yz\f\23\2\2z"+
		"{\7\37\2\2{|\7\66\2\2|~\7\r\2\2}\177\5\f\7\2~}\3\2\2\2~\177\3\2\2\2\177"+
		"\u0080\3\2\2\2\u0080\u0085\7\36\2\2\u0081\u0082\f\13\2\2\u0082\u0083\7"+
		",\2\2\u0083\u0085\5\32\16\2\u0084J\3\2\2\2\u0084M\3\2\2\2\u0084P\3\2\2"+
		"\2\u0084S\3\2\2\2\u0084V\3\2\2\2\u0084Y\3\2\2\2\u0084\\\3\2\2\2\u0084"+
		"_\3\2\2\2\u0084b\3\2\2\2\u0084e\3\2\2\2\u0084h\3\2\2\2\u0084n\3\2\2\2"+
		"\u0084q\3\2\2\2\u0084t\3\2\2\2\u0084y\3\2\2\2\u0084\u0081\3\2\2\2\u0085"+
		"\u0088\3\2\2\2\u0086\u0084\3\2\2\2\u0086\u0087\3\2\2\2\u0087\t\3\2\2\2"+
		"\u0088\u0086\3\2\2\2\u0089\u008a\5\32\16\2\u008a\u008b\7\37\2\2\u008b"+
		"\u008c\7*\2\2\u008c\u0091\3\2\2\2\u008d\u008e\7\25\2\2\u008e\u008f\7\37"+
		"\2\2\u008f\u0091\7*\2\2\u0090\u0089\3\2\2\2\u0090\u008d\3\2\2\2\u0091"+
		"\13\3\2\2\2\u0092\u0097\5\b\5\2\u0093\u0094\7\t\2\2\u0094\u0096\5\b\5"+
		"\2\u0095\u0093\3\2\2\2\u0096\u0099\3\2\2\2\u0097\u0095\3\2\2\2\u0097\u0098"+
		"\3\2\2\2\u0098\r\3\2\2\2\u0099\u0097\3\2\2\2\u009a\u009d\5\22\n\2\u009b"+
		"\u009d\5\24\13\2\u009c\u009a\3\2\2\2\u009c\u009b\3\2\2\2\u009d\17\3\2"+
		"\2\2\u009e\u009f\7\66\2\2\u009f\21\3\2\2\2\u00a0\u00a1\t\b\2\2\u00a1\23"+
		"\3\2\2\2\u00a2\u00a3\t\t\2\2\u00a3\25\3\2\2\2\u00a4\u00a5\5\30\r\2\u00a5"+
		"\u00a6\5\34\17\2\u00a6\27\3\2\2\2\u00a7\u00a8\7\17\2\2\u00a8\u00ad\5\32"+
		"\16\2\u00a9\u00aa\7\t\2\2\u00aa\u00ac\5\32\16\2\u00ab\u00a9\3\2\2\2\u00ac"+
		"\u00af\3\2\2\2\u00ad\u00ab\3\2\2\2\u00ad\u00ae\3\2\2\2\u00ae\u00b0\3\2"+
		"\2\2\u00af\u00ad\3\2\2\2\u00b0\u00b1\7$\2\2\u00b1\31\3\2\2\2\u00b2\u00b7"+
		"\5 \21\2\u00b3\u00b4\7\13\2\2\u00b4\u00b6\7\5\2\2\u00b5\u00b3\3\2\2\2"+
		"\u00b6\u00b9\3\2\2\2\u00b7\u00b5\3\2\2\2\u00b7\u00b8\3\2\2\2\u00b8\u00c3"+
		"\3\2\2\2\u00b9\u00b7\3\2\2\2\u00ba\u00bf\5\"\22\2\u00bb\u00bc\7\13\2\2"+
		"\u00bc\u00be\7\5\2\2\u00bd\u00bb\3\2\2\2\u00be\u00c1\3\2\2\2\u00bf\u00bd"+
		"\3\2\2\2\u00bf\u00c0\3\2\2\2\u00c0\u00c3\3\2\2\2\u00c1\u00bf\3\2\2\2\u00c2"+
		"\u00b2\3\2\2\2\u00c2\u00ba\3\2\2\2\u00c3\33\3\2\2\2\u00c4\u00c5\7\66\2"+
		"\2\u00c5\u00c6\5\36\20\2\u00c6\35\3\2\2\2\u00c7\u00c9\7\r\2\2\u00c8\u00ca"+
		"\5\f\7\2\u00c9\u00c8\3\2\2\2\u00c9\u00ca\3\2\2\2\u00ca\u00cb\3\2\2\2\u00cb"+
		"\u00cc\7\36\2\2\u00cc\37\3\2\2\2\u00cd\u00cf\5\20\t\2\u00ce\u00d0\5\30"+
		"\r\2\u00cf\u00ce\3\2\2\2\u00cf\u00d0\3\2\2\2\u00d0\u00d8\3\2\2\2\u00d1"+
		"\u00d2\7\37\2\2\u00d2\u00d4\7\66\2\2\u00d3\u00d5\5\30\r\2\u00d4\u00d3"+
		"\3\2\2\2\u00d4\u00d5\3\2\2\2\u00d5\u00d7\3\2\2\2\u00d6\u00d1\3\2\2\2\u00d7"+
		"\u00da\3\2\2\2\u00d8\u00d6\3\2\2\2\u00d8\u00d9\3\2\2\2\u00d9!\3\2\2\2"+
		"\u00da\u00d8\3\2\2\2\u00db\u00dc\t\n\2\2\u00dc#\3\2\2\2\u00dd\u00df\7"+
		"8\2\2\u00de\u00e0\5&\24\2\u00df\u00de\3\2\2\2\u00df\u00e0\3\2\2\2\u00e0"+
		"%\3\2\2\2\u00e1\u00e2\7\r\2\2\u00e2\u00e3\5\f\7\2\u00e3\u00e4\7\36\2\2"+
		"\u00e4\'\3\2\2\2\24*\64H~\u0084\u0086\u0090\u0097\u009c\u00ad\u00b7\u00bf"+
		"\u00c2\u00c9\u00cf\u00d4\u00d8\u00df";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
	}
}