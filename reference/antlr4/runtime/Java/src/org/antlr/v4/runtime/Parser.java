/*
 * [The "BSD license"]
 *  Copyright (c) 2012 Terence Parr
 *  Copyright (c) 2012 Sam Harwell
 *  All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions
 *  are met:
 *
 *  1. Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *  2. Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer in the
 *     documentation and/or other materials provided with the distribution.
 *  3. The name of the author may not be used to endorse or promote products
 *     derived from this software without specific prior written permission.
 *
 *  THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 *  IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 *  OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 *  IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 *  INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 *  NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 *  DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 *  THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 *  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 *  THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.antlr.v4.runtime;

import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNDeserializationOptions;
import org.antlr.v4.runtime.atn.ATNDeserializer;
import org.antlr.v4.runtime.atn.ATNSerializer;
import org.antlr.v4.runtime.atn.ATNSimulator;
import org.antlr.v4.runtime.atn.ATNState;
import org.antlr.v4.runtime.atn.AmbiguityInfo;
import org.antlr.v4.runtime.atn.ParseInfo;
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.atn.ProfilingATNSimulator;
import org.antlr.v4.runtime.atn.RuleTransition;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.misc.IntegerStack;
import org.antlr.v4.runtime.misc.IntervalSet;
import org.antlr.v4.runtime.misc.NotNull;
import org.antlr.v4.runtime.misc.Nullable;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.tree.Trees;
import org.antlr.v4.runtime.tree.pattern.ParseTreePattern;
import org.antlr.v4.runtime.tree.pattern.ParseTreePatternMatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** This is all the parsing support code essentially; most of it is error recovery stuff. */
public abstract class Parser extends Recognizer<Token, ParserATNSimulator> {
	public class TraceListener implements ParseTreeListener {
		@Override
		public void enterEveryRule(ParserRuleContext ctx) {
			System.out.println("enter   " + getRuleNames()[ctx.getRuleIndex()] +
							   ", LT(1)=" + _input.LT(1).getText());
		}

		@Override
		public void exitEveryRule(ParserRuleContext ctx) {
			System.out.println("exit    "+getRuleNames()[ctx.getRuleIndex()]+
							   ", LT(1)="+_input.LT(1).getText());
		}

		@Override
		public void visitErrorNode(ErrorNode node) {
		}

		@Override
		public void visitTerminal(TerminalNode node) {
			ParserRuleContext parent = (ParserRuleContext)node.getParent().getRuleContext();
			Token token = node.getSymbol();
			System.out.println("consume "+token+" rule "+
							   getRuleNames()[parent.getRuleIndex()]);
		}
	}

	public static class TrimToSizeListener implements ParseTreeListener {
		public static final TrimToSizeListener INSTANCE = new TrimToSizeListener();

		@Override
		public void visitTerminal(TerminalNode node) {
		}

		@Override
		public void visitErrorNode(ErrorNode node) {
		}

		@Override
		public void enterEveryRule(ParserRuleContext ctx) {
		}

		@Override
		public void exitEveryRule(ParserRuleContext ctx) {
			if (ctx.children instanceof ArrayList) {
				((ArrayList<?>)ctx.children).trimToSize();
			}
		}
	}

	/**
	 * This field maps from the serialized ATN string to the deserialized {@link ATN} with
	 * bypass alternatives.
	 *
	 * @see ATNDeserializationOptions#isGenerateRuleBypassTransitions()
	 */
	private static final Map<String, ATN> bypassAltsAtnCache =
		new WeakHashMap<String, ATN>();

	/**
	 * The error handling strategy for the parser. The default value is a new
	 * instance of {@link DefaultErrorStrategy}.
	 *
	 * @see #getErrorHandler
	 * @see #setErrorHandler
	 */
	@NotNull
	protected ANTLRErrorStrategy _errHandler = new DefaultErrorStrategy();

	/**
	 * The input stream.
	 *
	 * @see #getInputStream
	 * @see #setInputStream
	 */
	protected TokenStream _input;

	protected final IntegerStack _precedenceStack;
	{
		_precedenceStack = new IntegerStack();
		_precedenceStack.push(0);
	}
	/**
	 * The {@link ParserRuleContext} object for the currently executing rule.
	 * This is always non-null during the parsing process.
	 */
	protected ParserRuleContext _ctx;

	/**
	 * Specifies whether or not the parser should construct a parse tree during
	 * the parsing process. The default value is {@code true}.
	 *
	 * @see #getBuildParseTree
	 * @see #setBuildParseTree
	 */
	protected boolean _buildParseTrees = true;


