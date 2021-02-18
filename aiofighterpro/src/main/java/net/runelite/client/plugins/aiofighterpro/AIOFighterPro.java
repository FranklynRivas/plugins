package net.runelite.client.plugins.aiofighterpro;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.queries.NPCQuery;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginType;
import net.runelite.client.plugins.aiofighterpro.states.FightEnemiesState;
import net.runelite.client.plugins.aiofighterpro.states.LootItemsState;
import net.runelite.client.plugins.aiofighterpro.states.State;
import net.runelite.client.plugins.paistisuite.PScript;
import net.runelite.client.plugins.paistisuite.PaistiSuite;
import net.runelite.client.plugins.paistisuite.api.*;
import net.runelite.client.plugins.paistisuite.api.WebWalker.WalkingCondition;
import net.runelite.client.plugins.paistisuite.api.WebWalker.api_lib.DaxWalker;
import net.runelite.client.plugins.paistisuite.api.WebWalker.walker_engine.bfs.BFS;
import net.runelite.client.plugins.paistisuite.api.WebWalker.walker_engine.real_time_collision.CollisionDataCollector;
import net.runelite.client.plugins.paistisuite.api.WebWalker.walker_engine.real_time_collision.RealTimeCollisionTile;
import net.runelite.client.plugins.paistisuite.api.types.PGroundItem;
import net.runelite.client.plugins.paistisuite.api.types.PItem;
import net.runelite.client.ui.overlay.OverlayManager;
import org.pf4j.Extension;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Extension
@PluginDependency(PaistiSuite.class)
@PluginDescriptor(
        name = "AiO Fighter Pro",
        enabledByDefault = false,
        description = "Fully configurable all-in-one fighter",
        tags = {"combat", "magic", "fighter", "paisti"},
        type = PluginType.PVM
)

@Slf4j
@Singleton
public class AIOFighterPro extends PScript {
    int nextRunAt = PUtils.random(25,65);
    int nextEatAt;

    public int minEatHp;
    public int maxEatHp;
    public int searchRadius;
    public WorldPoint searchRadiusCenter;
    public String[] enemiesToTarget;
    public String[] foodsToEat;
    public String[] lootNames;
    public int lootGEValue;
    public Predicate<NPC> validTargetFilter;
    public Predicate<PGroundItem> validLootFilter;
    public Predicate<PItem> validFoodFilter;
    State currentState;
    List<State> states = new ArrayList<State>();
    public FightEnemiesState fightEnemiesState = new FightEnemiesState(this);
    public LootItemsState lootItemsState = new LootItemsState(this);
    private String currentStateName;

    @Inject
    private AIOFighterProConfig config;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private AIOFighterProOverlay overlay;
    @Inject
    private AIOFighterProOverlayMinimap minimapoverlay;
    @Inject
    private ConfigManager configManager;


