// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// memlink.cc
//
//	A pointer to a sized block of memory.
//

#include "mistream.h"
#include "uassert.h"
#include "ustdxept.h"

#if PLATFORM_ANDROID
#include <stdio.h>
#endif

namespace ustl {

/// Reads the object from stream \p s
void memlink::read (istream& is)
{
    written_size_type n;
    is >> n;
    is.verify_remaining ("read", "ustl::memlink", n);
    if (n > size())
#if PLATFORM_ANDROID
        printf("length error\n");
#else
	throw length_error ("memlink can not increase the size of the linked storage for reading");
#endif
    resize (n);
    is.read (data(), n);
    is.align (alignof (n));
}

/// Copies data from \p p, \p n to the linked block starting at \p start.
void memlink::copy (iterator start, const void* p, size_type n)
{
    assert (data() || !n);
    assert (p || !n);
    assert (start >= begin() && start + n <= end());
    if (p)
	copy_n (const_iterator(p), n, start);
}

/// Fills the linked block with the given pattern.
/// \arg start   Offset at which to start filling the linked block
/// \arg p       Pointer to the pattern.
/// \arg elSize  Size of the pattern.
/// \arg elCount Number of times to write the pattern.
/// Total number of bytes written is \p elSize * \p elCount.
///
void memlink::fill (iterator start, const void* p, size_type elSize, size_type elCount)
{
    assert (data() || !elCount || !elSize);
    assert (start >= begin() && start + elSize * elCount <= end());
    if (elSize == 1)
	fill_n (start, elCount, *reinterpret_cast<const uint8_t*>(p));
    else while (elCount--)
	start = copy_n (const_iterator(p), elSize, start);
}

} // namespace ustl

