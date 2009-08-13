

ContextBindRootScript {
	param RsScript sampler
	}

ContextBindProgramFragmentStore {
	param RsProgramFragmentStore pgm
	}

ContextBindProgramFragment {
	param RsProgramFragment pgm
	}

ContextBindProgramVertex {
	param RsProgramVertex pgm
	}

ContextSetDefineF {
    param const char* name
    param float value
    }

ContextSetDefineI32 {
    param const char* name
    param int32_t value
    }

AssignName {
	param void *obj
	param const char *name
	param size_t len
	}

ElementBegin {
}

ElementAddPredefined {
	param RsElementPredefined predef
	}

ElementAdd {
	param RsDataKind dataKind
	param RsDataType dataType
	param bool isNormalized
	param size_t bits
	param const char * name
	}

ElementCreate {
	ret RsElement
	}

ElementGetPredefined {
	param RsElementPredefined predef
	ret RsElement
	}

ElementDestroy {
	param RsElement ve
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

TypeDestroy {
	param RsType p
	}

AllocationCreateTyped {
	param RsType type
	ret RsAllocation
	}

AllocationCreatePredefSized {
	param RsElementPredefined predef
	param size_t count
	ret RsAllocation
	}

AllocationCreateSized {
	param RsElement e
	param size_t count
	ret RsAllocation
	}

AllocationCreateFromFile {
	param const char *file
	param bool genMips
	ret RsAllocation
	}

AllocationCreateFromBitmap {
	param uint32_t width
	param uint32_t height
	param RsElementPredefined dstFmt
	param RsElementPredefined srcFmt
	param bool genMips
	param const void * data
	ret RsAllocation
	}

AllocationCreateFromBitmapBoxed {
	param uint32_t width
	param uint32_t height
	param RsElementPredefined dstFmt
	param RsElementPredefined srcFmt
	param bool genMips
	param const void * data
	ret RsAllocation
	}


AllocationUploadToTexture {
	param RsAllocation alloc
	param uint32_t baseMipLevel
	}

AllocationUploadToBufferObject {
	param RsAllocation alloc
	}

AllocationDestroy {
	param RsAllocation alloc
	}


AllocationData {
	param RsAllocation va
	param const void * data
	}

Allocation1DSubData {
	param RsAllocation va
	param uint32_t xoff
	param uint32_t count
	param const void *data
	}

Allocation2DSubData {
	param RsAllocation va
	param uint32_t xoff
	param uint32_t yoff
	param uint32_t w
	param uint32_t h
	param const void *data
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

Adapter1DDestroy {
	param RsAdapter1D adapter
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

Adapter2DDestroy {
	param RsAdapter2D adapter
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

SamplerBegin {
	}

SamplerSet {
	param RsSamplerParam p
	param RsSamplerValue value
	}

SamplerCreate {
	ret RsSampler
	}

SamplerDestroy {
	param RsSampler s
	}

TriangleMeshBegin {
	param RsElement vertex
	param RsElement index
	}

TriangleMeshAddVertex {
	param const void *vtx
	}

TriangleMeshAddTriangle {
	param uint32_t idx1
	param uint32_t idx2
	param uint32_t idx3
	}

TriangleMeshCreate {
	ret RsTriangleMesh
	}

TriangleMeshDestroy {
	param RsTriangleMesh mesh
	}

TriangleMeshRender {
	param RsTriangleMesh vtm
	}

TriangleMeshRenderRange {
	param RsTriangleMesh vtm
	param uint32_t start
	param uint32_t count
	}

ScriptDestroy {
	param RsScript script
	}

ScriptBindAllocation {
	param RsScript vtm
	param RsAllocation va
	param uint32_t slot
	}


ScriptCBegin {
	}

ScriptSetClearColor {
	param RsScript s
	param float r
	param float g
	param float b
	param float a
	}

ScriptSetTimeZone {
	param RsScript s
	param const char * timeZone
	param uint32_t length
	}

ScriptSetClearDepth {
	param RsScript s
	param float depth
	}

ScriptSetClearStencil {
	param RsScript s
	param uint32_t stencil
	}

ScriptCAddType {
	param RsType type
	}

ScriptCSetRoot {
	param bool isRoot
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

ScriptCSetDefineF {
    param const char* name
    param float value
    }

ScriptCSetDefineI32 {
    param const char* name
    param int32_t value
    }

ProgramFragmentStoreBegin {
	param RsElement in
	param RsElement out
	}

ProgramFragmentStoreColorMask {
	param bool r
	param bool g
	param bool b
	param bool a
	}

ProgramFragmentStoreBlendFunc {
	param RsBlendSrcFunc srcFunc
	param RsBlendDstFunc destFunc
	}

ProgramFragmentStoreDepthMask {
	param bool enable
}

ProgramFragmentStoreDither {
	param bool enable
}

ProgramFragmentStoreDepthFunc {
	param RsDepthFunc func
}

ProgramFragmentStoreCreate {
	ret RsProgramFragmentStore
	}

ProgramFragmentStoreDestroy {
	param RsProgramFragmentStore pfs
	}


ProgramFragmentBegin {
	param RsElement in
	param RsElement out
	}

ProgramFragmentBindTexture {
	param RsProgramFragment pf
	param uint32_t slot
	param RsAllocation a
	}

ProgramFragmentBindSampler {
	param RsProgramFragment pf
	param uint32_t slot
	param RsSampler s
	}

ProgramFragmentSetType {
	param uint32_t slot
	param RsType t
	}

ProgramFragmentSetEnvMode {
	param uint32_t slot
	param RsTexEnvMode env
	}

ProgramFragmentSetTexEnable {
	param uint32_t slot
	param bool enable
	}

ProgramFragmentCreate {
	ret RsProgramFragment
	}

ProgramFragmentDestroy {
	param RsProgramFragment pf
	}


ProgramVertexBegin {
	param RsElement in
	param RsElement out
	}

ProgramVertexCreate {
	ret RsProgramVertex
	}

ProgramVertexBindAllocation {
	param RsProgramVertex vpgm
	param RsAllocation constants
	}

ProgramVertexSetTextureMatrixEnable {
	param bool enable
	}

ProgramVertexAddLight {
	param RsLight light
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

LightDestroy {
	param RsLight light
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

FileOpen {
	ret RsFile
	param const char *name
	param size_t len
	}


SimpleMeshCreate {
	ret RsSimpleMesh
	param RsAllocation prim
	param RsAllocation index
	param RsAllocation *vtx
	param uint32_t vtxCount
	param uint32_t primType
	}

SimpleMeshDestroy {
	param RsSimpleMesh mesh
	}

SimpleMeshBindIndex {
	param RsSimpleMesh mesh
	param RsAllocation idx
	}

SimpleMeshBindPrimitive {
	param RsSimpleMesh mesh
	param RsAllocation prim
	}

SimpleMeshBindVertex {
	param RsSimpleMesh mesh
	param RsAllocation vtx
	param uint32_t slot
	}

