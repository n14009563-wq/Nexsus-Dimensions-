package com.nexus.dimensions.structure;

/**
 * Pure position-space geometry for {@code structures.randomRotation}/{@code
 * randomMirror} — rotates and/or mirrors a blueprint's {@code (dx, dz)}
 * offsets around the vertical axis before they're added to an anchor
 * column. {@code dy} is never touched (rotation is always around Y).
 * <p>
 * Deliberately does NOT re-orient the placed block's own {@code
 * BlockData} (stairs facing, log axis, chest facing, ...) — doing that
 * correctly needs per-subtype handling across {@code Directional}/{@code
 * Orientable}/{@code Rotatable}/{@code Bisected}/etc., a meaningfully
 * bigger feature than moving block positions around. See DESIGN.md
 * section 9 for the tradeoff and why it's safe for blueprints built from
 * non-directional blocks (like the bundled {@code ruin_small}).
 */
public final class BlueprintTransform {

    private BlueprintTransform() {
    }

    /**
     * @param dx           blueprint-relative X offset
     * @param dz           blueprint-relative Z offset
     * @param rotationStep 0-3, each step is a 90-degree rotation
     * @param mirror       true = also flip across the X axis before rotating
     * @return {newDx, newDz}
     */
    public static int[] apply(int dx, int dz, int rotationStep, boolean mirror) {
        int x = mirror ? -dx : dx;
        int z = dz;
        int steps = ((rotationStep % 4) + 4) % 4; // guard against a stray negative
        for (int i = 0; i < steps; i++) {
            int newX = -z;
            int newZ = x;
            x = newX;
            z = newZ;
        }
        return new int[]{x, z};
    }
}
