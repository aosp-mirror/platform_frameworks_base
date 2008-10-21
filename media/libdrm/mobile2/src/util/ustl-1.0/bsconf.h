/* This file is part of bsconf - a configure replacement.
 *
 * This is the configuration file used by bsconf.c to specify information
 * specific to your project that it needs to substitute into files listed
 * in g_Files. Being a configuration file, this file can be used or
 * modified entirely without restriction. You should change all values
 * appropriately to the name of your project and its requirements. The
 * bsconf license does not apply to this file. It can and should be
 * treated as a template for the creation of your own configuration file.
 *
 * All substituted variable names are given without enclosing @@. For
 * example: "CC" will match "@CC@" in config.h.in and replace it with
 * "gcc" in config.h.
*/

#include "uassert.h"

#define BSCONF_VERSION		0x03

#define PACKAGE_NAME		"ustl"
#define LIB_MAJOR		"1"
#define LIB_MINOR		"0"
#define LIB_BUILD		"0"

#define PACKAGE_VERSION		LIB_MAJOR "." LIB_MINOR
#define PACKAGE_TARNAME		PACKAGE_NAME
#define PACKAGE_STRING		PACKAGE_NAME " " PACKAGE_VERSION
#define PACKAGE_BUGREPORT	"Mike Sharov <msharov@users.sourceforge.net>"

static cpchar_t g_Files [] = {
    "Config.mk",
    "config.h",
    "ustl.spec"
};

/* Values substitute @VARNAME@ */
static cpchar_t g_EnvVars [] = {
    "CC",
    "LD",
    "CXX",
    "CPP",
    "HOME",
    "CXXFLAGS",
    "LDFLAGS",
    "CPPFLAGS",
    "LDFLAGS",
    "CFLAGS"
};

/*  VARIABLE	PROGRAM		HOW TO CALL	IF NOT FOUND */
static cpchar_t g_ProgVars [] = {
    "CC",	"gcc",		"gcc",		"@CC@",
    "CC",	"cc",		"cc",		"gcc",
    "CXX",	"g++",		"g++",		"@CXX@",
    "CXX",	"c++",		"c++",		"g++",
    "LD",	"ld",		"ld",		"ld",
    "AR",	"ar",		"ar",		"echo",
    "RANLIB",	"ranlib",	"ranlib",	"touch",
    "DOXYGEN",	"doxygen",	"doxygen",	"echo",
    "INSTALL",	"install",	"install -c",	"cp"
};

/*   NAME               IF NOT FOUND                    IF FOUND */
static cpchar_t	g_Headers [] = {
    "assert.h",		"#undef HAVE_ASSERT_H",		"#define HAVE_ASSERT_H 1",
    "ctype.h",		"#undef HAVE_CTYPE_H",		"#define HAVE_CTYPE_H 1",
    "errno.h",		"#undef HAVE_ERRNO_H",		"#define HAVE_ERRNO_H 1",
    "fcntl.h",		"#undef HAVE_FCNTL_H",		"#define HAVE_FCNTL_H 1",
    "float.h",		"#undef HAVE_FLOAT_H",		"#define HAVE_FLOAT_H 1",
    "inttypes.h",	"#undef HAVE_INTTYPES_H",	"#define HAVE_INTTYPES_H 1",
    "limits.h",		"#undef HAVE_LIMITS_H",		"#define HAVE_LIMITS_H 1",
    "locale.h",		"#undef HAVE_LOCALE_H",		"#define HAVE_LOCALE_H 1",
    "malloc.h",		"#undef HAVE_MALLOC_H",		"#define HAVE_MALLOC_H 1",
    "alloca.h",		"#undef HAVE_ALLOCA_H",		"#define HAVE_ALLOCA_H 1",
    "memory.h",		"#undef HAVE_MEMORY_H",		"#define HAVE_MEMORY_H 1",
    "signal.h",		"#undef HAVE_SIGNAL_H",		"#define HAVE_SIGNAL_H 1",
    "stdarg.h",		"#undef HAVE_STDARG_H",		"#define HAVE_STDARG_H 1",
    "stddef.h",		"#undef HAVE_STDDEF_H",		"#define HAVE_STDDEF_H 1",
    "stdint.h",		"#undef HAVE_STDINT_H",		"#define HAVE_STDINT_H 1",
    "stdio.h",		"#undef HAVE_STDIO_H",		"#define HAVE_STDIO_H 1",
    "stdlib.h",		"#undef HAVE_STDLIB_H",		"#define HAVE_STDLIB_H 1",
    "string.h",		"#undef HAVE_STRING_H",		"#define HAVE_STRING_H 1",
    "strings.h",	"#undef HAVE_STRINGS_H",	"#define HAVE_STRINGS_H 1",
    "sys/stat.h",	"#undef HAVE_SYS_STAT_H",	"#define HAVE_SYS_STAT_H 1",
    "sys/types.h",	"#undef HAVE_SYS_TYPES_H",	"#define HAVE_SYS_TYPES_H 1",
    "sys/wait.h",	"#undef HAVE_SYS_WAIT_H",	"#define HAVE_SYS_WAIT_H 1",
    "time.h",		"#undef HAVE_TIME_H",		"#define HAVE_TIME_H 1",
    "unistd.h",		"#undef HAVE_UNISTD_H",		"#define HAVE_UNISTD_H 1",
    "math.h",		"#undef HAVE_MATH_H",		"#define HAVE_MATH_H 1",
    "stdlib.h",		"#undef HAVE_STDLIB_H",		"#define HAVE_STDLIB_H 1"
};

