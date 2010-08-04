
ContextFinish {
	handcodeApi
	}

ContextBindRootScript {
	param RsScript sampler
	}

ContextBindProgramStore {
	param RsProgramStore pgm
	}

ContextBindProgramFragment {
	param RsProgramFragment pgm
	}

ContextBindProgramVertex {
	param RsProgramVertex pgm
	}

ContextBindProgramRaster {
	param RsProgramRaster pgm
	}

ContextBindFont {
	param RsFont pgm
	}

ContextPause {
	}

ContextResume {
	}

ContextSetSurface {
	param uint32_t width
	param uint32_t height
	param ANativeWindow *sur
	}

ContextDump {
	param int32_t bits
}

ContextGetError {
	param RsError *err
	ret const char *
	}

ContextSetPriority {
	param int32_t priority
	}

AssignName {
	param void *obj
	param const char *name
	param size_t len
	}

GetName {
	param void *obj
	param const char **name
	}

ObjDestroy {
	param void *obj
	}

ElementCreate {
	param RsDataType mType
	param RsDataKind mKind
	param bool mNormalized
	param uint32_t mVectorSize
	ret RsElement
	}

ElementCreate2 {
	param size_t count
	param const RsElement * elements
	param const char ** names
	param const size_t * nameLengths
	ret RsElement
	}

ElementGetNativeData {
	param RsElement elem
	param uint32_t *elemData
	param uint32_t elemDataSize
	}

ElementGetSubElements {
	param RsElement elem
	param uint32_t *ids
	param const char **names
	param uint32_t dataSize
	}

TypeBegin {
	param RsElement type
	}

TypeAdd {
	param RsDimension dim
	param size_t value
	}

TypeCreate {
	ret RsType
	}

TypeGetNativeData {
	param RsType type
	param uint32_t * typeData
	param uint32_t typeDataSize
	}

AllocationCreateTyped {
	param RsType type
	ret RsAllocation
	}

AllocationCreateSized {
	param RsElement e
	param size_t count
	ret RsAllocation
	}

AllocationCreateBitmapRef {
	param RsType type
	param void * bmpPtr
	param void * callbackData
	param RsBitmapCallback_t callback
	ret RsAllocation
	}

AllocationCreateFromBitmap {
	param uint32_t width
	param uint32_t height
	param RsElement dstFmt
	param RsElement srcFmt
	param bool genMips
	param const void * data
	ret RsAllocation
	}

AllocationCreateFromBitmapBoxed {
	param uint32_t width
	param uint32_t height
	param RsElement dstFmt
	param RsElement srcFmt
	param bool genMips
	param const void * data
	ret RsAllocation
	}


AllocationUploadToTexture {
	param RsAllocation alloc
	param bool genMipMaps
	param uint32_t baseMipLevel
	}

AllocationUploadToBufferObject {
	param RsAllocation alloc
	}


AllocationData {
	param RsAllocation va
	param const void * data
	param uint32_t bytes
	handcodeApi
	togglePlay
	}

Allocation1DSubData {
	param RsAllocation va
	param uint32_t xoff
	param uint32_t count
	param const void *data
	param uint32_t bytes
	handcodeApi
	togglePlay
	}

Allocation2DSubData {
	param RsAllocation va
	param uint32_t xoff
	param uint32_t yoff
	param uint32_t w
	param uint32_t h
	param const void *data
	param uint32_t bytes
	}

AllocationRead {
	param RsAllocation va
	param void * data
	}

Adapter1DCreate {
	ret RsAdapter1D
	}

Adapter1DBindAllocation {
	param RsAdapter1D adapt
	param RsAllocation alloc
	}

Adapter1DSetConstraint {
	param RsAdapter1D adapter
	param RsDimension dim
	param uint32_t value
	}

Adapter1DData {
	param RsAdapter1D adapter
	param const void * data
	}

Adapter1DSubData {
	param RsAdapter1D adapter
	param uint32_t xoff
	param uint32_t count
	param const void *data
	}

Adapter2DCreate {
	ret RsAdapter2D
	}

Adapter2DBindAllocation {
	param RsAdapter2D adapt
	param RsAllocation alloc
	}

Adapter2DSetConstraint {
	param RsAdapter2D adapter
	param RsDimension dim
	param uint32_t value
	}

Adapter2DData {
	param RsAdapter2D adapter
	param const void *data
	}

Adapter2DSubData {
	param RsAdapter2D adapter
	param uint32_t xoff
	param uint32_t yoff
	param uint32_t w
	param uint32_t h
	param const void *data
	}

AllocationGetType {
	param RsAllocation va
	ret const void*
	}

SamplerBegin {
	}

SamplerSet {
	param RsSamplerParam p
	param RsSamplerValue value
	}

SamplerCreate {
	ret RsSampler
	}



ScriptBindAllocation {
	param RsScript vtm
	param RsAllocation va
	param uint32_t slot
	}


