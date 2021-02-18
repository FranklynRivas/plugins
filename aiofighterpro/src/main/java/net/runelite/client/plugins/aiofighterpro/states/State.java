package net.runelite.client.plugins.aiofighterpro.states;

import net.runelite.client.plugins.aiofighterpro.AIOFighterPro;

import java.util.ArrayList;
import java.util.List;

public abstract class State {
    AIOFighterPro plugin;
    List<State> subStates = new ArrayList<State>();
    String chainedName;
    public State(AIOFighterPro plugin){
        this.plugin = plugin;
        this.chainedName = getName();
    }

    public State getValidState(){
        for (State s : subStates) {
            if (s.condition()) return s;
        }

        return null;
    }
    public abstract boolean condition();
    public abstract String getName();

    public synchronized String chainedName(){
        return this.chainedName;
    }

    public void loop(){
        if (getValidState() != null) {
            synchronized (this) {
                chainedName = getName() + " > " +  getValidState().chainedName();
            }
            getValidState().loop();
            return;
        }
    };
}