	/**
	 * When {@link #setTrace}{@code (true)} is called, a reference to the
	 * {@link TraceListener} is stored here so it can be easily removed in a
	 * later call to {@link #setTrace}{@code (false)}. The listener itself is
	 * implemented as a parser listener so this field is not directly used by
	 * other parser methods.
	 */
	private TraceListener _tracer;

	/**
	 * The list of {@link ParseTreeListener} listeners registered to receive
	 * events during the parse.
	 *
	 * @see #addParseListener
	 */
	@Nullable
    protected List<ParseTreeListener> _parseListeners;

	/**
	 * The number of syntax errors reported during parsing. This value is
	 * incremented each time {@link #notifyErrorListeners} is called.
	 */
	protected int _syntaxErrors;

	public Parser(TokenStream input) {
		setInputStream(input);
	}

	/** reset the parser's state */
	public void reset() {
		if ( getInputStream()!=null ) getInputStream().seek(0);
		_errHandler.reset(this);
		_ctx = null;
		_syntaxErrors = 0;
		setTrace(false);
		_precedenceStack.clear();
		_precedenceStack.push(0);
		ATNSimulator interpreter = getInterpreter();
		if (interpreter != null) {
			interpreter.reset();
		}
	}

	/**
	 * Match current input symbol against {@code ttype}. If the symbol type
	 * matches, {@link ANTLRErrorStrategy#reportMatch} and {@link #consume} are
	 * called to complete the match process.
	 *
	 * <p>If the symbol type does not match,
	 * {@link ANTLRErrorStrategy#recoverInline} is called on the current error
	 * strategy to attempt recovery. If {@link #getBuildParseTree} is
	 * {@code true} and the token index of the symbol returned by
	 * {@link ANTLRErrorStrategy#recoverInline} is -1, the symbol is added to
	 * the parse tree by calling {@link ParserRuleContext#addErrorNode}.</p>
	 *
	 * @param ttype the token type to match
	 * @return the matched symbol
	 * @throws RecognitionException if the current input symbol did not match
	 * {@code ttype} and the error strategy could not recover from the
	 * mismatched symbol
	 */
	@NotNull
	public Token match(int ttype) throws RecognitionException {
		Token t = getCurrentToken();
		if ( t.getType()==ttype ) {
			_errHandler.reportMatch(this);
			consume();
		}
		else {
			t = _errHandler.recoverInline(this);
			if ( _buildParseTrees && t.getTokenIndex()==-1 ) {
				// we must have conjured up a new token during single token insertion
				// if it's not the current symbol
				_ctx.addErrorNode(t);
			}
		}
		return t;
	}

	/**
	 * Match current input symbol as a wildcard. If the symbol type matches
	 * (i.e. has a value greater than 0), {@link ANTLRErrorStrategy#reportMatch}
	 * and {@link #consume} are called to complete the match process.
	 *
	 * <p>If the symbol type does not match,
	 * {@link ANTLRErrorStrategy#recoverInline} is called on the current error
	 * strategy to attempt recovery. If {@link #getBuildParseTree} is
	 * {@code true} and the token index of the symbol returned by
	 * {@link ANTLRErrorStrategy#recoverInline} is -1, the symbol is added to
	 * the parse tree by calling {@link ParserRuleContext#addErrorNode}.</p>
	 *
	 * @return the matched symbol
	 * @throws RecognitionException if the current input symbol did not match
	 * a wildcard and the error strategy could not recover from the mismatched
	 * symbol
	 */
	@NotNull
	public Token matchWildcard() throws RecognitionException {
		Token t = getCurrentToken();
		if (t.getType() > 0) {
			_errHandler.reportMatch(this);
			consume();
		}
		else {
			t = _errHandler.recoverInline(this);
			if (_buildParseTrees && t.getTokenIndex() == -1) {
				// we must have conjured up a new token during single token insertion
				// if it's not the current symbol
				_ctx.addErrorNode(t);
			}
		}

		return t;
	}

	/**
	 * Track the {@link ParserRuleContext} objects during the parse and hook
	 * them up using the {@link ParserRuleContext#children} list so that it
	 * forms a parse tree. The {@link ParserRuleContext} returned from the start
	 * rule represents the root of the parse tree.
	 *
	 * <p>Note that if we are not building parse trees, rule contexts only point
	 * upwards. When a rule exits, it returns the context but that gets garbage
	 * collected if nobody holds a reference. It points upwards but nobody
	 * points at it.</p>
	 *
	 * <p>When we build parse trees, we are adding all of these contexts to
	 * {@link ParserRuleContext#children} list. Contexts are then not candidates
	 * for garbage collection.</p>
	 * 
	 * @sharpen.property BuildParseTree
	 */
	public void setBuildParseTree(boolean buildParseTrees) {
		this._buildParseTrees = buildParseTrees;
	}