    @Provides
    AIOFighterProConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(AIOFighterProConfig.class);
    }

    @Override
    protected void startUp()
    {

    }

    @Override
    protected synchronized void onStart() {
        PUtils.sendGameMessage("AiO Fighter started!");
        readConfig();
        searchRadiusCenter = PPlayer.location();
        DaxWalker.setCredentials(PaistiSuite.getDaxCredentialsProvider());
        states.add(this.lootItemsState);
        states.add(this.fightEnemiesState);
        overlayManager.add(overlay);
        overlayManager.add(minimapoverlay);
    }

    private synchronized void readConfig(){
        searchRadius = config.searchRadius();
        enemiesToTarget = PUtils.parseCommaSeparated(config.enemyNames());
        foodsToEat = PUtils.parseCommaSeparated(config.foodNames());
        lootNames = PUtils.parseCommaSeparated(config.lootNames());
        maxEatHp = Math.min(PSkills.getActualLevel(Skill.HITPOINTS), config.maxEatHP());
        minEatHp = Math.min(config.minEatHP(), maxEatHp);
        nextEatAt = (int)PUtils.randomNormal(minEatHp, maxEatHp);
        validTargetFilter = createValidTargetFilter();
        validLootFilter = createValidLootFilter();
        validFoodFilter = createValidFoodFilter();
        lootGEValue = config.lootGEValue();
        log.info("Targeting enemies: " + String.join(", ", enemiesToTarget));
        log.info("Food names: " + String.join(", ", foodsToEat));
        log.info("Loot names: " + String.join(", ", lootNames));
        log.info("Loot over value: " + (lootGEValue <= 0 ? "disabled" : lootGEValue));
        log.info("Min eat: " + minEatHp + " max eat: " + maxEatHp + " next eat: " + nextEatAt);
    }

    @Override
    protected synchronized void onStop() {
        overlayManager.remove(overlay);
        overlayManager.remove(minimapoverlay);
        PUtils.sendGameMessage("AiO Fighter stopped!");
    }

    public State getValidState(){
        for (State s : states) {
            if (s.condition()) return s;
        }
        return null;
    }

    @Override
    protected void loop() {
        PUtils.sleepFlat(50, 150);
        if (PUtils.getClient().getGameState() != GameState.LOGGED_IN) return;
        handleEating();
        if (isStopRequested()) return;
        handleRun();
        if (isStopRequested()) return;
        State prevState = currentState;
        currentState = getValidState();
        if (currentState != null) {
            if (prevState != currentState){
                log.info("Entered state: " + currentState.getName());
            }
            setCurrentStateName(currentState.getName());
            currentState.loop();
        }
    }

    private boolean handleEating(){
        if (PSkills.getCurrentLevel(Skill.HITPOINTS) <= nextEatAt){
            nextEatAt = (int)PUtils.randomNormal(minEatHp, maxEatHp);
            log.info("Next eat at " + nextEatAt);
            NPC targetBeforeEating = null;
            if (currentState == fightEnemiesState
                    && fightEnemiesState.inCombat()
                    && validTargetFilter.test((NPC)PPlayer.get().getInteracting())
            ) {
                targetBeforeEating = (NPC)PPlayer.get().getInteracting();
            }

            boolean success = eatFood();
            if (success && targetBeforeEating != null && PUtils.random(1,5) <= 4) {
                log.info("Re-targeting current enemy after eating");
                if (PInteraction.npc(targetBeforeEating, "Attack")){
                    PUtils.sleepNormal(100, 700);
                }
            }
        }
        return false;
    }

    public boolean eatFood(){
        PItem food = PInventory.findItem(validFoodFilter);
        if (food != null) log.info("Eating " + food.getName());
        if (PInteraction.item(food, "Eat")){
            log.info("Ate food");
            PUtils.sleepNormal(100, 700);
            return true;
        }
        log.info("Failed to eat food!");
        return false;
    }

    public WalkingCondition walkingCondition = () -> {
        if (isStopRequested()) return WalkingCondition.State.EXIT_OUT_WALKER_FAIL;
        if (PUtils.getClient().getGameState() != GameState.LOGGED_IN) return WalkingCondition.State.EXIT_OUT_WALKER_FAIL;
        handleRun();
        handleEating();
        return WalkingCondition.State.CONTINUE_WALKER;
    };

    public void handleRun(){
        if (!PWalking.isRunEnabled() && PWalking.getRunEnergy() > nextRunAt){
            nextRunAt = PUtils.random(25, 65);
            PWalking.setRunEnabled(true);
            PUtils.sleepNormal(300, 1500, 500, 1200);
        }
    }

    public List<NPC> getValidTargets(){
        return PUtils.clientOnly(() -> {
            CollisionDataCollector.generateRealTimeCollision();
            return new NPCQuery().result(PUtils.getClient())
                    .list.stream().filter(validTargetFilter).collect(Collectors.toList());
        }, "getValidTargets");
    }

    private synchronized Predicate<NPC> createValidTargetFilter(){
        Predicate<NPC> filter = (NPC n) -> {
            return n.getWorldLocation().distanceToHypotenuse(searchRadiusCenter) <= searchRadius
                && Filters.NPCs.nameOrIdEquals(enemiesToTarget).test(n)
                && (n.getInteracting() == null || n.getInteracting().equals(PPlayer.get()))
                && !n.isDead();
        };

        if (config.enablePathfind()) filter = filter.and(this::isReachable);
        return filter;
    };

    private synchronized Predicate<PItem> createValidFoodFilter(){
        return Filters.Items.nameOrIdEquals(foodsToEat);
    }

    private synchronized Predicate<PGroundItem> createValidLootFilter(){
        Predicate<PGroundItem> filter = Filters.GroundItems.nameContainsOrIdEquals(lootNames);
        if (lootGEValue > 0) filter = filter.or(Filters.GroundItems.SlotPriceAtLeast(lootGEValue));
        filter = filter.and(item -> item.getLocation().distanceToHypotenuse(searchRadiusCenter) <= (searchRadius+2));
        if (config.lootOwnKills()) filter = filter.and(item -> item.getLootType() == PGroundItem.LootType.PVM);
        if (config.enablePathfind()) filter = filter.and(item -> isReachable(item.getLocation()));
        filter = filter.and(item -> PInventory.getEmptySlots() > 0 || (item.isStackable() && PInventory.findItem(Filters.Items.idEquals(item.getId())) != null));
        return filter;
    }

    public Boolean isReachable(WorldPoint p){
        return PUtils.clientOnly(() -> {
            if (BFS.isReachable(RealTimeCollisionTile.get(
                    PPlayer.location().getX(),
                    PPlayer.location().getY(),
                    PPlayer.location().getPlane()),
                    RealTimeCollisionTile.get(
                            p.getX(),
                            p.getY(),
                            p.getPlane()), (int)Math.round(Math.PI*(searchRadius*searchRadius*1.5*1.5)))) {
                return true;
            }

            return false;
        }, "isReachable");
    }

    public Boolean isReachable(NPC n){
        return isReachable(n.getWorldLocation());
    }


    @Subscribe
    private synchronized void onConfigChanged(ConfigChanged event){
        if (!event.getGroup().equalsIgnoreCase("AIOFighterProConfigs")) return;
        readConfig();
    }

    @Subscribe
    private synchronized void onConfigButtonPressed(ConfigButtonClicked configButtonClicked)
    {
        if (!configButtonClicked.getGroup().equalsIgnoreCase("AIOFighterProConfigs")) return;

        if (configButtonClicked.getKey().equals("startButton"))
        {
            Player player = PUtils.getClient().getLocalPlayer();
            if (player != null && PUtils.getClient().getGameState() == GameState.LOGGED_IN)
            {
                try {
                    super.start();
                } catch (Exception e){
                    log.error(e.toString());
                }
            }
        } else if (configButtonClicked.getKey().equals("stopButton")){
            requestStop();
        }
    }

    public synchronized String getCurrentStateName() {
        return currentStateName;
    }

    public synchronized void setCurrentStateName(String currentStateName) {
        this.currentStateName = currentStateName;
    }
}
