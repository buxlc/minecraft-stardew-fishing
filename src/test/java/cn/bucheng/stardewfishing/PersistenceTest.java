package cn.bucheng.stardewfishing;

import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PersistenceTest {
    @TempDir Path temporary;
    @Test void discoveriesLongestAndToggleSurviveReloadAndDoNotRegress() throws Exception {
        Settings s = SettingsFixture.defaults(); UUID id = UUID.randomUUID(); PlayerStore store = new PlayerStore(temporary);
        assertTrue(store.caught(id, true, s.fish.get("carp"), 300, 10000));
        assertFalse(store.caught(id, true, s.fish.get("carp"), 220, 20000));
        store.toggle(id, true);
        var profile = new PlayerStore(temporary).get(id, true);
        assertFalse(profile.enabled());
        assertEquals(2, profile.records().get("carp").count()); assertEquals(300, profile.records().get("carp").longestTenths());
        assertEquals(10000, profile.records().get("carp").firstCaught());
    }
    @Test void individualKingCooldownIsDurableAndSpeciesSpecific() throws Exception {
        Settings s = SettingsFixture.defaults(); UUID id = UUID.randomUUID(); PlayerStore store = new PlayerStore(temporary);
        var king = s.fish.get("river_king"); store.caught(id, true, king, 1200, 100000000);
        PlayerStore reload = new PlayerStore(temporary);
        assertFalse(reload.kingReady(id, true, king, 100000001, 24));
        assertTrue(reload.kingReady(id, true, king, 100000000 + 86400000, 24));
        assertTrue(reload.kingReady(id, true, s.fish.get("ocean_king"), 100000001, 24));
    }
    @Test void corruptedProfileIsPreservedAndNeverSilentlyReset() throws Exception {
        UUID id = UUID.randomUUID(); Path path = temporary.resolve(id + ".yml"); String corrupt = "enabled: broken\nfish: []\n";
        Files.writeString(path, corrupt); PlayerStore store = new PlayerStore(temporary);
        assertThrows(IllegalStateException.class, () -> store.toggle(id, true));
        assertEquals(corrupt, Files.readString(path));
    }
    @Test void failedAtomicWriteDoesNotUpdateCachedRecord() throws Exception {
        Settings s = SettingsFixture.defaults(); UUID id = UUID.randomUUID(); Path blocked = temporary.resolve("not_a_directory");
        Files.writeString(blocked, "occupied"); PlayerStore store = new PlayerStore(blocked);
        assertThrows(IllegalStateException.class, () -> store.caught(id, true, s.fish.get("carp"), 220, 10000));
        assertTrue(store.get(id, true).records().isEmpty());
    }
    @Test void signaturesRejectChangedLengthCookingAndSecretButSurviveRestart() throws Exception {
        Path path = temporary.resolve("key.bin"); ItemSigner signer = new ItemSigner(path);
        String value = "fish|carp|300|false", signature = signer.sign(value);
        assertTrue(new ItemSigner(path).verify(value, signature));
        assertFalse(signer.verify("fish|carp|420|false", signature));
        assertFalse(signer.verify("fish|carp|300|true", signature));
        assertFalse(new ItemSigner(temporary.resolve("other.bin")).verify(value, signature));
        assertFalse(signer.verify(value, null));
    }
}
