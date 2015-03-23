// Generated from XMLParser.g4 by ANTLR 4.4
package android.databinding.parser;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.tree.ParseTreeVisitor;

/**
 * This interface defines a complete generic visitor for a parse tree produced
 * by {@link XMLParser}.
 *
 * @param <Result> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public interface XMLParserVisitor<Result> extends ParseTreeVisitor<Result> {
	/**
	 * Visit a parse tree produced by {@link XMLParser#content}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitContent(@NotNull XMLParser.ContentContext ctx);

	/**
	 * Visit a parse tree produced by {@link XMLParser#element}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitElement(@NotNull XMLParser.ElementContext ctx);

	/**
	 * Visit a parse tree produced by {@link XMLParser#prolog}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitProlog(@NotNull XMLParser.PrologContext ctx);

	/**
	 * Visit a parse tree produced by {@link XMLParser#document}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitDocument(@NotNull XMLParser.DocumentContext ctx);

	/**
	 * Visit a parse tree produced by {@link XMLParser#attribute}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitAttribute(@NotNull XMLParser.AttributeContext ctx);

	/**
	 * Visit a parse tree produced by {@link XMLParser#chardata}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitChardata(@NotNull XMLParser.ChardataContext ctx);

	/**
	 * Visit a parse tree produced by {@link XMLParser#reference}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitReference(@NotNull XMLParser.ReferenceContext ctx);

	/**
	 * Visit a parse tree produced by {@link XMLParser#misc}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	Result visitMisc(@NotNull XMLParser.MiscContext ctx);
}