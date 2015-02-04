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

package com.android.databinding.util

import java.io.File
import org.antlr.v4.runtime.ANTLRInputStream
import java.io.FileReader
import com.android.databinding.XMLLexer
import org.antlr.v4.runtime.CommonTokenStream
import com.android.databinding.XMLParser
import com.android.databinding.log
import com.android.databinding.XMLParserBaseVisitor
import com.android.databinding.Position
import com.android.databinding.toPosition
import com.android.databinding.toEndPosition
import java.util.Comparator
import com.google.common.base.Preconditions

/**
 * Ugly inefficient class to strip unwanted tags from XML.
 * Band-aid solution to unblock development
 */
object XmlEditor {
    val reservedElementNames = arrayListOf("variable", "import")
    var rootNodeContext: XMLParser.ElementContext? = null
    var rootNodeHasTag = false
    val visitor = object : XMLParserBaseVisitor<MutableList<Pair<Position, Position>>>() {
        override fun visitAttribute(ctx: XMLParser.AttributeContext): MutableList<Pair<Position, Position>>? {
            log { "attr:${ctx.attrName.getText()} ${ctx.attrValue.getText()} . parent: ${ctx.getParent()}" }
            if (ctx.getParent() == rootNodeContext && ctx.attrName.getText() == "android:tag") {
                rootNodeHasTag = true
            }
            if (ctx.attrName.getText().startsWith("bind:")) {

                return arrayListOf(Pair(ctx.getStart().toPosition(), ctx.getStop().toEndPosition()))
            } else if (ctx.attrValue.getText().startsWith("\"@{") && ctx.attrValue.getText().endsWith("}\"")) {
                return arrayListOf(Pair(ctx.getStart().toPosition(), ctx.getStop().toEndPosition()))
            }

            //log{"visiting attr: ${ctx.getText()} at location ${ctx.getStart().toS()} ${ctx.getStop().toS()}"}
            return super<XMLParserBaseVisitor>.visitAttribute(ctx)
        }

        override fun visitElement(ctx: XMLParser.ElementContext): MutableList<Pair<Position, Position>>? {
            log { "elm ${ctx.elmName.getText()} || ${ctx.Name()} paren : ${ctx.getParent()}" }
            if (rootNodeContext == null) {
                rootNodeContext = ctx
            }
            if (reservedElementNames.contains(ctx.elmName?.getText()) || ctx.elmName.getText().startsWith("bind:")) {
                return arrayListOf(Pair(ctx.getStart().toPosition(), ctx.getStop().toEndPosition()))
            }
            return super< XMLParserBaseVisitor>.visitElement(ctx)
        }

        override fun defaultResult(): MutableList<Pair<Position, Position>>? = arrayListOf()

        override fun aggregateResult(aggregate: MutableList<Pair<Position, Position>>?, nextResult: MutableList<Pair<Position, Position>>?): MutableList<Pair<Position, Position>>? =
                if (aggregate == null) {
                    nextResult
                } else if (nextResult == null) {
                    aggregate
                } else {
                    aggregate.addAll(nextResult)
                    aggregate
                }
    }

    fun strip(f: File, newTag: String? = null): String? {
        rootNodeContext = null //clear it
        rootNodeHasTag = false
        val inputStream = ANTLRInputStream(FileReader(f))
        val lexer = XMLLexer(inputStream)
        val tokenStream = CommonTokenStream(lexer)
        val parser = XMLParser(tokenStream)
        val expr = parser.document()
        val parsedExpr = expr.accept(visitor)
        if (parsedExpr.isEmpty()) {
            return null//nothing to strip
        }
        Preconditions.checkNotNull(rootNodeContext, "Cannot find root node for ${f.getName()}")
        Preconditions.checkState(rootNodeHasTag == false, """You cannot set a tag in the layout
        root if you are using binding. Invalid file: ${f}""")
        val rootNodeBounds = Pair(rootNodeContext!!.getStart().toPosition(), rootNodeContext!!.getStop().toEndPosition())

        log { "root node bounds: ${rootNodeBounds}" }
        val out = StringBuilder()
        val lines = f.readLines("utf-8")

        lines.forEach { out.appendln(it) }

        // TODO we probably don't need to sort
        val sorted = parsedExpr.sortBy(object : Comparator<Pair<Position, Position>> {
            override fun compare(o1: Pair<Position, Position>, o2: Pair<Position, Position>): Int {
                val lineCmp = o1.first.line.compareTo(o2.first.charIndex)
                if (lineCmp != 0) {
                    return lineCmp
                }
                return o1.first.line.compareTo(o2.first.charIndex)
            }
        })
        var lineStarts = arrayListOf(0)
        lines.withIndex().forEach {
            if (it.index > 0) {
                lineStarts.add(lineStarts[it.index - 1] + lines[it.index - 1].length() + 1)
            }
        }

        val separator = System.lineSeparator().charAt(0)
        sorted.forEach {
            val posStart = lineStarts[it.first.line] + it.first.charIndex
            val posEnd = lineStarts[it.second.line] + it.second.charIndex
            for ( i in posStart..(posEnd - 1)) {
                if (out.charAt(i) != separator) {
                    out.setCharAt(i, ' ')
                }
            }
        }
        Log.d{"new tag to set: $newTag"}
        if (newTag != null) {
            Preconditions.checkState(rootNodeBounds.first.line != rootNodeBounds.second.line,
                    """The root tag should be multi line to add the tag. ${f.getName()}""")
            val line = rootNodeBounds.first.line
            out.insert(lineStarts[line] + lines[line].length(), """ android:tag = "$newTag" """)
        }


        return out.toString()
    }
}