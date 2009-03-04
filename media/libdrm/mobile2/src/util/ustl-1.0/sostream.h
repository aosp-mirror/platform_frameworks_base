// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// sostream.h
//

#ifndef SOSTREAM_H_5323DC8C26E181D43278F2F53FDCF19F
#define SOSTREAM_H_5323DC8C26E181D43278F2F53FDCF19F

#include "uassert.h"
#include "ustring.h"
#include "mostream.h"

namespace ustl {

class string;

/// \class ostringstream sostream.h ustl.h
/// \ingroup TextStreams
///
/// \brief This stream writes textual data into a memory block.
///
class ostringstream : public ostream {
public:
				ostringstream (const string& v = string::empty_string);
				ostringstream (void* p, size_t n);
    void			iwrite (uint8_t v);
    void			iwrite (wchar_t v);
    inline void			iwrite (int v)			{ iformat (v); }
    inline void			iwrite (unsigned int v)		{ iformat (v); }
    inline void			iwrite (long int v)		{ iformat (v); }
    inline void			iwrite (unsigned long int v)	{ iformat (v); }
    inline void			iwrite (float v)		{ iformat (v); }
    inline void			iwrite (double v)		{ iformat (v); }
    void			iwrite (bool v);
    inline void			iwrite (const char* s)		{ write_buffer (s, strlen(s)); }
    inline void			iwrite (const string& v)	{ write_buffer (v.begin(), v.size()); }
    inline void			iwrite (fmtflags f);
#if HAVE_LONG_LONG
    inline void			iwrite (long long v)		{ iformat (v); }
    inline void			iwrite (unsigned long long v)	{ iformat (v); }
#endif
    inline size_type		max_size (void) const		{ return (m_Buffer.max_size()); }
    inline void			put (char c)			{ iwrite (uint8_t(c)); }
    int				vformat (const char* fmt, va_list args);
    int				format (const char* fmt, ...) __attribute__((__format__(__printf__, 2, 3)));
    inline void			set_base (uint16_t b)		{ m_Base = b; }
    inline void			set_width (uint16_t w)		{ m_Width = w; }
    inline void			set_decimal_separator (char)	{ }
    inline void			set_thousand_separator (char)	{ }
    inline void			set_precision (uint16_t v)	{ m_Precision = v; }
    void			link (void* p, size_type n);
    inline void			link (memlink& l)		{ link (l.data(), l.writable_size()); }
    inline const string&	str (void)			{ flush(); return (m_Buffer); }
    void			str (const string& s);
    void			write (const void* buffer, size_type size);
    void			write (const cmemlink& buf);
    inline void			write_strz (const char*)	{ assert (!"Writing nul characters into a text stream is not allowed"); }
    void			flush (void);
    virtual size_type		overflow (size_type n = 1);
protected:
    void			write_buffer (const char* buf, size_type bufSize);
    inline void			reserve (size_type n)		{ m_Buffer.reserve (n, false); }
    inline size_type		capacity (void) const		{ return (m_Buffer.capacity()); }
private:
    inline char*		encode_dec (char* fmt, uint32_t n) const;
    void			fmtstring (char* fmt, const char* typestr, bool bInteger) const;
    template <typename T>
    void			iformat (T v);
private:
    string			m_Buffer;		///< The output buffer.
    uint32_t			m_Flags;		///< See ios_base::fmtflags.
    uint16_t			m_Width;		///< Field width.
    uint8_t			m_Base;			///< Numeric base for writing numbers.
    uint8_t			m_Precision;		///< Number of digits after the decimal separator.
};

//----------------------------------------------------------------------

template <typename T>
inline const char* printf_typestring (const T&)	{ return (""); }
#define PRINTF_TYPESTRING_SPEC(type,str)	\
template <> inline const char* printf_typestring (const type&)	{ return (str); }
PRINTF_TYPESTRING_SPEC (int,		"d")
PRINTF_TYPESTRING_SPEC (unsigned int,	"u")
PRINTF_TYPESTRING_SPEC (long,		"ld")
PRINTF_TYPESTRING_SPEC (unsigned long,	"lu")
PRINTF_TYPESTRING_SPEC (float,		"f")
PRINTF_TYPESTRING_SPEC (double,		"lf")
#if HAVE_LONG_LONG
PRINTF_TYPESTRING_SPEC (long long,	"lld")
PRINTF_TYPESTRING_SPEC (unsigned long long, "llu")
#endif
#undef PRINTF_TYPESTRING_SPEC

template <typename T>
void ostringstream::iformat (T v)
{
    char fmt [16];
    fmtstring (fmt, printf_typestring(v), numeric_limits<T>::is_integer);
    format (fmt, v);
}

/// Sets the flag \p f in the stream.
inline void ostringstream::iwrite (fmtflags f)
{
    switch (f) {
	case oct:	set_base (8);	break;
	case dec:	set_base (10);	break;
	case hex:	set_base (16);	break;
	case left:	m_Flags |= left; m_Flags &= ~right; break;
	case right:	m_Flags |= right; m_Flags &= ~left; break;
	default:	m_Flags |= f;	break;
    }
}

//----------------------------------------------------------------------

#define OSTRSTREAM_OPERATOR(RealT, CastT)			\
inline ostringstream& operator<< (ostringstream& os, RealT v)	\
{ os.iwrite ((CastT) v); return (os); }

template <typename T>
OSTRSTREAM_OPERATOR (T*,		unsigned long int)
OSTRSTREAM_OPERATOR (const void*,	unsigned long int)
OSTRSTREAM_OPERATOR (void*,		unsigned long int)
OSTRSTREAM_OPERATOR (const char*,	const char*)
OSTRSTREAM_OPERATOR (char*,		const char*)
OSTRSTREAM_OPERATOR (uint8_t*,		const char*)
OSTRSTREAM_OPERATOR (const uint8_t*,	const char*)
OSTRSTREAM_OPERATOR (const string&,	const string&)
OSTRSTREAM_OPERATOR (ios_base::fmtflags,ios_base::fmtflags)
OSTRSTREAM_OPERATOR (int8_t,		uint8_t)
OSTRSTREAM_OPERATOR (uint8_t,		uint8_t)
OSTRSTREAM_OPERATOR (short int,		int)
OSTRSTREAM_OPERATOR (unsigned short,	unsigned int)
OSTRSTREAM_OPERATOR (int,		int)
OSTRSTREAM_OPERATOR (unsigned int,	unsigned int)
OSTRSTREAM_OPERATOR (long,		long)
OSTRSTREAM_OPERATOR (unsigned long,	unsigned long)
OSTRSTREAM_OPERATOR (float,		float)
OSTRSTREAM_OPERATOR (double,		double)
OSTRSTREAM_OPERATOR (bool,		bool)
OSTRSTREAM_OPERATOR (wchar_t,		wchar_t)
#if HAVE_THREE_CHAR_TYPES
OSTRSTREAM_OPERATOR (char,		uint8_t)
#endif
#if HAVE_LONG_LONG
OSTRSTREAM_OPERATOR (long long,		long long)
OSTRSTREAM_OPERATOR (unsigned long long, unsigned long long)
#endif

} // namespace ustl

#endif

