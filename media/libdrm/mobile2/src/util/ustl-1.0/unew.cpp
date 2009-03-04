// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// unew.cc
//

#include "unew.h"
#include <stdlib.h>

#if PLATFORM_ANDROID
#include <stdio.h>
#endif

void* throwing_malloc (size_t n) throw (ustl::bad_alloc)
{
    void* p = malloc (n);
    if (!p)
#if PLATFORM_ANDROID
        printf("bad alloc\n");
#else
	throw ustl::bad_alloc (n);
#endif
    return (p);
}

void free_nullok (void* p) throw()
{
    if (p)
	free (p);
}

