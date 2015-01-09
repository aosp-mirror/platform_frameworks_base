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
		"'%'", "'^'", "'byte'", "'.'", "')'", "'+'", "'='", "'&&'", "'||'", "'>'", 
		"'??'", "'/'", "'=='", "'~'", "'>='", "'class'", "'|'", "'instanceof'", 
		"'!'", "'this'", "IntegerLiteral", "FloatingPointLiteral", "BooleanLiteral", 
		"CharacterLiteral", "SingleQuoteString", "DoubleQuoteString", "'null'", 
		"Identifier", "WS", "ResourceReference", "ResourceName"
	};
	public static final int
		RULE_bindingSyntax = 0, RULE_defaults = 1, RULE_constantValue = 2, RULE_constantExpression = 3, 
		RULE_expression = 4, RULE_classExtraction = 5, RULE_expressionList = 6, 
		RULE_literal = 7, RULE_identifier = 8, RULE_javaLiteral = 9, RULE_stringLiteral = 10, 
		RULE_explicitGenericInvocation = 11, RULE_typeArguments = 12, RULE_type = 13, 
		RULE_explicitGenericInvocationSuffix = 14, RULE_arguments = 15, RULE_classOrInterfaceType = 16, 
		RULE_primitiveType = 17;
	public static final String[] ruleNames = {
		"bindingSyntax", "defaults", "constantValue", "constantExpression", "expression", 
		"classExtraction", "expressionList", "literal", "identifier", "javaLiteral", 
		"stringLiteral", "explicitGenericInvocation", "typeArguments", "type", 
		"explicitGenericInvocationSuffix", "arguments", "classOrInterfaceType", 
		"primitiveType"
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
			setState(36); expression(0);
			setState(38);
			_la = _input.LA(1);
			if (_la==T__36) {
				{
				setState(37); defaults();
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
			setState(40); match(T__36);
			setState(41); match(T__37);
			setState(42); match(T__12);
			setState(43); constantValue();
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
		public ConstantExpressionContext constantExpression() {
			return getRuleContext(ConstantExpressionContext.class,0);
		}
		public LiteralContext literal() {
			return getRuleContext(LiteralContext.class,0);
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
			setState(48);
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
				setState(45); literal();
				}
				break;
			case ResourceReference:
				enterOuterAlt(_localctx, 2);
				{
				setState(46); match(ResourceReference);
				}
				break;
			case Identifier:
				enterOuterAlt(_localctx, 3);
				{
				setState(47); constantExpression(0);
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

	public static class ConstantExpressionContext extends ParserRuleContext {
		public TerminalNode Identifier() { return getToken(BindingExpressionParser.Identifier, 0); }
		public ConstantExpressionContext constantExpression() {
			return getRuleContext(ConstantExpressionContext.class,0);
		}
		public IdentifierContext identifier() {
			return getRuleContext(IdentifierContext.class,0);
		}
		public ConstantExpressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_constantExpression; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterConstantExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitConstantExpression(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitConstantExpression(this);
			else return visitor.visitChildren(this);
		}
	}

	@RuleVersion(0)
	public final ConstantExpressionContext constantExpression() throws RecognitionException {
		return constantExpression(0);
	}

	private ConstantExpressionContext constantExpression(int _p) throws RecognitionException {
		ParserRuleContext _parentctx = _ctx;
		int _parentState = getState();
		ConstantExpressionContext _localctx = new ConstantExpressionContext(_ctx, _parentState);
		ConstantExpressionContext _prevctx = _localctx;
		int _startState = 6;
		enterRecursionRule(_localctx, 6, RULE_constantExpression, _p);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			{
			setState(51); identifier();
			}
			_ctx.stop = _input.LT(-1);
			setState(58);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,2,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					if ( _parseListeners!=null ) triggerExitRuleEvent();
					_prevctx = _localctx;
					{
					{
					_localctx = new ConstantExpressionContext(_parentctx, _parentState);
					pushNewRecursionContext(_localctx, _startState, RULE_constantExpression);
					setState(53);
					if (!(precpred(_ctx, 2))) throw new FailedPredicateException(this, "precpred(_ctx, 2)");
					setState(54); match(T__15);
					setState(55); match(Identifier);
					}
					} 
				}
				setState(60);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,2,_ctx);
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
	public static class ExplicitGenericInvocationOpContext extends ExpressionContext {
		public ExplicitGenericInvocationContext explicitGenericInvocation() {
			return getRuleContext(ExplicitGenericInvocationContext.class,0);
		}
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public ExplicitGenericInvocationOpContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterExplicitGenericInvocationOp(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitExplicitGenericInvocationOp(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitExplicitGenericInvocationOp(this);
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
	public static class GenericCallContext extends ExpressionContext {
		public ExplicitGenericInvocationSuffixContext explicitGenericInvocationSuffix() {
			return getRuleContext(ExplicitGenericInvocationSuffixContext.class,0);
		}
		public ArgumentsContext arguments() {
			return getRuleContext(ArgumentsContext.class,0);
		}
		public TypeArgumentsContext typeArguments() {
			return getRuleContext(TypeArgumentsContext.class,0);
		}
		public GenericCallContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).enterGenericCall(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof BindingExpressionListener ) ((BindingExpressionListener)listener).exitGenericCall(this);
		}
		@Override
		public <Result> Result accept(ParseTreeVisitor<? extends Result> visitor) {
			if ( visitor instanceof BindingExpressionVisitor<?> ) return ((BindingExpressionVisitor<? extends Result>)visitor).visitGenericCall(this);
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
		int _startState = 8;
		enterRecursionRule(_localctx, 8, RULE_expression, _p);
		int _la;
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(85);
			switch ( getInterpreter().adaptivePredict(_input,4,_ctx) ) {
			case 1:
				{
				_localctx = new CastOpContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;

				setState(62); match(T__32);
				setState(63); type();
				setState(64); match(T__14);
				setState(65); expression(16);
				}
				break;

			case 2:
				{
				_localctx = new UnaryOpContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(67);
				_la = _input.LA(1);
				if ( !(_la==T__33 || _la==T__13) ) {
				_errHandler.recoverInline(this);
				}
				consume();
				setState(68); expression(15);
				}
				break;

			case 3:
				{
				_localctx = new UnaryOpContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(69);
				_la = _input.LA(1);
				if ( !(_la==T__5 || _la==T__0) ) {
				_errHandler.recoverInline(this);
				}
				consume();
				setState(70); expression(14);
				}
				break;

			case 4:
				{
				_localctx = new GroupingContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(71); match(T__32);
				setState(72); expression(0);
				setState(73); match(T__14);
				}
				break;

			case 5:
				{
				_localctx = new PrimaryContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(75); literal();
				}
				break;

			case 6:
				{
				_localctx = new PrimaryContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(76); identifier();
				}
				break;

			case 7:
				{
				_localctx = new PrimaryContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(77); classExtraction();
				}
				break;

			case 8:
				{
				_localctx = new ResourceContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(78); match(ResourceReference);
				}
				break;

			case 9:
				{
				_localctx = new GenericCallContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(79); typeArguments();
				setState(83);
				switch (_input.LA(1)) {
				case Identifier:
					{
					setState(80); explicitGenericInvocationSuffix();
					}
					break;
				case THIS:
					{
					setState(81); match(THIS);
					setState(82); arguments();
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				}
				break;
			}
			_ctx.stop = _input.LT(-1);
			setState(150);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,7,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					if ( _parseListeners!=null ) triggerExitRuleEvent();
					_prevctx = _localctx;
					{
					setState(148);
					switch ( getInterpreter().adaptivePredict(_input,6,_ctx) ) {
					case 1:
						{
						_localctx = new MathOpContext(new ExpressionContext(_parentctx, _parentState));
						((MathOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(87);
						if (!(precpred(_ctx, 13))) throw new FailedPredicateException(this, "precpred(_ctx, 13)");
						setState(88);
						((MathOpContext)_localctx).op = _input.LT(1);
						_la = _input.LA(1);
						if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << T__35) | (1L << T__18) | (1L << T__7))) != 0)) ) {
							((MathOpContext)_localctx).op = _errHandler.recoverInline(this);
						}
						consume();
						setState(89); ((MathOpContext)_localctx).right = expression(14);
						}
						break;

					case 2:
						{
						_localctx = new MathOpContext(new ExpressionContext(_parentctx, _parentState));
						((MathOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(90);
						if (!(precpred(_ctx, 12))) throw new FailedPredicateException(this, "precpred(_ctx, 12)");
						setState(91);
						((MathOpContext)_localctx).op = _input.LT(1);
						_la = _input.LA(1);
						if ( !(_la==T__33 || _la==T__13) ) {
							((MathOpContext)_localctx).op = _errHandler.recoverInline(this);
						}
						consume();
						setState(92); ((MathOpContext)_localctx).right = expression(13);
						}
						break;

					case 3:
						{
						_localctx = new BitShiftOpContext(new ExpressionContext(_parentctx, _parentState));
						((BitShiftOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(93);
						if (!(precpred(_ctx, 11))) throw new FailedPredicateException(this, "precpred(_ctx, 11)");
						setState(94);
						((BitShiftOpContext)_localctx).op = _input.LT(1);
						_la = _input.LA(1);
						if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << T__41) | (1L << T__25) | (1L << T__20))) != 0)) ) {
							((BitShiftOpContext)_localctx).op = _errHandler.recoverInline(this);
						}
						consume();
						setState(95); ((BitShiftOpContext)_localctx).right = expression(12);
						}
						break;

					case 4:
						{
						_localctx = new ComparisonOpContext(new ExpressionContext(_parentctx, _parentState));
						((ComparisonOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(96);
						if (!(precpred(_ctx, 10))) throw new FailedPredicateException(this, "precpred(_ctx, 10)");
						setState(97);
						((ComparisonOpContext)_localctx).op = _input.LT(1);
						_la = _input.LA(1);
						if ( !((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << T__30) | (1L << T__27) | (1L << T__9) | (1L << T__4))) != 0)) ) {
							((ComparisonOpContext)_localctx).op = _errHandler.recoverInline(this);
						}
						consume();
						setState(98); ((ComparisonOpContext)_localctx).right = expression(11);
						}
						break;

					case 5:
						{
						_localctx = new ComparisonOpContext(new ExpressionContext(_parentctx, _parentState));
						((ComparisonOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(99);
						if (!(precpred(_ctx, 8))) throw new FailedPredicateException(this, "precpred(_ctx, 8)");
						setState(100);
						((ComparisonOpContext)_localctx).op = _input.LT(1);
						_la = _input.LA(1);
						if ( !(_la==T__28 || _la==T__6) ) {
							((ComparisonOpContext)_localctx).op = _errHandler.recoverInline(this);
						}
						consume();
						setState(101); ((ComparisonOpContext)_localctx).right = expression(9);
						}
						break;

					case 6:
						{
						_localctx = new BinaryOpContext(new ExpressionContext(_parentctx, _parentState));
						((BinaryOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(102);
						if (!(precpred(_ctx, 7))) throw new FailedPredicateException(this, "precpred(_ctx, 7)");
						setState(103); ((BinaryOpContext)_localctx).op = match(T__38);
						setState(104); ((BinaryOpContext)_localctx).right = expression(8);
						}
						break;

					case 7:
						{
						_localctx = new BinaryOpContext(new ExpressionContext(_parentctx, _parentState));
						((BinaryOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(105);
						if (!(precpred(_ctx, 6))) throw new FailedPredicateException(this, "precpred(_ctx, 6)");
						setState(106); ((BinaryOpContext)_localctx).op = match(T__17);
						setState(107); ((BinaryOpContext)_localctx).right = expression(7);
						}
						break;

					case 8:
						{
						_localctx = new BinaryOpContext(new ExpressionContext(_parentctx, _parentState));
						((BinaryOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(108);
						if (!(precpred(_ctx, 5))) throw new FailedPredicateException(this, "precpred(_ctx, 5)");
						setState(109); ((BinaryOpContext)_localctx).op = match(T__2);
						setState(110); ((BinaryOpContext)_localctx).right = expression(6);
						}
						break;

					case 9:
						{
						_localctx = new AndOrOpContext(new ExpressionContext(_parentctx, _parentState));
						((AndOrOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(111);
						if (!(precpred(_ctx, 4))) throw new FailedPredicateException(this, "precpred(_ctx, 4)");
						setState(112); ((AndOrOpContext)_localctx).op = match(T__11);
						setState(113); ((AndOrOpContext)_localctx).right = expression(5);
						}
						break;

					case 10:
						{
						_localctx = new AndOrOpContext(new ExpressionContext(_parentctx, _parentState));
						((AndOrOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(114);
						if (!(precpred(_ctx, 3))) throw new FailedPredicateException(this, "precpred(_ctx, 3)");
						setState(115); ((AndOrOpContext)_localctx).op = match(T__10);
						setState(116); ((AndOrOpContext)_localctx).right = expression(4);
						}
						break;

					case 11:
						{
						_localctx = new TernaryOpContext(new ExpressionContext(_parentctx, _parentState));
						((TernaryOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(117);
						if (!(precpred(_ctx, 2))) throw new FailedPredicateException(this, "precpred(_ctx, 2)");
						setState(118); ((TernaryOpContext)_localctx).op = match(T__26);
						setState(119); ((TernaryOpContext)_localctx).iftrue = expression(0);
						setState(120); match(T__31);
						setState(121); ((TernaryOpContext)_localctx).iffalse = expression(3);
						}
						break;

					case 12:
						{
						_localctx = new QuestionQuestionOpContext(new ExpressionContext(_parentctx, _parentState));
						((QuestionQuestionOpContext)_localctx).left = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(123);
						if (!(precpred(_ctx, 1))) throw new FailedPredicateException(this, "precpred(_ctx, 1)");
						setState(124); ((QuestionQuestionOpContext)_localctx).op = match(T__8);
						setState(125); ((QuestionQuestionOpContext)_localctx).right = expression(2);
						}
						break;

					case 13:
						{
						_localctx = new DotOpContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(126);
						if (!(precpred(_ctx, 20))) throw new FailedPredicateException(this, "precpred(_ctx, 20)");
						setState(127); match(T__15);
						setState(128); match(Identifier);
						}
						break;

					case 14:
						{
						_localctx = new ExplicitGenericInvocationOpContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(129);
						if (!(precpred(_ctx, 19))) throw new FailedPredicateException(this, "precpred(_ctx, 19)");
						setState(130); match(T__15);
						setState(131); explicitGenericInvocation();
						}
						break;

					case 15:
						{
						_localctx = new BracketOpContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(132);
						if (!(precpred(_ctx, 18))) throw new FailedPredicateException(this, "precpred(_ctx, 18)");
						setState(133); match(T__34);
						setState(134); expression(0);
						setState(135); match(T__40);
						}
						break;

					case 16:
						{
						_localctx = new MethodInvocationContext(new ExpressionContext(_parentctx, _parentState));
						((MethodInvocationContext)_localctx).target = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(137);
						if (!(precpred(_ctx, 17))) throw new FailedPredicateException(this, "precpred(_ctx, 17)");
						setState(138); match(T__15);
						setState(139); ((MethodInvocationContext)_localctx).methodName = match(Identifier);
						setState(140); match(T__32);
						setState(142);
						_la = _input.LA(1);
						if ((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << T__42) | (1L << T__39) | (1L << T__33) | (1L << T__32) | (1L << T__30) | (1L << T__29) | (1L << T__24) | (1L << T__23) | (1L << T__22) | (1L << T__21) | (1L << T__19) | (1L << T__16) | (1L << T__13) | (1L << T__5) | (1L << T__0) | (1L << IntegerLiteral) | (1L << FloatingPointLiteral) | (1L << BooleanLiteral) | (1L << CharacterLiteral) | (1L << SingleQuoteString) | (1L << DoubleQuoteString) | (1L << NullLiteral) | (1L << Identifier) | (1L << ResourceReference))) != 0)) {
							{
							setState(141); ((MethodInvocationContext)_localctx).args = expressionList();
							}
						}

						setState(144); match(T__14);
						}
						break;

					case 17:
						{
						_localctx = new InstanceOfOpContext(new ExpressionContext(_parentctx, _parentState));
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(145);
						if (!(precpred(_ctx, 9))) throw new FailedPredicateException(this, "precpred(_ctx, 9)");
						setState(146); match(T__1);
						setState(147); type();
						}
						break;
					}
					} 
				}
				setState(152);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,7,_ctx);
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
		enterRule(_localctx, 10, RULE_classExtraction);
		try {
			setState(160);
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
				setState(153); type();
				setState(154); match(T__15);
				setState(155); match(T__3);
				}
				break;
			case T__24:
				enterOuterAlt(_localctx, 2);
				{
				setState(157); match(T__24);
				setState(158); match(T__15);
				setState(159); match(T__3);
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
		enterRule(_localctx, 12, RULE_expressionList);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(162); expression(0);
			setState(167);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==T__36) {
				{
				{
				setState(163); match(T__36);
				setState(164); expression(0);
				}
				}
				setState(169);
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
		enterRule(_localctx, 14, RULE_literal);
		try {
			setState(172);
			switch (_input.LA(1)) {
			case IntegerLiteral:
			case FloatingPointLiteral:
			case BooleanLiteral:
			case CharacterLiteral:
			case NullLiteral:
				enterOuterAlt(_localctx, 1);
				{
				setState(170); javaLiteral();
				}
				break;
			case SingleQuoteString:
			case DoubleQuoteString:
				enterOuterAlt(_localctx, 2);
				{
				setState(171); stringLiteral();
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
		enterRule(_localctx, 16, RULE_identifier);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(174); match(Identifier);
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
		enterRule(_localctx, 18, RULE_javaLiteral);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(176);
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
		enterRule(_localctx, 20, RULE_stringLiteral);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(178);
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
		enterRule(_localctx, 22, RULE_explicitGenericInvocation);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(180); typeArguments();
			setState(181); explicitGenericInvocationSuffix();
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
		enterRule(_localctx, 24, RULE_typeArguments);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(183); match(T__30);
			setState(184); type();
			setState(189);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==T__36) {
				{
				{
				setState(185); match(T__36);
				setState(186); type();
				}
				}
				setState(191);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(192); match(T__9);
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
		enterRule(_localctx, 26, RULE_type);
		try {
			int _alt;
			setState(210);
			switch (_input.LA(1)) {
			case Identifier:
				enterOuterAlt(_localctx, 1);
				{
				setState(194); classOrInterfaceType();
				setState(199);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,12,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(195); match(T__34);
						setState(196); match(T__40);
						}
						} 
					}
					setState(201);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,12,_ctx);
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
				setState(202); primitiveType();
				setState(207);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,13,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(203); match(T__34);
						setState(204); match(T__40);
						}
						} 
					}
					setState(209);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,13,_ctx);
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
		enterRule(_localctx, 28, RULE_explicitGenericInvocationSuffix);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(212); match(Identifier);
			setState(213); arguments();
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
		enterRule(_localctx, 30, RULE_arguments);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(215); match(T__32);
			setState(217);
			_la = _input.LA(1);
			if ((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << T__42) | (1L << T__39) | (1L << T__33) | (1L << T__32) | (1L << T__30) | (1L << T__29) | (1L << T__24) | (1L << T__23) | (1L << T__22) | (1L << T__21) | (1L << T__19) | (1L << T__16) | (1L << T__13) | (1L << T__5) | (1L << T__0) | (1L << IntegerLiteral) | (1L << FloatingPointLiteral) | (1L << BooleanLiteral) | (1L << CharacterLiteral) | (1L << SingleQuoteString) | (1L << DoubleQuoteString) | (1L << NullLiteral) | (1L << Identifier) | (1L << ResourceReference))) != 0)) {
				{
				setState(216); expressionList();
				}
			}

			setState(219); match(T__14);
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
		enterRule(_localctx, 32, RULE_classOrInterfaceType);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(221); identifier();
			setState(223);
			switch ( getInterpreter().adaptivePredict(_input,16,_ctx) ) {
			case 1:
				{
				setState(222); typeArguments();
				}
				break;
			}
			setState(232);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,18,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(225); match(T__15);
					setState(226); match(Identifier);
					setState(228);
					switch ( getInterpreter().adaptivePredict(_input,17,_ctx) ) {
					case 1:
						{
						setState(227); typeArguments();
						}
						break;
					}
					}
					} 
				}
				setState(234);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,18,_ctx);
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
		enterRule(_localctx, 34, RULE_primitiveType);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(235);
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
		case 3: return constantExpression_sempred((ConstantExpressionContext)_localctx, predIndex);

		case 4: return expression_sempred((ExpressionContext)_localctx, predIndex);
		}
		return true;
	}
	private boolean expression_sempred(ExpressionContext _localctx, int predIndex) {
		switch (predIndex) {
		case 1: return precpred(_ctx, 13);

		case 2: return precpred(_ctx, 12);

		case 3: return precpred(_ctx, 11);

		case 4: return precpred(_ctx, 10);

		case 5: return precpred(_ctx, 8);

		case 6: return precpred(_ctx, 7);

		case 7: return precpred(_ctx, 6);

		case 8: return precpred(_ctx, 5);

		case 9: return precpred(_ctx, 4);

		case 10: return precpred(_ctx, 3);

		case 11: return precpred(_ctx, 2);

		case 12: return precpred(_ctx, 1);

		case 13: return precpred(_ctx, 20);

		case 14: return precpred(_ctx, 19);

		case 15: return precpred(_ctx, 18);

		case 17: return precpred(_ctx, 9);

		case 16: return precpred(_ctx, 17);
		}
		return true;
	}
	private boolean constantExpression_sempred(ConstantExpressionContext _localctx, int predIndex) {
		switch (predIndex) {
		case 0: return precpred(_ctx, 2);
		}
		return true;
	}

	public static final String _serializedATN =
		"\3\uaf6f\u8320\u479d\ub75c\u4880\u1605\u191c\uab37\39\u00f0\4\2\t\2\4"+
		"\3\t\3\4\4\t\4\4\5\t\5\4\6\t\6\4\7\t\7\4\b\t\b\4\t\t\t\4\n\t\n\4\13\t"+
		"\13\4\f\t\f\4\r\t\r\4\16\t\16\4\17\t\17\4\20\t\20\4\21\t\21\4\22\t\22"+
		"\4\23\t\23\3\2\3\2\5\2)\n\2\3\3\3\3\3\3\3\3\3\3\3\4\3\4\3\4\5\4\63\n\4"+
		"\3\5\3\5\3\5\3\5\3\5\3\5\7\5;\n\5\f\5\16\5>\13\5\3\6\3\6\3\6\3\6\3\6\3"+
		"\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\5\6"+
		"V\n\6\5\6X\n\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6"+
		"\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3"+
		"\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6\3\6"+
		"\3\6\3\6\3\6\3\6\3\6\3\6\5\6\u0091\n\6\3\6\3\6\3\6\3\6\7\6\u0097\n\6\f"+
		"\6\16\6\u009a\13\6\3\7\3\7\3\7\3\7\3\7\3\7\3\7\5\7\u00a3\n\7\3\b\3\b\3"+
		"\b\7\b\u00a8\n\b\f\b\16\b\u00ab\13\b\3\t\3\t\5\t\u00af\n\t\3\n\3\n\3\13"+
		"\3\13\3\f\3\f\3\r\3\r\3\r\3\16\3\16\3\16\3\16\7\16\u00be\n\16\f\16\16"+
		"\16\u00c1\13\16\3\16\3\16\3\17\3\17\3\17\7\17\u00c8\n\17\f\17\16\17\u00cb"+
		"\13\17\3\17\3\17\3\17\7\17\u00d0\n\17\f\17\16\17\u00d3\13\17\5\17\u00d5"+
		"\n\17\3\20\3\20\3\20\3\21\3\21\5\21\u00dc\n\21\3\21\3\21\3\22\3\22\5\22"+
		"\u00e2\n\22\3\22\3\22\3\22\5\22\u00e7\n\22\7\22\u00e9\n\22\f\22\16\22"+
		"\u00ec\13\22\3\23\3\23\3\23\2\2\4\b\n\24\2\2\4\2\6\2\b\2\n\2\f\2\16\2"+
		"\20\2\22\2\24\2\26\2\30\2\32\2\34\2\36\2 \2\"\2$\2\2\13\4\2\f\f  \4\2"+
		"((--\5\2\n\n\33\33&&\5\2\4\4\24\24\31\31\6\2\17\17\22\22$$))\4\2\21\21"+
		"\'\'\4\2/\62\65\65\3\2\63\64\b\2\3\3\6\6\20\20\26\30\32\32\35\35\u0107"+
		"\2&\3\2\2\2\4*\3\2\2\2\6\62\3\2\2\2\b\64\3\2\2\2\nW\3\2\2\2\f\u00a2\3"+
		"\2\2\2\16\u00a4\3\2\2\2\20\u00ae\3\2\2\2\22\u00b0\3\2\2\2\24\u00b2\3\2"+
		"\2\2\26\u00b4\3\2\2\2\30\u00b6\3\2\2\2\32\u00b9\3\2\2\2\34\u00d4\3\2\2"+
		"\2\36\u00d6\3\2\2\2 \u00d9\3\2\2\2\"\u00df\3\2\2\2$\u00ed\3\2\2\2&(\5"+
		"\n\6\2\')\5\4\3\2(\'\3\2\2\2()\3\2\2\2)\3\3\2\2\2*+\7\t\2\2+,\7\b\2\2"+
		",-\7!\2\2-.\5\6\4\2.\5\3\2\2\2/\63\5\20\t\2\60\63\78\2\2\61\63\5\b\5\2"+
		"\62/\3\2\2\2\62\60\3\2\2\2\62\61\3\2\2\2\63\7\3\2\2\2\64\65\b\5\1\2\65"+
		"\66\5\22\n\2\66<\3\2\2\2\678\f\4\2\289\7\36\2\29;\7\66\2\2:\67\3\2\2\2"+
		";>\3\2\2\2<:\3\2\2\2<=\3\2\2\2=\t\3\2\2\2><\3\2\2\2?@\b\6\1\2@A\7\r\2"+
		"\2AB\5\34\17\2BC\7\37\2\2CD\5\n\6\22DX\3\2\2\2EF\t\2\2\2FX\5\n\6\21GH"+
		"\t\3\2\2HX\5\n\6\20IJ\7\r\2\2JK\5\n\6\2KL\7\37\2\2LX\3\2\2\2MX\5\20\t"+
		"\2NX\5\22\n\2OX\5\f\7\2PX\78\2\2QU\5\32\16\2RV\5\36\20\2ST\7.\2\2TV\5"+
		" \21\2UR\3\2\2\2US\3\2\2\2VX\3\2\2\2W?\3\2\2\2WE\3\2\2\2WG\3\2\2\2WI\3"+
		"\2\2\2WM\3\2\2\2WN\3\2\2\2WO\3\2\2\2WP\3\2\2\2WQ\3\2\2\2X\u0098\3\2\2"+
		"\2YZ\f\17\2\2Z[\t\4\2\2[\u0097\5\n\6\20\\]\f\16\2\2]^\t\2\2\2^\u0097\5"+
		"\n\6\17_`\f\r\2\2`a\t\5\2\2a\u0097\5\n\6\16bc\f\f\2\2cd\t\6\2\2d\u0097"+
		"\5\n\6\ref\f\n\2\2fg\t\7\2\2g\u0097\5\n\6\13hi\f\t\2\2ij\7\7\2\2j\u0097"+
		"\5\n\6\nkl\f\b\2\2lm\7\34\2\2m\u0097\5\n\6\tno\f\7\2\2op\7+\2\2p\u0097"+
		"\5\n\6\bqr\f\6\2\2rs\7\"\2\2s\u0097\5\n\6\7tu\f\5\2\2uv\7#\2\2v\u0097"+
		"\5\n\6\6wx\f\4\2\2xy\7\23\2\2yz\5\n\6\2z{\7\16\2\2{|\5\n\6\5|\u0097\3"+
		"\2\2\2}~\f\3\2\2~\177\7%\2\2\177\u0097\5\n\6\4\u0080\u0081\f\26\2\2\u0081"+
		"\u0082\7\36\2\2\u0082\u0097\7\66\2\2\u0083\u0084\f\25\2\2\u0084\u0085"+
		"\7\36\2\2\u0085\u0097\5\30\r\2\u0086\u0087\f\24\2\2\u0087\u0088\7\13\2"+
		"\2\u0088\u0089\5\n\6\2\u0089\u008a\7\5\2\2\u008a\u0097\3\2\2\2\u008b\u008c"+
		"\f\23\2\2\u008c\u008d\7\36\2\2\u008d\u008e\7\66\2\2\u008e\u0090\7\r\2"+
		"\2\u008f\u0091\5\16\b\2\u0090\u008f\3\2\2\2\u0090\u0091\3\2\2\2\u0091"+
		"\u0092\3\2\2\2\u0092\u0097\7\37\2\2\u0093\u0094\f\13\2\2\u0094\u0095\7"+
		",\2\2\u0095\u0097\5\34\17\2\u0096Y\3\2\2\2\u0096\\\3\2\2\2\u0096_\3\2"+
		"\2\2\u0096b\3\2\2\2\u0096e\3\2\2\2\u0096h\3\2\2\2\u0096k\3\2\2\2\u0096"+
		"n\3\2\2\2\u0096q\3\2\2\2\u0096t\3\2\2\2\u0096w\3\2\2\2\u0096}\3\2\2\2"+
		"\u0096\u0080\3\2\2\2\u0096\u0083\3\2\2\2\u0096\u0086\3\2\2\2\u0096\u008b"+
		"\3\2\2\2\u0096\u0093\3\2\2\2\u0097\u009a\3\2\2\2\u0098\u0096\3\2\2\2\u0098"+
		"\u0099\3\2\2\2\u0099\13\3\2\2\2\u009a\u0098\3\2\2\2\u009b\u009c\5\34\17"+
		"\2\u009c\u009d\7\36\2\2\u009d\u009e\7*\2\2\u009e\u00a3\3\2\2\2\u009f\u00a0"+
		"\7\25\2\2\u00a0\u00a1\7\36\2\2\u00a1\u00a3\7*\2\2\u00a2\u009b\3\2\2\2"+
		"\u00a2\u009f\3\2\2\2\u00a3\r\3\2\2\2\u00a4\u00a9\5\n\6\2\u00a5\u00a6\7"+
		"\t\2\2\u00a6\u00a8\5\n\6\2\u00a7\u00a5\3\2\2\2\u00a8\u00ab\3\2\2\2\u00a9"+
		"\u00a7\3\2\2\2\u00a9\u00aa\3\2\2\2\u00aa\17\3\2\2\2\u00ab\u00a9\3\2\2"+
		"\2\u00ac\u00af\5\24\13\2\u00ad\u00af\5\26\f\2\u00ae\u00ac\3\2\2\2\u00ae"+
		"\u00ad\3\2\2\2\u00af\21\3\2\2\2\u00b0\u00b1\7\66\2\2\u00b1\23\3\2\2\2"+
		"\u00b2\u00b3\t\b\2\2\u00b3\25\3\2\2\2\u00b4\u00b5\t\t\2\2\u00b5\27\3\2"+
		"\2\2\u00b6\u00b7\5\32\16\2\u00b7\u00b8\5\36\20\2\u00b8\31\3\2\2\2\u00b9"+
		"\u00ba\7\17\2\2\u00ba\u00bf\5\34\17\2\u00bb\u00bc\7\t\2\2\u00bc\u00be"+
		"\5\34\17\2\u00bd\u00bb\3\2\2\2\u00be\u00c1\3\2\2\2\u00bf\u00bd\3\2\2\2"+
		"\u00bf\u00c0\3\2\2\2\u00c0\u00c2\3\2\2\2\u00c1\u00bf\3\2\2\2\u00c2\u00c3"+
		"\7$\2\2\u00c3\33\3\2\2\2\u00c4\u00c9\5\"\22\2\u00c5\u00c6\7\13\2\2\u00c6"+
		"\u00c8\7\5\2\2\u00c7\u00c5\3\2\2\2\u00c8\u00cb\3\2\2\2\u00c9\u00c7\3\2"+
		"\2\2\u00c9\u00ca\3\2\2\2\u00ca\u00d5\3\2\2\2\u00cb\u00c9\3\2\2\2\u00cc"+
		"\u00d1\5$\23\2\u00cd\u00ce\7\13\2\2\u00ce\u00d0\7\5\2\2\u00cf\u00cd\3"+
		"\2\2\2\u00d0\u00d3\3\2\2\2\u00d1\u00cf\3\2\2\2\u00d1\u00d2\3\2\2\2\u00d2"+
		"\u00d5\3\2\2\2\u00d3\u00d1\3\2\2\2\u00d4\u00c4\3\2\2\2\u00d4\u00cc\3\2"+
		"\2\2\u00d5\35\3\2\2\2\u00d6\u00d7\7\66\2\2\u00d7\u00d8\5 \21\2\u00d8\37"+
		"\3\2\2\2\u00d9\u00db\7\r\2\2\u00da\u00dc\5\16\b\2\u00db\u00da\3\2\2\2"+
		"\u00db\u00dc\3\2\2\2\u00dc\u00dd\3\2\2\2\u00dd\u00de\7\37\2\2\u00de!\3"+
		"\2\2\2\u00df\u00e1\5\22\n\2\u00e0\u00e2\5\32\16\2\u00e1\u00e0\3\2\2\2"+
		"\u00e1\u00e2\3\2\2\2\u00e2\u00ea\3\2\2\2\u00e3\u00e4\7\36\2\2\u00e4\u00e6"+
		"\7\66\2\2\u00e5\u00e7\5\32\16\2\u00e6\u00e5\3\2\2\2\u00e6\u00e7\3\2\2"+
		"\2\u00e7\u00e9\3\2\2\2\u00e8\u00e3\3\2\2\2\u00e9\u00ec\3\2\2\2\u00ea\u00e8"+
		"\3\2\2\2\u00ea\u00eb\3\2\2\2\u00eb#\3\2\2\2\u00ec\u00ea\3\2\2\2\u00ed"+
		"\u00ee\t\n\2\2\u00ee%\3\2\2\2\25(\62<UW\u0090\u0096\u0098\u00a2\u00a9"+
		"\u00ae\u00bf\u00c9\u00d1\u00d4\u00db\u00e1\u00e6\u00ea";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
	}
}