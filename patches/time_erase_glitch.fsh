#version 150

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform float Progress;
uniform float ElapsedTime;

in vec2 texCoord;
in vec2 oneTexel;
out vec4 fragColor;

float hash11(float p) {
    return fract(sin(p * 127.1 + 311.7) * 43758.5453123);
}

float smoothBand(float distanceToCenter, float halfHeight) {
    return 1.0 - smoothstep(halfHeight, halfHeight + 0.0028, distanceToCenter);
}

void main() {
    vec2 uv = texCoord;
    float p = clamp(Progress, 0.0, 1.0);
    float envelope = pow(max(0.0, sin(p * 3.14159265)), 0.72);
    float time = ElapsedTime;

    // A slight global horizontal tear anchors the individual slices.
    float globalTear = sin(uv.y * 31.0 + time * 8.0) * 0.0045 * envelope;
    vec2 sampleUv = uv + vec2(globalTear, 0.0);

    vec3 original = texture(DiffuseSampler, clamp(sampleUv, 0.001, 0.999)).rgb;
    vec3 tornColor = original;
    float totalMask = 0.0;
    float magentaEnergy = 0.0;
    float violetEnergy = 0.0;

    // Broken horizontal strips with independently changing lengths, heights,
    // vertical offsets and horizontal displacement. Their uneven overlap gives
    // the image the jagged, layered space-tear look from the supplied reference.
    for (int i = 0; i < 30; i++) {
        float fi = float(i);
        float seed = fi * 19.73 + 4.17;
        float center = (fi + 0.5 + (hash11(seed) - 0.5) * 0.74) / 30.0;
        center += sin(time * (1.1 + hash11(seed + 1.0) * 2.2) + seed) * 0.0035 * envelope;

        float halfHeight = mix(0.0018, 0.013, hash11(seed + 2.0));
        halfHeight *= 0.55 + envelope * 0.95;
        float maskY = smoothBand(abs(uv.y - center), halfHeight);

        float left = hash11(seed + 3.0) * 0.77;
        float length = mix(0.15, 1.0 - left, hash11(seed + 4.0));
        float right = min(1.0, left + length);
        float segment = step(left, uv.x) * step(uv.x, right);

        // Some rows break into short separated fragments instead of a single bar.
        float cell = floor(uv.x * (8.0 + hash11(seed + 5.0) * 20.0));
        float broken = mix(0.58, 1.0, step(0.19, hash11(cell + seed * 3.7)));
        float mask = maskY * segment * broken * envelope;

        float direction = hash11(seed + 6.0) > 0.5 ? 1.0 : -1.0;
        float offset = direction
                * (0.012 + hash11(seed + 7.0) * 0.055)
                * (0.35 + envelope * 0.95);
        offset += sin(time * 14.0 + seed) * 0.006 * envelope;

        vec2 displacedUv = clamp(uv + vec2(offset, 0.0), 0.001, 0.999);
        float chroma = (0.002 + hash11(seed + 8.0) * 0.009) * envelope;
        vec3 displaced = vec3(
                texture(DiffuseSampler, clamp(displacedUv + vec2(chroma, 0.0), 0.001, 0.999)).r,
                texture(DiffuseSampler, displacedUv).g,
                texture(DiffuseSampler, clamp(displacedUv - vec2(chroma, 0.0), 0.001, 0.999)).b
        );

        float tintChoice = hash11(seed + 9.0);
        vec3 tint = mix(
                vec3(0.30, 0.025, 0.53),
                vec3(0.96, 0.075, 0.48),
                tintChoice
        );

        tornColor = mix(tornColor, displaced, clamp(mask * 0.92, 0.0, 1.0));
        tornColor = mix(tornColor, tint, clamp(mask * (0.20 + 0.34 * tintChoice), 0.0, 0.68));
        totalMask = max(totalMask, mask);
        magentaEnergy += mask * (0.25 + tintChoice * 0.48);
        violetEnergy += mask * (1.0 - tintChoice) * 0.38;
    }

    // Thin bright edges and deeper purple gaps make the horizontal tears read
    // as several translucent layers rather than one opaque rectangle.
    float edgePulse = sin(time * 22.0 + uv.y * 180.0) * 0.5 + 0.5;
    float edgeGlow = clamp(totalMask * edgePulse * 0.36, 0.0, 0.32);
    vec3 pink = vec3(1.0, 0.12, 0.55);
    vec3 violet = vec3(0.30, 0.015, 0.52);
    tornColor += pink * edgeGlow;
    tornColor = mix(tornColor, violet, clamp(violetEnergy * 0.16, 0.0, 0.28));

    // A faint secondary RGB separation survives around the strongest tear,
    // matching the warped, chromatically split look without hiding gameplay.
    float globalChroma = envelope * 0.0035 * totalMask;
    vec3 finalColor = mix(original, tornColor, clamp(envelope * 1.15, 0.0, 1.0));
    finalColor.r = mix(finalColor.r,
            texture(DiffuseSampler, clamp(sampleUv + vec2(globalChroma, 0.0), 0.001, 0.999)).r,
            globalChroma * 7.0);
    finalColor.b = mix(finalColor.b,
            texture(DiffuseSampler, clamp(sampleUv - vec2(globalChroma, 0.0), 0.001, 0.999)).b,
            globalChroma * 7.0);

    fragColor = vec4(clamp(finalColor, 0.0, 1.0), 1.0);
}