	/**
	 * Gets whether or not a complete parse tree will be constructed while
	 * parsing. This property is {@code true} for a newly constructed parser.
	 *
	 * @return {@code true} if a complete parse tree will be constructed while
	 * parsing, otherwise {@code false}
	 *
	 * @sharpen.property BuildParseTree
	 */
	public boolean getBuildParseTree() {
		return _buildParseTrees;
	}

	/**
	 * Trim the internal lists of the parse tree during parsing to conserve memory.
	 * This property is set to {@code false} by default for a newly constructed parser.
	 *
	 * @sharpen.property TrimParseTree
	 * 
	 * @param trimParseTrees {@code true} to trim the capacity of the {@link ParserRuleContext#children}
	 * list to its size after a rule is parsed.
	 */
	public void setTrimParseTree(boolean trimParseTrees) {
		if (trimParseTrees) {
			if (getTrimParseTree()) {
				return;
			}

			addParseListener(TrimToSizeListener.INSTANCE);
		}
		else {
			removeParseListener(TrimToSizeListener.INSTANCE);
		}
	}

	/**
	 * @return {@code true} if the {@link ParserRuleContext#children} list is trimmed
	 * using the default {@link Parser.TrimToSizeListener} during the parse process.
	 * 
	 * @sharpen.property TrimParseTree
	 */
	public boolean getTrimParseTree() {
		return getParseListeners().contains(TrimToSizeListener.INSTANCE);
	}

	/**
	 * @sharpen.property ParseListeners
	 */
	@NotNull
    public List<ParseTreeListener> getParseListeners() {
		List<ParseTreeListener> listeners = _parseListeners;
		if (listeners == null) {
			return Collections.emptyList();
		}

		return listeners;
	}

	/**
	 * Registers {@code listener} to receive events during the parsing process.
	 *
	 * <p>To support output-preserving grammar transformations (including but not
	 * limited to left-recursion removal, automated left-factoring, and
	 * optimized code generation), calls to listener methods during the parse
	 * may differ substantially from calls made by
	 * {@link ParseTreeWalker#DEFAULT} used after the parse is complete. In
	 * particular, rule entry and exit events may occur in a different order
	 * during the parse than after the parser. In addition, calls to certain
	 * rule entry methods may be omitted.</p>
	 *
	 * <p>With the following specific exceptions, calls to listener events are
	 * <em>deterministic</em>, i.e. for identical input the calls to listener
	 * methods will be the same.</p>
	 *
	 * <ul>
	 * <li>Alterations to the grammar used to generate code may change the
	 * behavior of the listener calls.</li>
	 * <li>Alterations to the command line options passed to ANTLR 4 when
	 * generating the parser may change the behavior of the listener calls.</li>
	 * <li>Changing the version of the ANTLR Tool used to generate the parser
	 * may change the behavior of the listener calls.</li>
	 * </ul>
	 *
	 * @param listener the listener to add
	 *
	 * @throws NullPointerException if {@code} listener is {@code null}
	 */
	public void addParseListener(@NotNull ParseTreeListener listener) {
		if (listener == null) {
			throw new NullPointerException("listener");
		}

		if (_parseListeners == null) {
			_parseListeners = new ArrayList<ParseTreeListener>();
		}

		this._parseListeners.add(listener);
	}

	/**
	 * Remove {@code listener} from the list of parse listeners.
	 *
	 * <p>If {@code listener} is {@code null} or has not been added as a parse
	 * listener, this method does nothing.</p>
	 *
	 * @see #addParseListener
	 *
	 * @param listener the listener to remove
	 */
	public void removeParseListener(ParseTreeListener listener) {
		if (_parseListeners != null) {
			if (_parseListeners.remove(listener)) {
				if (_parseListeners.isEmpty()) {
					_parseListeners = null;
				}
			}
		}
	}

	/**
	 * Remove all parse listeners.
	 *
	 * @see #addParseListener
	 */
	public void removeParseListeners() {
		_parseListeners = null;
	}

	/**
	 * Notify any parse listeners of an enter rule event.
	 *
	 * @see #addParseListener
	 */
	protected void triggerEnterRuleEvent() {
		for (ParseTreeListener listener : _parseListeners) {
			listener.enterEveryRule(_ctx);
			_ctx.enterRule(listener);
		}
	}

