package com.dsmnh.mobile.modserver;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/** ME 设备登记落盘：重启后从清单恢复，注销同步写回。 */
public class MeAdapterPersistenceTest {

    @Test
    public void devicesSurviveReloadAndUnregisterIsPersisted() throws Exception {
        File dir = Files.createTempDirectory("me-devices").toFile();
        File store = new File(dir, "dsmnhmobile-me-devices.json");
        try {
            MeAdapter.setStore(store);
            MeAdapter.register("dev-a", 0, 10, 64, -3, "owner-1", "monitor");
            MeAdapter.register("dev-b", 7, 1, 2, 3, "owner-2", "monitor");
            assertTrue(store.isFile(), "登记后应写出清单文件");

            MeAdapter.unregister("dev-a");
            MeAdapter.load();

            assertNull(MeAdapter.find("dev-a"), "注销的设备不应被恢复");
            MeAdapter.Device restored = MeAdapter.find("dev-b");
            assertNotNull(restored, "未注销的设备应被恢复");
            assertEquals(7, restored.dim);
            assertEquals(2, restored.y);
            assertEquals("owner-2", restored.owner);
        } finally {
            MeAdapter.unregister("dev-b");
            MeAdapter.setStore(null);
        }
    }
}
