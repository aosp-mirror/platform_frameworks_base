// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
/// \file strmsize.h
/// \brief This file contains stream_size_of functions for basic types and *STREAMABLE macros.
/// stream_size_of functions return the size of the object's data that is written or
/// read from a stream.
//

#ifndef STRMSIZE_H_052FF16B2D8A608761BF10333D065073
#define STRMSIZE_H_052FF16B2D8A608761BF10333D065073

#include "uassert.h"

namespace ustl {

/// Returns the size of the given object. Overloads for standard types are available.
template <typename T>
inline size_t stream_size_of (T*)	{ return (sizeof(T*));		}
#ifndef DOXYGEN_SHOULD_IGNORE_THIS
inline size_t stream_size_of (int8_t)	{ return (sizeof(int8_t));	}
inline size_t stream_size_of (uint8_t)	{ return (sizeof(uint8_t));	}
inline size_t stream_size_of (int16_t)	{ return (sizeof(int16_t));	}
inline size_t stream_size_of (uint16_t)	{ return (sizeof(uint16_t));	}
inline size_t stream_size_of (int32_t)	{ return (sizeof(int32_t));	}
inline size_t stream_size_of (uint32_t)	{ return (sizeof(uint32_t));	}
inline size_t stream_size_of (float)	{ return (sizeof(float));	}
inline size_t stream_size_of (double)	{ return (sizeof(double));	}
inline size_t stream_size_of (bool)	{ return (sizeof(uint8_t));	}
inline size_t stream_size_of (wchar_t)	{ return (sizeof(wchar_t));	}
#if HAVE_THREE_CHAR_TYPES
inline size_t stream_size_of (char)	{ return (sizeof(char));	}
#endif
#if HAVE_INT64_T
inline size_t stream_size_of (int64_t)	{ return (sizeof(int64_t));	}
inline size_t stream_size_of (uint64_t)	{ return (sizeof(uint64_t));	}
#endif
#if SIZE_OF_LONG == SIZE_OF_INT
inline size_t stream_size_of (long v)			{ return (sizeof (v));	}
inline size_t stream_size_of (unsigned long v)		{ return (sizeof (v));	}
#endif
#if HAVE_LONG_LONG && (!HAVE_INT64_T || SIZE_OF_LONG_LONG > 8)
inline size_t stream_size_of (long long v)		{ return (sizeof (v));	}
inline size_t stream_size_of (unsigned long long v)	{ return (sizeof (v));	}
#endif
#endif // DOXYGEN_SHOULD_IGNORE_THIS

} // namespace ustl

/// Declares that T is not written to istream/ostream.
#define NOT_STREAMABLE(T)	\
    namespace ustl {		\
	inline istream& operator>> (istream& is, T&)		{ return (is); }	\
	inline ostream& operator<< (ostream& os, const T&)	{ return (os); }	\
	inline size_t stream_size_of (const T&)			{ return (0); }		\
    }

//
// Extra overloads in this macro are needed because it is the one used for
// marshalling pointers. Passing a pointer to stream_size_of creates a
// conversion ambiguity between converting to const pointer& and converting
// to bool; the compiler always chooses the bool conversion (because it
// requires 1 conversion instead of 2 for the other choice). There is little
// point in adding the overloads to other macros, since they are never used
// for pointers.
//
/// Declares that T is to be written as is into binary streams.
#define INTEGRAL_STREAMABLE(T)	\
    namespace ustl {		\
	inline istream& operator>> (istream& is, T& v)		{ is.iread(v);  return (is); }	\
	inline ostream& operator<< (ostream& os, const T& v)	{ os.iwrite(v); return (os); }	\
	inline ostream& operator<< (ostream& os, T& v)		{ os.iwrite(v); return (os); }	\
	inline size_t stream_size_of (const T& v)		{ return (sizeof(v)); }		\
	inline size_t stream_size_of (T& v)			{ return (sizeof(v)); }		\
    }

#ifdef NDEBUG
    #define STD_STREAMABLE_SZCHK_BEGIN
    #define STD_STREAMABLE_SZCHK_END
#else
    #define STD_STREAMABLE_SZCHK_BEGIN		\
	assert (os.aligned (alignof (v)));	\
	const uoff_t vStart (os.pos())
    #define STD_STREAMABLE_SZCHK_END		\
	if (os.pos() - vStart != v.stream_size()) \
	    throw stream_bounds_exception ("write", typeid(v).name(), vStart, os.pos() - vStart, v.stream_size())
#endif

/// Declares that T contains read, write, and stream_size methods.
#define STD_STREAMABLE(T)	\
    namespace ustl {		\
	inline istream& operator>> (istream& is, T& v)		{ assert (is.aligned (alignof (v))); v.read (is);  return (is); }	\
	inline ostream& operator<< (ostream& os, const T& v)	{ STD_STREAMABLE_SZCHK_BEGIN; v.write (os); STD_STREAMABLE_SZCHK_END; return (os); }	\
	inline size_t stream_size_of (const T& v)		{ return (v.stream_size()); }	\
    }

/// Declares that T is to be cast into TSUB for streaming.
#define CAST_STREAMABLE(T,TSUB)	\
    namespace ustl {		\
	inline istream& operator>> (istream& is, T& v)		{ TSUB sv; is >> sv; v = (T)(sv); return (is); }	\
	inline ostream& operator<< (ostream& os, const T& v)	{ os << TSUB(v); return (os); }			\
	inline size_t stream_size_of (const T& v)		{ return (sizeof(TSUB(v))); }				\
    }

/// Placed into a class it declares the methods required by STD_STREAMABLE. Syntactic sugar.
#define DECLARE_STD_STREAMABLE			\
    public:					\
	void	read (istream& is);		\
	void	write (ostream& os) const;	\
	size_t	stream_size (void) const

/// Declares \p T to be writable to text streams. Reading is not implemented because you should not do it.
#define TEXT_STREAMABLE(T)	\
    namespace ustl {		\
	inline ostringstream& operator<< (ostringstream& os, const T& v)	\
	    { v.text_write (os); return (os); }	\
    }

/// Specifies that \p T is printed by using it as an index into \p Names string array.
#define LOOKUP_TEXT_STREAMABLE(T,Names,nNames)	\
    namespace ustl {		\
	inline ostringstream& operator<< (ostringstream& os, const T& v)	\
	{				\
	    if (uoff_t(v) < (nNames))	\
		os << Names[v];		\
	    else			\
		os << uoff_t(v);	\
	    return (os);		\
	}				\
    }

#endif

