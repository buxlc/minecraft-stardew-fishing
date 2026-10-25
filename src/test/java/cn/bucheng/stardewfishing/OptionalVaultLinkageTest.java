package cn.bucheng.stardewfishing;

import java.io.File;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OptionalVaultLinkageTest {
    @Test void pluginAndFishingClassesLoadWithoutVaultInstalled() throws Exception {
        List<URL> urls = new ArrayList<>();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        for (String path : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!path.toLowerCase(Locale.ROOT).contains("vaultapi")) urls.add(new File(path).toURI().toURL());
        }
        try (URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("net.milkbowl.vault.economy.Economy"));
            Class<?> bridge = Class.forName("cn.bucheng.stardewfishing.EconomyBridge", true, loader);
            assertNotNull(bridge.getDeclaredMethod("available"));
            Class<?> main = Class.forName("cn.bucheng.stardewfishing.StardewFishingPlugin", true, loader);
            assertNotNull(main.getDeclaredMethod("onEnable"));
        }
    }
}
