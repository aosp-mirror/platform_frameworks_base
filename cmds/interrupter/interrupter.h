/*
 * Copyright 2012, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define CONCATENATE(arg1, arg2)   CONCATENATE1(arg1, arg2)
#define CONCATENATE1(arg1, arg2)  CONCATENATE2(arg1, arg2)
#define CONCATENATE2(arg1, arg2)  arg1##arg2

#define INTERRUPTER(sym) \
    if (real_##sym == NULL) \
        __init_##sym(); \
    if (maybe_interrupt()) { \
        errno = EINTR; \
        return -1; \
    }

#define CALL_FUNCTION_1(sym, ret, type1) \
ret (*real_##sym)(type1) = NULL; \
ret sym(type1 arg1) { \
    INTERRUPTER(sym) \
    return real_##sym(arg1); \
}

#define CALL_FUNCTION_2(sym, ret, type1, type2) \
ret (*real_##sym)(type1, type2) = NULL; \
ret sym(type1 arg1, type2 arg2) { \
    INTERRUPTER(sym) \
    return real_##sym(arg1, arg2); \
}

#define CALL_FUNCTION_3(sym, ret, type1, type2, type3) \
ret (*real_##sym)(type1, type2, type3) = NULL; \
ret sym(type1 arg1, type2 arg2, type3 arg3) { \
    INTERRUPTER(sym) \
    return real_##sym(arg1, arg2, arg3); \
}

#define CALL_FUNCTION_4(sym, ret, type1, type2, type3, type4) \
ret (*real_##sym)(type1, type2, type3, type4) = NULL; \
ret sym(type1 arg1, type2 arg2, type3 arg3, type4 arg4) { \
    INTERRUPTER(sym) \
    return real_##sym(arg1, arg2, arg3, arg4); \
}

#define CALL_FUNCTION_5(sym, ret, type1, type2, type3, type4, type5) \
ret (*real_##sym)(type1, type2, type3, type4, type5) = NULL; \
ret sym(type1 arg1, type2 arg2, type3 arg3, type4 arg4, type5 arg5) { \
    INTERRUPTER(sym) \
    return real_##sym(arg1, arg2, arg3, arg4, arg5); \
}

#define DEFINE_INTERCEPT_N(N, sym, ret, ...) \
static void __init_##sym(void); \
CONCATENATE(CALL_FUNCTION_, N)(sym, ret, __VA_ARGS__) \
static void __init_##sym(void) { \
    real_##sym = dlsym(RTLD_NEXT, #sym); \
    if (real_##sym == NULL) { \
        fprintf(stderr, "Error hooking " #sym ": %s\n", dlerror()); \
    } \
}

#define INTERCEPT_NARG(...) INTERCEPT_NARG_N(__VA_ARGS__, INTERCEPT_RSEQ_N())
#define INTERCEPT_NARG_N(...) INTERCEPT_ARG_N(__VA_ARGS__)
#define INTERCEPT_ARG_N(_1, _2, _3, _4, _5, _6, _7, _8, N, ...) N
#define INTERCEPT_RSEQ_N() 8, 7, 6, 5, 4, 3, 2, 1, 0

#define DEFINE_INTERCEPT(sym, ret, ...) DEFINE_INTERCEPT_N(INTERCEPT_NARG(__VA_ARGS__), sym, ret, __VA_ARGS__)
