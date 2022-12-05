package com.supalosa.bot.production;

import org.mvel2.Unit;

import java.util.Collections;
import java.util.List;

/**
 * Interface for an object that requests units.
 */
public interface UnitRequester {

    UnitRequester EMPTY_REQUESTER = () -> Collections.emptyList();

    List<UnitTypeRequest> getRequestedUnits();

    static UnitRequester empty() {
        return EMPTY_REQUESTER;
    }
}
