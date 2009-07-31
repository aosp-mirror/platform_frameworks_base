// Grass live wallpaper

#pragma version(1)
#pragma stateVertex(default)
#pragma stateFragment(PFBackground)
#pragma stateFragmentStore(PFSBackground)

#define WVGA_PORTRAIT_WIDTH 480.0f
#define WVGA_PORTRAIT_HEIGHT 762.0f

#define RSID_SKY_TEXTURES 0
#define RSID_SKY_TEXTURE_NIGHT 0
#define RSID_SKY_TEXTURE_SUNRISE 1
#define RSID_SKY_TEXTURE_NOON 2
#define RSID_SKY_TEXTURE_SUNSET 3

#define MIDNIGHT 0.0f
#define MORNING 0.375f
#define AFTERNOON 0.6f
#define DUSK 0.8f

float time() {
    return (second() % 60) / 60.0f;
}

void alpha(float a) {
    color(1.0f, 1.0f, 1.0f, a);
}

float norm(float a, float start, float end) {
    return (a - start) / (end - start);
}

void drawNight() {
    bindTexture(NAMED_PFBackground, 0, loadI32(RSID_SKY_TEXTURES, RSID_SKY_TEXTURE_NIGHT));
    // NOTE: Hacky way to draw the night sky
    drawRect(WVGA_PORTRAIT_WIDTH - 512.0f, -32.0f, WVGA_PORTRAIT_WIDTH, 1024.0f - 32.0f, 0.0f);
}

void drawSunrise() {
    bindTexture(NAMED_PFBackground, 0, loadI32(RSID_SKY_TEXTURES, RSID_SKY_TEXTURE_SUNRISE));
    drawRect(0.0f, 0.0f, WVGA_PORTRAIT_WIDTH, WVGA_PORTRAIT_HEIGHT, 0.0f);
}

void drawNoon() {
    bindTexture(NAMED_PFBackground, 0, loadI32(RSID_SKY_TEXTURES, RSID_SKY_TEXTURE_NOON));
    drawRect(0.0f, 0.0f, WVGA_PORTRAIT_WIDTH, WVGA_PORTRAIT_HEIGHT, 0.0f);
}

void drawSunset() {
    bindTexture(NAMED_PFBackground, 0, loadI32(RSID_SKY_TEXTURES, RSID_SKY_TEXTURE_SUNSET));
    drawRect(0.0f, 0.0f, WVGA_PORTRAIT_WIDTH, WVGA_PORTRAIT_HEIGHT, 0.0f);
}

int main(int launchID) {
    float now = time();
    alpha(1.0f);

    if (now >= MIDNIGHT && now < MORNING) {
        drawNight();
        alpha(norm(now, MIDNIGHT, MORNING));
        drawSunrise();
    }
    
    if (now >= MORNING && now < AFTERNOON) {
        drawSunrise();
        alpha(norm(now, MORNING, AFTERNOON));
        drawNoon();
    }

    if (now >= AFTERNOON && now < DUSK) {
        drawNoon();
        alpha(norm(now, AFTERNOON, DUSK));
        drawSunset();
    }
    
    if (now >= DUSK) {
        drawSunset();
        alpha(norm(now, DUSK, 1.0f));
        drawNight();
    }

    return 1;
}
