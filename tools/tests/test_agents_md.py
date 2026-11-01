"""Offline guards for the agent entry point and its actual repository contracts."""
import argparse
import ast
import re
import shlex
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import car  # noqa: E402


def read(relative):
    return (ROOT / relative).read_text(encoding="utf-8")


def shell_commands(text):
    for block in re.findall(r"```(?:sh|bash|shell)\n(.*?)```", text, re.S):
        for line in block.replace("\\\n", " ").splitlines():
            words = shlex.split(line, comments=True)
            if words:
                yield words


def gradle_modules():
    settings = read("settings.gradle.kts")
    modules = {name: ROOT / name.strip(":").replace(":", "/")
               for args in re.findall(r"\binclude\(([^)]*)\)", settings)
               for name in re.findall(r'"(:[^"]+)"', args)}
    for name, directory in re.findall(
            r'project\("([^"]+)"\)\.projectDir\s*=\s*file\("([^"]+)"\)', settings):
        modules[name] = ROOT / directory
    return modules


def valid_gradle_task(task, modules):
    module, separator, name = task.rpartition(":")
    if not separator or module not in modules:
        return False
    build = (modules[module] / "build.gradle.kts").read_text(encoding="utf-8")
    # These task families are provided by the Android application plugin, not custom tasks.
    alias = re.search(r"alias\(libs\.plugins\.([\w.]+)\)", build)
    if not alias:
        return False
    catalog_key = alias.group(1).replace(".", "-")
    catalog = read("gradle/libs.versions.toml")
    if not re.search(r'^' + re.escape(catalog_key)
                     + r'\s*=\s*\{[^}\n]*id\s*=\s*"com\.android\.application"', catalog, re.M):
        return False
    variants = {"Debug", "Release"}
    start = re.search(r"\bbuildTypes\s*\{", build)
    if start:
        depth, end = 1, start.end()
        while depth and end < len(build):
            depth += (build[end] == "{") - (build[end] == "}")
            end += 1
        block = build[start.end():end - 1]
        variants.update(v[0].upper() + v[1:] for v in
                        re.findall(r'(?:create|getByName)\("(\w+)"\)', block))
    tasks = {task for variant in variants for task in {
        "assemble" + variant, "lint" + variant, "test" + variant + "UnitTest",
    }}
    instrumentation = re.search(r'\btestBuildType\s*=\s*"(\w+)"', build)
    test_variant = instrumentation.group(1) if instrumentation else "debug"
    test_variant = test_variant[0].upper() + test_variant[1:]
    if test_variant in variants:
        tasks.update({"assemble" + test_variant + "AndroidTest",
                      "connected" + test_variant + "AndroidTest"})
    return name in tasks


def apk_gates():
    """Find the tracked production owner, including a future car_support Python owner."""
    paths = subprocess.check_output(
        ["git", "ls-files", "-z", "--", "tools/*.sh", "tools/car_support/*.py"],
        cwd=ROOT).decode("utf-8").split("\0")
    found = []
    for relative in filter(None, paths):
        source = read(relative)
        if relative.endswith(".sh"):
            for name, value in re.findall(r"^(MAX_\w*BYTES)\s*=\s*(\d+)\s*$", source, re.M):
                if re.search(r'-ge\s*"\$' + re.escape(name) + r'"', source):
                    found.append((relative, int(value)))
        else:
            tree = ast.parse(source)
            constants = {node.targets[0].id: node.value.value
                         for node in ast.walk(tree) if isinstance(node, ast.Assign)
                         and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name)
                         and isinstance(node.value, ast.Constant)
                         and type(node.value.value) is int
                         and re.fullmatch(r"MAX_\w*BYTES", node.targets[0].id)}
            for node in ast.walk(tree):
                if isinstance(node, ast.Compare):
                    for op, right in zip(node.ops, node.comparators):
                        if (isinstance(op, ast.GtE) and isinstance(right, ast.Name)
                                and right.id in constants):
                            found.append((relative, constants[right.id]))
    return found


