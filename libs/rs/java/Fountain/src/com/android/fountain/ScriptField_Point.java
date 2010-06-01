
package com.android.fountain;

import android.content.res.Resources;
import android.renderscript.*;
import android.util.Log;

public class ScriptField_Point
    extends android.renderscript.Script.FieldBase
{

    static public class Item {
        Item() {
            delta = new Float2();
            pos = new Float2();
            color = new Short4();
        }

        public static final int sizeof = (5*4);
        Float2 delta;
        Float2 pos;
        Short4 color;
    }
    private Item mItemArray[];


    public ScriptField_Point(RenderScript rs, int count) {
        // Allocate a pack/unpack buffer
        mIOBuffer = new FieldPacker(Item.sizeof * count);
        mItemArray = new Item[count];

        Element.Builder eb = new Element.Builder(rs);
        eb.add(Element.F32_2(rs), "delta");
        eb.add(Element.F32_2(rs), "position");
        eb.add(Element.U8_4(rs), "color");
        mElement = eb.create();

        init(rs, count);
    }

    private void copyToArray(Item i, int index) {
        mIOBuffer.reset(index * Item.sizeof);
        mIOBuffer.addF32(i.delta);
        mIOBuffer.addF32(i.pos);
        mIOBuffer.addU8(i.color);
    }

    public void set(Item i, int index, boolean copyNow) {
        mItemArray[index] = i;
        if (copyNow) {
            copyToArray(i, index);
            mAllocation.subData1D(index * Item.sizeof, Item.sizeof, mIOBuffer.getData());
        }
    }

    public void copyAll() {
        for (int ct=0; ct < mItemArray.length; ct++) {
            copyToArray(mItemArray[ct], ct);
        }
        mAllocation.data(mIOBuffer.getData());
    }


    private FieldPacker mIOBuffer;


}
