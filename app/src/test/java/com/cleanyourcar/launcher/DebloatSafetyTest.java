package com.cleanyourcar.launcher;

import com.cleanyourcar.launcher.compat.DeviceProfile;
import com.cleanyourcar.launcher.compat.ProfileCodec;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Debloat protection has one source of truth: the shared profile contract (checked here through
 * the app's own ProfileCodec) and the tooling policy behind the wrappers. This test keeps no
 * second protected-package list; it checks the shipped profile/list and wrapper refusals.
 */
public class DebloatSafetyTest {
    private static final String PROFILE = "profiles/devices/xyauto-yt3518c/profile.json";
    private static final String LIST = "examples/yt3518c-mt3561s/debloat.txt";

    private static File repoFile(String relative) {
        File file = new File("../" + relative);
        if (!file.exists()) {
            file = new File(relative);
        }
        assertTrue(relative + " must exist", file.exists());
        return file;
    }

    private static List<String> activePackages(File list) throws Exception {
        List<String> packages = new ArrayList<>();
        for (String raw : Files.readAllLines(list.toPath(), StandardCharsets.UTF_8)) {
            String line = raw.replaceAll("#.*", "").trim();
            if (!line.isEmpty()) packages.add(line);
        }
        return packages;
    }

    private static String run(int[] exitCode, String... command) throws Exception {
        Process proc = new ProcessBuilder(command).redirectErrorStream(true).start();
        proc.getOutputStream().close();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream in = proc.getInputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) output.write(buffer, 0, read);
        }
        exitCode[0] = proc.waitFor();
        return output.toString("UTF-8");
    }

    private static String wrapper(int[] exitCode, String script, String... args) throws Exception {
        List<String> command = new ArrayList<>(Arrays.asList("bash",
                repoFile("tools/" + script).getAbsolutePath()));
        command.addAll(Arrays.asList(args));
        return run(exitCode, command.toArray(new String[0]));
    }

    private static DeviceProfile sharedProfile() throws Exception {
        String json = new String(Files.readAllBytes(repoFile(PROFILE).toPath()), StandardCharsets.UTF_8);
        return ProfileCodec.parse(json, Integer.MAX_VALUE);
    }

    @Test
    public void yt3518cDebloatList_neverContainsPackagesProtectedByTheSharedProfile() throws Exception {
        DeviceProfile profile = sharedProfile();
        assertFalse("shared profile must declare protections", profile.protectedPackages.isEmpty());
        List<String> candidates = activePackages(repoFile(LIST));
        assertTrue("debloat.txt should contain at least 15 reported candidates", candidates.size() >= 15);
        Set<String> protectedNames = new HashSet<>(profile.protectedPackages);
        for (String candidate : candidates) {
            assertFalse("Protected package must NEVER be a candidate: " + candidate,
                    protectedNames.contains(candidate));
        }
    }

    @Test
    public void disableWrapperDryRun_isStaticValidationOnly() throws Exception {
        int[] code = new int[1];
        String output = wrapper(code, "disable.sh", repoFile(LIST).getAbsolutePath(), "--dry-run");
        assertEquals(output, 0, code[0]);
        assertTrue(output, output.contains("NOT a device safety or qualification result"));
    }

    @Test
    public void disableWrapper_refusesEverySharedProfileProtectionEvenWithInlineComment() throws Exception {
        File bad = File.createTempFile("bad-debloat-", ".txt");
        try {
            for (String protectedName : sharedProfile().protectedPackages) {
                Files.write(bad.toPath(), Arrays.asList(
                        "# test comment",
                        "com.pve.otacheck",
                        protectedName + " # inline comment attempt"), StandardCharsets.UTF_8);
                int[] code = new int[1];
                String output = wrapper(code, "disable.sh", bad.getAbsolutePath(), "--dry-run");
                assertNotEquals("must refuse " + protectedName + ": " + output, 0, code[0]);
            }
        } finally {
            bad.delete();
        }
    }

    @Test
    public void wrappers_refuseLegacyListUsageAndAutoConfirmation() throws Exception {
        File list = repoFile(LIST);
        int[] code = new int[1];
        wrapper(code, "disable.sh", list.getAbsolutePath());
        assertNotEquals("device-less legacy disable usage must be refused", 0, code[0]);
        wrapper(code, "restore.sh", list.getAbsolutePath(), "--serial", "X", "--user", "0");
        assertNotEquals("restoring from a list must be refused", 0, code[0]);
        wrapper(code, "disable.sh", list.getAbsolutePath(), "--serial", "X", "--user", "0", "--confirm", "x");
        assertNotEquals("wrappers must never confirm", 0, code[0]);
    }
}
