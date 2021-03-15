package net.runelite.client.plugins.pfighteraio;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.queries.NPCQuery;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.config.ConfigManager;

import static net.runelite.api.ObjectID.*;
import static net.runelite.api.ProjectileID.CANNONBALL;
import static net.runelite.api.ProjectileID.GRANITE_CANNONBALL;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.paistisuite.PScript;
import net.runelite.client.plugins.paistisuite.PaistiSuite;
import net.runelite.client.plugins.paistisuite.api.*;
import net.runelite.client.plugins.paistisuite.api.WebWalker.WalkingCondition;
import net.runelite.client.plugins.paistisuite.api.WebWalker.api_lib.DaxWalker;
import net.runelite.client.plugins.paistisuite.api.WebWalker.walker_engine.local_pathfinding.Reachable;
import net.runelite.client.plugins.paistisuite.api.WebWalker.wrappers.Keyboard;
import net.runelite.client.plugins.paistisuite.api.WebWalker.wrappers.RSTile;
import net.runelite.client.plugins.paistisuite.api.types.Filters;
import net.runelite.client.plugins.paistisuite.api.types.PGroundItem;
import net.runelite.client.plugins.paistisuite.api.types.PItem;
import net.runelite.client.plugins.paistisuite.api.types.PTileObject;
import net.runelite.client.plugins.pfighteraio.states.*;
import net.runelite.client.ui.overlay.OverlayManager;
import org.pf4j.Extension;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.event.KeyEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Extension
@PluginDependency(PaistiSuite.class)
@PluginDescriptor(
        name = "PFighterAIO",
        enabledByDefault = false,
        description = "Fully configurable all-in-one fighter",
        tags = {"combat", "magic", "fighter", "paisti"}
)

@Slf4j
@Singleton
public class PFighterAIO extends PScript {
    int nextRunAt = PUtils.random(25,65);
    int nextEatAt;
    public boolean enablePathfind;
    public Instant startedTimestamp;
    boolean usingSavedSafeSpot = false;
    boolean usingSavedFightTile = false;
    boolean usingSavedCannonTile = false;
    public int minEatHp;
    public int maxEatHp;
    public int searchRadius;
    public int reservedInventorySlots;
    public WorldPoint safeSpot;
    public WorldPoint searchRadiusCenter;
    public WorldPoint bankTile;
    public WorldPoint cannonTile;
    public String[] enemiesToTarget;
    public String[] foodsToEat;
    public String[] lootNames;
    public int lootGEValue;
    public Predicate<NPC> validTargetFilter;
    public Predicate<NPC> validTargetFilterWithoutDistance;
    public Predicate<PGroundItem> validLootFilter;
    public Predicate<PItem> validFoodFilter;
    public boolean stopWhenOutOfFood;
    public boolean eatFoodForLoot;
    public boolean safeSpotForCombat;
    public boolean safeSpotForLogout;
    public boolean forceLoot;
    public boolean bankingEnabled;
    public boolean bankForFood;
    public boolean bankForLoot;
    public boolean teleportWhileBanking;
    public boolean usePotions;
    public int minPotionBoost;
    public int maxPotionBoost;
    private String apiKey;
    public List<BankingState.WithdrawItem> itemsToWithdraw;
    State currentState;
    List<State> states;
    public FightEnemiesState fightEnemiesState;
    public LootItemsState lootItemsState;
    public WalkToFightAreaState walkToFightAreaState;
    public BankingState bankingState;
    public TakeBreaksState takeBreaksState;
    public SetupCannonState setupCannonState;
    public DrinkPotionsState drinkPotionsState;
    private String currentStateName;
    private long lastAntiAfk = System.currentTimeMillis();
    private long antiAfkDelay = PUtils.randomNormal(120000, 270000);
    public PBreakScheduler breakScheduler = null;
    public boolean safeSpotForBreaks;
    public boolean enableBreaks;
    public boolean useCannon;
    private int cannonBallsLeft;
    private boolean cannonPlaced;
    private boolean cannonFinished;
    private WorldPoint currentCannonPos;
    private int currentCannonWorld;
    private final ReentrantLock cannonVarsLock = new ReentrantLock();
    private boolean flickQuickPrayers;
    private boolean assistFlickPrayers;
    public boolean enableAlching;
    public int alchMinHAValue;
    public int alchMaxPriceDifference;
    private int breakMinIntervalMinutes;
    private int breakMaxIntervalMinutes;
    private int breakMinDurationSeconds;
    private int breakMaxDurationSeconds;
    private ExecutorService prayerFlickExecutor;
    private static final Pattern NUMBER_PATTERN = Pattern.compile("([0-9]+)");
    private static boolean skipProjectileCheckThisTick = false;

