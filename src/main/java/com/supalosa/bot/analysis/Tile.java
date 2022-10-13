package com.supalosa.bot.analysis;

public class Tile {
    public boolean isRamp = false;
    public boolean isTopOfRamp = false;
    public int rampId = -1;
    public int terrain = 0;
    public boolean placeable = false;
    public boolean pathable = false;
    // for reapers, colossus etc.
    public boolean traversableCliff = false;
    public int distanceToBorder = 0;
}
