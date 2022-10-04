package com.supalosa.bot.analysis;

import com.github.ocraft.s2client.protocol.spatial.Point2d;

import java.util.Set;

public class Ramp {
    private int rampId;
    private Set<Point2d> rampTiles;

    private Set<Point2d> topOfRampTiles;

    public Ramp(int rampId, Set<Point2d> rampTiles, Set<Point2d> topOfRampTiles) {
        this.rampId = rampId;
        this.rampTiles = rampTiles;
        this.topOfRampTiles = topOfRampTiles;
    }

    public int getRampId() {
        return rampId;
    }

    public Set<Point2d> getRampTiles() {
        return rampTiles;
    }

    public Set<Point2d> getTopOfRampTiles() {
        return topOfRampTiles;
    }
}