	/**
	 * Notify any parse listeners of an exit rule event.
	 *
	 * @see #addParseListener
	 */
	protected void triggerExitRuleEvent() {
		// reverse order walk of listeners
		for (int i = _parseListeners.size()-1; i >= 0; i--) {
			ParseTreeListener listener = _parseListeners.get(i);
			_ctx.exitRule(listener);
			listener.exitEveryRule(_ctx);
		}
	}

	/**
	 * Gets the number of syntax errors reported during parsing. This value is
	 * incremented each time {@link #notifyErrorListeners} is called.
	 *
	 * @see #notifyErrorListeners
	 * 
	 * @sharpen.property NumberOfSyntaxErrors
	 */
	public int getNumberOfSyntaxErrors() {
		return _syntaxErrors;
	}

	/**
	 * @sharpen.property TokenFactory
	 */
	public TokenFactory getTokenFactory() {
		return _input.getTokenSource().getTokenFactory();
	}

	/**
	 * The ATN with bypass alternatives is expensive to create so we create it
	 * lazily.
	 *
	 * @throws UnsupportedOperationException if the current parser does not
	 * implement the {@link #getSerializedATN()} method.
	 */
	@NotNull
	public ATN getATNWithBypassAlts() {
		String serializedAtn = getSerializedATN();
		if (serializedAtn == null) {
			throw new UnsupportedOperationException("The current parser does not support an ATN with bypass alternatives.");
		}

		synchronized (bypassAltsAtnCache) {
			ATN result = bypassAltsAtnCache.get(serializedAtn);
			if (result == null) {
				ATNDeserializationOptions deserializationOptions = new ATNDeserializationOptions();
				deserializationOptions.setGenerateRuleBypassTransitions(true);
				result = new ATNDeserializer(deserializationOptions).deserialize(serializedAtn.toCharArray());
				bypassAltsAtnCache.put(serializedAtn, result);
			}

			return result;
		}
	}

	/**
	 * The preferred method of getting a tree pattern. For example, here's a
	 * sample use:
	 *
	 * <pre>
	 * ParseTree t = parser.expr();
	 * ParseTreePattern p = parser.compileParseTreePattern("&lt;ID&gt;+0", MyParser.RULE_expr);
	 * ParseTreeMatch m = p.match(t);
	 * String id = m.get("ID");
	 * </pre>
	 */
	public ParseTreePattern compileParseTreePattern(String pattern, int patternRuleIndex) {
		if ( getInputStream()!=null ) {
			TokenSource tokenSource = getInputStream().getTokenSource();
			if ( tokenSource instanceof Lexer ) {
				Lexer lexer = (Lexer)tokenSource;
				return compileParseTreePattern(pattern, patternRuleIndex, lexer);
			}
		}
		throw new UnsupportedOperationException("Parser can't discover a lexer to use");
	}

	/**
	 * The same as {@link #compileParseTreePattern(String, int)} but specify a
	 * {@link Lexer} rather than trying to deduce it from this parser.
	 */
	public ParseTreePattern compileParseTreePattern(String pattern, int patternRuleIndex,
													Lexer lexer)
	{
		ParseTreePatternMatcher m = new ParseTreePatternMatcher(lexer, this);
		return m.compile(pattern, patternRuleIndex);
	}

	/**
	 * @sharpen.property ErrorHandler
	 */
	@NotNull
	public ANTLRErrorStrategy getErrorHandler() {
		return _errHandler;
	}

	/**
	 * @sharpen.property ErrorHandler
	 */
	public void setErrorHandler(@NotNull ANTLRErrorStrategy handler) {
		this._errHandler = handler;
	}

	@Override
	public TokenStream getInputStream() {
		return _input;
	}

	/** Set the token stream and reset the parser. */
	public void setInputStream(TokenStream input) {
		this._input = null;
		reset();
		this._input = input;
	}

    /** Match needs to return the current input symbol, which gets put
     *  into the label for the associated token ref; e.g., x=ID.
	 * 
	 * @sharpen.property CurrentToken
	 */
	@NotNull
    public Token getCurrentToken() {
		return _input.LT(1);
	}

	public final void notifyErrorListeners(@NotNull String msg)	{
		notifyErrorListeners(getCurrentToken(), msg, null);
	}

