// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// cmemlink.cc
//
// See cmemlink.h for documentation.
//

#include "cmemlink.h"
#include "ofstream.h"
#include "strmsize.h"
#include "ualgo.h"
#include "uassert.h"

#if PLATFORM_ANDROID
#include <stdio.h>
#undef CPU_HAS_MMX
#endif

namespace ustl {

/// \brief Attaches the object to pointer \p p of size \p n.
///
/// If \p p is NULL and \p n is non-zero, bad_alloc is thrown and current
/// state remains unchanged.
///
void cmemlink::link (const void* p, size_type n)
{
    if (!p && n)
#if PLATFORM_ANDROID
       printf("bad alloc\n");
#else /* !PLATFORM_ANDROID */
	throw bad_alloc (n);
#endif
    unlink();
    relink (p, n);
}

/// Writes the object to stream \p os
void cmemlink::write (ostream& os) const
{
    const written_size_type sz (size());
    assert (sz == size() && "No support for writing memblocks larger than 4G");
    os << sz;
    os.write (cdata(), sz);
    os.align (alignof (sz));
}

/// Writes the object to stream \p os
void cmemlink::text_write (ostringstream& os) const
{
    os.write (begin(), readable_size());
}

/// Returns the number of bytes required to write this object to a stream.
cmemlink::size_type cmemlink::stream_size (void) const
{
    const written_size_type sz (size());
    return (Align (stream_size_of (sz) + sz, alignof(sz)));
}

/// Writes the data to file \p "filename".
void cmemlink::write_file (const char* filename, int mode) const
{
    fstream f;
    f.exceptions (fstream::allbadbits);
    f.open (filename, fstream::out | fstream::trunc, mode);
    f.write (cdata(), readable_size());
    f.close();
}

/// swaps the contents with \p l
void cmemlink::swap (cmemlink& l)
{
#if CPU_HAS_MMX && SIZE_OF_POINTER == 4
    asm (
	"movq %0, %%mm0\n\t"
	"movq %2, %%mm1\n\t"
	"movq %%mm0, %2\n\t"
	"movq %%mm1, %0"
	: "=m"(m_Data), "=m"(m_Size), "=m"(l.m_Data), "=m"(l.m_Size)
	: 
	: "mm0", "mm1", "st", "st(1)");
    simd::reset_mmx();
#elif CPU_HAS_SSE && SIZE_OF_POINTER == 8
    asm (
	"movups %0, %%xmm0\n\t"
	"movups %2, %%xmm1\n\t"
	"movups %%xmm0, %2\n\t"
	"movups %%xmm1, %0"
	: "=m"(m_Data), "=m"(m_Size), "=m"(l.m_Data), "=m"(l.m_Size)
	: 
	: "xmm0", "xmm1");
#else
    ::ustl::swap (m_Data, l.m_Data);
    ::ustl::swap (m_Size, l.m_Size);
#endif
}

/// Compares to memory block pointed by l. Size is compared first.
bool cmemlink::operator== (const cmemlink& l) const
{
    return (l.m_Size == m_Size &&
	    (l.m_Data == m_Data || 0 == memcmp (l.m_Data, m_Data, m_Size)));
}

} // namespace ustl

