#include "mkvparser.hpp"
#include <cassert>
#include <cstring>

mkvparser::IMkvReader::~IMkvReader()
{
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

#if 0
bool mkvparser::Match(
    IMkvReader* pReader,
    long long& pos,
    unsigned long id,
    wchar_t*& val)
{
    char* str;
    
    if (!Match(pReader, pos, id, str))
        return false;

    const size_t size = mbstowcs(NULL, str, 0);
       
    if (size == 0) 
        val = NULL;
    else 
    { 
        val = new wchar_t[size+1];
        mbstowcs(val, str, size);
        val[size] = L'\0';
    }

    delete[] str;
    return true;    
}
#endif


bool mkvparser::Match(
    IMkvReader* pReader,
    long long& pos,
    unsigned long id_,
    unsigned char*& val,
    size_t *optionalSize)
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
    val = new unsigned char[size];
 
    if (optionalSize) {
        *optionalSize = size;
    }

    for (size_t i = 0; i < size; ++i)
    {
        unsigned char b;

        hr = pReader->Read(pos + i, 1, &b);
        assert(hr == 0L);

        val[i] = b; 
    }
    
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
    m_clusterCount(0)
    //m_clusterNumber(0)
{
}


Segment::~Segment()
{
    Cluster** i = m_clusters;
    Cluster** j = m_clusters + m_clusterCount;

    while (i != j)
    {
        Cluster* p = *i++;
        assert(p);		
        delete p;
    } 
    
    delete[] m_clusters;
       
    delete m_pTracks;
    delete m_pInfo;
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
            m_pInfo = new  SegmentInfo(this, pos, size);
            assert(m_pInfo);  //TODO
            
            if (m_pTracks)
                bQuit = true;
        }
        else if (id == 0x0654AE6B)  //Tracks ID
        {
            assert(m_pTracks == NULL);
            m_pTracks = new  Tracks(this, pos, size);
            assert(m_pTracks);  //TODO
            
            if (m_pInfo)
                bQuit = true;
        }
        else if (id == 0x0F43B675)  //Cluster ID
        {
#if 0
            if (m_pInfo == NULL)  //TODO: liberalize
                ;  
            else if (m_pTracks == NULL)
                ;
            else
                //ParseCluster(idpos, pos, size);            
                Cluster::Parse(this, m_clusters, pos, size);
#endif
            bQuit = true;
        }
        
        m_pos = pos + size;  //consume payload
    }
    
    assert(m_pos <= stop);
    
    return 0;  //success
}


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

        if (off >= 0)
        {
            pos_ = idpos;
            break;
        }

        if (id == 0x0F43B675)  //Cluster ID
            off = idpos - m_start;
    }
    
    Segment* const this_ = const_cast<Segment*>(this);
    const size_t idx = m_clusterCount;
    
    if (pos >= stop)
    {
        pos_ = stop;
        
#if 0        
        if (off < 0)
        {
            pCluster = Cluster::CreateEndOfStream(this_, idx);
            return 1L;
        }
#else
        if (off < 0)
            return 1L;
#endif
                
        //Reading 0 bytes at pos might work too -- it would depend 
        //on how the reader is implemented.
        
        unsigned char b;

        const long hr = m_pReader->Read(pos - 1, 1, &b);
        
        if (hr < 0)
            return hr;
            
        if (hr != 0L)
            return E_BUFFER_NOT_FULL;
    }
    
    assert(off >= 0);
    assert(pos_ >= m_start);
    assert(pos_ <= stop);

    pCluster = Cluster::Parse(this_, idx, off);
    return 0L;
}


bool Segment::AddCluster(Cluster* pCluster, long long pos)
{
    assert(pos >= m_start);
    
    const long long stop = m_start + m_size;
    assert(pos <= stop);

    if (pCluster)    
        m_clusters[pos] = pCluster;
        
    m_pos = pos;  //m_pos >= stop is now we know we have all clusters
    
    return (pos >= stop);
}