ScriptCBegin {
	}


ScriptSetTimeZone {
	param RsScript s
	param const char * timeZone
	param uint32_t length
	}


ScriptInvoke {
	param RsScript s
	param uint32_t slot
	}

ScriptInvokeV {
	param RsScript s
	param uint32_t slot
	param const void * data
	param uint32_t dataLen
	handcodeApi
	togglePlay
	}

ScriptSetVarI {
	param RsScript s
	param uint32_t slot
	param int value
	}

ScriptSetVarF {
	param RsScript s
	param uint32_t slot
	param float value
	}

ScriptSetVarV {
	param RsScript s
	param uint32_t slot
	param const void * data
	param uint32_t dataLen
	handcodeApi
	togglePlay
	}


ScriptCSetScript {
	param void * codePtr
	}

ScriptCSetText {
	param const char * text
	param uint32_t length
	}

ScriptCCreate {
	ret RsScript
	}


ProgramStoreBegin {
	param RsElement in
	param RsElement out
	}

ProgramStoreColorMask {
	param bool r
	param bool g
	param bool b
	param bool a
	}

ProgramStoreBlendFunc {
	param RsBlendSrcFunc srcFunc
	param RsBlendDstFunc destFunc
	}

ProgramStoreDepthMask {
	param bool enable
}

ProgramStoreDither {
	param bool enable
}

ProgramStoreDepthFunc {
	param RsDepthFunc func
}

ProgramStoreCreate {
	ret RsProgramStore
	}

ProgramRasterCreate {
	param bool pointSmooth
	param bool lineSmooth
	param bool pointSprite
	ret RsProgramRaster
}

ProgramRasterSetLineWidth {
	param RsProgramRaster pr
	param float lw
}

ProgramRasterSetCullMode {
	param RsProgramRaster pr
	param RsCullMode mode
}

ProgramBindConstants {
	param RsProgram vp
	param uint32_t slot
	param RsAllocation constants
	}


ProgramBindTexture {
	param RsProgramFragment pf
	param uint32_t slot
	param RsAllocation a
	}

ProgramBindSampler {
	param RsProgramFragment pf
	param uint32_t slot
	param RsSampler s
	}

ProgramFragmentCreate {
	param const uint32_t * params
	param uint32_t paramLength
	ret RsProgramFragment
	}

ProgramFragmentCreate2 {
	param const char * shaderText
	param uint32_t shaderLength
	param const uint32_t * params
	param uint32_t paramLength
	ret RsProgramFragment
	}

ProgramVertexCreate {
	param bool texMat
	ret RsProgramVertex
	}

ProgramVertexCreate2 {
	param const char * shaderText
	param uint32_t shaderLength
	param const uint32_t * params
	param uint32_t paramLength
	ret RsProgramVertex
	}

LightBegin {
	}

LightSetLocal {
	param bool isLocal
	}

LightSetMonochromatic {
	param bool isMono
	}

LightCreate {
	ret RsLight light
	}


LightSetPosition {
	param RsLight light
	param float x
	param float y
	param float z
	}

LightSetColor {
	param RsLight light
	param float r
	param float g
	param float b
	}

FileA3DCreateFromAssetStream {
	param const void * data
	param size_t len
	ret RsFile
	}

FileOpen {
	ret RsFile
	param const char *name
	param size_t len
	}

FileA3DGetNumIndexEntries {
	param int32_t * numEntries
	param RsFile file
	}

FileA3DGetIndexEntries {
	param RsFileIndexEntry * fileEntries
	param uint32_t numEntries
	param RsFile fileA3D
	}

FileA3DGetEntryByIndex {
	param uint32_t index
	param RsFile file
	ret RsObjectBase
	}

FontCreateFromFile {
	param const char *name
	param uint32_t fontSize
	param uint32_t dpi
	ret RsFont
	}

MeshCreate {
	ret RsMesh
	param uint32_t vtxCount
	param uint32_t idxCount
	}

MeshBindIndex {
	param RsMesh mesh
	param RsAllocation idx
	param uint32_t primType
	param uint32_t slot
	}

MeshBindVertex {
	param RsMesh mesh
	param RsAllocation vtx
	param uint32_t slot
	}

MeshGetVertexBufferCount {
	param RsMesh mesh
	param int32_t *numVtx
	}

MeshGetIndexCount {
	param RsMesh mesh
	param int32_t *numIdx
	}

MeshGetVertices {
	param RsMesh mv
	param RsAllocation *vtxData
	param uint32_t vtxDataCount
	}

MeshGetIndices {
	param RsMesh mv
	param RsAllocation *va
	param uint32_t *primType
	param uint32_t idxDataCount
	}

AnimationCreate {
	param const float *inValues
	param const float *outValues
	param uint32_t valueCount
	param RsAnimationInterpolation interp
	param RsAnimationEdge pre
	param RsAnimationEdge post
	ret RsAnimation
	}

