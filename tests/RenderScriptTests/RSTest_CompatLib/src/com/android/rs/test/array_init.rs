#include "shared.rsh"

// Testing constant array initialization
float fa[4] = {1.0, 9.9999f};
double da[2] = {7.0, 8.88888};
char ca[4] = {'a', 7, 'b', 'c'};
short sa[4] = {1, 1, 2, 3};
int ia[4] = {5, 8};
long la[2] = {13, 21};
long long lla[4] = {34};
bool ba[3] = {true, false};

void array_init_test() {
    bool failed = false;

    _RS_ASSERT(fa[0] == 1.0);
    _RS_ASSERT(fa[1] == 9.9999f);
    _RS_ASSERT(fa[2] == 0);
    _RS_ASSERT(fa[3] == 0);

    _RS_ASSERT(da[0] == 7.0);
    _RS_ASSERT(da[1] == 8.88888);

    _RS_ASSERT(ca[0] == 'a');
    _RS_ASSERT(ca[1] == 7);
    _RS_ASSERT(ca[2] == 'b');
    _RS_ASSERT(ca[3] == 'c');

    _RS_ASSERT(sa[0] == 1);
    _RS_ASSERT(sa[1] == 1);
    _RS_ASSERT(sa[2] == 2);
    _RS_ASSERT(sa[3] == 3);

    _RS_ASSERT(ia[0] == 5);
    _RS_ASSERT(ia[1] == 8);
    _RS_ASSERT(ia[2] == 0);
    _RS_ASSERT(ia[3] == 0);

    _RS_ASSERT(la[0] == 13);
    _RS_ASSERT(la[1] == 21);

    _RS_ASSERT(lla[0] == 34);
    _RS_ASSERT(lla[1] == 0);
    _RS_ASSERT(lla[2] == 0);
    _RS_ASSERT(lla[3] == 0);

    _RS_ASSERT(ba[0] == true);
    _RS_ASSERT(ba[1] == false);
    _RS_ASSERT(ba[2] == false);

    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}