long Segment::Load()
{
    //Outermost (level 0) segment object has been constructed, 
    //and pos designates start of payload.  We need to find the
    //inner (level 1) elements.
    const long long stop = m_start + m_size;
#ifdef _DEBUG
    {
        long long total, available;
        
        long hr = m_pReader->Length(&total, &available);
        assert(hr >= 0);
        assert(available >= total);
        assert(stop <= total);
    }
#endif
    long long index = m_pos;
    
    m_clusterCount = 0;

    while (index < stop)
    {
        long len = 0;

        long long result = GetUIntLength(m_pReader, index, len);
       
        if (result < 0)  //error
            return static_cast<long>(result);
            
        if ((index + len) > stop)
            return E_FILE_FORMAT_INVALID;
            
        const long long idpos = index;
        const long long id = ReadUInt(m_pReader, idpos, len);
        
        if (id < 0)  //error
            return static_cast<long>(id);
            
        index += len;  //consume ID
        
        //Read Size
        result = GetUIntLength(m_pReader, index, len);
        
        if (result < 0)  //error
            return static_cast<long>(result);
            
        if ((index + len) > stop)
            return E_FILE_FORMAT_INVALID;
            
        const long long size = ReadUInt(m_pReader, index, len);
        
        if (size < 0)  //error
            return static_cast<long>(size);
            
        index += len;  //consume length of size of element
 
        if (id == 0x0F43B675) // Cluster ID 
            break;
	
        if (id == 0x014D9B74) // SeekHead ID 
        {
            ParseSeekHead(index, size, NULL); 
            break;
        }
        index += size;
    }
        
    if (m_clusterCount == 0)
        return -1L;

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
            break;

        if (id == 0x014D9B74)  //SeekHead ID
        {
            m_clusters = new Cluster*[m_clusterCount];   
            size_t index = 0;
            
            ParseSeekHead(pos, size, &index);            
            assert(index == m_clusterCount);
        }            
        else if (id == 0x0549A966)  //Segment Info ID
        {
            assert(m_pInfo == NULL);
            m_pInfo = new  SegmentInfo(this, pos, size);
            assert(m_pInfo);  //TODO
        }
        else if (id == 0x0654AE6B)  //Tracks ID
        {
            assert(m_pTracks == NULL);
            m_pTracks = new Tracks(this, pos, size);
            assert(m_pTracks);  //TODO
        }

        m_pos = pos + size;  //consume payload
    }
    
    assert(m_clusters);
    
    //TODO: see notes above.  This check is here (temporarily) to ensure
    //that the first seekhead has entries for the clusters (because that's
    //when they're loaded).  In case we are given a file that lists the
    //clusters in a second seekhead, the worst thing that happens is that
    //we treat this as an invalid file (which is better then simply
    //asserting somewhere).  But that's only a work-around.  What we need
    //to do is be able to handle having multiple seekheads, and having
    //clusters listed somewhere besides the first seekhead.
    //    
    //if (m_clusters == NULL)
    //    return E_FILE_FORMAT_INVALID;
        
    //NOTE: we stop parsing when we reach the first cluster, under the
    //assumption all clusters are named in some SeekHead.  Clusters
    //will have been (pre)loaded, so we indicate that we have all clusters
    //by adjusting the parse position:
    m_pos = stop;  //means "we have all clusters"

    return 0L;
}


void Segment::ParseSeekHead(long long start, long long size_, size_t* pIndex)
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
            ParseSeekEntry(pos, size, pIndex);
        
        pos += size;  //consume payload
        assert(pos <= stop);
    }
    
    assert(pos == stop);
}


