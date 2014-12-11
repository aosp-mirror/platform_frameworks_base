package com.android.databinding.ext

import org.w3c.dom.Node

/**
 * Created by yboyar on 11/11/14.
 */

public fun Node.getAndroidId() : String? =
    getAttributes()?.getNamedItem("android:id")?.getNodeValue()

public fun Node.getAndroidIdPath(includeRoot : Boolean) : List<String> {
    val ids = arrayListOf<String>()
    ids.add(getAndroidId()!!)
    var parent : Node? = getParentNode()
    while (parent != null && (includeRoot || parent?.getParentNode()?.getParentNode() != null)) {
        val id = parent?.getAndroidId()
        if (id != null) {
            ids.add(id)
        }
        parent = parent?.getParentNode()
    }
    return ids
}

