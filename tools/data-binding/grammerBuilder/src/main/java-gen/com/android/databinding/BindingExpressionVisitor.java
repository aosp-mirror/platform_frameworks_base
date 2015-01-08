// Generated from BindingExpression.g4 by ANTLR 4.4
package com.android.databinding;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.tree.ParseTreeVisitor;

/**
 * This interface defines a complete generic visitor for a parse tree produced
 * by {@link BindingExpressionParser}.
 *
 * @param <Result> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public interface BindingExpressionVisitor<Result> extends ParseTreeVisitor<Result> {
	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitExpression(@NotNull BindingExpressionParser.ExpressionContext ctx);

	/**
	 * Visit a parse tree produced by the {@code BracketOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitBracketOp(@NotNull BindingExpressionParser.BracketOpContext ctx);

	/**
	 * Visit a parse tree produced by the {@code CastOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitCastOp(@NotNull BindingExpressionParser.CastOpContext ctx);

	/**
	 * Visit a parse tree produced by the {@code UnaryOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitUnaryOp(@NotNull BindingExpressionParser.UnaryOpContext ctx);

	/**
	 * Visit a parse tree produced by the {@code MethodInvocation}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitMethodInvocation(@NotNull BindingExpressionParser.MethodInvocationContext ctx);

	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#expressionList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitExpressionList(@NotNull BindingExpressionParser.ExpressionListContext ctx);

	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#classOrInterfaceType}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitClassOrInterfaceType(@NotNull BindingExpressionParser.ClassOrInterfaceTypeContext ctx);

	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#stringLiteral}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitStringLiteral(@NotNull BindingExpressionParser.StringLiteralContext ctx);

	/**
	 * Visit a parse tree produced by the {@code Primary}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitPrimary(@NotNull BindingExpressionParser.PrimaryContext ctx);

	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#type}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitType(@NotNull BindingExpressionParser.TypeContext ctx);

	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#bindingSyntax}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitBindingSyntax(@NotNull BindingExpressionParser.BindingSyntaxContext ctx);

	/**
	 * Visit a parse tree produced by the {@code TernaryOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitTernaryOp(@NotNull BindingExpressionParser.TernaryOpContext ctx);

	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#constantValue}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitConstantValue(@NotNull BindingExpressionParser.ConstantValueContext ctx);

	/**
	 * Visit a parse tree produced by the {@code DotOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitDotOp(@NotNull BindingExpressionParser.DotOpContext ctx);

	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#defaults}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitDefaults(@NotNull BindingExpressionParser.DefaultsContext ctx);

	/**
	 * Visit a parse tree produced by the {@code InstanceOfOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitInstanceOfOp(@NotNull BindingExpressionParser.InstanceOfOpContext ctx);

	/**
	 * Visit a parse tree produced by the {@code BinaryOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitBinaryOp(@NotNull BindingExpressionParser.BinaryOpContext ctx);

	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#explicitGenericInvocation}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitExplicitGenericInvocation(@NotNull BindingExpressionParser.ExplicitGenericInvocationContext ctx);

	/**
	 * Visit a parse tree produced by the {@code Resource}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitResource(@NotNull BindingExpressionParser.ResourceContext ctx);

	/**
	 * Visit a parse tree produced by the {@code ExplicitGenericInvocationOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitExplicitGenericInvocationOp(@NotNull BindingExpressionParser.ExplicitGenericInvocationOpContext ctx);

	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#typeArguments}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitTypeArguments(@NotNull BindingExpressionParser.TypeArgumentsContext ctx);

	/**
	 * Visit a parse tree produced by the {@code Grouping}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitGrouping(@NotNull BindingExpressionParser.GroupingContext ctx);

	/**
	 * Visit a parse tree produced by the {@code GenericCall}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitGenericCall(@NotNull BindingExpressionParser.GenericCallContext ctx);

	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#classExtraction}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitClassExtraction(@NotNull BindingExpressionParser.ClassExtractionContext ctx);

	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#arguments}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitArguments(@NotNull BindingExpressionParser.ArgumentsContext ctx);

	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#primitiveType}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitPrimitiveType(@NotNull BindingExpressionParser.PrimitiveTypeContext ctx);

	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#constantExpression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitConstantExpression(@NotNull BindingExpressionParser.ConstantExpressionContext ctx);

	/**
	 * Visit a parse tree produced by the {@code QuestionQuestionOp}
	 * labeled alternative in {@link BindingExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitQuestionQuestionOp(@NotNull BindingExpressionParser.QuestionQuestionOpContext ctx);

	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#javaLiteral}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitJavaLiteral(@NotNull BindingExpressionParser.JavaLiteralContext ctx);

	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#explicitGenericInvocationSuffix}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitExplicitGenericInvocationSuffix(@NotNull BindingExpressionParser.ExplicitGenericInvocationSuffixContext ctx);

	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#identifier}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitIdentifier(@NotNull BindingExpressionParser.IdentifierContext ctx);

	/**
	 * Visit a parse tree produced by {@link BindingExpressionParser#literal}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitLiteral(@NotNull BindingExpressionParser.LiteralContext ctx);
}