/*   NAME               IF NOT FOUND                    IF FOUND */
static cpchar_t g_Libs [] = {
    "supc++",		"",				"-lsupc++",
#if __GNUC__ >= 4
    "gcc",		"-lgcc_s",			"-lgcc_s",
    "gcc_eh",		"",				"",
#elif __GNUC__ >= 3
    "gcc",		"-lgcc_s",			"-lgcc",
    "gcc_eh",		"-lgcc_s",			"-lgcc_eh",
#else
    "gcc",		"",				"-lgcc",
    "gcc_eh",		"",				"",
#endif
    "SystemStubs",	"",				"-lSystemStubs", /* For MacOS 10.4+ */
    "c",		"",				"-lc"
};

/*   NAME               IF NOT FOUND                    IF FOUND */
static cpchar_t g_Functions [] = {
    "atexit",		"#undef HAVE_ATEXIT",		"#define HAVE_ATEXIT 1",
    "malloc",		"#undef HAVE_MALLOC\n",		"#define HAVE_MALLOC 1\n",
    "memchr",		"#undef HAVE_MEMCHR",		"#define HAVE_MEMCHR 1",
    "memmove",		"#undef HAVE_MEMMOVE",		"#define HAVE_MEMMOVE 1",
    "memset",		"#undef HAVE_MEMSET",		"#define HAVE_MEMSET 1",
    "ptrdiff_t",	"#undef HAVE_PTRDIFF_T",	"#define HAVE_PTRDIFF_T 1",
    "strerror",		"#undef HAVE_STRERROR",		"#define HAVE_STRERROR 1",
    "strsignal",	"#undef HAVE_STRSIGNAL",	"#define HAVE_STRSIGNAL 1",
    "strtol",		"#undef HAVE_STRTOL",		"#define HAVE_STRTOL 1",
#if __GNUC__ >= 3
    "round",		"#undef HAVE_ROUND",		"#define HAVE_ROUND 1",
#endif
    "strrchr",		"#undef HAVE_STRRCHR",		"#define HAVE_STRRCHR 1",
    "__va_copy",	"#undef HAVE_VA_COPY",		"#define HAVE_VA_COPY 1"
};

/*   NAME               WITHOUT TEXT                            WITH TEXT */
static cpchar_t g_Components [] = {
    "shared",		"#BUILD_SHARED\t= 1",			"BUILD_SHARED\t= 1 ",
    "static",		"#BUILD_STATIC\t= 1",			"BUILD_STATIC\t= 1 ",
    "debug",		"#DEBUG\t\t= 1",			"DEBUG\t\t= 1 ",
    "bounds",		"#undef WANT_STREAM_BOUNDS_CHECKING",	"#define WANT_STREAM_BOUNDS_CHECKING 1 ",
    "fastcopy",		"#undef WANT_UNROLLED_COPY",		"#define WANT_UNROLLED_COPY 1 ",
#if __GNUC__ >= 3 && (__i386__ || __x86_64__) && !sun
    "mmx",		"#undef WANT_MMX",			"#define WANT_MMX 1 ",
#endif
    "libstdc++",	"#define WITHOUT_LIBSTDCPP 1",		"#undef WITHOUT_LIBSTDCPP",
    "libstdc++",	"NOLIBSTDCPP\t= -nodefaultlibs ",	"#NOLIBSTDCPP\t= -nodefaultlibs"
};

/* Parallel to g_Components */
static SComponentInfo g_ComponentInfos [VectorSize(g_Components) / 3] = {
    { 1, "Builds the shared library (if supported by the OS)" },
    { 0, "Builds the static library" },
    { 0, "Compiles the library with debugging information" },
    { 1, "Disable runtime bounds checking on stream reads/writes" },
    { 1, "Disable specializations for copy/fill" },
#if __GNUC__ >= 3 && (__i386__ || __x86_64__) && !sun
    { 1, "Disable use of MMX/SSE/3dNow! instructions" },
#endif
#if __GNUC__ >= 3
    { 0, "Link with libstdc++" },
    { 0, "" }
#else
    { 1, "" },
    { 1, "" }
#endif
};

/* Substitutes names like @PACKAGE_NAME@ with the second field */
static cpchar_t g_CustomVars [] = {
    "PACKAGE_NAME",		PACKAGE_NAME,
    "PACKAGE_VERSION",		PACKAGE_VERSION,
    "PACKAGE_TARNAME",		PACKAGE_TARNAME,
    "PACKAGE_STRING",		PACKAGE_STRING,
    "PACKAGE_BUGREPORT",	PACKAGE_BUGREPORT,
    "LIBNAME",			PACKAGE_NAME,
    "LIB_MAJOR",		LIB_MAJOR,
    "LIB_MINOR",		LIB_MINOR,
    "LIB_BUILD",		LIB_BUILD
};

