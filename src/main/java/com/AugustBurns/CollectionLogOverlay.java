package com.AugustBurns;

import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

public class CollectionLogOverlay extends Overlay
{
    // ======== DIMENSIONS ========
    private static final int PANEL_WIDTH = 400;
    private static final int PANEL_HEIGHT = 440;
    private static final int MIN_PANEL_WIDTH = 100;
    private static final int MIN_PANEL_HEIGHT = 100;
    private static final int MAX_PANEL_WIDTH = 1500;
    private static final int MAX_PANEL_HEIGHT = 1500;
    private static final int RESIZE_HANDLE_SIZE = 14;
    private static final int HEADER_HEIGHT = 36;
    private static final int SEARCH_BAR_HEIGHT = 28;
    private static final int SECTION_HEIGHT = 26;
    private static final int ROW_HEIGHT = 26;
    private static final int ICON_SIZE = 20;
    private static final int PADDING = 8;
    private static final int SCROLL_SPEED = 30;
    private static final int SCROLLBAR_WIDTH = 8;
    private static final int MAX_SUGGESTIONS = 6;

    // ======== COLORS ========
    private static final Color BG_COLOR = new Color(30, 25, 19, 240);
    private static final Color HEADER_BG = new Color(50, 40, 28, 255);
    private static final Color BORDER_COLOR = new Color(109, 96, 73);
    private static final Color BORDER_HIGHLIGHT = new Color(149, 132, 100);
    private static final Color TITLE_COLOR = new Color(255, 152, 31);
    private static final Color SECTION_COLOR = new Color(255, 203, 5);
    private static final Color SECTION_BG = new Color(45, 38, 28, 200);
    private static final Color ITEM_COLOR = new Color(225, 215, 195);
    private static final Color ITEM_GREY = new Color(100, 95, 85);
    private static final Color ITEM_OBTAINED = new Color(30, 200, 80);
    private static final Color ICON_BG = new Color(25, 20, 15, 200);
    private static final Color ICON_OBTAINED_BG = new Color(20, 50, 25, 200);
    private static final Color CLOSE_COLOR = new Color(200, 60, 60);
    private static final Color SEPARATOR_COLOR = new Color(80, 70, 55);
    private static final Color SCROLLBAR_BG = new Color(60, 50, 38, 150);
    private static final Color SCROLLBAR_THUMB = new Color(140, 125, 100, 200);
    private static final Color SCROLLBAR_HOVER = new Color(170, 155, 130, 230);
    private static final Color SEARCH_BG = new Color(20, 17, 12, 220);
    private static final Color SEARCH_BORDER = new Color(90, 80, 65);
    private static final Color SEARCH_FOCUSED_BORDER = new Color(255, 152, 31);
    private static final Color SEARCH_TEXT = new Color(220, 210, 190);
    private static final Color SEARCH_PLACEHOLDER = new Color(120, 110, 95);
    private static final Color SUGGESTION_BG = new Color(40, 34, 26, 245);
    private static final Color SUGGESTION_HOVER = new Color(60, 50, 36, 245);
    private static final Color ROW_ALT_BG = new Color(35, 30, 22, 100);
    private static final Color COUNT_COLOR = new Color(180, 170, 140);
    private static final Color FOOTER_COLOR = new Color(140, 130, 110);
    private static final Color RESIZE_HANDLE_COLOR = new Color(109, 96, 73, 180);
    private static final Color RESIZE_HANDLE_HOVER = new Color(180, 160, 120, 220);
    private static final Color TOOLTIP_BG = new Color(20, 17, 12, 235);
    private static final Color TOOLTIP_BORDER = new Color(109, 96, 73);
    private static final Color TOOLTIP_TEXT = new Color(220, 210, 190);
    private static final Color TOOLTIP_ACCENT = new Color(255, 203, 5);
    private static final Color SECTION_COMPLETE = new Color(30, 200, 80);

    // Rarity colors
    private static final Color RATE_ALWAYS = new Color(30, 200, 80);
    private static final Color RATE_COMMON = new Color(180, 180, 180);
    private static final Color RATE_UNCOMMON = new Color(80, 190, 255);
    private static final Color RATE_RARE = new Color(180, 80, 255);
    private static final Color RATE_VERY_RARE = new Color(255, 165, 50);
    private static final Color RATE_DEFAULT = new Color(200, 190, 170);

    public enum State
    {
        HIDDEN, LOADING, SHOWING, ERROR, DISAMBIGUATION
    }

    // ======== INJECTED ========
    @Inject
    private Client client;

    @Inject
    private CollectionPluginConfig config;

    // Passed from plugin (not injected - injection doesn't resolve reliably in overlays)
    private ItemManager itemManager;
    private final Set<Integer> failedImageIds = new HashSet<>();

    // ======== IMAGE CACHE ========
    // Fixed thread pool caps concurrent wiki image downloads and allows clean shutdown.
    private static final int WIKI_IMAGE_CACHE_MAX = 200;
    private final ExecutorService imageDownloadExecutor = Executors.newFixedThreadPool(3);

