package com.AugustBurns;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class WikiDropFetcher
{
    private static final String WIKI_API_URL = "https://oldschool.runescape.wiki/api.php";
    private static final String USER_AGENT = "RuneLite-collectionlogexpanded/1.0 (RuneLite Plugin; https://github.com/runelite)";

    private static final Pattern DROPS_LINE_PATTERN = Pattern.compile("\\{\\{DropsLine", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECTION_HEADER_PATTERN = Pattern.compile("^={3,4}\\s*(.+?)\\s*={3,4}$", Pattern.MULTILINE);
    private static final Pattern FRACTION_PATTERN = Pattern.compile("\\{\\{Fraction\\|([^|{}]+)\\|([^|{}]+)\\}\\}");

    // Sub-table templates that need HTML rendering to parse.
    // Also matches any template ending in "DropTable", "DropLines", or "DropTableInfo"
    // to catch variants like GeneralSeedDropLines, WildernessSlayerDropTable, etc.
    private static final Pattern SUB_TABLE_PATTERN = Pattern.compile(
            "\\{\\{\\w*(?:DropTable|DropLines|DropTableInfo)(?:\\||\\}})",
            Pattern.CASE_INSENSITIVE
    );

    // Disambiguation page detection
    private static final Pattern DISAMBIG_PATTERN = Pattern.compile(
            "\\{\\{[Dd]isamb", Pattern.CASE_INSENSITIVE
    );

    // Extract [[Page Name]] or [[Page Name|Display Text]] links from wikitext
    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile(
            "\\[\\[([^\\]|#]+?)(?:\\|[^\\]]*)?\\]\\]"
    );

    // Matches decimal HTML entities like &#160; &#91; &#93; etc.
    private static final Pattern HTML_ENTITY_PATTERN = Pattern.compile("&#(\\d+);");

    public interface FetchCallback
    {
        void onSuccess(NpcDropData data);
        void onError(String errorMessage);
        void onDisambiguation(String npcName, List<String> options);
    }

    // ================================================================
    //  FETCH ALL MONSTER NAMES (Category:Monsters API)
    // ================================================================

    /**
     * Fetches all monster page names from Category:Monsters using the
     * MediaWiki categorymembers API. Paginates automatically.
     * Callback receives the complete list of monster names.
     */
    public void fetchAllMonsterNames(OkHttpClient httpClient, Consumer<List<String>> callback)
    {
        List<String> allNames = new ArrayList<>();
        fetchMonsterPage(httpClient, null, allNames, callback);
    }

    private void fetchMonsterPage(OkHttpClient httpClient, String cmcontinue,
                                  List<String> accumulated, Consumer<List<String>> callback)
    {
        String url = WIKI_API_URL + "?action=query&list=categorymembers"
                + "&cmtitle=Category:Monsters&cmlimit=500&cmnamespace=0&format=json";
        if (cmcontinue != null)
        {
            url += "&cmcontinue=" + cmcontinue;
        }

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build();

        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.warn("Failed to fetch monster category page: {}", e.getMessage());
                callback.accept(accumulated);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                try
                {
                    if (!response.isSuccessful())
                    {
                        callback.accept(accumulated);
                        return;
                    }

                    String body = response.body().string();
                    JsonObject root = new JsonParser().parse(body).getAsJsonObject();
                    JsonObject query = root.getAsJsonObject("query");

                    if (query != null && query.has("categorymembers"))
                    {
                        JsonArray members = query.getAsJsonArray("categorymembers");
                        for (JsonElement elem : members)
                        {
                            JsonObject member = elem.getAsJsonObject();
                            String title = member.get("title").getAsString();
                            if (!title.isEmpty())
                            {
                                accumulated.add(title);
                            }
                        }
                    }

                    // Check for continuation
                    if (root.has("continue"))
                    {
                        JsonObject cont = root.getAsJsonObject("continue");
                        if (cont.has("cmcontinue"))
                        {
                            String nextToken = cont.get("cmcontinue").getAsString();
                            log.debug("Fetching next monster category page ({} so far)", accumulated.size());
                            fetchMonsterPage(httpClient, nextToken, accumulated, callback);
                            return;
                        }
                    }

                    log.debug("Fetched {} monster names from Category:Monsters", accumulated.size());
                    callback.accept(accumulated);
                }
                catch (Exception e)
                {
                    log.warn("Error parsing monster category response: {}", e.getMessage());
                    callback.accept(accumulated);
                }
                finally
                {
                    response.close();
                }
            }
        });
    }

    // ================================================================
    //  FETCH DROP TABLE (wikitext parsing + HTML fallback)
    // ================================================================

    public void fetchDropTable(String npcName, OkHttpClient httpClient, FetchCallback callback)
    {
        String encodedName;
        try
        {
            encodedName = URLEncoder.encode(npcName, StandardCharsets.UTF_8.toString());
        }
        catch (Exception e)
        {
            encodedName = npcName.replace(" ", "_");
        }

        String url = WIKI_API_URL + "?action=parse&page=" + encodedName
                + "&prop=wikitext&format=json&redirects=1";

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build();

        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.warn("Wiki fetch failed for {}: {}", npcName, e.getMessage());
                callback.onError("Failed to connect to OSRS Wiki.");
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                try
                {
                    if (!response.isSuccessful())
                    {
                        callback.onError("Wiki returned HTTP " + response.code());
                        return;
                    }

                    String body = response.body().string();
                    JsonObject root = new JsonParser().parse(body).getAsJsonObject();

                    if (root.has("error"))
                    {
                        String errorCode = root.getAsJsonObject("error").get("code").getAsString();
                        if ("missingtitle".equals(errorCode))
                        {
                            callback.onError("No wiki page found for \"" + npcName + "\".");
                        }
                        else
                        {
                            callback.onError("Wiki error: " + errorCode);
                        }
                        return;
                    }

                    JsonObject parse = root.getAsJsonObject("parse");
                    String wikitext = parse.getAsJsonObject("wikitext").get("*").getAsString();

                    // Check for disambiguation page
                    if (DISAMBIG_PATTERN.matcher(wikitext).find())
                    {
                        log.debug("Disambiguation page detected for {}", npcName);
                        List<String> options = extractDisambiguationLinks(wikitext);
                        callback.onDisambiguation(npcName, options);
                        return;
                    }

                    // Find all drop-related sections (handles "Drops", "Drop table 1", etc.)
                    String allDropsText = findAllDropsText(wikitext);
                    if (allDropsText == null)
                    {
                        callback.onError("No drop table found for \"" + npcName + "\".");
                        return;
                    }

                    // Parse DropsLine entries from raw wikitext (for item IDs from |id= params)
                    List<NpcDropData.DropSection> sections = parseWikitext(wikitext);

                    // Always fetch rendered HTML for complete data:
                    // - All sub-table templates (RareDropTable, HerbDropLines, etc.) are expanded
                    // - Item image URLs are available in <img> tags for icon display
                    fetchAndParseHtml(npcName, sections, httpClient, callback);
                }
                catch (Exception e)
                {
                    log.warn("Error parsing wiki data for {}: {}", npcName, e.getMessage());
                    callback.onError("Error parsing wiki data.");
                }
                finally
                {
                    response.close();
                }
            }
        });
    }

    /**
     * Extracts page links from a disambiguation page's wikitext.
     * Returns a list of wiki page names that the user can select from.
     */
    private List<String> extractDisambiguationLinks(String wikitext)
    {
        List<String> links = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Matcher matcher = WIKI_LINK_PATTERN.matcher(wikitext);
        while (matcher.find())
        {
            String link = matcher.group(1).trim();
            // Skip non-article links (categories, files, templates)
            if (link.contains(":")) continue;
            // Skip self-links and common non-NPC links
            if (link.equalsIgnoreCase("disambiguation")) continue;

            String lower = link.toLowerCase();
            if (!seen.contains(lower))
            {
                seen.add(lower);
                links.add(link);
            }
        }

        return links;
    }

    // ================================================================
    //  SUB-TABLE EXPANSION (via rendered HTML)
    // ================================================================

    private boolean containsSubTableTemplates(String text)
    {
        return SUB_TABLE_PATTERN.matcher(text).find();
    }

    /**
     * Fetches the fully rendered HTML of the NPC page and parses drop tables from it.
     * Sub-table templates (RareDropTable, HerbDropLines, etc.) are rendered by Lua modules
     * on the wiki server, so the HTML contains all drop items fully expanded.
     */
    private void fetchAndParseHtml(String npcName, List<NpcDropData.DropSection> wikitextSections,
                                   OkHttpClient httpClient, FetchCallback callback)
    {
        String encodedName;
        try
        {
            encodedName = URLEncoder.encode(npcName, StandardCharsets.UTF_8.toString());
        }
        catch (Exception e)
        {
            encodedName = npcName.replace(" ", "_");
        }

        String url = WIKI_API_URL + "?action=parse&page=" + encodedName
                + "&prop=text&format=json&redirects=1";

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build();

        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.debug("HTML fetch failed for {}, using wikitext-only results", npcName);
                if (wikitextSections.isEmpty())
                {
                    callback.onError("No drop table found for \"" + npcName + "\".");
                }
                else
                {
                    callback.onSuccess(new NpcDropData(npcName, wikitextSections));
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                try
                {
                    if (!response.isSuccessful())
                    {
                        if (wikitextSections.isEmpty())
                        {
                            callback.onError("No drop table found for \"" + npcName + "\".");
                        }
                        else
                        {
                            callback.onSuccess(new NpcDropData(npcName, wikitextSections));
                        }
                        return;
                    }

                    String body = response.body().string();
                    JsonObject root = new JsonParser().parse(body).getAsJsonObject();
                    JsonObject parse = root.getAsJsonObject("parse");
                    String html = parse.getAsJsonObject("text").get("*").getAsString();

                    List<NpcDropData.DropSection> htmlSections = parseHtmlDropTable(html);

                    if (!htmlSections.isEmpty())
                    {
                        // HTML is the authoritative source since it has all sub-tables expanded.
                        // Transfer item IDs from wikitext parsing (DropsLine |id= params) to HTML items.
                        transferItemIds(wikitextSections, htmlSections);

                        int totalItems = 0;
                        for (NpcDropData.DropSection s : htmlSections)
                        {
                            totalItems += s.getItems().size();
                        }
                        log.debug("HTML parsing found {} sections, {} items for {}",
                                htmlSections.size(), totalItems, npcName);

                        callback.onSuccess(new NpcDropData(npcName, htmlSections));
                    }
                    else if (!wikitextSections.isEmpty())
                    {
                        log.debug("HTML parsing empty, using wikitext sections for {}", npcName);
                        callback.onSuccess(new NpcDropData(npcName, wikitextSections));
                    }
                    else
                    {
                        callback.onError("No drop table found for \"" + npcName + "\".");
                    }
                }
                catch (Exception e)
                {
                    log.warn("Error parsing HTML drops for {}: {}", npcName, e.getMessage());
                    if (wikitextSections.isEmpty())
                    {
                        callback.onError("Error parsing wiki data.");
                    }
                    else
                    {
                        callback.onSuccess(new NpcDropData(npcName, wikitextSections));
                    }
                }
                finally
                {
                    response.close();
                }
            }
        });
    }

    /**
     * Parses drop items from the fully rendered HTML of an NPC wiki page.
     * Finds ALL h2 headings containing "drop" (handles "Drops", "Drop table 1",
     * "Level 92, 100, 101, and 113 drops", "Wilderness Slayer Cave drops", etc.)
     * and parses drop tables from each section.
     *
     * HTML structure per row (confirmed from live OSRS Wiki):
     *   td[0] class="inventory-image" - item image with link
     *   td[1] class="item-col"        - item name as <a> link
     *   td[2]                          - quantity (data-sort-value)
     *   td[3]                          - rarity (<span data-drop-fraction="X/Y">)
     *   td[4] class="ge-column"        - GE price
     *   td[5] class="alch-column"      - High Alch value
     */
    private List<NpcDropData.DropSection> parseHtmlDropTable(String html)
    {
        List<NpcDropData.DropSection> allSections = new ArrayList<>();

        // Find ALL h2 headings and their positions
        Pattern h2Pattern = Pattern.compile("<h2[^>]*>(.*?)</h2>", Pattern.DOTALL);
        Matcher h2Matcher = h2Pattern.matcher(html);

        List<int[]> allH2 = new ArrayList<>();
        List<String> allH2Text = new ArrayList<>();

        while (h2Matcher.find())
        {
            allH2.add(new int[]{h2Matcher.start(), h2Matcher.end()});
            String text = h2Matcher.group(1).replaceAll("<[^>]*>", "").trim();
            allH2Text.add(text);
        }

        // Find drop-related h2 sections (text contains "drop" anywhere)
        List<int[]> dropRanges = new ArrayList<>();
        List<String> dropNames = new ArrayList<>();

        for (int i = 0; i < allH2.size(); i++)
        {
            String text = allH2Text.get(i);
            if (text.toLowerCase().contains("drop"))
            {
                int start = allH2.get(i)[1]; // Start after the h2 closing tag
                int end = (i + 1 < allH2.size()) ? allH2.get(i + 1)[0] : html.length();
                dropRanges.add(new int[]{start, end});
                dropNames.add(text);
            }
        }

        if (dropRanges.isEmpty()) return allSections;

        boolean multiple = dropRanges.size() > 1;

        for (int i = 0; i < dropRanges.size(); i++)
        {
            String sectionHtml = html.substring(dropRanges.get(i)[0], dropRanges.get(i)[1]);
            String defaultName = multiple ? dropNames.get(i) : "Drops";
            List<NpcDropData.DropSection> sections = parseSingleHtmlDropSection(sectionHtml, defaultName);
            allSections.addAll(sections);
        }

        return allSections;
    }

    /**
     * Parses drop items from a single HTML drop section (between two h2 headings).
     * Splits by h3/h4 sub-headings and extracts items from each table.
     */
    private List<NpcDropData.DropSection> parseSingleHtmlDropSection(String sectionHtml, String defaultSectionName)
    {
        List<NpcDropData.DropSection> sections = new ArrayList<>();

        Pattern headingPattern = Pattern.compile("<h[34][^>]*>(.*?)</h[34]>", Pattern.DOTALL);
        Pattern rowPattern = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL);
        Pattern cellPattern = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL);
        Pattern linkTextPattern = Pattern.compile("<a[^>]*>([^<]+)</a>");
        Pattern dropFractionPattern = Pattern.compile("data-drop-fraction=\"([^\"]+)\"");
        Pattern imgSrcPattern = Pattern.compile("src=\"([^\"]+)\"");

        NpcDropData.DropSection currentSection = new NpcDropData.DropSection(defaultSectionName);

        // Split at h3/h4 heading tags to group tables with their section headers
        String[] chunks = sectionHtml.split("(?=<h[34])");
        for (String chunk : chunks)
        {
            // Check for sub-section header
            Matcher headingMatcher = headingPattern.matcher(chunk);
            if (headingMatcher.find())
            {
                String heading = decodeHtmlEntities(headingMatcher.group(1).replaceAll("<[^>]*>", "").trim());
                if (!heading.isEmpty())
                {
                    if (!currentSection.getItems().isEmpty())
                    {
                        sections.add(currentSection);
                    }
                    // Rename "100%" to "Always" for cleaner display
                    if (heading.matches("\\d+%?"))
                    {
                        heading = "Always";
                    }
                    currentSection = new NpcDropData.DropSection(heading);
                }
            }

            // Parse table rows in this chunk
            Matcher rowMatcher = rowPattern.matcher(chunk);
            while (rowMatcher.find())
            {
                String row = rowMatcher.group(1);

                // Skip header rows
                if (row.contains("<th")) continue;

                // Extract all <td> cells
                List<String> cells = new ArrayList<>();
                Matcher cellMatcher = cellPattern.matcher(row);
                while (cellMatcher.find())
                {
                    cells.add(cellMatcher.group(1));
                }

                // Need at least: image, name, quantity, rarity (4 cells minimum)
                if (cells.size() < 4) continue;

                // Cell 0 (index 0): item image URL from <img src="...">
                String imageUrl = null;
                Matcher imgMatcher = imgSrcPattern.matcher(cells.get(0));
                if (imgMatcher.find())
                {
                    imageUrl = imgMatcher.group(1);
                    // Ensure full URL (some may be protocol-relative)
                    if (imageUrl.startsWith("//"))
                    {
                        imageUrl = "https:" + imageUrl;
                    }
                    else if (imageUrl.startsWith("/"))
                    {
                        imageUrl = "https://oldschool.runescape.wiki" + imageUrl;
                    }
                }

                // Cell 1 (index 1): item name from <a> link text in item-col
                String itemName = null;
                Matcher linkMatcher = linkTextPattern.matcher(cells.get(1));
                if (linkMatcher.find())
                {
                    itemName = decodeHtmlEntities(linkMatcher.group(1).trim());
                }
                if (itemName == null || itemName.isEmpty()) continue;

                // Modern OSRS drops Clue scroll boxes, not raw clue scrolls
                itemName = normalizeClueScrollName(itemName);

                // Cell 2 (index 2): quantity (strip HTML tags, decode entities)
                String quantity = decodeHtmlEntities(cells.get(2).replaceAll("<[^>]*>", "").trim());
                quantity = quantity.replace(",", "");
                if (quantity.isEmpty()) quantity = "1";

                // Cell 3 (index 3): rarity - prefer data-drop-fraction attribute
                String rarity = "Unknown";
                Matcher fractionMatcher = dropFractionPattern.matcher(cells.get(3));
                if (fractionMatcher.find())
                {
                    rarity = decodeHtmlEntities(fractionMatcher.group(1).trim());
                }
                else
                {
                    String rarityText = decodeHtmlEntities(cells.get(3).replaceAll("<[^>]*>", "").trim());
                    if (!rarityText.isEmpty())
                    {
                        rarity = rarityText;
                    }
                }

                NpcDropData.DropItem item = new NpcDropData.DropItem(itemName, -1, quantity, rarity);
                item.setImageUrl(imageUrl);
                currentSection.getItems().add(item);
            }
        }

        if (!currentSection.getItems().isEmpty())
        {
            sections.add(currentSection);
        }

        return sections;
    }

    /**
     * Transfers item IDs from wikitext-parsed items to HTML-parsed items.
     * Wikitext DropsLine entries often have |id= parameters with the exact item ID,
     * which is more reliable than name-based resolution. HTML parsing doesn't capture IDs.
     */
    private void transferItemIds(List<NpcDropData.DropSection> wikitextSections,
                                 List<NpcDropData.DropSection> htmlSections)
    {
        // Build name -> ID map from wikitext items
        Map<String, Integer> nameToId = new HashMap<>();
        for (NpcDropData.DropSection s : wikitextSections)
        {
            for (NpcDropData.DropItem item : s.getItems())
            {
                if (item.getId() > 0)
                {
                    nameToId.put(item.getName().toLowerCase(), item.getId());
                }
            }
        }

        if (nameToId.isEmpty()) return;

        // Apply IDs to HTML items
        for (NpcDropData.DropSection s : htmlSections)
        {
            for (NpcDropData.DropItem item : s.getItems())
            {
                if (item.getId() <= 0)
                {
                    Integer id = nameToId.get(item.getName().toLowerCase());
                    if (id != null)
                    {
                        item.setId(id);
                    }
                }
            }
        }
    }

    // ================================================================
    //  WIKITEXT PARSING
    // ================================================================

    private List<NpcDropData.DropSection> parseDropsText(String dropsText, String defaultSectionName)
    {
        List<NpcDropData.DropSection> sections = new ArrayList<>();

        String currentSectionName = defaultSectionName;
        NpcDropData.DropSection currentSection = new NpcDropData.DropSection(currentSectionName);

        String[] lines = dropsText.split("\n");
        for (String line : lines)
        {
            String trimmed = line.trim();

            Matcher headerMatcher = SECTION_HEADER_PATTERN.matcher(trimmed);
            if (headerMatcher.matches())
            {
                if (!currentSection.getItems().isEmpty())
                {
                    sections.add(currentSection);
                }
                currentSectionName = headerMatcher.group(1).trim();
                currentSectionName = currentSectionName.replaceAll("\\[\\[|\\]\\]", "");
                currentSection = new NpcDropData.DropSection(currentSectionName);
                continue;
            }

            if (DROPS_LINE_PATTERN.matcher(trimmed).find())
            {
                NpcDropData.DropItem item = parseDropsLine(trimmed);
                if (item != null)
                {
                    currentSection.getItems().add(item);
                }
            }
        }

        if (!currentSection.getItems().isEmpty())
        {
            sections.add(currentSection);
        }

        return sections;
    }

    /**
     * Parses all drop-related h2 sections from wikitext.
     * Handles "== Drops ==", "== Drop table 1 ==", "== Level 92 drops ==", etc.
     * Matches any h2 containing "drop" anywhere in the heading text.
     */
    private List<NpcDropData.DropSection> parseWikitext(String wikitext)
    {
        List<NpcDropData.DropSection> allSections = new ArrayList<>();

        // Match any h2 heading containing "drop" anywhere (covers Drops, Drop table 1,
        // Level 92 drops, Wilderness Slayer Cave drops, etc.)
        Pattern dropH2 = Pattern.compile("^==\\s*([^=]*[Dd]rop[^=]*)\\s*==", Pattern.MULTILINE);
        Matcher matcher = dropH2.matcher(wikitext);

        List<String> headings = new ArrayList<>();
        List<int[]> ranges = new ArrayList<>();

        while (matcher.find())
        {
            headings.add(matcher.group(1).trim());
            int start = matcher.start();
            int end = findNextTopSection(wikitext, start + matcher.group().length());
            if (end == -1) end = wikitext.length();
            ranges.add(new int[]{start, end});
        }

        if (ranges.isEmpty()) return allSections;

        boolean multiple = ranges.size() > 1;

        for (int i = 0; i < ranges.size(); i++)
        {
            String text = wikitext.substring(ranges.get(i)[0], ranges.get(i)[1]);
            // When multiple drop sections exist, use the h2 heading as default section name
            // (e.g., "Drop table 1") to distinguish them
            String defaultName = multiple ? headings.get(i) : "Drops";
            List<NpcDropData.DropSection> sections = parseDropsText(text, defaultName);
            allSections.addAll(sections);
        }

        return allSections;
    }

    /**
     * Finds all drop-related h2 sections and returns their combined text.
     * Used to check for sub-table templates across all drop sections.
     * Matches any h2 containing "drop" (e.g., "Drops", "Drop table 1",
     * "Level 92 drops", "Wilderness Slayer Cave drops").
     */
    private String findAllDropsText(String wikitext)
    {
        Pattern dropH2 = Pattern.compile("^==\\s*[^=]*[Dd]rop[^=]*==", Pattern.MULTILINE);
        Matcher matcher = dropH2.matcher(wikitext);

        StringBuilder allText = new StringBuilder();
        while (matcher.find())
        {
            int start = matcher.start();
            int end = findNextTopSection(wikitext, start + matcher.group().length());
            if (end == -1) end = wikitext.length();
            allText.append(wikitext, start, end).append("\n");
        }

        return allText.length() > 0 ? allText.toString() : null;
    }

    private int findNextTopSection(String wikitext, int fromIndex)
    {
        Pattern pattern = Pattern.compile("^==[^=]", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(wikitext);
        if (matcher.find(fromIndex)) return matcher.start();
        return -1;
    }

    private NpcDropData.DropItem parseDropsLine(String line)
    {
        String lineLower = line.toLowerCase();
        int start = lineLower.indexOf("{{dropsline|");
        if (start == -1) return null;

        int templateStart = start + "{{dropsline|".length();
        int end = findMatchingBraces(line, start);
        if (end == -1) return null;

        String content = line.substring(templateStart, end);
        Map<String, String> params = parseTemplateParams(content);

        String name = params.getOrDefault("name", "").trim();
        if (name.isEmpty()) return null;

        name = name.replaceAll("\\[\\[([^\\]|]+)(\\|[^\\]]+)?\\]\\]", "$1");

        // Modern OSRS drops Clue scroll boxes, not raw clue scrolls
        name = normalizeClueScrollName(name);

        String quantity = params.getOrDefault("quantity", "1").trim();
        quantity = quantity.replaceAll("\\{\\{[^}]*\\}\\}", "").trim();
        if (quantity.isEmpty()) quantity = "1";

        String rarity = cleanRarity(params.getOrDefault("rarity", "Unknown").trim());

        int id = -1;
        String idStr = params.get("id");
        if (idStr != null)
        {
            try { id = Integer.parseInt(idStr.trim()); }
            catch (NumberFormatException ignored) {}
        }

        return new NpcDropData.DropItem(name, id, quantity, rarity);
    }

    private int findMatchingBraces(String text, int start)
    {
        int depth = 0;
        for (int i = start; i < text.length() - 1; i++)
        {
            if (text.charAt(i) == '{' && text.charAt(i + 1) == '{') { depth++; i++; }
            else if (text.charAt(i) == '}' && text.charAt(i + 1) == '}')
            {
                depth--;
                if (depth == 0) return i;
                i++;
            }
        }
        return -1;
    }

    private Map<String, String> parseTemplateParams(String content)
    {
        Map<String, String> params = new HashMap<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < content.length(); i++)
        {
            char c = content.charAt(i);
            if (c == '{' && i + 1 < content.length() && content.charAt(i + 1) == '{')
            { depth++; current.append("{{"); i++; }
            else if (c == '}' && i + 1 < content.length() && content.charAt(i + 1) == '}')
            { depth--; current.append("}}"); i++; }
            else if (c == '|' && depth == 0)
            { addParam(params, current.toString()); current = new StringBuilder(); }
            else { current.append(c); }
        }
        addParam(params, current.toString());
        return params;
    }

    private void addParam(Map<String, String> params, String param)
    {
        int eq = param.indexOf('=');
        if (eq > 0)
        {
            params.put(param.substring(0, eq).trim().toLowerCase(), param.substring(eq + 1).trim());
        }
    }

    /**
     * Decodes HTML entities that remain after stripping tags.
     * The wiki rendered HTML contains entities like:
     *   &#160; -> non-breaking space
     *   &#91;  -> [
     *   &#93;  -> ]
     *   &amp;  -> &
     *   &nbsp; -> space
     */
    private String decodeHtmlEntities(String text)
    {
        if (text == null || text.isEmpty()) return text;

        text = text.replace("&amp;",  "&");
        text = text.replace("&lt;",   "<");
        text = text.replace("&gt;",   ">");
        text = text.replace("&quot;", "\"");
        text = text.replace("&apos;", "'");
        text = text.replace("&nbsp;", " ");

        Matcher m = HTML_ENTITY_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find())
        {
            int codePoint = Integer.parseInt(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    new String(Character.toChars(codePoint))));
        }
        m.appendTail(sb);
        text = sb.toString();

        text = text.replace('\u00A0', ' ').trim();

        return text;
    }

    /**
     * Renames legacy "Clue scroll (difficulty)" entries to "Clue scroll box (difficulty)"
     * to reflect the modern OSRS drop mechanic (monsters drop caskets/boxes, not raw scrolls).
     * Only renames the four variable-difficulty scrolls; "Clue scroll (beginner)" dropped by
     * specific monsters directly is left unchanged.
     */
    private String normalizeClueScrollName(String name)
    {
        if (name == null) return null;
        // Match "Clue scroll (easy/medium/hard/elite)" — case-insensitive
        if (name.matches("(?i)Clue scroll \\((easy|medium|hard|elite)\\)"))
        {
            // Extract the difficulty word and rebuild as a box name
            int open = name.indexOf('(');
            int close = name.indexOf(')');
            if (open >= 0 && close > open)
            {
                String difficulty = name.substring(open + 1, close);
                return "Clue scroll box (" + difficulty.toLowerCase() + ")";
            }
        }
        return name;
    }

    private String cleanRarity(String rarity)
    {
        if (rarity == null || rarity.isEmpty()) return "Unknown";

        Matcher fractionMatcher = FRACTION_PATTERN.matcher(rarity);
        if (fractionMatcher.find())
        {
            return fractionMatcher.group(1).trim() + "/" + fractionMatcher.group(2).trim();
        }

        rarity = rarity.replaceAll("\\{\\{[^}]*\\}\\}", "").trim();
        rarity = rarity.replaceAll("<[^>]*>", "").trim();
        rarity = rarity.replaceAll("\\[\\[([^\\]|]+)(\\|[^\\]]+)?\\]\\]", "$1");

        String lower = rarity.toLowerCase();
        if (lower.startsWith("always")) return "Always";
        if (lower.startsWith("common")) return "Common";
        if (lower.startsWith("uncommon")) return "Uncommon";
        if (lower.startsWith("very rare")) return "Very rare";
        if (lower.startsWith("rare")) return "Rare";

        if (rarity.isEmpty()) return "Unknown";
        return rarity;
    }
}