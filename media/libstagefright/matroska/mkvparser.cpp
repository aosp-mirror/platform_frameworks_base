// Copyright (c) 2010 The WebM project authors. All Rights Reserved.
//
// Use of this source code is governed by a BSD-style license
// that can be found in the LICENSE file in the root of the source
// tree. An additional intellectual property rights grant can be found
// in the file PATENTS.  All contributing project authors may
// be found in the AUTHORS file in the root of the source tree.

#include "mkvparser.hpp"
#include <cassert>
#include <cstring>
#include <new>
//#include <windows.h>
//#include "odbgstream.hpp"
//using std::endl;

mkvparser::IMkvReader::~IMkvReader()
{
}


void mkvparser::GetVersion(int& major, int& minor, int& build, int& revision)
{
    major = 1;
    minor = 0;
    build = 0;
    revision = 4;
}


long long mkvparser::ReadUInt(IMkvReader* pReader, long long pos, long& len)
{
    assert(pReader);
    assert(pos >= 0);

    long long total, available;

    long hr = pReader->Length(&total, &available);
    assert(hr >= 0);
    assert(pos < available);
    assert((available - pos) >= 1);  //assume here max u-int len is 8

    unsigned char b;

    hr = pReader->Read(pos, 1, &b);
    if (hr < 0)
        return hr;

    assert(hr == 0L);

    if (b & 0x80)       //1000 0000
    {
        len = 1;
        b &= 0x7F;      //0111 1111
    }
    else if (b & 0x40)  //0100 0000
    {
        len = 2;
        b &= 0x3F;      //0011 1111
    }
    else if (b & 0x20)  //0010 0000
    {
        len = 3;
        b &= 0x1F;      //0001 1111
    }
    else if (b & 0x10)  //0001 0000
    {
        len = 4;
        b &= 0x0F;      //0000 1111
    }
    else if (b & 0x08)  //0000 1000
    {
        len = 5;
        b &= 0x07;      //0000 0111
    }
    else if (b & 0x04)  //0000 0100
    {
        len = 6;
        b &= 0x03;      //0000 0011
    }
    else if (b & 0x02)  //0000 0010
    {
        len = 7;
        b &= 0x01;      //0000 0001
    }
    else
    {
        assert(b & 0x01);  //0000 0001
        len = 8;
        b = 0;             //0000 0000
    }

    assert((available - pos) >= len);

    long long result = b;
    ++pos;
    for (long i = 1; i < len; ++i)
    {
        hr = pReader->Read(pos, 1, &b);

        if (hr < 0)
            return hr;

        assert(hr == 0L);

        result <<= 8;
        result |= b;

        ++pos;
    }

    return result;
}


long long mkvparser::GetUIntLength(
    IMkvReader* pReader,
    long long pos,
    long& len)
{
    assert(pReader);
    assert(pos >= 0);

    long long total, available;

    long hr = pReader->Length(&total, &available);
    assert(hr >= 0);
    assert(available <= total);

    if (pos >= available)
        return pos;  //too few bytes available

    unsigned char b;

    hr = pReader->Read(pos, 1, &b);

    if (hr < 0)
        return hr;

    assert(hr == 0L);

    if (b == 0)  //we can't handle u-int values larger than 8 bytes
        return E_FILE_FORMAT_INVALID;

    unsigned char m = 0x80;
    len = 1;

    while (!(b & m))
    {
        m >>= 1;
        ++len;
    }

    return 0;  //success
}


long long mkvparser::SyncReadUInt(
    IMkvReader* pReader,
    long long pos,
    long long stop,
    long& len)
{
    assert(pReader);

    if (pos >= stop)
        return E_FILE_FORMAT_INVALID;

    unsigned char b;

    long hr = pReader->Read(pos, 1, &b);

    if (hr < 0)
        return hr;

    if (hr != 0L)
        return E_BUFFER_NOT_FULL;

    if (b == 0)  //we can't handle u-int values larger than 8 bytes
        return E_FILE_FORMAT_INVALID;

    unsigned char m = 0x80;
    len = 1;

    while (!(b & m))
    {
        m >>= 1;
        ++len;
    }

    if ((pos + len) > stop)
        return E_FILE_FORMAT_INVALID;

    long long result = b & (~m);
    ++pos;

    for (int i = 1; i < len; ++i)
    {
        hr = pReader->Read(pos, 1, &b);

        if (hr < 0)
            return hr;

        if (hr != 0L)
            return E_BUFFER_NOT_FULL;

        result <<= 8;
        result |= b;

        ++pos;
    }

    return result;
}


long long mkvparser::UnserializeUInt(
    IMkvReader* pReader,
    long long pos,
    long long size)
{
    assert(pReader);
    assert(pos >= 0);
    assert(size > 0);
    assert(size <= 8);

    long long result = 0;

    for (long long i = 0; i < size; ++i)
    {
        unsigned char b;

        const long hr = pReader->Read(pos, 1, &b);

        if (hr < 0)
            return hr;
        result <<= 8;
        result |= b;

        ++pos;
    }

    return result;
}


float mkvparser::Unserialize4Float(
    IMkvReader* pReader,
    long long pos)
{
    assert(pReader);
    assert(pos >= 0);

    long long total, available;

    long hr = pReader->Length(&total, &available);
    assert(hr >= 0);
    assert(available <= total);
    assert((pos + 4) <= available);

    float result;

    unsigned char* const p = (unsigned char*)&result;
    unsigned char* q = p + 4;

    for (;;)
    {
        hr = pReader->Read(pos, 1, --q);
        assert(hr == 0L);

        if (q == p)
            break;

        ++pos;
    }

    return result;
}


double mkvparser::Unserialize8Double(
    IMkvReader* pReader,
    long long pos)
{
    assert(pReader);
    assert(pos >= 0);

    double result;

    unsigned char* const p = (unsigned char*)&result;
    unsigned char* q = p + 8;

    for (;;)
    {
        const long hr = pReader->Read(pos, 1, --q);
        assert(hr == 0L);

        if (q == p)
            break;

        ++pos;
    }

    return result;
}

signed char mkvparser::Unserialize1SInt(
    IMkvReader* pReader,
    long long pos)
{
    assert(pReader);
    assert(pos >= 0);

    long long total, available;

    long hr = pReader->Length(&total, &available);
    assert(hr == 0);
    assert(available <= total);
    assert(pos < available);

    signed char result;

    hr = pReader->Read(pos, 1, (unsigned char*)&result);
    assert(hr == 0);

    return result;
}

short mkvparser::Unserialize2SInt(
    IMkvReader* pReader,
    long long pos)
{
    assert(pReader);
    assert(pos >= 0);

    long long total, available;

    long hr = pReader->Length(&total, &available);
    assert(hr >= 0);
    assert(available <= total);
    assert((pos + 2) <= available);

    short result;

    unsigned char* const p = (unsigned char*)&result;
    unsigned char* q = p + 2;

    for (;;)
    {
        hr = pReader->Read(pos, 1, --q);
        assert(hr == 0L);

        if (q == p)
            break;

        ++pos;
    }

    return result;
}


bool mkvparser::Match(
    IMkvReader* pReader,
    long long& pos,
    unsigned long id_,
    long long& val)

{
    assert(pReader);
    assert(pos >= 0);

    long long total, available;

    long hr = pReader->Length(&total, &available);
    assert(hr >= 0);
    assert(available <= total);

    long len;

    const long long id = ReadUInt(pReader, pos, len);
    assert(id >= 0);
    assert(len > 0);
    assert(len <= 8);
    assert((pos + len) <= available);

    if ((unsigned long)id != id_)
        return false;

    pos += len;  //consume id

    const long long size = ReadUInt(pReader, pos, len);
    assert(size >= 0);
    assert(size <= 8);
    assert(len > 0);
    assert(len <= 8);
    assert((pos + len) <= available);

    pos += len;  //consume length of size of payload

    val = UnserializeUInt(pReader, pos, size);
    assert(val >= 0);

    pos += size;  //consume size of payload

    return true;
}

bool mkvparser::Match(
    IMkvReader* pReader,
    long long& pos,
    unsigned long id_,
    char*& val)
{
    assert(pReader);
    assert(pos >= 0);

    long long total, available;

    long hr = pReader->Length(&total, &available);
    assert(hr >= 0);
    assert(available <= total);

    long len;

    const long long id = ReadUInt(pReader, pos, len);
    assert(id >= 0);
    assert(len > 0);
    assert(len <= 8);
    assert((pos + len) <= available);

    if ((unsigned long)id != id_)
        return false;

    pos += len;  //consume id

    const long long size_ = ReadUInt(pReader, pos, len);
    assert(size_ >= 0);
    assert(len > 0);
    assert(len <= 8);
    assert((pos + len) <= available);

    pos += len;  //consume length of size of payload
    assert((pos + size_) <= available);

    const size_t size = static_cast<size_t>(size_);
    val = new char[size+1];

    for (size_t i = 0; i < size; ++i)
    {
        char c;

        hr = pReader->Read(pos + i, 1, (unsigned char*)&c);
        assert(hr == 0L);

        val[i] = c;

        if (c == '\0')
            break;

    }

    val[size] = '\0';
    pos += size_;  //consume size of payload

    return true;
}

bool mkvparser::Match(
    IMkvReader* pReader,
    long long& pos,
    unsigned long id_,
    unsigned char*& buf,
    size_t& buflen)
{
    assert(pReader);
    assert(pos >= 0);

    long long total, available;

    long hr = pReader->Length(&total, &available);
    assert(hr >= 0);
    assert(available <= total);

    long len;
    const long long id = ReadUInt(pReader, pos, len);
    assert(id >= 0);
    assert(len > 0);
    assert(len <= 8);
    assert((pos + len) <= available);

    if ((unsigned long)id != id_)
        return false;

    pos += len;  //consume id

    const long long size_ = ReadUInt(pReader, pos, len);
    assert(size_ >= 0);
    assert(len > 0);
    assert(len <= 8);
    assert((pos + len) <= available);

    pos += len;  //consume length of size of payload
    assert((pos + size_) <= available);

    const long buflen_ = static_cast<long>(size_);

    buf = new (std::nothrow) unsigned char[buflen_];
    assert(buf);  //TODO

    hr = pReader->Read(pos, buflen_, buf);
    assert(hr == 0L);

    buflen = buflen_;

    pos += size_;  //consume size of payload
    return true;
}


bool mkvparser::Match(
    IMkvReader* pReader,
    long long& pos,
    unsigned long id_,
    double& val)
{
    assert(pReader);
    assert(pos >= 0);

    long long total, available;

    long hr = pReader->Length(&total, &available);
    assert(hr >= 0);
    assert(available <= total);
    long idlen;
    const long long id = ReadUInt(pReader, pos, idlen);
    assert(id >= 0);  //TODO

    if ((unsigned long)id != id_)
        return false;

    long sizelen;
    const long long size = ReadUInt(pReader, pos + idlen, sizelen);

    switch (size)
    {
        case 4:
        case 8:
            break;
        default:
            return false;
    }

    pos += idlen + sizelen;  //consume id and size fields
    assert((pos + size) <= available);

    if (size == 4)
        val = Unserialize4Float(pReader, pos);
    else
    {
        assert(size == 8);
        val = Unserialize8Double(pReader, pos);
    }

    pos += size;  //consume size of payload

    return true;
}


bool mkvparser::Match(
    IMkvReader* pReader,
    long long& pos,
    unsigned long id_,
    short& val)
{
    assert(pReader);
    assert(pos >= 0);

    long long total, available;

    long hr = pReader->Length(&total, &available);
    assert(hr >= 0);
    assert(available <= total);

    long len;
    const long long id = ReadUInt(pReader, pos, len);
    assert(id >= 0);
    assert((pos + len) <= available);

    if ((unsigned long)id != id_)
        return false;

    pos += len;  //consume id

    const long long size = ReadUInt(pReader, pos, len);
    assert(size <= 2);
    assert((pos + len) <= available);

    pos += len;  //consume length of size of payload
    assert((pos + size) <= available);

    //TODO:
    // Generalize this to work for any size signed int
    if (size == 1)
        val = Unserialize1SInt(pReader, pos);
    else
        val = Unserialize2SInt(pReader, pos);

    pos += size;  //consume size of payload

    return true;
}


