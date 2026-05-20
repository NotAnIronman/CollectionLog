package com.AugustBurns;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

import java.awt.Color;

@ConfigGroup("collectionlogexpanded")
public interface CollectionPluginConfig extends Config
{
    // ================================================================
    //  OVERLAY BEHAVIOR
    // ================================================================

    @ConfigSection(
        name = "Overlay Behavior",
        description = "Control how the drop table overlay behaves",
        position = 0
    )
    String overlaySection = "overlay";

    @ConfigItem(
        keyName = "closeOnEscape",
        name = "Close on Escape",
        description = "Close the drop table overlay when pressing Escape",
        section = overlaySection,
        position = 0
    )
    default boolean closeOnEscape()
    {
        return true;
    }

    @ConfigItem(
        keyName = "closeOnClickOutside",
        name = "Close on Click Outside",
        description = "Close the drop table overlay when clicking outside of it",
        section = overlaySection,
        position = 1
    )
    default boolean closeOnClickOutside()
    {
        return true;
    }

    @ConfigItem(
        keyName = "closeOnDamage",
        name = "Close on Damage",
        description = "Close the drop table overlay when you take damage",
        section = overlaySection,
        position = 2
    )
    default boolean closeOnDamage()
    {
        return false;
    }

    @ConfigItem(
        keyName = "requireShiftForMenu",
        name = "Require Shift for Menu",
        description = "Only add the 'Collection Log' right-click option when holding Shift, to reduce menu clutter",
        section = overlaySection,
        position = 3
    )
    default boolean requireShiftForMenu()
    {
        return false;
    }

    // ================================================================
    //  DISPLAY
    // ================================================================

    @ConfigSection(
        name = "Display",
        description = "Control how drops are displayed",
        position = 1
    )
    String displaySection = "display";

    @ConfigItem(
        keyName = "greyOutUnobtained",
        name = "Grey Out Unobtained",
        description = "Grey out items you haven't received as a drop yet",
        section = displaySection,
        position = 0
    )
    default boolean greyOutUnobtained()
    {
        return false;
    }

    @ConfigItem(
        keyName = "showCompletionCount",
        name = "Show Completion Count (#/#)",
        description = "Show obtained/total counts next to section headers, and turn the header green when the section is complete",
        section = displaySection,
        position = 1
    )
    default boolean showCompletionCount()
    {
        return false;
    }

    @ConfigItem(
        keyName = "showDryMessage",
        name = "Show Dry/Over-rate Message",
        description = "Show a humorous message in the drop tooltip indicating how dry or lucky you are relative to the expected drop rate",
        section = displaySection,
        position = 2
    )
    default boolean showDryMessage()
    {
        return true;
    }

    @ConfigItem(
        keyName = "autoPreloadNearbyNpcs",
        name = "Auto-preload Nearby NPCs",
        description = "Silently fetch and cache drop tables for combat NPCs that spawn near you, "
                    + "so kill tracking starts immediately without needing to open their Collection Log first",
        section = displaySection,
        position = 3
    )
    default boolean autoPreloadNearbyNpcs()
    {
        return false;
    }

    @ConfigItem(
        keyName = "autoCollapseCompleted",
        name = "Auto-collapse Completed Sections",
        description = "When opening the drop table for an NPC, automatically collapse any section where all items have been obtained",
        section = displaySection,
        position = 4
    )
    default boolean autoCollapseCompleted()
    {
        return false;
    }

    // ================================================================
    //  APPEARANCE — Colors
    // ================================================================

    @ConfigSection(
        name = "Appearance",
        description = "Customize the colors and opacity of the overlay",
        position = 2,
        closedByDefault = true
    )
    String appearanceSection = "appearance";

    @ConfigItem(
        keyName = "colorBackground",
        name = "Background Color",
        description = "Main background color of the overlay panel",
        section = appearanceSection,
        position = 0
    )
    default Color colorBackground()
    {
        return new Color(30, 25, 19);
    }