void Segment::ParseSecondarySeekHead(long long off, size_t* pIndex)
{
    assert(off >= 0);
    assert(off < m_size);

    long long pos = m_start + off;
    const long long stop = m_start + m_size;
    
    long len;

    long long result = GetUIntLength(m_pReader, pos, len);
    assert(result == 0);
    assert((pos + len) <= stop);
    
    const long long idpos = pos;

    const long long id = ReadUInt(m_pReader, idpos, len);
    assert(id == 0x014D9B74);  //SeekHead ID
    
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
    
    ParseSeekHead(pos, size, pIndex);
}


void Segment::ParseSeekEntry(long long start, long long size_, size_t* pIndex)
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
   
    if (seekId == 0x0F43B675)  //cluster id
    {       
        if (pIndex == NULL)
            ++m_clusterCount; 
        else
        {
            assert(m_clusters);
            assert(m_clusterCount > 0);
            
            size_t& index = *pIndex;
            assert(index < m_clusterCount);
            
            Cluster*& pCluster = m_clusters[index];
            
            pCluster = Cluster::Parse(this, index, seekOff);
            assert(pCluster);  //TODO
            
            ++index;
        }
    }
    else if (seekId == 0x014D9B74)  //SeekHead ID
    {
        ParseSecondarySeekHead(seekOff, pIndex);
    }
}


long long Segment::Unparsed() const
{
    const long long stop = m_start + m_size;

    const long long result = stop - m_pos;
    assert(result >= 0);
    
    return result;
}


#if 0  //NOTE: too inefficient
long long Segment::Load(long long time_ns)
{
    if (Unparsed() <= 0)
        return 0;
    
    while (m_clusters.empty())
    {
        const long long result = Parse();
        
        if (result)  //error, or not enough bytes available
            return result;
            
        if (Unparsed() <= 0)
            return 0;
    }
    
    while (m_clusters.back()->GetTime() < time_ns)
    {
        const long long result = Parse();
        
        if (result)  //error, or not enough bytes available
            return result;
            
        if (Unparsed() <= 0)
            return 0;
    }        

    return 0;        
}
#endif


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

    const size_t idx = m_clusterCount - 1;    
    Cluster* const pCluster = m_clusters[idx];
    assert(pCluster);
        
    return pCluster;
}


unsigned long Segment::GetCount() const
{
    //TODO: m_clusterCount should not be long long.
    return static_cast<unsigned long>(m_clusterCount);
}


Cluster* Segment::GetNext(const Cluster* pCurr)
{
    assert(pCurr);
    assert(pCurr != &m_eos);
    assert(m_clusters);
    assert(m_clusterCount > 0);

    size_t idx =  pCurr->m_index;
    assert(idx < m_clusterCount);
    assert(pCurr == m_clusters[idx]);
    
    idx++;
    
    if (idx >= m_clusterCount) 
        return &m_eos;
        
    Cluster* const pNext = m_clusters[idx];
    assert(pNext);
    
    return pNext;
}


Cluster* Segment::GetCluster(long long time_ns)
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
       
    size_t i = 0;
    size_t j = m_clusterCount;
    
    while (i < j)
    {
        //INVARIANT:
        //[0, i) <= time_ns
        //[i, j) ?
        //[j, m_clusterCount)  > time_ns
        
        const size_t k = i + (j - i) / 2;
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
    
    const size_t k = i - 1;
    
    Cluster* const pCluster = m_clusters[k];
    assert(pCluster);
    assert(pCluster->m_index == k);
    assert(pCluster->GetTime() <= time_ns);
    
    return pCluster;
}


Tracks* Segment::GetTracks() const
{
    return m_pTracks;
}


const SegmentInfo* const Segment::GetInfo() const
{
    return m_pInfo;
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
    m_duration = 0;
    
    
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
            
        else if (Match(pReader, pos, 0x3BA9, m_pTitleAsUTF8))        //[7B][A9]
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
    assert(m_duration >= 0);    
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

    delete[] codecNameAsUTF8;
    codecNameAsUTF8 = NULL;
}

const BlockEntry* Track::GetEOS() const
{
    return &m_eos;
}

