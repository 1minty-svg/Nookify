package com.nookify.backend.dto;

/**
 * One wall segment produced by LayoutEngineService.
 *
 * (x, z)      — center of the segment (matches GLB model pivot)
 * rotation    — Y-axis rotation in degrees, face points INTO the apartment
 *               0=face-Z, 90=face-X, 180=face+Z, 270=face-X
 * type        — EXTERNAL (apartment boundary) or INTERNAL (partition)
 * roomName    — zone this segment belongs to (context for Gemini)
 * width       — nominal model width in meters (1, 2 or 3)
 * scaleWidth  — scale factor to apply along the wall's length axis on the frontend
 *               = 1.0 for full-size segments (no scaling needed)
 *               < 1.0 for the last sub-1m remainder segment
 *               e.g. 0.6 means: load the 1m model, scale it to 0.6m
 *
 * Frontend usage (Three.js):
 *   if (seg.rotation === 0 || seg.rotation === 180) mesh.scale.z = seg.scaleWidth;
 *   else                                            mesh.scale.x = seg.scaleWidth;
 */
public class WallSegment {

    public enum WallType { EXTERNAL, INTERNAL }

    public double x;
    public double z;
    public double rotation;
    public WallType type;
    public String roomName;
    public double width;
    public double scaleWidth;

    public WallSegment(double x, double z, double rotation, WallType type,
                       String roomName, double width, double scaleWidth) {
        this.x = x;
        this.z = z;
        this.rotation = rotation;
        this.type = type;
        this.roomName = roomName;
        this.width = width;
        this.scaleWidth = scaleWidth;
    }
}
