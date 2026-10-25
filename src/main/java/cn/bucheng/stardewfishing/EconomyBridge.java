package cn.bucheng.stardewfishing;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/** Vault remains optional; fishing, crafting and the collection work without an economy provider. */
public final class EconomyBridge {
    interface Provider { boolean enabled(); int digits(); boolean deposit(Player p, double amount); boolean withdraw(Player p, double amount); }
    private final java.util.function.Supplier<Provider> provider;
    public EconomyBridge(JavaPlugin plugin) { this(() -> find(plugin)); }
    EconomyBridge(java.util.function.Supplier<Provider> provider) { this.provider = provider; }
    private Provider current() {
        try { Provider p = provider.get(); return p != null && p.enabled() ? p : null; }
        catch (LinkageError | RuntimeException e) { return null; }
    }
    public boolean available() { return current() != null; }
    public double round(double amount) {
        Provider p = current();
        int digits = p == null ? 2 : p.digits();
        int scale = digits < 0 ? 2 : Math.min(2, digits);
        return java.math.BigDecimal.valueOf(amount).setScale(scale, java.math.RoundingMode.HALF_UP).doubleValue();
    }
    public boolean deposit(Player player, double amount) {
        Provider p = current();
        return p != null && Double.isFinite(amount) && amount > 0 && p.deposit(player, amount);
    }
    public boolean withdraw(Player player, double amount) {
        Provider p = current();
        return p != null && Double.isFinite(amount) && amount >= 0 && (amount == 0 || p.withdraw(player, amount));
    }
    private static Provider find(JavaPlugin plugin) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("Vault")) return null;
        return VaultProvider.find();
    }
    private static final class VaultProvider implements Provider {
        private final net.milkbowl.vault.economy.Economy economy;
        private VaultProvider(net.milkbowl.vault.economy.Economy e) { economy = e; }
        static Provider find() {
            RegisteredServiceProvider<net.milkbowl.vault.economy.Economy> registration = Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
            return registration == null ? null : new VaultProvider(registration.getProvider());
        }
        public boolean enabled() { return economy.isEnabled(); }
        public int digits() { return economy.fractionalDigits(); }
        public boolean deposit(Player p, double a) { return economy.depositPlayer(p, a).transactionSuccess(); }
        public boolean withdraw(Player p, double a) { return economy.withdrawPlayer(p, a).transactionSuccess(); }
    }
}
