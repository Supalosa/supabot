package com.supalosa.bot.instrumentation;

import com.github.ocraft.s2client.bot.gateway.ActionInterface;
import com.github.ocraft.s2client.protocol.action.ActionChat;
import com.github.ocraft.s2client.protocol.data.Ability;
import com.github.ocraft.s2client.protocol.spatial.Point2d;
import com.github.ocraft.s2client.protocol.unit.Tag;
import com.github.ocraft.s2client.protocol.unit.Unit;

import java.util.List;
import java.util.Set;

/**
 * Instrumentation over the ActionInterface to allow checking of APM.
 */
public class InstrumentedActionInterface implements ActionInterface {

    private ActionInterface delegate;
    private long count;

    public InstrumentedActionInterface(ActionInterface delegate) {
        this.delegate = delegate;
        this.count = 0;
    }

    /**
     * Returns the amount of calls that have been made since this method was last called.
     */
    public long getCountAndReset() {
        long result = this.count;
        this.count = 0;
        return result;
    }

    private void increment() {
        this.count++;
    }

    @Override
    public ActionInterface unitCommand(Unit unit, Ability ability, boolean queuedCommand) {
        increment();
        return delegate.unitCommand(unit, ability, queuedCommand);
    }

    @Override
    public ActionInterface unitCommand(Unit unit, Ability ability, Point2d point, boolean queuedCommand) {
        increment();
        return delegate.unitCommand(unit, ability, point, queuedCommand);
    }

    @Override
    public ActionInterface unitCommand(Unit unit, Ability ability, Unit target, boolean queuedCommand) {
        increment();
        return delegate.unitCommand(unit, ability, target, queuedCommand);
    }

    @Override
    public ActionInterface unitCommand(List<Unit> units, Ability ability, boolean queuedMove) {
        increment();
        return delegate.unitCommand(units, ability, queuedMove);
    }

    @Override
    public ActionInterface unitCommand(List<Unit> units, Ability ability, Point2d point, boolean queuedCommand) {
        increment();
        return delegate.unitCommand(units, ability, point, queuedCommand);
    }

    @Override
    public ActionInterface unitCommand(List<Unit> units, Ability ability, Unit target, boolean queuedCommand) {
        increment();
        return delegate.unitCommand(units, ability, target, queuedCommand);
    }

    @Override
    public ActionInterface unitCommand(Tag unit, Ability ability, boolean queuedCommand) {
        increment();
        return delegate.unitCommand(unit, ability, queuedCommand);
    }

    @Override
    public ActionInterface unitCommand(Tag unit, Ability ability, Point2d point, boolean queuedCommand) {
        increment();
        return delegate.unitCommand(unit, ability, point, queuedCommand);
    }

    @Override
    public ActionInterface unitCommand(Tag unit, Ability ability, Unit target, boolean queuedCommand) {
        increment();
        return delegate.unitCommand(unit, ability, target, queuedCommand);
    }

    @Override
    public ActionInterface unitCommand(Set<Tag> units, Ability ability, boolean queuedMove) {
        increment();
        return delegate.unitCommand(units, ability, queuedMove);
    }

    @Override
    public ActionInterface unitCommand(Set<Tag> units, Ability ability, Point2d point, boolean queuedCommand) {
        increment();
        return delegate.unitCommand(units, ability, point, queuedCommand);
    }

    @Override
    public ActionInterface unitCommand(Set<Tag> units, Ability ability, Unit target, boolean queuedCommand) {
        increment();
        return delegate.unitCommand(units, ability, target, queuedCommand);
    }

    @Override
    public List<Tag> commands() {
        increment();
        return delegate.commands();
    }

    @Override
    public ActionInterface toggleAutocast(Tag unitTag, Ability ability) {
        increment();
        return delegate.toggleAutocast(unitTag, ability);
    }

    @Override
    public ActionInterface toggleAutocast(List<Tag> unitTags, Ability ability) {
        increment();
        return delegate.toggleAutocast(unitTags, ability);
    }

    @Override
    public ActionInterface sendChat(String message, ActionChat.Channel channel) {
        increment();
        return delegate.sendChat(message, channel);
    }

    @Override
    public boolean sendActions() {
        return delegate.sendActions();
    }
}
