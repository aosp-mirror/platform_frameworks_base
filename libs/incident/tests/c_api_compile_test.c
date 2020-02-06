#include <stdio.h>
#include <incident/incident_report.h>

/*
 * This file ensures that incident/incident_report.h actually compiles with C,
 * since there is no other place in the tree that actually uses it from C.
 */
int not_called() {
    return 0;
}

