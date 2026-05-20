package com.AugustBurns;

import java.util.ArrayList;
import java.util.List;

/**
 * Data model for an NPC's drop table.
 * Serialized to/from JSON for local caching.
 */
public class NpcDropData
{
    private String npcName;
    private long fetchedAt;
    private List<DropSection> sections;
    /** Total confirmed kills tracked via loot events. Used for dry-streak probability. */
    private int killCount;

    public NpcDropData()
    {
        this.sections = new ArrayList<>();
    }

    public NpcDropData(String npcName, List<DropSection> sections)
    {
        this.npcName = npcName;
        this.fetchedAt = System.currentTimeMillis();
        this.sections = sections != null ? sections : new ArrayList<>();
    }

    public String getNpcName()
    {
        return npcName;
    }

    public void setNpcName(String npcName)
    {
        this.npcName = npcName;
    }

    public long getFetchedAt()
    {
        return fetchedAt;
    }

    public void setFetchedAt(long fetchedAt)
    {
        this.fetchedAt = fetchedAt;
    }

    public List<DropSection> getSections()
    {
        return sections;
    }

    public void setSections(List<DropSection> sections)
    {
        this.sections = sections;
    }

    public int getTotalDropCount()
    {
        int count = 0;
        for (DropSection section : sections)
        {
            count += section.getItems().size();
        }
        return count;
    }

    public int getKillCount()
    {
        return killCount;
    }

    public void setKillCount(int killCount)
    {
        this.killCount = killCount;
    }

    public void incrementKillCount()
    {
        this.killCount++;
    }

    /**
     * A named section of the drop table (e.g. "Weapons and armour", "Runes").
     */
    public static class DropSection
    {
        private String name;
        private List<DropItem> items;

        public DropSection()
        {
            this.items = new ArrayList<>();
        }

        public DropSection(String name)
        {
            this.name = name;
            this.items = new ArrayList<>();
        }

        public String getName()
        {
            return name;
        }

        public void setName(String name)
        {
            this.name = name;
        }

        public List<DropItem> getItems()
        {
            return items;
        }

        public void setItems(List<DropItem> items)
        {
            this.items = items;
        }
    }

    /**
     * A single drop entry with item details and drop rate.
     */
    public static class DropItem
    {
        private String name;
        private int id;
        private String quantity;
        private String rarity;
        private boolean obtained;
        private int obtainedCount;
        private String imageUrl;
        /** True when this drop only occurs on free-to-play worlds (members=No on wiki). */
        private boolean f2pOnly;

        public DropItem()
        {
            this.id = -1;
            this.obtained = false;
            this.obtainedCount = 0;
        }

        public DropItem(String name, int id, String quantity, String rarity)
        {
            this.name = name;
            this.id = id;
            this.quantity = quantity;
            this.rarity = rarity;
            this.obtained = false;
            this.obtainedCount = 0;
        }

        public String getName()
        {
            return name;
        }

        public void setName(String name)
        {
            this.name = name;
        }

        public int getId()
        {
            return id;
        }

        public void setId(int id)
        {
            this.id = id;
        }

        public String getQuantity()
        {
            return quantity;
        }

        public void setQuantity(String quantity)
        {
            this.quantity = quantity;
        }

        public String getRarity()
        {
            return rarity;
        }

        public void setRarity(String rarity)
        {
            this.rarity = rarity;
        }

        public boolean isObtained()
        {
            return obtained;
        }

        public void setObtained(boolean obtained)
        {
            this.obtained = obtained;
        }

        public int getObtainedCount()
        {
            return obtainedCount;
        }

        public void setObtainedCount(int obtainedCount)
        {
            this.obtainedCount = obtainedCount;
        }

        public String getImageUrl()
        {
            return imageUrl;
        }

        public void setImageUrl(String imageUrl)
        {
            this.imageUrl = imageUrl;
        }

        public boolean isF2pOnly()
        {
            return f2pOnly;
        }

        public void setF2pOnly(boolean f2pOnly)
        {
            this.f2pOnly = f2pOnly;
        }

        /**
         * Increments the obtained count by 1 and sets obtained to true.
         */
        public void incrementObtainedCount()
        {
            this.obtainedCount++;
            this.obtained = true;
        }

        /**
         * Returns a display string like "Item Name (3-5)" or just "Item Name".
         */
        public String getDisplayName()
        {
            if (quantity != null && !quantity.equals("1") && !quantity.isEmpty())
            {
                return name + " (" + quantity + ")";
            }
            return name;
        }
    }
}