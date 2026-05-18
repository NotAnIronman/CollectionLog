package com.AugustBurns;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("collectionlogexpanded")
public interface CollectionPluginConfig extends Config
{
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

    @ConfigSection(
        name = "Cache",
        description = "Control how drop data is cached locally",
        position = 2
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
}
