package com.nexus.dimensions.generation.noise;

import java.util.Random;

/**
 * Self-contained, seeded noise toolkit used by every Nexus dimension.
 * <p>
 * Deliberately dependency-free (no FastNoiseLite jar, no shading) so the
 * plugin has zero runtime dependencies beyond the Paper API. Implements
 * classic Ken Perlin "improved noise" in 2D and 3D, fractal Brownian
 * motion (fBm) on top of it (with an optional ridged variant and domain
 * warp), and a simple Worley/cellular F1 distance field used for crater
 * placement.
 */
public final class NoiseUtil {

    private final int[] perm = new int[512];

    public NoiseUtil(long seed) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) {
            p[i] = i;
        }
        // Deterministic seeded shuffle (Fisher-Yates) so the same seed
        // always produces the same terrain.
        Random random = new Random(seed);
        for (int i = 255; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = p[i];
            p[i] = p[j];
            p[j] = tmp;
        }
        for (int i = 0; i < 512; i++) {
            perm[i] = p[i & 255];
        }
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static double grad(int hash, double x, double y, double z) {
        int h = hash & 15;
        double u = h < 8 ? x : y;
        double v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    /** Raw 3D Perlin noise, range roughly [-1, 1]. */
    public double perlin3(double x, double y, double z) {
        int X = (int) Math.floor(x) & 255;
        int Y = (int) Math.floor(y) & 255;
        int Z = (int) Math.floor(z) & 255;
        x -= Math.floor(x);
        y -= Math.floor(y);
        z -= Math.floor(z);
        double u = fade(x);
        double v = fade(y);
        double w = fade(z);

        int a = perm[X] + Y;
        int aa = perm[a] + Z;
        int ab = perm[a + 1] + Z;
        int b = perm[X + 1] + Y;
        int ba = perm[b] + Z;
        int bb = perm[b + 1] + Z;

        return lerp(w,
                lerp(v,
                        lerp(u, grad(perm[aa], x, y, z), grad(perm[ba], x - 1, y, z)),
                        lerp(u, grad(perm[ab], x, y - 1, z), grad(perm[bb], x - 1, y - 1, z))),
                lerp(v,
                        lerp(u, grad(perm[aa + 1], x, y, z - 1), grad(perm[ba + 1], x - 1, y, z - 1)),
                        lerp(u, grad(perm[ab + 1], x, y - 1, z - 1), grad(perm[bb + 1], x - 1, y - 1, z - 1))));
    }

    /** Raw 2D Perlin noise (z pinned to 0), range roughly [-1, 1]. */
    public double perlin2(double x, double y) {
        return perlin3(x, y, 0.0);
    }

    /**
     * Fractal Brownian motion: sums {@code octaves} layers of 2D Perlin
     * noise at increasing frequency / decreasing amplitude.
     *
     * @param ridged if true, uses {@code 1 - |noise|} per octave, which
     *               produces sharp ridge/crag lines instead of smooth hills
     * @param warp   domain-warp strength; 0 disables it. Non-zero values
     *               offset the sample point by another noise field first,
     *               breaking up any grid-aligned look.
     */
    public double fbm2D(double x, double y, double frequency, int octaves, double lacunarity, double gain,
                         boolean ridged, double warp) {
        if (warp > 0.0001) {
            double wx = perlin2((x + 1000) * frequency * 0.5, (y - 1000) * frequency * 0.5);
            double wy = perlin2((x - 500) * frequency * 0.5, (y + 500) * frequency * 0.5);
            x += wx * warp / frequency;
            y += wy * warp / frequency;
        }

        double amplitude = 1.0;
        double freq = frequency;
        double sum = 0.0;
        double norm = 0.0;
        for (int i = 0; i < octaves; i++) {
            double n = perlin2(x * freq, y * freq);
            if (ridged) {
                n = 1.0 - Math.abs(n);
                n = n * n;
            }
            sum += n * amplitude;
            norm += amplitude;
            amplitude *= gain;
            freq *= lacunarity;
        }
        return norm > 0 ? sum / norm : 0;
    }

    /** 3D fBm, used for cave carving. Range roughly [-1, 1]. */
    public double fbm3D(double x, double y, double z, double frequency, int octaves, double lacunarity, double gain) {
        double amplitude = 1.0;
        double freq = frequency;
        double sum = 0.0;
        double norm = 0.0;
        for (int i = 0; i < octaves; i++) {
            sum += perlin3(x * freq, y * freq, z * freq) * amplitude;
            norm += amplitude;
            amplitude *= gain;
            freq *= lacunarity;
        }
        return norm > 0 ? sum / norm : 0;
    }

    /**
     * Worley / cellular noise (F1: distance to the nearest jittered grid
     * point), normalized to roughly [0, 1]. Used for crater placement —
     * low values near a cell center, rising towards the cell edges.
     */
    public double worley2D(double x, double y, double frequency, double jitter) {
        double px = x * frequency;
        double py = y * frequency;
        int cellX = (int) Math.floor(px);
        int cellY = (int) Math.floor(py);

        double minDist = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                int cx = cellX + dx;
                int cy = cellY + dy;
                double jitterX = hash01(cx, cy, 1) * jitter;
                double jitterY = hash01(cx, cy, 2) * jitter;
                double pointX = cx + 0.5 + (jitterX - 0.5);
                double pointY = cy + 0.5 + (jitterY - 0.5);
                double ddx = px - pointX;
                double ddy = py - pointY;
                double dist = Math.sqrt(ddx * ddx + ddy * ddy);
                if (dist < minDist) {
                    minDist = dist;
                }
            }
        }
        return Math.min(1.0, minDist);
    }

    /**
     * 3D Worley/cellular noise (F1: distance to the nearest jittered grid
     * point in a 3x3x3 neighborhood of cells), normalized to roughly
     * [0, ~1]. Used for cellular cave carving ({@code caves.mode:
     * cellular}) — small values near a jittered point read as "inside a
     * cavern chamber," rising towards cell edges reads as solid rock
     * between chambers, which produces a network of roughly egg-shaped
     * rooms connected where two chambers' territories meet, a distinctly
     * different look from the smoother, more uniform threshold-on-fBm
     * caves the default mode produces.
     */
    public double worley3D(double x, double y, double z, double frequency, double jitter) {
        double px = x * frequency;
        double py = y * frequency;
        double pz = z * frequency;
        int cellX = (int) Math.floor(px);
        int cellY = (int) Math.floor(py);
        int cellZ = (int) Math.floor(pz);

        double minDist = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int cx = cellX + dx;
                    int cy = cellY + dy;
                    int cz = cellZ + dz;
                    double jitterX = hash013(cx, cy, cz, 1) * jitter;
                    double jitterY = hash013(cx, cy, cz, 2) * jitter;
                    double jitterZ = hash013(cx, cy, cz, 3) * jitter;
                    double pointX = cx + 0.5 + (jitterX - 0.5);
                    double pointY = cy + 0.5 + (jitterY - 0.5);
                    double pointZ = cz + 0.5 + (jitterZ - 0.5);
                    double ddx = px - pointX;
                    double ddy = py - pointY;
                    double ddz = pz - pointZ;
                    double dist = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
                    if (dist < minDist) {
                        minDist = dist;
                    }
                }
            }
        }
        return Math.min(1.5, minDist);
    }

    /** Deterministic hash -> [0, 1), independent of the instance's permutation table. */
    private double hash01(int x, int y, int salt) {
        long h = x * 374761393L + y * 668265263L + salt * 2147483647L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        h ^= (h >>> 16);
        return ((h & 0xFFFFFFL) / (double) 0xFFFFFF);
    }

    /** Same idea as {@link #hash01(int, int, int)} with a third spatial axis, for {@link #worley3D}. */
    private double hash013(int x, int y, int z, int salt) {
        long h = x * 374761393L + y * 668265263L + z * 2246822519L + salt * 3266489917L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        h ^= (h >>> 16);
        return ((h & 0xFFFFFFL) / (double) 0xFFFFFF);
    }
}