    @Inject
    private PFighterAIOConfig config;
    @Inject
    private OverlayManager overlayManager;
    @Inject
    private PFighterAIOOverlay overlay;
    @Inject
    private PFighterAIOOverlayMinimap minimapoverlay;
    @Inject
    private ConfigManager configManager;
    private LicenseValidator licenseValidator;
    private ExecutorService validatorExecutor;

    @Provides
    PFighterAIOConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(PFighterAIOConfig.class);
    }

    @Override
    protected void startUp()
    {
        if (PUtils.getClient() != null && PUtils.getClient().getGameState() == GameState.LOGGED_IN){
            readConfig();
        }
        overlayManager.add(overlay);
        overlayManager.add(minimapoverlay);
    }

    public boolean shouldPray() {
        if (isRunning()){
            if (PPlayer.get().getInteracting() != null){
                if (validTargetFilterWithoutDistance.test((NPC)PPlayer.get().getInteracting())) {
                    return true;
                }
            }

            if (PObjects.findNPC(validTargetFilterWithoutDistance.and(n -> n.getInteracting() != null && n.getInteracting() == PPlayer.get())) != null){
                return true;
            }
        } else if (assistFlickPrayers){
            if (PPlayer.get().getInteracting() != null && PPlayer.get().getInteracting() instanceof NPC){
                if (Filters.NPCs.actionsContains("Attack").test((NPC)PPlayer.get().getInteracting())) {
                    return true;
                }
            }
            if (PObjects.findNPC(Filters.NPCs.actionsContains("Attack").and(n -> n.getInteracting() != null && n.getInteracting() == PPlayer.get())) != null){
                return true;
            }
        }
        return false;
    }

    @Subscribe
    private void onGameTick(GameTick event){
        skipProjectileCheckThisTick = false;
        if (((isRunning() && flickQuickPrayers) || assistFlickPrayers) && PSkills.getCurrentLevel(Skill.PRAYER) > 0) {
            if (shouldPray()){
                if (PVars.getVarbit(Varbits.QUICK_PRAYER) > 0){
                    prayerFlickExecutor.submit(() -> {
                        PUtils.sleepNormal(25, 240);
                        Widget quickPray = PWidgets.get(WidgetInfo.MINIMAP_QUICK_PRAYER_ORB);
                        PInteraction.widget(quickPray, "Deactivate");
                        PUtils.sleepNormal(90, 150);
                        PInteraction.widget(quickPray, "Activate");
                    });
                } else {
                    prayerFlickExecutor.submit(() -> {
                        PUtils.sleepNormal(25, 240);
                        Widget quickPray = PWidgets.get(WidgetInfo.MINIMAP_QUICK_PRAYER_ORB);
                        PInteraction.widget(quickPray, "Activate");
                    });
                }
            } else {
                if (PVars.getVarbit(Varbits.QUICK_PRAYER) != 0){
                    prayerFlickExecutor.submit(() -> {
                        PUtils.sleepNormal(100, 350);
                            Widget quickPray = PWidgets.get(WidgetInfo.MINIMAP_QUICK_PRAYER_ORB);
                            PInteraction.widget(quickPray, "Deactivate");
                    });
                }
            }
        }
    }

    @Subscribe
    protected void onGameStateChanged(GameStateChanged event){
        if (event.getGameState() == GameState.LOGGED_IN){
            readConfig();
        }
    }

    @Override
    protected synchronized void onStart() {
        PUtils.sendGameMessage("AiO Fighter started!");
        readConfig();
        breakScheduler = new PBreakScheduler(breakMinIntervalMinutes, breakMaxIntervalMinutes, breakMinDurationSeconds, breakMaxDurationSeconds);
        fightEnemiesState = new FightEnemiesState(this);
        lootItemsState = new LootItemsState(this);
        walkToFightAreaState = new WalkToFightAreaState(this);
        bankingState = new BankingState(this);
        takeBreaksState = new TakeBreaksState(this);
        setupCannonState = new SetupCannonState(this);
        drinkPotionsState = new DrinkPotionsState(this);

        startedTimestamp = Instant.now();
        if (usingSavedSafeSpot){
            PUtils.sendGameMessage("Loaded safespot from last config.");
        }
        if (usingSavedFightTile){
            PUtils.sendGameMessage("Loaded fight area from last config.");
        }
        if (usingSavedCannonTile){
            PUtils.sendGameMessage("Loaded cannon tile from last config.");
        }
        DaxWalker.setCredentials(PaistiSuite.getDaxCredentialsProvider());
        DaxWalker.getInstance().allowTeleports = true;
        states = new ArrayList<State>();
        states.add(this.bankingState);
        states.add(this.takeBreaksState);
        states.add(this.drinkPotionsState);
        states.add(this.lootItemsState);
        states.add(this.setupCannonState);
        states.add(this.fightEnemiesState);
        states.add(this.walkToFightAreaState);
        currentState = null;
        currentStateName = null;

        licenseValidator = new LicenseValidator("PFIGHTERAIO", 600, apiKey);
        validatorExecutor = Executors.newSingleThreadExecutor();
        validatorExecutor.submit(() -> {
            licenseValidator.startValidating();
        });
    }

    private synchronized void readConfig(){
        // Targeting & tiles
        searchRadius = config.searchRadius();
        enemiesToTarget = PUtils.parseCommaSeparated(config.enemyNames());
        safeSpotForCombat = config.enableSafeSpot();
        safeSpotForLogout = config.exitInSafeSpot();
        enablePathfind = config.enablePathfind();
        validTargetFilter = createValidTargetFilter(false);
        validTargetFilterWithoutDistance = createValidTargetFilter(true);

        // Eating
        foodsToEat = PUtils.parseCommaSeparated(config.foodNames());
        validFoodFilter = createValidFoodFilter();
        maxEatHp = Math.min(PSkills.getActualLevel(Skill.HITPOINTS), config.maxEatHP());
        minEatHp = Math.min(config.minEatHP(), maxEatHp);
        nextEatAt = (int)PUtils.randomNormal(minEatHp, maxEatHp);
        stopWhenOutOfFood = config.stopWhenOutOfFood();
        usePotions = config.usePotions();
        minPotionBoost = Math.max(1, config.minPotionBoost());
        maxPotionBoost = Math.max(config.minPotionBoost(), Math.max(config.maxPotionBoost(), 8));
        minPotionBoost = Math.min(minPotionBoost, maxPotionBoost);

        // Looting
        lootNames = PUtils.parseCommaSeparated(config.lootNames());
        eatFoodForLoot = config.eatForLoot();
        forceLoot = config.forceLoot();
        lootGEValue = config.lootGEValue();
        validLootFilter = createValidLootFilter();
        reservedInventorySlots = 0;
        enableAlching = config.enableAlching();
        alchMaxPriceDifference = config.alchMaxPriceDifference();
        alchMinHAValue = config.alchMinHAValue();

        // Banking
        bankTile = null;
        if (config.bankLocation() != PFighterBanks.AUTODETECT){
            bankTile = config.bankLocation().getWorldPoint();
        }
        bankForFood = config.bankForFood();
        bankForLoot = config.bankForLoot();
        bankingEnabled = config.enableBanking();
        teleportWhileBanking = config.teleportWhileBanking();
        try {
            itemsToWithdraw = new ArrayList<BankingState.WithdrawItem>();
            String[] split = PUtils.parseNewlineSeparated(config.withdrawItems());
            Arrays.stream(split).forEach(s -> log.info("Row: " + s));
            for (String s : split){
                if (s.length() <= 0) continue;
                String[] parts = s.split(":");
                String nameOrId = parts[0];
                int quantity = Integer.parseInt(parts[1].strip());
                itemsToWithdraw.add(new BankingState.WithdrawItem(nameOrId, quantity));
            }
        } catch (Exception e){
            PUtils.sendGameMessage("Error when trying to read withdraw items list");
        }

        // Breaks
        enableBreaks = config.enableBreaks();
        safeSpotForBreaks = config.safeSpotForBreaks();
        breakMinIntervalMinutes = config.minBreakIntervalMinutes();
        breakMaxIntervalMinutes = Math.max(config.maxBreakIntervalMinutes(), breakMinIntervalMinutes);
        breakMinDurationSeconds = config.minBreakDurationSeconds();
        breakMaxDurationSeconds = Math.max(config.maxBreakDurationSeconds(), breakMinDurationSeconds);

        // Prayer
        flickQuickPrayers = config.flickQuickPrayers();
        assistFlickPrayers = config.assistFlickPrayers();
        if (flickQuickPrayers || assistFlickPrayers) {
            if (prayerFlickExecutor == null) prayerFlickExecutor = Executors.newSingleThreadExecutor();
        }

        // Cannoning
        useCannon = config.useCannon();

        // Stored tiles
        if (searchRadiusCenter == null){
            usingSavedFightTile = true;
            searchRadiusCenter = config.storedFightTile();
        }

        if (safeSpot == null && config.storedSafeSpotTile().distanceTo2D(config.storedFightTile()) < 50) {
            safeSpot = config.storedSafeSpotTile();
            usingSavedSafeSpot = true;
        }

        if (cannonTile == null && useCannon && config.storedCannonTile().distanceTo2D(config.storedFightTile()) < 50) {
            usingSavedCannonTile = true;
            cannonTile = config.storedCannonTile();
        }

        // Licensing
        apiKey = config.apiKey();

        log.info("Targeting enemies: " + String.join(", ", enemiesToTarget));
        log.info("Food names: " + String.join(", ", foodsToEat));
        log.info("Loot names: " + String.join(", ", lootNames));
        log.info("Loot over value: " + (lootGEValue <= 0 ? "disabled" : lootGEValue));
        log.info("Min eat: " + minEatHp + " max eat: " + maxEatHp + " next eat: " + nextEatAt);
    }

    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned event)
    {
        GameObject gameObject = event.getGameObject();
        if (gameObject.getId() == CANNON_BASE && !isCannonPlaced())
        {
            Player localPlayer = PPlayer.get();
            if (localPlayer.getWorldLocation().distanceTo(gameObject.getWorldLocation()) <= 2
                    && localPlayer.getAnimation() == AnimationID.BURYING_BONES)
            {
                log.info("Cannon placement started");
                synchronized (cannonVarsLock) {
                    cannonFinished = false;
                    cannonPlaced = true;
                    currentCannonPos = gameObject.getWorldLocation();
                    currentCannonWorld = PUtils.getClient().getWorld();
                }
            }
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (event.getType() != ChatMessageType.SPAM && event.getType() != ChatMessageType.GAMEMESSAGE)
        {
            return;
        }

        if (event.getMessage() == null) return;

        if (event.getMessage().equals("You add the furnace."))
        {
            synchronized (cannonVarsLock){
                cannonPlaced = true;
                cannonFinished = true;
                cannonBallsLeft = 0;
            }
        }

        if (event.getMessage().contains("You pick up the cannon")
                || event.getMessage().contains("Your cannon has decayed. Speak to Nulodion to get a new one!")
                || event.getMessage().contains("Your cannon has been destroyed!"))
        {
            synchronized (cannonVarsLock){
                cannonPlaced = false;
                cannonBallsLeft = 0;
            }
        }

        if (event.getMessage().startsWith("You load the cannon with"))
        {
            Matcher m = NUMBER_PATTERN.matcher(event.getMessage());
            if (m.find())
            {
                // The cannon will usually refill to MAX_CBALLS, but if the
                // player didn't have enough cannonballs in their inventory,
                // it could fill up less than that. Filling the cannon to
                // cballsLeft + amt is not always accurate though because our
                // counter doesn't decrease if the player has been too far away
                // from the cannon due to the projectiels not being in memory,
                // so our counter can be higher than it is supposed to be.
                int amt = 0;
                try {
                    amt = Integer.parseInt(m.group());
                } catch (Exception e){
                    log.error("Error parsing cannonball amount: " + e.getMessage());
                    e.printStackTrace();
                    return;
                }

                synchronized (cannonVarsLock){
                    if (cannonBallsLeft + amt >= 30)
                    {
                        skipProjectileCheckThisTick = true;
                        cannonBallsLeft = 30;
                    }
                    else
                    {
                        cannonBallsLeft += amt;
                    }
                }
            }
            else if (event.getMessage().equals("You load the cannon with one cannonball."))
            {
                synchronized (cannonVarsLock) {
                    if (cannonBallsLeft + 1 >= 30)
                    {
                        skipProjectileCheckThisTick = true;
                        cannonBallsLeft = 30;
                    }
                    else
                    {
                        cannonBallsLeft++;
                    }
                }
            }
        }

        if (event.getMessage().contains("Your cannon is out of ammo!"))
        {
            skipProjectileCheckThisTick = true;
            // If the player was out of range of the cannon, some cannonballs
            // may have been used without the client knowing, so having this
            // extra check is a good idea.
            synchronized (cannonVarsLock){
                cannonBallsLeft = 0;
            }
        }

        if (event.getMessage().startsWith("You unload your cannon and receive Cannonball")
                || event.getMessage().startsWith("You unload your cannon and receive Granite cannonball"))
        {
            skipProjectileCheckThisTick = true;

            synchronized (cannonVarsLock){
                cannonBallsLeft = 0;
            }
        }
    }

    @Subscribe
    public void onProjectileMoved(ProjectileMoved event)
    {
        Projectile projectile = event.getProjectile();

        if ((projectile.getId() == CANNONBALL || projectile.getId() == GRANITE_CANNONBALL) && getCurrentCannonPos() != null && getCurrentCannonWorld() == PUtils.getClient().getWorld())
        {
            WorldPoint projectileLoc = WorldPoint.fromLocal(PUtils.getClient(), projectile.getX1(), projectile.getY1(), PUtils.getClient().getPlane());

            //Check to see if projectile x,y is 0 else it will continuously decrease while ball is flying.
            if (projectileLoc.equals(getCurrentCannonPos()) && projectile.getX() == 0 && projectile.getY() == 0)
            {
                // When there's a chat message about cannon reloaded/unloaded/out of ammo,
                // the message event runs before the projectile event. However they run
                // in the opposite order on the server. So if both fires in the same tick,
                // we don't want to update the cannonball counter if it was set to a specific
                // amount.
                if (!skipProjectileCheckThisTick)
                {
                    synchronized (cannonVarsLock){
                        cannonBallsLeft--;
                    }
                }
            }
        }
    }

    public PTileObject getCannon(){
        WorldPoint searchPos;
        synchronized (cannonVarsLock){
            searchPos = new WorldPoint(currentCannonPos.getX(), currentCannonPos.getY(), currentCannonPos.getPlane());
        }
        return PObjects.findObject(Filters.Objects.idEquals(5,6,7,8,9).and((ob) -> ob.getWorldLocation().distanceTo(searchPos) <= 2));
    }

    public int getCannonBallsLeft() {
        synchronized (cannonVarsLock) {
            return this.cannonBallsLeft;
        }
    }

    public boolean isCannonPlaced(){
        synchronized (cannonVarsLock){
            return this.cannonPlaced;
        }
    }

    public boolean isCannonFinished(){
        synchronized (cannonVarsLock){
            return this.cannonFinished;
        }
    }

    public WorldPoint getCurrentCannonPos(){
        synchronized (cannonVarsLock){
            return this.currentCannonPos;
        }
    }

    public int getCurrentCannonWorld(){
        synchronized (cannonVarsLock) {
            return currentCannonWorld;
        }
    }

    @Override
    protected synchronized void onStop() {
        PUtils.sendGameMessage("AiO Fighter stopped!");
        searchRadiusCenter = null;
        safeSpot = null;
        licenseValidator.requestStop();
    }

    @Override
    protected void shutDown() {
        requestStop();
        licenseValidator.requestStop();
        overlayManager.remove(overlay);
        overlayManager.remove(minimapoverlay);
    }

    public State getValidState(){
        for (State s : states) {
            if (s.condition()) return s;
        }
        return null;
    }

    private boolean checkValidator(){
        if (!licenseValidator.isValid() && !fightEnemiesState.inCombat()) {
            log.info("License Validation error: " + licenseValidator.getLastError());
            PUtils.sendGameMessage("License validation error: " + licenseValidator.getLastError());
            requestStop();
            return false;
        }
        return true;
    }

    @Override
    protected void loop() {
        PUtils.sleepFlat(50, 150);
        if (PUtils.getClient().getGameState() != GameState.LOGGED_IN) return;

        if (!checkValidator()) return;
        if (handleStopConditions()) return;
        handleEating();
        if (isStopRequested()) return;
        handleRun();
        if (isStopRequested()) return;
        handleAntiAfk();
        handleLevelUps();

        State prevState = currentState;
        currentState = getValidState();
        if(!checkValidator()) return;
        if (currentState != null) {
            if (prevState != currentState){
                log.info("Entered state: " + currentState.getName());
            }
            setCurrentStateName(currentState.chainedName());
            currentState.loop();
        } else {
            log.info("Looking for state...");
            setCurrentStateName("Looking for state...");
        }
    }

    private void handleLevelUps(){
        if (PDialogue.isConversationWindowUp()){
            List<Widget> options = PDialogue.getDialogueOptions();
            if (options.stream().anyMatch(o -> o.getText().contains("Click here to continue"))){
                PDialogue.clickHereToContinue();
            }
        }
    }

    private void handleAntiAfk(){
        if (System.currentTimeMillis() - lastAntiAfk >= antiAfkDelay) {
            lastAntiAfk = System.currentTimeMillis();
            antiAfkDelay = PUtils.randomNormal(240000, 295000);
            Keyboard.typeKeysInt(KeyEvent.VK_BACK_SPACE);
            PUtils.sleepNormal(100, 200);
        }
    }

    private boolean executeStop(){
        if (PBanking.isBankOpen() || PBanking.isDepositBoxOpen()) {
            PBanking.closeBank();
            PUtils.sleepNormal(700, 1500);
        }

        if (safeSpotForLogout && PPlayer.location().distanceTo(safeSpot) != 0 && PPlayer.distanceTo(safeSpot) <= 45) {
            if (!PWalking.sceneWalk(safeSpot)) {
                DaxWalker.getInstance().allowTeleports = false;
                DaxWalker.walkTo(new RSTile(safeSpot), walkingCondition);
            }

            return true;
        }

        if (!fightEnemiesState.inCombat()){
            PUtils.logout();
            if  (PUtils.waitCondition(1500, () -> PUtils.getClient().getGameState() != GameState.LOGGED_IN)) {
                requestStop();
            } else {
                PUtils.sleepNormal(700, 2500);
            }
            return true;
        } else if (fightEnemiesState.inCombat()){
            PUtils.sleepNormal(700, 1500);
            return true;
        }

        return false;
    }

    private boolean handleStopConditions(){
        if (stopWhenOutOfFood && PInventory.findItem(validFoodFilter) == null) {
            log.info("Stopping. No food remaining.");
            setCurrentStateName("Stopping. No food remaining.");
            executeStop();
            return true;
        }

        if (this.bankingState.bankingFailure){
            log.info("Stopping. Banking failed.");
            setCurrentStateName("Stopping. Banking failed.");
            executeStop();
            return true;
        }

        return false;
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
            PUtils.sleepNormal(300, 1000);
            return true;
        }
        log.info("Failed to eat food!");
        return false;
    }

    public WalkingCondition walkingCondition = () -> {
        if (isStopRequested()) return WalkingCondition.State.EXIT_OUT_WALKER_FAIL;
        if (PUtils.getClient().getGameState() != GameState.LOGGED_IN && PUtils.getClient().getGameState() != GameState.LOADING) return WalkingCondition.State.EXIT_OUT_WALKER_FAIL;
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
            return new NPCQuery().result(PUtils.getClient())
                    .list.stream().filter(validTargetFilter).collect(Collectors.toList());
        }, "getValidTargets");
    }

    private synchronized Predicate<NPC> createValidTargetFilter(boolean ignoreDistance){
        Predicate<NPC> filter = (NPC n) -> {
            return (ignoreDistance || n.getWorldLocation().distanceToHypotenuse(searchRadiusCenter) <= searchRadius)
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
        filter = filter.and(item -> lootItemsState.haveSpaceForItem(item));
        if (config.lootOwnKills()) filter = filter.and(item -> item.getLootType() == PGroundItem.LootType.PVM);
        filter = filter.and(item -> isReachable(item.getLocation()));
        return filter;
    }

    public Boolean isReachable(WorldPoint p){
        Reachable r = new Reachable();
        return r.canReach(new RSTile(p));
    }

    public Boolean isReachable(NPC n){
        Reachable r = new Reachable();
        return r.canReach(new RSTile(n.getWorldLocation()));
    }

    @Subscribe
    private synchronized void onConfigChanged(ConfigChanged event){
        if (!event.getGroup().equalsIgnoreCase("PFighterAIO")) return;
        readConfig();
    }

    @Subscribe
    private synchronized void onConfigButtonPressed(ConfigButtonClicked configButtonClicked)
    {
        if (!configButtonClicked.getGroup().equalsIgnoreCase("PFighterAIO")) return;
        if (PPlayer.get() == null && PUtils.getClient().getGameState() != GameState.LOGGED_IN) return;

        if (configButtonClicked.getKey().equalsIgnoreCase("startButton"))
        {
            Player player = PPlayer.get();
            try {
                super.start();
            } catch (Exception e){
                log.error(e.toString());
                e.printStackTrace();
            }
        } else if (configButtonClicked.getKey().equalsIgnoreCase("stopButton")){
            requestStop();
        } else if (configButtonClicked.getKey().equalsIgnoreCase("setFightAreaButton")) {
            PUtils.sendGameMessage("Fight area set to your position!");
            configManager.setConfiguration("PFighterAIO", "storedFightTile", PPlayer.location());
            searchRadiusCenter = PPlayer.location();
            usingSavedFightTile = false;
        } else if (configButtonClicked.getKey().equalsIgnoreCase("setSafeSpotButton")) {
            PUtils.sendGameMessage("Safe spot set to your position!");
            configManager.setConfiguration("PFighterAIO", "storedSafeSpotTile", PPlayer.location());
            safeSpot = PPlayer.location();
            usingSavedSafeSpot = false;
        } else if (configButtonClicked.getKey().equalsIgnoreCase("setCannonTileButton")){
            PUtils.sendGameMessage("Cannon tile set to your position!");
            configManager.setConfiguration("PFighterAIO", "storedCannonTile", PPlayer.location());
            cannonTile = PPlayer.location();
            usingSavedCannonTile = false;
        }
    }

    public synchronized String getCurrentStateName() {
        return currentStateName;
    }

    public synchronized void setCurrentStateName(String currentStateName) {
        this.currentStateName = currentStateName;
    }
}
