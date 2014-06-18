#ifndef __R_H
#define __R_H

namespace R {

namespace attr {
    enum {
        attr1       = 0x7f010000, // default
        attr2       = 0x7f010001, // default
    };
}

namespace layout {
    enum {
        main        = 0x7f020000,  // default, fr-sw600dp-v13
    };
}

namespace string {
    enum {
        test1       = 0x7f030000,   // default
        test2       = 0x7f030001,   // default

        test3       = 0x7f070000,   // default (in feature)
        test4       = 0x7f070001,   // default (in feature)
    };
}

namespace integer {
    enum {
        number1     = 0x7f040000,   // default, sv
        number2     = 0x7f040001,   // default

        test3       = 0x7f080000,   // default (in feature)
    };
}

namespace style {
    enum {
        Theme1      = 0x7f050000,   // default
        Theme2      = 0x7f050001,   // default
    };
}

namespace array {
    enum {
        integerArray1 = 0x7f060000,   // default
    };
}

}

#endif // __R_H
