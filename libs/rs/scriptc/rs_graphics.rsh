

extern float rand(float max);

//extern void vec2Rand(float *, float len);
extern float2 vec2Rand(float len);

extern float3 float3Norm(float3);
extern float float3Length(float3);
extern float3 float3Add(float3 lhs, float3 rhs);
extern float3 float3Sub(float3 lhs, float3 rhs);
extern float3 float3Cross(float3 lhs, float3 rhs);
extern float float3Dot(float3 lhs, float3 rhs);
extern float3 float3Scale(float3 v, float scale);

extern float4 float4Add(float4 lhs, float4 rhs);
extern float4 float4Sub(float4 lhs, float4 rhs);
extern float4 float4Cross(float4 lhs, float4 rhs);
extern float float4Dot(float4 lhs, float4 rhs);
extern float4 float4Scale(float4 v, float scale);

    // context
extern void bindProgramFragment(rs_program_fragment);
extern void bindProgramStore(rs_program_store);
extern void bindProgramVertex(rs_program_vertex);

extern void bindSampler(rs_program_fragment, int slot, rs_sampler);
extern void bindSampler(rs_program_fragment, int slot, rs_allocation);

extern void vpLoadModelMatrix(const float *);
extern void vpLoadTextureMatrix(const float *);


// drawing
extern void drawRect(float x1, float y1, float x2, float y2, float z);
extern void drawQuad(float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3, float x4, float y4, float z4);
extern void drawQuadTexCoords(float x1, float y1, float z1, float u1, float v1, float x2, float y2, float z2, float u2, float v2, float x3, float y3, float z3, float u3, float v3, float x4, float y4, float z4, float u4, float v4);
extern void drawSprite(float x, float y, float z, float w, float h);
extern void drawSpriteScreenspace(float x, float y, float z, float w, float h);
extern void drawLine(float x1, float y1, float z1, float x2, float y2, float z2);
extern void drawPoint(float x1, float y1, float z1);
extern void drawSimpleMesh(int ism);
extern void drawSimpleMeshRange(int ism, int start, int len);

// misc
extern void pfClearColor(float, float, float, float);
extern void color(float, float, float, float);
extern void hsb(float, float, float, float);
extern void hsbToRgb(float, float, float, float*);
extern int hsbToAbgr(float, float, float, float);

extern void uploadToTexture(int, int);
extern void uploadToBufferObject(int);

extern int colorFloatRGBAtoUNorm8(float, float, float, float);
extern int colorFloatRGBto565(float, float, float);

extern int getWidth();
extern int getHeight();

extern int sendToClient(void *data, int cmdID, int len, int waitForSpace);

extern uint32_t allocGetDimX(rs_allocation);
extern uint32_t allocGetDimY(rs_allocation);
extern uint32_t allocGetDimZ(rs_allocation);
extern uint32_t allocGetDimLOD(rs_allocation);
extern uint32_t allocGetDimFaces(rs_allocation);

//
extern float normf(float start, float stop, float value);
extern float clampf(float amount, float low, float high);
extern float turbulencef2(float x, float y, float octaves);
extern float turbulencef3(float x, float y, float z, float octaves);

extern uchar4 __attribute__((overloadable)) convertColorTo8888(float r, float g, float b);
extern uchar4 __attribute__((overloadable)) convertColorTo8888(float r, float g, float b, float a);
extern uchar4 __attribute__((overloadable)) convertColorTo8888(float3);
extern uchar4 __attribute__((overloadable)) convertColorTo8888(float4);


