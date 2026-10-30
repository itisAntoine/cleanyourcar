package com.cleanyourcar.launcher;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public class DebloatSafetyTest {
    private static final Set<String> PROTECTED_PACKAGES = new HashSet<>(Arrays.asList(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.packageinstaller",
            "com.android.defcontainer",
            "com.android.inputmethod.latin",
            "com.android.shell",
            "com.android.providers.settings",
            "com.android.providers.media",
            "com.android.externalstorage",
            "com.android.location.fused",
            "com.autochips.android.backcar",
            "com.autochips.bluetooth",
            "com.android.bluetooth",
            "com.xygala.canbus",
            "com.xygala.pvcanset",
            "com.xy.spl",
            "com.xy.brakecheck",
            "com.xyauto.Settings",
            "com.fvsm.camera",
            "com.acloud.stub.localmusic",
            "com.acloud.stub.localradio",
            "com.mediatek.usbchecker",
            "com.google.android.apps.maps",
            "com.google.android.gms"
    ));

    @Test
    public void yt3518cDebloatList_neverContainsProtectedCarOrSystemPackages() throws Exception {
        File debloatFile = new File("../examples/yt3518c-mt3561s/debloat.txt");
        if (!debloatFile.exists()) {
            debloatFile = new File("examples/yt3518c-mt3561s/debloat.txt");
        }
        assertTrue("debloat.txt must exist", debloatFile.exists());

        List<String> lines = Files.readAllLines(debloatFile.toPath());
        int activeCount = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            activeCount++;
            assertFalse("Protected package must NEVER be in debloat.txt: " + line,
                    PROTECTED_PACKAGES.contains(line));
        }
        assertTrue("debloat.txt should contain at least 15 bloatware packages", activeCount >= 15);
    }

    @Test
    public void disableScript_rejectsProtectedPackageEvenWithInlineComment() throws Exception {
        File script = new File("../tools/disable.sh");
        if (!script.exists()) {
            script = new File("tools/disable.sh");
        }
        assertTrue("tools/disable.sh must exist", script.exists());

        File tempBadList = File.createTempFile("bad-debloat-", ".txt");
        try {
            Files.write(tempBadList.toPath(), Arrays.asList(
                    "# test comment",
                    "com.pve.otacheck",
                    "com.autochips.android.backcar # inline comment attempt"
            ));
            Process proc = new ProcessBuilder("bash", script.getAbsolutePath(), tempBadList.getAbsolutePath(), "--dry-run")
                    .redirectErrorStream(true)
                    .start();
            int exitCode = proc.waitFor();
            assertNotEquals("disable.sh must exit non-zero when protected package is present", 0, exitCode);
        } finally {
            tempBadList.delete();
        }
    }
}
