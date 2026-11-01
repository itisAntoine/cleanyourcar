"""Check tracked Markdown and new reviewable Markdown before its first commit."""
import re
import subprocess
import unittest
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[2]
REQUIRED = ("AGENTS.md", "CLAUDE.md", "GEMINI.md", "SECURITY.md",
            "docs/TROUBLESHOOTING.md", "docs/DOS-AND-DONTS.md")


def markdown_paths():
    paths = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z", "--", "*.md"],
        cwd=ROOT).decode("utf-8").split("\0")
    return sorted({path for path in paths if path
                   and not path.startswith((".github/skills/", ".hallmark/"))})


def destinations(text):
    # Code examples are not Markdown links. Include images and reference definitions.
    text = re.sub(r"^(`{3,}|~{3,}).*?^\1[ \t]*$", "", text, flags=re.M | re.S)
    text = re.sub(r"`+[^`\n]*`+", "", text)
    inline = r'!?\[[^\]\n]*\]\(\s*'
    reference = r'^\s{0,3}\[[^\]\n]+\]:\s*(<[^>\n]*>|\S+)'
    for match in re.finditer(inline, text):
        start = end = match.end()
        if end < len(text) and text[end] == "<":
            end = text.find(">", end + 1)
            if end < 0:
                continue
            end += 1
        else:
            depth = 0
            while end < len(text):
                char = text[end]
                if char == "\\":
                    end += 2
                    continue
                if (char == ")" or char.isspace()) and depth == 0:
                    break
                depth += (char == "(") - (char == ")")
                end += 1
        if end > start and re.match(r'\s*(?:["\'][^\n]*?["\']\s*)?\)', text[end:]):
            yield text[start:end].strip("<>")
    for match in re.finditer(reference, text, re.M):
        yield match.group(1).strip("<>")


def relative_target(destination):
    parsed = urlsplit(destination)
    if parsed.scheme or parsed.netloc or not parsed.path:
        return None
    return unquote(re.sub(r"\\([() ])", r"\1", parsed.path))


class DocsLinksTest(unittest.TestCase):
    def test_required_docs_exist(self):
        for relative in REQUIRED:
            with self.subTest(path=relative):
                self.assertTrue((ROOT / relative).is_file(), "missing contributor doc: " + relative)

    def test_relative_links_resolve(self):
        broken = []
        for relative in markdown_paths():
            path = ROOT / relative
            self.assertTrue(path.is_file(), "tracked Markdown was removed: " + relative)
            for destination in destinations(path.read_text(encoding="utf-8")):
                target = relative_target(destination)
                if target is not None and not (path.parent / target).exists():
                    broken.append(relative + " -> " + destination)
        self.assertEqual(broken, [], "Broken relative Markdown links:\n" + "\n".join(broken))

    def test_link_syntax_and_external_targets(self):
        sample = ('[local](../AGENTS.md#rules) ![image](image%20one.png)\n'
                  '[named][policy]\n[policy]: <SECURITY.md> "title"\n'
                  '[web](https://example.org) [mail](mailto:security@example.org)\n'
                  '`[code](missing.md)`\n```sh\n[example](missing.md)\n```\n')
        self.assertEqual(list(destinations(sample)),
                         ["../AGENTS.md#rules", "image%20one.png",
                          "https://example.org", "mailto:security@example.org", "SECURITY.md"])
        self.assertEqual(relative_target("../AGENTS.md#rules"), "../AGENTS.md")
        self.assertEqual(relative_target("image%20one.png"), "image one.png")
        for external in ("https://example.org", "mailto:security@example.org", "#rules"):
            self.assertIsNone(relative_target(external))

    def test_parenthesized_escaped_and_titled_destinations(self):
        sample = ('[nested](docs/topic(one).md#section)\n'
                  '[escaped](docs/topic\\(two\\).md)\n'
                  '[spaced](<docs/topic three.md> "Title")\n'
                  '[reference]: docs/topic(four).md "Title"\n')
        self.assertEqual(list(destinations(sample)), [
            "docs/topic(one).md#section", r"docs/topic\(two\).md",
            "docs/topic three.md", "docs/topic(four).md"])


if __name__ == "__main__":
    unittest.main()
