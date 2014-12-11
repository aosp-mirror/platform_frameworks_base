/**
 * Created by yboyar on 11/10/14.
 */
package com.android.databinding.renderer

import java.util.TreeSet


class AttrRenderer(rs : List<ViewExprBinderRenderer>) {
    val names = TreeSet<String>();
    {
        rs.forEach {
            it.lb.variables.values().forEach {
                names.add(it.name)
            }
            it.lb.bindings.forEach {
                names.add(it.targetFieldName)
            }
        }

    }
    public fun render() : String = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <declare-styleable name="DataBindingAuto">
        ${names.map {"<attr name=\"${it}\" format=\"string\"/>"}.join("\n        ")}
    </declare-styleable>
</resources>
"""
}