namespace mkvparser
{

EBMLHeader::EBMLHeader():
    m_docType(NULL)
{
}

EBMLHeader::~EBMLHeader()
{
    delete[] m_docType;
}

long long EBMLHeader::Parse(
    IMkvReader* pReader,
    long long& pos)
{
    assert(pReader);

    long long total, available;

    long hr = pReader->Length(&total, &available);

    if (hr < 0)
        return hr;

    pos = 0;
    long long end = (1024 < available)? 1024: available;

    for (;;)
    {
        unsigned char b = 0;

        while (pos < end)
        {
            hr = pReader->Read(pos, 1, &b);

            if (hr < 0)
                return hr;

            if (b == 0x1A)
                break;

            ++pos;
        }

        if (b != 0x1A)
        {
            if ((pos >= 1024) ||
                (available >= total) ||
                ((total - available) < 5))
                  return -1;

            return available + 5;  //5 = 4-byte ID + 1st byte of size
        }

        if ((total - pos) < 5)
            return E_FILE_FORMAT_INVALID;

        if ((available - pos) < 5)
            return pos + 5;  //try again later

        long len;

        const long long result = ReadUInt(pReader, pos, len);

        if (result < 0)  //error
            return result;

        if (result == 0x0A45DFA3)  //ReadId masks-off length indicator bits
        {
            assert(len == 4);
            pos += len;
            break;
        }

        ++pos;  //throw away just the 0x1A byte, and try again
    }

    long len;
    long long result = GetUIntLength(pReader, pos, len);

    if (result < 0)  //error
        return result;

    if (result > 0)  //need more data
        return result;

    assert(len > 0);
    assert(len <= 8);

    if ((total -  pos) < len)
        return E_FILE_FORMAT_INVALID;
    if ((available - pos) < len)
        return pos + len;  //try again later

    result = ReadUInt(pReader, pos, len);

    if (result < 0)  //error
        return result;

    pos += len;  //consume u-int

    if ((total - pos) < result)
        return E_FILE_FORMAT_INVALID;

    if ((available - pos) < result)
        return pos + result;

    end = pos + result;

    m_version = 1;
    m_readVersion = 1;
    m_maxIdLength = 4;
    m_maxSizeLength = 8;
    m_docTypeVersion = 1;
    m_docTypeReadVersion = 1;

    while (pos < end)
    {
        if (Match(pReader, pos, 0x0286, m_version))
            ;
        else if (Match(pReader, pos, 0x02F7, m_readVersion))
            ;
        else if (Match(pReader, pos, 0x02F2, m_maxIdLength))
            ;
        else if (Match(pReader, pos, 0x02F3, m_maxSizeLength))
            ;
        else if (Match(pReader, pos, 0x0282, m_docType))
            ;
        else if (Match(pReader, pos, 0x0287, m_docTypeVersion))
            ;
        else if (Match(pReader, pos, 0x0285, m_docTypeReadVersion))
            ;
        else
        {
            result = ReadUInt(pReader, pos, len);
            assert(result > 0);
            assert(len > 0);
            assert(len <= 8);

            pos += len;
            assert(pos < end);

            result = ReadUInt(pReader, pos, len);
            assert(result >= 0);
            assert(len > 0);
            assert(len <= 8);

            pos += len + result;
            assert(pos <= end);
        }
    }

    assert(pos == end);

    return 0;
}


Segment::Segment(
    IMkvReader* pReader,
    long long start,
    long long size) :
    m_pReader(pReader),
    m_start(start),
    m_size(size),
    m_pos(start),
    m_pInfo(NULL),
    m_pTracks(NULL),
    m_pCues(NULL),
    m_clusters(NULL),
    m_clusterCount(0),
    m_clusterPreloadCount(0),
    m_clusterSize(0)
{
}


Segment::~Segment()
{
    const long count = m_clusterCount + m_clusterPreloadCount;

    Cluster** i = m_clusters;
    Cluster** j = m_clusters + count;

    while (i != j)
    {
        Cluster* const p = *i++;
        assert(p);

        delete p;
    }

    delete[] m_clusters;

    delete m_pTracks;
    delete m_pInfo;
    delete m_pCues;
}


long long Segment::CreateInstance(
    IMkvReader* pReader,
    long long pos,
    Segment*& pSegment)
{
    assert(pReader);
    assert(pos >= 0);

    pSegment = NULL;

    long long total, available;

    long hr = pReader->Length(&total, &available);
    assert(hr >= 0);
    assert(available <= total);

    //I would assume that in practice this loop would execute
    //exactly once, but we allow for other elements (e.g. Void)
    //to immediately follow the EBML header.  This is fine for
    //the source filter case (since the entire file is available),
    //but in the splitter case over a network we should probably
    //just give up early.  We could for example decide only to
    //execute this loop a maximum of, say, 10 times.

    while (pos < total)
    {
        //Read ID

        long len;
        long long result = GetUIntLength(pReader, pos, len);

        if (result)  //error, or too few available bytes
            return result;

        if ((pos + len) > total)
            return E_FILE_FORMAT_INVALID;

        if ((pos + len) > available)
            return pos + len;

        //TODO: if we liberalize the behavior of ReadUInt, we can
        //probably eliminate having to use GetUIntLength here.
        const long long id = ReadUInt(pReader, pos, len);

        if (id < 0)  //error
            return id;

        pos += len;  //consume ID

        //Read Size

        result = GetUIntLength(pReader, pos, len);

        if (result)  //error, or too few available bytes
            return result;

        if ((pos + len) > total)
            return E_FILE_FORMAT_INVALID;

        if ((pos + len) > available)
            return pos + len;

        //TODO: if we liberalize the behavior of ReadUInt, we can
        //probably eliminate having to use GetUIntLength here.
        const long long size = ReadUInt(pReader, pos, len);

        if (size < 0)
            return size;

        pos += len;  //consume length of size of element

        //Pos now points to start of payload

        if ((pos + size) > total)
            return E_FILE_FORMAT_INVALID;

        if (id == 0x08538067)  //Segment ID
        {
            pSegment = new  Segment(pReader, pos, size);
            assert(pSegment);  //TODO

            return 0;    //success
        }

        pos += size;  //consume payload
    }

    assert(pos == total);

    pSegment = new Segment(pReader, pos, 0);
    assert(pSegment);  //TODO

    return 0;  //success (sort of)
}


long long Segment::ParseHeaders()
{
    //Outermost (level 0) segment object has been constructed,
    //and pos designates start of payload.  We need to find the
    //inner (level 1) elements.
    long long total, available;

    long hr = m_pReader->Length(&total, &available);
    assert(hr >= 0);
    assert(available <= total);

    const long long stop = m_start + m_size;
    assert(stop <= total);
    assert(m_pos <= stop);

    bool bQuit = false;

    while ((m_pos < stop) && !bQuit)
    {
        long long pos = m_pos;

        long len;
        long long result = GetUIntLength(m_pReader, pos, len);

        if (result)  //error, or too few available bytes
            return result;

        if ((pos + len) > stop)
            return E_FILE_FORMAT_INVALID;

        if ((pos + len) > available)
            return pos + len;

        const long long idpos = pos;
        const long long id = ReadUInt(m_pReader, idpos, len);

        if (id < 0)  //error
            return id;

        pos += len;  //consume ID

        //Read Size
        result = GetUIntLength(m_pReader, pos, len);

        if (result)  //error, or too few available bytes
            return result;

        if ((pos + len) > stop)
            return E_FILE_FORMAT_INVALID;

        if ((pos + len) > available)
            return pos + len;

        const long long size = ReadUInt(m_pReader, pos, len);

        if (size < 0)
            return size;

        pos += len;  //consume length of size of element

        //Pos now points to start of payload

        if ((pos + size) > stop)
            return E_FILE_FORMAT_INVALID;

        //We read EBML elements either in total or nothing at all.

        if ((pos + size) > available)
            return pos + size;

        if (id == 0x0549A966)  //Segment Info ID
        {
            assert(m_pInfo == NULL);

            m_pInfo = new SegmentInfo(this, pos, size);
            assert(m_pInfo);  //TODO
        }
        else if (id == 0x0654AE6B)  //Tracks ID
        {
            assert(m_pTracks == NULL);

            m_pTracks = new Tracks(this, pos, size);
            assert(m_pTracks);  //TODO
        }
        else if (id == 0x0C53BB6B)  //Cues ID
        {
            if (m_pCues == NULL)
            {
                m_pCues = new Cues(this, pos, size);
                assert(m_pCues);  //TODO
            }
        }
        else if (id == 0x014D9B74)  //SeekHead ID
        {
            ParseSeekHead(pos, size);
        }
        else if (id == 0x0F43B675)  //Cluster ID
        {
            bQuit = true;
        }

        if (!bQuit)
            m_pos = pos + size;  //consume payload
    }

    assert(m_pos <= stop);

    if (m_pInfo == NULL)  //TODO: liberalize this behavior
        return E_FILE_FORMAT_INVALID;

    if (m_pTracks == NULL)
        return E_FILE_FORMAT_INVALID;

    return 0;  //success
}


#if 0
long Segment::ParseCluster(Cluster*& pCluster, long long& pos_) const
{
    pCluster = NULL;
    pos_ = -1;

    const long long stop = m_start + m_size;
    assert(m_pos <= stop);

    long long pos = m_pos;
    long long off = -1;

    while (pos < stop)
    {
        long len;
        const long long idpos = pos;

        const long long id = SyncReadUInt(m_pReader, pos, stop, len);

        if (id < 0)  //error
            return static_cast<long>(id);

        if (id == 0)
            return E_FILE_FORMAT_INVALID;

        pos += len;  //consume id
        assert(pos < stop);

        const long long size = SyncReadUInt(m_pReader, pos, stop, len);

        if (size < 0)  //error
            return static_cast<long>(size);

        pos += len;  //consume size
        assert(pos <= stop);

        if (size == 0)  //weird
            continue;

        //pos now points to start of payload

        pos += size;  //consume payload
        assert(pos <= stop);

        if (id == 0x0F43B675)  //Cluster ID
        {
            off = idpos - m_start;  // >= 0 means we found a cluster
            break;
        }
    }

    assert(pos <= stop);

    //Indicate to caller how much of file has been consumed. This is
    //used later in AddCluster to adjust the current parse position
    //(the value cached in the segment object itself) to the
    //file position value just past the cluster we parsed.

    if (off < 0)  //we did not found any more clusters
    {
        pos_ = stop;  //pos_ >= 0 here means EOF (cluster is NULL)
        return 0;     //TODO: confirm this return value
    }

    //We found a cluster.  Now read something, to ensure that it is
    //fully loaded in the network cache.

    if (pos >= stop)  //we parsed the entire segment
    {
        //We did find a cluster, but it was very last element in the segment.
        //Our preference is that the loop above runs 1 1/2 times:
        //the first pass finds the cluster, and the second pass
        //finds the element the follows the cluster.  In this case, however,
        //we reached the end of the file without finding another element,
        //so we didn't actually read anything yet associated with "end of the
        //cluster".  And we must perform an actual read, in order
        //to guarantee that all of the data that belongs to this
        //cluster has been loaded into the network cache.  So instead
        //of reading the next element that follows the cluster, we
        //read the last byte of the cluster (which is also the last
        //byte in the file).

        //Read the last byte of the file. (Reading 0 bytes at pos
        //might work too -- it would depend on how the reader is
        //implemented.  Here we take the more conservative approach,
        //since this makes fewer assumptions about the network
        //reader abstraction.)

        unsigned char b;

        const int result = m_pReader->Read(pos - 1, 1, &b);
        assert(result == 0);

        pos_ = stop;
    }
    else
    {
        long len;
        const long long idpos = pos;

        const long long id = SyncReadUInt(m_pReader, pos, stop, len);

        if (id < 0)  //error
            return static_cast<long>(id);

        if (id == 0)
            return E_BUFFER_NOT_FULL;

        pos += len;  //consume id
        assert(pos < stop);

        const long long size = SyncReadUInt(m_pReader, pos, stop, len);

        if (size < 0)  //error
            return static_cast<long>(size);

        pos_ = idpos;
    }

    //We found a cluster, and it has been completely loaded into the
    //network cache.  (We can guarantee this because we actually read
    //the EBML tag that follows the cluster, or, if we reached EOF,
    //because we actually read the last byte of the cluster).

    Segment* const this_ = const_cast<Segment*>(this);

    pCluster = Cluster::Parse(this_, m_clusterCount, off);
    assert(pCluster);
    assert(pCluster->m_index == m_clusterCount);

    return 0;
}


bool Segment::AddCluster(Cluster* pCluster, long long pos)
{
    assert(pos >= m_start);

    const long long stop = m_start + m_size;
    assert(pos <= stop);

    if (pCluster)
    {
        AppendCluster(pCluster);
        assert(m_clusters);
        assert(m_clusterSize > pCluster->m_index);
        assert(m_clusters[pCluster->m_index] == pCluster);
    }

    m_pos = pos;  //m_pos >= stop is now we know we have all clusters

    return (pos >= stop);
}
#endif


long Segment::LoadCluster()
{
    const long long stop = m_start + m_size;

    while (m_pos < stop)
    {
        long long pos = m_pos;

        long len;

        long long result = GetUIntLength(m_pReader, pos, len);

        if (result < 0)  //error
            return static_cast<long>(result);

        if ((pos + len) > stop)
            return E_FILE_FORMAT_INVALID;

        const long long idpos = pos;
        const long long id = ReadUInt(m_pReader, idpos, len);

        if (id < 0)  //error
            return static_cast<long>(id);

        pos += len;  //consume ID

        //Read Size
        result = GetUIntLength(m_pReader, pos, len);

        if (result < 0)  //error
            return static_cast<long>(result);

        if ((pos + len) > stop)
            return E_FILE_FORMAT_INVALID;

        const long long size = ReadUInt(m_pReader, pos, len);

        if (size < 0)  //error
            return static_cast<long>(size);

        pos += len;  //consume length of size of element

        if (size == 0)  //weird
        {
            m_pos = pos;
            continue;
        }

        //Pos now points to start of payload

        if ((pos + size) > stop)
            return E_FILE_FORMAT_INVALID;

        if (id == 0x0C53BB6B)  //Cues ID
        {
            if (m_pCues == NULL)
            {
                m_pCues = new Cues(this, pos, size);
                assert(m_pCues);  //TODO
            }

            m_pos = pos + size;  //consume payload
            continue;
        }

        if (id != 0x0F43B675)  //Cluster ID
        {
            m_pos = pos + size;  //consume payload
            continue;
        }

        const long idx = m_clusterCount;
        const long long idoff = idpos - m_start;

        if (m_clusterPreloadCount > 0)
        {
            assert(idx < m_clusterSize);

            Cluster* const pCluster = m_clusters[idx];
            assert(pCluster);
            assert(pCluster->m_index < 0);

            const long long off_ = pCluster->m_pos;
            assert(off_);

            const long long off = off_ * ((off_ >= 0) ? 1 : -1);
            assert(idoff <= off);

            if (idoff == off)  //cluster has been preloaded already
            {
                pCluster->m_index = idx;
                ++m_clusterCount;
                --m_clusterPreloadCount;

                m_pos = pos + size;  //consume payload
                break;
            }
        }

        Cluster* const pCluster = Cluster::Parse(this, idx, idoff);
        assert(pCluster);
        assert(pCluster->m_index == idx);

        AppendCluster(pCluster);
        assert(m_clusters);
        assert(idx < m_clusterSize);
        assert(m_clusters[idx] == pCluster);

        m_pos = pos + size;  //consume payload
        break;
    }

    assert(m_pos <= stop);
    return 0;
}


void Segment::AppendCluster(Cluster* pCluster)
{
    assert(pCluster);
    assert(pCluster->m_index >= 0);

    const long count = m_clusterCount + m_clusterPreloadCount;

    long& size = m_clusterSize;
    assert(size >= count);

    const long idx = pCluster->m_index;
    assert(idx == m_clusterCount);

    if (count >= size)
    {
        long n;

        if (size > 0)
            n = 2 * size;
        else if (m_pInfo == 0)
            n = 2048;
        else
        {
            const long long ns = m_pInfo->GetDuration();

            if (ns <= 0)
                n = 2048;
            else
            {
                const long long sec = (ns + 999999999LL) / 1000000000LL;
                n = static_cast<long>(sec);
            }
        }

        Cluster** const qq = new Cluster*[n];
        Cluster** q = qq;

        Cluster** p = m_clusters;
        Cluster** const pp = p + count;

        while (p != pp)
            *q++ = *p++;

        delete[] m_clusters;

        m_clusters = qq;
        size = n;
    }

    if (m_clusterPreloadCount > 0)
    {
        assert(m_clusters);

        Cluster** const p = m_clusters + m_clusterCount;
        assert(*p);
        assert((*p)->m_index < 0);

        Cluster** q = p + m_clusterPreloadCount;
        assert(q < (m_clusters + size));

        for (;;)
        {
            Cluster** const qq = q - 1;
            assert((*qq)->m_index < 0);

            *q = *qq;
            q = qq;

            if (q == p)
                break;
        }
    }

    m_clusters[idx] = pCluster;
    ++m_clusterCount;
}


void Segment::PreloadCluster(Cluster* pCluster, ptrdiff_t idx)
{
    assert(pCluster);
    assert(pCluster->m_index < 0);
    assert(idx >= m_clusterCount);

    const long count = m_clusterCount + m_clusterPreloadCount;

    long& size = m_clusterSize;
    assert(size >= count);

    if (count >= size)
    {
        long n;

        if (size > 0)
            n = 2 * size;
        else if (m_pInfo == 0)
            n = 2048;
        else
        {
            const long long ns = m_pInfo->GetDuration();

            if (ns <= 0)
                n = 2048;
            else
            {
                const long long sec = (ns + 999999999LL) / 1000000000LL;
                n = static_cast<long>(sec);
            }
        }

        Cluster** const qq = new Cluster*[n];
        Cluster** q = qq;

        Cluster** p = m_clusters;
        Cluster** const pp = p + count;

        while (p != pp)
            *q++ = *p++;

        delete[] m_clusters;

        m_clusters = qq;
        size = n;
    }

    assert(m_clusters);

    Cluster** const p = m_clusters + idx;

    Cluster** q = m_clusters + count;
    assert(q >= p);
    assert(q < (m_clusters + size));

    while (q > p)
    {
        Cluster** const qq = q - 1;
        assert((*qq)->m_index < 0);

        *q = *qq;
        q = qq;
    }

    m_clusters[idx] = pCluster;
    ++m_clusterPreloadCount;
}


long Segment::Load()
{
    assert(m_clusters == NULL);
    assert(m_clusterSize == 0);
    assert(m_clusterCount == 0);

    //Outermost (level 0) segment object has been constructed,
    //and pos designates start of payload.  We need to find the
    //inner (level 1) elements.
    const long long stop = m_start + m_size;

#ifdef _DEBUG  //TODO: this is really Microsoft-specific
    {
        long long total, available;

        long hr = m_pReader->Length(&total, &available);
        assert(hr >= 0);
        assert(available >= total);
        assert(stop <= total);
    }
#endif

    while (m_pos < stop)
    {
        long long pos = m_pos;

        long len;

        long long result = GetUIntLength(m_pReader, pos, len);

        if (result < 0)  //error
            return static_cast<long>(result);

        if ((pos + len) > stop)
            return E_FILE_FORMAT_INVALID;

        const long long idpos = pos;
        const long long id = ReadUInt(m_pReader, idpos, len);

        if (id < 0)  //error
            return static_cast<long>(id);

        pos += len;  //consume ID

        //Read Size
        result = GetUIntLength(m_pReader, pos, len);

        if (result < 0)  //error
            return static_cast<long>(result);

        if ((pos + len) > stop)
            return E_FILE_FORMAT_INVALID;

        const long long size = ReadUInt(m_pReader, pos, len);

        if (size < 0)  //error
            return static_cast<long>(size);

        pos += len;  //consume length of size of element

        //Pos now points to start of payload

        if ((pos + size) > stop)
            return E_FILE_FORMAT_INVALID;

        if (id == 0x0F43B675)  //Cluster ID
        {
            const long idx = m_clusterCount;
            const long long off = idpos - m_start;

            Cluster* const pCluster = Cluster::Parse(this, idx, off);
            assert(pCluster);
            assert(pCluster->m_index == idx);

            AppendCluster(pCluster);
            assert(m_clusters);
            assert(m_clusterSize > idx);
            assert(m_clusters[idx] == pCluster);
        }
        else if (id == 0x0C53BB6B)  //Cues ID
        {
            assert(m_pCues == NULL);

            m_pCues = new Cues(this, pos, size);
            assert(m_pCues);  //TODO
        }
        else if (id == 0x0549A966)  //SegmentInfo ID
        {
            assert(m_pInfo == NULL);

            m_pInfo = new  SegmentInfo(this, pos, size);
            assert(m_pInfo);
        }
        else if (id == 0x0654AE6B)  //Tracks ID
        {
            assert(m_pTracks == NULL);

            m_pTracks = new Tracks(this, pos, size);
            assert(m_pTracks);  //TODO
        }

        m_pos = pos + size;  //consume payload
    }

    assert(m_pos >= stop);

    if (m_pInfo == NULL)
        return E_FILE_FORMAT_INVALID;  //TODO: ignore this case?

    if (m_pTracks == NULL)
        return E_FILE_FORMAT_INVALID;

    if (m_clusters == NULL)  //TODO: ignore this case?
        return E_FILE_FORMAT_INVALID;

    //TODO: decide whether we require Cues element
    //if (m_pCues == NULL)
    //   return E_FILE_FORMAT_INVALID;

    return 0;
}


void Segment::ParseSeekHead(long long start, long long size_)
{
    long long pos = start;
    const long long stop = start + size_;

    while (pos < stop)
    {
        long len;

        const long long id = ReadUInt(m_pReader, pos, len);
        assert(id >= 0);  //TODO
        assert((pos + len) <= stop);

        pos += len;  //consume ID

        const long long size = ReadUInt(m_pReader, pos, len);
        assert(size >= 0);
        assert((pos + len) <= stop);

        pos += len;  //consume Size field
        assert((pos + size) <= stop);

        if (id == 0x0DBB)  //SeekEntry ID
            ParseSeekEntry(pos, size);

        pos += size;  //consume payload
        assert(pos <= stop);
    }

    assert(pos == stop);
}


void Segment::ParseCues(long long off)
{
    if (m_pCues)
        return;

    //odbgstream os;
    //os << "Segment::ParseCues (begin)" << endl;

    long long pos = m_start + off;
    const long long stop = m_start + m_size;

    long len;

    long long result = GetUIntLength(m_pReader, pos, len);
    assert(result == 0);
    assert((pos + len) <= stop);

    const long long idpos = pos;

    const long long id = ReadUInt(m_pReader, idpos, len);
    assert(id == 0x0C53BB6B);  //Cues ID

    pos += len;  //consume ID
    assert(pos < stop);

    //Read Size

    result = GetUIntLength(m_pReader, pos, len);
    assert(result == 0);
    assert((pos + len) <= stop);

    const long long size = ReadUInt(m_pReader, pos, len);
    assert(size >= 0);

    pos += len;  //consume length of size of element
    assert((pos + size) <= stop);

    //Pos now points to start of payload

    m_pCues = new Cues(this, pos, size);
    assert(m_pCues);  //TODO

    //os << "Segment::ParseCues (end)" << endl;
}


void Segment::ParseSeekEntry(
   long long start,
   long long size_)
{
    long long pos = start;

    const long long stop = start + size_;

    long len;

    const long long seekIdId = ReadUInt(m_pReader, pos, len);
    //seekIdId;
    assert(seekIdId == 0x13AB);  //SeekID ID
    assert((pos + len) <= stop);

    pos += len;  //consume id

    const long long seekIdSize = ReadUInt(m_pReader, pos, len);
    assert(seekIdSize >= 0);
    assert((pos + len) <= stop);

    pos += len;  //consume size

    const long long seekId = ReadUInt(m_pReader, pos, len);  //payload
    assert(seekId >= 0);
    assert(len == seekIdSize);
    assert((pos + len) <= stop);

    pos += seekIdSize;  //consume payload

    const long long seekPosId = ReadUInt(m_pReader, pos, len);
    //seekPosId;
    assert(seekPosId == 0x13AC);  //SeekPos ID
    assert((pos + len) <= stop);

    pos += len;  //consume id

    const long long seekPosSize = ReadUInt(m_pReader, pos, len);
    assert(seekPosSize >= 0);
    assert((pos + len) <= stop);

    pos += len;  //consume size
    assert((pos + seekPosSize) <= stop);

    const long long seekOff = UnserializeUInt(m_pReader, pos, seekPosSize);
    assert(seekOff >= 0);
    assert(seekOff < m_size);

    pos += seekPosSize;  //consume payload
    assert(pos == stop);

    const long long seekPos = m_start + seekOff;
    assert(seekPos < (m_start + m_size));

    if (seekId == 0x0C53BB6B)  //Cues ID
        ParseCues(seekOff);
}


Cues::Cues(Segment* pSegment, long long start_, long long size_) :
    m_pSegment(pSegment),
    m_start(start_),
    m_size(size_),
    m_cue_points(NULL),
    m_count(0),
    m_preload_count(0),
    m_pos(start_)
{
}


Cues::~Cues()
{
    const size_t n = m_count + m_preload_count;

    CuePoint** p = m_cue_points;
    CuePoint** const q = p + n;

    while (p != q)
    {
        CuePoint* const pCP = *p++;
        assert(pCP);

        delete pCP;
    }

    delete[] m_cue_points;
}


void Cues::Init() const
{
    if (m_cue_points)
        return;

    assert(m_count == 0);
    assert(m_preload_count == 0);

    IMkvReader* const pReader = m_pSegment->m_pReader;

    const long long stop = m_start + m_size;
    long long pos = m_start;

    size_t cue_points_size = 0;

    while (pos < stop)
    {
        const long long idpos = pos;

        long len;

        const long long id = ReadUInt(pReader, pos, len);
        assert(id >= 0);  //TODO
        assert((pos + len) <= stop);

        pos += len;  //consume ID

        const long long size = ReadUInt(pReader, pos, len);
        assert(size >= 0);
        assert((pos + len) <= stop);

        pos += len;  //consume Size field
        assert((pos + size) <= stop);

        if (id == 0x3B)  //CuePoint ID
            PreloadCuePoint(cue_points_size, idpos);

        pos += size;  //consume payload
        assert(pos <= stop);
    }
}


void Cues::PreloadCuePoint(
    size_t& cue_points_size,
    long long pos) const
{
    assert(m_count == 0);

    if (m_preload_count >= cue_points_size)
    {
        size_t n;

        if (cue_points_size > 0)
            n = static_cast<size_t>(2 * cue_points_size);
        else
        {
            const SegmentInfo* const pInfo = m_pSegment->GetInfo();

            if (pInfo == NULL)
                n = 2048;
            else
            {
                const long long ns = pInfo->GetDuration();

                if (ns <= 0)
                    n = 2048;
                else
                {
                    const long long sec = (ns + 999999999LL) / 1000000000LL;
                    n = static_cast<size_t>(sec);
                }
            }
        }

        CuePoint** const qq = new CuePoint*[n];
        CuePoint** q = qq;  //beginning of target

        CuePoint** p = m_cue_points;                //beginning of source
        CuePoint** const pp = p + m_preload_count;  //end of source

        while (p != pp)
            *q++ = *p++;

        delete[] m_cue_points;

        m_cue_points = qq;
        cue_points_size = n;
    }

    CuePoint* const pCP = new CuePoint(m_preload_count, pos);
    m_cue_points[m_preload_count++] = pCP;
}


bool Cues::LoadCuePoint() const
{
    //odbgstream os;
    //os << "Cues::LoadCuePoint" << endl;

    const long long stop = m_start + m_size;

    if (m_pos >= stop)
        return false;  //nothing else to do

    Init();

    IMkvReader* const pReader = m_pSegment->m_pReader;

    while (m_pos < stop)
    {
        const long long idpos = m_pos;

        long len;

        const long long id = ReadUInt(pReader, m_pos, len);
        assert(id >= 0);  //TODO
        assert((m_pos + len) <= stop);

        m_pos += len;  //consume ID

        const long long size = ReadUInt(pReader, m_pos, len);
        assert(size >= 0);
        assert((m_pos + len) <= stop);

        m_pos += len;  //consume Size field
        assert((m_pos + size) <= stop);

        if (id != 0x3B)  //CuePoint ID
        {
            m_pos += size;  //consume payload
            assert(m_pos <= stop);

            continue;
        }

        assert(m_preload_count > 0);

        CuePoint* const pCP = m_cue_points[m_count];
        assert(pCP);
        assert((pCP->GetTimeCode() >= 0) || (-pCP->GetTimeCode() == idpos));

        pCP->Load(pReader);
        ++m_count;
        --m_preload_count;

        m_pos += size;  //consume payload
        assert(m_pos <= stop);

        break;
    }

    return (m_pos < stop);
}


bool Cues::Find(
    long long time_ns,
    const Track* pTrack,
    const CuePoint*& pCP,
    const CuePoint::TrackPosition*& pTP) const
{
    assert(time_ns >= 0);
    assert(pTrack);

    LoadCuePoint();

    assert(m_cue_points);
    assert(m_count > 0);

    CuePoint** const ii = m_cue_points;
    CuePoint** i = ii;

    CuePoint** const jj = ii + m_count + m_preload_count;
    CuePoint** j = jj;

    pCP = *i;
    assert(pCP);

    if (time_ns <= pCP->GetTime(m_pSegment))
    {
        pTP = pCP->Find(pTrack);
        return (pTP != NULL);
    }

    IMkvReader* const pReader = m_pSegment->m_pReader;

    while (i < j)
    {
        //INVARIANT:
        //[ii, i) <= time_ns
        //[i, j)  ?
        //[j, jj) > time_ns

        CuePoint** const k = i + (j - i) / 2;
        assert(k < jj);

        CuePoint* const pCP = *k;
        assert(pCP);

        pCP->Load(pReader);

        const long long t = pCP->GetTime(m_pSegment);

        if (t <= time_ns)
            i = k + 1;
        else
            j = k;

        assert(i <= j);
    }

    assert(i == j);
    assert(i <= jj);
    assert(i > ii);

    pCP = *--i;
    assert(pCP);
    assert(pCP->GetTime(m_pSegment) <= time_ns);

    //TODO: here and elsewhere, it's probably not correct to search
    //for the cue point with this time, and then search for a matching
    //track.  In principle, the matching track could be on some earlier
    //cue point, and with our current algorithm, we'd miss it.  To make
    //this bullet-proof, we'd need to create a secondary structure,
    //with a list of cue points that apply to a track, and then search
    //that track-based structure for a matching cue point.

    pTP = pCP->Find(pTrack);
    return (pTP != NULL);
}


#if 0
bool Cues::FindNext(
    long long time_ns,
    const Track* pTrack,
    const CuePoint*& pCP,
    const CuePoint::TrackPosition*& pTP) const
{
    pCP = 0;
    pTP = 0;

    if (m_count == 0)
        return false;

    assert(m_cue_points);

    const CuePoint* const* const ii = m_cue_points;
    const CuePoint* const* i = ii;

    const CuePoint* const* const jj = ii + m_count;
    const CuePoint* const* j = jj;

    while (i < j)
    {
        //INVARIANT:
        //[ii, i) <= time_ns
        //[i, j)  ?
        //[j, jj) > time_ns

        const CuePoint* const* const k = i + (j - i) / 2;
        assert(k < jj);

        pCP = *k;
        assert(pCP);

        const long long t = pCP->GetTime(m_pSegment);

        if (t <= time_ns)
            i = k + 1;
        else
            j = k;

        assert(i <= j);
    }

    assert(i == j);
    assert(i <= jj);

    if (i >= jj)  //time_ns is greater than max cue point
        return false;

    pCP = *i;
    assert(pCP);
    assert(pCP->GetTime(m_pSegment) > time_ns);

    pTP = pCP->Find(pTrack);
    return (pTP != NULL);
}
#endif


const CuePoint* Cues::GetFirst() const
{
    LoadCuePoint();  //init cues

    const size_t count = m_count + m_preload_count;

    if (count == 0)  //weird
        return NULL;

    CuePoint* const* const pp = m_cue_points;
    assert(pp);

    CuePoint* const pCP = pp[0];
    assert(pCP);
    assert(pCP->GetTimeCode() >= 0);

    return pCP;
}


const CuePoint* Cues::GetLast() const
{
    LoadCuePoint();  //init cues

    const size_t count = m_count + m_preload_count;

    if (count == 0)  //weird
        return NULL;

    const size_t index = count - 1;

    CuePoint* const* const pp = m_cue_points;
    assert(pp);

    CuePoint* const pCP = pp[index];
    assert(pCP);

    pCP->Load(m_pSegment->m_pReader);
    assert(pCP->GetTimeCode() >= 0);

    return pCP;
}


const CuePoint* Cues::GetNext(const CuePoint* pCurr) const
{
    if (pCurr == NULL)
        return NULL;

    assert(pCurr->GetTimeCode() >= 0);
    assert(m_cue_points);
    assert(m_count >= 1);

    const size_t count = m_count + m_preload_count;

    size_t index = pCurr->m_index;
    assert(index < count);

    CuePoint* const* const pp = m_cue_points;
    assert(pp);
    assert(pp[index] == pCurr);

    ++index;

    if (index >= count)
        return NULL;

    CuePoint* const pNext = pp[index];
    assert(pNext);

    pNext->Load(m_pSegment->m_pReader);

    return pNext;
}


const BlockEntry* Cues::GetBlock(
    const CuePoint* pCP,
    const CuePoint::TrackPosition* pTP) const
{
    if (pCP == NULL)
        return NULL;

    if (pTP == NULL)
        return NULL;

    return m_pSegment->GetBlock(*pCP, *pTP);
}


const BlockEntry* Segment::GetBlock(
    const CuePoint& cp,
    const CuePoint::TrackPosition& tp)
{
    Cluster** const ii = m_clusters;
    Cluster** i = ii;

    const long count = m_clusterCount + m_clusterPreloadCount;

    Cluster** const jj = ii + count;
    Cluster** j = jj;

    while (i < j)
    {
        //INVARIANT:
        //[ii, i) < pTP->m_pos
        //[i, j) ?
        //[j, jj)  > pTP->m_pos

        Cluster** const k = i + (j - i) / 2;
        assert(k < jj);

        Cluster* const pCluster = *k;
        assert(pCluster);

        const long long pos_ = pCluster->m_pos;
        assert(pos_);

        const long long pos = pos_ * ((pos_ < 0) ? -1 : 1);

        if (pos < tp.m_pos)
            i = k + 1;
        else if (pos > tp.m_pos)
            j = k;
        else
            return pCluster->GetEntry(cp, tp);
    }

    assert(i == j);

    Cluster* const pCluster = Cluster::Parse(this, -1, tp.m_pos);
    const ptrdiff_t idx = i - m_clusters;

    PreloadCluster(pCluster, idx);
    assert(m_clusters);
    assert(m_clusterPreloadCount > 0);
    assert(m_clusters[idx] == pCluster);

    return pCluster->GetEntry(cp, tp);
}



CuePoint::CuePoint(size_t idx, long long pos) :
    m_index(idx),
    m_timecode(-1 * pos),
    m_track_positions(NULL),
    m_track_positions_count(0)
{
    assert(pos > 0);
}


CuePoint::~CuePoint()
{
    delete[] m_track_positions;
}


void CuePoint::Load(IMkvReader* pReader)
{
    //odbgstream os;
    //os << "CuePoint::Load(begin): timecode=" << m_timecode << endl;

    if (m_timecode >= 0)  //already loaded
        return;

    assert(m_track_positions == NULL);
    assert(m_track_positions_count == 0);

    long long pos_ = -m_timecode;

    long long stop;

    {
        long len;

        const long long id = ReadUInt(pReader, pos_, len);
        assert(id == 0x3B);  //CuePoint ID
        //assert((pos + len) <= stop);

        pos_ += len;  //consume ID

        const long long size = ReadUInt(pReader, pos_, len);
        assert(size >= 0);
        //assert((pos + len) <= stop);

        pos_ += len;  //consume Size field
        //assert((pos + size) <= stop);

        //pos_ now points to start of payload

        stop = pos_ + size;
    }

    long long pos = pos_;

    //First count number of track positions

    while (pos < stop)
    {
        long len;

        const long long id = ReadUInt(pReader, pos, len);
        assert(id >= 0);  //TODO
        assert((pos + len) <= stop);

        pos += len;  //consume ID

        const long long size = ReadUInt(pReader, pos, len);
        assert(size >= 0);
        assert((pos + len) <= stop);

        pos += len;  //consume Size field
        assert((pos + size) <= stop);

        if (id == 0x33)  //CueTime ID
            m_timecode = UnserializeUInt(pReader, pos, size);

        else if (id == 0x37) //CueTrackPosition(s) ID
            ++m_track_positions_count;

        pos += size;  //consume payload
        assert(pos <= stop);
    }

    assert(m_timecode >= 0);
    assert(m_track_positions_count > 0);

    //os << "CuePoint::Load(cont'd): idpos=" << idpos
    //   << " timecode=" << m_timecode
    //   << endl;

    m_track_positions = new TrackPosition[m_track_positions_count];

    //Now parse track positions

    TrackPosition* p = m_track_positions;
    pos = pos_;

    while (pos < stop)
    {
        long len;

        const long long id = ReadUInt(pReader, pos, len);
        assert(id >= 0);  //TODO
        assert((pos + len) <= stop);

        pos += len;  //consume ID

        const long long size = ReadUInt(pReader, pos, len);
        assert(size >= 0);
        assert((pos + len) <= stop);

        pos += len;  //consume Size field
        assert((pos + size) <= stop);

        if (id == 0x37) //CueTrackPosition(s) ID
        {
            TrackPosition& tp = *p++;
            tp.Parse(pReader, pos, size);
        }

        pos += size;  //consume payload
        assert(pos <= stop);
    }

    assert(size_t(p - m_track_positions) == m_track_positions_count);
}



void CuePoint::TrackPosition::Parse(
    IMkvReader* pReader,
    long long start_,
    long long size_)
{
    const long long stop = start_ + size_;
    long long pos = start_;

    m_track = -1;
    m_pos = -1;
    m_block = 1;  //default

    while (pos < stop)
    {
        long len;

        const long long id = ReadUInt(pReader, pos, len);
        assert(id >= 0);  //TODO
        assert((pos + len) <= stop);

        pos += len;  //consume ID

        const long long size = ReadUInt(pReader, pos, len);
        assert(size >= 0);
        assert((pos + len) <= stop);

        pos += len;  //consume Size field
        assert((pos + size) <= stop);

        if (id == 0x77)  //CueTrack ID
            m_track = UnserializeUInt(pReader, pos, size);

        else if (id == 0x71)  //CueClusterPos ID
            m_pos = UnserializeUInt(pReader, pos, size);

        else if (id == 0x1378)  //CueBlockNumber
            m_block = UnserializeUInt(pReader, pos, size);

        pos += size;  //consume payload
        assert(pos <= stop);
    }

    assert(m_pos >= 0);
    //assert(m_track > 0);
    //assert(m_block > 0);
}


const CuePoint::TrackPosition* CuePoint::Find(const Track* pTrack) const
{
    assert(pTrack);

    const long long n = pTrack->GetNumber();

    const TrackPosition* i = m_track_positions;
    const TrackPosition* const j = i + m_track_positions_count;

    while (i != j)
    {
        const TrackPosition& p = *i++;

        if (p.m_track == n)
            return &p;
    }

    return NULL;  //no matching track number found
}


long long CuePoint::GetTimeCode() const
{
    return m_timecode;
}

long long CuePoint::GetTime(Segment* pSegment) const
{
    assert(pSegment);
    assert(m_timecode >= 0);

    const SegmentInfo* const pInfo = pSegment->GetInfo();
    assert(pInfo);

    const long long scale = pInfo->GetTimeCodeScale();
    assert(scale >= 1);

    const long long time = scale * m_timecode;

    return time;
}


long long Segment::Unparsed() const
{
    const long long stop = m_start + m_size;

    const long long result = stop - m_pos;
    assert(result >= 0);

    return result;
}


Cluster* Segment::GetFirst()
{
    if ((m_clusters == NULL) || (m_clusterCount <= 0))
       return &m_eos;

    Cluster* const pCluster = m_clusters[0];
    assert(pCluster);

    return pCluster;
}


Cluster* Segment::GetLast()
{
    if ((m_clusters == NULL) || (m_clusterCount <= 0))
        return &m_eos;

    const long idx = m_clusterCount - 1;

    Cluster* const pCluster = m_clusters[idx];
    assert(pCluster);

    return pCluster;
}


unsigned long Segment::GetCount() const
{
    return m_clusterCount;
}


Cluster* Segment::GetNext(const Cluster* pCurr)
{
    assert(pCurr);
    assert(pCurr != &m_eos);
    assert(m_clusters);

    long idx =  pCurr->m_index;

    if (idx >= 0)
    {
        assert(m_clusterCount > 0);
        assert(idx < m_clusterCount);
        assert(pCurr == m_clusters[idx]);

        ++idx;

        if (idx >= m_clusterCount)
            return &m_eos;  //caller will LoadCluster as desired

        Cluster* const pNext = m_clusters[idx];
        assert(pNext);
        assert(pNext->m_index >= 0);
        assert(pNext->m_index == idx);

        return pNext;
    }

    assert(m_clusterPreloadCount > 0);

    const long long off_ = pCurr->m_pos;
    const long long off = off_ * ((off_ < 0) ? -1 : 1);

    long long pos = m_start + off;
    const long long stop = m_start + m_size;  //end of segment

    {
        long len;

        long long result = GetUIntLength(m_pReader, pos, len);
        assert(result == 0);  //TODO
        assert((pos + len) <= stop);  //TODO

        const long long id = ReadUInt(m_pReader, pos, len);
        assert(id == 0x0F43B675);  //Cluster ID   //TODO

        pos += len;  //consume ID

        //Read Size
        result = GetUIntLength(m_pReader, pos, len);
        assert(result == 0);  //TODO
        assert((pos + len) <= stop);  //TODO

        const long long size = ReadUInt(m_pReader, pos, len);
        assert(size > 0);  //TODO
        assert((pCurr->m_size <= 0) || (pCurr->m_size == size));

        pos += len;  //consume length of size of element
        assert((pos + size) <= stop);  //TODO

        //Pos now points to start of payload

        pos += size;  //consume payload
    }

    long long off_next = 0;

    while (pos < stop)
    {
        long len;

        long long result = GetUIntLength(m_pReader, pos, len);
        assert(result == 0);  //TODO
        assert((pos + len) <= stop);  //TODO

        const long long idpos = pos;  //pos of next (potential) cluster

        const long long id = ReadUInt(m_pReader, idpos, len);
        assert(id > 0);  //TODO

        pos += len;  //consume ID

        //Read Size
        result = GetUIntLength(m_pReader, pos, len);
        assert(result == 0);  //TODO
        assert((pos + len) <= stop);  //TODO

        const long long size = ReadUInt(m_pReader, pos, len);
        assert(size >= 0);  //TODO

        pos += len;  //consume length of size of element
        assert((pos + size) <= stop);  //TODO

        //Pos now points to start of payload

        if (size == 0)  //weird
            continue;

        if (id == 0x0F43B675)  //Cluster ID
        {
            off_next = idpos - m_start;
            break;
        }

        pos += size;  //consume payload
    }

    if (off_next <= 0)
        return 0;

    Cluster** const ii = m_clusters + m_clusterCount;
    Cluster** i = ii;

    Cluster** const jj = ii + m_clusterPreloadCount;
    Cluster** j = jj;

    while (i < j)
    {
        //INVARIANT:
        //[0, i) < pos_next
        //[i, j) ?
        //[j, jj)  > pos_next

        Cluster** const k = i + (j - i) / 2;
        assert(k < jj);

        Cluster* const pNext = *k;
        assert(pNext);
        assert(pNext->m_index < 0);

        const long long pos_ = pNext->m_pos;
        assert(pos_);

        pos = pos_ * ((pos_ < 0) ? -1 : 1);

        if (pos < off_next)
            i = k + 1;
        else if (pos > off_next)
            j = k;
        else
            return pNext;
    }

    assert(i == j);

    Cluster* const pNext = Cluster::Parse(this, -1, off_next);
    const ptrdiff_t idx_next = i - m_clusters;  //insertion position

    PreloadCluster(pNext, idx_next);
    assert(m_clusters);
    assert(idx_next < m_clusterSize);
    assert(m_clusters[idx_next] == pNext);

    return pNext;
}


Cluster* Segment::FindCluster(long long time_ns)
{
    if ((m_clusters == NULL) || (m_clusterCount <= 0))
        return &m_eos;

    {
        Cluster* const pCluster = m_clusters[0];
        assert(pCluster);
        assert(pCluster->m_index == 0);

        if (time_ns <= pCluster->GetTime())
            return pCluster;
    }

    //Binary search of cluster array

    long i = 0;
    long j = m_clusterCount;

    while (i < j)
    {
        //INVARIANT:
        //[0, i) <= time_ns
        //[i, j) ?
        //[j, m_clusterCount)  > time_ns

        const long k = i + (j - i) / 2;
        assert(k < m_clusterCount);

        Cluster* const pCluster = m_clusters[k];
        assert(pCluster);
        assert(pCluster->m_index == k);

        const long long t = pCluster->GetTime();

        if (t <= time_ns)
            i = k + 1;
        else
            j = k;

        assert(i <= j);
    }

    assert(i == j);
    assert(i > 0);
    assert(i <= m_clusterCount);

    const long k = i - 1;

    Cluster* const pCluster = m_clusters[k];
    assert(pCluster);
    assert(pCluster->m_index == k);
    assert(pCluster->GetTime() <= time_ns);

    return pCluster;
}


const BlockEntry* Segment::Seek(
    long long time_ns,
    const Track* pTrack)
{
    assert(pTrack);

    if ((m_clusters == NULL) || (m_clusterCount <= 0))
        return pTrack->GetEOS();

    Cluster** const i = m_clusters;
    assert(i);

    {
        Cluster* const pCluster = *i;
        assert(pCluster);
        assert(pCluster->m_index == 0);  //m_clusterCount > 0
        assert(pCluster->m_pSegment == this);

        if (time_ns <= pCluster->GetTime())
            return pCluster->GetEntry(pTrack);
    }

    Cluster** const j = i + m_clusterCount;

    if (pTrack->GetType() == 2)  //audio
    {
        //TODO: we could decide to use cues for this, as we do for video.
        //But we only use it for video because looking around for a keyframe
        //can get expensive.  Audio doesn't require anything special so a
        //straight cluster search is good enough (we assume).

        Cluster** lo = i;
        Cluster** hi = j;

        while (lo < hi)
        {
            //INVARIANT:
            //[i, lo) <= time_ns
            //[lo, hi) ?
            //[hi, j)  > time_ns

            Cluster** const mid = lo + (hi - lo) / 2;
            assert(mid < hi);

            Cluster* const pCluster = *mid;
            assert(pCluster);
            assert(pCluster->m_index == long(mid - m_clusters));
            assert(pCluster->m_pSegment == this);

            const long long t = pCluster->GetTime();

            if (t <= time_ns)
                lo = mid + 1;
            else
                hi = mid;

            assert(lo <= hi);
        }

        assert(lo == hi);
        assert(lo > i);
        assert(lo <= j);

        Cluster* const pCluster = *--lo;
        assert(pCluster);
        assert(pCluster->GetTime() <= time_ns);

        return pCluster->GetEntry(pTrack);
    }

    assert(pTrack->GetType() == 1);  //video

    Cluster** lo = i;
    Cluster** hi = j;

    while (lo < hi)
    {
        //INVARIANT:
        //[i, lo) <= time_ns
        //[lo, hi) ?
        //[hi, j)  > time_ns

        Cluster** const mid = lo + (hi - lo) / 2;
        assert(mid < hi);

        Cluster* const pCluster = *mid;
        assert(pCluster);

        const long long t = pCluster->GetTime();

        if (t <= time_ns)
            lo = mid + 1;
        else
            hi = mid;

        assert(lo <= hi);
    }

    assert(lo == hi);
    assert(lo > i);
    assert(lo <= j);

    Cluster* pCluster = *--lo;
    assert(pCluster);
    assert(pCluster->GetTime() <= time_ns);

    {
        const BlockEntry* const pBlockEntry = pCluster->GetEntry(pTrack);
        assert(pBlockEntry);

        if (!pBlockEntry->EOS())  //found a keyframe
        {
            const Block* const pBlock = pBlockEntry->GetBlock();
            assert(pBlock);

            //TODO: this isn't necessarily the keyframe we want,
            //since there might another keyframe on this same
            //cluster with a greater timecode that but that is
            //still less than the requested time.  For now we
            //simply return the first keyframe we find.

            if (pBlock->GetTime(pCluster) <= time_ns)
                return pBlockEntry;
        }
    }

    const VideoTrack* const pVideo = static_cast<const VideoTrack*>(pTrack);

    while (lo != i)
    {
        pCluster = *--lo;
        assert(pCluster);
        assert(pCluster->GetTime() <= time_ns);

        const BlockEntry* const pBlockEntry = pCluster->GetMaxKey(pVideo);
        assert(pBlockEntry);

        if (!pBlockEntry->EOS())
            return pBlockEntry;
    }

    //weird: we're on the first cluster, but no keyframe found
    //should never happen but we must return something anyway

    return pTrack->GetEOS();
}


#if 0
bool Segment::SearchCues(
    long long time_ns,
    Track* pTrack,
    Cluster*& pCluster,
    const BlockEntry*& pBlockEntry,
    const CuePoint*& pCP,
    const CuePoint::TrackPosition*& pTP)
{
    if (pTrack->GetType() != 1)  //not video
        return false;  //TODO: for now, just handle video stream

    if (m_pCues == NULL)
        return false;

    if (!m_pCues->Find(time_ns, pTrack, pCP, pTP))
        return false;  //weird

    assert(pCP);
    assert(pTP);
    assert(pTP->m_track == pTrack->GetNumber());

    //We have the cue point and track position we want,
    //so we now need to search for the cluster having
    //the indicated position.

    return GetCluster(pCP, pTP, pCluster, pBlockEntry);
}
#endif


Tracks* Segment::GetTracks() const
{
    return m_pTracks;
}


const SegmentInfo* Segment::GetInfo() const
{
    return m_pInfo;
}


const Cues* Segment::GetCues() const
{
    return m_pCues;
}


long long Segment::GetDuration() const
{
    assert(m_pInfo);
    return m_pInfo->GetDuration();
}


SegmentInfo::SegmentInfo(Segment* pSegment, long long start, long long size_) :
    m_pSegment(pSegment),
    m_start(start),
    m_size(size_),
    m_pMuxingAppAsUTF8(NULL),
    m_pWritingAppAsUTF8(NULL),
    m_pTitleAsUTF8(NULL)
{
    IMkvReader* const pReader = m_pSegment->m_pReader;

    long long pos = start;
    const long long stop = start + size_;

    m_timecodeScale = 1000000;
    m_duration = -1;

    while (pos < stop)
    {
        if (Match(pReader, pos, 0x0AD7B1, m_timecodeScale))
            assert(m_timecodeScale > 0);

        else if (Match(pReader, pos, 0x0489, m_duration))
            assert(m_duration >= 0);

        else if (Match(pReader, pos, 0x0D80, m_pMuxingAppAsUTF8))   //[4D][80]
            assert(m_pMuxingAppAsUTF8);

        else if (Match(pReader, pos, 0x1741, m_pWritingAppAsUTF8))  //[57][41]
            assert(m_pWritingAppAsUTF8);

        else if (Match(pReader, pos, 0x3BA9, m_pTitleAsUTF8))       //[7B][A9]
            assert(m_pTitleAsUTF8);

        else
        {
            long len;

            const long long id = ReadUInt(pReader, pos, len);
            //id;
            assert(id >= 0);
            assert((pos + len) <= stop);

            pos += len;  //consume id
            assert((stop - pos) > 0);

            const long long size = ReadUInt(pReader, pos, len);
            assert(size >= 0);
            assert((pos + len) <= stop);

            pos += len + size;  //consume size and payload
            assert(pos <= stop);
        }
    }

    assert(pos == stop);
}

SegmentInfo::~SegmentInfo()
{
    if (m_pMuxingAppAsUTF8)
    {
        delete[] m_pMuxingAppAsUTF8;
        m_pMuxingAppAsUTF8 = NULL;
    }

    if (m_pWritingAppAsUTF8)
    {
        delete[] m_pWritingAppAsUTF8;
        m_pWritingAppAsUTF8 = NULL;
    }

    if (m_pTitleAsUTF8)
    {
        delete[] m_pTitleAsUTF8;
        m_pTitleAsUTF8 = NULL;
    }
}

long long SegmentInfo::GetTimeCodeScale() const
{
    return m_timecodeScale;
}


long long SegmentInfo::GetDuration() const
{
    if (m_duration < 0)
        return -1;

    assert(m_timecodeScale >= 1);

    const double dd = double(m_duration) * double(m_timecodeScale);
    const long long d = static_cast<long long>(dd);

    return d;
}

const char* SegmentInfo::GetMuxingAppAsUTF8() const
{
    return m_pMuxingAppAsUTF8;
}


const char* SegmentInfo::GetWritingAppAsUTF8() const
{
    return m_pWritingAppAsUTF8;
}

const char* SegmentInfo::GetTitleAsUTF8() const
{
    return m_pTitleAsUTF8;
}

Track::Track(Segment* pSegment, const Info& i) :
    m_pSegment(pSegment),
    m_info(i)
{
}

Track::~Track()
{
    Info& info = const_cast<Info&>(m_info);
    info.Clear();
}

Track::Info::Info():
    type(-1),
    number(-1),
    uid(-1),
    nameAsUTF8(NULL),
    codecId(NULL),
    codecPrivate(NULL),
    codecPrivateSize(0),
    codecNameAsUTF8(NULL)
{
}


void Track::Info::Clear()
{
    delete[] nameAsUTF8;
    nameAsUTF8 = NULL;

    delete[] codecId;
    codecId = NULL;

    delete[] codecPrivate;
    codecPrivate = NULL;

    codecPrivateSize = 0;

    delete[] codecNameAsUTF8;
    codecNameAsUTF8 = NULL;
}

const BlockEntry* Track::GetEOS() const
{
    return &m_eos;
}

long long Track::GetType() const
{
    return m_info.type;
}

long long Track::GetNumber() const
{
    return m_info.number;
}

const char* Track::GetNameAsUTF8() const
{
    return m_info.nameAsUTF8;
}

const char* Track::GetCodecNameAsUTF8() const
{
    return m_info.codecNameAsUTF8;
}


const char* Track::GetCodecId() const
{
    return m_info.codecId;
}

const unsigned char* Track::GetCodecPrivate(size_t& size) const
{
    size = m_info.codecPrivateSize;
    return m_info.codecPrivate;
}


long Track::GetFirst(const BlockEntry*& pBlockEntry) const
{
    Cluster* pCluster = m_pSegment->GetFirst();

    //If Segment::GetFirst returns NULL, then this must be a network
    //download, and we haven't loaded any clusters yet.  In this case,
    //returning NULL from Track::GetFirst means the same thing.

    for (int i = 0; i < 100; ++i)  //arbitrary upper bound
    {
        if (pCluster == NULL)
        {
            pBlockEntry = GetEOS();
            return 1;
        }

        if (pCluster->EOS())
        {
            if (m_pSegment->Unparsed() <= 0)  //all clusters have been loaded
            {
                pBlockEntry = GetEOS();
                return 1;
            }

            pBlockEntry = 0;
            return E_BUFFER_NOT_FULL;
        }

        pBlockEntry = pCluster->GetFirst();

        while (pBlockEntry)
        {
            const Block* const pBlock = pBlockEntry->GetBlock();
            assert(pBlock);

            if (pBlock->GetTrackNumber() == m_info.number)
                return 0;

            pBlockEntry = pCluster->GetNext(pBlockEntry);
        }

        pCluster = m_pSegment->GetNext(pCluster);
    }

    //NOTE: if we get here, it means that we didn't find a block with
    //a matching track number.  We interpret that as an error (which
    //might be too conservative).

    pBlockEntry = GetEOS();  //so we can return a non-NULL value
    return 1;
}


long Track::GetNext(
    const BlockEntry* pCurrEntry,
    const BlockEntry*& pNextEntry) const
{
    assert(pCurrEntry);
    assert(!pCurrEntry->EOS());  //?

    const Block* const pCurrBlock = pCurrEntry->GetBlock();
    assert(pCurrBlock->GetTrackNumber() == m_info.number);

    Cluster* pCluster = pCurrEntry->GetCluster();
    assert(pCluster);
    assert(!pCluster->EOS());

    pNextEntry = pCluster->GetNext(pCurrEntry);

    for (int i = 0; i < 100; ++i)  //arbitrary upper bound to search
    {
        while (pNextEntry)
        {
            const Block* const pNextBlock = pNextEntry->GetBlock();
            assert(pNextBlock);

            if (pNextBlock->GetTrackNumber() == m_info.number)
                return 0;

            pNextEntry = pCluster->GetNext(pNextEntry);
        }

        pCluster = m_pSegment->GetNext(pCluster);

        if (pCluster == NULL)
        {
            pNextEntry = GetEOS();
            return 1;
        }

        if (pCluster->EOS())
        {
            if (m_pSegment->Unparsed() <= 0)   //all clusters have been loaded
            {
                pNextEntry = GetEOS();
                return 1;
            }

            //TODO: there is a potential O(n^2) problem here: we tell the
            //caller to (pre)load another cluster, which he does, but then he
            //calls GetNext again, which repeats the same search.  This is
            //a pathological case, since the only way it can happen is if
            //there exists a long sequence of clusters none of which contain a
            // block from this track.  One way around this problem is for the
            //caller to be smarter when he loads another cluster: don't call
            //us back until you have a cluster that contains a block from this
            //track. (Of course, that's not cheap either, since our caller
            //would have to scan the each cluster as it's loaded, so that
            //would just push back the problem.)

            pNextEntry = NULL;
            return E_BUFFER_NOT_FULL;
        }

        pNextEntry = pCluster->GetFirst();
    }

    //NOTE: if we get here, it means that we didn't find a block with
    //a matching track number after lots of searching, so we give
    //up trying.

    pNextEntry = GetEOS();  //so we can return a non-NULL value
    return 1;
}


Track::EOSBlock::EOSBlock()
{
}


bool Track::EOSBlock::EOS() const
{
    return true;
}


Cluster* Track::EOSBlock::GetCluster() const
{
    return NULL;
}


size_t Track::EOSBlock::GetIndex() const
{
    return 0;
}


const Block* Track::EOSBlock::GetBlock() const
{
    return NULL;
}


bool Track::EOSBlock::IsBFrame() const
{
    return false;
}


VideoTrack::VideoTrack(Segment* pSegment, const Info& i) :
    Track(pSegment, i),
    m_width(-1),
    m_height(-1),
    m_rate(-1)
{
    assert(i.type == 1);
    assert(i.number > 0);

    IMkvReader* const pReader = pSegment->m_pReader;

    const Settings& s = i.settings;
    assert(s.start >= 0);
    assert(s.size >= 0);

    long long pos = s.start;
    assert(pos >= 0);

    const long long stop = pos + s.size;

    while (pos < stop)
    {
#ifdef _DEBUG
        long len;
        const long long id = ReadUInt(pReader, pos, len);
        assert(id >= 0);  //TODO: handle error case
        assert((pos + len) <= stop);
#endif
        if (Match(pReader, pos, 0x30, m_width))
            ;
        else if (Match(pReader, pos, 0x3A, m_height))
            ;
        else if (Match(pReader, pos, 0x0383E3, m_rate))
            ;
        else
        {
            long len;
            const long long id = ReadUInt(pReader, pos, len);
            assert(id >= 0);  //TODO: handle error case
            assert((pos + len) <= stop);

            pos += len;  //consume id

            const long long size = ReadUInt(pReader, pos, len);
            assert(size >= 0);  //TODO: handle error case
            assert((pos + len) <= stop);

            pos += len;  //consume length of size
            assert((pos + size) <= stop);

            //pos now designates start of payload

            pos += size;  //consume payload
            assert(pos <= stop);
        }
    }

    return;
}


bool VideoTrack::VetEntry(const BlockEntry* pBlockEntry) const
{
    assert(pBlockEntry);

    const Block* const pBlock = pBlockEntry->GetBlock();
    assert(pBlock);
    assert(pBlock->GetTrackNumber() == m_info.number);

    return pBlock->IsKey();
}


long long VideoTrack::GetWidth() const
{
    return m_width;
}


long long VideoTrack::GetHeight() const
{
    return m_height;
}


double VideoTrack::GetFrameRate() const
{
    return m_rate;
}


AudioTrack::AudioTrack(Segment* pSegment, const Info& i) :
    Track(pSegment, i),
    m_rate(0.0),
    m_channels(0),
    m_bitDepth(-1)
{
    assert(i.type == 2);
    assert(i.number > 0);

    IMkvReader* const pReader = pSegment->m_pReader;

    const Settings& s = i.settings;
    assert(s.start >= 0);
    assert(s.size >= 0);

    long long pos = s.start;
    assert(pos >= 0);

    const long long stop = pos + s.size;

    while (pos < stop)
    {
#ifdef _DEBUG
        long len;
        const long long id = ReadUInt(pReader, pos, len);
        assert(id >= 0);  //TODO: handle error case
        assert((pos + len) <= stop);
#endif
        if (Match(pReader, pos, 0x35, m_rate))
            ;
        else if (Match(pReader, pos, 0x1F, m_channels))
            ;
        else if (Match(pReader, pos, 0x2264, m_bitDepth))
            ;
        else
        {
            long len;
            const long long id = ReadUInt(pReader, pos, len);
            assert(id >= 0);  //TODO: handle error case
            assert((pos + len) <= stop);

            pos += len;  //consume id

            const long long size = ReadUInt(pReader, pos, len);
            assert(size >= 0);  //TODO: handle error case
            assert((pos + len) <= stop);

            pos += len;  //consume length of size
            assert((pos + size) <= stop);

            //pos now designates start of payload

            pos += size;  //consume payload
            assert(pos <= stop);
        }
    }

    return;
}


bool AudioTrack::VetEntry(const BlockEntry* pBlockEntry) const
{
    assert(pBlockEntry);

    const Block* const pBlock = pBlockEntry->GetBlock();
    assert(pBlock);
    assert(pBlock->GetTrackNumber() == m_info.number);

    return true;
}


double AudioTrack::GetSamplingRate() const
{
    return m_rate;
}


long long AudioTrack::GetChannels() const
{
    return m_channels;
}

long long AudioTrack::GetBitDepth() const
{
    return m_bitDepth;
}

Tracks::Tracks(Segment* pSegment, long long start, long long size_) :
    m_pSegment(pSegment),
    m_start(start),
    m_size(size_),
    m_trackEntries(NULL),
    m_trackEntriesEnd(NULL)
{
    long long stop = m_start + m_size;
    IMkvReader* const pReader = m_pSegment->m_pReader;

    long long pos1 = m_start;
    int count = 0;

    while (pos1 < stop)
    {
        long len;
        const long long id = ReadUInt(pReader, pos1, len);
        assert(id >= 0);
        assert((pos1 + len) <= stop);

        pos1 += len;  //consume id

        const long long size = ReadUInt(pReader, pos1, len);
        assert(size >= 0);
        assert((pos1 + len) <= stop);

        pos1 += len;  //consume length of size

        //pos now desinates start of element
        if (id == 0x2E)  //TrackEntry ID
            ++count;

        pos1 += size;  //consume payload
        assert(pos1 <= stop);
    }

    if (count <= 0)
        return;

    m_trackEntries = new Track*[count];
    m_trackEntriesEnd = m_trackEntries;

    long long pos = m_start;

    while (pos < stop)
    {
        long len;
        const long long id = ReadUInt(pReader, pos, len);
        assert(id >= 0);
        assert((pos + len) <= stop);

        pos += len;  //consume id

        const long long size1 = ReadUInt(pReader, pos, len);
        assert(size1 >= 0);
        assert((pos + len) <= stop);

        pos += len;  //consume length of size

        //pos now desinates start of element

        if (id == 0x2E)  //TrackEntry ID
            ParseTrackEntry(pos, size1, *m_trackEntriesEnd++);

        pos += size1;  //consume payload
        assert(pos <= stop);
    }
}


unsigned long Tracks::GetTracksCount() const
{
    const ptrdiff_t result = m_trackEntriesEnd - m_trackEntries;
    assert(result >= 0);

    return static_cast<unsigned long>(result);
}


void Tracks::ParseTrackEntry(
    long long start,
    long long size,
    Track*& pTrack)
{
    IMkvReader* const pReader = m_pSegment->m_pReader;

    long long pos = start;
    const long long stop = start + size;

    Track::Info i;

    Track::Settings videoSettings;
    videoSettings.start = -1;

    Track::Settings audioSettings;
    audioSettings.start = -1;

    while (pos < stop)
    {
#ifdef _DEBUG
        long len;
        const long long id = ReadUInt(pReader, pos, len);
        len;
        id;
#endif
        if (Match(pReader, pos, 0x57, i.number))
            assert(i.number > 0);
        else if (Match(pReader, pos, 0x33C5, i.uid))
            ;
        else if (Match(pReader, pos, 0x03, i.type))
            ;
        else if (Match(pReader, pos, 0x136E, i.nameAsUTF8))
            assert(i.nameAsUTF8);
        else if (Match(pReader, pos, 0x06, i.codecId))
            ;
        else if (Match(pReader,
                       pos,
                       0x23A2,
                       i.codecPrivate,
                       i.codecPrivateSize))
            ;
        else if (Match(pReader, pos, 0x058688, i.codecNameAsUTF8))
            assert(i.codecNameAsUTF8);
        else
        {
            long len;

            const long long id = ReadUInt(pReader, pos, len);
            assert(id >= 0);  //TODO: handle error case
            assert((pos + len) <= stop);

            pos += len;  //consume id

            const long long size = ReadUInt(pReader, pos, len);
            assert(size >= 0);  //TODO: handle error case
            assert((pos + len) <= stop);

            pos += len;  //consume length of size
            const long long start = pos;

            pos += size;  //consume payload
            assert(pos <= stop);

            if (id == 0x60)
            {
                videoSettings.start = start;
                videoSettings.size = size;
            }
            else if (id == 0x61)
            {
                audioSettings.start = start;
                audioSettings.size = size;
            }
        }
    }

    assert(pos == stop);
    //TODO: propertly vet info.number, to ensure both its existence,
    //and that it is unique among all tracks.
    assert(i.number > 0);

    //TODO: vet settings, to ensure that video settings (0x60)
    //were specified when type = 1, and that audio settings (0x61)
    //were specified when type = 2.
    if (i.type == 1)  //video
    {
        assert(audioSettings.start < 0);
        assert(videoSettings.start >= 0);

        i.settings = videoSettings;

        VideoTrack* const t = new VideoTrack(m_pSegment, i);
        assert(t);  //TODO
        pTrack = t;
    }
    else if (i.type == 2)  //audio
    {
        assert(videoSettings.start < 0);
        assert(audioSettings.start >= 0);

        i.settings = audioSettings;

        AudioTrack* const t = new  AudioTrack(m_pSegment, i);
        assert(t);  //TODO
        pTrack = t;
    }
    else
    {
        // for now we do not support other track types yet.
        // TODO: support other track types
        i.Clear();

        pTrack = NULL;
    }

    return;
}


Tracks::~Tracks()
{
    Track** i = m_trackEntries;
    Track** const j = m_trackEntriesEnd;

    while (i != j)
    {
        Track* const pTrack = *i++;
        delete pTrack;
    }

    delete[] m_trackEntries;
}


Track* Tracks::GetTrackByNumber(unsigned long tn_) const
{
    const long long tn = tn_;

    Track** i = m_trackEntries;
    Track** const j = m_trackEntriesEnd;

    while (i != j)
    {
        Track* const pTrack = *i++;

        if (pTrack == NULL)
            continue;

        if (tn == pTrack->GetNumber())
            return pTrack;
    }

    return NULL;  //not found
}


Track* Tracks::GetTrackByIndex(unsigned long idx) const
{
    const ptrdiff_t count = m_trackEntriesEnd - m_trackEntries;

    if (idx >= static_cast<unsigned long>(count))
         return NULL;

    return m_trackEntries[idx];
}


void Cluster::Load()
{
    assert(m_pSegment);
    assert(m_pos);
    assert(m_size);

    if (m_pos > 0)  //loaded
    {
        assert(m_size > 0);
        assert(m_timecode >= 0);
        return;
    }

    assert(m_pos < 0);  //not loaded yet
    assert(m_size < 0);
    assert(m_timecode < 0);

    IMkvReader* const pReader = m_pSegment->m_pReader;

    m_pos *= -1;                                  //relative to segment
    long long pos = m_pSegment->m_start + m_pos;  //absolute

    long len;

    const long long id_ = ReadUInt(pReader, pos, len);
    assert(id_ >= 0);
    assert(id_ == 0x0F43B675);  //Cluster ID

    pos += len;  //consume id

    const long long size_ = ReadUInt(pReader, pos, len);
    assert(size_ >= 0);

    pos += len;  //consume size

    m_size = size_;
    const long long stop = pos + size_;

    long long timecode = -1;

    while (pos < stop)
    {
        if (Match(pReader, pos, 0x67, timecode))
            break;
        else
        {
            const long long id = ReadUInt(pReader, pos, len);
            assert(id >= 0);  //TODO
            assert((pos + len) <= stop);

            pos += len;  //consume id

            const long long size = ReadUInt(pReader, pos, len);
            assert(size >= 0);  //TODO
            assert((pos + len) <= stop);

            pos += len;  //consume size

            if (id == 0x20)  //BlockGroup ID
                break;

            if (id == 0x23)  //SimpleBlock ID
                break;

            pos += size;  //consume payload
            assert(pos <= stop);
        }
    }

    assert(pos <= stop);
    assert(timecode >= 0);

    m_timecode = timecode;
}


Cluster* Cluster::Parse(
    Segment* pSegment,
    long idx,
    long long off)
{
    assert(pSegment);
    assert(off >= 0);
    assert(off < pSegment->m_size);

    Cluster* const pCluster = new Cluster(pSegment, idx, -off);
    assert(pCluster);

    return pCluster;
}


Cluster::Cluster() :
    m_pSegment(NULL),
    m_index(0),
    m_pos(0),
    m_size(0),
    m_timecode(0),
    m_entries(NULL),
    m_entriesCount(0)
{
}


Cluster::Cluster(
    Segment* pSegment,
    long idx,
    long long off) :
    m_pSegment(pSegment),
    m_index(idx),
    m_pos(off),
    m_size(-1),
    m_timecode(-1),
    m_entries(NULL),
    m_entriesCount(0)
{
}


Cluster::~Cluster()
{
    BlockEntry** i = m_entries;
    BlockEntry** const j = m_entries + m_entriesCount;

    while (i != j)
    {
         BlockEntry* p = *i++;
         assert(p);

         delete p;
    }

    delete[] m_entries;
}


bool Cluster::EOS() const
{
    return (m_pSegment == NULL);
}


void Cluster::LoadBlockEntries()
{
    if (m_entries)
        return;

    assert(m_pSegment);
    assert(m_pos);
    assert(m_size);
    assert(m_entriesCount == 0);

    IMkvReader* const pReader = m_pSegment->m_pReader;

    if (m_pos < 0)
        m_pos *= -1;  //relative to segment

    long long pos = m_pSegment->m_start + m_pos;  //absolute

    {
        long len;

        const long long id = ReadUInt(pReader, pos, len);
        id;
        assert(id >= 0);
        assert(id == 0x0F43B675);  //Cluster ID

        pos += len;  //consume id

        const long long size = ReadUInt(pReader, pos, len);
        assert(size > 0);

        pos += len;  //consume size

        //pos now points to start of payload

        if (m_size >= 0)
            assert(size == m_size);
        else
            m_size = size;
    }

    const long long stop = pos + m_size;
    long long timecode = -1;  //of cluster itself

    //First count the number of entries

    long long idx = pos;  //points to start of payload
    m_entriesCount = 0;

    while (idx < stop)
    {
        if (Match(pReader, idx, 0x67, timecode))
        {
            if (m_timecode >= 0)
                assert(timecode == m_timecode);
            else
                m_timecode = timecode;
        }
        else
        {
            long len;

            const long long id = ReadUInt(pReader, idx, len);
            assert(id >= 0);  //TODO
            assert((idx + len) <= stop);

            idx += len;  //consume id

            const long long size = ReadUInt(pReader, idx, len);
            assert(size >= 0);  //TODO
            assert((idx + len) <= stop);

            idx += len;  //consume size

            if (id == 0x20)  //BlockGroup ID
                ++m_entriesCount;
            else if (id == 0x23)  //SimpleBlock ID
                ++m_entriesCount;

            idx += size;  //consume payload
            assert(idx <= stop);
        }
    }

    assert(idx == stop);
    assert(m_timecode >= 0);

    if (m_entriesCount == 0)  //TODO: handle empty clusters
        return;

    m_entries = new BlockEntry*[m_entriesCount];
    size_t index = 0;

    while (pos < stop)
    {
        if (Match(pReader, pos, 0x67, timecode))
            assert(timecode == m_timecode);
        else
        {
            long len;
            const long long id = ReadUInt(pReader, pos, len);
            assert(id >= 0);  //TODO
            assert((pos + len) <= stop);

            pos += len;  //consume id

            const long long size = ReadUInt(pReader, pos, len);
            assert(size >= 0);  //TODO
            assert((pos + len) <= stop);

            pos += len;  //consume size

            if (id == 0x20)  //BlockGroup ID
                ParseBlockGroup(pos, size, index++);
            else if (id == 0x23)  //SimpleBlock ID
                ParseSimpleBlock(pos, size, index++);

            pos += size;  //consume payload
            assert(pos <= stop);
        }
    }

    assert(pos == stop);
    assert(timecode >= 0);
    assert(index == m_entriesCount);
}



long long Cluster::GetTimeCode()
{
    Load();
    return m_timecode;
}


long long Cluster::GetTime()
{
    const long long tc = GetTimeCode();
    assert(tc >= 0);

    const SegmentInfo* const pInfo = m_pSegment->GetInfo();
    assert(pInfo);

    const long long scale = pInfo->GetTimeCodeScale();
    assert(scale >= 1);

    const long long t = m_timecode * scale;

    return t;
}


long long Cluster::GetFirstTime()
{
    const BlockEntry* const pEntry = GetFirst();

    if (pEntry == NULL)  //empty cluster
        return GetTime();

    const Block* const pBlock = pEntry->GetBlock();
    assert(pBlock);

    return pBlock->GetTime(this);
}


long long Cluster::GetLastTime()
{
    const BlockEntry* const pEntry = GetLast();

    if (pEntry == NULL)  //empty cluster
        return GetTime();

    const Block* const pBlock = pEntry->GetBlock();
    assert(pBlock);

    return pBlock->GetTime(this);
}


void Cluster::ParseBlockGroup(long long start, long long size, size_t index)
{
    assert(m_entries);
    assert(m_entriesCount);
    assert(index < m_entriesCount);

    BlockGroup* const pGroup =
        new (std::nothrow) BlockGroup(this, index, start, size);
    assert(pGroup);  //TODO

    m_entries[index] = pGroup;
}



void Cluster::ParseSimpleBlock(long long start, long long size, size_t index)
{
    assert(m_entries);
    assert(m_entriesCount);
    assert(index < m_entriesCount);

    SimpleBlock* const pSimpleBlock =
        new (std::nothrow) SimpleBlock(this, index, start, size);
    assert(pSimpleBlock);  //TODO

    m_entries[index] = pSimpleBlock;
}


const BlockEntry* Cluster::GetFirst()
{
    LoadBlockEntries();
    //assert(m_entries);
    //assert(m_entriesCount >= 1);

    if ((m_entries == NULL) || (m_entriesCount == 0))
        return NULL;

    const BlockEntry* const pFirst = m_entries[0];
    assert(pFirst);

    return pFirst;
}


const BlockEntry* Cluster::GetLast()
{
    LoadBlockEntries();
    //assert(m_entries);
    //assert(m_entriesCount >= 1);

    if ((m_entries == NULL) || (m_entriesCount == 0))
        return NULL;

    const size_t idx = m_entriesCount - 1;

    const BlockEntry* const pLast = m_entries[idx];
    assert(pLast);

    return pLast;
}


const BlockEntry* Cluster::GetNext(const BlockEntry* pEntry) const
{
    assert(pEntry);
    assert(m_entries);
    assert(m_entriesCount);

    size_t idx = pEntry->GetIndex();
    assert(idx < m_entriesCount);
    assert(m_entries[idx] == pEntry);

    ++idx;

    if (idx >= m_entriesCount)
      return NULL;

    return m_entries[idx];
}


const BlockEntry* Cluster::GetEntry(const Track* pTrack)
{
    assert(pTrack);

    if (m_pSegment == NULL)  //EOS
        return pTrack->GetEOS();

    LoadBlockEntries();

    if ((m_entries == NULL) || (m_entriesCount == 0))
        return NULL;

    BlockEntry** i = m_entries;
    assert(i);

    BlockEntry** const j = i + m_entriesCount;

    while (i != j)
    {
        const BlockEntry* const pEntry = *i++;
        assert(pEntry);
        assert(!pEntry->EOS());

        const Block* const pBlock = pEntry->GetBlock();
        assert(pBlock);

        if (pBlock->GetTrackNumber() != pTrack->GetNumber())
            continue;

        if (pTrack->VetEntry(pEntry))
            return pEntry;
    }

    return pTrack->GetEOS();  //no satisfactory block found
}


const BlockEntry*
Cluster::GetEntry(
    const CuePoint& cp,
    const CuePoint::TrackPosition& tp)
{
    assert(m_pSegment);

    LoadBlockEntries();

    if (m_entries == NULL)
        return NULL;

    const long long count = m_entriesCount;

    if (count <= 0)
        return NULL;

    const long long tc = cp.GetTimeCode();

    if ((tp.m_block > 0) && (tp.m_block <= count))
    {
        const size_t block = static_cast<size_t>(tp.m_block);
        const size_t index = block - 1;

        const BlockEntry* const pEntry = m_entries[index];
        assert(pEntry);
        assert(!pEntry->EOS());

        const Block* const pBlock = pEntry->GetBlock();
        assert(pBlock);

        if ((pBlock->GetTrackNumber() == tp.m_track) &&
            (pBlock->GetTimeCode(this) == tc))
        {
            return pEntry;
        }
    }

    const BlockEntry* const* i = m_entries;
    const BlockEntry* const* const j = i + count;

    while (i != j)
    {
        const BlockEntry* const pEntry = *i++;
        assert(pEntry);
        assert(!pEntry->EOS());

        const Block* const pBlock = pEntry->GetBlock();
        assert(pBlock);

        if (pBlock->GetTrackNumber() != tp.m_track)
            continue;

        const long long tc_ = pBlock->GetTimeCode(this);

        if (tc_ < tc)
            continue;

        if (tc_ > tc)
            return NULL;

        const Tracks* const pTracks = m_pSegment->GetTracks();
        assert(pTracks);

        const long tn = static_cast<long>(tp.m_track);
        const Track* const pTrack = pTracks->GetTrackByNumber(tn);

        if (pTrack == NULL)
            return NULL;

        const long long type = pTrack->GetType();

        if (type == 2)  //audio
            return pEntry;

        if (type != 1)  //not video
            return NULL;

        if (!pBlock->IsKey())
            return NULL;

        return pEntry;
    }

    return NULL;
}


const BlockEntry* Cluster::GetMaxKey(const VideoTrack* pTrack)
{
    assert(pTrack);

    if (m_pSegment == NULL)  //EOS
        return pTrack->GetEOS();

    LoadBlockEntries();
    //assert(m_entries);

    BlockEntry** i = m_entries + m_entriesCount;
    BlockEntry** const j = m_entries;

    while (i != j)
    {
        const BlockEntry* const pEntry = *--i;
        assert(pEntry);
        assert(!pEntry->EOS());

        const Block* const pBlock = pEntry->GetBlock();
        assert(pBlock);

        if (pBlock->GetTrackNumber() != pTrack->GetNumber())
            continue;

        if (pBlock->IsKey())
            return pEntry;
    }

    return pTrack->GetEOS();  //no satisfactory block found
}



BlockEntry::BlockEntry()
{
}


BlockEntry::~BlockEntry()
{
}


SimpleBlock::SimpleBlock(
    Cluster* pCluster,
    size_t idx,
    long long start,
    long long size) :
    m_pCluster(pCluster),
    m_index(idx),
    m_block(start, size, pCluster->m_pSegment->m_pReader)
{
}


bool SimpleBlock::EOS() const
{
    return false;
}


Cluster* SimpleBlock::GetCluster() const
{
    return m_pCluster;
}


size_t SimpleBlock::GetIndex() const
{
    return m_index;
}


const Block* SimpleBlock::GetBlock() const
{
    return &m_block;
}


bool SimpleBlock::IsBFrame() const
{
    return false;
}


BlockGroup::BlockGroup(
    Cluster* pCluster,
    size_t idx,
    long long start,
    long long size_) :
    m_pCluster(pCluster),
    m_index(idx),
    m_prevTimeCode(0),
    m_nextTimeCode(0),
    m_pBlock(NULL)  //TODO: accept multiple blocks within a block group
{
    IMkvReader* const pReader = m_pCluster->m_pSegment->m_pReader;

    long long pos = start;
    const long long stop = start + size_;

    bool bSimpleBlock = false;
    bool bReferenceBlock = false;

    while (pos < stop)
    {
        short t;

        if (Match(pReader, pos, 0x7B, t))
        {
            if (t < 0)
                m_prevTimeCode = t;
            else if (t > 0)
                m_nextTimeCode = t;
            else
                assert(false);

            bReferenceBlock = true;
        }
        else
        {
            long len;
            const long long id = ReadUInt(pReader, pos, len);
            assert(id >= 0);  //TODO
            assert((pos + len) <= stop);

            pos += len;  //consume ID

            const long long size = ReadUInt(pReader, pos, len);
            assert(size >= 0);  //TODO
            assert((pos + len) <= stop);

            pos += len;  //consume size

            switch (id)
            {
                case 0x23:  //SimpleBlock ID
                    bSimpleBlock = true;
                    //YES, FALL THROUGH TO NEXT CASE

                case 0x21:  //Block ID
                    ParseBlock(pos, size);
                    break;

                default:
                    break;
            }

            pos += size;  //consume payload
            assert(pos <= stop);
        }
    }

    assert(pos == stop);
    assert(m_pBlock);

    if (!bSimpleBlock)
        m_pBlock->SetKey(!bReferenceBlock);
}


BlockGroup::~BlockGroup()
{
    delete m_pBlock;
}


void BlockGroup::ParseBlock(long long start, long long size)
{
    IMkvReader* const pReader = m_pCluster->m_pSegment->m_pReader;

    Block* const pBlock = new Block(start, size, pReader);
    assert(pBlock);  //TODO

    //TODO: the Matroska spec says you have multiple blocks within the
    //same block group, with blocks ranked by priority (the flag bits).

    assert(m_pBlock == NULL);
    m_pBlock = pBlock;
}


bool BlockGroup::EOS() const
{
    return false;
}


Cluster* BlockGroup::GetCluster() const
{
    return m_pCluster;
}


size_t BlockGroup::GetIndex() const
{
    return m_index;
}


const Block* BlockGroup::GetBlock() const
{
    return m_pBlock;
}


short BlockGroup::GetPrevTimeCode() const
{
    return m_prevTimeCode;
}


short BlockGroup::GetNextTimeCode() const
{
    return m_nextTimeCode;
}


bool BlockGroup::IsBFrame() const
{
    return (m_nextTimeCode > 0);
}



Block::Block(long long start, long long size_, IMkvReader* pReader) :
    m_start(start),
    m_size(size_)
{
    long long pos = start;
    const long long stop = start + size_;

    long len;

    m_track = ReadUInt(pReader, pos, len);
    assert(m_track > 0);
    assert((pos + len) <= stop);

    pos += len;  //consume track number
    assert((stop - pos) >= 2);

    m_timecode = Unserialize2SInt(pReader, pos);

    pos += 2;
    assert((stop - pos) >= 1);

    const long hr = pReader->Read(pos, 1, &m_flags);
    assert(hr == 0L);

    ++pos;
    assert(pos <= stop);

    m_frameOff = pos;

    const long long frame_size = stop - pos;

    assert(frame_size <= 2147483647L);

    m_frameSize = static_cast<long>(frame_size);
}


long long Block::GetTimeCode(Cluster* pCluster) const
{
    assert(pCluster);

    const long long tc0 = pCluster->GetTimeCode();
    assert(tc0 >= 0);

    const long long tc = tc0 + static_cast<long long>(m_timecode);
    assert(tc >= 0);

    return tc;  //unscaled timecode units
}


long long Block::GetTime(Cluster* pCluster) const
{
    assert(pCluster);

    const long long tc = GetTimeCode(pCluster);

    const Segment* const pSegment = pCluster->m_pSegment;
    const SegmentInfo* const pInfo = pSegment->GetInfo();
    assert(pInfo);

    const long long scale = pInfo->GetTimeCodeScale();
    assert(scale >= 1);

    const long long ns = tc * scale;

    return ns;
}


long long Block::GetTrackNumber() const
{
    return m_track;
}


bool Block::IsKey() const
{
    return ((m_flags & static_cast<unsigned char>(1 << 7)) != 0);
}

unsigned char Block::Flags() const {
    return m_flags;
}

void Block::SetKey(bool bKey)
{
    if (bKey)
        m_flags |= static_cast<unsigned char>(1 << 7);
    else
        m_flags &= 0x7F;
}


long long Block::GetOffset() const
{
  return m_frameOff;
}


long Block::GetSize() const
{
    return m_frameSize;
}


long Block::Read(IMkvReader* pReader, unsigned char* buf) const
{

    assert(pReader);
    assert(buf);

    const long hr = pReader->Read(m_frameOff, m_frameSize, buf);

    return hr;
}


}  //end namespace mkvparser
