#define NO_RS_FUNCS 1

#include "stdio.h"
#include "RenderScript.h"
#include <vector>

struct Element;

struct ElementField {
    const char *name;
    Element *e;
    ElementField(const char *n, Element *_e) {
        name = n;
        e = _e;
    }
    ElementField() {
        name = NULL;
        e = NULL;
    }
};

struct Element {
    ElementField *fields;
    size_t fieldCount;
    const char *name;
    bool generated;

    RsDataType compType;
    uint32_t compVectorSize;

    Element() {
        fields = NULL;
        fieldCount = 0;
        name = NULL;
        generated = false;
        compType = RS_TYPE_ELEMENT;
        compVectorSize = 0;
    }

    Element(uint32_t _fieldCount, const char *_name) {
        fields = new ElementField[_fieldCount];
        fieldCount = _fieldCount;
        name = _name;
        generated = false;
        compType = RS_TYPE_ELEMENT;
        compVectorSize = 0;
    }

    Element(RsDataType t, uint32_t s) {
        fields = NULL;
        fieldCount = 0;
        name = NULL;
        generated = false;
        compType = t;
        compVectorSize = s;
    }

};


static void genHeader(FILE *f, const char *packageName)
{
    fprintf(f, "package %s;\n", packageName);
    fprintf(f, "\n");
    fprintf(f, "import android.renderscript.*;\n");
    fprintf(f, "\n");
    fprintf(f, "\n");
}

static const char * RSTypeToJava(RsDataType dt)
{
    switch(dt) {
    //case RS_TYPE_FLOAT_16:         return "float";
    case RS_TYPE_FLOAT_32:         return "float";
    //case RS_TYPE_FLOAT_64:         return "double";

    case RS_TYPE_SIGNED_8:         return "byte";
    case RS_TYPE_SIGNED_16:        return "short";
    case RS_TYPE_SIGNED_32:        return "int";
    //case RS_TYPE_SIGNED_64:        return "long";

    case RS_TYPE_UNSIGNED_8:       return "short";
    case RS_TYPE_UNSIGNED_16:      return "int";
    case RS_TYPE_UNSIGNED_32:      return "long";
    //case RS_TYPE_UNSIGNED_64:      return NULL;

    //case RS_TYPE_ELEMENT:          return "android.renderscript.Element";
    //case RS_TYPE_TYPE:             return "android.renderscript.Type";
    //case RS_TYPE_ALLOCATION:       return "android.renderscript.Allocation";
    //case RS_TYPE_SAMPLER:          return "android.renderscript.Sampler";
    //case RS_TYPE_SCRIPT:           return "android.renderscript.Script";
    //case RS_TYPE_MESH:             return "android.renderscript.Mesh";
    //case RS_TYPE_PROGRAM_FRAGMENT: return "android.renderscript.ProgramFragment";
    //case RS_TYPE_PROGRAM_VERTEX:   return "android.renderscript.ProgramVertex";
    //case RS_TYPE_PROGRAM_RASTER:   return "android.renderscript.ProgramRaster";
    //case RS_TYPE_PROGRAM_STORE:    return "android.renderscript.ProgramStore";
    default: return NULL;
    }
    return NULL;
}

static const char * RSTypeToString(RsDataType dt)
{
    switch(dt) {
    case RS_TYPE_FLOAT_16:         return "F16";
    case RS_TYPE_FLOAT_32:         return "F32";
    case RS_TYPE_FLOAT_64:         return "F64";

    case RS_TYPE_SIGNED_8:         return "I8";
    case RS_TYPE_SIGNED_16:        return "I16";
    case RS_TYPE_SIGNED_32:        return "I32";
    case RS_TYPE_SIGNED_64:        return "I64";

    case RS_TYPE_UNSIGNED_8:       return "U8";
    case RS_TYPE_UNSIGNED_16:      return "U16";
    case RS_TYPE_UNSIGNED_32:      return "U32";
    case RS_TYPE_UNSIGNED_64:      return "U64";

    //case RS_TYPE_ELEMENT:          return "android.renderscript.Element";
    //case RS_TYPE_TYPE:             return "android.renderscript.Type";
    //case RS_TYPE_ALLOCATION:       return "android.renderscript.Allocation";
    //case RS_TYPE_SAMPLER:          return "android.renderscript.Sampler";
    //case RS_TYPE_SCRIPT:           return "android.renderscript.Script";
    //case RS_TYPE_MESH:             return "android.renderscript.Mesh";
    //case RS_TYPE_PROGRAM_FRAGMENT: return "android.renderscript.ProgramFragment";
    //case RS_TYPE_PROGRAM_VERTEX:   return "android.renderscript.ProgramVertex";
    //case RS_TYPE_PROGRAM_RASTER:   return "android.renderscript.ProgramRaster";
    //case RS_TYPE_PROGRAM_STORE:    return "android.renderscript.ProgramStore";
    default: return NULL;
    }
    return NULL;
}

