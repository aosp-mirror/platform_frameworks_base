#!/bin/sh

awk '
/^#define GL_/ {
    names[count] = $2;
    values[count] = $3;
    sort[count] = $3 + 0;
    count++;
}
END {
    for (i = 1; i < count; i++) {
        for (j = 0; j < i; j++) {
            if (sort[i] < sort[j]) {
                tn = names[i];
                tv = values[i];
                ts = sort[i];
                names[i] = names[j];
                values[i] = values[j];
                sort[i] = sort[j];
                names[j] = tn;
                values[j] = tv;
                sort[j] = ts;
            }
        }
    }
 
    for (i = 0; i < count; i++) {
        printf("GLENUM(%s, %s)\n", names[i], values[i]);
    }
}
' < $1

