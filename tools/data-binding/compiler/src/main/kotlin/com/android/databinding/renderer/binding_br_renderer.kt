/**
 * Created by yboyar on 11/9/14.
 */
package com.android.databinding.renderer

import java.util.TreeMap
import java.util.LinkedHashMap

class BrRenderer(val pkg : String, val className : String, val vbrs : List<ViewExprBinderRenderer>) {
    val keyToInt = LinkedHashMap<String,Int>();
    {
        addKey("__")
        vbrs.forEach {
            it.lb.variables.values().forEach {
                addKey(it.name)
            }
        }
    }
    var counter = 0
    fun addKey(name : String) {
        if (!keyToInt.contains(name)) {
            keyToInt.put(name, counter ++)
        }
    }

    public fun toInt(key : String) : Int = if (key == "") keyToInt.get("__") else keyToInt.get(key) ?: -1
    public fun toIntS(key : String) : String = "${className}.${if (key == "") "__" else key}"
    public fun render() : String {
        return """
package $pkg;

public class $className {
    ${keyToInt.map({ "public static final int ${it.key} = ${it.value};"}).joinToString("\n    ")}
}
"""
    }
}
