package cn.bucheng.stardewfishing;

import java.util.*;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

final class Text {
    private Text() { }
    static String color(String text) { return ChatColor.translateAlternateColorCodes('&', text); }
    static void send(CommandSender to, String text) { to.sendMessage(color("&3[星露渔歌] &r" + text)); }
    static String money(double value) { return String.format(Locale.ROOT, "%.2f", value); }
    static String length(int value) { return String.format(Locale.ROOT, "%.1f", value / 10.0); }
    static boolean air(Material material) { return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR; }
    static ItemStack icon(Material material, String title, String... lore) {
        return icon(material, title, Arrays.asList(lore));
    }
    static ItemStack icon(Material material, String title, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(title)); meta.setLore(lore.stream().map(Text::color).toList());
        item.setItemMeta(meta); return item;
    }
}