	public void notifyErrorListeners(@NotNull Token offendingToken, @NotNull String msg,
									 @Nullable RecognitionException e)
	{
		_syntaxErrors++;
		int line = -1;
		int charPositionInLine = -1;
		if (offendingToken != null) {
			line = offendingToken.getLine();
			charPositionInLine = offendingToken.getCharPositionInLine();
		}

		ANTLRErrorListener<? super Token> listener = getErrorListenerDispatch();
		listener.syntaxError(this, offendingToken, line, charPositionInLine, msg, e);
	}

	/**
	 * Consume and return the {@linkplain #getCurrentToken current symbol}.
	 *
	 * <p>E.g., given the following input with {@code A} being the current
	 * lookahead symbol, this function moves the cursor to {@code B} and returns
	 * {@code A}.</p>
	 *
	 * <pre>
	 *  A B
	 *  ^
	 * </pre>
	 *
	 * If the parser is not in error recovery mode, the consumed symbol is added
	 * to the parse tree using {@link ParserRuleContext#addChild(Token)}, and
	 * {@link ParseTreeListener#visitTerminal} is called on any parse listeners.
	 * If the parser <em>is</em> in error recovery mode, the consumed symbol is
	 * added to the parse tree using
	 * {@link ParserRuleContext#addErrorNode(Token)}, and
	 * {@link ParseTreeListener#visitErrorNode} is called on any parse
	 * listeners.
	 */
	public Token consume() {
		Token o = getCurrentToken();
		if (o.getType() != EOF) {
			getInputStream().consume();
		}
		boolean hasListener = _parseListeners != null && !_parseListeners.isEmpty();
		if (_buildParseTrees || hasListener) {
			if ( _errHandler.inErrorRecoveryMode(this) ) {
				ErrorNode node = _ctx.addErrorNode(o);
				if (_parseListeners != null) {
					for (ParseTreeListener listener : _parseListeners) {
						listener.visitErrorNode(node);
					}
				}
			}
			else {
				TerminalNode node = _ctx.addChild(o);
				if (_parseListeners != null) {
					for (ParseTreeListener listener : _parseListeners) {
						listener.visitTerminal(node);
					}
				}
			}
		}
		return o;
	}

	protected void addContextToParseTree() {
		ParserRuleContext parent = (ParserRuleContext)_ctx.parent;
		// add current context to parent if we have a parent
		if ( parent!=null )	{
			parent.addChild(_ctx);
		}
	}

	/**
	 * Always called by generated parsers upon entry to a rule. Access field
	 * {@link #_ctx} get the current context.
	 */
	public void enterRule(@NotNull ParserRuleContext localctx, int state, int ruleIndex) {
		setState(state);
		_ctx = localctx;
		_ctx.start = _input.LT(1);
		if (_buildParseTrees) addContextToParseTree();
        if ( _parseListeners != null) triggerEnterRuleEvent();
	}

	public void enterLeftFactoredRule(ParserRuleContext localctx, int state, int ruleIndex) {
		setState(state);
		if (_buildParseTrees) {
			ParserRuleContext factoredContext = (ParserRuleContext)_ctx.getChild(_ctx.getChildCount() - 1);
			_ctx.removeLastChild();
			factoredContext.parent = localctx;
			localctx.addChild(factoredContext);
		}

		_ctx = localctx;
		_ctx.start = _input.LT(1);
		if (_buildParseTrees) {
			addContextToParseTree();
		}

		if (_parseListeners != null) {
			triggerEnterRuleEvent();
		}
	}

    public void exitRule() {
		_ctx.stop = _input.LT(-1);
        // trigger event on _ctx, before it reverts to parent
        if ( _parseListeners != null) triggerExitRuleEvent();
		setState(_ctx.invokingState);
		_ctx = (ParserRuleContext)_ctx.parent;
    }

	public void enterOuterAlt(ParserRuleContext localctx, int altNum) {
		// if we have new localctx, make sure we replace existing ctx
		// that is previous child of parse tree
		if ( _buildParseTrees && _ctx != localctx ) {
			ParserRuleContext parent = (ParserRuleContext)_ctx.parent;
			if ( parent!=null )	{
				parent.removeLastChild();
				parent.addChild(localctx);
			}
		}
		_ctx = localctx;
	}

	/**
	 * Get the precedence level for the top-most precedence rule.
	 *
	 * @return The precedence level for the top-most precedence rule, or -1 if
	 * the parser context is not nested within a precedence rule.
	 *
	 * @sharpen.property Precedence
	 */
	public final int getPrecedence() {
		if (_precedenceStack.isEmpty()) {
			return -1;
		}

		return _precedenceStack.peek();
	}

