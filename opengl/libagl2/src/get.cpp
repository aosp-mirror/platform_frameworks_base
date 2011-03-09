#include "gles2context.h"

static char const * const gVendorString     = "Android";
static char const * const gRendererString   = "Android PixelFlinger2 0.0";
static char const * const gVersionString    = "OpenGL ES 2.0";
static char const * const gExtensionsString =
//   "GL_OES_byte_coordinates "              // OK
//   "GL_OES_fixed_point "                   // OK
//   "GL_OES_single_precision "              // OK
//   "GL_OES_read_format "                   // OK
//   "GL_OES_compressed_paletted_texture "   // OK
//   "GL_OES_draw_texture "                  // OK
//   "GL_OES_matrix_get "                    // OK
//   "GL_OES_query_matrix "                  // OK
//   //        "GL_OES_point_size_array "              // TODO
//   //        "GL_OES_point_sprite "                  // TODO
//   "GL_OES_EGL_image "                     // OK
//#ifdef GL_OES_compressed_ETC1_RGB8_texture
//   "GL_OES_compressed_ETC1_RGB8_texture "  // OK
//#endif
//   "GL_ARB_texture_compression "           // OK
//   "GL_ARB_texture_non_power_of_two "      // OK
//   "GL_ANDROID_user_clip_plane "           // OK
//   "GL_ANDROID_vertex_buffer_object "      // OK
//   "GL_ANDROID_generate_mipmap "           // OK
   ""
   ;

void glGetIntegerv(GLenum pname, GLint* params)
{
   switch (pname) {
   case GL_MAX_TEXTURE_SIZE :
      *params = 4096; // limit is in precision of texcoord calculation, which uses 16.16
      break;
   case GL_MAX_VERTEX_ATTRIBS:
      *params = GGL_MAXVERTEXATTRIBS;
      break;
   case GL_MAX_VERTEX_UNIFORM_VECTORS:
      *params = GGL_MAXVERTEXUNIFORMVECTORS;
      break;
   case GL_MAX_VARYING_VECTORS:
      *params = GGL_MAXVARYINGVECTORS;
      break;
   case GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS:
      *params = GGL_MAXCOMBINEDTEXTUREIMAGEUNITS;
      break;
   case GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS:
      *params = GGL_MAXVERTEXTEXTUREIMAGEUNITS;
      break;
   case GL_MAX_TEXTURE_IMAGE_UNITS:
      *params = GGL_MAXTEXTUREIMAGEUNITS;
      break;
   case GL_MAX_FRAGMENT_UNIFORM_VECTORS:
      *params = GGL_MAXFRAGMENTUNIFORMVECTORS;
      break;
   case GL_ALIASED_LINE_WIDTH_RANGE:
      *params = 1; // TODO: not implemented
      break;
   default:
      LOGD("agl2: glGetIntegerv 0x%.4X", pname);
      assert(0);
   }
}

const GLubyte* glGetString(GLenum name)
{
   switch (name) {
   case GL_VENDOR:
      return (const GLubyte*)gVendorString;
   case GL_RENDERER:
      return (const GLubyte*)gRendererString;
   case GL_VERSION:
      return (const GLubyte*)gVersionString;
   case GL_EXTENSIONS:
      return (const GLubyte*)gExtensionsString;
   }
   assert(0); //(c, GL_INVALID_ENUM);
   return 0;
}
