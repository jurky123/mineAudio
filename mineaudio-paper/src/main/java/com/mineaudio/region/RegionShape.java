package com.mineaudio.region;

/** 区域形状：V1 支持 Cuboid 与 Sphere，坐标判定全部走 double，便于单测。 */
public sealed interface RegionShape {

    boolean contains(double x, double y, double z);

    int minChunkX();

    int maxChunkX();

    int minChunkZ();

    int maxChunkZ();

    record Cuboid(double minX, double minY, double minZ, double maxX, double maxY, double maxZ)
            implements RegionShape {

        public Cuboid {
            if (minX > maxX) {
                double swap = minX;
                minX = maxX;
                maxX = swap;
            }
            if (minY > maxY) {
                double swap = minY;
                minY = maxY;
                maxY = swap;
            }
            if (minZ > maxZ) {
                double swap = minZ;
                minZ = maxZ;
                maxZ = swap;
            }
        }

        @Override
        public boolean contains(double x, double y, double z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        @Override
        public int minChunkX() {
            return chunk(minX);
        }

        @Override
        public int maxChunkX() {
            return chunk(maxX);
        }

        @Override
        public int minChunkZ() {
            return chunk(minZ);
        }

        @Override
        public int maxChunkZ() {
            return chunk(maxZ);
        }
    }

    record Sphere(double centerX, double centerY, double centerZ, double radius) implements RegionShape {

        public Sphere {
            if (radius < 0) radius = 0;
        }

        @Override
        public boolean contains(double x, double y, double z) {
            double dx = x - centerX;
            double dy = y - centerY;
            double dz = z - centerZ;
            return dx * dx + dy * dy + dz * dz <= radius * radius;
        }

        @Override
        public int minChunkX() {
            return chunk(centerX - radius);
        }

        @Override
        public int maxChunkX() {
            return chunk(centerX + radius);
        }

        @Override
        public int minChunkZ() {
            return chunk(centerZ - radius);
        }

        @Override
        public int maxChunkZ() {
            return chunk(centerZ + radius);
        }
    }

    private static int chunk(double coordinate) {
        return (int) Math.floor(coordinate) >> 4;
    }
}
