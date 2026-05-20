package com.AugustBurns;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import com.google.gson.Gson;

import javax.inject.Inject;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@PluginDescriptor(
        name = "Collection Log Expanded",
        description = "Right-click NPCs to view their drop table from the OSRS Wiki",
        tags = {"drops", "collection", "log", "wiki", "npc", "loot"}
)
public class CollectionPlugin extends Plugin
        implements net.runelite.client.input.KeyListener,
        net.runelite.client.input.MouseListener,
        net.runelite.client.input.MouseWheelListener
{
    private static final String USER_AGENT = "RuneLite-collectionlogexpanded/1.0 (RuneLite Plugin)";
    private static final String CONFIG_GROUP = "collectionlogexpanded";
    private static final String CONFIG_POS_X = "overlayPosX";
    private static final String CONFIG_POS_Y = "overlayPosY";
    private static final String CONFIG_SIZE_W = "overlaySizeW";
    private static final String CONFIG_SIZE_H = "overlaySizeH";

    @Inject
    private Client client;

    @Inject
    private Gson gson;

    @Inject
    private ClientThread clientThread;

    @Inject
    private CollectionPluginConfig config;

    @Inject
    private ConfigManager configManager;

    @Inject
    private OkHttpClient okHttpClient;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private KeyManager keyManager;

    @Inject
    private MouseManager mouseManager;

    @Inject
    private CollectionLogOverlay overlay;

    @Inject
    private ItemManager itemManager;

    private CollectionPluginPanel panel;
    private NavigationButton navButton;
    private WikiDropFetcher wikiDropFetcher;
    private DropCacheManager cacheManager;

    // Item name -> item ID mapping (loaded from wiki prices API)
    private final Map<String, Integer> itemNameToId = new ConcurrentHashMap<>();

    // NPC names from spawns + cache for local autocomplete
    private final Set<String> knownNpcNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    // Full monster name list from Category:Monsters (for search autocomplete)
    private List<String> monsterNameCache = new ArrayList<>();

    // Tracks NPC name -> composition ID from spawns (for wiki disambiguation)
    private final Map<String, Integer> recentNpcIds = new ConcurrentHashMap<>();


    // Alt+drag state (move)
    private boolean altDragging = false;
    private int dragStartMouseX;
    private int dragStartMouseY;
    private int dragStartPanelX;
    private int dragStartPanelY;

    // Alt+drag state (resize) — triggered when Alt+left-click lands on the resize handle
    private boolean altResizing = false;
    private int resizeStartMouseX;
    private int resizeStartMouseY;
    private int resizeStartW;
    private int resizeStartH;

    @Override
    protected void startUp()
    {
        wikiDropFetcher = new WikiDropFetcher();
        cacheManager = new DropCacheManager(gson);

        // Side panel
        panel = new CollectionPluginPanel();
        panel.setSearchCallback(this::onPanelSearch);

        BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = icon.createGraphics();
        g.setColor(new java.awt.Color(255, 152, 31));
        g.fillRect(3, 2, 10, 12);
        g.setColor(new java.awt.Color(200, 120, 20));
        g.drawRect(3, 2, 10, 12);
        g.setColor(new java.awt.Color(255, 203, 5));
        g.drawLine(5, 5, 11, 5);
        g.drawLine(5, 7, 11, 7);
        g.drawLine(5, 9, 11, 9);
        g.drawLine(5, 11, 9, 11);
        g.dispose();

        navButton = NavigationButton.builder()
                .tooltip("Collection Log Expanded")
                .icon(icon)
                .priority(5)
                .panel(panel)
                .build();

        clientToolbar.addNavigation(navButton);
        overlayManager.add(overlay);
        keyManager.registerKeyListener(this);
        mouseManager.registerMouseListener(this);
        mouseManager.registerMouseWheelListener(this);

        // Wire overlay callbacks and pass ItemManager
        overlay.setSearchCallback(this::lookupNpcBySearch);
        overlay.setItemManager(itemManager);
        overlay.setDataChangedCallback(this::onOverlayDataChanged);

        // Load saved overlay position
        loadSavedPosition();

        // Seed local autocomplete with cached NPC names
        knownNpcNames.addAll(cacheManager.getCachedNpcNames());
        refreshAvailableNames();

        // Load item name -> ID mapping for icons
        loadItemMapping();

        // Load monster name list for search autocomplete
        loadMonsterNameCache();

        log.debug("Collection Log Expanded started");
    }

    @Override
    protected void shutDown()
    {
        overlay.hide();
        overlayManager.remove(overlay);
        clientToolbar.removeNavigation(navButton);
        keyManager.unregisterKeyListener(this);
        mouseManager.unregisterMouseListener(this);
        mouseManager.unregisterMouseWheelListener(this);
        knownNpcNames.clear();
        itemNameToId.clear();
        recentNpcIds.clear();
        monsterNameCache.clear();
        altDragging = false;
        altResizing = false;

        log.debug("Collection Log Expanded stopped");
    }

    @Provides
    CollectionPluginConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(CollectionPluginConfig.class);
    }

    // ================================================================
    //  ITEM MAPPING (name -> ID for icons)
    // ================================================================

    /**
     * Fetches the full item name->ID mapping from the OSRS Wiki prices API.
     * This runs once on startup and populates itemNameToId for icon resolution.
     */
    private void loadItemMapping()
    {
        String url = "https://prices.runescape.wiki/api/v1/osrs/mapping";

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build();

        okHttpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.warn("Failed to load item mapping: {}", e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                try
                {
                    if (!response.isSuccessful())
                    {
                        log.warn("Item mapping API returned HTTP {}", response.code());
                        return;
                    }

                    String body = response.body().string();
                    JsonArray items = new JsonParser().parse(body).getAsJsonArray();

                    for (JsonElement element : items)
                    {
                        JsonObject item = element.getAsJsonObject();
                        if (item.has("name") && item.has("id"))
                        {
                            String name = item.get("name").getAsString();
                            int id = item.get("id").getAsInt();
                            itemNameToId.put(name.toLowerCase(), id);
                        }
                    }

                    log.debug("Loaded {} item name->ID mappings", itemNameToId.size());
                }
                catch (Exception e)
                {
                    log.warn("Error parsing item mapping: {}", e.getMessage());
                }
                finally
                {
                    response.close();
                }
            }
        });
    }

    /**
     * Resolves item IDs by name for all drops in the data.
     * Called after fetching drops from wiki (since wiki doesn't include IDs).
     * Uses multiple matching strategies to maximize icon coverage.
     */
    private void resolveItemIds(NpcDropData data)
    {
        for (NpcDropData.DropSection section : data.getSections())
        {
            for (NpcDropData.DropItem item : section.getItems())
            {
                if (item.getId() <= 0 && item.getName() != null)
                {
                    String name = item.getName();
                    Integer id = null;

                    // 1) Exact match (case-insensitive)
                    id = itemNameToId.get(name.toLowerCase());

                    // 2) Try with straight apostrophe replaced by curly and vice versa
                    if (id == null)
                    {
                        id = itemNameToId.get(name.toLowerCase().replace("\u2019", "'"));
                    }
                    if (id == null)
                    {
                        id = itemNameToId.get(name.toLowerCase().replace("'", "\u2019"));
                    }

                    // 3) Strip trailing parenthetical like "(p++)", "(noted)", "(4)", "(empty)"
                    if (id == null)
                    {
                        String stripped = name.replaceAll("\\s*\\([^)]*\\)$", "").trim();
                        if (!stripped.equalsIgnoreCase(name))
                        {
                            id = itemNameToId.get(stripped.toLowerCase());
                        }
                    }

                    // 4) Try adding common suffixes that wiki might omit
                    if (id == null && !name.contains("("))
                    {
                        // Some items in the database have qualifiers the wiki omits
                        for (String suffix : new String[]{"(1)", "(4)", "(3)", "(2)"})
                        {
                            id = itemNameToId.get((name + " " + suffix).toLowerCase());
                            if (id != null) break;
                        }
                    }

                    // 5) Trim extra whitespace and special characters
                    if (id == null)
                    {
                        String cleaned = name.replaceAll("[^a-zA-Z0-9 ()'+\\-]", "").trim();
                        cleaned = cleaned.replaceAll("\\s+", " ");
                        if (!cleaned.equalsIgnoreCase(name))
                        {
                            id = itemNameToId.get(cleaned.toLowerCase());
                        }
                    }

                    if (id != null)
                    {
                        item.setId(id);
                    }
                }
            }
        }
    }

    /**
     * Resolves item IDs for untradeable items that weren't in the prices API.
     * Fetches wiki page content in batch and parses item IDs from the infobox.
     * Calls onComplete when done (whether successful or not).
     */
    private void resolveUntradeableItemIds(NpcDropData data, Runnable onComplete)
    {
        // Collect item names still missing IDs
        List<String> missingNames = new ArrayList<>();
        for (NpcDropData.DropSection section : data.getSections())
        {
            for (NpcDropData.DropItem item : section.getItems())
            {
                if (item.getId() <= 0 && item.getName() != null && !item.getName().isEmpty())
                {
                    if (!missingNames.contains(item.getName()))
                    {
                        missingNames.add(item.getName());
                    }
                }
            }
        }

        if (missingNames.isEmpty())
        {
            onComplete.run();
            return;
        }

        // Batch fetch wiki pages (up to 50 per API call) and parse item IDs from infoboxes
        List<String> batch = missingNames.subList(0, Math.min(missingNames.size(), 50));

        // Encode each name individually, then join with pipe separator (NOT encoded)
        // MediaWiki API uses | as title separator; encoding it to %7C breaks the query
        StringBuilder encodedTitles = new StringBuilder();
        for (int i = 0; i < batch.size(); i++)
        {
            if (i > 0) encodedTitles.append("|");
            try
            {
                encodedTitles.append(
                        java.net.URLEncoder.encode(batch.get(i), "UTF-8")
                                .replace("+", "%20")
                );
            }
            catch (Exception e)
            {
                encodedTitles.append(batch.get(i).replace(" ", "%20"));
            }
        }

        String url = "https://oldschool.runescape.wiki/api.php?action=query"
                + "&titles=" + encodedTitles.toString()
                + "&prop=revisions&rvprop=content&rvslots=main&format=json&redirects=1";

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build();

        okHttpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.debug("Wiki page batch fetch failed: {}", e.getMessage());
                onComplete.run();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                try
                {
                    if (!response.isSuccessful())
                    {
                        onComplete.run();
                        return;
                    }

                    String body = response.body().string();
                    JsonObject root = new JsonParser().parse(body).getAsJsonObject();
                    JsonObject query = root.getAsJsonObject("query");
                    if (query == null)
                    {
                        onComplete.run();
                        return;
                    }

                    JsonObject pages = query.getAsJsonObject("pages");
                    if (pages == null)
                    {
                        onComplete.run();
                        return;
                    }

                    // Parse item ID from each page's infobox: |id = 1234 or |itemid = 1234
                    java.util.regex.Pattern idPattern = java.util.regex.Pattern.compile(
                            "\\|\\s*(?:id|itemid)\\s*=\\s*(\\d+)");

                    Map<String, Integer> resolved = new HashMap<>();

                    for (Map.Entry<String, JsonElement> entry : pages.entrySet())
                    {
                        try
                        {
                            JsonObject page = entry.getValue().getAsJsonObject();
                            if (page.has("missing")) continue;

                            String pageTitle = page.get("title").getAsString();
                            JsonArray revisions = page.getAsJsonArray("revisions");
                            if (revisions == null || revisions.size() == 0) continue;

                            JsonObject rev = revisions.get(0).getAsJsonObject();
                            String content;
                            if (rev.has("slots"))
                            {
                                content = rev.getAsJsonObject("slots")
                                        .getAsJsonObject("main")
                                        .get("*").getAsString();
                            }
                            else if (rev.has("*"))
                            {
                                content = rev.get("*").getAsString();
                            }
                            else
                            {
                                continue;
                            }

                            java.util.regex.Matcher m = idPattern.matcher(content);
                            if (m.find())
                            {
                                int id = Integer.parseInt(m.group(1));
                                if (id > 0)
                                {
                                    resolved.put(pageTitle.toLowerCase(), id);
                                    itemNameToId.put(pageTitle.toLowerCase(), id);
                                }
                            }
                        }
                        catch (Exception ignored) {}
                    }

                    // Apply resolved IDs to items
                    for (NpcDropData.DropSection section : data.getSections())
                    {
                        for (NpcDropData.DropItem item : section.getItems())
                        {
                            if (item.getId() <= 0 && item.getName() != null)
                            {
                                Integer id = resolved.get(item.getName().toLowerCase());
                                if (id != null)
                                {
                                    item.setId(id);
                                }
                            }
                        }
                    }

                    log.debug("Resolved {} untradeable item IDs via wiki pages", resolved.size());
                    onComplete.run();
                }
                catch (Exception e)
                {
                    log.debug("Error parsing wiki page batch response: {}", e.getMessage());
                    onComplete.run();
                }
                finally
                {
                    response.close();
                }
            }
        });
    }

    // ================================================================
    //  MONSTER NAME CACHE (Category:Monsters)
    // ================================================================

    /**
     * Loads the monster name list from local cache. If the cache is empty or stale,
     * fetches the full list from Category:Monsters and saves it for future use.
     * This list powers the search bar autocomplete with monster-only results.
     */
    private void loadMonsterNameCache()
    {
        monsterNameCache = cacheManager.loadMonsterNames();
        if (!monsterNameCache.isEmpty())
        {
            log.debug("Loaded {} monster names from cache", monsterNameCache.size());
            refreshAvailableNames();
        }

        // Refresh from wiki if cache is old or empty (7 day expiry)
        if (!cacheManager.isMonsterCacheValid(7))
        {
            wikiDropFetcher.fetchAllMonsterNames(okHttpClient, names ->
            {
                if (names != null && !names.isEmpty())
                {
                    monsterNameCache = new ArrayList<>(names);
                    cacheManager.saveMonsterNames(names);
                    clientThread.invokeLater(() -> refreshAvailableNames());
                    log.debug("Refreshed monster cache with {} names from wiki", names.size());
                }
            });
        }
    }

    // ================================================================
    //  MENU ENTRY (with NPC ID capture)
    // ================================================================

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event)
    {
        if (event.getType() == MenuAction.EXAMINE_NPC.getId())
        {
            String npcName = cleanNpcName(event.getTarget());

            // Only add "Collection Log" for NPCs that have a combat level.
            NPC matchedNpc = null;
            for (NPC npc : client.getNpcs())
            {
                if (npc != null && npcName.equalsIgnoreCase(npc.getName()))
                {
                    matchedNpc = npc;
                    break;
                }
            }
            if (matchedNpc == null || matchedNpc.getCombatLevel() <= 0)
            {
                return;
            }

            // #3: If "Require Shift for Menu" is enabled, only add the entry when Shift is held
            if (config.requireShiftForMenu() && !client.isKeyPressed(net.runelite.api.KeyCode.KC_SHIFT))
            {
                return;
            }

            int npcId = recentNpcIds.getOrDefault(npcName.toLowerCase(), -1);
            final String finalNpcName = npcName;
            final int finalNpcId = npcId;

            client.getMenu().createMenuEntry(-1)
                    .setOption("Collection Log")
                    .setTarget(event.getTarget())
                    .setType(MenuAction.RUNELITE)
                    .onClick(e -> lookupNpc(finalNpcName, finalNpcId));
        }
    }

    // ================================================================
    //  NPC TRACKING (for local autocomplete)
    // ================================================================

    @Subscribe
    public void onNpcSpawned(NpcSpawned event)
    {
        NPC npc = event.getNpc();
        String name = npc.getName();
        if (name == null || name.isEmpty()) return;

        // Track composition ID for wiki disambiguation
        recentNpcIds.put(name.toLowerCase(), npc.getId());

        boolean isNew = knownNpcNames.add(name);
        if (isNew)
        {
            refreshAvailableNames();
        }

        // #11: Auto-preload — silently fetch drop data for nearby combat NPCs
        // so kill tracking begins immediately without the user opening the log first.
        if (config.autoPreloadNearbyNpcs()
                && npc.getCombatLevel() > 0
                && cacheManager.loadFromCache(name) == null)
        {
            final String npcName = name;
            final int npcId = npc.getId();
            // Defer the network call off the game thread with a short delay to avoid
            // flooding the wiki API when entering an area with many new NPCs.
            new Thread(() ->
            {
                try { Thread.sleep(1500 + (int)(Math.random() * 2000)); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                silentlyPrefetchDropData(npcName, npcId);
            }, "cl-expanded-prefetch-" + npcName).start();
        }
    }

    /**
     * Fetches drop data for an NPC in the background without showing the overlay.
     * Used by auto-preload (#11). Saves to cache so kill tracking can begin.
     * Does nothing if data is already cached by the time the fetch completes.
     */
    private void silentlyPrefetchDropData(String npcName, int npcId)
    {
        // Double-check the cache (another thread may have fetched it in the meantime)
        if (cacheManager.loadFromCache(npcName) != null) return;

        log.debug("Auto-preloading drop data for {}", npcName);
        wikiDropFetcher.fetchDropTable(npcName, okHttpClient, new WikiDropFetcher.FetchCallback()
        {
            @Override
            public void onSuccess(NpcDropData data)
            {
                // Merge any existing obtained status before saving
                NpcDropData existing = cacheManager.loadFromCache(npcName);
                if (existing != null)
                {
                    mergeObtainedStatus(existing, data);
                    data.setKillCount(existing.getKillCount());
                }
                cacheManager.saveToCache(data);
                knownNpcNames.add(data.getNpcName());
                refreshAvailableNames();
                log.debug("Auto-preloaded {} drops for {}", data.getTotalDropCount(), npcName);
            }

            @Override
            public void onError(String errorMessage)
            {
                // Silent — no UI for background prefetch failures
                log.debug("Auto-preload failed for {}: {}", npcName, errorMessage);
            }

            @Override
            public void onDisambiguation(String npcName2, List<String> options)
            {
                // Disambiguation requires user input — silently skip
                log.debug("Auto-preload skipped disambiguation for {}", npcName2);
            }
        });
    }

    // ================================================================
    //  LOOT TRACKING (obtained items)
    // ================================================================

    @Subscribe
    public void onNpcLootReceived(NpcLootReceived event)
    {
        NPC npc = event.getNpc();
        if (npc == null || npc.getName() == null)
        {
            return;
        }

        String npcName = npc.getName();
        NpcDropData cached = cacheManager.loadFromCache(npcName);
        if (cached == null)
        {
            return;
        }

        boolean changed = false;
        Collection<ItemStack> loot = event.getItems();

        for (ItemStack lootItem : loot)
        {
            int lootId = lootItem.getId();
            int lootQty = lootItem.getQuantity();

            // Get the item's display name for name-based matching
            String lootName = null;
            try
            {
                ItemComposition comp = itemManager.getItemComposition(lootId);
                if (comp != null)
                {
                    lootName = comp.getName();

                    // If this is a noted item, also get the un-noted ID for matching
                    if (comp.getNote() != -1)
                    {
                        int unnotedId = comp.getLinkedNoteId();
                        if (unnotedId > 0)
                        {
                            lootId = unnotedId;
                        }
                    }
                }
            }
            catch (Exception ignored)
            {
            }

            // Find the best matching drop entry, preferring quantity-specific match
            NpcDropData.DropItem bestMatch = null;
            NpcDropData.DropItem nameOnlyMatch = null;

            for (NpcDropData.DropSection section : cached.getSections())
            {
                for (NpcDropData.DropItem drop : section.getItems())
                {
                    boolean idMatch = drop.getId() > 0 && drop.getId() == lootId;
                    boolean nameMatch = lootName != null && drop.getName() != null
                            && drop.getName().equalsIgnoreCase(lootName);

                    if (idMatch || nameMatch)
                    {
                        // Check if quantity matches this specific drop entry
                        if (quantityMatchesDrop(lootQty, drop.getQuantity()))
                        {
                            bestMatch = drop;
                            break;
                        }
                        else if (nameOnlyMatch == null)
                        {
                            nameOnlyMatch = drop;
                        }
                    }
                }
                if (bestMatch != null) break;
            }

            // Use quantity-specific match, or fall back to first name/ID match
            NpcDropData.DropItem matched = (bestMatch != null) ? bestMatch : nameOnlyMatch;
            if (matched != null)
            {
                matched.incrementObtainedCount();
                changed = true;
            }
        }

        if (changed)
        {
            cacheManager.saveToCache(cached);

            if (overlay.isVisible()
                    && overlay.getDropData() != null
                    && npcName.equalsIgnoreCase(overlay.getDropData().getNpcName()))
            {
                clientThread.invokeLater(() -> overlay.updateDropData(cached));
            }

            log.debug("Updated obtained items for {}", npcName);
        }

        // Always increment kill count for any NPC we have cached drop data for.
        // A loot event reliably signals one kill even if no new drops were obtained.
        cached.incrementKillCount();
        cacheManager.saveToCache(cached);

        if (overlay.isVisible()
                && overlay.getDropData() != null
                && npcName.equalsIgnoreCase(overlay.getDropData().getNpcName()))
        {
            clientThread.invokeLater(() -> overlay.updateDropData(cached));
        }
    }

    // ================================================================
    //  DAMAGE DETECTION
    // ================================================================

    @Subscribe
    public void onHitsplatApplied(HitsplatApplied event)
    {
        if (config.closeOnDamage()
                && overlay.isVisible()
                && event.getActor() == client.getLocalPlayer())
        {
            overlay.hide();
        }
    }

    // ================================================================
    //  KEYBOARD INPUT
    // ================================================================

    /**
     * Listens for config changes. The "clearDropCache" item acts as a trigger button:
     * when it flips to true the plugin clears all cached drop data and immediately
     * resets the value back to false so it behaves like a momentary button press.
     */
    @Subscribe
    public void onConfigChanged(net.runelite.client.events.ConfigChanged event)
    {
        if (!event.getGroup().equals("collectionlogexpanded")) return;

        if (event.getKey().equals("clearDropCache") && "true".equals(event.getNewValue()))
        {
            // Require the confirmation checkbox to also be ticked
            if (!config.confirmClearCache())
            {
                // Reset the button and tell the user to tick confirm first
                configManager.setConfiguration("collectionlogexpanded", "clearDropCache", false);
                clientThread.invokeLater(() ->
                    client.addChatMessage(
                        net.runelite.api.ChatMessageType.GAMEMESSAGE,
                        "",
                        "<col=ff6600>[Collection Log Expanded]</col> Tick <col=ffffff>'Confirm: I understand this is permanent'</col> first, then tick 'Clear Drop Cache Now'.",
                        null)
                );
                return;
            }

            cacheManager.clearCache();
            // Reset both checkboxes
            configManager.setConfiguration("collectionlogexpanded", "clearDropCache", false);
            configManager.setConfiguration("collectionlogexpanded", "confirmClearCache", false);
            log.debug("Drop cache cleared via config button");

            if (overlay.isVisible())
            {
                overlay.hide();
            }

            clientThread.invokeLater(() ->
                client.addChatMessage(
                    net.runelite.api.ChatMessageType.GAMEMESSAGE,
                    "",
                    "<col=00ff00>[Collection Log Expanded]</col> Drop cache cleared. All obtained marks and kill counts have been removed. Next lookup will fetch fresh data from the wiki.",
                    null)
            );
        }
    }

    @Override
    public void keyPressed(KeyEvent e)
    {
        if (!overlay.isVisible())
        {
            return;
        }

        if (overlay.isSearchFocused())
        {
            overlay.handleSearchKeyPress(e);
            e.consume();
            return;
        }

        if (config.closeOnEscape() && e.getKeyCode() == KeyEvent.VK_ESCAPE)
        {
            overlay.hide();
            e.consume();
        }
    }

    @Override
    public void keyTyped(KeyEvent e)
    {
        if (overlay.isVisible() && overlay.isSearchFocused())
        {
            char c = e.getKeyChar();
            if (c >= 32 && c < 127)
            {
                overlay.appendSearchChar(c);
            }
            e.consume();
        }
    }

    @Override
    public void keyReleased(KeyEvent e)
    {
    }

    // ================================================================
    //  MOUSE INPUT (with Alt+drag support)
    // ================================================================

    @Override
    public MouseEvent mousePressed(MouseEvent e)
    {
        if (!overlay.isVisible()) return e;

        // Middle click always passes through for camera
        if (e.getButton() == MouseEvent.BUTTON2) return e;

        Point point = e.getPoint();
        boolean altDown = e.isAltDown();

        // Alt + right-click on overlay = reset position to center
        if (altDown && e.getButton() == MouseEvent.BUTTON3 && overlay.isInBounds(point))
        {
            overlay.resetPosition();
            overlay.resetSize();
            savePosition();
            saveSize();
            return null;
        }

        // Alt + left-click on overlay resize handle = start resizing
        if (altDown && e.getButton() == MouseEvent.BUTTON1 && overlay.isOnResizeHandle(point))
        {
            altResizing = true;
            resizeStartMouseX = point.x;
            resizeStartMouseY = point.y;
            resizeStartW = overlay.getCurrentWidth();
            resizeStartH = overlay.getCurrentHeight();
            return null;
        }

        // Alt + left-click on overlay (not resize handle) = start dragging
        if (altDown && e.getButton() == MouseEvent.BUTTON1 && overlay.isInBounds(point))
        {
            altDragging = true;
            dragStartMouseX = point.x;
            dragStartMouseY = point.y;
            dragStartPanelX = overlay.getCustomX();
            dragStartPanelY = overlay.getCustomY();

            // If panel was centered (custom == -1), get actual rendered position
            if (dragStartPanelX < 0 || dragStartPanelY < 0)
            {
                Rectangle b = overlay.getCurrentBounds();
                if (b != null)
                {
                    dragStartPanelX = b.x;
                    dragStartPanelY = b.y;
                }
                else
                {
                    dragStartPanelX = 0;
                    dragStartPanelY = 0;
                }
            }
            return null;
        }

        // Normal overlay interaction
        if (overlay.isInBounds(point))
        {
            overlay.handleMousePress(point);
            return null;
        }
        else if (config.closeOnClickOutside())
        {
            overlay.hide();
            return e;
        }
        return e;
    }

    @Override
    public MouseEvent mouseClicked(MouseEvent e) { return e; }

    @Override
    public MouseEvent mouseReleased(MouseEvent e)
    {
        if (!overlay.isVisible()) return e;
        if (e.getButton() == MouseEvent.BUTTON2) return e;

        // End Alt+resize
        if (altResizing)
        {
            altResizing = false;
            saveSize();
            return null;
        }

        // End Alt+drag
        if (altDragging)
        {
            altDragging = false;
            savePosition();
            return null;
        }

        overlay.handleMouseRelease();
        if (overlay.isInBounds(e.getPoint())) return null;
        return e;
    }

    @Override
    public MouseEvent mouseEntered(MouseEvent e) { return e; }

    @Override
    public MouseEvent mouseExited(MouseEvent e) { return e; }

    @Override
    public MouseEvent mouseDragged(MouseEvent e)
    {
        if (!overlay.isVisible()) return e;
        if ((e.getModifiersEx() & MouseEvent.BUTTON2_DOWN_MASK) != 0) return e;

        overlay.updateMousePosition(e.getPoint());

        // Alt+resize drags the bottom-right corner
        if (altResizing)
        {
            int dx = e.getPoint().x - resizeStartMouseX;
            int dy = e.getPoint().y - resizeStartMouseY;
            overlay.setCustomSize(resizeStartW + dx, resizeStartH + dy);
            return null;
        }

        // Alt+drag moves the overlay window
        if (altDragging)
        {
            int dx = e.getPoint().x - dragStartMouseX;
            int dy = e.getPoint().y - dragStartMouseY;
            overlay.setCustomPosition(dragStartPanelX + dx, dragStartPanelY + dy);
            return null;
        }

        if (overlay.handleMouseDrag(e.getPoint())) return null;
        return e;
    }

    @Override
    public MouseEvent mouseMoved(MouseEvent e)
    {
        if (overlay.isVisible())
        {
            overlay.updateMousePosition(e.getPoint());
        }
        return e;
    }

    // ================================================================
    //  MOUSE WHEEL
    // ================================================================

    @Override
    public MouseWheelEvent mouseWheelMoved(MouseWheelEvent e)
    {
        if (!overlay.isVisible()) return e;
        if (overlay.isInBounds(e.getPoint()))
        {
            overlay.scroll(e.getWheelRotation());
            return null;
        }
        return e;
    }

    // ================================================================
    //  CORE LOOKUP (with NPC ID resolution)
    // ================================================================

    /**
     * Looks up drops for an NPC by name.
     * Uses the wiki's redirect support to handle most disambiguation automatically.
     * The NPC ID parameter is reserved for future use.
     */
    private void lookupNpc(String npcName, int npcId)
    {
        if (npcName == null || npcName.isEmpty()) return;

        log.debug("Looking up drops for: {} (npcId={})", npcName, npcId);

        // Check cache first
        NpcDropData cached = cacheManager.loadFromCache(npcName);
        if (cached != null && cacheManager.isCacheValid(cached, config.cacheExpiryDays()))
        {
            // Re-resolve IDs in case mapping loaded after initial cache
            resolveItemIds(cached);
            log.debug("Using data for {}", npcName);
            overlay.showDropData(cached);
            panel.displayDropData(cached);
            panel.setStatus("Showing cdrops for: " + npcName);
            return;
        }

        overlay.showLoading(npcName);
        panel.setStatus("Loading drops for: " + npcName + "...");
        panel.clearResults();

        // Fetch directly using the NPC name - wiki's redirects=1 handles most disambiguation
        fetchDropsForNpc(npcName, npcName, cached);
    }

    /**
     * Called from the search bar or suggestion dropdown.
     * Uses the name directly since opensearch already provided the correct page title.
     */
    private void lookupNpcBySearch(String npcName)
    {
        if (npcName == null || npcName.isEmpty()) return;
        if (npcName.toLowerCase().contains("party pete"))
        {
            overlay.showPartyMode();
            return;
        }
        lookupNpc(npcName, -1);
    }

    /**
     * Fetches and displays drop data from the wiki.
     * @param wikiName The resolved wiki page name to query
     * @param displayName The name to show in the UI
     * @param oldCached Previous cached data (for merging obtained status)
     */
    private void fetchDropsForNpc(String wikiName, String displayName, NpcDropData oldCached)
    {
        wikiDropFetcher.fetchDropTable(wikiName, okHttpClient, new WikiDropFetcher.FetchCallback()
        {
            @Override
            public void onSuccess(NpcDropData data)
            {
                // Resolve item IDs by name (wiki doesn't provide IDs)
                resolveItemIds(data);

                // Preserve obtained status from old cache
                if (oldCached != null)
                {
                    mergeObtainedStatus(oldCached, data);
                }

                // Resolve untradeable item IDs via wiki pages, then display
                resolveUntradeableItemIds(data, () ->
                {
                    cacheManager.saveToCache(data);
                    knownNpcNames.add(data.getNpcName());

                    clientThread.invokeLater(() -> overlay.showDropData(data));
                    panel.displayDropData(data);
                    panel.setStatus("Showing " + data.getTotalDropCount() + " drops for: " + data.getNpcName());

                    log.debug("Loaded {} drops for {}", data.getTotalDropCount(), data.getNpcName());
                });
            }

            @Override
            public void onError(String errorMessage)
            {
                clientThread.invokeLater(() -> overlay.showError(displayName, errorMessage));
                panel.setStatus("Error: " + errorMessage);
                panel.clearResults();
                log.warn("Drop lookup failed for {}: {}", wikiName, errorMessage);
            }

            @Override
            public void onDisambiguation(String npcName, List<String> options)
            {
                clientThread.invokeLater(() -> overlay.showDisambiguation(npcName, options));
                panel.setStatus("Multiple matches for: " + npcName + " (" + options.size() + " options)");
                log.debug("Disambiguation page for {} with {} options", npcName, options.size());
            }
        });
    }

    private void mergeObtainedStatus(NpcDropData oldData, NpcDropData newData)
    {
        for (NpcDropData.DropSection newSection : newData.getSections())
        {
            for (NpcDropData.DropItem newItem : newSection.getItems())
            {
                for (NpcDropData.DropSection oldSection : oldData.getSections())
                {
                    for (NpcDropData.DropItem oldItem : oldSection.getItems())
                    {
                        if (oldItem.isObtained()
                                && oldItem.getName() != null
                                && oldItem.getName().equalsIgnoreCase(newItem.getName()))
                        {
                            // Match by name AND quantity to avoid cross-contaminating counts
                            // between same-name items at different quantities
                            String oldQty = oldItem.getQuantity() != null ? oldItem.getQuantity() : "";
                            String newQty = newItem.getQuantity() != null ? newItem.getQuantity() : "";
                            if (oldQty.equals(newQty))
                            {
                                newItem.setObtained(true);
                                newItem.setObtainedCount(oldItem.getObtainedCount());
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Checks if a received loot quantity matches a drop table quantity string.
     * Handles ranges ("100-200"), lists ("5;10;15"), exact values ("15"),
     * and annotations like "(noted)".
     */
    private boolean quantityMatchesDrop(int receivedQty, String dropQuantity)
    {
        if (dropQuantity == null || dropQuantity.isEmpty() || dropQuantity.equals("1"))
        {
            return receivedQty == 1;
        }

        // Strip annotations like "(noted)"
        String qty = dropQuantity.replaceAll("\\s*\\(noted\\)", "").trim();

        // Handle semicolon-separated lists: "5;10;15"
        if (qty.contains(";"))
        {
            for (String part : qty.split(";"))
            {
                if (matchesSingleQuantity(receivedQty, part.trim()))
                {
                    return true;
                }
            }
            return false;
        }

        // Handle comma-separated lists: "5,10,15"
        if (qty.contains(","))
        {
            for (String part : qty.split(","))
            {
                if (matchesSingleQuantity(receivedQty, part.trim()))
                {
                    return true;
                }
            }
            return false;
        }

        return matchesSingleQuantity(receivedQty, qty);
    }

    private boolean matchesSingleQuantity(int receivedQty, String qty)
    {
        // Handle ranges: "100-200" or "100\u2013200" (en-dash)
        String[] rangeParts = qty.split("[-\u2013]");
        if (rangeParts.length == 2)
        {
            try
            {
                int low = Integer.parseInt(rangeParts[0].trim());
                int high = Integer.parseInt(rangeParts[1].trim());
                return receivedQty >= low && receivedQty <= high;
            }
            catch (NumberFormatException ignored) {}
        }

        // Handle exact value
        try
        {
            return receivedQty == Integer.parseInt(qty.trim());
        }
        catch (NumberFormatException ignored) {}

        return false;
    }

    // ================================================================
    //  POSITION PERSISTENCE
    // ================================================================

    private void loadSavedPosition()
    {
        try
        {
            String xStr = configManager.getConfiguration(CONFIG_GROUP, CONFIG_POS_X);
            String yStr = configManager.getConfiguration(CONFIG_GROUP, CONFIG_POS_Y);
            if (xStr != null && yStr != null)
            {
                int x = Integer.parseInt(xStr);
                int y = Integer.parseInt(yStr);
                overlay.setCustomPosition(x, y);
                log.debug("Loaded saved overlay position: {}, {}", x, y);
            }

            String wStr = configManager.getConfiguration(CONFIG_GROUP, CONFIG_SIZE_W);
            String hStr = configManager.getConfiguration(CONFIG_GROUP, CONFIG_SIZE_H);
            if (wStr != null && hStr != null)
            {
                int w = Integer.parseInt(wStr);
                int h = Integer.parseInt(hStr);
                overlay.setCustomSize(w, h);
                log.debug("Loaded saved overlay size: {}x{}", w, h);
            }
        }
        catch (NumberFormatException ignored)
        {
        }
    }

    private void savePosition()
    {
        int x = overlay.getCustomX();
        int y = overlay.getCustomY();
        configManager.setConfiguration(CONFIG_GROUP, CONFIG_POS_X, String.valueOf(x));
        configManager.setConfiguration(CONFIG_GROUP, CONFIG_POS_Y, String.valueOf(y));
    }

    private void saveSize()
    {
        int w = overlay.getCustomWidth();
        int h = overlay.getCustomHeight();
        if (w > 0 && h > 0)
        {
            configManager.setConfiguration(CONFIG_GROUP, CONFIG_SIZE_W, String.valueOf(w));
            configManager.setConfiguration(CONFIG_GROUP, CONFIG_SIZE_H, String.valueOf(h));
        }
    }

    // ================================================================
    //  OVERLAY DATA CHANGED CALLBACK
    // ================================================================

    /**
     * Called by the overlay when item obtained status changes via click-to-toggle.
     * Saves updated data to cache.
     */
    private void onOverlayDataChanged()
    {
        NpcDropData data = overlay.getDropData();
        if (data != null)
        {
            cacheManager.saveToCache(data);
            log.debug("Saved obtained status changes for {}", data.getNpcName());
        }
    }

    // ================================================================
    //  HELPERS
    // ================================================================

    private void onPanelSearch()
    {
        String searchText = panel.getSearchText();
        if (searchText != null && !searchText.isEmpty())
        {
            lookupNpcBySearch(searchText);
        }
    }

    private void refreshAvailableNames()
    {
        if (!monsterNameCache.isEmpty())
        {
            // Use full monster list from Category:Monsters
            overlay.setAvailableNpcNames(new ArrayList<>(monsterNameCache));
        }
        else
        {
            // Fallback to spawned + cached NPC names until monster list loads
            List<String> names = new ArrayList<>(knownNpcNames);
            overlay.setAvailableNpcNames(names);
        }
    }

    private String cleanNpcName(String text)
    {
        if (text == null) return "";
        // Strip color tags like <col=ffff00>
        text = text.replaceAll("<[^>]*>", "");
        // Strip combat level like (level-2)
        text = text.replaceAll("\\s*\\(level-\\d+\\)", "");
        return text.trim();
    }
}