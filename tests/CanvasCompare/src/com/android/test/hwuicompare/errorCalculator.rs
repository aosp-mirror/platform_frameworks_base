#pragma version(1)
#pragma rs java_package_name(com.android.test.hwuicompare)

int REGION_SIZE;
int WIDTH;
int HEIGHT;

const uchar4 *ideal;
const uchar4 *given;

void countInterestingRegions(const int32_t *v_in, int32_t *v_out) {
    int y = v_in[0];
    v_out[0] = 0;

    for (int x = 0; x < HEIGHT; x += REGION_SIZE) {
        bool interestingRegion = false;
        int regionColor = (int)ideal[y * WIDTH + x];
        for (int i = 0; i < REGION_SIZE && !interestingRegion; i++) {
            for (int j = 0; j < REGION_SIZE && !interestingRegion; j++) {
                interestingRegion |= (int)(ideal[(y + i) * WIDTH + (x + j)]) != regionColor;
            }
        }
        if (interestingRegion) {
            v_out[0]++;
        }
    }
}

void accumulateError(const int32_t *v_in, int32_t *v_out) {
    int startY = v_in[0];
    int error = 0;
    for (int y = startY; y < startY + REGION_SIZE; y++) {
        for (int x = 0; x < HEIGHT; x++) {
            uchar4 idealPixel = ideal[y * WIDTH + x];
            uchar4 givenPixel = given[y * WIDTH + x];
            error += abs(idealPixel.x - givenPixel.x);
            error += abs(idealPixel.y - givenPixel.y);
            error += abs(idealPixel.z - givenPixel.z);
            error += abs(idealPixel.w - givenPixel.w);
        }
    }
    v_out[0] = error;
}

void displayDifference(const uchar4 *v_in, uchar4 *v_out, uint32_t x, uint32_t y) {
    float4 idealPixel = rsUnpackColor8888(ideal[y * WIDTH + x]);
    float4 givenPixel = rsUnpackColor8888(given[y * WIDTH + x]);

    float4 diff = idealPixel - givenPixel;
    float totalDiff = diff.x + diff.y + diff.z + diff.w;
    if (totalDiff < 0) {
        v_out[0] = rsPackColorTo8888(0, 0, clamp(-totalDiff/2.f, 0.f, 1.f));
    } else {
        v_out[0] = rsPackColorTo8888(clamp(totalDiff/2.f, 0.f, 1.f), 0, 0);
    }
}
