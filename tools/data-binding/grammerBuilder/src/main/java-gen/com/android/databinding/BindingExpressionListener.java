// Generated from BindingExpression.g4 by ANTLR 4.4
package com.android.databinding;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.tree.ParseTreeListener;

/**
 * This interface defines a complete listener for a parse tree produced by
 * {@link BindingExpressionParser}.
 */
public interface BindingExpressionListener extends ParseTreeListener {
	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterExpression(@NotNull BindingExpressionParser.ExpressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitExpression(@NotNull BindingExpressionParser.ExpressionContext ctx);

	/**
	 * Enter a parse tree produced by the {@code BracketOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterBracketOp(@NotNull BindingExpressionParser.BracketOpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code BracketOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitBracketOp(@NotNull BindingExpressionParser.BracketOpContext ctx);

	/**
	 * Enter a parse tree produced by the {@code UnaryOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterUnaryOp(@NotNull BindingExpressionParser.UnaryOpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code UnaryOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitUnaryOp(@NotNull BindingExpressionParser.UnaryOpContext ctx);

	/**
	 * Enter a parse tree produced by the {@code CastOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterCastOp(@NotNull BindingExpressionParser.CastOpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code CastOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitCastOp(@NotNull BindingExpressionParser.CastOpContext ctx);

	/**
	 * Enter a parse tree produced by the {@code AndOrOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterAndOrOp(@NotNull BindingExpressionParser.AndOrOpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code AndOrOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitAndOrOp(@NotNull BindingExpressionParser.AndOrOpContext ctx);

	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#expressionList}.
	 * @param ctx the parse tree
	 */
	void enterExpressionList(@NotNull BindingExpressionParser.ExpressionListContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#expressionList}.
	 * @param ctx the parse tree
	 */
	void exitExpressionList(@NotNull BindingExpressionParser.ExpressionListContext ctx);

	/**
	 * Enter a parse tree produced by the {@code MethodInvocation}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterMethodInvocation(@NotNull BindingExpressionParser.MethodInvocationContext ctx);
	/**
	 * Exit a parse tree produced by the {@code MethodInvocation}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitMethodInvocation(@NotNull BindingExpressionParser.MethodInvocationContext ctx);

	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#classOrInterfaceType}.
	 * @param ctx the parse tree
	 */
	void enterClassOrInterfaceType(@NotNull BindingExpressionParser.ClassOrInterfaceTypeContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#classOrInterfaceType}.
	 * @param ctx the parse tree
	 */
	void exitClassOrInterfaceType(@NotNull BindingExpressionParser.ClassOrInterfaceTypeContext ctx);

	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#stringLiteral}.
	 * @param ctx the parse tree
	 */
	void enterStringLiteral(@NotNull BindingExpressionParser.StringLiteralContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#stringLiteral}.
	 * @param ctx the parse tree
	 */
	void exitStringLiteral(@NotNull BindingExpressionParser.StringLiteralContext ctx);

	/**
	 * Enter a parse tree produced by the {@code Primary}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterPrimary(@NotNull BindingExpressionParser.PrimaryContext ctx);
	/**
	 * Exit a parse tree produced by the {@code Primary}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitPrimary(@NotNull BindingExpressionParser.PrimaryContext ctx);

	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#type}.
	 * @param ctx the parse tree
	 */
	void enterType(@NotNull BindingExpressionParser.TypeContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#type}.
	 * @param ctx the parse tree
	 */
	void exitType(@NotNull BindingExpressionParser.TypeContext ctx);

	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#bindingSyntax}.
	 * @param ctx the parse tree
	 */
	void enterBindingSyntax(@NotNull BindingExpressionParser.BindingSyntaxContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#bindingSyntax}.
	 * @param ctx the parse tree
	 */
	void exitBindingSyntax(@NotNull BindingExpressionParser.BindingSyntaxContext ctx);

	/**
	 * Enter a parse tree produced by the {@code ComparisonOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterComparisonOp(@NotNull BindingExpressionParser.ComparisonOpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ComparisonOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitComparisonOp(@NotNull BindingExpressionParser.ComparisonOpContext ctx);

	/**
	 * Enter a parse tree produced by the {@code TernaryOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterTernaryOp(@NotNull BindingExpressionParser.TernaryOpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code TernaryOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitTernaryOp(@NotNull BindingExpressionParser.TernaryOpContext ctx);

	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#constantValue}.
	 * @param ctx the parse tree
	 */
	void enterConstantValue(@NotNull BindingExpressionParser.ConstantValueContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#constantValue}.
	 * @param ctx the parse tree
	 */
	void exitConstantValue(@NotNull BindingExpressionParser.ConstantValueContext ctx);

	/**
	 * Enter a parse tree produced by the {@code DotOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterDotOp(@NotNull BindingExpressionParser.DotOpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code DotOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitDotOp(@NotNull BindingExpressionParser.DotOpContext ctx);

	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#defaults}.
	 * @param ctx the parse tree
	 */
	void enterDefaults(@NotNull BindingExpressionParser.DefaultsContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#defaults}.
	 * @param ctx the parse tree
	 */
	void exitDefaults(@NotNull BindingExpressionParser.DefaultsContext ctx);

	/**
	 * Enter a parse tree produced by the {@code BitShiftOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterBitShiftOp(@NotNull BindingExpressionParser.BitShiftOpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code BitShiftOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitBitShiftOp(@NotNull BindingExpressionParser.BitShiftOpContext ctx);

	/**
	 * Enter a parse tree produced by the {@code InstanceOfOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterInstanceOfOp(@NotNull BindingExpressionParser.InstanceOfOpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code InstanceOfOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitInstanceOfOp(@NotNull BindingExpressionParser.InstanceOfOpContext ctx);

	/**
	 * Enter a parse tree produced by the {@code BinaryOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterBinaryOp(@NotNull BindingExpressionParser.BinaryOpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code BinaryOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitBinaryOp(@NotNull BindingExpressionParser.BinaryOpContext ctx);

	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#explicitGenericInvocation}.
	 * @param ctx the parse tree
	 */
	void enterExplicitGenericInvocation(@NotNull BindingExpressionParser.ExplicitGenericInvocationContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#explicitGenericInvocation}.
	 * @param ctx the parse tree
	 */
	void exitExplicitGenericInvocation(@NotNull BindingExpressionParser.ExplicitGenericInvocationContext ctx);

	/**
	 * Enter a parse tree produced by the {@code Resource}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterResource(@NotNull BindingExpressionParser.ResourceContext ctx);
	/**
	 * Exit a parse tree produced by the {@code Resource}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitResource(@NotNull BindingExpressionParser.ResourceContext ctx);

	/**
	 * Enter a parse tree produced by the {@code ExplicitGenericInvocationOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterExplicitGenericInvocationOp(@NotNull BindingExpressionParser.ExplicitGenericInvocationOpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ExplicitGenericInvocationOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitExplicitGenericInvocationOp(@NotNull BindingExpressionParser.ExplicitGenericInvocationOpContext ctx);

	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#typeArguments}.
	 * @param ctx the parse tree
	 */
	void enterTypeArguments(@NotNull BindingExpressionParser.TypeArgumentsContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#typeArguments}.
	 * @param ctx the parse tree
	 */
	void exitTypeArguments(@NotNull BindingExpressionParser.TypeArgumentsContext ctx);

	/**
	 * Enter a parse tree produced by the {@code Grouping}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterGrouping(@NotNull BindingExpressionParser.GroupingContext ctx);
	/**
	 * Exit a parse tree produced by the {@code Grouping}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitGrouping(@NotNull BindingExpressionParser.GroupingContext ctx);

	/**
	 * Enter a parse tree produced by the {@code GenericCall}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterGenericCall(@NotNull BindingExpressionParser.GenericCallContext ctx);
	/**
	 * Exit a parse tree produced by the {@code GenericCall}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitGenericCall(@NotNull BindingExpressionParser.GenericCallContext ctx);

	/**
	 * Enter a parse tree produced by the {@code MathOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterMathOp(@NotNull BindingExpressionParser.MathOpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code MathOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitMathOp(@NotNull BindingExpressionParser.MathOpContext ctx);

	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#classExtraction}.
	 * @param ctx the parse tree
	 */
	void enterClassExtraction(@NotNull BindingExpressionParser.ClassExtractionContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#classExtraction}.
	 * @param ctx the parse tree
	 */
	void exitClassExtraction(@NotNull BindingExpressionParser.ClassExtractionContext ctx);

	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#arguments}.
	 * @param ctx the parse tree
	 */
	void enterArguments(@NotNull BindingExpressionParser.ArgumentsContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#arguments}.
	 * @param ctx the parse tree
	 */
	void exitArguments(@NotNull BindingExpressionParser.ArgumentsContext ctx);

	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#primitiveType}.
	 * @param ctx the parse tree
	 */
	void enterPrimitiveType(@NotNull BindingExpressionParser.PrimitiveTypeContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#primitiveType}.
	 * @param ctx the parse tree
	 */
	void exitPrimitiveType(@NotNull BindingExpressionParser.PrimitiveTypeContext ctx);

	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#constantExpression}.
	 * @param ctx the parse tree
	 */
	void enterConstantExpression(@NotNull BindingExpressionParser.ConstantExpressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#constantExpression}.
	 * @param ctx the parse tree
	 */
	void exitConstantExpression(@NotNull BindingExpressionParser.ConstantExpressionContext ctx);

	/**
	 * Enter a parse tree produced by the {@code QuestionQuestionOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterQuestionQuestionOp(@NotNull BindingExpressionParser.QuestionQuestionOpContext ctx);
	/**
	 * Exit a parse tree produced by the {@code QuestionQuestionOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitQuestionQuestionOp(@NotNull BindingExpressionParser.QuestionQuestionOpContext ctx);

	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#javaLiteral}.
	 * @param ctx the parse tree
	 */
	void enterJavaLiteral(@NotNull BindingExpressionParser.JavaLiteralContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#javaLiteral}.
	 * @param ctx the parse tree
	 */
	void exitJavaLiteral(@NotNull BindingExpressionParser.JavaLiteralContext ctx);

	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#explicitGenericInvocationSuffix}.
	 * @param ctx the parse tree
	 */
	void enterExplicitGenericInvocationSuffix(@NotNull BindingExpressionParser.ExplicitGenericInvocationSuffixContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#explicitGenericInvocationSuffix}.
	 * @param ctx the parse tree
	 */
	void exitExplicitGenericInvocationSuffix(@NotNull BindingExpressionParser.ExplicitGenericInvocationSuffixContext ctx);

	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#identifier}.
	 * @param ctx the parse tree
	 */
	void enterIdentifier(@NotNull BindingExpressionParser.IdentifierContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#identifier}.
	 * @param ctx the parse tree
	 */
	void exitIdentifier(@NotNull BindingExpressionParser.IdentifierContext ctx);

	/**
	 * Enter a parse tree produced by {@link BindingExpressionParser#literal}.
	 * @param ctx the parse tree
	 */
	void enterLiteral(@NotNull BindingExpressionParser.LiteralContext ctx);
	/**
	 * Exit a parse tree produced by {@link BindingExpressionParser#literal}.
	 * @param ctx the parse tree
	 */
	void exitLiteral(@NotNull BindingExpressionParser.LiteralContext ctx);
}