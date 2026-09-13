package dev.tyler.kits;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class BakaKitsPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final int DISCORD_SLOT = 10;
    private static final String DEFAULT_DISCORD_URL = "";
    private static final String DISCORD_HEAD_TEXTURE = "http://textures.minecraft.net/texture/7873c12bffb5251a0b88d5ae75c7247cb39a75ff1a81cbe4c8a39b311ddeda";
    private static final List<KitDefinition> KITS = List.of(
            new KitDefinition("1", Material.LIGHT_GRAY_CANDLE, 11),
            new KitDefinition("2", Material.ORANGE_CANDLE, 12),
            new KitDefinition("3", Material.MAGENTA_CANDLE, 13),
            new KitDefinition("4", Material.LIGHT_BLUE_CANDLE, 14),
            new KitDefinition("5", Material.PINK_CANDLE, 15),
            new KitDefinition("6", Material.PURPLE_CANDLE, 16)
    );

    private File kitDataFile;
    private FileConfiguration kitData;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ensureKitIconDefaults();
        loadKitData();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("kit")).setExecutor(this);
        Objects.requireNonNull(getCommand("kit")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("editkit")).setExecutor(this);
        Objects.requireNonNull(getCommand("editkit")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("setdiscord")).setExecutor(this);
        Objects.requireNonNull(getCommand("setdiscord")).setTabCompleter(this);
    }

    @Override
    public void onDisable() {
        saveKitData();
    }

    private void ensureKitIconDefaults() {
        boolean changed = false;
        for (KitDefinition kit : KITS) {
            String path = "kits.icons." + kit.id();
            if (!getConfig().contains(path)) {
                getConfig().set(path, kit.defaultIcon().name());
                changed = true;
            }
        }
        if (changed) {
            saveConfig();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("kit")) {
            return handleKit(sender);
        }
        if (command.getName().equalsIgnoreCase("editkit")) {
            return handleEditKit(sender, args);
        }
        if (command.getName().equalsIgnoreCase("setdiscord")) {
            return handleSetDiscord(sender, args);
        }
        return true;
    }

    private boolean handleKit(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission("bakakits.use")) {
            send(player, "no-permission");
            return true;
        }
        openKitMenu(player);
        return true;
    }

    private boolean handleEditKit(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission("bakakits.edit")) {
            send(player, "no-permission");
            return true;
        }
        if ((args.length == 1 || args.length == 2) && args[0].equalsIgnoreCase("resetcooldown")) {
            resetOwnCooldown(player, args.length == 2 ? args[1] : "");
            return true;
        }
        if (args.length >= 3 && args[1].equalsIgnoreCase("rename")) {
            KitDefinition kit = findKit(args[0]);
            if (kit == null) {
                player.sendMessage(color("&cUnknown kit number: &e" + args[0]));
                return true;
            }
            renameKit(player, kit, String.join(" ", List.of(args).subList(2, args.length)));
            return true;
        }
        if (args.length != 2 || !args[1].equalsIgnoreCase("inv")) {
            player.sendMessage(color("&cUsage: /editkit <1|2|3|4|5|6> inv"));
            player.sendMessage(color("&cUsage: /editkit <1|2|3|4|5|6> rename <name>"));
            player.sendMessage(color("&cUsage: /editkit resetcooldown [kit]"));
            return true;
        }

        KitDefinition kit = findKit(args[0]);
        if (kit == null) {
            player.sendMessage(color("&cUnknown kit number: &e" + args[0]));
            return true;
        }

        saveKitFromPlayer(player, kit);
        player.sendMessage(color("&aSaved &e" + kit.id() + " &akit from your inventory, armor, and offhand."));
        return true;
    }

    private boolean handleSetDiscord(CommandSender sender, String[] args) {
        if (!sender.hasPermission("bakakits.setdiscord")) {
            send(sender, "no-permission");
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(color("&cUsage: /setdiscord <discordlink>"));
            return true;
        }

        String link = args[0].trim();
        if (!link.startsWith("https://") && !link.startsWith("http://")) {
            sender.sendMessage(color("&cUse a full link, like &ehttps://discord.gg/example&c."));
            return true;
        }
        try {
            URI.create(link).toURL();
        } catch (IllegalArgumentException | IOException exception) {
            sender.sendMessage(color("&cThat does not look like a valid link."));
            return true;
        }

        getConfig().set("kits.discord-link", link);
        saveConfig();
        sender.sendMessage(color("&aDiscord kit button link set to &e" + link));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("kit") || command.getName().equalsIgnoreCase("setdiscord")) {
            return Collections.emptyList();
        }
        if (!command.getName().equalsIgnoreCase("editkit") || !sender.hasPermission("bakakits.edit")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            List<String> completions = new ArrayList<>(KITS.stream().map(KitDefinition::id).toList());
            completions.add("resetcooldown");
            return completions;
        }
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("resetcooldown")) {
                return KITS.stream().map(KitDefinition::id).toList();
            }
            return List.of("inv", "rename");
        }
        return Collections.emptyList();
    }

    @EventHandler
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        if (event.getMessage().equalsIgnoreCase("/kit")) {
            event.setCancelled(true);
            handleKit(event.getPlayer());
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        InventoryHolder topHolder = event.getView().getTopInventory().getHolder();
        if (topHolder instanceof KitPreviewHolder) {
            event.setCancelled(true);
            return;
        }
        if (!(topHolder instanceof KitMenuHolder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getRawSlot() == DISCORD_SLOT) {
            sendDiscordLink(player);
            return;
        }
        for (KitDefinition kit : KITS) {
            if (kit.slot() == event.getRawSlot()) {
                if (event.getClick().isRightClick()) {
                    openKitPreview(player, kit);
                } else {
                    claimKit(player, kit);
                }
                return;
            }
        }
    }

    private void openKitMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(new KitMenuHolder(), 27, color(getConfig().getString("kits.title", "&d&lBaka &b&lKITS")));
        inventory.setItem(DISCORD_SLOT, discordHeadItem());
        for (KitDefinition kit : KITS) {
            inventory.setItem(kit.slot(), kitDisplayItem(kit, player));
        }
        player.openInventory(inventory);
    }

    private ItemStack discordHeadItem() {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta itemMeta = item.getItemMeta();
        if (itemMeta instanceof SkullMeta meta) {
            try {
                PlayerProfile profile = Bukkit.createPlayerProfile(UUID.fromString("7873c12b-ffb5-251a-0b88-d5ae75c7247c"));
                URL texture = URI.create(DISCORD_HEAD_TEXTURE).toURL();
                profile.getTextures().setSkin(texture);
                meta.setOwnerProfile(profile);
            } catch (IllegalArgumentException | IOException exception) {
                getLogger().warning("Failed to apply Discord head texture: " + exception.getMessage());
            }
            meta.displayName(color("&6&lDiscord"));
            meta.lore(List.of(
                    color("&7Custom Head ID: 4320"),
                    color("&9www.minecraft-heads.com"),
                    color(""),
                    color("&e> &lCLICK &eto Open")
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack kitDisplayItem(KitDefinition kit, Player player) {
        boolean hasPermission = player.hasPermission(kit.permission());
        boolean saved = kitData != null && kitData.isConfigurationSection("kits." + kit.id());
        long remaining = cooldownRemaining(player, kit);
        ItemStack item = new ItemStack(hasPermission ? kitIcon(kit) : Material.RED_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        meta.displayName(color((hasPermission ? "&a&l" : "&c&l") + kitDisplayName(kit)));
        meta.lore(List.of(
                color("&d&lBaka &b&lKITS"),
                color(""),
                color("&9Information:"),
                color("&b| &7Kit Number: &e" + kit.id()),
                color((hasPermission ? "&a" : "&c") + "| &7Permission: " + (hasPermission ? "&aUnlocked" : "&cLocked")),
                color((saved ? "&a" : "&c") + "| &7Kit: " + (saved ? "&aReady" : "&cNot saved")),
                color((remaining <= 0 ? "&a" : "&c") + "| &7Cooldown remaining: " + (remaining <= 0 ? "&aReady" : "&c" + formatDuration(remaining))),
                color(""),
                color("&eLeft click &7to claim."),
                color("&eRight click &7to preview."),
                color("&7Rename: &e/editkit " + kit.id() + " rename <name>"),
                color("&7Save: &e/editkit " + kit.id() + " inv")
        ));
        item.setItemMeta(meta);
        return item;
    }

    private void claimKit(Player player, KitDefinition kit) {
        if (!player.hasPermission(kit.permission())) {
            player.sendMessage(color("&cYou do not have permission for &e" + kitDisplayName(kit) + "&c."));
            return;
        }
        if (kitData == null || !kitData.isConfigurationSection("kits." + kit.id())) {
            player.sendMessage(color("&cThat kit has not been saved yet."));
            return;
        }
        long remaining = cooldownRemaining(player, kit);
        if (remaining > 0) {
            playCooldownSound(player);
            player.sendMessage(color("&cYou can claim &e" + kitDisplayName(kit) + " &cagain in &e" + formatDuration(remaining) + "&c."));
            return;
        }

        for (ItemStack item : kitData.getList("kits." + kit.id() + ".inventory", Collections.emptyList()).stream()
                .filter(ItemStack.class::isInstance)
                .map(ItemStack.class::cast)
                .toList()) {
            if (item != null && !item.getType().isAir()) {
                returnToPlayer(player, item.clone());
            }
        }
        for (ItemStack item : kitData.getList("kits." + kit.id() + ".armor", Collections.emptyList()).stream()
                .filter(ItemStack.class::isInstance)
                .map(ItemStack.class::cast)
                .toList()) {
            giveArmorOrReturn(player, item);
        }
        ItemStack offhand = kitData.getItemStack("kits." + kit.id() + ".offhand");
        if (offhand != null && !offhand.getType().isAir()) {
            if (player.getInventory().getItemInOffHand().getType().isAir()) {
                player.getInventory().setItemInOffHand(offhand.clone());
            } else {
                returnToPlayer(player, offhand.clone());
            }
        }

        setCooldown(player, kit);
        player.sendMessage(color("&aClaimed &e" + kitDisplayName(kit) + "&a."));
        player.closeInventory();
    }

    private void openKitPreview(Player player, KitDefinition kit) {
        if (kitData == null || !kitData.isConfigurationSection("kits." + kit.id())) {
            player.sendMessage(color("&cThat kit has not been saved yet."));
            return;
        }

        Inventory inventory = Bukkit.createInventory(new KitPreviewHolder(), 54, color("&8Preview : &e" + kitDisplayName(kit)));
        inventory.setItem(0, menuItem(Material.LEATHER_HELMET, "&bHelmet", "&7Armor preview slot."));
        inventory.setItem(1, menuItem(Material.IRON_CHESTPLATE, "&bChestplate", "&7Armor preview slot."));
        inventory.setItem(2, menuItem(Material.IRON_LEGGINGS, "&bLeggings", "&7Armor preview slot."));
        inventory.setItem(3, menuItem(Material.IRON_BOOTS, "&bBoots", "&7Armor preview slot."));
        inventory.setItem(5, menuItem(Material.SHIELD, "&bOffhand", "&7Offhand preview slot."));
        inventory.setItem(9, menuItem(Material.CHEST, "&aInventory Items", "&7The kit contents are shown below."));

        List<ItemStack> armor = kitData.getList("kits." + kit.id() + ".armor", Collections.emptyList()).stream()
                .filter(ItemStack.class::isInstance)
                .map(ItemStack.class::cast)
                .toList();
        for (ItemStack item : armor) {
            placeArmorPreview(inventory, item);
        }

        ItemStack offhand = kitData.getItemStack("kits." + kit.id() + ".offhand");
        if (offhand != null && !offhand.getType().isAir()) {
            inventory.setItem(5, offhand.clone());
        }

        int slot = 18;
        for (ItemStack item : kitData.getList("kits." + kit.id() + ".inventory", Collections.emptyList()).stream()
                .filter(ItemStack.class::isInstance)
                .map(ItemStack.class::cast)
                .toList()) {
            if (item == null || item.getType().isAir()) {
                continue;
            }
            while (slot < inventory.getSize() && inventory.getItem(slot) != null) {
                slot++;
            }
            if (slot >= inventory.getSize()) {
                break;
            }
            inventory.setItem(slot, item.clone());
            slot++;
        }

        player.openInventory(inventory);
    }

    private void placeArmorPreview(Inventory inventory, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        String material = item.getType().name();
        if (material.endsWith("_HELMET")) {
            inventory.setItem(0, item.clone());
        } else if (material.endsWith("_CHESTPLATE")) {
            inventory.setItem(1, item.clone());
        } else if (material.endsWith("_LEGGINGS")) {
            inventory.setItem(2, item.clone());
        } else if (material.endsWith("_BOOTS")) {
            inventory.setItem(3, item.clone());
        } else {
            int slot = firstPreviewEmpty(inventory, 18);
            if (slot >= 0) {
                inventory.setItem(slot, item.clone());
            }
        }
    }

    private int firstPreviewEmpty(Inventory inventory, int startSlot) {
        for (int slot = startSlot; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) {
                return slot;
            }
        }
        return -1;
    }

    private ItemStack menuItem(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(color(name));
            meta.lore(List.of(color(lore)));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void playCooldownSound(Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 0.8f);
    }

    private Material kitIcon(KitDefinition kit) {
        String configured = getConfig().getString("kits.icons." + kit.id(), kit.defaultIcon().name());
        Material material = Material.matchMaterial(configured == null ? "" : configured);
        if (material == null || material.isAir()) {
            return kit.defaultIcon();
        }
        return material;
    }

    private void renameKit(Player player, KitDefinition kit, String name) {
        String trimmed = name.trim();
        if (trimmed.isBlank()) {
            player.sendMessage(color("&cUsage: /editkit " + kit.id() + " rename <name>"));
            return;
        }
        if (kitData == null) {
            loadKitData();
        }
        kitData.set("kits." + kit.id() + ".name", trimmed);
        saveKitData();
        player.sendMessage(color("&aRenamed kit &e" + kit.id() + " &ato &e" + trimmed + "&a."));
    }

    private void saveKitFromPlayer(Player player, KitDefinition kit) {
        if (kitData == null) {
            loadKitData();
        }

        String path = "kits." + kit.id();
        List<ItemStack> inventory = new ArrayList<>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && !item.getType().isAir()) {
                inventory.add(item.clone());
            }
        }

        List<ItemStack> armor = new ArrayList<>();
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (item != null && !item.getType().isAir()) {
                armor.add(item.clone());
            }
        }

        ItemStack offhand = player.getInventory().getItemInOffHand();
        kitData.set(path + ".inventory", inventory);
        kitData.set(path + ".armor", armor);
        kitData.set(path + ".offhand", offhand == null || offhand.getType().isAir() ? null : offhand.clone());
        saveKitData();
    }

    private void resetOwnCooldown(Player player, String kitName) {
        if (kitData == null) {
            loadKitData();
        }

        String basePath = "cooldowns." + player.getUniqueId();
        if (kitName == null || kitName.isBlank()) {
            kitData.set(basePath, null);
            saveKitData();
            player.sendMessage(color("&aReset all of your kit cooldowns."));
            return;
        }

        KitDefinition kit = findKit(kitName);
        if (kit == null) {
            player.sendMessage(color("&cUnknown kit: &e" + kitName));
            return;
        }

        kitData.set(basePath + "." + kit.id(), null);
        saveKitData();
        player.sendMessage(color("&aReset your &e" + kit.id() + " &akit cooldown."));
    }

    private void loadKitData() {
        kitDataFile = new File(getDataFolder(), "kits.yml");
        kitData = YamlConfiguration.loadConfiguration(kitDataFile);
    }

    private void saveKitData() {
        if (kitData == null || kitDataFile == null) {
            return;
        }
        try {
            kitData.save(kitDataFile);
        } catch (IOException exception) {
            getLogger().warning("Failed to save kits.yml: " + exception.getMessage());
        }
    }

    private long cooldownRemaining(Player player, KitDefinition kit) {
        if (kitData == null) {
            return 0L;
        }
        long cooldown = kitCooldownMinutes(kit) * 60L * 1000L;
        long lastClaim = kitData.getLong("cooldowns." + player.getUniqueId() + "." + kit.id(), 0L);
        return Math.max(0L, cooldown - (System.currentTimeMillis() - lastClaim));
    }

    private long kitCooldownMinutes(KitDefinition kit) {
        long fallback = getConfig().getLong("kits.cooldown-minutes", 30L);
        return Math.max(0L, getConfig().getLong("kits.per-kit-cooldowns." + kit.id(), fallback));
    }

    private void setCooldown(Player player, KitDefinition kit) {
        if (kitData == null) {
            loadKitData();
        }
        kitData.set("cooldowns." + player.getUniqueId() + "." + kit.id(), System.currentTimeMillis());
        saveKitData();
    }

    private void sendDiscordLink(Player player) {
        String discordUrl = discordUrl();
        if (discordUrl.isBlank()) {
            player.sendMessage(color("&cThe Discord link has not been set yet."));
            return;
        }
        player.sendMessage(color("&d&lBaka &b&lKITS &8> &eDiscord: ")
                .append(Component.text(discordUrl)
                        .color(NamedTextColor.AQUA)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(discordUrl))));
    }

    private String discordUrl() {
        String configured = getConfig().getString("kits.discord-link", DEFAULT_DISCORD_URL);
        if (configured == null || configured.isBlank()) {
            return "";
        }
        return configured;
    }

    private String kitDisplayName(KitDefinition kit) {
        if (kitData != null) {
            String configured = kitData.getString("kits." + kit.id() + ".name", "");
            if (configured != null && !configured.isBlank()) {
                return configured;
            }
        }
        return "Unnamed Kit #" + kit.id();
    }

    private void giveArmorOrReturn(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        Material material = item.getType();
        if (material.name().endsWith("_HELMET") && isEmpty(inventory.getHelmet())) {
            inventory.setHelmet(item.clone());
        } else if (material.name().endsWith("_CHESTPLATE") && isEmpty(inventory.getChestplate())) {
            inventory.setChestplate(item.clone());
        } else if (material.name().endsWith("_LEGGINGS") && isEmpty(inventory.getLeggings())) {
            inventory.setLeggings(item.clone());
        } else if (material.name().endsWith("_BOOTS") && isEmpty(inventory.getBoots())) {
            inventory.setBoots(item.clone());
        } else {
            returnToPlayer(player, item.clone());
        }
    }

    private void returnToPlayer(Player player, ItemStack item) {
        MapReturner.returnToPlayer(player, item);
    }

    private boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    private KitDefinition findKit(String input) {
        String normalized = input.toLowerCase(Locale.ROOT);
        for (KitDefinition kit : KITS) {
            if (kit.id().equals(normalized)) {
                return kit;
            }
        }
        return null;
    }

    private String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long days = totalSeconds / 86400L;
        long hours = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        List<String> parts = new ArrayList<>();
        if (days > 0) {
            parts.add(days + "d");
        }
        if (hours > 0) {
            parts.add(hours + "h");
        }
        if (minutes > 0) {
            parts.add(minutes + "min");
        }
        if (seconds > 0 || parts.isEmpty()) {
            parts.add(seconds + "s");
        }
        return String.join(" ", parts);
    }

    private void send(CommandSender sender, String key) {
        sender.sendMessage(color(getConfig().getString("messages.prefix", "") + getConfig().getString("messages." + key, "")));
    }

    private static Component color(String text) {
        return LEGACY.deserialize(text == null ? "" : text).decoration(TextDecoration.ITALIC, false);
    }

    private record KitDefinition(String id, Material defaultIcon, int slot) {
        private String permission() {
            return "bakakits.claim." + id;
        }

    }

    private static final class KitMenuHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return Bukkit.createInventory(this, 9);
        }
    }

    private static final class KitPreviewHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return Bukkit.createInventory(this, 54);
        }
    }

    private static final class MapReturner {
        private static void returnToPlayer(Player player, ItemStack item) {
            player.getInventory().addItem(item).values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
    }
}