	/**
	 * @deprecated Use
	 * {@link #enterRecursionRule(ParserRuleContext, int, int, int)} instead.
	 */
	@Deprecated
	public void enterRecursionRule(ParserRuleContext localctx, int ruleIndex) {
		enterRecursionRule(localctx, getATN().ruleToStartState[ruleIndex].stateNumber, ruleIndex, 0);
	}

	public void enterRecursionRule(ParserRuleContext localctx, int state, int ruleIndex, int precedence) {
		setState(state);
		_precedenceStack.push(precedence);
		_ctx = localctx;
		_ctx.start = _input.LT(1);
		if (_parseListeners != null) {
			triggerEnterRuleEvent(); // simulates rule entry for left-recursive rules
		}
	}

	/** Like {@link #enterRule} but for recursive rules.
	 *  Make the current context the child of the incoming localctx.
	 */
	public void pushNewRecursionContext(ParserRuleContext localctx, int state, int ruleIndex) {
		ParserRuleContext previous = _ctx;
		previous.parent = localctx;
		previous.invokingState = state;
		previous.stop = _input.LT(-1);

		_ctx = localctx;
		_ctx.start = previous.start;
		if (_buildParseTrees) {
			_ctx.addChild(previous);
		}

		if ( _parseListeners != null ) {
			triggerEnterRuleEvent(); // simulates rule entry for left-recursive rules
		}
	}

	public void unrollRecursionContexts(ParserRuleContext _parentctx) {
		_precedenceStack.pop();
		_ctx.stop = _input.LT(-1);
		ParserRuleContext retctx = _ctx; // save current ctx (return value)

		// unroll so _ctx is as it was before call to recursive method
		if ( _parseListeners != null ) {
			while ( _ctx != _parentctx ) {
				triggerExitRuleEvent();
				_ctx = (ParserRuleContext)_ctx.parent;
			}
		}
		else {
			_ctx = _parentctx;
		}

		// hook into tree
		retctx.parent = _parentctx;

		if (_buildParseTrees && _parentctx != null) {
			// add return ctx into invoking rule's tree
			_parentctx.addChild(retctx);
		}
	}

	public ParserRuleContext getInvokingContext(int ruleIndex) {
		ParserRuleContext p = _ctx;
		while ( p!=null ) {
			if ( p.getRuleIndex() == ruleIndex ) return p;
			p = (ParserRuleContext)p.parent;
		}
		return null;
	}

	/**
	 * @sharpen.property Context
	 */
	public ParserRuleContext getContext() {
		return _ctx;
	}

	/**
	 * @sharpen.property Context
	 */
	public void setContext(ParserRuleContext ctx) {
		_ctx = ctx;
	}

	@Override
	public boolean precpred(@Nullable RuleContext localctx, int precedence) {
		return precedence >= _precedenceStack.peek();
	}

	@Override
	public ParserErrorListener getErrorListenerDispatch() {
		return new ProxyParserErrorListener(getErrorListeners());
	}

	public boolean inContext(String context) {
		// TODO: useful in parser?
		return false;
	}

