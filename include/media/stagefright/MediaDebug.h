#ifndef MEDIA_DEBUG_H_

#define MEDIA_DEBUG_H_

#include <cutils/log.h>

#define LITERAL_TO_STRING_INTERNAL(x)    #x
#define LITERAL_TO_STRING(x) LITERAL_TO_STRING_INTERNAL(x)

#define CHECK_EQ(x,y)                                                   \
    LOG_ALWAYS_FATAL_IF(                                                \
            (x) != (y),                                                 \
            __FILE__ ":" LITERAL_TO_STRING(__LINE__) " " #x " != " #y)

#define CHECK(x)                                                        \
    LOG_ALWAYS_FATAL_IF(                                                \
            !(x),                                                       \
            __FILE__ ":" LITERAL_TO_STRING(__LINE__) " " #x)

#endif  // MEDIA_DEBUG_H_