    @ConfigItem(
        keyName = "colorHeader",
        name = "Header Color",
        description = "Background color of the header bar",
        section = appearanceSection,
        position = 1
    )
    default Color colorHeader()
    {
        return new Color(50, 40, 28);
    }

    @ConfigItem(
        keyName = "colorBorder",
        name = "Border Color",
        description = "Color of the panel border and dividers",
        section = appearanceSection,
        position = 2
    )
    default Color colorBorder()
    {
        return new Color(109, 96, 73);
    }

    @ConfigItem(
        keyName = "colorTitle",
        name = "Title Color",
        description = "Color of the NPC name in the header",
        section = appearanceSection,
        position = 3
    )
    default Color colorTitle()
    {
        return new Color(255, 152, 31);
    }

    @ConfigItem(
        keyName = "colorSectionHeader",
        name = "Section Header Color",
        description = "Color of section header text (e.g. 'Runes and ammunition')",
        section = appearanceSection,
        position = 4
    )
    default Color colorSectionHeader()
    {
        return new Color(255, 203, 5);
    }

    @ConfigItem(
        keyName = "colorDropText",
        name = "Drop Item Text Color",
        description = "Color of drop item names in the list (foreground)",
        section = appearanceSection,
        position = 5
    )
    default Color colorDropText()
    {
        return new Color(225, 215, 195);
    }

    @ConfigItem(
        keyName = "colorObtained",
        name = "Obtained Highlight Color",
        description = "Color used to highlight items you have already obtained",
        section = appearanceSection,
        position = 6
    )
    default Color colorObtained()
    {
        return new Color(30, 200, 80);
    }

    // ================================================================
    //  APPEARANCE — Opacity
    // ================================================================

    @ConfigItem(
        keyName = "backgroundOpacity",
        name = "Background Opacity",
        description = "Opacity of the overlay background (0 = fully transparent, 100 = fully opaque)",
        section = appearanceSection,
        position = 7
    )
    @Range(min = 0, max = 100)
    default int backgroundOpacity()
    {
        return 94;  // matches the original 240/255 ≈ 94%
    }

    @ConfigItem(
        keyName = "foregroundOpacity",
        name = "Drop Row Opacity",
        description = "Opacity of individual drop item rows (0 = fully transparent, 100 = fully opaque)",
        section = appearanceSection,
        position = 8
    )
    @Range(min = 0, max = 100)
    default int foregroundOpacity()
    {
        return 100;
    }

    // ================================================================
    //  CACHE
    // ================================================================

    @ConfigSection(
        name = "Cache",
        description = "Control how drop data is cached locally",
        position = 3
    )
    String cacheSection = "cache";

    @ConfigItem(
        keyName = "cacheExpiryDays",
        name = "Cache Expiry (days)",
        description = "Number of days before cached drop data is refreshed from the wiki (0 = never expires)",
        section = cacheSection,
        position = 0
    )
    default int cacheExpiryDays()
    {
        return 7;
    }

    @ConfigItem(
        keyName = "confirmClearCache",
        name = "⚠ Confirm: I understand this is permanent",
        description = "<html><b>READ BEFORE CLEARING:</b><br>"
                    + "Clearing the cache will permanently delete all locally stored drop data,<br>"
                    + "including every item marked as obtained and all kill counts tracked.<br>"
                    + "<b>This cannot be undone.</b><br><br>"
                    + "Tick this box first, then tick 'Clear Drop Cache Now' to proceed.</html>",
        section = cacheSection,
        position = 1
    )
    default boolean confirmClearCache()
    {
        return false;
    }

    @ConfigItem(
        keyName = "clearDropCache",
        name = "Clear Drop Cache Now",
        description = "Clears all cached drop data. You must tick the confirmation box above first.",
        section = cacheSection,
        position = 2
    )
    default boolean clearDropCache()
    {
        return false;
    }
}

