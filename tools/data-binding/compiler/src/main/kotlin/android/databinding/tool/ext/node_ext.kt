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

package android.databinding.tool.ext

import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.util.ArrayList

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

public fun NodeList.forEach( f : (Node) -> Unit ) {
    val cnt = getLength()
    if (cnt == 0) return
    for (i in 0..cnt - 1) {
        f(item(i))
    }
}
public fun NodeList.toArrayList() : ArrayList<Node> {
    val cnt = getLength()
    val arrayList = arrayListOf<Node>()
    for (i in 0..cnt - 1) {
        arrayList.add(item(i))
    }
    return arrayList
}