    // Size-bounded LRU cache: evicts oldest entry once over WIKI_IMAGE_CACHE_MAX.
    private final Map<String, BufferedImage> wikiImageCache =
            Collections.synchronizedMap(
                    new LinkedHashMap<String, BufferedImage>(64, 0.75f, true)
                    {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest)
                        {
                            return size() > WIKI_IMAGE_CACHE_MAX;
                        }
                    });
    private final Set<String> pendingImageDownloads = ConcurrentHashMap.newKeySet();
    private final Set<String> failedImageUrls = ConcurrentHashMap.newKeySet();

    // ======== STATE ========
    private State state = State.HIDDEN;
    private NpcDropData dropData;
    private String npcName = "";
    private String errorMessage = "";
    private boolean greyOutUnobtained = false;

    // Scroll
    private int scrollY = 0;
    private int totalContentHeight = 0;
    private int maxScrollY = 0;

    // Scrollbar drag
    private boolean draggingScrollbar = false;
    private int dragOffset = 0;

    // Collapsible sections
    private final Set<String> collapsedSections = new HashSet<>();

    // Search
    private boolean searchFocused = false;
    private StringBuilder searchText = new StringBuilder();
    private List<String> suggestions = new ArrayList<>();
    private int selectedSuggestion = -1;
    private List<String> availableNpcNames = new ArrayList<>();
    private Consumer<String> searchCallback;

    // Disambiguation display
    private List<String> disambiguationOptions = new ArrayList<>();
    private final List<Rectangle> disambiguationBounds = new ArrayList<>();

    // Party mode (1/1,000,000 Easter egg — or always for Party Pete)
    private boolean partyMode = false;
    private long partyStartTime = 0;

    // Custom position (Alt+drag). -1 means centered (default).
    private int customX = -1;
    private int customY = -1;

    // Custom size (Alt+resize). -1 means use default PANEL_WIDTH/HEIGHT.
    private int customW = -1;
    private int customH = -1;

    // Tracks which drop item the mouse is currently hovering over (for dry-streak tooltip)
    private int hoveredSectionIdx = -1;
    private int hoveredItemIdx = -1;
    private Point lastMousePoint = null;

    // Resize handle bounds (bottom-right corner grip)
    private Rectangle resizeHandleBounds;

    // Whether to show completion counts on section headers (read from config each render)
    private boolean showCompletionCount = false;

    // ======== LIVE COLORS (refreshed from config each frame) ========
    // These shadow the static constants so all rendering methods just use these fields.
    private Color liveBgColor        = BG_COLOR;
    private Color liveHeaderBgColor  = HEADER_BG;
    private Color liveBorderColor    = BORDER_COLOR;
    private Color liveTitleColor     = TITLE_COLOR;
    private Color liveSectionColor   = SECTION_COLOR;
    private Color liveItemColor      = ITEM_COLOR;
    private Color liveObtainedColor  = ITEM_OBTAINED;
    private float liveBgAlpha        = 1.0f;   // 0.0–1.0
    private float liveFgAlpha        = 1.0f;   // applied per drop-row composite

    // Click-to-toggle obtained
    private Runnable dataChangedCallback;

    // Tracks clickable item icon areas for click-to-toggle
    private static class ItemClickArea
    {
        final int sectionIndex;
        final int itemIndex;
        final Rectangle iconBounds;

        ItemClickArea(int sectionIndex, int itemIndex, Rectangle iconBounds)
        {
            this.sectionIndex = sectionIndex;
            this.itemIndex = itemIndex;
            this.iconBounds = iconBounds;
        }
    }

    private final List<ItemClickArea> itemClickAreas = new ArrayList<>();

    // ======== BOUNDS ========
    private Rectangle bounds;
    private Rectangle closeBounds;
    private Rectangle countBadgeBounds;
    private Rectangle searchBarBounds;
    private Rectangle scrollbarTrackBounds;
    private Rectangle scrollbarThumbBounds;
    private final Map<String, Rectangle> sectionHeaderBounds = new HashMap<>();
    private final List<Rectangle> suggestionBounds = new ArrayList<>();

    public CollectionLogOverlay()
    {
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ALWAYS_ON_TOP);
        setPriority(OverlayPriority.HIGHEST);
    }

    public void showPartyMode()
    {
        this.npcName = "Party Pete";
        this.dropData = null;
        this.errorMessage = "You found an easter egg!";
        this.state = State.SHOWING;
        this.partyMode = true;
        this.partyStartTime = System.currentTimeMillis();
        this.scrollY = 0;
        this.collapsedSections.clear();
        this.itemClickAreas.clear();
    }

    // ================================================================
    //  MAIN RENDER
    // ================================================================

    @Override
    public Dimension render(Graphics2D g)
    {
        if (state == State.HIDDEN)
        {
            bounds = null;
            closeBounds = null;
            countBadgeBounds = null;
            return null;
        }

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int canvasW = client.getCanvasWidth();
        int canvasH = client.getCanvasHeight();

        // Use custom size if set, otherwise default dimensions
        int panelW = (customW > 0) ? Math.max(MIN_PANEL_WIDTH, Math.min(customW, Math.min(MAX_PANEL_WIDTH, canvasW - 30)))
                                   : Math.min(PANEL_WIDTH, canvasW - 30);
        int panelH = (customH > 0) ? Math.max(MIN_PANEL_HEIGHT, Math.min(customH, Math.min(MAX_PANEL_HEIGHT, canvasH - 30)))
                                   : Math.min(PANEL_HEIGHT, canvasH - 30);

        // Read config flags fresh each frame
        showCompletionCount = config.showCompletionCount();
        refreshLiveColors();

        int panelX, panelY;
        if (customX >= 0 && customY >= 0)
        {
            panelX = Math.max(0, Math.min(customX, canvasW - panelW));
            panelY = Math.max(0, Math.min(customY, canvasH - panelH));
        }
        else
        {
            panelX = (canvasW - panelW) / 2;
            panelY = (canvasH - panelH) / 2;
        }

        bounds = new Rectangle(panelX, panelY, panelW, panelH);
        sectionHeaderBounds.clear();
        itemClickAreas.clear();

        renderBackground(g, panelX, panelY, panelW, panelH);
        renderHeader(g, panelX, panelY, panelW);

        int searchY = panelY + HEADER_HEIGHT + 1;
        renderSearchBar(g, panelX + 1, searchY, panelW - 2);

        int contentY = searchY + SEARCH_BAR_HEIGHT + 1;
        int contentH = panelH - HEADER_HEIGHT - SEARCH_BAR_HEIGHT - 3;
        int contentW = panelW - 2;
        int contentX = panelX + 1;

        if (state == State.LOADING)
        {
            renderCenteredText(g, "Loading drop table...", ITEM_COLOR, contentX, contentY, contentW, contentH);
        }
        else if (state == State.ERROR)
        {
            renderCenteredText(g, errorMessage, new Color(255, 100, 100), contentX, contentY, contentW, contentH);
        }
        else if (state == State.DISAMBIGUATION)
        {
            renderDisambiguation(g, contentX, contentY, contentW, contentH);
        }
        else if (dropData == null || dropData.getSections().isEmpty())
        {
            renderCenteredText(g, "No drops found.", ITEM_COLOR, contentX, contentY, contentW, contentH);
        }
        else
        {
            renderContent(g, contentX, contentY, contentW, contentH);
        }

        if (searchFocused && searchText.length() > 0)
        {
            renderSearchSuggestions(g, panelX + 1, searchY + SEARCH_BAR_HEIGHT, panelW - 2);
        }

        // Resize handle (bottom-right corner) — always visible when overlay is shown
        renderResizeHandle(g, panelX + panelW - RESIZE_HANDLE_SIZE, panelY + panelH - RESIZE_HANDLE_SIZE);

        // Dry-streak tooltip for hovered drop item
        if (state == State.SHOWING && dropData != null && hoveredSectionIdx >= 0)
        {
            List<NpcDropData.DropSection> sections = dropData.getSections();
            if (hoveredSectionIdx < sections.size())
            {
                List<NpcDropData.DropItem> items = sections.get(hoveredSectionIdx).getItems();
                if (hoveredItemIdx < items.size())
                {
                    renderDryStreakTooltip(g, items.get(hoveredItemIdx), canvasW, canvasH);
                }
            }
        }

        return new Dimension(panelW, panelH);
    }

    // ================================================================
    //  BACKGROUND & BORDER
    // ================================================================

    private void renderBackground(Graphics2D g, int x, int y, int w, int h)
    {
        if (partyMode)
        {
            g.setStroke(new BasicStroke(3.0f));
            g.setColor(partyColor(0.0f));
            g.drawRoundRect(x - 2, y - 2, w + 4, h + 4, 8, 8);
            g.setStroke(new BasicStroke(1.0f));
        }
        else
        {
            g.setColor(BORDER_HIGHLIGHT);
            g.drawRoundRect(x - 1, y - 1, w + 2, h + 2, 6, 6);
        }

        // Apply background opacity from config
        Color bgWithAlpha = withAlpha(liveBgColor, Math.round(liveBgAlpha * 255));
        g.setColor(bgWithAlpha);
        g.fillRoundRect(x, y, w, h, 5, 5);

        g.setColor(partyMode ? partyColor(0.5f) : liveBorderColor);
        g.drawRoundRect(x, y, w, h, 5, 5);
    }

    // ================================================================
    //  HEADER
    // ================================================================

    private void renderHeader(Graphics2D g, int panelX, int panelY, int panelW)
    {
        // Header background uses the same opacity as the main background
        Color headerWithAlpha = withAlpha(liveHeaderBgColor, Math.round(liveBgAlpha * 255));
        g.setColor(headerWithAlpha);
        g.fillRect(panelX + 1, panelY + 1, panelW - 2, HEADER_HEIGHT);

        g.setColor(partyMode ? partyColor(0.3f) : liveBorderColor);
        g.drawLine(panelX + 1, panelY + HEADER_HEIGHT, panelX + panelW - 1, panelY + HEADER_HEIGHT);

        Font titleFont = FontManager.getRunescapeBoldFont();
        g.setFont(titleFont);
        FontMetrics fm = g.getFontMetrics();

        int closeButtonWidth = 26;

        // Drop count badge
        String countBadge = "";
        if (state == State.SHOWING && dropData != null)
        {
            countBadge = dropData.getTotalDropCount() + " drops";
        }
        int countBadgeWidth = countBadge.isEmpty() ? 0 : fm.stringWidth(countBadge) + 12;

        // Title: "NpcName (1,234)" when kills are tracked (#5)
        int rightReserved = closeButtonWidth + countBadgeWidth + 6;
        int titleAreaLeft = panelX + PADDING + 2;
        int titleAreaWidth = panelW - PADDING - rightReserved - (titleAreaLeft - panelX);

        String title = npcName;
        if (!partyMode && state == State.SHOWING && dropData != null && dropData.getKillCount() > 0)
        {
            title = npcName + " (" + String.format("%,d", dropData.getKillCount()) + ")";
        }
        while (fm.stringWidth(title) > titleAreaWidth && title.length() > 4)
        {
            title = title.substring(0, title.length() - 4) + "...";
        }
        int titleX = titleAreaLeft + (titleAreaWidth - fm.stringWidth(title)) / 2;
        int titleY = panelY + (HEADER_HEIGHT + fm.getAscent()) / 2;

        if (partyMode)
        {
            int charX = titleX;
            for (int i = 0; i < title.length(); i++)
            {
                float offset = (float) i / title.length();
                int bounce = (int) (Math.sin((System.currentTimeMillis() - partyStartTime) / 150.0 + i * 0.4) * 3);
                g.setColor(partyColor(offset));
                g.drawString(String.valueOf(title.charAt(i)), charX, titleY + bounce);
                charX += fm.charWidth(title.charAt(i));
            }
        }
        else
        {
            g.setColor(liveTitleColor);
            g.drawString(title, titleX, titleY);
        }

        // Drop count badge (clickable: collapses/expands all sections)
        if (!countBadge.isEmpty())
        {
            Font smallFont = FontManager.getRunescapeSmallFont();
            g.setFont(smallFont);
            FontMetrics sfm = g.getFontMetrics();
            int badgeW = sfm.stringWidth(countBadge) + 10;
            int badgeH = 16;
            int badgeX = panelX + panelW - closeButtonWidth - badgeW - 6;
            int badgeY = panelY + (HEADER_HEIGHT - badgeH) / 2;

            countBadgeBounds = new Rectangle(badgeX, badgeY, badgeW, badgeH);

            g.setColor(partyMode ? withAlpha(partyColor(0.7f), 80) : new Color(80, 65, 45, 180));
            g.fillRoundRect(badgeX, badgeY, badgeW, badgeH, 8, 8);
            g.setColor(partyMode ? partyColor(0.7f) : new Color(140, 125, 95));
            g.drawRoundRect(badgeX, badgeY, badgeW, badgeH, 8, 8);
            g.setColor(partyMode ? partyColor(0.9f) : liveSectionColor);
            g.drawString(countBadge, badgeX + 5, badgeY + badgeH - 4);

            g.setFont(titleFont);
        }
        else
        {
            countBadgeBounds = null;
        }

        // Close button
        int closeSize = 16;
        int closeX = panelX + panelW - closeSize - PADDING;
        int closeY = panelY + (HEADER_HEIGHT - closeSize) / 2;
        closeBounds = new Rectangle(closeX - 2, closeY - 2, closeSize + 4, closeSize + 4);

        g.setColor(CLOSE_COLOR);
        g.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(closeX + 3, closeY + 3, closeX + closeSize - 3, closeY + closeSize - 3);
        g.drawLine(closeX + closeSize - 3, closeY + 3, closeX + 3, closeY + closeSize - 3);
        g.setStroke(new BasicStroke(1.0f));
    }

    // ================================================================
    //  SEARCH BAR
    // ================================================================

    private void renderSearchBar(Graphics2D g, int x, int y, int w)
    {
        int barX = x + PADDING;
        int barY = y + 4;
        int barW = w - PADDING * 2;
        int barH = SEARCH_BAR_HEIGHT - 8;

        searchBarBounds = new Rectangle(barX, barY, barW, barH);

        g.setColor(SEARCH_BG);
        g.fillRoundRect(barX, barY, barW, barH, 4, 4);

        g.setColor(searchFocused ? SEARCH_FOCUSED_BORDER : SEARCH_BORDER);
        g.drawRoundRect(barX, barY, barW, barH, 4, 4);

        int iconX = barX + 6;
        int iconY = barY + barH / 2;
        g.setColor(SEARCH_PLACEHOLDER);
        g.drawOval(iconX, iconY - 5, 8, 8);
        g.drawLine(iconX + 7, iconY + 3, iconX + 10, iconY + 6);

        Font searchFont = FontManager.getRunescapeSmallFont();
        g.setFont(searchFont);
        FontMetrics fm = g.getFontMetrics();
        int textX = barX + 20;
        int textY = barY + (barH + fm.getAscent()) / 2 - 1;

        if (searchText.length() > 0)
        {
            g.setColor(SEARCH_TEXT);
            g.drawString(searchText.toString(), textX, textY);

            if (searchFocused && (System.currentTimeMillis() % 1000) < 500)
            {
                int cursorX = textX + fm.stringWidth(searchText.toString());
                g.setColor(SEARCH_TEXT);
                g.drawLine(cursorX + 1, barY + 3, cursorX + 1, barY + barH - 3);
            }
        }
        else
        {
            g.setColor(SEARCH_PLACEHOLDER);
            g.drawString("Search NPC...", textX, textY);

            if (searchFocused && (System.currentTimeMillis() % 1000) < 500)
            {
                g.setColor(SEARCH_TEXT);
                g.drawLine(textX, barY + 3, textX, barY + barH - 3);
            }
        }
    }

    // ================================================================
    //  SEARCH SUGGESTIONS DROPDOWN
    // ================================================================

    private void renderSearchSuggestions(Graphics2D g, int x, int y, int w)
    {
        suggestionBounds.clear();

        int count = Math.min(suggestions.size(), MAX_SUGGESTIONS);
        int rowCount = Math.max(1, count);
        int dropH = rowCount * 22 + 4;

        g.setColor(new Color(0, 0, 0, 100));
        g.fillRoundRect(x + PADDING + 2, y + 2, w - PADDING * 2, dropH, 4, 4);

        g.setColor(SUGGESTION_BG);
        g.fillRoundRect(x + PADDING, y, w - PADDING * 2, dropH, 4, 4);
        g.setColor(SEARCH_FOCUSED_BORDER);
        g.drawRoundRect(x + PADDING, y, w - PADDING * 2, dropH, 4, 4);

        Font font = FontManager.getRunescapeSmallFont();
        g.setFont(font);

        if (count == 0)
        {
            g.setColor(SEARCH_PLACEHOLDER);
            g.drawString("Press Enter to search \"" + searchText + "\" on wiki", x + PADDING + 8, y + 16);
            return;
        }

        for (int i = 0; i < count; i++)
        {
            int itemY = y + 2 + i * 22;
            Rectangle itemRect = new Rectangle(x + PADDING + 1, itemY, w - PADDING * 2 - 2, 22);
            suggestionBounds.add(itemRect);

            if (i == selectedSuggestion)
            {
                g.setColor(SUGGESTION_HOVER);
                g.fillRect(itemRect.x, itemRect.y, itemRect.width, itemRect.height);
            }

            g.setColor(i == selectedSuggestion ? TITLE_COLOR : SEARCH_TEXT);
            g.drawString(suggestions.get(i), x + PADDING + 8, itemY + 15);
        }
    }

    // ================================================================
    //  CONTENT (DROP TABLE)
    // ================================================================

    private void renderContent(Graphics2D g, int contentX, int contentY, int contentW, int contentH)
    {
        totalContentHeight = calculateContentHeight();
        boolean needsScrollbar = totalContentHeight > contentH;
        int textAreaW = contentW - (needsScrollbar ? SCROLLBAR_WIDTH + 4 : 0);

        maxScrollY = Math.max(0, totalContentHeight - contentH + PADDING);
        scrollY = Math.max(0, Math.min(scrollY, maxScrollY));

        Shape oldClip = g.getClip();
        g.setClip(contentX, contentY, contentW, contentH);

        // Reset hover tracking each frame; renderDropItem re-sets it if mouse is over a row
        hoveredSectionIdx = -1;
        hoveredItemIdx = -1;

        Font sectionFont = FontManager.getRunescapeBoldFont();
        Font itemFont = FontManager.getRunescapeSmallFont();

        int drawY = contentY - scrollY + PADDING;
        int globalItemIndex = 0;

        List<NpcDropData.DropSection> sections = dropData.getSections();
        for (int s = 0; s < sections.size(); s++)
        {
            NpcDropData.DropSection section = sections.get(s);
            boolean collapsed = collapsedSections.contains(section.getName());

            if (isInVisibleRange(drawY, SECTION_HEIGHT, contentY, contentH))
            {
                renderSectionHeader(g, sectionFont, section, collapsed, contentX, drawY, textAreaW,
                        contentY, contentY + contentH, globalItemIndex);
            }
            drawY += SECTION_HEIGHT;

            if (!collapsed)
            {
                for (int i = 0; i < section.getItems().size(); i++)
                {
                    NpcDropData.DropItem item = section.getItems().get(i);
                    if (isInVisibleRange(drawY, ROW_HEIGHT, contentY, contentH))
                    {
                        renderDropItem(g, itemFont, item, contentX, drawY, textAreaW, i, globalItemIndex, s, i);
                    }
                    drawY += ROW_HEIGHT;
                    globalItemIndex++;
                }
            }
            else
            {
                globalItemIndex += section.getItems().size();
            }

            if (s < sections.size() - 1)
            {
                if (isInVisibleRange(drawY, 6, contentY, contentH))
                {
                    g.setColor(partyMode ? partyColor((float) s / sections.size()) : SEPARATOR_COLOR);
                    g.drawLine(contentX + PADDING, drawY + 2, contentX + textAreaW - PADDING, drawY + 2);
                }
                drawY += 6;
            }
        }

        g.setClip(oldClip);

        if (needsScrollbar)
        {
            renderScrollbar(g, contentX + contentW - SCROLLBAR_WIDTH - 2, contentY + 2,
                    SCROLLBAR_WIDTH, contentH - 4);
        }
    }

    // ================================================================
    //  DISAMBIGUATION LIST
    // ================================================================

    private void renderDisambiguation(Graphics2D g, int contentX, int contentY, int contentW, int contentH)
    {
        disambiguationBounds.clear();

        int itemH = 26;
        int headerH = 28;
        totalContentHeight = headerH + disambiguationOptions.size() * itemH + PADDING * 2;
        boolean needsScrollbar = totalContentHeight > contentH;
        int textAreaW = contentW - (needsScrollbar ? SCROLLBAR_WIDTH + 4 : 0);

        maxScrollY = Math.max(0, totalContentHeight - contentH);
        scrollY = Math.max(0, Math.min(scrollY, maxScrollY));

        Shape oldClip = g.getClip();
        g.setClip(contentX, contentY, contentW, contentH);

        int drawY = contentY - scrollY + PADDING;

        Font titleFont = FontManager.getRunescapeBoldFont();
        g.setFont(titleFont);
        g.setColor(TITLE_COLOR);
        g.drawString("Multiple matches \u2014 select one:", contentX + PADDING, drawY + 16);
        drawY += headerH;

        g.setColor(SEPARATOR_COLOR);
        g.drawLine(contentX + PADDING, drawY - 2, contentX + textAreaW - PADDING, drawY - 2);

        Font itemFont = FontManager.getRunescapeSmallFont();
        g.setFont(itemFont);

        for (int i = 0; i < disambiguationOptions.size(); i++)
        {
            int rowY = drawY + i * itemH;
            Rectangle rect = new Rectangle(contentX + 2, rowY, textAreaW - 4, itemH);
            disambiguationBounds.add(rect);

            if (i % 2 == 0)
            {
                g.setColor(ROW_ALT_BG);
                g.fillRect(rect.x, rect.y, rect.width, rect.height);
            }

            g.setColor(SECTION_COLOR);
            g.drawString("\u25B8", contentX + PADDING + 2, rowY + 17);

            g.setColor(SEARCH_TEXT);
            g.drawString(disambiguationOptions.get(i), contentX + PADDING + 16, rowY + 17);
        }

        g.setClip(oldClip);

        if (needsScrollbar)
        {
            renderScrollbar(g, contentX + contentW - SCROLLBAR_WIDTH - 2, contentY + 2,
                    SCROLLBAR_WIDTH, contentH - 4);
        }
    }

    // ================================================================
    //  SECTION HEADER
    // ================================================================

    private void renderSectionHeader(Graphics2D g, Font font, NpcDropData.DropSection section,
                                     boolean collapsed, int x, int y, int w,
                                     int clipTop, int clipBottom, int itemIndex)
    {
        g.setColor(partyMode ? withAlpha(partyColor(itemIndex * 0.05f), 120) : SECTION_BG);
        g.fillRect(x + 2, y, w - 4, SECTION_HEIGHT - 2);

        g.setFont(font);
        g.setColor(partyMode ? partyColor(itemIndex * 0.05f + 0.2f) : liveSectionColor);
        String arrow = collapsed ? "\u25B8" : "\u25BE";
        g.drawString(arrow, x + PADDING + 2, y + SECTION_HEIGHT - 9);

        int textX = x + PADDING + 16;

        if (partyMode)
        {
            int bounce = (int) (Math.sin((System.currentTimeMillis() - partyStartTime) / 200.0 + itemIndex * 0.5) * 2);
            g.drawString(section.getName(), textX, y + SECTION_HEIGHT - 9 + bounce);
        }
        else
        {
            g.drawString(section.getName(), textX, y + SECTION_HEIGHT - 9);
        }

        FontMetrics fm = g.getFontMetrics();
        int nameWidth = fm.stringWidth(section.getName());

        // Build the count badge string
        String countStr;
        boolean sectionComplete = false;
        if (showCompletionCount && !partyMode)
        {
            int total = section.getItems().size();
            int obtained = 0;
            for (NpcDropData.DropItem item : section.getItems())
            {
                if (item.isObtained()) obtained++;
            }
            sectionComplete = (obtained == total && total > 0);
            countStr = "(" + obtained + "/" + total + ")";
        }
        else
        {
            countStr = "(" + section.getItems().size() + ")";
        }

        Color countColor;
        if (partyMode)
        {
            countColor = partyColor(itemIndex * 0.05f + 0.4f);
        }
        else if (sectionComplete)
        {
            countColor = SECTION_COMPLETE;
        }
        else
        {
            countColor = COUNT_COLOR;
        }
        g.setColor(countColor);
        g.drawString(countStr, textX + nameWidth + 6, y + SECTION_HEIGHT - 9);

        if (y >= clipTop && y + SECTION_HEIGHT <= clipBottom)
        {
            sectionHeaderBounds.put(section.getName(), new Rectangle(x, y, w, SECTION_HEIGHT));
        }
    }

    // ================================================================
    //  DROP ITEM ROW
    // ================================================================

    private void renderDropItem(Graphics2D g, Font font, NpcDropData.DropItem item,
                                int x, int y, int w, int rowIndex, int globalIndex,
                                int sectionIdx, int itemIdx)
    {
        boolean greyed = greyOutUnobtained && !item.isObtained();
        Composite originalComposite = g.getComposite();

        if (rowIndex % 2 == 0)
        {
            g.setColor(ROW_ALT_BG);
            g.fillRect(x + 4, y, w - 8, ROW_HEIGHT - 1);
        }

        // Apply foreground opacity. Greyed items get their own reduced alpha on top.
        float rowAlpha = greyed ? liveFgAlpha * 0.35f : liveFgAlpha;
        if (rowAlpha < 0.99f)
        {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, rowAlpha))));
        }

        int iconX = x + PADDING + 6;
        int iconY = y + (ROW_HEIGHT - ICON_SIZE) / 2;

        g.setColor(item.isObtained() ? withAlpha(liveObtainedColor, 60) : ICON_BG);
        g.fillRect(iconX, iconY, ICON_SIZE, ICON_SIZE);

        if (item.isObtained() && !partyMode)
        {
            g.setColor(liveObtainedColor);
        }
        else
        {
            g.setColor(partyMode ? partyColor(globalIndex * 0.07f) : liveBorderColor);
        }
        g.drawRect(iconX, iconY, ICON_SIZE, ICON_SIZE);

        BufferedImage img = null;
        if (item.getId() > 0)
        {
            img = getItemImage(item.getId());
        }
        if (img == null && item.getImageUrl() != null)
        {
            img = getWikiImage(item.getImageUrl());
        }
        if (img != null)
        {
            g.drawImage(img, iconX + 1, iconY + 1, ICON_SIZE - 2, ICON_SIZE - 2, null);
        }

        itemClickAreas.add(new ItemClickArea(sectionIdx, itemIdx,
                new Rectangle(iconX - 2, iconY - 2, ICON_SIZE + 4, ICON_SIZE + 4)));

        // Track row hover for dry-streak tooltip
        Rectangle rowRect = new Rectangle(x + 4, y, w - 8, ROW_HEIGHT - 1);
        if (lastMousePoint != null && rowRect.contains(lastMousePoint))
        {
            hoveredSectionIdx = sectionIdx;
            hoveredItemIdx = itemIdx;
        }

        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        int textX = iconX + ICON_SIZE + 6;
        int textY = y + (ROW_HEIGHT + fm.getAscent()) / 2 - 2;

        if (partyMode)
        {
            int bounce = (int) (Math.sin((System.currentTimeMillis() - partyStartTime) / 150.0 + globalIndex * 0.3) * 2);
            g.setColor(partyColor(globalIndex * 0.07f + 0.15f));
            g.drawString(item.getDisplayName(), textX, textY + bounce);
        }
        else
        {
            g.setColor(greyed ? ITEM_GREY : liveItemColor);
            g.drawString(item.getDisplayName(), textX, textY);
        }

        if (item.getObtainedCount() > 0)
        {
            String countBadge = "x" + item.getObtainedCount();
            int nameWidth = fm.stringWidth(item.getDisplayName());
            int badgeX = textX + nameWidth + 4;
            g.setColor(liveObtainedColor);
            g.drawString(countBadge, badgeX, textY);
        }

        String rarity = item.getRarity();
        Color rateColor;
        if (partyMode)
        {
            rateColor = partyColor(globalIndex * 0.07f + 0.5f);
        }
        else
        {
            rateColor = greyed ? ITEM_GREY : getRarityColor(rarity);
        }
        g.setColor(rateColor);
        int rateWidth = fm.stringWidth(rarity);

        // #8: F2P-only badge — draw a small "[F2P]" pill to the left of the rarity text
        int f2pBadgeW = 0;
        if (item.isF2pOnly() && !partyMode)
        {
            Font smallFont = g.getFont().deriveFont(g.getFont().getSize2D() - 1f);
            g.setFont(smallFont);
            FontMetrics sfm = g.getFontMetrics();
            String f2pLabel = "F2P";
            int badgePadX = 3;
            int badgeH = sfm.getHeight() - 1;
            f2pBadgeW = sfm.stringWidth(f2pLabel) + badgePadX * 2 + 4;
            int badgeX = x + w - rateWidth - PADDING - 4 - f2pBadgeW - 4;
            int badgeY = y + (ROW_HEIGHT - badgeH) / 2;

            g.setColor(new Color(60, 120, 60, 180));
            g.fillRoundRect(badgeX, badgeY, f2pBadgeW - 4, badgeH, 3, 3);
            g.setColor(new Color(100, 200, 100));
            g.drawRoundRect(badgeX, badgeY, f2pBadgeW - 4, badgeH, 3, 3);
            g.drawString(f2pLabel, badgeX + badgePadX, badgeY + sfm.getAscent() - 1);
            g.setFont(font);
        }

        int rateX = x + w - rateWidth - PADDING - 4;
        g.setColor(rateColor);

        if (partyMode)
        {
            int bounce = (int) (Math.sin((System.currentTimeMillis() - partyStartTime) / 150.0 + globalIndex * 0.3 + 1.0) * 2);
            g.drawString(rarity, rateX, textY + bounce);
        }
        else
        {
            g.drawString(rarity, rateX, textY);
        }

        // Always restore composite — may have been changed by opacity or greying
        g.setComposite(originalComposite);
    }

    private void renderScrollbar(Graphics2D g, int x, int y, int width, int height)
    {
        scrollbarTrackBounds = new Rectangle(x, y, width, height);

        g.setColor(SCROLLBAR_BG);
        g.fillRoundRect(x, y, width, height, 4, 4);

        if (maxScrollY > 0)
        {
            int thumbH = Math.max(20, (int) ((float) height * height / totalContentHeight));
            int thumbY = y + (int) ((float) scrollY / maxScrollY * (height - thumbH));

            scrollbarThumbBounds = new Rectangle(x, thumbY, width, thumbH);

            g.setColor(draggingScrollbar ? SCROLLBAR_HOVER : SCROLLBAR_THUMB);
            g.fillRoundRect(x, thumbY, width, thumbH, 4, 4);
        }
        else
        {
            scrollbarThumbBounds = null;
        }
    }

    // ================================================================
    //  RESIZE HANDLE
    // ================================================================

    private void renderResizeHandle(Graphics2D g, int hx, int hy)
    {
        resizeHandleBounds = new Rectangle(hx, hy, RESIZE_HANDLE_SIZE, RESIZE_HANDLE_SIZE);

        boolean hovered = lastMousePoint != null && resizeHandleBounds.contains(lastMousePoint);
        Color handleColor = hovered ? RESIZE_HANDLE_HOVER : RESIZE_HANDLE_COLOR;

        // Draw three diagonal lines in the corner as a classic resize grip
        g.setColor(handleColor);
        g.setStroke(new BasicStroke(1.5f));
        for (int i = 1; i <= 3; i++)
        {
            int offset = i * 4;
            g.drawLine(hx + RESIZE_HANDLE_SIZE - offset, hy + RESIZE_HANDLE_SIZE,
                       hx + RESIZE_HANDLE_SIZE,          hy + RESIZE_HANDLE_SIZE - offset);
        }
        g.setStroke(new BasicStroke(1.0f));
    }

    // ================================================================
    //  DRY-STREAK TOOLTIP
    // ================================================================

    /**
     * Renders a dry-streak probability tooltip near the mouse cursor.
     *
     * Behaviour depends on whether the player has ever received this specific drop:
     *
     * HAS NOT received the drop yet:
     *   Line 2 — "N kills → X% chance of ≥1 drop"
     *   Line 3 — "(Y% of players still dry)"   [color-coded by how overdue]
     *   Line 4 — Dry-without-drop message (optional, from config)
     *
     * HAS received the drop (obtained ≥ 1):
     *   Line 2 — "N kills → you should have ~X drops by now"
     *   Line 3 — "You have: Y   |   Expected: ~Z"
     *   Line 4 — Dry-with-drop OR over-rate message (optional, from config)
     *
     * Always: line 1 = item name + rarity.
     */
    private void renderDryStreakTooltip(Graphics2D g, NpcDropData.DropItem item, int canvasW, int canvasH)
    {
        if (lastMousePoint == null) return;

        double p = parseDropProbability(item.getRarity());
        if (p <= 0 || p >= 1.0) return;

        int n       = (dropData != null) ? dropData.getKillCount() : 0;
        int obtained = countObtained(item);

        String line1 = item.getName() + "  [" + item.getRarity() + "]";
        String line2;
        String line3;
        String line4 = null;
        Color  line3Color;
        Color  line4Color = new Color(200, 190, 160);

        if (n <= 0)
        {
            // No kills tracked — prompt the player
            line2 = "No kills tracked yet for this NPC.";
            line3 = "Kill it and loot to start tracking.";
            line3Color = new Color(160, 150, 130);
        }
        else if (obtained <= 0)
        {
            // Has never received this drop
            double prob  = 1.0 - Math.pow(1.0 - p, n);
            int percent  = (int) Math.round(prob * 100);
            int stillDry = 100 - percent;

            line2 = n + " kills  \u2192  " + percent + "% chance of \u22651 drop";
            line3 = "(" + stillDry + "% of players still dry)";

            // Color line 3 by how overdue the player is
            line3Color = percent >= 99 ? new Color(255, 80,  80)
                       : percent >= 63 ? new Color(255, 165, 50)
                                       : new Color(140, 200, 140);

            if (config.showDryMessage())
            {
                double expected = n * p;
                line4 = getDryMessageNoDrop(expected);
                if (line4 != null)
                {
                    double dryMult = (expected > 0.001) ? expected : 0;
                    line4Color = dryMult >= 2.0 ? new Color(255, 80,  80)
                               : dryMult >= 1.5 ? new Color(255, 140, 40)
                                                : new Color(220, 200, 100);
                }
            }
        }
        else
        {
            // Has received the drop — show expected vs actual count
            double expected    = n * p;
            int    expectedInt = (int) Math.round(expected);

            line2 = n + " kills  \u2192  you should have ~" + expectedInt + " drop"
                    + (expectedInt == 1 ? "" : "s") + " by now";
            line3 = "You have: " + obtained + "   |   Expected: ~" + expectedInt;

            // Color line 3 by over/under rate
            double ratio = (expected > 0) ? obtained / expected : 1.0;
            line3Color = ratio >= 1.25 ? new Color(80,  220, 120)
                       : ratio <= 0.6  ? new Color(255, 140, 50)
                                       : new Color(200, 200, 140);

            if (config.showDryMessage())
            {
                line4 = getDryMessageWithDrop(obtained, expected);
                if (line4 != null)
                {
                    line4Color = ratio >= 1.5 ? new Color(80, 220, 120)
                               : ratio >= 0.75 ? new Color(200, 200, 140)
                               : ratio >= 0.4  ? new Color(255, 165, 50)
                                               : new Color(255, 80,  80);
                }
            }
        }

        // ---- Layout & draw ----
        Font font = FontManager.getRunescapeSmallFont();
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();

        int padX      = 8;
        int padY      = 5;
        int lineH     = fm.getHeight() + 2;
        int lineCount = (line4 != null) ? 4 : 3;

        int tipW = fm.stringWidth(line1);
        tipW = Math.max(tipW, fm.stringWidth(line2));
        tipW = Math.max(tipW, fm.stringWidth(line3));
        if (line4 != null) tipW = Math.max(tipW, fm.stringWidth(line4));
        tipW += padX * 2;
        int tipH = lineH * lineCount + padY * 2;

        int tipX = lastMousePoint.x + 12;
        int tipY = lastMousePoint.y - tipH - 10;
        if (tipX + tipW > canvasW - 4) tipX = canvasW - tipW - 4;
        if (tipY < 4) tipY = lastMousePoint.y + 18;

        // Shadow
        g.setColor(new Color(0, 0, 0, 120));
        g.fillRoundRect(tipX + 2, tipY + 2, tipW, tipH, 6, 6);

        // Background + border
        g.setColor(TOOLTIP_BG);
        g.fillRoundRect(tipX, tipY, tipW, tipH, 6, 6);
        g.setColor(TOOLTIP_BORDER);
        g.drawRoundRect(tipX, tipY, tipW, tipH, 6, 6);

        int tx = tipX + padX;
        int ty = tipY + padY + fm.getAscent();

        g.setColor(TOOLTIP_ACCENT);
        g.drawString(line1, tx, ty);  ty += lineH;

        g.setColor(TOOLTIP_TEXT);
        g.drawString(line2, tx, ty);  ty += lineH;

        g.setColor(line3Color);
        g.drawString(line3, tx, ty);

        if (line4 != null)
        {
            ty += lineH;
            g.setColor(line4Color);
            g.drawString(line4, tx, ty);
        }
    }

    /**
     * Humorous message when the player HAS NOT received this drop yet.
     * Thresholds are based on how many expected drops they "should" have had (n * p).
     *
     *   expected ≥ 3.0  → "Just go ahead and make a post on reddit."
     *   expected ≥ 2.5  → "This stopped being funny a while ago."
     *   expected ≥ 2.0  → "Are you sure this is still worth it?"
     *   expected ≥ 1.5  → "It'll happen anytime now, right?"
     *   expected ≥ 0.75 → "You're not dry yet, technically."
     */
    private String getDryMessageNoDrop(double expected)
    {
        if (expected < 0.75) return null;
        if (expected >= 3.0) return "Just go ahead and make a post on reddit.";
        if (expected >= 2.5) return "This stopped being funny a while ago.";
        if (expected >= 2.0) return "Are you sure this is still worth it?";
        if (expected >= 1.5) return "It'll happen anytime now, right?";
        return "You're not dry yet, technically.";
    }

    /**
     * Humorous message when the player HAS received this drop at least once.
     * Uses the obtained/expected ratio to place them on the dry↔lucky spectrum.
     *
     * Dry-with-drop (ratio < 0.75):
     *   ratio < 0.333 → "There goes the Gp/hour."
     *   ratio < 0.4   → "Might as well take the loss."
     *   ratio < 0.5   → "Hey, at least you got one."
     *   ratio < 0.75  → "Hope you didn't need extras."
     *
     * On rate (ratio 0.75–1.25):
     *   → "Deserved."
     *
     * Over rate (ratio > 1.25) — same messages as before:
     *   ratio ≥ 3.0   → "I better not see you ever complain."
     *   ratio ≥ 2.5   → "Leave some for the rest of us!"
     *   ratio ≥ 2.0   → "Go ahead and buy a scratcher."
     *   ratio ≥ 1.5   → "This makes up for that other thing."
     *   ratio ≥ 1.25  → "This makes up for that other thing."
     */
    private String getDryMessageWithDrop(int obtained, double expected)
    {
        if (expected < 0.5) return null;

        double ratio = (expected > 0) ? obtained / expected : 1.0;

        if (ratio >= 3.0)  return "I better not see you ever complain.";
        if (ratio >= 2.5)  return "Leave some for the rest of us!";
        if (ratio >= 2.0)  return "Go ahead and buy a scratcher.";
        if (ratio >= 1.5)  return "This makes up for that other thing.";
        if (ratio >= 0.75) return "Deserved.";
        if (ratio >= 0.5)  return "Hope you didn't need extras.";
        if (ratio >= 0.4)  return "Hey, at least you got one.";
        if (ratio >= 0.333) return "Might as well take the loss.";
        return "There goes the Gp/hour.";
    }

    /** Returns the obtainedCount for a specific DropItem instance within current dropData. */
    private int countObtained(NpcDropData.DropItem target)
    {
        if (dropData == null) return 0;
        for (NpcDropData.DropSection section : dropData.getSections())
            for (NpcDropData.DropItem item : section.getItems())
                if (item == target) return item.getObtainedCount();
        return 0;
    }

    /**
     * Converts a rarity string to a probability in [0, 1].
     * Returns -1 if the rarity cannot be parsed (e.g. "Always", "Unknown").
     */
    private double parseDropProbability(String rarity)
    {
        if (rarity == null) return -1;
        String lower = rarity.toLowerCase();
        if (lower.equals("always")) return 1.0;
        if (lower.equals("common")) return 1.0 / 8;
        if (lower.equals("uncommon")) return 1.0 / 32;
        if (lower.equals("rare")) return 1.0 / 128;
        if (lower.equals("very rare")) return 1.0 / 512;

        if (rarity.contains("/"))
        {
            try
            {
                String[] parts = rarity.split("/");
                double num = Double.parseDouble(parts[0].trim());
                double den = Double.parseDouble(parts[1].trim());
                if (den > 0) return num / den;
            }
            catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private void renderCenteredText(Graphics2D g, String text, Color color, int x, int y, int w, int h)
    {
        Font font = FontManager.getRunescapeSmallFont();
        g.setFont(font);
        g.setColor(color);
        FontMetrics fm = g.getFontMetrics();

        // First pass: word-wrap into lines
        List<String> lines = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        for (String word : words)
        {
            String test = line.length() > 0 ? line + " " + word : word;
            if (fm.stringWidth(test) > w - 40)
            {
                if (line.length() > 0) lines.add(line.toString());
                line = new StringBuilder(word);
            }
            else
            {
                if (line.length() > 0) line.append(" ");
                line.append(word);
            }
        }
        if (line.length() > 0) lines.add(line.toString());

        // Second pass: draw each line centered horizontally and the block centered vertically
        int lineH = fm.getHeight() + 2;
        int totalH = lines.size() * lineH;
        int startY = y + (h - totalH) / 2 + fm.getAscent();

        for (int i = 0; i < lines.size(); i++)
        {
            int lineX = x + (w - fm.stringWidth(lines.get(i))) / 2;
            g.drawString(lines.get(i), lineX, startY + i * lineH);
        }
    }

    // ================================================================
    //  HELPERS
    // ================================================================

    private boolean isInVisibleRange(int drawY, int itemHeight, int clipTop, int clipHeight)
    {
        return drawY + itemHeight > clipTop && drawY < clipTop + clipHeight;
    }

    private int calculateContentHeight()
    {
        if (dropData == null) return 0;

        int height = PADDING;
        List<NpcDropData.DropSection> sections = dropData.getSections();
        for (int i = 0; i < sections.size(); i++)
        {
            height += SECTION_HEIGHT;
            if (!collapsedSections.contains(sections.get(i).getName()))
            {
                height += sections.get(i).getItems().size() * ROW_HEIGHT;
            }
            if (i < sections.size() - 1) height += 6;
        }
        height += PADDING;
        return height;
    }

    private BufferedImage getItemImage(int itemId)
    {
        if (itemId <= 0 || itemManager == null) return null;
        if (failedImageIds.contains(itemId)) return null;

        try
        {
            return itemManager.getImage(itemId);
        }
        catch (Exception e)
        {
            failedImageIds.add(itemId);
            return null;
        }
    }

    /**
     * Downloads and caches item images from the OSRS Wiki.
     * Uses a fixed thread pool (max 3 concurrent downloads) and an LRU cache.
     * Returns null until the image is ready (async).
     */
    private BufferedImage getWikiImage(String imageUrl)
    {
        if (imageUrl == null || imageUrl.isEmpty()) return null;
        if (failedImageUrls.contains(imageUrl)) return null;

        BufferedImage cached = wikiImageCache.get(imageUrl);
        if (cached != null) return cached;

        if (pendingImageDownloads.contains(imageUrl)) return null;
        if (imageDownloadExecutor.isShutdown()) return null;

        pendingImageDownloads.add(imageUrl);
        imageDownloadExecutor.submit(() ->
        {
            try
            {
                URL url = new URL(imageUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "RuneLite-collectionlogexpanded/1.0");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                BufferedImage img = ImageIO.read(conn.getInputStream());
                conn.disconnect();

                if (img != null)
                {
                    wikiImageCache.put(imageUrl, img);
                }
                else
                {
                    failedImageUrls.add(imageUrl);
                }
            }
            catch (Exception e)
            {
                failedImageUrls.add(imageUrl);
            }
            finally
            {
                pendingImageDownloads.remove(imageUrl);
            }
        });

        return null;
    }

    private Color getRarityColor(String rarity)
    {
        if (rarity == null) return RATE_DEFAULT;
        String lower = rarity.toLowerCase();
        if (lower.equals("always")) return RATE_ALWAYS;
        if (lower.equals("common")) return RATE_COMMON;
        if (lower.equals("uncommon")) return RATE_UNCOMMON;
        if (lower.equals("rare")) return RATE_RARE;
        if (lower.equals("very rare")) return RATE_VERY_RARE;

        if (rarity.contains("/"))
        {
            try
            {
                String[] parts = rarity.split("/");
                double rate = Double.parseDouble(parts[0].trim()) / Double.parseDouble(parts[1].trim());
                if (rate >= 1.0) return RATE_ALWAYS;
                if (rate >= 0.05) return RATE_COMMON;
                if (rate >= 0.01) return RATE_UNCOMMON;
                if (rate >= 0.002) return RATE_RARE;
                return RATE_VERY_RARE;
            }
            catch (NumberFormatException ignored) {}
        }
        return RATE_DEFAULT;
    }

    // ================================================================
    //  LIVE COLOR REFRESH
    // ================================================================

    /**
     * Pulls color/opacity values from config and stores them in instance fields.
     * Called once per render frame so all sub-methods use consistent values without
     * each needing to call config themselves.
     */
    private void refreshLiveColors()
    {
        liveBgColor       = config.colorBackground();
        liveHeaderBgColor = config.colorHeader();
        liveBorderColor   = config.colorBorder();
        liveTitleColor    = config.colorTitle();
        liveSectionColor  = config.colorSectionHeader();
        liveItemColor     = config.colorDropText();
        liveObtainedColor = config.colorObtained();

        liveBgAlpha = Math.max(0f, Math.min(1f, config.backgroundOpacity() / 100f));
        liveFgAlpha = Math.max(0f, Math.min(1f, config.foregroundOpacity() / 100f));
    }

    // ---- Party mode color helpers ----

    private Color partyColor(float offset)
    {
        float hue = ((System.currentTimeMillis() - partyStartTime) % 2500) / 2500f + offset;
        return Color.getHSBColor(hue % 1.0f, 0.85f, 1.0f);
    }

    private Color withAlpha(Color c, int alpha)
    {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    // ---- Search helpers ----

    private void updateSuggestions()
    {
        suggestions.clear();
        selectedSuggestion = -1;

        if (searchText.length() == 0)
        {
            return;
        }

        String query = searchText.toString().toLowerCase();

        for (String name : availableNpcNames)
        {
            if (name.toLowerCase().contains(query))
            {
                suggestions.add(name);
                if (suggestions.size() >= MAX_SUGGESTIONS)
                {
                    break;
                }
            }
        }
    }

    // ================================================================
    //  INPUT HANDLERS (called from plugin)
    // ================================================================

    /**
     * Handles a mouse press inside the overlay. Returns true if the event was consumed.
     */
    public boolean handleMousePress(Point p)
    {
        // Search suggestions (highest priority when visible)
        if (searchFocused && !suggestions.isEmpty())
        {
            for (int i = 0; i < suggestionBounds.size(); i++)
            {
                if (suggestionBounds.get(i).contains(p))
                {
                    String selected = suggestions.get(i);
                    searchFocused = false;
                    searchText = new StringBuilder();
                    suggestions.clear();
                    if (searchCallback != null)
                    {
                        searchCallback.accept(selected);
                    }
                    return true;
                }
            }
        }

        // Close button
        if (closeBounds != null && closeBounds.contains(p))
        {
            hide();
            return true;
        }

        // Drop count badge — collapse all if any expanded, expand all if all collapsed
        if (countBadgeBounds != null && countBadgeBounds.contains(p) && dropData != null)
        {
            boolean allCollapsed = true;
            for (NpcDropData.DropSection s : dropData.getSections())
            {
                if (!collapsedSections.contains(s.getName()))
                {
                    allCollapsed = false;
                    break;
                }
            }
            if (allCollapsed)
            {
                collapsedSections.clear();
            }
            else
            {
                for (NpcDropData.DropSection s : dropData.getSections())
                {
                    collapsedSections.add(s.getName());
                }
            }
            return true;
        }

        // Search bar
        if (searchBarBounds != null && searchBarBounds.contains(p))
        {
            searchFocused = true;
            updateSuggestions();
            return true;
        }
        else if (searchFocused)
        {
            searchFocused = false;
            suggestions.clear();
        }

        // Disambiguation option clicks
        if (state == State.DISAMBIGUATION)
        {
            for (int i = 0; i < disambiguationBounds.size(); i++)
            {
                if (disambiguationBounds.get(i).contains(p))
                {
                    String selected = disambiguationOptions.get(i);
                    disambiguationOptions.clear();
                    disambiguationBounds.clear();
                    if (searchCallback != null)
                    {
                        searchCallback.accept(selected);
                    }
                    return true;
                }
            }
        }

        // Scrollbar
        if (scrollbarTrackBounds != null && scrollbarTrackBounds.contains(p))
        {
            if (scrollbarThumbBounds != null && scrollbarThumbBounds.contains(p))
            {
                draggingScrollbar = true;
                dragOffset = p.y - scrollbarThumbBounds.y;
            }
            else if (maxScrollY > 0)
            {
                float ratio = (float) (p.y - scrollbarTrackBounds.y) / scrollbarTrackBounds.height;
                scrollY = (int) (ratio * maxScrollY);
                scrollY = Math.max(0, Math.min(scrollY, maxScrollY));
            }
            return true;
        }

        // Section headers (collapse toggle)
        for (Map.Entry<String, Rectangle> entry : sectionHeaderBounds.entrySet())
        {
            if (entry.getValue().contains(p))
            {
                String name = entry.getKey();
                if (collapsedSections.contains(name))
                {
                    collapsedSections.remove(name);
                }
                else
                {
                    collapsedSections.add(name);
                }
                return true;
            }
        }

        // Click-to-toggle obtained on item icons
        if (dropData != null && greyOutUnobtained)
        {
            for (ItemClickArea area : itemClickAreas)
            {
                if (area.iconBounds.contains(p))
                {
                    List<NpcDropData.DropSection> sections = dropData.getSections();
                    if (area.sectionIndex < sections.size())
                    {
                        NpcDropData.DropSection section = sections.get(area.sectionIndex);
                        if (area.itemIndex < section.getItems().size())
                        {
                            NpcDropData.DropItem item = section.getItems().get(area.itemIndex);
                            boolean newObtained = !item.isObtained();
                            item.setObtained(newObtained);
                            if (newObtained && item.getObtainedCount() == 0)
                            {
                                item.setObtainedCount(1);
                            }
                            else if (!newObtained)
                            {
                                item.setObtainedCount(0);
                            }

                            if (dataChangedCallback != null)
                            {
                                dataChangedCallback.run();
                            }
                        }
                    }
                    return true;
                }
            }
        }

        return true;
    }

    /**
     * Handles mouse drag. Returns true if consumed (scrollbar dragging).
     */
    public boolean handleMouseDrag(Point p)
    {
        if (draggingScrollbar && scrollbarTrackBounds != null && scrollbarThumbBounds != null)
        {
            int trackHeight = scrollbarTrackBounds.height;
            int thumbHeight = scrollbarThumbBounds.height;
            float ratio = (float) (p.y - dragOffset - scrollbarTrackBounds.y) / (trackHeight - thumbHeight);
            ratio = Math.max(0, Math.min(1, ratio));
            scrollY = (int) (ratio * maxScrollY);
            return true;
        }
        return false;
    }

    /**
     * Handles mouse release.
     */
    public void handleMouseRelease()
    {
        draggingScrollbar = false;
    }

    /**
     * Handles a key press when the search bar is focused.
     */
    public void handleSearchKeyPress(java.awt.event.KeyEvent e)
    {
        switch (e.getKeyCode())
        {
            case java.awt.event.KeyEvent.VK_BACK_SPACE:
                if (searchText.length() > 0)
                {
                    searchText.deleteCharAt(searchText.length() - 1);
                    updateSuggestions();
                }
                break;
            case java.awt.event.KeyEvent.VK_ENTER:
                String query;
                if (selectedSuggestion >= 0 && selectedSuggestion < suggestions.size())
                {
                    query = suggestions.get(selectedSuggestion);
                }
                else
                {
                    query = searchText.toString().trim();
                }
                if (!query.isEmpty() && searchCallback != null)
                {
                    searchCallback.accept(query);
                }
                searchFocused = false;
                searchText = new StringBuilder();
                suggestions.clear();
                break;
            case java.awt.event.KeyEvent.VK_ESCAPE:
                searchFocused = false;
                searchText = new StringBuilder();
                suggestions.clear();
                break;
            case java.awt.event.KeyEvent.VK_UP:
                if (!suggestions.isEmpty())
                {
                    selectedSuggestion = Math.max(0, selectedSuggestion - 1);
                }
                break;
            case java.awt.event.KeyEvent.VK_DOWN:
                if (!suggestions.isEmpty())
                {
                    selectedSuggestion = Math.min(suggestions.size() - 1, selectedSuggestion + 1);
                }
                break;
        }
    }

    /**
     * Appends a typed character to the search text.
     */
    public void appendSearchChar(char c)
    {
        if (searchText.length() < 40)
        {
            searchText.append(c);
            updateSuggestions();
        }
    }

    // ================================================================
    //  PUBLIC API
    // ================================================================

    /**
     * Shuts down the image download thread pool.
     * Call from CollectionPlugin.shutDown() to stop background threads.
     */
    public void shutdown()
    {
        imageDownloadExecutor.shutdownNow();
        pendingImageDownloads.clear();
        wikiImageCache.clear();
        failedImageUrls.clear();
    }

    public void showLoading(String npcName)
    {
        this.npcName = npcName;
        this.state = State.LOADING;
        this.scrollY = 0;
        this.dropData = null;
        this.errorMessage = "";
        this.collapsedSections.clear();
        this.searchFocused = false;
        this.searchText = new StringBuilder();
        this.suggestions.clear();
        this.disambiguationOptions.clear();
        this.disambiguationBounds.clear();
        this.partyMode = false;
        this.itemClickAreas.clear();
    }

    public void showDropData(NpcDropData data)
    {
        this.dropData = data;
        this.npcName = data.getNpcName();
        this.state = State.SHOWING;
        this.scrollY = 0;
        this.collapsedSections.clear();
        this.greyOutUnobtained = config.greyOutUnobtained();
        this.itemClickAreas.clear();

        // #12: Auto-collapse sections where every item has been obtained
        if (config.autoCollapseCompleted())
        {
            for (NpcDropData.DropSection section : data.getSections())
            {
                if (section.getItems().isEmpty()) continue;
                boolean allObtained = true;
                for (NpcDropData.DropItem item : section.getItems())
                {
                    if (!item.isObtained())
                    {
                        allObtained = false;
                        break;
                    }
                }
                if (allObtained)
                {
                    collapsedSections.add(section.getName());
                }
            }
        }

        // Party Pete always triggers party mode; otherwise 1/1,000,000 chance
        this.partyMode = data.getNpcName().toLowerCase().contains("party pete") || Math.random() < 0.000001;
        this.partyStartTime = System.currentTimeMillis();
    }

    /**
     * Updates drop data without resetting scroll/party/collapse state.
     * Used when obtained status changes from loot tracking.
     */
    public void updateDropData(NpcDropData data)
    {
        this.dropData = data;
        this.greyOutUnobtained = config.greyOutUnobtained();
    }

    public void showError(String npcName, String error)
    {
        this.npcName = npcName;
        this.errorMessage = error;
        this.state = State.ERROR;
        this.scrollY = 0;
        this.partyMode = false;
        this.itemClickAreas.clear();
    }

    public void hide()
    {
        this.state = State.HIDDEN;
        this.dropData = null;
        this.scrollY = 0;
        this.draggingScrollbar = false;
        this.searchFocused = false;
        this.searchText = new StringBuilder();
        this.suggestions.clear();
        this.disambiguationOptions.clear();
        this.disambiguationBounds.clear();
        this.failedImageIds.clear();
        this.itemClickAreas.clear();
        // NOTE: customX/customY are NOT reset on hide - position persists
    }

    public boolean isVisible()
    {
        return state != State.HIDDEN;
    }

    public void scroll(int direction)
    {
        scrollY += direction * SCROLL_SPEED;
        scrollY = Math.max(0, Math.min(scrollY, maxScrollY));
    }

    public boolean isInBounds(Point point)
    {
        return bounds != null && bounds.contains(point);
    }

    public boolean isCloseButtonClicked(Point point)
    {
        return closeBounds != null && closeBounds.contains(point);
    }

    public boolean isSearchFocused()
    {
        return searchFocused;
    }

    public State getState()
    {
        return state;
    }

    public NpcDropData getDropData()
    {
        return dropData;
    }

    public void setSearchCallback(Consumer<String> callback)
    {
        this.searchCallback = callback;
    }

    public void setAvailableNpcNames(List<String> names)
    {
        this.availableNpcNames = names;
    }

    public void setItemManager(ItemManager manager)
    {
        this.itemManager = manager;
    }

    /**
     * Sets the callback invoked when drop data changes (e.g. click-to-toggle obtained).
     */
    public void setDataChangedCallback(Runnable callback)
    {
        this.dataChangedCallback = callback;
    }

    /**
     * Shows disambiguation options for an NPC name with multiple wiki matches.
     */
    public void showDisambiguation(String npcName, List<String> options)
    {
        this.npcName = npcName;
        this.state = State.DISAMBIGUATION;
        this.disambiguationOptions = new ArrayList<>(options);
        this.disambiguationBounds.clear();
        this.dropData = null;
        this.scrollY = 0;
        this.searchFocused = false;
        this.searchText = new StringBuilder();
        this.suggestions.clear();
        this.partyMode = false;
        this.itemClickAreas.clear();
    }

    // ================================================================
    //  POSITION (Alt+drag support)
    // ================================================================

    /**
     * Sets a custom position for the overlay panel.
     * Use -1, -1 to reset to centered.
     */
    public void setCustomPosition(int x, int y)
    {
        this.customX = x;
        this.customY = y;
    }

    public void resetPosition()
    {
        this.customX = -1;
        this.customY = -1;
    }

    public int getCustomX()
    {
        return customX;
    }

    public int getCustomY()
    {
        return customY;
    }

    /**
     * Sets a custom size for the overlay panel.
     * Clamped to [MIN, MAX] at render time.
     */
    public void setCustomSize(int w, int h)
    {
        this.customW = Math.max(MIN_PANEL_WIDTH, w);
        this.customH = Math.max(MIN_PANEL_HEIGHT, h);
    }

    public void resetSize()
    {
        this.customW = -1;
        this.customH = -1;
    }

    public int getCustomWidth()
    {
        return customW;
    }

    public int getCustomHeight()
    {
        return customH;
    }

    /** Returns the actual rendered pixel width of the panel this frame. */
    public int getCurrentWidth()
    {
        return bounds != null ? bounds.width : (customW > 0 ? customW : PANEL_WIDTH);
    }

    /** Returns the actual rendered pixel height of the panel this frame. */
    public int getCurrentHeight()
    {
        return bounds != null ? bounds.height : (customH > 0 ? customH : PANEL_HEIGHT);
    }

    /**
     * Returns true if the point is on the resize handle (bottom-right corner grip).
     * Only valid when the overlay is visible.
     */
    public boolean isOnResizeHandle(Point point)
    {
        return resizeHandleBounds != null && resizeHandleBounds.contains(point);
    }

    /**
     * Call from CollectionPlugin.mouseMoved to update the tracked mouse position
     * so the tooltip and resize handle hover state work correctly.
     */
    public void updateMousePosition(Point p)
    {
        this.lastMousePoint = p;
    }

    public Rectangle getCurrentBounds()
    {
        return bounds;
    }
}