	/** Given an AmbiguityInfo object that contains information about an
	 *  ambiguous decision event, return the list of ambiguous parse trees.
	 *  An ambiguity occurs when a specific token sequence can be recognized
	 *  in more than one way by the grammar. These ambiguities are detected only
	 *  at decision points.
	 *
	 *  The list of trees includes the actual interpretation (that for
	 *  the minimum alternative number) and all ambiguous alternatives.
	 *
	 *  This method reuses the same physical input token stream used to
	 *  detect the ambiguity by the original parser in the first place.
	 *  This method resets/seeks within but does not alter originalParser.
	 *  The input position is restored upon exit from this method.
	 *  Parsers using a {@link UnbufferedTokenStream} may not be able to
	 *  perform the necessary save index() / seek(saved_index) operation.
	 *
	 *  The trees are rooted at the node whose start..stop token indices
	 *  include the start and stop indices of this ambiguity event. That is,
	 *  the trees returns will always include the complete ambiguous subphrase
	 *  identified by the ambiguity event.
	 *
	 *  Be aware that this method does NOT notify error or parse listeners as
	 *  it would trigger duplicate or otherwise unwanted events.
	 *
	 *  This uses a temporary ParserATNSimulator and a ParserInterpreter
	 *  so we don't mess up any statistics, event lists, etc...
	 *  The parse tree constructed while identifying/making ambiguityInfo is
	 *  not affected by this method as it creates a new parser interp to
	 *  get the ambiguous interpretations.
	 *
	 *  Nodes in the returned ambig trees are independent of the original parse
	 *  tree (constructed while identifying/creating ambiguityInfo).
	 *
	 *  @param originalParser The parser used to create ambiguityInfo; it
	 *                        is not modified by this routine and can be either
	 *                        a generated or interpreted parser. It's token
	 *                        stream *is* reset/seek()'d.
	 *  @param ambiguityInfo The information about an ambiguous decision event
	 *                       for which you want ambiguous parse trees.
	 *
	 *  @throws RecognitionException Throws upon syntax error while matching
	 *                               ambig input.
	 *
	 *  @since 4.5
	 */
	public static List<ParserRuleContext> getAmbiguousParseTrees(@NotNull Parser originalParser,
																 @NotNull AmbiguityInfo ambiguityInfo,
																 int startRuleIndex)
	{
		List<ParserRuleContext> trees = new ArrayList<ParserRuleContext>();
		int saveTokenInputPosition = originalParser.getInputStream().index();
		try {
			// Create a new parser interpreter to parse the ambiguous subphrase
			ParserInterpreter parser;
			if ( originalParser instanceof ParserInterpreter ) {
				parser = new ParserInterpreter((ParserInterpreter) originalParser);
			}
			else {
				char[] serializedAtn = ATNSerializer.getSerializedAsChars(originalParser.getATN(), Arrays.asList(originalParser.getRuleNames()));
				ATN deserialized = new ATNDeserializer().deserialize(serializedAtn);
				parser = new ParserInterpreter(originalParser.getGrammarFileName(),
											   originalParser.getVocabulary(),
											   Arrays.asList(originalParser.getRuleNames()),
											   deserialized,
											   originalParser.getInputStream());
			}

			// Make sure that we don't get any error messages from using this temporary parser
			parser.removeErrorListeners();
			parser.removeParseListeners();
			parser.getInterpreter().setPredictionMode(PredictionMode.LL_EXACT_AMBIG_DETECTION);

			// get ambig trees
			int alt = ambiguityInfo.getAmbiguousAlternatives().nextSetBit(0);
			while ( alt>=0 ) {
				// re-parse input for all ambiguous alternatives
				// (don't have to do first as it's been parsed, but do again for simplicity
				//  using this temp parser.)
				parser.reset();
				parser.getInputStream().seek(ambiguityInfo.startIndex);
				parser.overrideDecision = ambiguityInfo.decision;
				parser.overrideDecisionInputIndex = ambiguityInfo.startIndex;
				parser.overrideDecisionAlt = alt;
				ParserRuleContext t = parser.parse(startRuleIndex);
				ParserRuleContext ambigSubTree =
					Trees.getRootOfSubtreeEnclosingRegion(t, ambiguityInfo.startIndex,
														  ambiguityInfo.stopIndex);
				trees.add(ambigSubTree);
				alt = ambiguityInfo.getAmbiguousAlternatives().nextSetBit(alt+1);
			}
		}
		finally {
			originalParser.getInputStream().seek(saveTokenInputPosition);
		}

		return trees;
	}

	/**
	 * Checks whether or not {@code symbol} can follow the current state in the
	 * ATN. The behavior of this method is equivalent to the following, but is
	 * implemented such that the complete context-sensitive follow set does not
	 * need to be explicitly constructed.
	 *
	 * <pre>
	 * return getExpectedTokens().contains(symbol);
	 * </pre>
	 *
	 * @param symbol the symbol type to check
	 * @return {@code true} if {@code symbol} can follow the current state in
	 * the ATN, otherwise {@code false}.
	 */
    public boolean isExpectedToken(int symbol) {
//   		return getInterpreter().atn.nextTokens(_ctx);
        ATN atn = getInterpreter().atn;
		ParserRuleContext ctx = _ctx;
        ATNState s = atn.states.get(getState());
        IntervalSet following = atn.nextTokens(s);
        if (following.contains(symbol)) {
            return true;
        }
//        System.out.println("following "+s+"="+following);
        if ( !following.contains(Token.EPSILON) ) return false;

        while ( ctx!=null && ctx.invokingState>=0 && following.contains(Token.EPSILON) ) {
            ATNState invokingState = atn.states.get(ctx.invokingState);
            RuleTransition rt = (RuleTransition)invokingState.transition(0);
            following = atn.nextTokens(rt.followState);
            if (following.contains(symbol)) {
                return true;
            }

            ctx = (ParserRuleContext)ctx.parent;
        }

        if ( following.contains(Token.EPSILON) && symbol == Token.EOF ) {
            return true;
        }

        return false;
    }