long long Track::GetType() const
{
    const unsigned long result = static_cast<unsigned long>(m_info.type);
    return result;
}

unsigned long Track::GetNumber() const
{
    assert(m_info.number >= 0);
    const unsigned long result = static_cast<unsigned long>(m_info.number);
    return result;
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


const unsigned char* Track::GetCodecPrivate(size_t *optionalSize) const
{
    if (optionalSize) {
        *optionalSize = m_info.codecPrivateSize;
    }
    return m_info.codecPrivate;
}


long Track::GetFirst(const BlockEntry*& pBlockEntry) const
{
    Cluster* const pCluster = m_pSegment->GetFirst();
    
    //If Segment::GetFirst returns NULL, then this must be a network 
    //download, and we haven't loaded any clusters yet.  In this case,
    //returning NULL from Track::GetFirst means the same thing.

    if ((pCluster == NULL) || pCluster->EOS())
    {
        pBlockEntry = NULL;
        return E_BUFFER_NOT_FULL;  //return 1L instead?
    }
        
    pBlockEntry = pCluster->GetFirst();
    
    while (pBlockEntry)
    {
        const Block* const pBlock = pBlockEntry->GetBlock();
        assert(pBlock);
        
        if (pBlock->GetTrackNumber() == (unsigned long)m_info.number)
            return 0L;
            
        pBlockEntry = pCluster->GetNext(pBlockEntry);
    }
    
    //NOTE: if we get here, it means that we didn't find a block with
    //a matching track number.  We interpret that as an error (which
    //might be too conservative).

    pBlockEntry = GetEOS();  //so we can return a non-NULL value
    return 1L;
}


long Track::GetNext(const BlockEntry* pCurrEntry, const BlockEntry*& pNextEntry) const
{
    assert(pCurrEntry);
    assert(!pCurrEntry->EOS());  //?
    assert(pCurrEntry->GetBlock()->GetTrackNumber() == (unsigned long)m_info.number);    
    
    const Cluster* const pCurrCluster = pCurrEntry->GetCluster();
    assert(pCurrCluster);
    assert(!pCurrCluster->EOS());
    
    pNextEntry = pCurrCluster->GetNext(pCurrEntry);
            
    while (pNextEntry)
    {    
        const Block* const pNextBlock = pNextEntry->GetBlock();
        assert(pNextBlock);
    
        if (pNextBlock->GetTrackNumber() == (unsigned long)m_info.number)
            return 0L;
            
        pNextEntry = pCurrCluster->GetNext(pNextEntry);
    }

    Segment* pSegment = pCurrCluster->m_pSegment;    
    Cluster* const pNextCluster = pSegment->GetNext(pCurrCluster);
    
    if ((pNextCluster == NULL) || pNextCluster->EOS())
    {
        if (pSegment->Unparsed() <= 0)   //all clusters have been loaded
        {
            pNextEntry = GetEOS();
            return 1L;
        }
        
        pNextEntry = NULL;
        return E_BUFFER_NOT_FULL;
    }
        
    pNextEntry = pNextCluster->GetFirst();
    
    while (pNextEntry)
    {
        const Block* const pNextBlock = pNextEntry->GetBlock();
        assert(pNextBlock);
        
        if (pNextBlock->GetTrackNumber() == (unsigned long)m_info.number)
            return 0L;
            
        pNextEntry = pNextCluster->GetNext(pNextEntry);
    }
    
    //TODO: what has happened here is that we did not find a block
    //with a matching track number on the next cluster.  It might
    //be the case that some cluster beyond the next cluster 
    //contains a block having a matching track number, but for
    //now we terminate the search immediately.  We do this so that
    //we don't end up searching the entire file looking for the
    //next block.  Another possibility is to try searching for the next
    //block in a small, fixed number of clusters (intead searching
    //just the next one), or to terminate the search when when the
    //there is a large gap in time, or large gap in file position.  It
    //might very well be the case that the approach we use here is
    //unnecessarily conservative.
    
    //TODO: again, here's a case where we need to return the special
    //EOS block.  Or something.  It's OK if pNext is NULL, because
    //we only need it to set the stop time of the media sample.
    //(The start time is determined from pCurr, which is non-NULL
    //and non-EOS.)  The problem is when we set pCurr=pNext; when
    //pCurr has the value NULL we interpret that to mean that we
    //haven't fully initialized pCurr and we attempt to set it to
    //point to the first block for this track.  But that's not what
    //we want at all; we want the next call to PopulateSample to
    //return end-of-stream, not (re)start from the beginning.
    //
    //One work-around is to send EOS immediately.  We would send 
    //the EOS the next pass anyway, so maybe it's no great loss.  The 
    //only problem is that if this the stream really does end one
    //cluster early (relative to other tracks), or the last frame
    //happens to be a keyframe ("CanSeekToEnd").
    //
    //The problem is that we need a way to mark as stream as
    //"at end of stream" without actually being at end of stream.
    //We need to give pCurr some value that means "you've reached EOS".
    //We can't synthesize the special EOS Cluster immediately
    //(when we first open the file, say), because we use the existance
    //of that special cluster value to mean that we've read all of 
    //the clusters (this is a network download, so we can't know apriori
    //how many we have).
    //
    //Or, we could return E_FAIL, and set another bit in the stream
    //object itself, to indicate that it should send EOS earlier
    //than when (pCurr=pStop).
    //
    //Or, probably the best solution, when we actually load the 
    //blocks into a cluster: if we notice that there's no block
    //for a track, we synthesize a nonce EOS block for that track.
    //That way we always have something to return.  But that will
    //only work for sequential scan???

    //pNext = NULL;    
    //return E_FAIL;
    pNextEntry = GetEOS();
    return 1L;
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
    assert(pBlock->GetTrackNumber() == (unsigned long)m_info.number);
    
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
    Track(pSegment, i)
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
    assert(pBlock->GetTrackNumber() == (unsigned long)m_info.number);

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

        else if (Match(pReader, pos, 0x23A2, i.codecPrivate, &i.codecPrivateSize))  
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
        Track* pTrack = *i++;
        delete pTrack;
        pTrack = NULL;    
    }

    delete[] m_trackEntries;
}


