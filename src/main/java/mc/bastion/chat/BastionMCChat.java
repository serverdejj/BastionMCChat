package mc.bastion.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BastionMCChat extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder().character('&').hexColors().build();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final Pattern HEX = Pattern.compile("&#([0-9A-Fa-f]{6})");
    private static final Pattern MENTION = Pattern.compile("(?<![A-Za-z0-9_])@([A-Za-z0-9_]{3,16})(?![A-Za-z0-9_])");
    private static final Pattern REPLACEMENTS = Pattern.compile("\\{(clan|rankup|rank|player|message)\\}");
    private FileConfiguration messages;
    private FileConfiguration colors;
    private FileConfiguration playerData;
    private File playerDataFile;
    private final Map<UUID, String> selectedColors = new HashMap<>();
    private final Map<UUID, Long> lastMessage = new HashMap<>();
    private final Map<UUID, Long> lastShowcase = new HashMap<>();
    private final Map<UUID, Long> lastMention = new HashMap<>();
    private final Map<UUID, String> previousText = new HashMap<>();
    private final Map<UUID, Long> previousTextTime = new HashMap<>();
    private final Map<UUID, Bubble> bubbles = new HashMap<>();
    private final Map<UUID, Snapshot> snapshots = new HashMap<>();
    private BukkitTask bubbleTask;
    private BukkitTask cleanupTask;
    private Method placeholderMethod;

    private enum ViewType { ITEM, INVENTORY, COLORS }
    private static final class MenuHolder implements InventoryHolder {
        final ViewType type;
        final UUID snapshotId;
        final Map<Integer, String> colorSlots = new HashMap<>();
        Inventory inventory;
        MenuHolder(ViewType type, UUID snapshotId) { this.type = type; this.snapshotId = snapshotId; }
        @Override public Inventory getInventory() { return inventory; }
    }
    private static final class Snapshot {
        final UUID id;
        final ViewType type;
        final String owner;
        final long expiresAt;
        final ItemStack item;
        final ItemStack[] storage;
        final ItemStack[] armor;
        final ItemStack offhand;
        Snapshot(UUID id, ViewType type, Player player, long durationMs) {
            this.id = id;
            this.type = type;
            this.owner = player.getName();
            this.expiresAt = System.currentTimeMillis() + durationMs;
            this.item = cloneItem(player.getInventory().getItemInMainHand());
            this.storage = cloneItems(player.getInventory().getStorageContents());
            this.armor = cloneItems(player.getInventory().getArmorContents());
            this.offhand = cloneItem(player.getInventory().getItemInOffHand());
        }
    }
    private static final class Bubble {
        final TextDisplay display;
        final UUID speaker;
        final long until;
        Bubble(TextDisplay display, UUID speaker, long until) {
            this.display = display; this.speaker = speaker; this.until = until;
        }
    }
    private static final class ProcessedMessage {
        final Component component;
        final Component bubble;
        final Set<UUID> mentions;
        ProcessedMessage(Component component, Component bubble, Set<UUID> mentions) {
            this.component = component; this.bubble = bubble; this.mentions = mentions;
        }
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("messages.yml");
        saveResourceIfMissing("chatcolor.yml");
        reloadAll();
        Objects.requireNonNull(getCommand("chatcolor")).setExecutor(this);
        Objects.requireNonNull(getCommand("chatcolor")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("bastionchat")).setExecutor(this);
        Objects.requireNonNull(getCommand("bastionchat")).setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        startTasks();
        getLogger().info("BastionMCChat 1.0.2 habilitado.");
    }
    @Override public void onDisable() {
        if (bubbleTask != null) bubbleTask.cancel();
        if (cleanupTask != null) cleanupTask.cancel();
        clearBubbles();
        savePlayerData();
    }
    private void saveResourceIfMissing(String path) {
        if (!new File(getDataFolder(), path).isFile()) saveResource(path, false);
    }
    private void reloadAll() {
        reloadConfig();
        messages = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
        colors = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "chatcolor.yml"));
        if (playerDataFile == null) {
            playerDataFile = new File(getDataFolder(), "player-colors.yml");
            playerData = YamlConfiguration.loadConfiguration(playerDataFile);
            selectedColors.clear();
            ConfigurationSection section = playerData.getConfigurationSection("players");
            if (section != null) for (String key : section.getKeys(false)) {
                try { selectedColors.put(UUID.fromString(key), section.getString(key)); }
                catch (IllegalArgumentException ignored) {}
            }
        }
        placeholderMethod = null;
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                placeholderMethod = papi.getMethod("setPlaceholders", Player.class, String.class);
            } catch (ReflectiveOperationException ex) {
                getLogger().warning("PlaceholderAPI no expone el metodo esperado: " + ex.getMessage());
            }
        }
    }
    private void startTasks() {
        if (bubbleTask != null) bubbleTask.cancel();
        if (cleanupTask != null) cleanupTask.cancel();
        bubbleTask = Bukkit.getScheduler().runTaskTimer(this, this::tickBubbles, 2L, Math.max(1L, getConfig().getLong("chat-bubbles.update-ticks", 2)));
        cleanupTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            snapshots.values().removeIf(s -> s.expiresAt <= now);
        }, 20L, 100L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!getConfig().getBoolean("chat.enabled", true)) return;
        // Cancel the vanilla broadcast, then read player inventory and send messages on the main thread.
        // Plugins that cancel at LOWEST..HIGH don't get overridden, including clan/private chat handlers.
        event.setCancelled(true);
        UUID senderId = event.getPlayer().getUniqueId();
        String raw = PLAIN.serialize(event.message());
        Bukkit.getScheduler().runTask(this, () -> {
            Player sender = Bukkit.getPlayer(senderId);
            if (sender != null && sender.isOnline()) broadcast(sender, raw);
        });
    }
    private void broadcast(Player player, String raw) {
        if (raw.isBlank()) return;
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        if (!player.hasPermission("bastionchat.bypass.cooldown")) {
            long interval = Math.max(0, getConfig().getLong("chat.cooldown-seconds", 2) * 1000);
            if (now - lastMessage.getOrDefault(id, 0L) < interval) {
                notice(player, "chat-cooldown"); return;
            }
            long duplicates = Math.max(0, getConfig().getLong("chat.duplicate-window-seconds", 15) * 1000);
            if (getConfig().getBoolean("chat.block-duplicate-messages", true)
                && raw.equalsIgnoreCase(previousText.getOrDefault(id, ""))
                && now - previousTextTime.getOrDefault(id, 0L) < duplicates) {
                notice(player, "chat-duplicate"); return;
            }
        }
        boolean askingShowcase = getConfig().getBoolean("showcase.enabled", true) && hasShowcaseToken(raw);
        if (askingShowcase && !player.hasPermission("bastionchat.bypass.cooldown")) {
            long elapsed = now - lastShowcase.getOrDefault(id, 0L);
            long limit = Math.max(0, getConfig().getLong("showcase.cooldown-seconds", 10) * 1000);
            if (elapsed < limit) { notice(player, "showcase-cooldown"); return; }
        }
        lastMessage.put(id, now);
        previousText.put(id, raw);
        previousTextTime.put(id, now);
        if (askingShowcase) lastShowcase.put(id, now);

        ProcessedMessage parsed = parseMessage(player, raw);
        Component formatted = renderFormat(player, parsed.component);
        Bukkit.getConsoleSender().sendMessage(formatted);
        for (Player recipient : Bukkit.getOnlinePlayers()) recipient.sendMessage(formatted);
        sendMentionSounds(player, parsed.mentions, now);
        if (getConfig().getBoolean("chat-bubbles.enabled", true)
            && !getConfig().getStringList("chat-bubbles.disabled-worlds").contains(player.getWorld().getName())) {
            createBubble(player, parsed.bubble);
        }
    }

    private String parsePapi(Player player, String string) {
        if (string == null || string.isBlank()) return "";
        if (placeholderMethod == null) return string;
        try {
            Object result = placeholderMethod.invoke(null, player, string);
            return result == null ? string : result.toString();
        } catch (ReflectiveOperationException ignored) { return string; }
    }
    private boolean unresolved(String value) {
        return value == null || value.isBlank() || value.matches("%[^%]+%") || value.matches("\\{[^}]+\\}");
    }
    /** Lee directamente el clan y su color desde la API publica de BastionMCClans.
     *  Si no existe o esta desactualizada, usa el placeholder configurable. */
    private String directClanColored(Player player) {
        org.bukkit.plugin.Plugin clans = Bukkit.getPluginManager().getPlugin("BastionMCClans");
        if (clans == null || !clans.isEnabled()) return null;
        try {
            Method hasClanMethod = clans.getClass().getMethod("hasClan", UUID.class);
            Object hasClan = hasClanMethod.invoke(clans, player.getUniqueId());
            if (!Boolean.TRUE.equals(hasClan)) return "";
            Method coloredMethod = clans.getClass().getMethod("getClanColored", UUID.class);
            Object result = coloredMethod.invoke(clans, player.getUniqueId());
            return result instanceof String ? (String) result : "";
        } catch (ReflectiveOperationException | SecurityException ex) {
            // Compatibilidad con otras versiones del plugin: usar PlaceholderAPI.
            return null;
        }
    }
    private Component clanComponent(Player player) {
        if (!getConfig().getBoolean("clan.enabled", true)) return Component.empty();
        String fallback = getConfig().getString("clan.no-clan", "&c✘");
        String value = null;
        if (getConfig().getBoolean("clan.use-direct-api", true)) {
            value = directClanColored(player);
        }
        if (value == null) {
            value = parsePapi(player, getConfig().getString("clan.placeholder", "%bastionclans_colored%"));
        }
        boolean missing = unresolved(value);
        if (!missing) for (String empty : getConfig().getStringList("clan.no-clan-values")) {
            if (empty.equalsIgnoreCase(PLAIN.serialize(colorize(value)).trim())) { missing = true; break; }
        }
        if (missing) return colorize(fallback);
        return colorize(getConfig().getString("clan.format", "&7[{clan}]").replace("{clan}", value));
    }
    private Component rankupComponent(Player player) {
        if (!getConfig().getBoolean("rankup.enabled", true)) return Component.empty();
        String level = parsePapi(player, getConfig().getString("rankup.placeholder", ""));
        if (unresolved(level)) level = getConfig().getString("rankup.default-level", "1");
        return colorize(getConfig().getString("rankup.format", "&e[{level}✦]").replace("{level}", level));
    }
    private Component rankComponent(Player player) {
        if (!getConfig().getBoolean("rank.enabled", true)) return Component.empty();
        String prefix = parsePapi(player, getConfig().getString("rank.placeholder", ""));
        if (unresolved(prefix)) prefix = getConfig().getString("rank.fallback", "&7[DEFAULT]");
        return colorize(getConfig().getString("rank.format", "{prefix}").replace("{prefix}", prefix));
    }
    private Component renderFormat(Player player, Component message) {
        String template = getConfig().getString("chat.format", "{clan} {rankup} {rank} &f{player} &8» {message}");
        Matcher matcher = REPLACEMENTS.matcher(template);
        int last = 0;
        Component result = Component.empty();
        while (matcher.find()) {
            result = result.append(colorize(template.substring(last, matcher.start())));
            Component replacement = switch (matcher.group(1)) {
                case "clan" -> clanComponent(player);
                case "rankup" -> rankupComponent(player);
                case "rank" -> rankComponent(player);
                case "player" -> Component.text(player.getName());
                case "message" -> message;
                default -> Component.empty();
            };
            result = result.append(replacement);
            last = matcher.end();
        }
        return result.append(colorize(template.substring(last)));
    }

    private boolean hasShowcaseToken(String raw) {
        for (String path : List.of("showcase.item", "showcase.inventory")) {
            if (!getConfig().getBoolean(path + ".enabled", true)) continue;
            String perm = path.endsWith(".item") ? "bastionchat.showitem" : "bastionchat.showinventory";
            // Permission handled by parseMessage, but the check here only detects presence.
            for (String alias : getConfig().getStringList(path + ".aliases")) {
                if (raw.toLowerCase(Locale.ROOT).contains(alias.toLowerCase(Locale.ROOT))) return true;
            }
        }
        return false;
    }
    private ProcessedMessage parseMessage(Player player, String raw) {
        Component message = Component.empty();
        Component bubble = Component.empty();
        Set<UUID> mentioned = new LinkedHashSet<>();
        String base = selectedColors.getOrDefault(player.getUniqueId(), getConfig().getString("chat.default-message-color", "&f"));
        // Chat color is always a single legacy or HEX code, never extracted from player-supplied text.
        final net.kyori.adventure.text.format.TextColor chosen = solidTextColor(base);

        List<String[]> tokens = new ArrayList<>();
        for (String path : List.of("showcase.item", "showcase.inventory", "showcase.ping")) {
            if (!getConfig().getBoolean("showcase.enabled", true) || !getConfig().getBoolean(path + ".enabled", true)) continue;
            String permission = switch(path) {
                case "showcase.item" -> "bastionchat.showitem";
                case "showcase.inventory" -> "bastionchat.showinventory";
                default -> "bastionchat.showping";
            };
            if (!player.hasPermission(permission)) continue;
            for (String alias : getConfig().getStringList(path + ".aliases")) tokens.add(new String[]{alias, path});
        }
        tokens.sort(Comparator.comparingInt((String[] s) -> s[0].length()).reversed());
        int index = 0;
        List<String> bubbleParts = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        while (index < raw.length()) {
            String[] matched = null;
            for (String[] entry : tokens) {
                int end = index + entry[0].length();
                if (end <= raw.length() && raw.regionMatches(true, index, entry[0], 0, entry[0].length())) {
                    matched = entry; break;
                }
            }
            if (matched == null && getConfig().getBoolean("mentions.enabled", true)
                && player.hasPermission(getConfig().getString("mentions.permission", "bastionchat.mention")) && raw.charAt(index) == '@') {
                Matcher mention = MENTION.matcher(raw);
                mention.region(index, raw.length());
                if (mention.lookingAt()) {
                    Player target = Bukkit.getPlayerExact(mention.group(1));
                    if (target == null) for (Player online : Bukkit.getOnlinePlayers()) {
                        if (online.getName().equalsIgnoreCase(mention.group(1))) { target = online; break; }
                    }
                    if (target != null) {
                        if (!getConfig().getBoolean("mentions.allow-multiple", true) && !mentioned.isEmpty()) target = null;
                    }
                    if (target != null) {
                        if (!buffer.isEmpty()) {
                            message = message.append(Component.text(buffer.toString(), chosen));
                            bubbleParts.add(buffer.toString()); buffer.setLength(0);
                        }
                        String actual = target.getName();
                        Component mentionComponent = colorize(getConfig().getString("mentions.format", "&d@{player}").replace("{player}", actual));
                        message = message.append(mentionComponent);
                        if (getConfig().getBoolean("mentions.bubbles.highlight", true))
                            bubbleParts.add("\u0001" + actual + "\u0002");
                        else bubbleParts.add("@" + actual);
                        if (target.getUniqueId().equals(player.getUniqueId()) && !getConfig().getBoolean("mentions.notify-self", false)) {
                            // Highlight but no self-notification.
                        } else mentioned.add(target.getUniqueId());
                        index += mention.group().length();
                        continue;
                    }
                }
            }
            if (matched != null) {
                if (!buffer.isEmpty()) {
                    message = message.append(Component.text(buffer.toString(), chosen));
                    bubbleParts.add(buffer.toString()); buffer.setLength(0);
                }
                String key = matched[1];
                if ("showcase.ping".equals(key)) {
                    String ping = String.valueOf(player.getPing());
                    String format = getConfig().getString("showcase.ping.format", "&a{ping}ms").replace("{ping}", ping);
                    message = message.append(colorize(format)); bubbleParts.add(ping + "ms");
                } else {
                    boolean item = "showcase.item".equals(key);
                    String name = item ? itemName(player.getInventory().getItemInMainHand()) : "Ver inventario";
                    if (item && player.getInventory().getItemInMainHand().getType().isAir()) {
                        String empty = getConfig().getString("showcase.item.empty-format", "&7[Mano vacía]");
                        message = message.append(colorize(empty)); bubbleParts.add("[Mano vacía]");
                    } else {
                        ViewType type = item ? ViewType.ITEM : ViewType.INVENTORY;
                        UUID snapshotId = UUID.randomUUID();
                        snapshots.put(snapshotId, new Snapshot(snapshotId, type, player,
                            Math.max(1, getConfig().getLong("showcase.expiration-seconds", 300)) * 1000));
                        String format = getConfig().getString(key + ".link-format", item ? "&d[{item}]" : "&a[Ver inventario]")
                            .replace("{item}", name).replace("%item_name%", name);
                        Component clickable = colorize(format).clickEvent(ClickEvent.runCommand("/bastionchat view " + snapshotId));
                        clickable = clickable.hoverEvent(HoverEvent.showText(colorize("&7Clic para inspeccionar")));
                        message = message.append(clickable);
                        bubbleParts.add(item ? "[" + name + "]" : "[Ver inventario]");
                    }
                }
                index += matched[0].length();
            } else {
                buffer.append(raw.charAt(index++));
            }
        }
        if (!buffer.isEmpty()) {
            message = message.append(Component.text(buffer.toString(), chosen));
            bubbleParts.add(buffer.toString());
        }
        String bubbleRaw = String.join("", bubbleParts);
        String wrapped = wrapText(bubbleRaw, Math.max(8, getConfig().getInt("chat-bubbles.text.max-characters-per-line", 30)),
            Math.max(1, getConfig().getInt("chat-bubbles.text.max-lines", 3)));
        final net.kyori.adventure.text.format.TextColor bubbleColor = getConfig().getBoolean("chatcolor.apply-to-bubbles", true)
            ? chosen : solidTextColor(getConfig().getString("chat.default-message-color", "&f"));
        bubble = renderBubbleMessage(wrapped, bubbleColor);
        String bubbleFormat = getConfig().getString("chat-bubbles.text.format", "{message}");
        int at = bubbleFormat.indexOf("{message}");
        if (at >= 0) {
            bubble = colorize(bubbleFormat.substring(0, at)).append(bubble)
                .append(colorize(bubbleFormat.substring(at + "{message}".length())));
        }
        return new ProcessedMessage(message, bubble, mentioned);
    }
    private Component renderBubbleMessage(String text, net.kyori.adventure.text.format.TextColor base) {
        Component result = Component.empty();
        int pos = 0;
        while (pos < text.length()) {
            int mark = text.indexOf('\u0001', pos);
            if (mark < 0) { result = result.append(Component.text(text.substring(pos), base)); break; }
            result = result.append(Component.text(text.substring(pos, mark), base));
            int end = text.indexOf('\u0002', mark + 1);
            if (end < 0) { result = result.append(Component.text(text.substring(mark + 1), base)); break; }
            result = result.append(colorize(getConfig().getString("mentions.format", "&d@{player}")
                .replace("{player}", text.substring(mark + 1, end))));
            pos = end + 1;
        }
        return result;
    }
    private String wrapText(String text, int maxChars, int lines) {
        String clean = text.replace('\r', ' ').replace('\n', ' ');
        List<String> output = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : clean.split(" +")) {
            if (word.isEmpty()) continue;
            if (!current.isEmpty() && plainLength(current.toString()) + plainLength(word) + 1 > maxChars) {
                output.add(current.toString()); current.setLength(0);
                if (output.size() >= lines) break;
            }
            if (!current.isEmpty()) current.append(' ');
            current.append(word);
        }
        if (output.size() < lines && !current.isEmpty()) output.add(current.toString());
        return String.join("\n", output);
    }
    private int plainLength(String s) { return s.replace("\u0001", "").replace("\u0002", "").length(); }

    private void sendMentionSounds(Player sender, Set<UUID> mentions, long now) {
        if (!getConfig().getBoolean("mentions.sound.enabled", true) || mentions.isEmpty()) return;
        long interval = Math.max(0, getConfig().getLong("mentions.cooldown-seconds", 3) * 1000);
        if (now - lastMention.getOrDefault(sender.getUniqueId(), 0L) < interval) return;
        Sound sound;
        try { sound = Sound.valueOf(getConfig().getString("mentions.sound.type", "BLOCK_NOTE_BLOCK_PLING")); }
        catch (IllegalArgumentException ex) { sound = Sound.BLOCK_NOTE_BLOCK_PLING; }
        for (UUID targetId : mentions) {
            Player target = Bukkit.getPlayer(targetId);
            if (target != null && target.isOnline()) target.playSound(target.getLocation(), sound,
                (float)getConfig().getDouble("mentions.sound.volume", 1.0),
                (float)getConfig().getDouble("mentions.sound.pitch", 1.5));
        }
        lastMention.put(sender.getUniqueId(), now);
    }

    private void createBubble(Player player, Component component) {
        if (getConfig().getBoolean("chat-bubbles.replace-previous", true)) removeBubble(player.getUniqueId());
        double height = getConfig().getDouble("chat-bubbles.position.height", 2.8);
        Location loc = player.getLocation().add(0, height, 0);
        TextDisplay display = player.getWorld().spawn(loc, TextDisplay.class, d -> {
            d.text(component);
            d.setBillboard(Display.Billboard.CENTER);
            d.setShadowed(true);
            d.setPersistent(false);
            d.setDefaultBackground(false);
            // Los TextDisplay deben estar visibles por defecto para que los clientes los rendericen.
            // La visibilidad por distancia y la opcion de autor se controlan en updateViewers().
            d.setVisibleByDefault(true);
            if (!getConfig().getBoolean("chat-bubbles.background.enabled", true)) {
                d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            } else {
                int opacity = Math.max(0, Math.min(100, getConfig().getInt("chat-bubbles.background.opacity", 65)));
                Color rgb = parseColor(getConfig().getString("chat-bubbles.background.color", "#000000"));
                d.setBackgroundColor(Color.fromARGB(opacity * 255 / 100, rgb.getRed(), rgb.getGreen(), rgb.getBlue()));
            }
        });
        Bubble bubble = new Bubble(display, player.getUniqueId(), System.currentTimeMillis()
            + Math.max(1, getConfig().getLong("chat-bubbles.duration-seconds", 5)) * 1000);
        bubbles.put(player.getUniqueId(), bubble);
        updateViewers(bubble, player);
    }
    private Color parseColor(String input) {
        try { return Color.fromRGB(Integer.parseInt(input.replace("#", "").replace("&", ""), 16) & 0xFFFFFF); }
        catch (NumberFormatException ex) { return Color.BLACK; }
    }
    private void tickBubbles() {
        long now = System.currentTimeMillis();
        for (Bubble bubble : new ArrayList<>(bubbles.values())) {
            Player speaker = Bukkit.getPlayer(bubble.speaker);
            if (speaker == null || !speaker.isOnline() || !bubble.display.isValid() || now >= bubble.until
                || speaker.getWorld() != bubble.display.getWorld()) {
                removeBubble(bubble.speaker); continue;
            }
            if (getConfig().getBoolean("chat-bubbles.follow-player", true)) {
                bubble.display.teleport(speaker.getLocation().add(0,
                    getConfig().getDouble("chat-bubbles.position.height", 2.8), 0));
            }
            updateViewers(bubble, speaker);
        }
    }
    private void updateViewers(Bubble bubble, Player speaker) {
        double radius = Math.max(1, getConfig().getDouble("chat-bubbles.view-distance", 20));
        double maxDistance = radius * radius;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            boolean canSee = viewer.getWorld() == speaker.getWorld()
                && viewer.getLocation().distanceSquared(speaker.getLocation()) <= maxDistance
                && (viewer != speaker || getConfig().getBoolean("chat-bubbles.show-to-sender", true));
            if (canSee) viewer.showEntity(this, bubble.display);
            else viewer.hideEntity(this, bubble.display);
        }
    }
    private void removeBubble(UUID speaker) {
        Bubble bubble = bubbles.remove(speaker);
        if (bubble != null && bubble.display.isValid()) bubble.display.remove();
    }
    private void clearBubbles() {
        for (UUID uuid : new ArrayList<>(bubbles.keySet())) removeBubble(uuid);
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        removeBubble(id); lastMessage.remove(id); lastShowcase.remove(id);
        previousText.remove(id); previousTextTime.remove(id); lastMention.remove(id);
    }
    @EventHandler public void onDeath(PlayerDeathEvent event) { removeBubble(event.getEntity().getUniqueId()); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("chatcolor")) {
            if (!(sender instanceof Player player)) { notice(sender, "players-only"); return true; }
            if (!getConfig().getBoolean("chatcolor.enabled", true)) { notice(player, "chatcolor-disabled"); return true; }
            if (!player.hasPermission(getConfig().getString("chatcolor.permission", "bastionchat.chatcolor"))) {
                notice(player, "no-permission"); return true;
            }
            if (args.length == 0) openColors(player);
            else if (args.length == 1 && args[0].equalsIgnoreCase("reset")) {
                selectedColors.remove(player.getUniqueId()); savePlayerData();
                notice(player, "chatcolor-reset");
            } else notice(player, "chatcolor-usage");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("view")) {
            if (!(sender instanceof Player player) || args.length != 2) return true;
            try {
                UUID id = UUID.fromString(args[1]);
                Snapshot snapshot = snapshots.get(id);
                if (snapshot != null && snapshot.expiresAt > System.currentTimeMillis()) openSnapshot(player, snapshot);
            } catch (IllegalArgumentException ignored) {} // Expired/bad IDs are intentionally silent.
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("bastionchat.admin.reload")) { notice(sender, "no-permission"); return true; }
            try {
                reloadAll(); startTasks(); clearBubbles();
                notice(sender, "reload-success");
            } catch (Exception ex) {
                getLogger().severe("Error de reload: " + ex.getMessage()); notice(sender, "reload-failure");
            }
            return true;
        }
        notice(sender, "unknown-command"); return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        if (command.getName().equalsIgnoreCase("chatcolor")) {
            return sender.hasPermission("bastionchat.chatcolor") && "reset".startsWith(args[0].toLowerCase(Locale.ROOT))
                ? List.of("reset") : List.of();
        }
        if (sender.hasPermission("bastionchat.admin.reload") && "reload".startsWith(args[0].toLowerCase(Locale.ROOT)))
            return List.of("reload");
        return List.of();
    }
    private void openColors(Player player) {
        int size = Math.max(1, Math.min(6, colors.getInt("menu.rows", 6))) * 9;
        MenuHolder holder = new MenuHolder(ViewType.COLORS, null);
        Inventory inv = Bukkit.createInventory(holder, size, colorize(colors.getString("menu.title", "&bColores del Chat")));
        holder.inventory = inv;
        fillMenu(inv, "menu.fill", colors);
        ConfigurationSection section = colors.getConfigurationSection("colors");
        if (section != null) for (String key : section.getKeys(false)) {
            String base = "colors." + key + ".";
            int slot = colors.getInt(base + "slot", -1);
            if (slot < 0 || slot >= size) continue;
            String color = colors.getString(base + "color", "&f");
            // Colors are solid; anything other than a single & code or a 6-digit HEX code is rejected.
            if (!validSolidColor(color)) continue;
            Material material = material(colors.getString(base + "material", "WHITE_WOOL"), Material.WHITE_WOOL);
            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(colorize(colors.getString(base + "name", color + key)));
            List<Component> lore = new ArrayList<>();
            lore.add(colorize(messages.getString("menu-item-description", "&7Clic para elegir.")));
            if (color.equalsIgnoreCase(selectedColors.get(player.getUniqueId()))) {
                lore.add(colorize(messages.getString("menu-current-color", "&aSeleccionado.")));
                meta.setEnchantmentGlintOverride(true);
            }
            meta.lore(lore); item.setItemMeta(meta);
            inv.setItem(slot, item); holder.colorSlots.put(slot, color);
        }
        int resetSlot = colors.getInt("menu.reset.slot", 49);
        if (resetSlot >= 0 && resetSlot < size) {
            ItemStack reset = new ItemStack(material(colors.getString("menu.reset.material", "BARRIER"), Material.BARRIER));
            ItemMeta meta = reset.getItemMeta();
            meta.displayName(colorize(colors.getString("menu.reset.name", "&cRestablecer")));
            meta.lore(colors.getStringList("menu.reset.lore").stream().map(this::colorize).toList());
            reset.setItemMeta(meta); inv.setItem(resetSlot, reset);
        }
        player.openInventory(inv);
    }
    private void openSnapshot(Player player, Snapshot snap) {
        if (snap.type == ViewType.ITEM) {
            String title = getConfig().getString("showcase.item.menu-title", "&dObjeto de {player}").replace("{player}", snap.owner);
            int rows = Math.max(1, Math.min(6, getConfig().getInt("showcase.item.menu-rows", 3)));
            MenuHolder holder = new MenuHolder(ViewType.ITEM, snap.id);
            Inventory inv = Bukkit.createInventory(holder, rows * 9, colorize(title));
            holder.inventory = inv;
            fillMenu(inv, "showcase.item.fill-material");
            int slot = getConfig().getInt("showcase.item.menu-slot", 13);
            if (slot >= 0 && slot < inv.getSize()) inv.setItem(slot, cloneItem(snap.item));
            player.openInventory(inv);
            return;
        }
        String title = getConfig().getString("showcase.inventory.menu-title", "&aInventario de {player}").replace("{player}", snap.owner);
        MenuHolder holder = new MenuHolder(ViewType.INVENTORY, snap.id);
        Inventory inv = Bukkit.createInventory(holder, 54, colorize(title));
        holder.inventory = inv;
        fillMenu(inv, "showcase.inventory.fill-material");
        // Slots 0..26: backpack (player storage indexes 9..35); 27..35: hotbar.
        for (int i = 9; i < 36 && i < snap.storage.length; i++) inv.setItem(i - 9, cloneItem(snap.storage[i]));
        for (int i = 0; i < 9 && i < snap.storage.length; i++) inv.setItem(27 + i, cloneItem(snap.storage[i]));
        if (getConfig().getBoolean("showcase.inventory.include-armor", true)) {
            // Armor storage: boots, leggings, chestplate, helmet.
            for (int i = 0; i < Math.min(4, snap.armor.length); i++) inv.setItem(45 + i, cloneItem(snap.armor[i]));
        }
        if (getConfig().getBoolean("showcase.inventory.include-offhand", true)) inv.setItem(50, cloneItem(snap.offhand));
        player.openInventory(inv);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || holder.type != ViewType.COLORS) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (event.getRawSlot() == colors.getInt("menu.reset.slot", 49)) {
            selectedColors.remove(player.getUniqueId()); savePlayerData(); player.closeInventory();
            notice(player, "chatcolor-reset"); return;
        }
        String color = holder.colorSlots.get(event.getRawSlot());
        if (color == null) return;
        selectedColors.put(player.getUniqueId(), color); savePlayerData(); player.closeInventory();
        String formatted = messages.getString("chatcolor-selected", "&aColor seleccionado: {color}")
            .replace("{color}", color + "COLOR&r");
        player.sendMessage(colorize(messages.getString("prefix", "") + formatted));
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) event.setCancelled(true);
    }
    private void fillMenu(Inventory inv, String configKey) {
        Material fill = material(getConfig().getString(configKey, "BLACK_STAINED_GLASS_PANE"), Material.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack glass = new ItemStack(fill);
            ItemMeta meta = glass.getItemMeta(); meta.displayName(Component.empty()); glass.setItemMeta(meta);
            inv.setItem(i, glass);
        }
    }
    private void fillMenu(Inventory inv, String path, FileConfiguration file) {
        if (!file.getBoolean(path + ".enabled", true)) return;
        Material fill = material(file.getString(path + ".material", "BLACK_STAINED_GLASS_PANE"), Material.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack pane = new ItemStack(fill);
            ItemMeta meta = pane.getItemMeta(); meta.displayName(Component.empty()); pane.setItemMeta(meta);
            inv.setItem(i, pane);
        }
    }
    private Material material(String name, Material fallback) {
        Material mat = Material.matchMaterial(name == null ? "" : name);
        return mat == null || mat.isAir() ? fallback : mat;
    }
    private void notice(CommandSender sender, String key) {
        sender.sendMessage(colorize(messages.getString("prefix", "") + messages.getString(key, "")));
    }
    private void savePlayerData() {
        if (playerData == null) return;
        playerData.set("players", null);
        for (var e : selectedColors.entrySet()) playerData.set("players." + e.getKey(), e.getValue());
        try { playerData.save(playerDataFile); }
        catch (Exception e) { getLogger().severe("No se pudieron guardar los colores: " + e.getMessage()); }
    }
    private net.kyori.adventure.text.format.TextColor solidTextColor(String input) {
        if (input == null) return net.kyori.adventure.text.format.NamedTextColor.WHITE;
        if (input.matches("(?i)&#[0-9a-f]{6}")) {
            net.kyori.adventure.text.format.TextColor hex = net.kyori.adventure.text.format.TextColor.fromHexString("#" + input.substring(2));
            return hex == null ? net.kyori.adventure.text.format.NamedTextColor.WHITE : hex;
        }
        if (input.matches("(?i)&[0-9a-f]")) {
            String codes = "0123456789abcdef";
            int[] rgb = {0x000000,0x0000AA,0x00AA00,0x00AAAA,0xAA0000,0xAA00AA,0xFFAA00,0xAAAAAA,
                0x555555,0x5555FF,0x55FF55,0x55FFFF,0xFF5555,0xFF55FF,0xFFFF55,0xFFFFFF};
            int idx = codes.indexOf(Character.toLowerCase(input.charAt(1)));
            return net.kyori.adventure.text.format.TextColor.color(rgb[idx]);
        }
        return net.kyori.adventure.text.format.NamedTextColor.WHITE;
    }
    private static boolean validSolidColor(String s) {
        return s != null && (s.matches("(?i)&[0-9a-f]") || s.matches("(?i)&#[a-f0-9]{6}"));
    }
    private Component colorize(String value) {
        if (value == null) return Component.empty();
        // PAPI/LP can return section-sign legacy codes.
        return LEGACY.deserialize(value.replace('§', '&'));
    }
    private static ItemStack cloneItem(ItemStack item) { return item == null ? null : item.clone(); }
    private static ItemStack[] cloneItems(ItemStack[] array) {
        ItemStack[] result = new ItemStack[array.length];
        for (int i = 0; i < array.length; i++) result[i] = cloneItem(array[i]);
        return result;
    }
    private String itemName(ItemStack item) {
        if (item == null || item.getType().isAir()) return "Mano vacía";
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta.hasDisplayName() && meta.displayName() != null) {
                String text = PLAIN.serialize(meta.displayName());
                if (!text.isBlank()) return text;
            }
        }
        String[] words = item.getType().name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }
}