	/**
	 * Computes the set of input symbols which could follow the current parser
	 * state and context, as given by {@link #getState} and {@link #getContext},
	 * respectively.
	 *
	 * @see ATN#getExpectedTokens(int, RuleContext)
	 */
	@NotNull
	public IntervalSet getExpectedTokens() {
		return getATN().getExpectedTokens(getState(), getContext());
	}

	@NotNull
    public IntervalSet getExpectedTokensWithinCurrentRule() {
        ATN atn = getInterpreter().atn;
        ATNState s = atn.states.get(getState());
   		return atn.nextTokens(s);
   	}

	/** Get a rule's index (i.e., {@code RULE_ruleName} field) or -1 if not found. */
	public int getRuleIndex(String ruleName) {
		Integer ruleIndex = getRuleIndexMap().get(ruleName);
		if ( ruleIndex!=null ) return ruleIndex;
		return -1;
	}

	/**
	 * @sharpen.property RuleContext
	 */
	public ParserRuleContext getRuleContext() { return _ctx; }

	/** Return List&lt;String&gt; of the rule names in your parser instance
	 *  leading up to a call to the current rule.  You could override if
	 *  you want more details such as the file/line info of where
	 *  in the ATN a rule is invoked.
	 *
	 *  This is very useful for error messages.
	 */
	public List<String> getRuleInvocationStack() {
		return getRuleInvocationStack(_ctx);
	}

	public List<String> getRuleInvocationStack(RuleContext p) {
		String[] ruleNames = getRuleNames();
		List<String> stack = new ArrayList<String>();
		while ( p!=null ) {
			// compute what follows who invoked us
			int ruleIndex = p.getRuleIndex();
			if ( ruleIndex<0 ) stack.add("n/a");
			else stack.add(ruleNames[ruleIndex]);
			p = p.parent;
		}
		return stack;
	}

	/** For debugging and other purposes. */
	public List<String> getDFAStrings() {
		List<String> s = new ArrayList<String>();
		for (int d = 0; d < _interp.atn.decisionToDFA.length; d++) {
			DFA dfa = _interp.atn.decisionToDFA[d];
			s.add( dfa.toString(getVocabulary(), getRuleNames()) );
		}
		return s;
	}

	/** For debugging and other purposes. */
	public void dumpDFA() {
		boolean seenOne = false;
		for (int d = 0; d < _interp.atn.decisionToDFA.length; d++) {
			DFA dfa = _interp.atn.decisionToDFA[d];
			if ( !dfa.isEmpty() ) {
				if ( seenOne ) System.out.println();
				System.out.println("Decision " + dfa.decision + ":");
				System.out.print(dfa.toString(getVocabulary(), getRuleNames()));
				seenOne = true;
			}
		}
	}

	/**
	 * @sharpen.property SourceName
	 */
	public String getSourceName() {
		return _input.getSourceName();
	}

	@Override
	public ParseInfo getParseInfo() {
		ParserATNSimulator interp = getInterpreter();
		if (interp instanceof ProfilingATNSimulator) {
			return new ParseInfo((ProfilingATNSimulator)interp);
		}
		return null;
	}

	/**
	 * @since 4.3
	 * @sharpen.property Profile
	 */
	public void setProfile(boolean profile) {
		ParserATNSimulator interp = getInterpreter();
		if ( profile ) {
			if (!(interp instanceof ProfilingATNSimulator)) {
				setInterpreter(new ProfilingATNSimulator(this));
			}
		}
		else if (interp instanceof ProfilingATNSimulator) {
			setInterpreter(new ParserATNSimulator(this, getATN()));
		}
		getInterpreter().setPredictionMode(interp.getPredictionMode());
	}

	/**
	 * @sharpen.property Trace
	 */
	public boolean isTrace() {
		for (Object o : getParseListeners()) {
			if (o instanceof TraceListener) {
				return true;
			}
		}

		return false;
	}

	/** During a parse is sometimes useful to listen in on the rule entry and exit
	 *  events as well as token matches. This is for quick and dirty debugging.
	 * 
	 * @sharpen.property Trace
	 */
	public void setTrace(boolean trace) {
		if ( !trace ) {
			removeParseListener(_tracer);
			_tracer = null;
		}
		else {
			if ( _tracer!=null ) removeParseListener(_tracer);
			else _tracer = new TraceListener();
			addParseListener(_tracer);
		}
	}
}