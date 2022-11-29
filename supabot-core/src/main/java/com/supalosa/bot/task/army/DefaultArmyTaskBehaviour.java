package com.supalosa.bot.task.army;

import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Unit;

/**
 * Interface to a behaviour class to be injected into the DefaultArmyTask to define its behaviour.
 *
 * The generic context objects are to allow constructing an army-wide context at the start of each step, and
 * process it on a per-unit basis. This allows (relatively) expensive computation to be used by every unit in the army.
 *
 * @param <AttackContext> The type of the object to construct in onArmyAttackingStep and passed to onArmyUnitAttackingStep.
 * @param <DisengagingContext> The type of the object to construct in onArmyDisengagingStep and passed to onArmyUnitDisengagingStep.
 * @param <RegroupingContext> The type of the object to construct in onArmyRegroupingStep and passed to onArmyUnitRegroupingStep.
 * @param <IdleContext> The type of the object to construct in onArmyIdleStep and passed to onArmyUnitIdleStep.
 */
public interface DefaultArmyTaskBehaviour<AttackContext, DisengagingContext, RegroupingContext, IdleContext> {

    /**
     * Called when a unit is added to this army.
     *
     * @param unit The unit that was added.
     */
    void onUnitAdded(Unit unit);

    /**
     * Called when a unit in this army has been damaged. If the unit has died, this method will not be called.
     *
     * @param unit The unit that was damaged.
     * @param previousHealth The initial health of the unit.
     * @param newHealth The current health of the unit.
     */
    void onUnitDamaged(Unit unit, float previousHealth, float newHealth);

    /**
     * Called when a unit in this army was lost (either by death or being taken for another task).
     *
     * @param unit The unit that was lost.
     */
    void onUnitLost(Unit unit);

    /**
     * Called when an enemy's unit has been damaged. If the unit has died, this method will not be called.
     *
     * @param unit The enemy unit that was damaged.
     * @param previousHealth The previous health of the unit.
     * @param newHealth The current health of the unit.
     */
    void onEnemyUnitDamaged(Unit unit, float previousHealth, float newHealth);

    /**
     * Called when an enemy's unit has been removed from the observed army, either because it died or has left vision.
     *
     * @param unit The enemy unit that was previously observed, but no longer visible.
     * @param previousLocation The last known location of the enemy.
     */
    void onEnemyUnitLost(Unit unit, Point2d previousLocation);

    /**
     * Returns the step handler for when the unit is in the Attack state.
     * It is preferable to not construct a new instance each time.
     *
     * @return The appropriate step handler.
     */
    DefaultArmyTaskBehaviourStateHandler<AttackContext> getAttackHandler();

    /**
     * Returns the step handler for when the unit is in the Disengaging state.
     * It is preferable to not construct a new instance each time.
     *
     * @return The appropriate step handler.
     */
    DefaultArmyTaskBehaviourStateHandler<DisengagingContext> getDisengagingHandler();

    /**
     * Returns the step handler for when the unit is in the Regrouping state.
     * It is preferable to not construct a new instance each time.
     *
     * @return The appropriate step handler.
     */
    DefaultArmyTaskBehaviourStateHandler<RegroupingContext> getRegroupingHandler();

    /**
     * Returns the step handler for when the unit is in the Idle state.
     * It is preferable to not construct a new instance each time.
     *
     * @return The appropriate step handler.
     */
    DefaultArmyTaskBehaviourStateHandler<IdleContext> getIdleHandler();

}
