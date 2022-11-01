package com.supalosa.bot;

import com.github.ocraft.s2client.bot.gateway.ObservationInterface;
import com.github.ocraft.s2client.bot.gateway.QueryInterface;
import com.github.ocraft.s2client.protocol.data.*;
import com.github.ocraft.s2client.protocol.observation.AvailableAbility;
import com.github.ocraft.s2client.protocol.query.AvailableAbilities;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Alliance;
import com.github.ocraft.s2client.protocol.unit.Unit;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * This class allows us to cache game data while also allowing us to
 * apply manual fixes to the (bugged) API.
 */
public class GameData {

    private final ObservationInterface observationInterface;
    private Map<UnitType, UnitTypeData> typeData = null;
    private Map<Ability, AbilityData> abilityData = null;
    private final Map<Ability, UnitType> abilityToUnitType = new HashMap<>();
    private Map<UnitType, Boolean> structureTypes = new HashMap<>();
    private Map<Tag, Set<Ability>> availableAbilities;
    private Map<UnitType, Optional<Float>> unitMaxRange = new HashMap<>();

    public GameData(ObservationInterface observationInterface) {
        this.observationInterface = observationInterface;
    }

    // Do not call until game has started.
    private Map<UnitType, UnitTypeData> getOrInitUnitTypeData() {
        if (this.typeData == null) {
            this.typeData = observationInterface.getUnitTypeData(true);
            this.typeData.forEach((unitType, unitTypeData) -> {
                unitTypeData.getAbility().ifPresent(ability -> this.abilityToUnitType.put(ability, unitType));
            });
        }
        return this.typeData;
    }
    private Map<Ability, AbilityData> getOrInitAbilityData() {
        if (this.abilityData == null) {
            this.abilityData = observationInterface.getAbilityData(true);
        }
        return this.abilityData;
    }

    public Optional<Integer> getUnitMineralCost(UnitType unitType) {
        UnitTypeData unitTypeData = getOrInitUnitTypeData().get(unitType);
        if (unitTypeData == null) {
            return Optional.empty();
        } else {
            return unitTypeData.getMineralCost();
        }
    }

    public Optional<Integer> getUnitVespeneCost(UnitType unitType) {
        UnitTypeData unitTypeData = getOrInitUnitTypeData().get(unitType);
        if (unitTypeData == null) {
            return Optional.empty();
        } else {
            return unitTypeData.getVespeneCost();
        }
    }

    /**
     * Returns the structure footprint of the unit. This is derived from the ability that created the unit.
     *
     * @param unitType The Unit (typically a Structure) to be queried.
     * @return A point2d representing the width and height of the unit.
     */
    public Optional<Point2d> getUnitFootprint(UnitType unitType) {
        UnitTypeData unitTypeData = getOrInitUnitTypeData().get(unitType);
        if (unitTypeData == null) {
            return Optional.empty();
        } else if (unitType.equals(Units.TERRAN_SUPPLY_DEPOT_LOWERED)) {
            return Optional.of(Point2d.of(2f, 2f));
        } if (Constants.MINERAL_TYPES.contains(unitType)) {
            return Optional.of(Point2d.of(2f, 1f));
        } else if (Constants.VESPENE_GEYSER_TYPES.contains(unitType)) {
            return Optional.of(Point2d.of(2f, 2f));
        } else if (Constants.ALL_TOWN_HALL_TYPES.contains(unitType)) {
            return Optional.of(Point2d.of(5f, 5f));
        } else {
            Optional<Ability> maybeAbility = unitTypeData.getAbility();
            if (maybeAbility.isEmpty()) {
                return Optional.empty();
            } else {
                Optional<Float> maybeRadius = getAbilityRadius(maybeAbility.get());
                return maybeRadius.map(radius -> Point2d.of((int)(radius * 2f), (int)(radius * 2f)));
            }
        }
    }

    public Optional<UnitTypeData> getUnitTypeData(UnitType unitType) {
        UnitTypeData unitTypeData = getOrInitUnitTypeData().get(unitType);
        if (unitTypeData == null) {
            return Optional.empty();
        } else {
            return Optional.of(unitTypeData);
        }
    }

