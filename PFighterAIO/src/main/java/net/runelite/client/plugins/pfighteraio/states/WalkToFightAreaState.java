package net.runelite.client.plugins.pfighteraio.states;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.paistisuite.api.PPlayer;
import net.runelite.client.plugins.paistisuite.api.PUtils;
import net.runelite.client.plugins.paistisuite.api.PWalking;
import net.runelite.client.plugins.paistisuite.api.WebWalker.api_lib.DaxWalker;
import net.runelite.client.plugins.paistisuite.api.WebWalker.wrappers.RSTile;
import net.runelite.client.plugins.pfighteraio.PFighterAIO;

@Slf4j
public class WalkToFightAreaState extends State {
    public WalkToFightAreaState(PFighterAIO plugin) {
        super(plugin);
    }

    @Override
    public boolean condition() {
        return !plugin.searchRadiusCenter.isInScene(PUtils.getClient())
                || plugin.searchRadiusCenter.distanceTo(PPlayer.location()) > plugin.searchRadius*0.9;
    }

    @Override
    public String getName() {
        return "Walk to fight area";
    }

    @Override
    public void loop(){
        super.loop();
        WorldPoint target = (plugin.safeSpotForCombat && plugin.safeSpot != null) ? plugin.safeSpot : plugin.searchRadiusCenter;

        if (!PPlayer.isMoving()) {
            if (target.isInScene(PUtils.getClient()) && plugin.isReachable(target) && PWalking.sceneWalk(target)){
                PUtils.sleepNormal(650, 1500);
            } else if (!DaxWalker.walkTo(new RSTile(target), plugin.walkingCondition)) {
                log.info("Unable to walk to fight area!");
                PUtils.sendGameMessage("Unable to walk to fight area!");
                PUtils.sleepNormal(650, 1500);
                plugin.requestStop();
            }
        }
    }
}
