#ifndef AIDL_AIDL_H_
#define AIDL_AIDL_H_

#include "options.h"

int compile_aidl(Options& options);
int preprocess_aidl(const Options& options);

#endif  // AIDL_AIDL_H_