Track* Tracks::GetTrackByNumber(unsigned long tn) const
{
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
    
    if (m_start > 0)
    {
        assert(m_size > 0);
        assert(m_timecode >= 0);
        return;
    }
    
    assert(m_size == 0);
    assert(m_timecode < 0);
    
    IMkvReader* const pReader = m_pSegment->m_pReader;

    const long long off = -m_start;  //relative to segment
    long long pos = m_pSegment->m_start + off;  //absolute
    
    long len;

    const long long id_ = ReadUInt(pReader, pos, len);
    assert(id_ >= 0);
    assert(id_ == 0x0F43B675);  //Cluster ID
    
    pos += len;  //consume id
    
    const long long size_ = ReadUInt(pReader, pos, len);
    assert(size_ >= 0);
    
    pos += len;  //consume size
    
    m_start = pos;
    m_size = size_;
    
    const long long stop = m_start + size_;
    
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
    size_t idx,
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
    m_start(0),
    m_size(0),
    m_timecode(0),
    m_pEntries(NULL),
    m_entriesCount(0)
{
}

Cluster::Cluster(
    Segment* pSegment,
    size_t idx,
    long long off) :
    m_pSegment(pSegment),
    m_index(idx),
    m_start(off),
    m_size(0),
    m_timecode(-1),
    m_pEntries(NULL),
    m_entriesCount(0)
{
}


Cluster::~Cluster()
{
#if 0
    while (!m_pEntries.empty())
    {
        BlockEntry* pBlockEntry = m_pEntries.front();
        assert(pBlockEntry);
        
        m_pEntries.pop_front();
        delete pBlockEntry;
    }
#else
    BlockEntry** i = m_pEntries;
    BlockEntry** const j = m_pEntries + m_entriesCount;
    while (i != j)
    {
         BlockEntry* p = *i++;
   
         assert(p);
         delete p;
    }
 
    delete[] m_pEntries;
#endif

}

bool Cluster::EOS() const
{
    return (m_pSegment == 0);
}


void Cluster::LoadBlockEntries()
{
    if (m_pEntries)
        return;

    Load();    
    assert(m_timecode >= 0);
    assert(m_start > 0);
    assert(m_size > 0);
    
    IMkvReader* const pReader = m_pSegment->m_pReader;
    
    long long pos = m_start;
    const long long stop = m_start + m_size;
    long long timecode = -1;
   
    long long idx = pos;

    m_entriesCount = 0;
    
    while (idx < stop)
    {
        if (Match(pReader, idx, 0x67, timecode))
            assert(timecode == m_timecode);
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

    if (m_entriesCount == 0)
        return;
     
    m_pEntries = new BlockEntry*[m_entriesCount];
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


void Cluster::ParseBlockGroup(long long start, long long size, size_t index)
{
    assert(m_pEntries);
    assert(m_entriesCount);
    assert(index < m_entriesCount);
    
    BlockGroup* const pGroup = new BlockGroup(this, index, start, size);
    assert(pGroup);  //TODO
        
    m_pEntries[index] = pGroup;
}



void Cluster::ParseSimpleBlock(long long start, long long size, size_t index)
{
    assert(m_pEntries);
    assert(m_entriesCount);
    assert(index < m_entriesCount);

    SimpleBlock* const pSimpleBlock = new SimpleBlock(this, index, start, size);
    assert(pSimpleBlock);  //TODO
        
    m_pEntries[index] = pSimpleBlock;
}


const BlockEntry* Cluster::GetFirst()
{
    LoadBlockEntries();
    
    return m_pEntries[0];
}

        
const BlockEntry* Cluster::GetLast()
{ 
    if (m_entriesCount == 0)
        return m_pEntries[0];
    
    return m_pEntries[m_entriesCount-1];
}

        
const BlockEntry* Cluster::GetNext(const BlockEntry* pEntry) const
{
    assert(pEntry);
    
    size_t idx = pEntry->GetIndex();
    
    ++idx;

    if (idx == m_entriesCount) 
      return NULL;

    return m_pEntries[idx];

}


const BlockEntry* Cluster::GetEntry(const Track* pTrack)
{

    assert(pTrack);
    
    if (m_pSegment == NULL)  //EOS
        return pTrack->GetEOS();
    
    LoadBlockEntries();
    
    BlockEntry* i = *m_pEntries;
    BlockEntry* j = *m_pEntries + m_entriesCount;
    while (i != j)
    {
        BlockEntry* pEntry = i;
        i++;
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
        m_pBlock->SetKey(m_prevTimeCode >= 0);
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
    //I haven't ever seen such a file (mkvmux certainly doesn't make
    //one), so until then I'll just assume block groups contain a single
    //block.
#if 0    
    m_blocks.push_back(pBlock);
#else
    assert(m_pBlock == NULL);
    m_pBlock = pBlock;
#endif

#if 0
    Track* const pTrack = pBlock->GetTrack();
    assert(pTrack);
    
    pTrack->Insert(pBlock);
#endif
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


unsigned long Block::GetTrackNumber() const
{
    assert(m_track > 0);
    
    return static_cast<unsigned long>(m_track);
}


bool Block::IsKey() const
{
    return ((m_flags & static_cast<unsigned char>(1 << 7)) != 0);
}


void Block::SetKey(bool bKey)
{
    if (bKey)
        m_flags |= static_cast<unsigned char>(1 << 7);
    else
        m_flags &= 0x7F;
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