class AgentsMarkdownTest(unittest.TestCase):
    def agents(self):
        self.assertTrue((ROOT / "AGENTS.md").is_file(), "AGENTS.md is missing")
        return read("AGENTS.md")

    def test_size_budget(self):
        text = self.agents()
        self.assertLess(len(text.splitlines()), 200)
        self.assertLess(len(text.encode("utf-8")), 16384)

    def test_pointer_files_import_agents_md(self):
        for name in ("CLAUDE.md", "GEMINI.md"):
            with self.subTest(path=name):
                self.assertTrue((ROOT / name).is_file(), name + " is missing")
                lines = read(name).splitlines()
                self.assertEqual(len(lines), 2)
                self.assertEqual(lines[-1], "@AGENTS.md")
        copilot = read(".github/copilot-instructions.md")
        self.assertRegex(copilot, r"\[[^]]+\]\(\.\./AGENTS\.md\)")
        self.assertEqual(re.findall(r"^## (.+)$", copilot, re.M),
                         ["Communication & Language Policy"])

    def test_people_docs_link_agents_md(self):
        for name, prefix in (("README.md", ""), ("docs/contributing-device-profile.md", "../")):
            with self.subTest(path=name):
                self.assertRegex(read(name), r"\[[^]]+\]\("
                                 + re.escape(prefix + "AGENTS.md") + r"\)")

    def test_job_routing_comes_first(self):
        text = self.agents()
        self.assertIn("## Which job are you doing?", text)
        self.assertIn("## Repo map", text)
        self.assertLess(text.index("## Which job are you doing?"), text.index("## Repo map"))

    def test_every_named_path_exists(self):
        for token in re.findall(r"`([^`\n]+)`", self.agents()):
            if re.search(r"[<*\s]", token):
                continue
            if "/" in token or token.endswith(
                    (".md", ".json", ".py", ".sh", ".java", ".kts", ".yml")):
                with self.subTest(path=token):
                    self.assertTrue((ROOT / token).exists(), "missing path: " + token)
        self.assertNotIn(".github/skills", self.agents())
        self.assertNotIn(".hallmark", self.agents())

    def test_numbers_match_build(self):
        text = self.agents()
        floor = re.search(r"\bminSdk\s*=\s*(\d+)", read("app/build.gradle.kts"))
        stated = re.search(r"\bminSdk\s*=\s*(\d+)", text)
        self.assertIsNotNone(stated, "state minSdk explicitly")
        self.assertEqual(stated.group(1), floor.group(1))
        limit = re.search(r"core APK.*?\b(\d+)\s+bytes", text)
        self.assertIsNotNone(limit, "state the core APK gate in bytes")
        gates = apk_gates()
        self.assertEqual(len(gates), 1, "exactly one tracked APK gate owner: " + repr(gates))
        self.assertEqual(int(limit.group(1)), gates[0][1])
        self.assertRegex(text, r">=\s*" + limit.group(1) + r"\s+bytes.*reject")

    def test_commands_exist(self):
        modules = gradle_modules()
        parser = car._parser()  # Parser construction only: no CLI execution or device contact.
        subcommands = next(action.choices for action in parser._actions
                           if isinstance(action, argparse._SubParsersAction))
        commands = list(shell_commands(self.agents()))
        self.assertTrue(any(words[0] == "./gradlew" for words in commands))
        self.assertTrue(any(words[:2] == ["python3", "tools/car.py"] for words in commands))
        for words in commands:
            with self.subTest(command=words):
                if words[0] == "./gradlew":
                    tasks = [word for word in words[1:] if not word.startswith("-")]
                    self.assertTrue(tasks)
                    for task in tasks:
                        self.assertTrue(valid_gradle_task(task, modules), "unknown task: " + task)
                if words[:2] == ["python3", "tools/car.py"]:
                    self.assertIn(words[2], subcommands)

    def test_task_guard_rejects_unknown_tasks_and_paths(self):
        modules = gradle_modules()
        self.assertTrue(valid_gradle_task(":navbridge:assembleDebug", modules))
        for task in (":missing:assembleDebug", ":app:inventedTask",
                     ":addons:nav-bridge:assembleDebug", ":app:assembleMain",
                     ":app:assembleTest", ":app:assembleAndroidTest",
                     ":app:assembleReleaseAndroidTest", ":app:connectedReleaseAndroidTest"):
            self.assertFalse(valid_gradle_task(task, modules), task)


if __name__ == "__main__":
    unittest.main()
