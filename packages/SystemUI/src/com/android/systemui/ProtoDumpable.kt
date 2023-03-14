package com.android.systemui

import com.android.systemui.dump.nano.SystemUIProtoDump

interface ProtoDumpable : Dumpable {
    fun dumpProto(systemUIProtoDump: SystemUIProtoDump, args: Array<String>)
}
