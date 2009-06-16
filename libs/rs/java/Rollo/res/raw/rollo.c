#pragma version(1)
#pragma stateVertex(PV)
#pragma stateFragment(PF)
#pragma stateFragmentStore(PFSBackground)

int main(void* con, int ft, int launchID)
{
    int x;

    renderTriangleMesh(con, NAMED_MeshCard);
    return 1;
}

