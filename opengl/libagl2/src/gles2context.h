#define _SIZE_T_DEFINED_
typedef unsigned int size_t;

#include <stdio.h>
#include <stdlib.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES/gl.h>
#include <GLES/glext.h>

#include <utils/threads.h>
#include <pthread.h>

#include <cutils/log.h>

#include <assert.h>

#ifdef __arm__
#ifndef __location__
#define __HIERALLOC_STRING_0__(s)   #s
#define __HIERALLOC_STRING_1__(s)   __HIERALLOC_STRING_0__(s)
#define __HIERALLOC_STRING_2__      __HIERALLOC_STRING_1__(__LINE__)
#define __location__                __FILE__ ":" __HIERALLOC_STRING_2__
#endif
#undef assert
#define assert(EXPR) { do { if (!(EXPR)) {LOGD("\n*\n*\n*\n* assert fail: '"#EXPR"' at "__location__"\n*\n*\n*\n*"); exit(EXIT_FAILURE); } } while (false); }
//#define printf LOGD
#else // #ifdef __arm__
//#define LOGD printf
#endif // #ifdef __arm__


#include <pixelflinger2/pixelflinger2_format.h>
#include <pixelflinger2/pixelflinger2.h>

#include <map>

typedef uint8_t                 GGLubyte;               // ub

#define ggl_likely(x)   __builtin_expect(!!(x), 1)
#define ggl_unlikely(x) __builtin_expect(!!(x), 0)

#undef NELEM
#define NELEM(x) (sizeof(x)/sizeof(*(x)))

template<typename T>
inline T max(T a, T b)
{
   return a<b ? b : a;
}

template<typename T>
inline T min(T a, T b)
{
   return a<b ? a : b;
}

struct egl_context_t {
   enum {
      IS_CURRENT      =   0x00010000,
      NEVER_CURRENT   =   0x00020000
   };
   uint32_t            flags;
   EGLDisplay          dpy;
   EGLConfig           config;
   EGLSurface          read;
   EGLSurface          draw;

   unsigned frame;
   clock_t lastSwapTime;
   float accumulateSeconds;
   
   static inline egl_context_t* context(EGLContext ctx);
};

struct GLES2Context;

#ifdef HAVE_ANDROID_OS
#include <bionic_tls.h>
// We have a dedicated TLS slot in bionic
inline void setGlThreadSpecific(GLES2Context *value)
{
   ((uint32_t *)__get_tls())[TLS_SLOT_OPENGL] = (uint32_t)value;
}
inline GLES2Context* getGlThreadSpecific()
{
   return (GLES2Context *)(((unsigned *)__get_tls())[TLS_SLOT_OPENGL]);
}
#else
extern pthread_key_t gGLKey;
inline void setGlThreadSpecific(GLES2Context *value)
{
   pthread_setspecific(gGLKey, value);
}
inline GLES2Context* getGlThreadSpecific()
{
   return static_cast<GLES2Context*>(pthread_getspecific(gGLKey));
}
#endif

struct VBO {
   unsigned size;
   GLenum usage;
   void * data;
};

struct GLES2Context {
   GGLContext rasterizer;
   egl_context_t egl;
   GGLInterface * iface; // shortcut to &rasterizer.interface

   struct VertexState {
      struct VertAttribPointer {
         unsigned size; // number of values per vertex
         GLenum type;  // data type
         unsigned stride; // bytes
         const void * ptr;
bool normalized :
         1;
bool enabled :
         1;
      } attribs [GGL_MAXVERTEXATTRIBS];

      VBO * vbo, * indices;
      std::map<GLuint, VBO *> vbos;
      GLuint free;

      Vector4 defaultAttribs [GGL_MAXVERTEXATTRIBS];
   } vert;

   struct TextureState {
      GGLTexture * tmus[GGL_MAXCOMBINEDTEXTUREIMAGEUNITS];
      int sampler2tmu[GGL_MAXCOMBINEDTEXTUREIMAGEUNITS]; // sampler2tmu[sampler] is index of tmu, -1 means not used
      unsigned active;
      std::map<GLuint, GGLTexture *> textures;
      GLuint free; // first possible free name
      GGLTexture * tex2D, * texCube; // default textures
      unsigned unpack;
      
      void UpdateSampler(GGLInterface * iface, unsigned tmu);
   } tex;

   GLES2Context();
   void InitializeTextures();
   void InitializeVertices();

   ~GLES2Context();
   void UninitializeTextures();
   void UninitializeVertices();

   static inline GLES2Context* get() {
      return getGlThreadSpecific();
   }
};

inline egl_context_t* egl_context_t::context(EGLContext ctx)
{
   GLES2Context* const gl = static_cast<GLES2Context*>(ctx);
   return static_cast<egl_context_t*>(&gl->egl);
}

#define GLES2_GET_CONTEXT(ctx) GLES2Context * ctx = GLES2Context::get(); \
                                 /*puts(__FUNCTION__);*/
#define GLES2_GET_CONST_CONTEXT(ctx) GLES2Context * ctx = GLES2Context::get(); \
                                       /*puts(__FUNCTION__);*/