bool rsGenerateElementClass(const Element *e, const char *packageName, FILE *f)
{
    genHeader(f, packageName);

    fprintf(f, "class Element_%s {\n", e->name);

    for (size_t ct=0; ct < e->fieldCount; ct++) {
        const char *ts = RSTypeToJava(e->fields[ct].e->compType);
        if (ts == NULL) {
            return false;
        }
        fprintf(f, "    public %s %s;\n", ts, e->fields[ct].name);
    }

    fprintf(f, "\n");
    fprintf(f, "    static Element getElement(RenderScript rs) {\n");
    fprintf(f, "        Element.Builder eb = new Element.Builder(rs);\n");
    for (size_t ct=0; ct < e->fieldCount; ct++) {
        const char *ts = RSTypeToString(e->fields[ct].e->compType);
        fprintf(f, "         eb.add(Element.USER_%s(rs), \"%s\");\n", ts, e->fields[ct].name);
    }
    fprintf(f, "        return eb.create();\n");
    fprintf(f, "    }\n");

    fprintf(f, "    static Allocation createAllocation(RenderScript rs) {\n");
    fprintf(f, "        Element e = getElement(rs);\n");
    fprintf(f, "        Allocation a = Allocation.createSized(rs, e, 1);\n");
    fprintf(f, "        return a;\n");
    fprintf(f, "    }\n");


    fprintf(f, "    void copyToAllocation(Allocation a) {\n");
    fprintf(f, "        mIOBuffer.reset();\n");
    for (size_t ct=0; ct < e->fieldCount; ct++) {
        const char *ts = RSTypeToString(e->fields[ct].e->compType);
        fprintf(f, "         mIOBuffer.add%s(%s);\n", ts, e->fields[ct].name);
    }
    fprintf(f, "        a.data(mIOBuffer.getData());\n");
    fprintf(f, "    }\n");



    fprintf(f, "    private FieldPacker mIOBuffer[];\n");
    fprintf(f, "    public Element_%s() {\n", e->name);
    fprintf(f, "        mIOBuffer = new FieldPacker(%i);\n", 100/*element->getSizeBytes()*/);
    fprintf(f, "    }\n");


    fprintf(f, "}\n");

    return true;
}

bool rsGenerateElementClassFile(Element *e, const char *packageName)
{
    char buf[1024];
    sprintf(buf, "Element_%s.java", e->name);
    printf("Creating file %s \n", buf);
    FILE *f = fopen(buf, "w");
    bool ret = rsGenerateElementClass(e, packageName, f);
    fclose(f);
    return ret;
}




/*
bool rsGenerateScriptClass(const ScriptC *script, const char *packageName, FILE *f)
{
    genHeader(f, packageName);

    fprintf(f, "class ScriptC_%s {\n", script->getName());



        ObjectBaseRef<const Type> mTypes[MAX_SCRIPT_BANKS];
    String8 mSlotNames[MAX_SCRIPT_BANKS];
    bool mSlotWritable[MAX_SCRIPT_BANKS];


}
*/



int main(int argc, const char *argv)
{
    Element *u8 = new Element(RS_TYPE_UNSIGNED_8, 1);
    Element *i32 = new Element(RS_TYPE_SIGNED_32, 1);
    Element *f32 = new Element(RS_TYPE_FLOAT_32, 1);

    Element *e_Pixel = new Element(4, "Pixel");
    e_Pixel->fields[0].e = u8;
    e_Pixel->fields[0].name = "a";
    e_Pixel->fields[1].e = u8;
    e_Pixel->fields[1].name = "b";
    e_Pixel->fields[2].e = u8;
    e_Pixel->fields[2].name = "g";
    e_Pixel->fields[3].e = u8;
    e_Pixel->fields[3].name = "r";

    Element *e_Params = new Element(5, "Params");
    e_Params->fields[0].e = i32;
    e_Params->fields[0].name = "inHeight";
    e_Params->fields[1].e = i32;
    e_Params->fields[1].name = "inWidth";
    e_Params->fields[2].e = i32;
    e_Params->fields[2].name = "outHeight";
    e_Params->fields[3].e = i32;
    e_Params->fields[3].name = "outWidth";
    e_Params->fields[4].e = f32;
    e_Params->fields[4].name = "threshold";


    printf("1\n");
    rsGenerateElementClassFile(e_Pixel, "android");
    rsGenerateElementClassFile(e_Params, "android");

}

