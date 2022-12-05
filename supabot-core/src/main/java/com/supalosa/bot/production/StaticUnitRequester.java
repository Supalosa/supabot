package com.supalosa.bot.production;

import java.util.List;

public class StaticUnitRequester implements UnitRequester {

    private List<UnitTypeRequest> requestedUnits;

    public StaticUnitRequester(List<UnitTypeRequest> requestedUnits) {
        this.requestedUnits = requestedUnits;
    }

    @Override
    public List<UnitTypeRequest> getRequestedUnits() {
        return this.requestedUnits;
    }
}
