package ru.apollot.pzsync.geometry;

public record Vec3(double x, double y, double z) {
    public Vec3 {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("coordinates must be finite");
        }
        if (z < Integer.MIN_VALUE || z >= (double) Integer.MAX_VALUE + 1.0) {
            throw new IllegalArgumentException("z must have a representable integer floor");
        }
    }

    public int integerFloor() {
        return (int) Math.floor(z);
    }

    double horizontalDistanceSquared(Vec3 other) {
        double dx = other.x - x;
        double dy = other.y - y;
        return dx * dx + dy * dy;
    }
}