    public Optional<Float> getAbilityRadius(Ability ability) {
        AbilityData abilityData = getOrInitAbilityData().get(ability);
        if (abilityData == null) {
            return Optional.empty();
        } else {
            if (ability == Abilities.BUILD_TECHLAB_BARRACKS ||
                    ability == Abilities.BUILD_TECHLAB_FACTORY ||
                    ability == Abilities.BUILD_TECHLAB_STARPORT ||
                    ability == Abilities.BUILD_REACTOR_BARRACKS ||
                    ability == Abilities.BUILD_REACTOR_FACTORY ||
                    ability == Abilities.BUILD_REACTOR_STARPORT) {
                return Optional.of(1f);
            }
            return abilityData.getFootprintRadius();
        }
    }

    public Optional<AbilityData> getAbility(Ability ability) {
        AbilityData abilityData = getOrInitAbilityData().get(ability);
        if (abilityData == null) {
            return Optional.empty();
        } else {
            return Optional.of(abilityData);
        }
    }

    public Optional<UnitType> getUnitBuiltByAbilility(Ability ability) {
        getOrInitUnitTypeData();
        return Optional.ofNullable(abilityToUnitType.get(ability));
    }

    public Optional<Ability> getAbilityForUnitType(UnitType unitType) {
        UnitTypeData data = getOrInitUnitTypeData().get(unitType);
        return data != null ? data.getAbility() : Optional.empty();
    }

    public boolean isStructure(UnitType unitType) {
        if (!structureTypes.containsKey(unitType)) {
            Optional<UnitTypeData> maybeUnitTypeData = getUnitTypeData(unitType);
            boolean isStructure = maybeUnitTypeData
                    .map(unitTypeData -> unitTypeData.getAttributes().contains(UnitAttribute.STRUCTURE))
                    .orElse(false);
            structureTypes.put(unitType, isStructure);
            return isStructure;
        } else {
            return structureTypes.get(unitType);
        }
    }

    /**
     * Return the size of the unit when it's in cargo.
     */
    public Optional<Integer> getUnitCargoSize(UnitType type) {
        return Optional.ofNullable(getOrInitUnitTypeData().get(type)).flatMap(unitTypeData -> unitTypeData.getCargoSize());
    }

    public void onStep(ObservationInterface observationInterface, QueryInterface queryInterface) {
        List<Unit> myUnits = observationInterface.getUnits(Alliance.SELF).stream().map(unitInPool -> unitInPool.unit()).collect(Collectors.toList());
        List<AvailableAbilities> available = queryInterface.getAbilitiesForUnits(myUnits, false);

        availableAbilities = available.stream().collect(
                Collectors.toMap(
                        AvailableAbilities::getUnitTag,
                        abilities ->
                                abilities.getAbilities().stream().map(AvailableAbility::getAbility).collect(Collectors.toSet()))
        );
    }

    /**
     * Return all abilities available to this unit.
     */
    public Set<Ability> getAvailableAbilities(Tag tag) {
        return availableAbilities.getOrDefault(tag, Collections.emptySet());
    }

    public boolean unitHasAbility(Tag tag, Ability ability) {
        return getAvailableAbilities(tag).contains(ability);
    }

    public Set<UnitAttribute> getAttributes(UnitType unitType) {
        Optional<UnitTypeData> maybeUnitTypeData = getUnitTypeData(unitType);
        return maybeUnitTypeData
                .map(unitTypeData -> unitTypeData.getAttributes())
                .orElse(Collections.emptySet());
    }

    public Optional<Float> getMaxUnitRange(UnitType unitType) {
        final Function<Set<Weapon>, Optional<Float>> mapper = weapons ->
                weapons.stream().max(Comparator.comparingDouble(Weapon::getRange)).map(Weapon::getRange);
        if (unitMaxRange.containsKey(unitType)) {
            return unitMaxRange.get(unitType);
        } else {
            Optional<Float> range = getUnitTypeData(unitType)
                    .map(UnitTypeData::getWeapons)
                    .flatMap(mapper);
            unitMaxRange.put(unitType, range);
            return range;
        }
    }
}
