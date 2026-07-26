#version 150

uniform sampler2D DiffuseSampler;
uniform float Intensity;
uniform float Time;
uniform vec3 FlashColor;
uniform float Explosive;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 textureSizePixels = vec2(textureSize(DiffuseSampler, 0));
    float aspectRatio = textureSizePixels.x / max(textureSizePixels.y, 1.0);
    vec2 centered = texCoord * 2.0 - 1.0;
    vec2 metric = vec2(centered.x * aspectRatio, centered.y);
    float radius = length(metric);
    vec2 radialDirection = radius > 0.0001 ? metric / radius : vec2(0.0);

    float fisheyeStrength = Intensity * (0.026 + Explosive * 0.018);
    vec2 warpedMetric = metric * (1.0 + fisheyeStrength * radius * radius);

    float shockRadius = min(Time * (2.85 + Explosive * 0.35), 1.55);
    float shockWidth = 0.055 + Explosive * 0.030;
    float shockOffset = radius - shockRadius;
    float shockRing = exp(-(shockOffset * shockOffset) / (shockWidth * shockWidth));
    float shockFade = 1.0 - smoothstep(0.78, 1.55, shockRadius);
    warpedMetric += radialDirection * shockRing * shockFade * Intensity
            * (0.020 + Explosive * 0.014);

    vec2 warpedCentered = vec2(warpedMetric.x / aspectRatio, warpedMetric.y);
    float edgeBlend = smoothstep(0.76, 1.02, max(abs(centered.x), abs(centered.y)));
    warpedCentered = mix(warpedCentered, centered, edgeBlend * 0.52);
    vec2 warpedUv = clamp(warpedCentered * 0.5 + 0.5, vec2(0.001), vec2(0.999));

    float chromaticAmount = Intensity * (0.0022 + Explosive * 0.0018)
            * smoothstep(0.10, 1.20, radius);
    vec2 chromaticOffset = vec2(radialDirection.x / aspectRatio, radialDirection.y)
            * chromaticAmount;
    float red = texture(DiffuseSampler, clamp(warpedUv + chromaticOffset, 0.001, 0.999)).r;
    float green = texture(DiffuseSampler, warpedUv).g;
    float blue = texture(DiffuseSampler, clamp(warpedUv - chromaticOffset, 0.001, 0.999)).b;
    vec3 color = vec3(red, green, blue);

    float centerFlash = exp(-radius * radius * 2.4);
    float exposure = Intensity * (0.055 + centerFlash * 0.085
            + shockRing * shockFade * (0.035 + Explosive * 0.025));
    color *= 1.0 + exposure;
    color += FlashColor * exposure * (0.085 + Explosive * 0.035);

    fragColor = vec4(color, 1.0);
}
