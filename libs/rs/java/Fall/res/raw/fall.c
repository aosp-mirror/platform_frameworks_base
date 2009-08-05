#pragma version(1)
#pragma stateVertex(PVBackground)
#pragma stateFragment(PFBackground)
#pragma stateFragmentStore(PSBackground)

int main(int index) {
    color(1.0f, 0.0f, 0.0f, 1.0f);
    drawTriangleMesh(NAMED_mesh);    

    return 1;
}
