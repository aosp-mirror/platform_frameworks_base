
#include "RenderScript.h"
#include "Element.h"
#include "Type.h"
#include "Allocation.h"

int main(int argc, char** argv)
{

    RenderScript *rs = new RenderScript();
    printf("New RS %p\n", rs);

    bool r = rs->init(16);
    printf("Init returned %i\n", r);

    const Element *e = Element::RGBA_8888(rs);
    printf("Element %p\n", e);

    Type::Builder tb(rs, e);
    tb.setX(128);
    tb.setY(128);
    const Type *t = tb.create();
    printf("Type %p\n", t);


    const Allocation *a1 = Allocation::createSized(rs, e, 1000);
    printf("Allocation %p\n", a1);



    printf("Deleting stuff\n");
    delete t;
    delete a1;
    delete e;
    delete rs;
    printf("Delete OK\n");
}
